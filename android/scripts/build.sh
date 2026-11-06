#!/usr/bin/env bash
# Сборка APK с нуля на чистой машине.
# Требуется JDK 17+ и Android SDK (platform 34, build-tools 34.0.0).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."

./scripts/fetch_libs.sh
./scripts/fetch_models.sh

./gradlew testDebugUnitTest assembleDebug

echo
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
echo "Установка: adb install -r app/build/outputs/apk/debug/app-debug.apk"
