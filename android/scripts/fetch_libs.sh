#!/usr/bin/env bash
# Скачивает нативные AAR в app/libs. Запускать один раз перед первой сборкой (~70 МБ).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LIBS="$(cd "$HERE/../app" && pwd)/libs"
mkdir -p "$LIBS"

fetch() { # url target
  [ -s "$2" ] && { echo "уже есть: $(basename "$2")"; return; }
  echo "скачиваем: $(basename "$2")"
  curl -fL --retry 3 -o "$2" "$1"
}

fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar" \
      "$LIBS/sherpa-onnx.aar"
fetch "https://github.com/microsoft/onnxruntime-genai/releases/download/v0.19.0/onnxruntime-genai-android-0.19.0.aar" \
      "$LIBS/onnxruntime-genai.aar"

ls -la "$LIBS"
