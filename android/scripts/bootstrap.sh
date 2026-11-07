#!/usr/bin/env bash
# Полная подготовка проекта к сборке с нуля: от чистого клона до рабочего APK.
#
# Что скачивается и почему не в git:
#   libs/sherpa-onnx.aar       47 МБ  — нативная библиотека диаризации и ASR
#   cpp/whisper.cpp            ~55 МБ — исходники ASR (собираются в APK)
#   cpp/llama.cpp              ~158 МБ — исходники локальной LLM
#   assets/models              ~1 ГБ  — веса моделей
# Всё вместе ~1.3 ГБ: держать это в git бессмысленно, поэтому — скрипт.
#
# Использование:
#   cd android && ./scripts/bootstrap.sh
#
# Требуется: curl, tar, git, JDK 17, Android SDK с NDK и CMake.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID="$(cd "$HERE/.." && pwd)"
CPP="$ANDROID/app/src/main/cpp"

echo "=== 1/5. Нативные библиотеки (sherpa-onnx) ==="
bash "$HERE/fetch_libs.sh"

echo
echo "=== 2/5. Исходники whisper.cpp ==="
if [ ! -d "$CPP/whisper.cpp" ]; then
    git clone --depth 1 https://github.com/ggerganov/whisper.cpp "$CPP/whisper.cpp"
else
    echo "уже есть: whisper.cpp"
fi

echo
echo "=== 3/5. Исходники llama.cpp ==="
if [ ! -d "$CPP/llama.cpp" ]; then
    git clone --depth 1 https://github.com/ggml-org/llama.cpp "$CPP/llama.cpp"
else
    echo "уже есть: llama.cpp"
fi

echo
echo "=== 4/5. Сборка llama.cpp под Android ==="
if [ -f "$CPP/llama-prebuilt/arm64-v8a/libllama.a" ]; then
    echo "уже собрано: llama-prebuilt"
else
    bash "$HERE/build_llama_android.sh"
fi

echo
echo "=== 5/5. Модели ==="
bash "$HERE/fetch_models.sh"

echo
echo "Готово. Дальше:"
echo "  cd $ANDROID"
echo "  ./gradlew :app:test                    # юнит-тесты"
echo "  ./gradlew :app:assembleRelease         # APK"
