#!/usr/bin/env bash
# Скачивает нативные AAR-библиотеки в app/libs. Один раз перед сборкой (~70 МБ).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LIBS="$HERE/../app/libs"
mkdir -p "$LIBS"
cd "$LIBS"

[ -f sherpa-onnx.aar ] || curl -L --fail -o sherpa-onnx.aar \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"

[ -f onnxruntime-genai.aar ] || curl -L --fail -o onnxruntime-genai.aar \
  "https://github.com/microsoft/onnxruntime-genai/releases/download/v0.19.0/onnxruntime-genai-android-0.19.0.aar"

ls -la "$LIBS"
