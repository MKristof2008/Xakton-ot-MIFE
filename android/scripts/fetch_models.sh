#!/usr/bin/env bash
# Скачивает модели ASR и диаризации в app/src/main/assets/models.
# После этого `./gradlew assembleDebug` собирает самодостаточный APK (офлайн).
# Запускать один раз перед сборкой: ~200 МБ загрузки.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$HERE/../app/src/main/assets/models"
WORK="$HERE/../.models_tmp"
mkdir -p "$ASSETS/asr" "$ASSETS/diar" "$WORK"
cd "$WORK"

fetch() { [ -f "$2" ] || curl -L --fail -o "$2" "$1"; }

echo "[1/4] Whisper base (int8), русский+мультиязычный"
fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2" whisper-base.tar.bz2
[ -f sherpa-onnx-whisper-base/base-encoder.int8.onnx ] || tar -xjf whisper-base.tar.bz2
cp sherpa-onnx-whisper-base/base-encoder.int8.onnx "$ASSETS/asr/whisper-base-encoder.int8.onnx"
cp sherpa-onnx-whisper-base/base-decoder.int8.onnx "$ASSETS/asr/whisper-base-decoder.int8.onnx"
cp sherpa-onnx-whisper-base/base-tokens.txt        "$ASSETS/asr/whisper-base-tokens.txt"

echo "[2/4] pyannote segmentation 3.0"
fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" seg.tar.bz2
[ -f sherpa-onnx-pyannote-segmentation-3-0/model.onnx ] || tar -xjf seg.tar.bz2
cp sherpa-onnx-pyannote-segmentation-3-0/model.onnx "$ASSETS/diar/segmentation-3.0.onnx"

echo "[3/4] Speaker embeddings (3D-Speaker ERes2Net)"
fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" emb.onnx
cp emb.onnx "$ASSETS/diar/3dspeaker_eres2net.onnx"

echo "[4/4] Счётчики и проверка"
ls -la "$ASSETS/asr" "$ASSETS/diar"
echo "Готово. Дальше: ./gradlew assembleDebug"
echo "Локальная LLM (опционально): положить модель ONNX Runtime GenAI в"
echo "app/src/main/assets/models/llm/phi3-mini/ (genai_config.json + *.onnx + *.onnx.data)"
