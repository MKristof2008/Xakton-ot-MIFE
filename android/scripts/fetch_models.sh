#!/usr/bin/env bash
# Кладёт модели ASR и диаризации в app/src/main/assets/models (~120 МБ).
# После этого assembleDebug собирает самодостаточный офлайн-APK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$(cd "$HERE/../app/src/main" && pwd)/assets/models"
WORK="${TMPDIR:-/tmp}/stenograf-models"
mkdir -p "$ASSETS/asr" "$ASSETS/diar" "$WORK"

cd "$WORK"
get() { [ -s "$2" ] || curl -fL --retry 3 -o "$2" "$1"; }

echo "[1/3] Русская модель ASR: zipformer-ru (Vosk model-ru, Apache-2.0)"
# Обучена только на русском: знает разговорную лексику и мат, не уходит
# в английские петли, как Whisper. 71 МБ против 374 МБ у whisper-small.
get "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-ru-int8-2025-04-20.tar.bz2" ru.tar.bz2
[ -d sherpa-onnx-zipformer-ru-int8-2025-04-20 ] || tar -xjf ru.tar.bz2
M=sherpa-onnx-zipformer-ru-int8-2025-04-20
cp "$M/encoder.int8.onnx" "$ASSETS/asr/ru-zipformer-encoder.int8.onnx"
cp "$M/decoder.onnx"      "$ASSETS/asr/ru-zipformer-decoder.onnx"
cp "$M/joiner.int8.onnx"  "$ASSETS/asr/ru-zipformer-joiner.int8.onnx"
cp "$M/tokens.txt"        "$ASSETS/asr/ru-zipformer-tokens.txt"

echo "[2/4] Многоязычный Whisper base — английская речь"
# Zipformer-ru английского не понимает вовсе, а Whisper multilingual знает
# и русский, и английский (и ещё ~90 языков) и умеет сам определять язык.
# int8: 29 МБ энкодер + 130 МБ декодер.
get "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2" ws-base.tar.bz2
[ -d sherpa-onnx-whisper-base ] || tar -xjf ws-base.tar.bz2
cp sherpa-onnx-whisper-base/base-encoder.int8.onnx "$ASSETS/asr/whisper-base-encoder.int8.onnx"
cp sherpa-onnx-whisper-base/base-decoder.int8.onnx "$ASSETS/asr/whisper-base-decoder.int8.onnx"
cp sherpa-onnx-whisper-base/base-tokens.txt        "$ASSETS/asr/whisper-base-tokens.txt"

echo "[3/4] pyannote segmentation-3.0"
get "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" seg.tar.bz2
[ -d sherpa-onnx-pyannote-segmentation-3-0 ] || tar -xjf seg.tar.bz2
cp sherpa-onnx-pyannote-segmentation-3-0/model.onnx "$ASSETS/diar/segmentation-3.0.onnx"

echo "[4/4] Эмбеддинги говорящего (3D-Speaker ERes2Net)"
get "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" emb.onnx
cp emb.onnx "$ASSETS/diar/3dspeaker_eres2net.onnx"

echo "Готово:"; ls -la "$ASSETS/asr" "$ASSETS/diar"
