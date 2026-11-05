# AndKey1 — Termux · Python · Windows Operating Guide

> AndKey1 (`com.andkey1`) is **one standalone offline Android app**. It contains this guide,
> the Python toolkit `model_toolkit.py`, and native implementations of the four file functions —
> so it works with no PC, no Termux and no network permission.
>
> | # | Function | In AndKey1 | CLI equivalent |
> |---|----------|-----------|----------------|
> | 1 | **Load** | tab **① Load** — pick a file, get size + `sha256` + format | `model_toolkit.py load PATH` |
> | 2 | **Inspect** `.gguf` / `.litert` / `.ablitert` | tab **② Inspect** — headers parsed by content, never executed | `model_toolkit.py inspect PATH` |
> | 3 | **Generate** | tab **③ Generate** — `report.json`, `manifest.json`, `stub.gguf` | `model_toolkit.py generate …` |
> | 4 | **Replace** | tab **④ Replace** — atomic swap, `.bak-<timestamp>`, verify, restore | `model_toolkit.py replace …` |
>
> Everything below is copy-paste. Each step has a **Verify** line. The app asks for **zero
> permissions** — no storage, no network — because all file access goes through Android's system
> picker.

---

## 1. Install AndKey1

### 1.1 From the phone browser (no PC)

Open in Chrome:

```text
https://github.com/rsv-op/AndKey/releases/latest/download/AndKey1-release.apk
```

1. When Chrome offers **Settings → Allow from this source**, allow it.
2. Tap the finished download → **Install**.
3. If Play Protect says *unsafe app blocked* (normal for a dev-signed APK), tap
   **More details → Install anyway**.
4. Open **AndKey1** from the app drawer.

**Verify:**

```bash
pm list packages | grep andkey1        # expect: package:com.andkey1
```

(A debug build installs side by side as `com.andkey1.debug`.)

### 1.2 From Termux

```bash
pkg install -y curl
curl -L -o ~/AndKey1.apk \
  https://github.com/rsv-op/AndKey/releases/latest/download/AndKey1-release.apk
termux-open ~/AndKey1.apk              # Android package installer opens
```

### 1.3 From a Windows PC (adb)

```powershell
winget install --id Google.PlatformTools -e
# phone: Settings → About → tap Build number 7× → Developer options → USB debugging
adb devices                            # expect: <serial>    device
adb install -r .\AndKey1-release.apk   # expect: Success
adb shell dumpsys package com.andkey1 | findstr INTERNET   # expect: no matches
```

`adb shell dumpsys package com.andkey1` reporting no `INTERNET` line is the zero-network proof:
the permission is stripped with `tools:node="remove"` at build time.

---

## 2. Using the four functions in the app

Tabs across the top: **Guide · ① Load · ② Inspect · ③ Generate · ④ Replace · Python tool · About**.
The blue status bar always shows the workspace folder.

Files are first copied into the app's own **workspace**:

```text
/sdcard/Android/data/com.andkey1/files/workspace/          (release)
/sdcard/Android/data/com.andkey1.debug/files/workspace/    (debug build)
```

### ① Load

Tap **Pick a file & run LOAD** → any file → you get:

```text
LOAD OK
  path    : /data/user/0/com.andkey1/cache/picked/my-model.gguf
  size    : 149 bytes (149 bytes)
  sha256  : c647c40e…
  format  : gguf
```

Format is decided by **magic bytes**, not the extension: `GGUF`, `PK` (zip/APK), a plausible
FlatBuffer root offset (LiteRT family), otherwise `unknown` with the first 16 bytes in hex.

### ② Inspect

Tap **Pick a model file & INSPECT**, or set how many GGUF metadata pairs to sample first.

```text
INSPECT OK
  format   : gguf
  --- GGUF header ---
  version  : 3
  tensors  : 0
  metadata : 3 KV pair(s)
    general.architecture = llama
    general.name = app-stub
    general.quantization = Q4_0
```

LiteRT family (`.litert`, `.ablitert`, `.tflite`, detected by content):

```text
INSPECT OK
  format   : litert
  --- LiteRT / TFLite FlatBuffer ---
  identifier  : TFL3
  model ver   : 3
  subgraphs   : <n>
  op codes    : <n>
  buffers     : <n>
```

Nothing is executed — only headers are parsed. A file named `.ablitert` that really holds GGUF
data is reported as `gguf`.

### ③ Generate

| Button | Output in the workspace |
|--------|-------------------------|
| Report from a model file… | `<model-name>.report.json` — full inspect result + timestamp |
| Stub GGUF (self-verified) | `stub.gguf` — minimal valid GGUF v3, 3 KV pairs, re-parsed to prove it |
| Manifest of the workspace folder | `manifest.json` — every file with size / `sha256` / format |
| Export last generated file to Downloads… | copies the newest output where a file manager can reach it |

**Verify (Termux):**

```bash
ls -lh /sdcard/Android/data/com.andkey1/files/workspace/
```

### ④ Replace — atomic swap with rollback

1. **Pick replacement SOURCE file…** → copied into the workspace, name printed as `SOURCE READY`.
2. Type the **target** file name (must already exist in the workspace, e.g. `model.gguf`).
3. **Run REPLACE (target ← source)**:

```text
REPLACE OK
  target : …/workspace/model.gguf
  before : <sha256 of old file>
  after  : <sha256 of new file>
  backup : …/workspace/model.gguf.bak-20261106-101500
  verified: hashes match
```

4. **Restore newest .bak of target** undoes it:

```text
RESTORE OK
  from   : …/workspace/model.gguf.bak-20261106-101500
  sha256 : <sha256 restored>
```

Guarantees: source is parsed before the swap; a format mismatch (e.g. `.litert` over `.gguf`)
is refused unless **force** is ticked; the write is `temp → fsync → rename`, so a crash can never
leave a half-written model; both sides are re-hashed after the swap and the operation fails loudly
on any mismatch.

### Python tool tab

**Copy source** / **Save to workspace** give you `model_toolkit.py` verbatim — the same file the
CLI section below uses. **Guide** tab has **Copy Markdown** / **Save to workspace** for this
document.

---

## 3. The same four functions from Termux / Windows (optional)

The toolkit is stdlib-only, Python 3.8+, zero network calls.

Get it:

```bash
# Termux — from the app itself: Python tool tab → Save to workspace, then
cp /sdcard/Android/data/com.andkey1/files/workspace/model_toolkit.py ~/
# or straight from the repository
curl -L -o ~/model_toolkit.py \
  https://github.com/rsv-op/AndKey/raw/main/andkey1/src/main/assets/model_toolkit.py
python model_toolkit.py --version          # expect: model_toolkit 1.0.0
```

```text
python model_toolkit.py load      PATH                       → function ①
python model_toolkit.py inspect   PATH [--json] [--max-kvs N] → function ②
python model_toolkit.py generate  report|manifest|stub-gguf   → function ③
python model_toolkit.py replace   TARGET SOURCE [--restore]   → function ④
```

### ① Load / ② Inspect

```bash
python model_toolkit.py load    ./my-model.gguf
python model_toolkit.py inspect ./my-model.gguf
python model_toolkit.py inspect ./encoder.litert --json
python model_toolkit.py inspect ./legacy.ablitert
```

### ③ Generate

```bash
python model_toolkit.py generate report   ./my-model.gguf -o ./report.json
python model_toolkit.py generate manifest ./models_dir    -o ./manifest.json
python model_toolkit.py generate stub-gguf ./stub.gguf --name demo --arch llama
```

### ④ Replace / restore

```bash
python model_toolkit.py replace ./model.gguf ./model-v2.gguf
python model_toolkit.py replace --restore ./model.gguf
```

**Exit codes:** `0` success · `1` file/dir not found · `2` parse or validation failure ·
`3` wrong usage. `load`, `inspect`, `generate report|manifest` and `replace` also accept `--json`.

---

## 4. Windows Shell recipes (adb)

```powershell
# push a model into the app workspace, inspect it on the PC, then back
adb push .\my-model.gguf /sdcard/Android/data/com.andkey1/files/workspace/
adb pull /sdcard/Android/data/com.andkey1/files/workspace/my-model.gguf .\my-model.gguf
python .\model_toolkit.py inspect .\my-model.gguf

# model upgrade with rollback
adb push .\model-v2.gguf /sdcard/Android/data/com.andkey1/files/workspace/
adb pull /sdcard/Android/data/com.andkey1/files/workspace/model.gguf .\model.gguf
python .\model_toolkit.py replace .\model.gguf .\model-v2.gguf
python .\model_toolkit.py replace --restore .\model.gguf        # undo
adb push .\model.gguf /sdcard/Android/data/com.andkey1/files/workspace/model.gguf
```

The in-app ④ Replace tab does exactly this without a PC, including the `.bak-` rollback.

---

## 5. Troubleshooting

| Symptom | Fix |
|---------|-----|
| Chrome refuses the install | Settings → Apps → Special access → Install unknown apps → Chrome → **Allow** |
| Play Protect blocks it | **More details → Install anyway** (dev-signed APK, not from Play) |
| `INSTALL_FAILED_ALREADY_EXISTS` | `adb install -r …`, or `adb uninstall com.andkey1` first |
| Picker shows no files | The picker filters nothing — browse to **Downloads**; some OEM file pickers hide archives until you tap ⋮ → Show internal storage |
| `FAILED (exit code 1)` | File not found — re-pick it; the workspace path is shown in the status bar |
| `FAILED (exit code 2)` | Parse/validation failure — truncated or corrupt file, or a format-mismatch guard on Replace (tick **force** only if intentional) |
| `no backups found` on Restore | The target has no `.bak-*` yet; run a Replace first |
| Files invisible in a file manager | Use **③ Generate → Export last generated file to Downloads…**, or `adb shell ls /sdcard/Android/data/com.andkey1/files/workspace` |
| GGUF inspect shows `parse_error` | File truncated mid-download — compare `sha256` from ① Load with the source |

---

## 6. What AndKey1 is (and is not)

- **One module, one APK, no dependencies** — the Java source, this guide and `model_toolkit.py`
  are the entire project (`andkey1/src/main/assets/`).
- **No network** — `INTERNET` and `ACCESS_NETWORK_STATE` are removed with `tools:node="remove"`,
  and CI fails the build if either reappears.
- **Android 8.0+** (`minSdk 26`, `targetSdk 34`), ~50 KB.
- It reads and writes model files; it does **not** run inference, chat with a model, or bundle any
  weights.

*Version 1.0.0 · package `com.andkey1` · guide and toolkit are byte-identical to the files shipped
in the APK.*
