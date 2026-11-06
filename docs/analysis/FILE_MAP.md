# Карта файлов зоны ML (feature/ml)

Каждый файл — в одном месте, с одним назначением. Дубли не допускаются:
если нужна та же логика — импортируем, не копируем.

## Код на устройстве (Android)

| Файл | Назначение |
|---|---|
| `android/app/src/main/cpp/whisper_jni.cpp` | JNI-обёртка whisper.cpp: инференс, фильтр спецтокенов, замер RTF, лог транскрипта |
| `android/app/src/main/cpp/CMakeLists.txt` | Сборка нативной библиотеки |
| `.../engine/WhisperBridge.kt` | Kotlin-мост к JNI, поиск модели (filesDir → assets) |
| `.../engine/WhisperAsrEngine.kt` | Реализация ASR-шага пайплайна |
| `.../engine/AudioConverter.kt` | Декодинг любого аудио → WAV 16 кГц моно (MediaCodec) |
| `.../engine/Engines.kt` | Контракты движков |
| `.../engine/Stubs.kt` | Заглушки этапов до интеграции |

## Код на ПК (Python-пайплайн и оценка)

| Файл | Назначение |
|---|---|
| `ml/llm.py` | LLM-постобработка и протокол совещания (JSON-вывод, валидация) |
| `ml/prompts.py` | Все промпты LLM (только здесь, больше нигде) |
| `tools/evaluate_wer.py` | Замер WER на тестовом наборе |
| `tools/ab_wer.py` | A/B-замер WER с/без LLM |
| `tools/llm_clean.py` | LLM-очистка текста (пунктуация/регистр), не трогать — A/B-тест |
| `tools/live_dictation.py` | Живая диктовка с микрофона ПК |
| `tools/make_dialog.py` | Генерация тестовых диалогов |
| `tools/test_minutes.py` | Тест протокола совещания |

## Скрипты графиков (только из данных документов)

| Файл | Строит |
|---|---|
| `tools/make_ml_charts.py` | `docs/img/wer_models.png`, `wer_llm_ab.png`, `rtf.png` |
| `tools/make_run_charts.py` | `docs/img/runs_rtf.png` из таблицы `docs/runs/README.md` |
| `tools/make_error_charts.py` | `docs/img/errors_by_type.png`, `errors_status.png` из реестра |

## Документация

| Папка | Содержимое |
|---|---|
| `docs/` | Верхний уровень: ML_MODELS.md (модели и замеры), ARCHITECTURE.md, TEST_RESULTS.md, LIMITATIONS.md и прочие доки сдачи |
| `docs/runs/` | Архив живых прогонов на телефоне: run-NNN.md + сводная таблица |
| `docs/analysis/` | ERROR_ANALYSIS.md — реестр ошибок E1–E7 с источниками |
| `docs/img/` | Все графики (генерируются скриптами, не править руками) |
| `docs/hackathon/` | ТЗ и контракты хакатона |
| `docs/design/` | UI-макеты (зона фронтенда) |

## Правила

1. Промпты — только в `ml/prompts.py`; новые промпты не размазывать по коду.
2. Цифра в доке = измерение с источником (logcat/коммит/тест); нет данных — TODO.
3. Графики генерируются скриптами из таблиц документов, не рисуются вручную.
4. Один файл — одна роль; при добавлении файла — дописать строку в эту карту.
