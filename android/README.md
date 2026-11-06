# Android-приложение (пункт 6 ТЗ)

## Статус: исходники готовы, сборка НЕ проверена

В этом окружении нет Android SDK, Gradle и JDK нужной версии
(стоит Java 8, нужна 17+), поэтому APK не собран и не запускался.
Ниже — рабочий каркас проекта; сборку нужно выполнить на машине
с Android Studio.

## Важное ограничение: faster-whisper на Android не работает

Проверено по индексу PyPI: у `ctranslate2` (ядро faster-whisper) **нет
Android-колёс** — только `manylinux` и `win`. То есть Python-пайплайн
из корня репозитория (`asr.py`, `diarization.py`) на устройство
напрямую не переносится.

Реальные варианты:

| Вариант | Плюсы | Минусы |
|---|---|---|
| **whisper.cpp через JNI** (выбран) | работает нативно, реально собирается под arm64 | нужна своя C++-обвязка |
| ONNX Runtime Mobile + whisper-onnx | проще интеграция | тяжелее артефакты |
| Chaquopy + пересборка ctranslate2 | остаётся Python | нужна сборка ctranslate2 под Android, нет готовых колёс |

Поэтому в `app/src/main/cpp` лежит JNI-обвязка над whisper.cpp: это
единственный путь, который действительно доедет до APK без пересборки
всей экосистемы.

## Структура

```
android/
  settings.gradle.kts          корневые настройки Gradle
  build.gradle.kts             версии плагинов
  gradle.properties
  app/
    build.gradle.kts           модуль приложения, NDK + CMake
    src/main/
      AndroidManifest.xml
      java/ru/mifi/neurosteng/
        MainActivity.kt        выбор файла, запуск, показ расшифровки
        TranscriptAdapter.kt   список «спикер + время + текст»
        WhisperBridge.kt       JNI-мост к whisper.cpp
        HexJson.kt             формат вывода по ТЗ
      cpp/
        CMakeLists.txt
        whisper_jni.cpp        JNI-функции, вызов whisper_full
      res/layout/activity_main.xml
      res/values/strings.xml
  README.md                    этот файл
```

## Что нужно сделать перед сборкой

1. Положить `whisper.cpp` в `app/src/main/cpp/whisper.cpp`
   (`git clone https://github.com/ggerganov/whisper.cpp`).
2. Скачать модель `ggml-base.bin` (~150 МБ) в `app/src/main/assets/models/`.
3. Установить Android Studio (JDK 17+, NDK 26+, CMake 3.22+).
4. `./gradlew assembleRelease`.

## Формат вывода

Совпадает с ТЗ и с `output.py` из корня репозитория:

```json
{"transcript":[{"speaker_id":"speaker_1","start_ms":0,"end_ms":4200,"text":"..."}]}
```

## Диаризация на устройстве

В каркасе не реализована. Для неё нужен ONNX-вариант эмбеддингов
(ECAPA в ONNX + кластеризация на Kotlin), либо упрощённое разделение
по паузам. Честно: это следующий этап, а не готовая функция.