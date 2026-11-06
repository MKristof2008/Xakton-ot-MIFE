# Нейро-стенографист — Android-приложение

Локальная расшифровка диалогов: речь → текст, кто говорит, таймкоды, экспорт JSON.
**Всё считается на устройстве, сеть не используется.**

| | |
|---|---|
| Пакет | `ru.mifi.stenograf` |
| minSdk / targetSdk | 26 / 34 |
| ABI | `arm64-v8a` |
| Размер APK | ~235 МБ (модели внутри, чтобы работало без сети сразу после установки) |
| Разрешения | **ни одного сетевого** — `INTERNET` и `ACCESS_NETWORK_STATE` удалены из манифеста |

## Сборка

```bash
cd android
./scripts/build.sh          # скачает библиотеки (~70 МБ) и модели (~200 МБ), прогонит тесты, соберёт APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Или по шагам:

```bash
./scripts/fetch_libs.sh     # sherpa-onnx.aar + onnxruntime-genai.aar -> app/libs
./scripts/fetch_models.sh   # whisper-base int8 + pyannote-seg + 3D-Speaker -> assets
./gradlew testDebugUnitTest assembleDebug
```

Требуется JDK 17+ и Android SDK (platform 34, build-tools 34.0.0). Сборка проверена:
Gradle 8.7, AGP 8.5.2, Kotlin 2.0.21.

## Пайплайн

```
аудиофайл (SAF, content://)
   ↓ AudioDecoder      MediaCodec/MediaExtractor → PCM float32 16 кГц моно
   ↓ AsrEngine         sherpa-onnx + Whisper base int8, language=ru, окна 20 с / перекрытие 2 с
   ↓ Diarizer          sherpa-onnx: pyannote segmentation-3.0 + 3D-Speaker ERes2Net + кластеризация
   ↓ Merge             разрезание сегментов по смене говорящего, склейка, нумерация speaker_N
   ↓ LlmPost           ONNX Runtime GenAI: пунктуация и регистр (опционально, с защитой от галлюцинаций)
   ↓ HexJson           JSON по схеме ТЗ + валидатор
```

| Модуль | Файл | Что делает |
|---|---|---|
| Декодер | `audio/AudioDecoder.kt` | любой формат → 16 кГц моно, ресемплинг, нормализация |
| ASR | `asr/AsrEngine.kt` | распознавание, таймкоды от начала файла |
| Диаризация | `diar/Diarizer.kt` | сегментация + эмбеддинги + кластеризация |
| Слияние | `output/Merge.kt` | порт `diarization.py::_best_speaker`, `output.py::merge_adjacent`, `normalize_speakers` |
| Экспорт | `output/HexJson.kt` | JSON + валидатор (порт `output.py::validate_hex`) |
| Локальная LLM | `llm/LlmPost.kt` | пунктуация; откат при потере слов или росте длины > 30 % |
| UI | `ui/*.kt` | импорт → прогресс → результат → экспорт |

## Модели

| Модель | Файл в `assets/models` | Размер | Лицензия |
|---|---|---|---|
| Whisper base int8 (multilingual) | `asr/whisper-base-*.onnx` | 152 МБ | MIT |
| pyannote segmentation 3.0 | `diar/segmentation-3.0.onnx` | 5.7 МБ | MIT |
| 3D-Speaker ERes2Net | `diar/3dspeaker_eres2net.onnx` | 38 МБ | Apache-2.0 |

Модели не хранятся в git — их скачивает `scripts/fetch_models.sh`.
В собранном APK они лежат в assets и распаковываются при первом запуске в `filesDir/models`.

**Локальная LLM** подключается опционально: положите модель ONNX Runtime GenAI
(`genai_config.json` + `*.onnx` + `*.onnx.data`) в `assets/models/llm/phi3-mini/`.
Без неё работает детерминированный пунктуатор — пайплайн не падает.

## Почему sherpa-onnx, а не whisper.cpp

| | sherpa-onnx (выбрано) | whisper.cpp |
|---|---|---|
| Готовый AAR для Android | ✅ | ❌ нужна своя сборка NDK + исходники в репозитории |
| Диаризация из коробки | ✅ pyannote + эмбеддинги + кластеризация | ❌ писать самому |
| Tаймкоды по токенам | ✅ | ✅ |
| Время до рабочего APK | часы | дни |

Отдельная JNI-обвязка над whisper.cpp осталась в `docs/legacy-android-stub/` как запасной путь,
если sherpa-onnx на конкретном устройстве покажет плохой WER.

## Ограничения

1. Только `arm64-v8a` — эмулятор x86 не подойдёт, нужен реальный телефон.
2. Whisper `base` — компромисс. Для точности важнее `small`, но RTF вырастет втрое.
3. Диаризация чувствительна к числу говорящих: авторежим ошибается на 4+; если известно — указывайте вручную.
4. Модель 3D-Speaker обучена на китайском/английском; на русской речи работает, но уступает pyannote 3.1 на ПК.
5. Первый запуск распаковывает ~200 МБ из assets — 10–30 секунд.
6. Длинные записи (> 30 мин) держат PCM в памяти: на 4 ГБ RAM это ~1 ГБ на час аудио.

## Тесты

```bash
./gradlew testDebugUnitTest
```

Покрыто: выбор спикера по пересечению, разрезание сегмента на двух говорящих,
нумерация спикеров, склейка соседних реплик, валидатор JSON, отбрасывание пустых сегментов.
Найденный тестами баг (склейка текста разных сегментов ASR) исправлен в `Merge.build`.