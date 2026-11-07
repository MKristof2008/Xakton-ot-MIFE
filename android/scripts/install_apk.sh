#!/usr/bin/env bash
# Ставит рабочий APK на подключённый телефон.
#
# Зачем отдельный скрипт: `./gradlew connectedAndroidTest` после прогона
# УДАЛЯЕТ приложение с устройства (так ведёт себя AGP). Проверяющий/автор
# видит пропавшую программу — это и было причиной «прога исчезла».
# Здесь APK ставится заново, и его уже никто не снимает.
#
# Использование:
#   ./scripts/install_apk.sh            # release (для проверки)
#   ./scripts/install_apk.sh debug      # debug (с отладочной информацией)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID="$(cd "$HERE/.." && pwd)"
VARIANT="${1:-release}"

case "$VARIANT" in
    release) APK="$ANDROID/app/build/outputs/apk/release/app-release.apk" ;;
    debug)   APK="$ANDROID/app/build/outputs/apk/debug/app-debug.apk" ;;
    *) echo "Вариант: release | debug" >&2; exit 1 ;;
esac

ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null 2>&1 || {
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    ADB="$SDK/platform-tools/adb"
}

[ -f "$APK" ] || { echo "Нет APK: $APK — сначала соберите: ./gradlew :app:assembleRelease" >&2; exit 1; }

echo "ставим: $(basename "$APK")"
"$ADB" install -r "$APK"

# Разрешения выдаём сразу, чтобы приложение запускалось без диалогов.
PKG=ru.hexteam.stenograph
"$ADB" shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null || true
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

echo
echo "установлено:"
"$ADB" shell "pm list packages | grep $PKG"