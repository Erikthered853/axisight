#!/usr/bin/env bash
set -euo pipefail

if [[ ! -f "./gradlew" ]]; then
  echo "gradlew not found. Run 'gradle wrapper' or open in Android Studio to generate it."
  exit 1
fi

chmod +x ./gradlew
./gradlew assembleDebug --stacktrace

ls -l app/build/outputs/apk/debug/*.apk || true
