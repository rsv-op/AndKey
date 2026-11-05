#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
model_toolkit.py — BuildGenie file operations toolkit
=====================================================

Implements the four required APK-support functions, identically on
Termux (Android), Windows Shell (PowerShell/CMD), and desktop Python:

  LOAD      Validate a file exists and report its identity (size, sha256, format)
  INSPECT   Parse .gguf / .litert / .ablitert (and .tflite) model files
  GENERATE  Produce files: JSON inspection reports, directory manifests,
            and a minimal valid GGUF stub for pipeline testing
  REPLACE   Atomically swap a file with backup + verification, with restore

Formats are detected by CONTENT (magic bytes), not by file extension:
  * GGUF              magic 'GGUF'  (llama.cpp model container)
  * LiteRT / TFLite   FlatBuffer with plausible root offset, optional
                      'TFL3' identifier at byte 4 — covers .litert,
                      .ablitert (Android-bundled LiteRT) and .tflite
  * ZIP               'PK' magic (APK/AAB containers)
  * anything else     header + hash report only

Standard library only — no pip installs. Python 3.8+.
Works offline; zero network calls (matches BuildGenie's zero-network policy).

Exit codes: 0 = OK, 1 = file not found, 2 = parse/validation error,
            3 = usage error.
"""

import argparse
import glob
import hashlib
import json
import os
import shutil
import struct
import sys
import tempfile
import time

TOOL_VERSION = "1.0.0"
LITERT_EXTENSIONS = (".litert", ".ablitert", ".tflite", ".lite")
READ_CAP = 4 * 1024 * 1024  # 4 MiB cap for FlatBuffer structure parsing


class ToolkitError(Exception):
    """Operational error with a process exit code."""

    def __init__(self, message, code=2):
        super().__init__(message)
        self.code = code


# ---------------------------------------------------------------------------
# Common helpers
# ---------------------------------------------------------------------------

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def fmt_size(n):
    if n < 1024:
        return "%d bytes" % n
    for unit in ("KiB", "MiB", "GiB", "TiB"):
        n /= 1024.0
        if n < 1024:
            return "%.1f %s" % (n, unit)
    return "%.1f PiB" % n


def detect_format(path):
    """Detect container format from magic bytes. Returns 'gguf' | 'zip' |
    'litert' | 'unknown'. Raises ToolkitError if unreadable."""
    if not os.path.isfile(path):
        raise ToolkitError("file not found: %s" % path, code=1)
    size = os.path.getsize(path)
    if size < 4:
        return "unknown"
    with open(path, "rb") as f:
        head = f.read(16)
    if head[:4] == b"GGUF":
        return "gguf"
    if head[:2] == b"PK":
        return "zip"
    # FlatBuffer heuristic: u32 at offset 0 = root table offset, must point
    # inside the file and past the 8-byte header (offset+optional identifier).
    if size >= 8:
        root = struct.unpack_from("<I", head, 0)[0]
        if 8 <= root < size:
            return "litert"
    return "unknown"


# ---------------------------------------------------------------------------
# INSPECT — GGUF
# ---------------------------------------------------------------------------

# GGUF metadata value types (spec: https://github.com/ggml-org/ggml/blob/master/docs/gguf.md)
GGUF_TYPES = {
    0: ("uint8", "B", 1),
    1: ("int8", "b", 1),
    2: ("uint16", "H", 2),
    3: ("int16", "h", 2),
    4: ("uint32", "I", 4),
    5: ("int32", "i", 4),
    6: ("float32", "f", 4),
    7: ("bool", "B", 1),
    8: ("string", None, None),
    9: ("array", None, None),
    10: ("uint64", "Q", 8),
    11: ("int64", "q", 8),
    12: ("float64", "d", 8),
}


def _gguf_read(f, n):
    data = f.read(n)
    if len(data) != n:
        raise ToolkitError("truncated GGUF file (wanted %d bytes, got %d)" % (n, len(data)))
    return data


def _gguf_value(f, vtype):
    """Read one GGUF metadata value of the given type, returning
    (display_string, raw_python_value)."""
    if vtype not in GGUF_TYPES:
        raise ToolkitError("unknown GGUF value type %d" % vtype)
    name, fmt, size = GGUF_TYPES[vtype]
    if name == "string":
        (length,) = struct.unpack("<Q", _gguf_read(f, 8))
        if length > 64 * 1024 * 1024:
            raise ToolkitError("GGUF string length %d implausible" % length)
        raw = _gguf_read(f, length)
        return raw.decode("utf-8", "replace"), raw.decode("utf-8", "replace")
    if name == "array":
        (elem_type,) = struct.unpack("<I", _gguf_read(f, 4))
        (count,) = struct.unpack("<Q", _gguf_read(f, 8))
        if elem_type not in GGUF_TYPES or GGUF_TYPES[elem_type][0] == "array":
            raise ToolkitError("unsupported GGUF array element type %d" % elem_type)
        _, efmt, esize = GGUF_TYPES[elem_type]
        elems = []
        display = []
        for i in range(count):
            if elem_type == 8:  # string elements
                (ln,) = struct.unpack("<Q", _gguf_read(f, 8))
                s = _gguf_read(f, ln).decode("utf-8", "replace")
                elems.append(s)
                if i < 8:
                    display.append(s)
            else:
                (v,) = struct.unpack("<" + efmt, _gguf_read(f, esize))
                elems.append(v)
                if i < 8:
                    display.append(v)
        shown = ", ".join(repr(x) for x in display)
        if count > 8:
            shown += ", ... (%d total)" % count
        return "[%s]" % shown, elems
    if name == "bool":
        (v,) = struct.unpack("<B", _gguf_read(f, 1))
        b = bool(v)
        return str(b), b
    (v,) = struct.unpack("<" + fmt, _gguf_read(f, size))
    return repr(v), v


def inspect_gguf(path, max_kvs=32):
    """Parse the GGUF header + first metadata KV pairs."""
    info = {"format": "gguf"}
    with open(path, "rb") as f:
        magic = _gguf_read(f, 4)
        if magic != b"GGUF":
            raise ToolkitError("not a GGUF file (magic=%r)" % magic)
        (version,) = struct.unpack("<I", _gguf_read(f, 4))
        (tensor_count,) = struct.unpack("<Q", _gguf_read(f, 8))
        (kv_count,) = struct.unpack("<Q", _gguf_read(f, 8))
        info["gguf_version"] = version
        info["tensor_count"] = tensor_count
        info["metadata_kv_count"] = kv_count

        kvs = []
        parse_error = None
        to_read = min(kv_count, max_kvs)
        try:
            for _ in range(to_read):
                (klen,) = struct.unpack("<Q", _gguf_read(f, 8))
                if klen > 1024 * 1024:
                    raise ToolkitError("GGUF key length %d implausible" % klen)
                key = _gguf_read(f, klen).decode("utf-8", "replace")
                (vtype,) = struct.unpack("<I", _gguf_read(f, 4))
                display, _raw = _gguf_value(f, vtype)
                kvs.append({
                    "key": key,
                    "type": GGUF_TYPES.get(vtype, ("type%d" % vtype,))[0],
                    "value": display,
                })
        except ToolkitError as e:
            parse_error = str(e)
        info["metadata_sample"] = kvs
        info["metadata_sampled"] = len(kvs)
        if parse_error:
            info["parse_error"] = parse_error
        elif kv_count > max_kvs:
            info["metadata_note"] = "%d of %d KV pairs shown (--max-kvs to change)" % (
                len(kvs), kv_count)
    return info


# ---------------------------------------------------------------------------
# INSPECT — LiteRT / TFLite FlatBuffer
# ---------------------------------------------------------------------------

def _fb_field(buf, table, idx):
    """Return absolute position of field `idx` in a FlatBuffer table, or None."""
    if table is None or table < 0 or table + 4 > len(buf):
        return None
    soff = struct.unpack_from("<i", buf, table)[0]
    vt = table - soff
    if vt < 0 or vt + 4 > len(buf):
        return None
    vt_size = struct.unpack_from("<H", buf, vt)[0]
    entry = 4 + 2 * idx
    if entry + 2 > vt_size:
        return None
    off = struct.unpack_from("<H", buf, vt + entry)[0]
    if off == 0:
        return None
    return table + off


def _fb_i32(buf, pos):
    if pos is None or pos + 4 > len(buf):
        return None
    return struct.unpack_from("<i", buf, pos)[0]


def _fb_string(buf, pos):
    if pos is None or pos + 4 > len(buf):
        return None
    uoff = struct.unpack_from("<I", buf, pos)[0]
    sp = pos + uoff
    if sp + 4 > len(buf):
        return None
    ln = struct.unpack_from("<I", buf, sp)[0]
    if ln > 16 * 1024 * 1024 or sp + 4 + ln > len(buf):
        return None
    return buf[sp + 4:sp + 4 + ln].decode("utf-8", "replace")


def _fb_vector_len(buf, pos):
    if pos is None or pos + 4 > len(buf):
        return None
    uoff = struct.unpack_from("<I", buf, pos)[0]
    vp = pos + uoff
    if vp + 4 > len(buf):
        return None
    return struct.unpack_from("<I", buf, vp)[0]


def inspect_litert(path):
    """Parse a LiteRT / TFLite FlatBuffer: file identifier + Model table
    fields (version, description, subgraph/operator/buffer counts)."""
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        buf = f.read(min(size, READ_CAP))

    info = {"format": "litert", "file_size": size}
    if len(buf) < 8:
        raise ToolkitError("file too small to be a LiteRT FlatBuffer")
    identifier = buf[4:8]
    try:
        ident = identifier.decode("ascii")
        if not ident.isprintable():
            ident = repr(identifier)
    except UnicodeDecodeError:
        ident = repr(identifier)
    info["identifier"] = ident

    root = struct.unpack_from("<I", buf, 0)[0]
    info["root_offset"] = root
    if root >= size:
        raise ToolkitError("FlatBuffer root offset %d beyond file size %d" % (root, size))
    if root >= len(buf):
        info["parse_error"] = "root table beyond %d MiB read cap" % (READ_CAP // (1024 * 1024))
        return info

    # Model table fields: 0=version(int32) 1=operator_codes(vec)
    #                     2=subgraphs(vec) 3=description(str) 4=buffers(vec)
    info["model_version"] = _fb_i32(buf, _fb_field(buf, root, 0))
    info["operator_code_count"] = _fb_vector_len(buf, _fb_field(buf, root, 1))
    info["subgraph_count"] = _fb_vector_len(buf, _fb_field(buf, root, 2))
    info["description"] = _fb_string(buf, _fb_field(buf, root, 3))
    info["buffer_count"] = _fb_vector_len(buf, _fb_field(buf, root, 4))

    if ident not in ("TFL3",) and info["model_version"] is None:
        raise ToolkitError(
            "FlatBuffer root parsed but no LiteRT Model fields found "
            "(identifier=%r) — not a LiteRT/TFLite model?" % ident)
    return info


# ---------------------------------------------------------------------------
# LOAD / INSPECT command implementations
# ---------------------------------------------------------------------------

def cmd_load(args):
    path = args.path
    if not os.path.isfile(path):
        raise ToolkitError("file not found: %s" % path, code=1)
    size = os.path.getsize(path)
    fmt = detect_format(path)
    result = {
        "action": "load",
        "ok": True,
        "path": os.path.abspath(path),
        "size_bytes": size,
        "size_human": fmt_size(size),
        "sha256": sha256_file(path),
        "format": fmt,
        "tool_version": TOOL_VERSION,
    }
    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print("LOAD OK")
        print("  path    : %s" % result["path"])
        print("  size    : %d bytes (%s)" % (size, result["size_human"]))
        print("  sha256  : %s" % result["sha256"])
        print("  format  : %s" % fmt)
    return result


def _inspect_common(path):
    if not os.path.isfile(path):
        raise ToolkitError("file not found: %s" % path, code=1)
    size = os.path.getsize(path)
    fmt = detect_format(path)
    result = {
        "action": "inspect",
        "ok": True,
        "path": os.path.abspath(path),
        "size_bytes": size,
        "size_human": fmt_size(size),
        "sha256": sha256_file(path),
        "format": fmt,
        "tool_version": TOOL_VERSION,
    }
    if fmt == "gguf":
        result.update(inspect_gguf(path, max_kvs=args_max_kvs_default()))
    elif fmt == "litert":
        ext = os.path.splitext(path)[1].lower()
        result["extension"] = ext
        if ext in LITERT_EXTENSIONS:
            result["extension_family"] = "LiteRT"
        try:
            result.update(inspect_litert(path))
        except ToolkitError as e:
            if ext in LITERT_EXTENSIONS:
                raise
            result["format"] = "flatbuffer?"
            result["parse_error"] = str(e)
    elif fmt == "zip":
        result["note"] = "ZIP container (APK/AAB/JAR) — use unzip -l to list entries"
    else:
        with open(path, "rb") as f:
            head = f.read(16)
        result["head_hex"] = head.hex()
        result["note"] = "unrecognized container — reported header only"
    return result


_MAX_KVS = 32  # module default; overridden by --max-kvs


def args_max_kvs_default():
    return _MAX_KVS


def cmd_inspect(args):
    global _MAX_KVS
    _MAX_KVS = args.max_kvs
    result = _inspect_common(args.path)
    if args.json:
        print(json.dumps(result, indent=2))
        return result

    print("INSPECT OK")
    print("  path     : %s" % result["path"])
    print("  format   : %s" % result["format"])
    print("  size     : %d bytes (%s)" % (result["size_bytes"], result["size_human"]))
    print("  sha256   : %s" % result["sha256"])
    if result["format"] == "gguf":
        print("  --- GGUF header ---")
        print("  version  : %s" % result.get("gguf_version"))
        print("  tensors  : %s" % result.get("tensor_count"))
        print("  metadata : %s KV pair(s)" % result.get("metadata_kv_count"))
        for kv in result.get("metadata_sample", []):
            print("    %s = %s" % (kv["key"], kv["value"]))
        if "metadata_note" in result:
            print("  note     : %s" % result["metadata_note"])
        if "parse_error" in result:
            print("  ERROR    : %s" % result["parse_error"])
    elif result["format"] == "litert":
        print("  --- LiteRT / TFLite FlatBuffer ---")
        print("  identifier  : %s" % result.get("identifier"))
        print("  model ver   : %s" % result.get("model_version"))
        print("  description : %s" % result.get("description"))
        print("  subgraphs   : %s" % result.get("subgraph_count"))
        print("  op codes    : %s" % result.get("operator_code_count"))
        print("  buffers     : %s" % result.get("buffer_count"))
        if "parse_error" in result:
            print("  ERROR       : %s" % result["parse_error"])
    else:
        for k in ("note", "head_hex", "extension", "parse_error"):
            if k in result and result[k] is not None:
                print("  %-10s: %s" % (k, result[k]))
    return result


# ---------------------------------------------------------------------------
# GENERATE
# ---------------------------------------------------------------------------

def _write_json(data, out_path):
    text = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    if out_path:
        d = os.path.dirname(os.path.abspath(out_path))
        if d and not os.path.isdir(d):
            os.makedirs(d, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=d or ".", prefix=".gen-", suffix=".tmp")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(text)
        os.replace(tmp, out_path)
        return out_path
    sys.stdout.write(text)
    return None


def gen_report(args):
    """GENERATE: JSON inspection report for one model file."""
    result = _inspect_common(args.model)
    result["action"] = "generate-report"
    result["generated_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    out = _write_json(result, args.out)
    if out and not args.json:
        print("GENERATE OK  report -> %s" % out)
    return result


def gen_stub_gguf(args):
    """GENERATE: minimal structurally-valid GGUF file (0 tensors, 3 KVs)."""
    def kv(key, value):
        k = key.encode("utf-8")
        v = value.encode("utf-8")
        return (struct.pack("<Q", len(k)) + k +
                struct.pack("<I", 8) +              # type 8 = string
                struct.pack("<Q", len(v)) + v)

    payload = b"GGUF"
    payload += struct.pack("<I", 3)                 # GGUF version 3
    payload += struct.pack("<Q", 0)                 # tensor count
    payload += struct.pack("<Q", 3)                 # metadata KV count
    payload += kv("general.architecture", args.arch)
    payload += kv("general.name", args.name)
    payload += kv("general.quantization", "Q4_0")

    out = os.path.abspath(args.out)
    d = os.path.dirname(out)
    if d and not os.path.isdir(d):
        os.makedirs(d, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=d or ".", prefix=".stub-", suffix=".tmp")
    with os.fdopen(fd, "wb") as f:
        f.write(payload)
    os.replace(tmp, out)

    # Self-verify: the stub must pass our own inspector
    check = inspect_gguf(out)
    if check["metadata_kv_count"] != 3:
        raise ToolkitError("stub self-verification failed")
    summary = {
        "action": "generate-stub-gguf",
        "ok": True,
        "path": out,
        "size_bytes": os.path.getsize(out),
        "sha256": sha256_file(out),
        "self_verified": True,
        "tool_version": TOOL_VERSION,
    }
    if args.json:
        print(json.dumps(summary, indent=2))
    else:
        print("GENERATE OK  stub-gguf -> %s" % out)
        print("  size     : %d bytes" % summary["size_bytes"])
        print("  sha256   : %s" % summary["sha256"])
        print("  verified : inspector parsed version=%s tensors=%s kvs=%s" % (
            check["gguf_version"], check["tensor_count"], check["metadata_kv_count"]))
    return summary


def gen_manifest(args):
    """GENERATE: JSON manifest (name/size/sha256/format) for a directory."""
    root = os.path.abspath(args.dir)
    if not os.path.isdir(root):
        raise ToolkitError("directory not found: %s" % root, code=1)
    entries = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if not d.startswith(".")]
        for fn in sorted(filenames):
            if fn.startswith("."):
                continue
            p = os.path.join(dirpath, fn)
            if not os.path.isfile(p):
                continue
            try:
                fmt = detect_format(p)
                entries.append({
                    "relpath": os.path.relpath(p, root).replace(os.sep, "/"),
                    "size_bytes": os.path.getsize(p),
                    "sha256": sha256_file(p),
                    "format": fmt,
                })
            except (OSError, ToolkitError) as e:
                entries.append({"relpath": os.path.relpath(p, root).replace(os.sep, "/"),
                                "error": str(e)})
    manifest = {
        "action": "generate-manifest",
        "ok": True,
        "root": root,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "file_count": len(entries),
        "files": entries,
        "tool_version": TOOL_VERSION,
    }
    out = _write_json(manifest, args.out)
    if out and not args.json:
        print("GENERATE OK  manifest (%d files) -> %s" % (len(entries), out))
    elif not out and not args.json:
        print("GENERATE OK  manifest (%d files) -> stdout" % len(entries), file=sys.stderr)
    return manifest


# ---------------------------------------------------------------------------
# REPLACE
# ---------------------------------------------------------------------------

def _backup_name(path):
    return "%s.bak-%s" % (path, time.strftime("%Y%m%d-%H%M%S"))


def _atomic_overwrite(dest, src_bytes):
    d = os.path.dirname(os.path.abspath(dest)) or "."
    fd, tmp = tempfile.mkstemp(dir=d, prefix=".repl-", suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(src_bytes)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, dest)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def cmd_replace(args):
    if args.restore:
        return _cmd_restore(args)
    target, source = args.target, args.source
    if not target or not source:
        raise ToolkitError("usage: replace TARGET SOURCE  (or: replace --restore TARGET)", code=3)
    if not os.path.isfile(source):
        raise ToolkitError("source not found: %s" % source, code=1)
    if not os.path.isfile(target):
        raise ToolkitError("target not found: %s (use cp/push to create new files)" % target, code=1)
    if os.path.abspath(target) == os.path.abspath(source):
        raise ToolkitError("target and source are the same file", code=3)

    src_fmt = detect_format(source)
    tgt_fmt = detect_format(target)
    if src_fmt != tgt_fmt and not args.force:
        raise ToolkitError(
            "format mismatch: target=%s source=%s (pass --force to override)" % (
                tgt_fmt, src_fmt))

    # Source must parse cleanly (unless --force)
    if not args.force:
        if src_fmt == "gguf":
            inspect_gguf(source)
        elif src_fmt == "litert":
            inspect_litert(source)

    with open(source, "rb") as f:
        src_bytes = f.read()

    result = {
        "action": "replace",
        "ok": True,
        "target": os.path.abspath(target),
        "source": os.path.abspath(source),
        "format": src_fmt,
        "target_sha256_before": sha256_file(target),
        "source_sha256": hashlib.sha256(src_bytes).hexdigest(),
        "backup": None,
        "tool_version": TOOL_VERSION,
    }

    if not args.no_backup:
        bak = _backup_name(target)
        shutil.copy2(target, bak)
        result["backup"] = bak

    _atomic_overwrite(target, src_bytes)
    result["target_sha256_after"] = sha256_file(target)
    result["verified"] = (result["target_sha256_after"] == result["source_sha256"])
    if not result["verified"]:
        raise ToolkitError("post-replace hash mismatch — target corrupted", code=2)

    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print("REPLACE OK")
        print("  target : %s" % result["target"])
        print("  before : %s" % result["target_sha256_before"])
        print("  after  : %s" % result["target_sha256_after"])
        print("  backup : %s" % (result["backup"] or "(none)"))
        print("  verified: hashes match")
    return result


def _cmd_restore(args):
    target = args.target
    if not target:
        raise ToolkitError("usage: replace --restore TARGET", code=3)
    if not os.path.isfile(target):
        raise ToolkitError("target not found: %s" % target, code=1)
    backups = sorted(glob.glob(glob.escape(target) + ".bak-*"))
    if not backups:
        raise ToolkitError("no backups found for %s (pattern %s.bak-*)" % (target, target))
    latest = backups[-1]
    with open(latest, "rb") as f:
        data = f.read()
    pre = _backup_name(target + ".pre-restore")
    shutil.copy2(target, pre)
    _atomic_overwrite(target, data)
    result = {
        "action": "restore",
        "ok": True,
        "target": os.path.abspath(target),
        "restored_from": latest,
        "pre_restore_backup": pre,
        "sha256_after": sha256_file(target),
        "available_backups": len(backups),
        "tool_version": TOOL_VERSION,
    }
    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print("RESTORE OK")
        print("  target : %s" % result["target"])
        print("  from   : %s" % latest)
        print("  saved current as : %s" % pre)
        print("  sha256 : %s" % result["sha256_after"])
    return result


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser():
    p = argparse.ArgumentParser(
        prog="model_toolkit",
        description="BuildGenie file toolkit: load / inspect / generate / replace "
                    "for .gguf, .litert, .ablitert model files. Stdlib only, offline.")
    p.add_argument("--version", action="version", version="model_toolkit " + TOOL_VERSION)
    sub = p.add_subparsers(dest="cmd", required=True)

    pl = sub.add_parser("load", help="Validate a file exists; report size, sha256, format")
    pl.add_argument("path")
    pl.add_argument("--json", action="store_true")
    pl.set_defaults(func=cmd_load)

    pi = sub.add_parser("inspect", help="Parse .gguf / .litert / .ablitert structure")
    pi.add_argument("path")
    pi.add_argument("--json", action="store_true")
    pi.add_argument("--max-kvs", type=int, default=32,
                    help="max GGUF metadata KV pairs to sample (default 32)")
    pi.set_defaults(func=cmd_inspect)

    pg = sub.add_parser("generate", help="Generate files (report / stub-gguf / manifest)")
    gsub = pg.add_subparsers(dest="gen_cmd", required=True)

    gr = gsub.add_parser("report", help="JSON inspection report for a model file")
    gr.add_argument("model")
    gr.add_argument("-o", "--out", default=None, help="output path (default: stdout)")
    gr.add_argument("--json", action="store_true")
    gr.set_defaults(func=gen_report)

    gs = gsub.add_parser("stub-gguf", help="Write a minimal valid GGUF test file")
    gs.add_argument("out")
    gs.add_argument("--name", default="stub-model")
    gs.add_argument("--arch", default="test")
    gs.add_argument("--json", action="store_true")
    gs.set_defaults(func=gen_stub_gguf)

    gm = gsub.add_parser("manifest", help="JSON manifest (name/size/sha256/format) of a directory")
    gm.add_argument("dir")
    gm.add_argument("-o", "--out", default=None, help="output path (default: stdout)")
    gm.add_argument("--json", action="store_true")
    gm.set_defaults(func=gen_manifest)

    pr = sub.add_parser("replace", help="Atomically replace TARGET with SOURCE (backup + verify)")
    pr.add_argument("target", nargs="?")
    pr.add_argument("source", nargs="?")
    pr.add_argument("--restore", action="store_true",
                    help="restore TARGET from its latest .bak-* file")
    pr.add_argument("--no-backup", action="store_true", help="skip the .bak-* copy")
    pr.add_argument("--force", action="store_true",
                    help="skip format-match and parse validation")
    pr.add_argument("--json", action="store_true")
    pr.set_defaults(func=cmd_replace)

    return p


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        args.func(args)
        return 0
    except ToolkitError as e:
        print("ERROR: %s" % e, file=sys.stderr)
        return e.code
    except BrokenPipeError:
        return 0
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
