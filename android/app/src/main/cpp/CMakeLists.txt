cmake_minimum_required(VERSION 3.22.1)
project(stenograph_jni CXX)

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

# ARM-оптимизации: на телефоне это даёт основной выигрыш по скорости.
# Важно: флаги нужны не только JNI-обёртке, но и самой библиотеке whisper
# (в Debug-сборке весь C++ собирается -O0 — отсюда аномальный RTF).
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(ARM_OPT_FLAGS -O3 -march=armv8.2-a+dotprod)
    foreach(tgt whisper ggml ggml-base ggml-cpu)
        if(TARGET ${tgt})
            target_compile_options(${tgt} PRIVATE ${ARM_OPT_FLAGS})
        endif()
    endforeach()
    target_compile_options(whisper_jni PRIVATE ${ARM_OPT_FLAGS})
endif()
# --- Локальная LLM: llama.cpp ---
#
# Собран ОТДЕЛЬНО (scripts/build_llama_android.sh) в статические библиотеки.
# Почему не add_subdirectory: у llama.cpp своя копия ggml с теми же именами
# целей, что у whisper.cpp (ggml, ggml-base, ggml-cpu) — в одном CMake они
# конфликтуют. Скрытая видимость символов не даёт двум ggml подменить друг
# друга внутри одного процесса.
set(LLAMA_PREBUILT ${CMAKE_CURRENT_SOURCE_DIR}/llama-prebuilt/${ANDROID_ABI})
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

if(EXISTS ${LLAMA_PREBUILT}/libllama.a)
    add_library(llm_jni SHARED llm_jni.cpp)

    target_include_directories(llm_jni PRIVATE
        ${LLAMA_DIR}/include
        ${LLAMA_DIR}/ggml/include
    )

    target_link_libraries(llm_jni
        ${LLAMA_PREBUILT}/libllama.a
        ${LLAMA_PREBUILT}/libggml.a
        ${LLAMA_PREBUILT}/libggml-base.a
        ${LLAMA_PREBUILT}/libggml-cpu.a
        android
        log
        z
    )

    # Символы llama.cpp не должны утекать из .so: иначе при совпадении имён
    # с whisper.cpp линкер выберет произвольную реализацию.
    target_link_options(llm_jni PRIVATE -Wl,--exclude-libs,ALL)

    if(ANDROID_ABI STREQUAL "arm64-v8a")
        target_compile_options(llm_jni PRIVATE ${ARM_OPT_FLAGS})
    endif()
else()
    message(WARNING
        "Нет предсобранного llama.cpp в ${LLAMA_PREBUILT}. "
        "Локальная LLM недоступна: запустите android/scripts/build_llama_android.sh")
endif()
