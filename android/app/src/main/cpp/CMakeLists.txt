cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni CXX)

# whisper.cpp кладётся рядом: app/src/main/cpp/whisper.cpp
# Ожидаемая структура: whisper.cpp/CMakeLists.txt, whisper.cpp/whisper.h
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

if(NOT EXISTS ${WHISPER_DIR}/include/whisper.h)
    message(WARNING
        "whisper.cpp не найден в ${WHISPER_DIR}. "
        "Склонируйте: git clone https://github.com/ggerganov/whisper.cpp "
        "в app/src/main/cpp/. Сборка без него упадёт.")
endif()

add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE ${WHISPER_DIR}/include)

target_link_libraries(whisper_jni
    whisper
    android
    log
)

# ARM-оптимизации: на телефоне это даёт основной выигрыш по скорости
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(whisper_jni PRIVATE -O3)
endif()