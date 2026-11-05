# AndKey1

**One standalone offline Android app** — no dependencies, no permissions, no network.

`andkey1/` contains the entire project: the Java source, `TERMUX_PYTHON_WINDOWS_GUIDE.md` and
`model_toolkit.py`. Those two files are compiled into the APK as assets and rendered/offered
in-app, so the app can never drift from its own documentation.

## Download

- **Release APK** (`com.andkey1`, sideload-ready, ~50 KB):
  [Releases → AndKey1](https://github.com/rsv-op/AndKey/releases) ·
  direct: `https://github.com/rsv-op/AndKey/releases/latest/download/AndKey1-release.apk`
- Always-current mirror (works where Actions artifacts are blocked):

```bash
curl -L -o AndKey1.apk \
  https://github.com/rsv-op/AndKey/raw/apk-latest/ci-artifacts/AndKey1-release.apk
adb install -r AndKey1.apk
```

## What it does

| Tab | Function |
|-----|----------|
| **Guide** | `TERMUX_PYTHON_WINDOWS_GUIDE.md`, rendered in-app (copy / save to workspace) |
| **① Load** | size, SHA-256 and format from magic bytes (`gguf` / `litert` / `zip` / `unknown`) |
| **② Inspect** | `.gguf` header (version, tensors, metadata KVs) and `.litert`/`.ablitert`/`.tflite` FlatBuffers (root offset, `TFL3`, model version, subgraph/op/buffer counts) |
| **③ Generate** | `<model>.report.json`, `manifest.json`, self-verified `stub.gguf`; export to Downloads |
| **④ Replace** | atomic temp→fsync→rename swap, `.bak-<timestamp>` backup, hash verification, restore |
| **Python tool** | `model_toolkit.py` verbatim — copy it out to Termux for the CLI |

`minSdk 26` (Android 8+) · `targetSdk 34` · framework-only Java 17 · ~50 KB.

## Build

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle :andkey1:assembleDebug      # andkey1/build/outputs/apk/debug/andkey1-debug.apk
gradle :andkey1:assembleRelease    # andkey1/build/outputs/apk/release/andkey1-release.apk
```

CI: [`.github/workflows/build-andkey1-apk.yml`](.github/workflows/build-andkey1-apk.yml) builds both
variants, asserts the two assets and `classes.dex` are inside the zip, and fails if any permission
reappears.

## Zero network

`INTERNET` and `ACCESS_NETWORK_STATE` are removed with `tools:node="remove"`; there are no
third-party libraries; every operation is local file I/O. Verify:

```bash
adb shell dumpsys package com.andkey1 | grep INTERNET   # expect: nothing
```

The full operating guide (in-app, Termux, Python CLI and Windows/adb recipes) is
`andkey1/src/main/assets/TERMUX_PYTHON_WINDOWS_GUIDE.md`.
