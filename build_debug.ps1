# Build debug APK locally (Windows)
# Requirements: Android Studio (SDK + build-tools installed), JDK 17, platform-tools in PATH

$ErrorActionPreference = "Stop"

# Ensure gradlew is executable on Windows
if (-not (Test-Path ".\gradlew.bat")) {
    Write-Error "gradlew.bat not found. Open this folder in Android Studio once to generate wrapper."
}

# Build
.\gradlew.bat assembleDebug --stacktrace

# Result
$apk = Get-ChildItem -Path ".\app\build\outputs\apk\debug" -Filter "*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($apk) {
    Write-Host "APK built:" $apk.FullName
} else {
    Write-Error "No APK found. Check the Gradle output above."
}
