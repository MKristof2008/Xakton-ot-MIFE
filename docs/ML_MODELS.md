# Выбор моделей ML (зона feature/ml)

Все цифры — реальные измерения на этом репозитории, если не указано иное.
Устройство замеров на ПК: Windows, CPU (без GPU). Целевое устройство: Android (SM-S938B).

## Архитектура ML-пайплайна (на устройстве)

```
аудио (любой формат)
  → AudioConverter (MediaCodec) → WAV 16 кГц моно
  → Whisper small (whisper.cpp, JNI, CPU arm64) → сегменты с таймкодами
  → фильтр галлюцинаций (границы > конца записи, пустой текст)
  → диаризация (sherpa-onnx, в разработке) → speaker_N
  → сшивка реплик с говорящими
  → локальная LLM (только пунктуация/регистр, флаг включения)
  → Transcript JSON {"transcript":[{speaker_id,start_ms,end_ms,text}]}
```

Инференс полностью локальный: в манифесте нет разрешения INTERNET
(проверено `aapt2 dump permissions`).

## ASR на устройстве: реализация

- Движок: **whisper.cpp** (master), собран из исходников в APK через
  CMake/NDK (`android/app/src/main/cpp/`). faster-whisper не подошёл:
  у ctranslate2 нет Android-сборок (проверено по PyPI).
- Модель: **ggml-small.bin** (~465 МБ, ~244M параметров, лицензия MIT,
  формат GGML). Кладётся в `filesDir/ggml-small.bin` (adb push при подготовке
  к защите), fallback — `assets/models/`.
- Код: `engine/WhisperBridge.kt` (JNI), `engine/WhisperAsrEngine.kt`,
  `android/app/src/main/cpp/whisper_jni.cpp`.
- Оптимизации (коммит 41295b4): `flash_attn = true`, автоподбор потоков
  (`hardware_concurrency/2`, в пределах 4–8), замер RTF в logcat (тег WhisperJNI),
  greedy-декодинг, `no_context = true` против зацикливания.

## Графики измерений

![WER моделей](img/wer_models.png)

![A/B роль LLM](img/wer_llm_ab.png)

![RTF](img/rtf.png)

Графики строятся скриптом `tools/make_ml_charts.py` из цифр этого документа
(ничего не выдумано: нет измерения — нет столбца).

## Тестовый набор

`artifacts/test-audio/` — 20 записей русской речи (VoxForge, 16 кГц моно) + эталонные
транскрипты. Замер: `evaluate_wer.py` (нормализация: регистр, пунктуация).

## ASR — кандидаты

| Модель | Движок | Размер | WER (20 записей RU) | RTF (ПК CPU) | Android | Лицензия | Вердикт |
|---|---|---|---|---|---|---|---|
| Whisper tiny | faster-whisper int8 | ~75 МБ | **0.345** | ~0.25 | whisper.cpp / sherpa-onnx | MIT | Нет: WER плохой |
| Whisper base | faster-whisper int8 | ~145 МБ | TODO | TODO | да | MIT | Запасной |
| Whisper small | faster-whisper int8 | ~480 МБ | **0.161** | **0.81** (5 записей) | да (q5/q8) | MIT | **Выбран** |
| Whisper small | faster-whisper float16 | ~950 МБ | TODO | TODO | нет (тяжёлый) | MIT | Нет для телефона |
| GigaAM v2 (RNNT/CTC) | onnx / sherpa-onnx | ~250–600 МБ | TODO | TODO | sherpa-onnx | проверить | Кандидат если small не влезет в RTF |
| Zipformer RU (sherpa-onnx) | sherpa-onnx | ~200 МБ | TODO | TODO | да, быстрый | проверить | Кандидат по скорости |

Выбран **Whisper small (int8/q5)** как базовый: измеренный WER 0.161 близок к цели <0.15,
модель свободная, есть проверенные пути портирования на Android (whisper.cpp, sherpa-onnx).

## Диаризация — кандидаты

| Модель | Движок | Android | Лицензия | Вердикт |
|---|---|---|---|---|
| pyannote/speaker-diarization-3.1 | torch (ПК-референс) | нет (torch тяжёлый) | MIT, gated | Референс для DER на ПК |
| sherpa-onnx: pyannote-segmentation-3.0 + wespeaker embedding + кластеризация | onnxruntime | **да** | MIT | **Выбран для Android** |
| SpeechBrain ECAPA + AHC | onnx | да | Apache-2.0 | Запасной |

План: на ПК меряем DER референсом pyannote 3.1; на телефон портируем через sherpa-onnx
(та же сегментация pyannote в ONNX + speaker embedding + агломеративная кластеризация).

## Локальная LLM (обязательна по ТЗ) — кандидаты

| Модель | Размер (Q4) | Движок | Роль | Вердикт |
|---|---|---|---|---|
| Gemma-2-2B-it GGUF Q4_K_M | ~1.6 ГБ | llama.cpp / sherpa | пунктуация+регистр+правка ASR | Кандидат |
| Qwen2.5-1.5B-Instruct GGUF Q4 | ~1.0 ГБ | llama.cpp | то же, сильнее в русском | **Первичный кандидат** |
| Phi-3-mini-4k Q4 | ~2.3 ГБ | llama.cpp | то же | Тяжеловат для телефона |
| Saiga/GigaChat-lite | ~1–2 ГБ | llama.cpp | русскоязычная | Проверить лицензию |

Роль LLM в пайплайне (настоящая, измеримая): восстановление пунктуации и регистра поверх
выхода ASR + исправление явных ASR-ошибок. Флаг включения, A/B-замер WER с/без LLM —
если ухудшает, ограничиваем только пунктуацией (она в любом случае остаётся в пайплайне).

### A/B-замер (ПК, Qwen2.5-1.5B-Instruct Q4_K_M, llama.cpp b8027)

Датасет: `artifacts/test-audio/dialog_2spk.wav` (79.8 с, 8 реплик, 2 спикера, VoxForge RU).
Инструмент: `tools/llm_clean.py` + `tools/ab_wer.py`.

| Вариант | WER |
|---|---|
| ASR (whisper small int8), без LLM | 0.1443 |
| ASR + LLM-очистка (полный промпт, правка слов) | 0.1546 |
| ASR + LLM-очистка (только пунктуация и регистр) | 0.1443 |

Дельта полного режима +0.010 — правка текста LLM **ухудшает** WER (модель
«додумывает» слова: «кнул трубкой» → «кинул трубку»). Поэтому роль LLM
ограничена безопасной: **только пунктуация и регистр**, построчно по одной
реплике, слова не меняются (`tools/llm_clean.py`, промпт «расставь знаки
препинания и заглавные буквы, слова не трогай»). Дельта WER +0.0000 — метрика
не страдает, а читаемость текста и баллы за UX/экспорт выигрывают. LLM остаётся
реальной частью пайплайна с флагом включения.

## Хранение на устройстве (без сети)

Whisper small int8 (~480 МБ) + сегментация (~6 МБ) + embedding (~30 МБ) + LLM (~1 ГБ)
→ слишком много для git и тяжело для APK. Предложение (согласовать с капитаном):
- в git — только скрипты скачивания и хэши (Git LFS или внешнее хранилище);
- в APK/assets — только сегментация+embedding (мелкие);
- ASR и LLM-модели кладутся в `filesDir/models/` — для защиты заранее копируем через adb
  (ТЗ требует «модели на устройстве к моменту проверки», не обязательно внутри APK).

## Открытые вопросы

1. HF-токен для pyannote 3.1 (gated) — для референсного DER на ПК.
2. Лимит размера APK у организаторов? От этого зависит схема хранения.
