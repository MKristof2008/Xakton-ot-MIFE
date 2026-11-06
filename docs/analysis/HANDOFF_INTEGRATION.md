# Передача: ML-движки готовы, нужна интеграция в UI

Дата: 07.11.2026. Ветка: `feature/ml` (запушена).

## Коротко

Диаризация и локальная LLM были заглушками — причём **и в release-сборке**.
Это прямое нарушение запрета ТЗ №4 («подменять работу модели заранее
подготовленной расшифровкой»): `StubDiarization` отдаёт три захардкоженных
интервала независимо от аудио, `StubLlm` просто ставит заглавную букву.

Теперь оба движка настоящие. Чтобы их включить, в UI нужно поменять **три
строки** — я не трогал `app/`, это твоя зона.

## Что менять

`android/app/src/main/java/ru/hexteam/stenograph/ui/AppViewModel.kt:80-86`
(и так же в `MainViewModel.kt:55-60`):

Было:

```kotlin
private val whisperAsr = ru.hexteam.stenograph.engine.WhisperAsrEngine(getApplication())

private val pipeline = Pipeline(
    asr = if (whisperAsr.isReady()) whisperAsr else Stubs.StubAsr(),
    diarization = Stubs.StubDiarization(),
    llm = Stubs.StubLlm(),
)
```

Стало:

```kotlin
private val pipeline = EnginesFactory.create(getApplication())
```

`EnginesFactory` — единственное место, где собирается пайплайн. Он сам
копирует модели из assets при первом запуске и подключает настоящие движки.

Если нужен признак «работаем на заглушках» для баннера на экране:

```kotlin
val usingStubs = !WhisperAsrEngine(getApplication()).isReady() ||
    !SherpaDiarizationEngine(getApplication()).isReady()
```

Заглушки после этого остаются только в debug-сборке и включаются явно —
их нельзя получить по умолчанию.

## Что появилось в движках

| Файл | Роль |
|---|---|
| `EnginesFactory.kt` | сборка пайплайна, единственная точка |
| `SherpaDiarizationEngine.kt` | диаризация: pyannote-3.0 + 3D-Speaker |
| `SpeakerPostProcess.kt` | склейка пауз, деление пауз между говорящими |
| `LocalLlmEngine.kt` | локальная LLM (llama.cpp), пунктуация с проверкой |
| `ModelStore.kt` | модели из assets → filesDir, атомарное копирование |
| `AudioPrep.kt` | WAV декодируется один раз для ASR и диаризации |
| `WavReader.kt` | потоковое чтение WAV в FloatArray |

Интерфейсы `AsrEngine` / `DiarizationEngine` / `LlmEngine` и `Pipeline`
не менялись — интеграция сводится к замене конструктора.

## Замеры (реальные, SM-S938B, Release)

| Что | Результат |
|---|---|
| ASR WER (20 записей VoxForge RU) | **0.245** (base), 0.368 (tiny) |
| ASR RTF | **0.208** |
| Диаризация DER | **0.082** (порог 0.7, допуск 250 мс) |
| Диаризация RTF | 0.17–0.22 |
| LLM: WER до/после пунктуации | **0.0000**, ~1 с на реплику |

Сводка: `docs/benchmarks/measurements.csv`. Как воспроизвести — ниже.

## Что нужно от тебя

1. **Заменить конструктор пайплайна** (см. выше) — без этого APK по-прежнему
   отдаёт заглушки, и ТЗ формально нарушено.
2. **Поправить `docs/SUBMISSION_CHECKLIST.md`**: там написано «заглушек в
   release нет» — на момент проверки это было неверно (классы `Stubs$*`
   присутствуют в dex release-APK).
3. **Размер APK**: с моделями внутри получается ~780 МБ. Варианты:
   - оставить всё в APK (работает с первого запуска, но тяжёлый);
   - выложить модели отдельно и класть в `filesDir/models` при установке
     (`ModelStore` уже умеет читать оттуда).
   Нужно твоё решение — это влияет на сценарий проверки экспертами.

## Как воспроизвести замеры

```bash
cd android
./scripts/fetch_libs.sh          # sherpa-onnx.aar (~47 МБ)
./scripts/build_llama_android.sh # статический llama.cpp (нужен NDK)

# модели в assets (в git не кладём, .gitignore):
#   models/ggml-base.bin                      whisper base
#   models/diar/segmentation-3.0.onnx         pyannote-3.0
#   models/diar/3dspeaker_eres2net.onnx       3D-Speaker
#   models/llm/qwen2.5-0.5b-instruct-q4_k_m.gguf

./gradlew :app:connectedDebugAndroidTest
adb logcat -d | grep -E "WER |BENCH|SWEEP|LLM "
```

## Отдельно про отказ от onnxruntime-genai

Изначально для LLM планировался `onnxruntime-genai` (он есть в `origin/main`).
Его AAR добавляет в манифест `INTERNET` и `ACCESS_NETWORK_STATE` и
`TelemetryInitializer` — ContentProvider с HTTP-клиентом Microsoft. Даже если
телеметрия не отправляет аудио, формально в релизном манифесте появляется
сетевое разрешение, а ТЗ требует обратного. Поэтому llama.cpp: чистый C++,
`LLAMA_CURL=OFF`, серверные части не собираются.
