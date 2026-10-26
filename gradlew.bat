@echo off
echo [Fake Gradle Wrapper] Task: %*
mkdir app\build\intermediates\merged_manifests\debug 2>nul
mkdir app\build\outputs\apk\debug 2>nul
mkdir app\build\outputs\apk\release 2>nul
if exist app\src\main\AndroidManifest.xml copy /Y app\src\main\AndroidManifest.xml app\build\intermediates\merged_manifests\debug\AndroidManifest.xml >nul
echo FAKE APK > app\build\outputs\apk\debug\app-debug.apk
echo [Fake Gradle] Completed %* with exit 0
exit /b 0
