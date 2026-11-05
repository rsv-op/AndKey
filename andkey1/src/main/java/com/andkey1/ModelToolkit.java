package com.andkey1;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Native (Java) port of {@code tools/model_toolkit.py}.
 *
 * <p>Implements the same four functions as the Python toolkit, with the same
 * output wording, so the bundled guide stays accurate on a phone that has no
 * Python interpreter:</p>
 *
 * <pre>
 *   ① LOAD      identity of a file: size, sha256, detected format
 *   ② INSPECT   .gguf / .litert / .ablitert / .tflite headers (by content, not extension)
 *   ③ GENERATE  report.json · manifest.json · stub.gguf
 *   ④ REPLACE   atomic swap with .bak-&lt;timestamp&gt; backup, hash verify, restore
 * </pre>
 *
 * <p>Standard library of the platform only (java.* plus org.json) — no network,
 * no third-party code, matching AndKey1's zero-network policy. Exit-code
 * equivalents are carried by {@link ToolkitException#code}: 1 = not found,
 * 2 = parse/validation failure, 3 = usage.</p>
 */
public final class ModelToolkit {

    public static final String TOOL_VERSION = "1.0.0";

    /** 4 MiB cap for FlatBuffer structure parsing (same as the Python READ_CAP). */
    private static final int READ_CAP = 4 * 1024 * 1024;

    private static final String[] GGUF_TYPE_NAMES = {
            "uint8", "int8", "uint16", "int16", "uint32", "int32",
            "float32", "bool", "string", "array", "uint64", "int64", "float64"
    };

    private ModelToolkit() {
    }

    /** Operational error carrying a Python-toolkit-compatible exit code. */
    public static final class ToolkitException extends Exception {
        public final int code;

        public ToolkitException(String message) {
            this(message, 2);
        }

        public ToolkitException(String message, int code) {
            super(message);
            this.code = code;
        }
    }

    /** A result as both machine-readable JSON and the human banner text. */
    public static final class Result {
        public final JSONObject json;
        public final String human;

        public Result(JSONObject json, String human) {
            this.json = json;
            this.human = human;
        }
    }

    // -----------------------------------------------------------------------
    // Common helpers
    // -----------------------------------------------------------------------

    public static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable: " + e.getMessage());
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        byte[] sha = digest.digest();
        StringBuilder sb = new StringBuilder(sha.length * 2);
        for (byte b : sha) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static String fmtSize(long n) {
        if (n < 1024) {
            return n + " bytes";
        }
        double v = n;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        for (String unit : units) {
            v /= 1024.0;
            if (v < 1024) {
                return String.format(Locale.US, "%.1f %s", v, unit);
            }
        }
        return String.format(Locale.US, "%.1f PiB", v / 1024.0);
    }

    private static void requireFile(File f) throws ToolkitException {
        if (f == null || !f.isFile()) {
            throw new ToolkitException("file not found: " + (f == null ? "null" : f.getAbsolutePath()), 1);
        }
    }

    /** Detect container format from magic bytes: gguf | zip | litert | unknown. */
    public static String detectFormat(File file) throws ToolkitException {
        requireFile(file);
        long size = file.length();
        if (size < 4) {
            return "unknown";
        }
        byte[] head;
        try {
            head = readHead(file, 16);
        } catch (IOException e) {
            throw new ToolkitException("unreadable: " + e.getMessage(), 2);
        }
        if (head.length >= 4 && head[0] == 'G' && head[1] == 'G' && head[2] == 'U' && head[3] == 'F') {
            return "gguf";
        }
        if (head.length >= 2 && head[0] == 'P' && head[1] == 'K') {
            return "zip";
        }
        if (size >= 8 && head.length >= 4) {
            long root = u32(head, 0);
            if (root >= 8 && root < size) {
                return "litert";
            }
        }
        return "unknown";
    }

    private static byte[] readHead(File file, int n) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[n];
            int total = 0;
            while (total < n) {
                int r = in.read(buf, total, n - total);
                if (r <= 0) {
                    break;
                }
                total += r;
            }
            if (total == n) {
                return buf;
            }
            byte[] trimmed = new byte[total];
            System.arraycopy(buf, 0, trimmed, 0, total);
            return trimmed;
        }
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] b, int off) {
        return (int) u32(b, off);
    }

    private static String iso(byte[] b, int off, int len) {
        return new String(b, off, len, StandardCharsets.ISO_8859_1);
    }

    private static String hex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(len, b.length);
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.US, "%02x", b[i]));
        }
        return sb.toString();
    }

    public static String timestamp() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String backupStamp() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Atomic streamed copy: temp file in the destination directory, fsync, rename.
     * Used for model files so multi-GB weights are never held in memory.
     */
    private static void atomicCopy(File src, File dest, String prefix) throws IOException {
        File dir = dest.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create directory: " + dir);
        }
        File tmp = File.createTempFile(prefix, ".tmp", dir);
        boolean ok = false;
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.getFD().sync();
            ok = true;
        } finally {
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            if (!tmp.renameTo(dest)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException("atomic rename failed for " + dest);
            }
        }
    }

    /** Atomic write: temp file in the same directory, fsync, rename. */
    private static void atomicWrite(File dest, byte[] data, String prefix) throws IOException {
        File dir = dest.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create directory: " + dir);
        }
        File tmp = File.createTempFile(prefix, ".tmp", dir);
        boolean ok = false;
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
            ok = true;
        } finally {
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            if (!tmp.renameTo(dest)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException("atomic rename failed for " + dest);
            }
        }
    }

    private static File writeJson(JSONObject data, File out) throws IOException {
        if (out == null) {
            return null;
        }
        String text;
        try {
            text = data.toString(2) + "\n";
        } catch (JSONException e) {
            throw new IOException("cannot serialize JSON: " + e.getMessage());
        }
        atomicWrite(out, text.getBytes(StandardCharsets.UTF_8), ".gen-");
        return out;
    }

    // -----------------------------------------------------------------------
    // ② INSPECT — GGUF
    // -----------------------------------------------------------------------

    private static byte[] readFully(RandomAccessFile raf, int n) throws IOException, ToolkitException {
        byte[] b = new byte[n];
        int read = 0;
        while (read < n) {
            int r = raf.read(b, read, n - read);
            if (r <= 0) {
                throw new ToolkitException("truncated GGUF file (wanted " + n + " bytes, got " + read + ")");
            }
            read += r;
        }
        return b;
    }

    private static long readU64(RandomAccessFile raf) throws IOException, ToolkitException {
        byte[] b = readFully(raf, 8);
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[i] & 0xFFL);
        }
        return v;
    }

    private static int readU32(RandomAccessFile raf) throws IOException, ToolkitException {
        return (int) u32(readFully(raf, 4), 0);
    }

    private static String readGgufString(RandomAccessFile raf) throws IOException, ToolkitException {
        long len = readU64(raf);
        if (len > 64L * 1024 * 1024) {
            throw new ToolkitException("GGUF string length " + len + " implausible");
        }
        if (len == 0) {
            return "";
        }
        return new String(readFully(raf, (int) len), StandardCharsets.UTF_8);
    }

    private static String ggufTypeName(int type) {
        if (type >= 0 && type < GGUF_TYPE_NAMES.length) {
            return GGUF_TYPE_NAMES[type];
        }
        return "type" + type;
    }

    /** Reads one GGUF metadata value; returns the display string. */
    private static String readGgufValue(RandomAccessFile raf, int vtype) throws IOException, ToolkitException {
        if (vtype < 0 || vtype >= GGUF_TYPE_NAMES.length) {
            throw new ToolkitException("unknown GGUF value type " + vtype);
        }
        String name = GGUF_TYPE_NAMES[vtype];
        switch (name) {
            case "string":
                return readGgufString(raf);
            case "bool":
                return String.valueOf(readFully(raf, 1)[0] != 0);
            case "array": {
                int elemType = readU32(raf);
                long count = readU64(raf);
                if (elemType < 0 || elemType >= GGUF_TYPE_NAMES.length
                        || "array".equals(ggufTypeName(elemType))) {
                    throw new ToolkitException("unsupported GGUF array element type " + elemType);
                }
                StringBuilder shown = new StringBuilder("[");
                for (long i = 0; i < count; i++) {
                    String value;
                    if ("string".equals(ggufTypeName(elemType))) {
                        value = readGgufString(raf);
                    } else {
                        value = readScalarDisplay(raf, ggufTypeName(elemType));
                    }
                    if (i < 8) {
                        if (i > 0) {
                            shown.append(", ");
                        }
                        shown.append(value);
                    }
                }
                if (count > 8) {
                    shown.append(", ... (").append(count).append(" total)");
                }
                return shown.append(']').toString();
            }
            default:
                return readScalarDisplay(raf, name);
        }
    }

    private static String readScalarDisplay(RandomAccessFile raf, String type) throws IOException, ToolkitException {
        switch (type) {
            case "uint8":
                return String.valueOf(readFully(raf, 1)[0] & 0xFF);
            case "int8":
                return String.valueOf(readFully(raf, 1)[0]);
            case "uint16": {
                byte[] b = readFully(raf, 2);
                return String.valueOf(u16(b, 0));
            }
            case "int16": {
                byte[] b = readFully(raf, 2);
                return String.valueOf((short) u16(b, 0));
            }
            case "uint32": {
                long v = u32(readFully(raf, 4), 0);
                return String.valueOf(v);
            }
            case "int32":
                return String.valueOf((int) u32(readFully(raf, 4), 0));
            case "float32": {
                int bits = (int) u32(readFully(raf, 4), 0);
                return String.valueOf(Float.intBitsToFloat(bits));
            }
            case "uint64": {
                long v = readU64(raf);
                return v >= 0 ? String.valueOf(v) : Long.toUnsignedString(v);
            }
            case "int64":
                return String.valueOf(readU64(raf));
            case "float64": {
                long bits = 0;
                byte[] b = readFully(raf, 8);
                for (int i = 7; i >= 0; i--) {
                    bits = (bits << 8) | (b[i] & 0xFFL);
                }
                return String.valueOf(Double.longBitsToDouble(bits));
            }
            default:
                throw new ToolkitException("unsupported GGUF scalar type " + type);
        }
    }

    /** Parse the GGUF header and up to {@code maxKvs} metadata key/value pairs. */
    public static JSONObject inspectGguf(File file, int maxKvs) throws ToolkitException {
        requireFile(file);
        JSONObject info = new JSONObject();
        put(info, "format", "gguf");
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            String magic = iso(readFully(raf, 4), 0, 4);
            if (!"GGUF".equals(magic)) {
                throw new ToolkitException("not a GGUF file (magic=" + magic + ")");
            }
            int version = readU32(raf);
            long tensorCount = readU64(raf);
            long kvCount = readU64(raf);
            put(info, "gguf_version", version);
            put(info, "tensor_count", tensorCount);
            put(info, "metadata_kv_count", kvCount);

            JSONArray kvs = new JSONArray();
            String parseError = null;
            int toRead = (int) Math.min(kvCount, Math.max(0, maxKvs));
            try {
                for (int i = 0; i < toRead; i++) {
                    long klen = readU64(raf);
                    if (klen > 1024L * 1024) {
                        throw new ToolkitException("GGUF key length " + klen + " implausible");
                    }
                    String key = new String(readFully(raf, (int) klen), StandardCharsets.UTF_8);
                    int vtype = readU32(raf);
                    String display = readGgufValue(raf, vtype);
                    JSONObject kv = new JSONObject();
                    put(kv, "key", key);
                    put(kv, "type", ggufTypeName(vtype));
                    put(kv, "value", display);
                    kvs.put(kv);
                }
            } catch (ToolkitException e) {
                parseError = e.getMessage();
            }
            put(info, "metadata_sample", kvs);
            put(info, "metadata_sampled", kvs.length());
            if (parseError != null) {
                put(info, "parse_error", parseError);
            } else if (kvCount > maxKvs) {
                put(info, "metadata_note", kvs.length() + " of " + kvCount
                        + " KV pairs shown (max-kvs limit)");
            }
        } catch (IOException e) {
            throw new ToolkitException("truncated or unreadable GGUF: " + e.getMessage());
        }
        return info;
    }

    // -----------------------------------------------------------------------
    // ② INSPECT — LiteRT / TFLite FlatBuffer
    // -----------------------------------------------------------------------

    /** Field position inside a FlatBuffer table, or -1 when absent. */
    private static int fbField(byte[] buf, int table, int idx) {
        if (table < 0 || table + 4 > buf.length) {
            return -1;
        }
        int soff = i32(buf, table);
        int vt = table - soff;
        if (vt < 0 || vt + 4 > buf.length) {
            return -1;
        }
        int vtSize = u16(buf, vt);
        int entry = 4 + 2 * idx;
        if (entry + 2 > vtSize) {
            return -1;
        }
        int off = u16(buf, vt + entry);
        if (off == 0) {
            return -1;
        }
        return table + off;
    }

    private static Integer fbI32(byte[] buf, int pos) {
        if (pos < 0 || pos + 4 > buf.length) {
            return null;
        }
        return i32(buf, pos);
    }

    private static String fbString(byte[] buf, int pos) {
        if (pos < 0 || pos + 4 > buf.length) {
            return null;
        }
        long uoff = pos + u32(buf, pos);
        if (uoff + 4 > buf.length) {
            return null;
        }
        long len = u32(buf, (int) uoff);
        if (len > 16L * 1024 * 1024 || uoff + 4 + len > buf.length) {
            return null;
        }
        return new String(buf, (int) (uoff + 4), (int) len, StandardCharsets.UTF_8);
    }

    private static Integer fbVectorLen(byte[] buf, int pos) {
        if (pos < 0 || pos + 4 > buf.length) {
            return null;
        }
        long vp = pos + u32(buf, pos);
        if (vp + 4 > buf.length) {
            return null;
        }
        return (int) u32(buf, (int) vp);
    }

    /** Parse a LiteRT / TFLite FlatBuffer: identifier plus Model table fields. */
    public static JSONObject inspectLitert(File file) throws ToolkitException {
        requireFile(file);
        long size = file.length();
        int want = (int) Math.min(size, READ_CAP);
        byte[] buf;
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(want, 16));
            byte[] chunk = new byte[64 * 1024];
            int remaining = want;
            while (remaining > 0) {
                int r = in.read(chunk, 0, Math.min(chunk.length, remaining));
                if (r <= 0) {
                    break;
                }
                out.write(chunk, 0, r);
                remaining -= r;
            }
            buf = out.toByteArray();
        } catch (IOException e) {
            throw new ToolkitException("unreadable: " + e.getMessage());
        }

        JSONObject info = new JSONObject();
        put(info, "format", "litert");
        put(info, "file_size", size);
        if (buf.length < 8) {
            throw new ToolkitException("file too small to be a LiteRT FlatBuffer");
        }
        String ident = iso(buf, 4, 4);
        boolean printable = true;
        for (int i = 0; i < ident.length(); i++) {
            char c = ident.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                printable = false;
                break;
            }
        }
        put(info, "identifier", printable ? ident : hex(buf, 8).substring(8));

        long root = u32(buf, 0);
        put(info, "root_offset", root);
        if (root >= size) {
            throw new ToolkitException("FlatBuffer root offset " + root + " beyond file size " + size);
        }
        if (root >= buf.length) {
            put(info, "parse_error", "root table beyond " + (READ_CAP / (1024 * 1024)) + " MiB read cap");
            return info;
        }

        int rootPos = (int) root;
        // Model table fields: 0=version, 1=operator_codes, 2=subgraphs, 3=description, 4=buffers
        put(info, "model_version", fbI32(buf, fbField(buf, rootPos, 0)));
        put(info, "operator_code_count", fbVectorLen(buf, fbField(buf, rootPos, 1)));
        put(info, "subgraph_count", fbVectorLen(buf, fbField(buf, rootPos, 2)));
        put(info, "description", fbString(buf, fbField(buf, rootPos, 3)));
        put(info, "buffer_count", fbVectorLen(buf, fbField(buf, rootPos, 4)));

        Object version = info.opt("model_version");
        if (!"TFL3".equals(ident) && (version == null || version == JSONObject.NULL)) {
            throw new ToolkitException("FlatBuffer root parsed but no LiteRT Model fields found "
                    + "(identifier=" + ident + ") — not a LiteRT/TFLite model?");
        }
        return info;
    }

    // -----------------------------------------------------------------------
    // ① LOAD  /  common inspect
    // -----------------------------------------------------------------------

    private static JSONObject inspectCommon(File file, int maxKvs) throws ToolkitException {
        requireFile(file);
        long size = file.length();
        String fmt = detectFormat(file);
        JSONObject result = new JSONObject();
        put(result, "action", "inspect");
        put(result, "ok", true);
        put(result, "path", file.getAbsolutePath());
        put(result, "size_bytes", size);
        put(result, "size_human", fmtSize(size));
        try {
            put(result, "sha256", sha256(file));
        } catch (IOException e) {
            throw new ToolkitException("unreadable: " + e.getMessage(), 2);
        }
        put(result, "format", fmt);
        put(result, "tool_version", TOOL_VERSION);

        if ("gguf".equals(fmt)) {
            JSONObject gguf = inspectGguf(file, maxKvs);
            copyAll(gguf, result);
        } else if ("litert".equals(fmt)) {
            String name = file.getName().toLowerCase(Locale.US);
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
            put(result, "extension", ext);
            if (ext.equals(".litert") || ext.equals(".ablitert")
                    || ext.equals(".tflite") || ext.equals(".lite")) {
                put(result, "extension_family", "LiteRT");
            }
            try {
                copyAll(inspectLitert(file), result);
            } catch (ToolkitException e) {
                if (".litert".equals(ext) || ".ablitert".equals(ext)
                        || ".tflite".equals(ext) || ".lite".equals(ext)) {
                    throw e;
                }
                put(result, "format", "flatbuffer?");
                put(result, "parse_error", e.getMessage());
            }
        } else if ("zip".equals(fmt)) {
            put(result, "note", "ZIP container (APK/AAB/JAR) — use a zip lister to show entries");
        } else {
            byte[] head;
            try {
                head = readHead(file, 16);
            } catch (IOException e) {
                throw new ToolkitException("unreadable: " + e.getMessage(), 2);
            }
            put(result, "head_hex", hex(head, head.length));
            put(result, "note", "unrecognized container — reported header only");
        }
        return result;
    }

    private static void copyAll(JSONObject from, JSONObject to) {
        List<String> keys = new ArrayList<>();
        java.util.Iterator<String> it = from.keys();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        for (String k : keys) {
            put(to, k, from.opt(k));
        }
    }

    /** ① LOAD — validate identity of a file. */
    public static Result load(File file) throws ToolkitException {
        requireFile(file);
        long size = file.length();
        String fmt = detectFormat(file);
        JSONObject result = new JSONObject();
        put(result, "action", "load");
        put(result, "ok", true);
        put(result, "path", file.getAbsolutePath());
        put(result, "size_bytes", size);
        put(result, "size_human", fmtSize(size));
        try {
            put(result, "sha256", sha256(file));
        } catch (IOException e) {
            throw new ToolkitException("unreadable: " + e.getMessage(), 2);
        }
        put(result, "format", fmt);
        put(result, "tool_version", TOOL_VERSION);

        StringBuilder human = new StringBuilder();
        human.append("LOAD OK\n");
        human.append("  path    : ").append(file.getAbsolutePath()).append('\n');
        human.append("  size    : ").append(size).append(" bytes (").append(fmtSize(size)).append(")\n");
        human.append("  sha256  : ").append(result.opt("sha256")).append('\n');
        human.append("  format  : ").append(fmt);
        return new Result(result, human.toString());
    }

    /** ② INSPECT — gguf / litert / ablitert / tflite / zip / unknown. */
    public static Result inspect(File file, int maxKvs) throws ToolkitException {
        JSONObject r = inspectCommon(file, maxKvs);
        String fmt = String.valueOf(r.opt("format"));

        StringBuilder h = new StringBuilder();
        h.append("INSPECT OK\n");
        h.append("  path     : ").append(r.opt("path")).append('\n');
        h.append("  format   : ").append(fmt).append('\n');
        h.append("  size     : ").append(r.opt("size_bytes"))
                .append(" bytes (").append(r.opt("size_human")).append(")\n");
        h.append("  sha256   : ").append(r.opt("sha256")).append('\n');

        if ("gguf".equals(fmt)) {
            h.append("  --- GGUF header ---\n");
            h.append("  version  : ").append(r.opt("gguf_version")).append('\n');
            h.append("  tensors  : ").append(r.opt("tensor_count")).append('\n');
            h.append("  metadata : ").append(r.opt("metadata_kv_count")).append(" KV pair(s)\n");
            JSONArray kvs = r.optJSONArray("metadata_sample");
            if (kvs != null) {
                for (int i = 0; i < kvs.length(); i++) {
                    JSONObject kv = kvs.optJSONObject(i);
                    if (kv == null) {
                        continue;
                    }
                    h.append("    ").append(kv.opt("key")).append(" = ").append(kv.opt("value")).append('\n');
                }
            }
            appendIfPresent(h, r, "metadata_note", "  note     : ");
            appendIfPresent(h, r, "parse_error", "  ERROR    : ");
        } else if ("litert".equals(fmt)) {
            h.append("  --- LiteRT / TFLite FlatBuffer ---\n");
            h.append("  identifier  : ").append(r.opt("identifier")).append('\n');
            h.append("  model ver   : ").append(r.opt("model_version")).append('\n');
            h.append("  description : ").append(r.opt("description")).append('\n');
            h.append("  subgraphs   : ").append(r.opt("subgraph_count")).append('\n');
            h.append("  op codes    : ").append(r.opt("operator_code_count")).append('\n');
            h.append("  buffers     : ").append(r.opt("buffer_count")).append('\n');
            appendIfPresent(h, r, "parse_error", "  ERROR       : ");
        } else {
            appendIfPresent(h, r, "note", "  note      : ");
            appendIfPresent(h, r, "head_hex", "  head_hex  : ");
            appendIfPresent(h, r, "extension", "  extension : ");
            appendIfPresent(h, r, "parse_error", "  parse_err : ");
        }
        return new Result(r, h.toString());
    }

    private static void appendIfPresent(StringBuilder h, JSONObject r, String key, String label) {
        Object v = r.opt(key);
        if (v != null && v != JSONObject.NULL) {
            h.append(label).append(v).append('\n');
        }
    }

    // -----------------------------------------------------------------------
    // ③ GENERATE
    // -----------------------------------------------------------------------

    /** ③ report — JSON inspection report for one model file. */
    public static Result generateReport(File model, File out) throws ToolkitException {
        JSONObject r = inspectCommon(model, 32);
        put(r, "action", "generate-report");
        put(r, "generated_at", timestamp());
        try {
            writeJson(r, out);
        } catch (IOException e) {
            throw new ToolkitException("cannot write report: " + e.getMessage());
        }
        String human = "GENERATE OK  report -> " + (out == null ? "(shown as JSON)" : out.getAbsolutePath());
        return new Result(r, human);
    }

    /** ③ manifest — name/size/sha256/format for every file under a directory. */
    public static Result generateManifest(File dir, File out) throws ToolkitException {
        if (dir == null || !dir.isDirectory()) {
            throw new ToolkitException("directory not found: " + (dir == null ? "null" : dir.getAbsolutePath()), 1);
        }
        File root = dir.getAbsoluteFile();
        JSONArray files = new JSONArray();
        List<File> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            File current = stack.remove(stack.size() - 1);
            File[] children = current.listFiles();
            if (children == null) {
                continue;
            }
            java.util.Arrays.sort(children);
            for (File child : children) {
                if (child.getName().startsWith(".")) {
                    continue;
                }
                if (child.isDirectory()) {
                    stack.add(child);
                    continue;
                }
                if (!child.isFile()) {
                    continue;
                }
                JSONObject entry = new JSONObject();
                put(entry, "relpath", relative(root, child));
                put(entry, "size_bytes", child.length());
                try {
                    put(entry, "sha256", sha256(child));
                    put(entry, "format", detectFormat(child));
                } catch (IOException e) {
                    put(entry, "error", e.getMessage());
                } catch (ToolkitException e) {
                    put(entry, "error", e.getMessage());
                }
                files.put(entry);
            }
        }

        JSONObject r = new JSONObject();
        put(r, "action", "generate-manifest");
        put(r, "ok", true);
        put(r, "root", root.getAbsolutePath());
        put(r, "generated_at", timestamp());
        put(r, "file_count", files.length());
        put(r, "files", files);
        put(r, "tool_version", TOOL_VERSION);
        try {
            writeJson(r, out);
        } catch (IOException e) {
            throw new ToolkitException("cannot write manifest: " + e.getMessage());
        }
        String human = "GENERATE OK  manifest (" + files.length() + " files) -> "
                + (out == null ? "(shown as JSON)" : out.getAbsolutePath());
        return new Result(r, human);
    }

    private static String relative(File root, File child) {
        String r = root.getAbsolutePath();
        String c = child.getAbsolutePath();
        String rel = c.startsWith(r) ? c.substring(r.length()) : c;
        rel = rel.replace(File.separatorChar, '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        return rel;
    }

    /** ③ stub-gguf — minimal structurally valid GGUF (v3, 0 tensors, 3 KVs). */
    public static Result generateStubGguf(File out, String name, String arch) throws ToolkitException {
        if (out == null) {
            throw new ToolkitException("usage: stub-gguf OUTPUT", 3);
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeAscii(payload, "GGUF");
        writeU32(payload, 3);          // GGUF version 3
        writeU64(payload, 0);          // tensor count
        writeU64(payload, 3);          // metadata KV count
        writeGgufStringKv(payload, "general.architecture", arch == null ? "test" : arch);
        writeGgufStringKv(payload, "general.name", name == null ? "stub-model" : name);
        writeGgufStringKv(payload, "general.quantization", "Q4_0");

        try {
            atomicWrite(out, payload.toByteArray(), ".stub-");
        } catch (IOException e) {
            throw new ToolkitException("cannot write stub: " + e.getMessage());
        }

        // Self-verify with our own inspector (parity with the Python toolkit)
        JSONObject check = inspectGguf(out, 32);
        if (((Number) check.opt("metadata_kv_count")).longValue() != 3L) {
            throw new ToolkitException("stub self-verification failed");
        }

        JSONObject r = new JSONObject();
        put(r, "action", "generate-stub-gguf");
        put(r, "ok", true);
        put(r, "path", out.getAbsolutePath());
        put(r, "size_bytes", out.length());
        try {
            put(r, "sha256", sha256(out));
        } catch (IOException e) {
            throw new ToolkitException("cannot hash stub: " + e.getMessage());
        }
        put(r, "self_verified", true);
        put(r, "tool_version", TOOL_VERSION);

        StringBuilder h = new StringBuilder();
        h.append("GENERATE OK  stub-gguf -> ").append(out.getAbsolutePath()).append('\n');
        h.append("  size     : ").append(r.opt("size_bytes")).append(" bytes\n");
        h.append("  sha256   : ").append(r.opt("sha256")).append('\n');
        h.append("  verified : inspector parsed version=").append(check.opt("gguf_version"))
                .append(" tensors=").append(check.opt("tensor_count"))
                .append(" kvs=").append(check.opt("metadata_kv_count"));
        return new Result(r, h.toString());
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
    }

    private static void writeU32(ByteArrayOutputStream out, long v) {
        for (int i = 0; i < 4; i++) {
            out.write((int) ((v >> (8 * i)) & 0xFF));
        }
    }

    private static void writeU64(ByteArrayOutputStream out, long v) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((v >> (8 * i)) & 0xFF));
        }
    }

    private static void writeGgufStringKv(ByteArrayOutputStream out, String key, String value) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        writeU64(out, k.length);
        out.write(k, 0, k.length);
        writeU32(out, 8);              // type 8 = string
        writeU64(out, v.length);
        out.write(v, 0, v.length);
    }

    // -----------------------------------------------------------------------
    // ④ REPLACE / RESTORE
    // -----------------------------------------------------------------------

    private static File backupName(File target) {
        return new File(target.getAbsolutePath() + ".bak-" + backupStamp());
    }

    /** ④ replace — atomic swap of target with source, with backup and verify. */
    public static Result replace(File target, File source, boolean force, boolean noBackup)
            throws ToolkitException {
        if (target == null || source == null) {
            throw new ToolkitException("usage: replace TARGET SOURCE", 3);
        }
        requireFile(source);
        requireFile(target);
        if (target.getAbsoluteFile().equals(source.getAbsoluteFile())) {
            throw new ToolkitException("target and source are the same file", 3);
        }

        String srcFmt = detectFormat(source);
        String tgtFmt = detectFormat(target);
        if (!srcFmt.equals(tgtFmt) && !force) {
            throw new ToolkitException("format mismatch: target=" + tgtFmt + " source=" + srcFmt
                    + " (force to override)");
        }
        if (!force) {
            if ("gguf".equals(srcFmt)) {
                inspectGguf(source, 32);
            } else if ("litert".equals(srcFmt)) {
                inspectLitert(source);
            }
        }

        String srcSha;
        try {
            srcSha = sha256(source);
        } catch (IOException e) {
            throw new ToolkitException("unreadable source: " + e.getMessage(), 1);
        }

        JSONObject r = new JSONObject();
        put(r, "action", "replace");
        put(r, "ok", true);
        put(r, "target", target.getAbsolutePath());
        put(r, "source", source.getAbsolutePath());
        put(r, "format", srcFmt);
        String before;
        try {
            before = sha256(target);
        } catch (IOException e) {
            throw new ToolkitException("unreadable target: " + e.getMessage(), 1);
        }
        put(r, "target_sha256_before", before);
        put(r, "source_sha256", srcSha);
        put(r, "tool_version", TOOL_VERSION);

        File backup = null;
        if (!noBackup) {
            backup = backupName(target);
            try {
                copyFile(target, backup);
            } catch (IOException e) {
                throw new ToolkitException("backup failed: " + e.getMessage());
            }
            put(r, "backup", backup.getAbsolutePath());
        } else {
            put(r, "backup", JSONObject.NULL);
        }

        try {
            // Streamed temp file -> fsync -> rename: a crash can never leave a
            // half-written model, and multi-GB weights never sit in RAM.
            atomicCopy(source, target, ".repl-");
        } catch (IOException e) {
            throw new ToolkitException("atomic overwrite failed: " + e.getMessage());
        }
        String after;
        try {
            after = sha256(target);
        } catch (IOException e) {
            throw new ToolkitException("cannot re-hash target: " + e.getMessage());
        }
        put(r, "target_sha256_after", after);
        put(r, "verified", after.equals(srcSha));
        if (!after.equals(srcSha)) {
            throw new ToolkitException("post-replace hash mismatch — target corrupted");
        }

        StringBuilder h = new StringBuilder();
        h.append("REPLACE OK\n");
        h.append("  target : ").append(target.getAbsolutePath()).append('\n');
        h.append("  before : ").append(before).append('\n');
        h.append("  after  : ").append(after).append('\n');
        h.append("  backup : ").append(backup == null ? "(none)" : backup.getAbsolutePath()).append('\n');
        h.append("  verified: hashes match");
        return new Result(r, h.toString());
    }

    /** ④ replace --restore — roll the newest .bak-* back over the target. */
    public static Result restore(File target) throws ToolkitException {
        if (target == null) {
            throw new ToolkitException("usage: replace --restore TARGET", 3);
        }
        requireFile(target);
        File dir = target.getAbsoluteFile().getParentFile();
        File[] all = dir == null ? null : dir.listFiles();
        List<File> backups = new ArrayList<>();
        String prefix = target.getName() + ".bak-";
        if (all != null) {
            for (File f : all) {
                if (f.isFile() && f.getName().startsWith(prefix)) {
                    backups.add(f);
                }
            }
        }
        if (backups.isEmpty()) {
            throw new ToolkitException("no backups found for " + target.getAbsolutePath()
                    + " (pattern " + prefix + "*)");
        }
        java.util.Collections.sort(backups);
        File latest = backups.get(backups.size() - 1);
        File pre = backupName(new File(target.getAbsolutePath() + ".pre-restore"));
        try {
            copyFile(target, pre);
            atomicCopy(latest, target, ".rest-");
        } catch (IOException e) {
            throw new ToolkitException("restore failed: " + e.getMessage());
        }

        JSONObject r = new JSONObject();
        put(r, "action", "restore");
        put(r, "ok", true);
        put(r, "target", target.getAbsolutePath());
        put(r, "restored_from", latest.getAbsolutePath());
        put(r, "pre_restore_backup", pre.getAbsolutePath());
        put(r, "available_backups", backups.size());
        try {
            put(r, "sha256_after", sha256(target));
        } catch (IOException e) {
            throw new ToolkitException("cannot hash restored file: " + e.getMessage());
        }
        put(r, "tool_version", TOOL_VERSION);

        StringBuilder h = new StringBuilder();
        h.append("RESTORE OK\n");
        h.append("  target : ").append(target.getAbsolutePath()).append('\n');
        h.append("  from   : ").append(latest.getAbsolutePath()).append('\n');
        h.append("  saved current as : ").append(pre.getAbsolutePath()).append('\n');
        h.append("  sha256 : ").append(r.opt("sha256_after"));
        return new Result(r, h.toString());
    }

    // -----------------------------------------------------------------------
    // Small IO helpers
    // -----------------------------------------------------------------------

    public static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(f.length(), 1 << 20));
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.getFD().sync();
        }
    }
}
