# BuildGenie — GitHub Architecture, Large-File Handling & Android Device Upgrades

> This document explains the exact file tree to push to GitHub, how to handle files that exceed GitHub's size limits, and the specific upgrades applied so the app runs smoothly on modern physical Android devices (Android 10 through Android 14/15).

---

## 1. Exact Repository File Structure for GitHub

Upload all generated files in this exact directory layout:

```
BuildGenie/                      ← repository root (this repo)
├── .github/
│   └── workflows/
│       └── android-release.yml       ← Automatically downloads >25 MB ML models & builds APK
├── .gitignore
├── .gitattributes                    ← LFS tracking (optional, for *.onnx/*.tflite/*.gguf)
├── settings.gradle
├── build.gradle                      ← Root Gradle file
├── GITHUB_AND_ANDROID_UPGRADES.md    ← This file
├── app/
│   ├── build.gradle                  ← App Gradle (MultiDex, POI excludes, ONNX/TFLite, API 26-34)
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml       ← Enforces tools:node="remove" on INTERNET permission
│       ├── assets/
│       │   ├── models/               ← Keep empty in Git (populated by Action or LFS)
│       │   │   └── .gitkeep
│       │   └── templates/
│       │       ├── llm_doc_finetune.md
│       │       ├── ml_text_classifier.md
│       │       └── static_rag_pipeline.md
│       ├── res/
│       │   └── raw/
│       │       └── builds.json       ← Build ontology & scoring definitions
│       └── java/com/buildgenie/
│           ├── MainActivity.java     ← UI + SAF Picker + 1-Tap PDF Export + AI Dev Chat
│           ├── chat/
│           │   └── LocalAiChatEngine.java ← Offline AI Dev Chat (RAG + ONNX/GGUF bridge)
│           ├── service/
│           │   └── FileWatchService.java
│           ├── ingestion/
│           │   └── TextExtractor.java
│           ├── analysis/
│           │   └── BuildMatcher.java
│           └── utils/
│               ├── PrivacyFilter.java
│               └── PdfExporter.java
```

**Conventions:**
- `minSdk 26` (Android 8.0) → `targetSdk 34` (Android 14) → `compileSdk 34`.
- `Java 17` toolchain (required for AGP 8.2+ and Apache POI 5.2.3).
- All on-device ML models are **not** committed directly — see §2.

Verify locally:
```bash
./gradlew :app:processDebugMainManifest
grep -q "INTERNET" app/build/intermediates/merged_manifests/debug/AndroidManifest.xml && echo "FAIL" || echo "VERIFIED offline"
```

---

## 2. How to Handle GitHub File Size Restrictions (25 MB Web / 100 MB Git Push)

All source code, templates, and JSON files in this repository are under 50 KB and can be uploaded directly via the GitHub web interface or `git push`.

However, on-device ML models (`.tflite`, `.onnx`, `.gguf`, Vosk acoustic models, Tesseract `.traineddata`) range from **23 MB to 450 MB**:

| Limit | Value |
|-------|-------|
| GitHub Web Upload Limit | **25 MB per file** |
| GitHub Hard Push Limit | **100 MB per file** |
| Git LFS Quota (free) | 1 GB storage + 1 GB bandwidth/month |

### Three Ways to Handle the Model Files

#### Option A — Automated GitHub Actions Bundling (Recommended, Included)

You only upload the lightweight Java/XML/Markdown files to GitHub.

Whenever you push code, the workflow `.github/workflows/android-release.yml` automatically:
1. Downloads the quantized `all-MiniLM-L6-v2` ONNX model (~23 MB) directly from Hugging Face into `app/src/main/assets/models/`
2. Runs `./gradlew assembleDebug` (and `assembleRelease` on `main`)
3. Verifies the merged manifest contains **no** `INTERNET` permission
4. Uploads the ready-to-install APK as an artifact under **Actions → Artifacts**

```yaml
- name: Fetch Open-Source ONNX/TFLite Models
  run: |
    mkdir -p app/src/main/assets/models
    curl -L -s "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx" -o app/src/main/assets/models/all-MiniLM-L6-v2-quant.onnx
    curl -L -s "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/vocab.txt" -o app/src/main/assets/models/vocab.txt
```

No Git LFS needed. APK stays installable without bundling 500 MB+ LLMs.

#### Option B — Git LFS (Large File Storage)

If you want the `.onnx` or `.tflite` binaries versioned inside your repo:

```bash
git lfs install
git lfs track "*.tflite" "*.onnx" "*.gguf" "*.traineddata"
git add .gitattributes
git add app/src/main/assets/models/all-MiniLM-L6-v2-quant.onnx
git commit -m "Add quantized embedding model via LFS"
git push
```

This repo includes `.gitattributes`:
```
*.onnx filter=lfs diff=lfs merge=lfs -text
*.tflite filter=lfs diff=lfs merge=lfs -text
*.gguf filter=lfs diff=lfs merge=lfs -text
*.traineddata filter=lfs diff=lfs merge=lfs -text
```

#### Option C — Side-Loading to the Device Folder (For 500 MB+ GGUF LLM Chat Models)

Bundling a 1 GB `.gguf` or 400 MB `onnx` LLM inside an APK slows installation and triggers Play Store limits.

`LocalAiChatEngine.java` checks **at runtime**:
```java
File localModel = new File(context.getFilesDir(), "models/local_llm_q4.onnx");
File downloadModel = new File("/sdcard/Download/BuildGenie/models/model_q4.gguf");
if (localModel.exists() || downloadModel.exists()) { /* ONNX Runtime active */ }
```

You can copy any quantized model directly onto your phone via USB/ADB without rebuilding:
```bash
adb push MyModel_q4.gguf /sdcard/Download/BuildGenie/models/
# or
adb push MyModel_q4.gguf /data/data/com.buildgenie/files/models/local_llm_q4.onnx
```

---

## 3. Crucial Upgrades Made to Run on Real Android Devices (API 26–34+)

Running heavy desktop-grade Java libraries (Apache POI, PDFBox, ONNX Runtime) and file watchers on modern Android requires specific OS-level fixes integrated below:

### Upgrade 1 — Android 10–14 Scoped Storage (MediaStore in PdfExporter.java)

**The Issue:** On Android 10+ (API 29+), `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)` throws `FileNotFoundException (EACCES: Permission denied)` even if `WRITE_EXTERNAL_STORAGE` is declared. Scoped Storage isolates apps.

**The Upgrade:** `PdfExporter.java` now branches:

```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    ContentResolver resolver = context.getContentResolver();
    ContentValues values = new ContentValues();
    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
    values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BuildGenie");
    Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    try (OutputStream out = resolver.openOutputStream(uri)) { document.save(out); }
} else {
    // Fallback API 26-28: legacy FileOutputStream to /Download/BuildGenie/
    File outDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BuildGenie");
    outDir.mkdirs();
    document.save(new FileOutputStream(new File(outDir, fileName)));
}
```

Result: PDFs save to `/Download/BuildGenie/` on Android 10–15 without `MANAGE_EXTERNAL_STORAGE`.

---

### Upgrade 2 — PDFBoxResourceLoader.init(context) Initialization

**The Issue:** Calling `PDType1Font.HELVETICA_BOLD` or `PDType1Font.COURIER` on Android without initializing the asset resource loader crashes with `ExceptionInInitializerError` — Android does not ship desktop system fonts in `/usr/share/fonts`.

**The Upgrade:** Added as **first line** inside `PdfExporter.generateBuildGuidePdf()`:

```java
PDFBoxResourceLoader.init(context.getApplicationContext());
try (PDDocument document = new PDDocument()) {
    PDDocumentInformation info = new PDDocumentInformation();
    info.setAuthor(""); info.setCreator(""); info.setProducer("");
    info.setTitle("Build Guide: " + buildId);
    Calendar epoch = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    epoch.setTimeInMillis(0);
    info.setCreationDate(epoch); info.setModificationDate(epoch);
    document.setDocumentInformation(info);
    renderMarkdownLinesToPdf(document, populatedMarkdown);
    // ...
}
```

Also scrubs metadata (epoch dates, empty author/creator) to prevent device fingerprinting.

---

### Upgrade 3 — Android 14 (targetSdk 34) Receiver & Foreground Service Rules

**The Issue:** Android 14 crashes on startup if:
- A dynamic `BroadcastReceiver` does not specify `RECEIVER_NOT_EXPORTED`, or
- `startForeground()` is called without `foregroundServiceType="dataSync"` in the manifest.

**The Upgrade:**

**MainActivity.java:**
```java
IntentFilter filter = new IntentFilter("com.buildgenie.ACTION_FILE_INGESTED");
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    registerReceiver(ingestionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
} else {
    registerReceiver(ingestionReceiver, filter);
}
```

**AndroidManifest.xml:**
```xml
<service android:name=".service.FileWatchService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

**FileWatchService.java:**
```java
startForeground(NOTIF_ID, notification); // with dataSync channel
fileObserver.startWatching(); // broadcasts ACTION_FILE_INGESTED
```

Prevents other apps from spoofing file-ingestion broadcasts and satisfies Android 14's foreground service policy.

---

### Upgrade 4 — android:largeHeap="true" for Apache POI & ONNX

**The Issue:** Parsing `.docx`/`.xlsx` via Apache POI OOXML alongside ONNX embeddings can spike memory past Android's default 192 MB–256 MB per-app heap ceiling, causing `OutOfMemoryError` on mid-range devices.

**The Upgrade:** Added in `AndroidManifest.xml`:

```xml
<application
    android:largeHeap="true"
    android:allowBackup="false"
    android:dataExtractionRules="@null"
    ...>
```

Increases available JVM heap to **512 MB+** on modern devices. Paired with `app/build.gradle` packaging excludes to reduce DEX size:

```gradle
packagingOptions {
    resources.excludes += ['META-INF/DEPENDENCIES', 'META-INF/LICENSE', 'META-INF/NOTICE']
}
```

And `multiDexEnabled true` for >64k methods (POI + PDFBox).

---

### Upgrade 5 — Strict Zero-Network Enforcement (tools:node="remove")

**The Issue:** Some third-party AAR dependencies silently merge `<uses-permission android:name="android.permission.INTERNET" />` into your final APK during Gradle manifest merging, violating the 100% offline promise.

**The Upgrade:** Two-layer enforcement:

**1. Manifest-level:** `AndroidManifest.xml` explicitly:
```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

**2. CI-level:** `android-release.yml` inspects the merged manifest **after** Gradle merging:
```bash
./gradlew :app:processDebugMainManifest
if grep -q "android.permission.INTERNET" app/build/intermediates/merged_manifests/debug/AndroidManifest.xml; then
  echo "ERROR: INTERNET permission detected in merged manifest!" && exit 1
else
  echo "VERIFIED: 100% Offline APK (No INTERNET permission present)."
fi
```

Guarantees the compiled APK **physically cannot** make network calls — verified on every push.

---

## Appendix — Verification Checklist (Physical Device Android 10–15)

- [ ] Install debug APK from Actions artifact via `adb install app-debug.apk`
- [ ] Open app → tap **"1. Pick SAF Inbox"** → grant folder → see `SAF Inbox Linked`
- [ ] Tap **"2. Seed Sample Files"** → `support_tickets.csv` + `finetune_prompts.jsonl` + `training_config.yaml` appear
- [ ] Check **Suggested Builds** → tap a build → PDF appears in `/Download/BuildGenie/build_<id>_20240101.pdf` (no metadata)
- [ ] Ask AI chat **"show schema"** → see `[Local RAG Synthesis Engine Active]` unless model side-loaded
- [ ] Side-load GGUF → restart app → chat shows `[Local ONNX Int4 Runtime Active]`
- [ ] Verify no INTERNET permission: `adb shell dumpsys package com.buildgenie | grep INTERNET` → empty

---

*Generated for BuildGenie Offline — 100% On-Device, Zero Telemetry, Zero Network.*
