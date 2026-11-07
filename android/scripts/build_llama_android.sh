#!/usr/bin/env bash
# Собирает llama.cpp под Android как СТАТИЧЕСКИЕ библиотеки.
#
# Почему отдельно, а не add_subdirectory рядом с whisper.cpp:
# у llama.cpp и whisper.cpp свои копии ggml с одинаковыми именами целей
# (ggml, ggml-base, ggml-cpu) — в одном CMake они конфликтуют, а как
# отдельные .so ещё и подменяли бы друг другу символы в общем процессе.
# Статическая линковка со скрытой видимостью символов даёт один
# libllm_jni.so, который ни на что не влияет извне.
#
# Результат: app/src/main/cpp/llama-prebuilt/<abi>/libllama.a и libggml*.a
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$(cd "$HERE/../app/src/main/cpp" && pwd)"
SRC="${LLAMA_SRC:-$CPP/llama.cpp}"
OUT="$CPP/llama-prebuilt"
BUILD="$SRC/build-android"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
if [ -z "${NDK:-}" ] || [ ! -d "$NDK" ]; then
    echo "Не найден NDK. Задайте ANDROID_NDK_HOME." >&2
    exit 1
fi
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
echo "NDK: $NDK"

if [ ! -d "$SRC" ]; then
    echo "Нет исходников llama.cpp в $SRC"
    echo "Клонируйте: git clone --depth 1 https://github.com/ggml-org/llama.cpp $SRC"
    exit 1
fi

CMAKE_BIN="${CMAKE_BIN:-$(command -v cmake || true)}"
if [ -z "${CMAKE_BIN:-}" ]; then
    CMAKE_BIN="$(ls -d "$SDK"/cmake/*/bin/cmake.exe "$SDK"/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -1)"
fi
if [ -z "${CMAKE_BIN:-}" ] || [ ! -x "$CMAKE_BIN" ]; then
    echo "Не найден cmake. Установите CMake в Android SDK или задайте CMAKE_BIN." >&2
    exit 1
fi
echo "cmake: $CMAKE_BIN"

# Генератор задаётся явно: на Windows CMake по умолчанию выбирает NMake,
# а он в Android SDK не поставляется — конфигурация падала.
NINJA="$(ls -d "$SDK"/cmake/*/bin/ninja.exe "$SDK"/cmake/*/bin/ninja 2>/dev/null | sort -V | tail -1)"
if [ -z "${NINJA:-}" ] || [ ! -x "$NINJA" ]; then
    echo "Не найден ninja в Android SDK. Установите CMake через sdkmanager." >&2
    exit 1
fi

"$CMAKE_BIN" -G Ninja -S "$SRC" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_STATIC=ON \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DGGML_BACKEND_DL=OFF \
    -DGGML_ALL_WARNINGS=OFF \
    -DGGML_FATAL_WARNINGS=OFF \
    -DCMAKE_C_FLAGS="-fvisibility=hidden -O3 -DNDEBUG" \
    -DCMAKE_CXX_FLAGS="-fvisibility=hidden -O3 -DNDEBUG" \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_TOOLS=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_CURL=OFF

"$CMAKE_BIN" --build "$BUILD" --target llama ggml ggml-base ggml-cpu -j "$(nproc 2>/dev/null || echo 4)"

mkdir -p "$OUT/arm64-v8a"
# Имена файлов зависят от версии llama.cpp — копируем всё, что похоже на нужное.
find "$BUILD" -name "libllama.a" -o -name "libggml*.a" | while read -r f; do
    cp "$f" "$OUT/arm64-v8a/"
done
ls -la "$OUT/arm64-v8a"
echo "Готово: $OUT/arm64-v8a"
