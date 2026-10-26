# BuildGenie — Offline Builder & AI Dev Chat

[![BuildGenie Offline APK & Model Bundler](https://github.com/rsv-op/AndKey/actions/workflows/android-release.yml/badge.svg)](https://github.com/rsv-op/AndKey/actions/workflows/android-release.yml)

**100% On-Device • Zero Telemetry • Zero Network Permission** — Android 10–16 (API 26–34)

## 📥 Download APK (Install on Vivo V50 Android 16)

> **Latest Release:** Go to [**Releases → Latest**](https://github.com/rsv-op/AndKey/releases) and download `app-debug.apk` or `app-release.apk`

Alternatively, get the artifact from any successful workflow run:
1. Open [**Actions → BuildGenie Offline APK & Model Bundler**](https://github.com/rsv-op/AndKey/actions/workflows/android-release.yml)
2. Click the latest **green** run (check-mark)
3. Scroll to **Artifacts** → download **BuildGenie-Offline-APK** (contains `app-debug.apk` + `app-release.apk`)

**Verify install (Vivo V50):**
```bash
adb install app-debug.apk
adb shell dumpsys package com.buildgenie | grep INTERNET # should be empty — zero-network verified
```

## Architecture

- **SAF Inbox** (`MainActivity` + `FileWatchService` with `RECEIVER_NOT_EXPORTED` + `foregroundServiceType=dataSync`)
- **Ingestion** (`TextExtractor` + `PDFBoxResourceLoader.init` before `PDDocument.load`)
- **Build Matching** (`BuildMatcher` + `builds.json` ontology, 3 templates)
- **Zero-Metadata PDF** (`PdfExporter` + `MediaStore` on Android 10+ + `PrivacyFilter`)

See [**GITHUB_AND_ANDROID_UPGRADES.md**](GITHUB_AND_ANDROID_UPGRADES.md) for full file tree, large-model handling (Git LFS / Action fetcher / side-load), and the 5 critical Android upgrades.

## Build Locally

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle :app:assembleDebug   # Debug APK
gradle :app:assembleRelease # Release APK (signed with debug keystore, ready for sideload)
# Outputs: app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

Every push to `main` builds a real APK (not dummy):
- Fetches `all-MiniLM-L6-v2-quant.onnx` (~23 MB) from Hugging Face (bypasses 100 MB Git limit)
- Verifies `android.permission.INTERNET` is removed via `tools:node="remove"` + `xmlns:tools`
- Validates APK via `unzip -l` + `aapt2 dump badging` + `file` (ZIP/DEX)
- Uploads to **Artifacts** and auto-creates **Release** `build-<run_number>` on `main`

ForFun — see `GITHUB_AND_ANDROID_UPGRADES.md`
