#!/usr/bin/env bash
# Скачивает нативные библиотеки в app/libs. Запускать один раз перед сборкой.
#
# Почему не в git: AAR весит ~47 МБ, а в git это лишний вес на каждое
# клонирование. Скрипт идемпотентен — повторный запуск ничего не качает.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LIBS="$(cd "$HERE/../app" && pwd)/libs"
mkdir -p "$LIBS"

fetch() { # url target
    [ -s "$2" ] && { echo "уже есть: $(basename "$2")"; return; }
    echo "скачиваем: $(basename "$2")"
    curl -fL --retry 3 -o "$2" "$1"
}

# sherpa-onnx: диаризация (pyannote-3.0 + 3D-Speaker) и ASR на устройстве.
# Сеть нужна только на этапе сборки, в APK её нет.
fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar" \
      "$LIBS/sherpa-onnx.aar"

ls -la "$LIBS"
