#!/usr/bin/env bash
# Скачивает модели в app/src/main/assets/models.
#
# В git модели не лежат: вместе это ~1 ГБ. Скрипт идемпотентен —
# уже скачанные файлы не перекачиваются.
#
# После него APK собирается самодостаточным: модели упакованы в assets и при
# первом запуске копируются в filesDir. Сеть нужна только здесь, на этапе
# подготовки; в приложении её нет.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$(cd "$HERE/../app/src/main" && pwd)/assets/models"
WORK="${TMPDIR:-/tmp}/stenograph-models"

mkdir -p "$ASSETS/asr" "$ASSETS/diar" "$ASSETS/llm" "$WORK"
cd "$WORK"

get() { # url target
    [ -s "$2" ] && { echo "уже есть: $(basename "$2")"; return; }
    echo "скачиваем: $(basename "$2")"
    curl -fL --retry 3 -o "$2" "$1"
}

echo "[1/3] Whisper small (multilingual, ru) — 465 МБ"
get "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin" ggml-small.bin
cp ggml-small.bin "$ASSETS/ggml-small.bin"

echo "[2/3] Диаризация: pyannote segmentation-3.0 + 3D-Speaker ERes2Net"
get "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" seg.tar.bz2
[ -d sherpa-onnx-pyannote-segmentation-3-0 ] || tar -xjf seg.tar.bz2
cp sherpa-onnx-pyannote-segmentation-3-0/model.onnx "$ASSETS/diar/segmentation-3.0.onnx"

get "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" emb.onnx
cp emb.onnx "$ASSETS/diar/3dspeaker_eres2net.onnx"

echo "[3/3] Локальная LLM: Qwen2.5-0.5B-Instruct Q4_K_M — 469 МБ"
get "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf" qwen.gguf
cp qwen.gguf "$ASSETS/llm/qwen2.5-0.5b-instruct-q4_k_m.gguf"

echo "Готово:"
du -sh "$ASSETS"/* "$ASSETS"/*/* 2>/dev/null | sort -h
