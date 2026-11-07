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

echo "[2/3] pyannote segmentation-3.0"
get "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" seg.tar.bz2
[ -d sherpa-onnx-pyannote-segmentation-3-0 ] || tar -xjf seg.tar.bz2
cp sherpa-onnx-pyannote-segmentation-3-0/model.onnx "$ASSETS/diar/segmentation-3.0.onnx"

echo "[3/3] Эмбеддинги говорящего (3D-Speaker ERes2Net)"
get "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" emb.onnx
cp emb.onnx "$ASSETS/diar/3dspeaker_eres2net.onnx"

echo "Готово:"; ls -la "$ASSETS/asr" "$ASSETS/diar"
cat <<'TIP'

Опционально — Whisper small как запасной движок и эталон для сравнения:
  ./scripts/fetch_whisper.sh
(добавит 374 МБ к APK; без него приложение работает на русской модели)
TIP
