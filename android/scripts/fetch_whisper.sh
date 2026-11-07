#!/usr/bin/env bash
# Опционально: Whisper small (multilingual) как второй движок ASR.
# Нужен только для сравнения качества на своих записях — APK вырастет на 374 МБ.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$(cd "$HERE/../app/src/main" && pwd)/assets/models/asr"
WORK="${TMPDIR:-/tmp}/stenograf-models"
mkdir -p "$ASSETS" "$WORK"
cd "$WORK"
[ -s ws.tar.bz2 ] || curl -fL --retry 3 -o ws.tar.bz2 \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2"
[ -d sherpa-onnx-whisper-small ] || tar -xjf ws.tar.bz2
M=sherpa-onnx-whisper-small
cp "$M/small-encoder.int8.onnx" "$ASSETS/whisper-small-encoder.int8.onnx"
cp "$M/small-decoder.int8.onnx" "$ASSETS/whisper-small-decoder.int8.onnx"
cp "$M/small-tokens.txt"        "$ASSETS/whisper-small-tokens.txt"
ls -la "$ASSETS" | grep whisper
