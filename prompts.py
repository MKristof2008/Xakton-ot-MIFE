"""Промпты для пайплайна «аудио -> транскрипт -> протокол».

Версия 2: адаптирована под ТЗ Hex.team (локальная LLM, Android).
Транскрипт ожидается в формате: [MM:SS] Спикер N: текст
"""
import json

# ---------- Общие правила ----------
BASE_RULES = (
    "Ты — профессиональный протоколист. Работаешь только с текстом транскрипта.\n"
    "Правила: не выдумывай факты, имена, сроки и цифры; если данных нет — "
    "пиши null или пустой список; сохраняй таймкоды [MM:SS] из источника; "
    "пиши на языке записи (по умолчанию русский)."
)

# ---------- Для локальной LLM (Gemma-2B, Phi-3) ----------
LOCAL_BASE_RULES = (
    "Ты — помощник протоколиста. Работай только с текстом. "
    "Не выдумывай факты. Сохраняй таймкоды [MM:SS]. Пиши по-русски."
)

# ---------- 1. Очистка ASR-ошибок ----------
CLEAN_PROMPT = (
    BASE_RULES
    + "\n\nЗадача: исправь явные ошибки распознавания речи (опечатки, склеенные "
    "слова, неверные окончания, пунктуацию). Не перефразируй, не сокращай, не "
    "добавляй смысл. Убери слова-паразиты только если они не несут смысла. "
    "Сохрани разметку [MM:SS] Спикер N: в каждой строке. "
    "Если слово неразборчиво — оставь как есть и добавь пометку [?].\n\n"
    "Верни только исправленный транскрипт."
)

# Короткая версия для локальной LLM
CLEAN_PROMPT_LOCAL = (
    LOCAL_BASE_RULES
    + "\n\nИсправь ошибки распознавания: опечатки, пунктуацию, склеенные слова. "
    "Не перефразируй. Сохрани [MM:SS] Спикер N:.\n\n"
    "Транскрипт:\n"
)

# ---------- 2. Структурированный протокол (облачная версия) ----------
MINUTES_SCHEMA = {
    "title": "короткое название встречи (до 8 слов)",
    "summary": "резюме в 3-5 предложениях",
    "topics": [{"name": "тема", "start": "MM:SS", "key_points": ["тезис"]}],
    "decisions": [{"decision": "что решили", "timestamp": "MM:SS"}],
    "action_items": [
        {
            "task": "что сделать",
            "owner": "ответственный или null",
            "deadline": "срок как в записи или null",
            "timestamp": "MM:SS",
        }
    ],
    "open_questions": [{"question": "вопрос без ответа", "timestamp": "MM:SS"}],
}

MINUTES_PROMPT = (
    BASE_RULES
    + "\n\nЗадача: составь протокол встречи по транскрипту.\n"
    "Задача (action item) — только если кто-то явно взял обязательство или "
    "ему поручили; обсуждение «надо бы» — не задача. Ответственного указывай "
    "только если он назван или однозначно понятен из реплик.\n"
    "Верни СТРОГО валидный JSON без markdown и пояснений по схеме:\n"
    + json.dumps(MINUTES_SCHEMA, ensure_ascii=False, indent=2)
)

# ---------- 3. Формат по ТЗ Hex.team (локальная LLM) ----------
# speaker_id, start_ms, end_ms, text
HEX_SCHEMA = {
    "transcript": [
        {
            "speaker_id": "speaker_1",
            "start_ms": 1250,
            "end_ms": 4380,
            "text": "Добрый день!"
        }
    ]
}

HEX_FORMAT_PROMPT = (
    LOCAL_BASE_RULES
    + "\n\nПреобразуй транскрипт в JSON по схеме:\n"
    + json.dumps(HEX_SCHEMA, ensure_ascii=False, indent=2)
    + "\n\nspeaker_id: speaker_1, speaker_2, ...\n"
    "start_ms, end_ms: время в миллисекундах (из [MM:SS])\n"
    "text: очищенный текст реплики\n\n"
    "Верни только JSON, без пояснений."
)

# ---------- 4. Диаризация: объединение реплик одного спикера ----------
DIARIZATION_MERGE_PROMPT = (
    LOCAL_BASE_RULES
    + "\n\nОбъедини подряд идущие реплики одного спикера в одну. "
    "Сохрани start_ms первой и end_ms последней. "
    "Верни JSON по той же схеме."
)

# ---------- 5. Длинные записи (map-reduce) ----------
CHUNK_PROMPT = (
    BASE_RULES
    + "\n\nЭто ФРАГМЕНТ длинной записи. Выдели: ключевые тезисы, решения, "
    "поручения (с владельцем и сроком, если есть), открытые вопросы. "
    "Сохрани таймкоды. Верни JSON по той же схеме, что и для полного протокола:\n"
    + json.dumps(MINUTES_SCHEMA, ensure_ascii=False, indent=2)
)

MERGE_PROMPT = (
    BASE_RULES
    + "\n\nНиже JSON-протоколы последовательных фрагментов одной записи. "
    "Объедини в один итоговый протокол: убери дубли, склей повторяющиеся темы, "
    "сохрани таймкоды, общее резюме напиши заново по всему содержанию. "
    "Верни СТРОГО валидный JSON по схеме:\n"
    + json.dumps(MINUTES_SCHEMA, ensure_ascii=False, indent=2)
)

# ---------- 6. Вопросы к записи (RAG-поиск) ----------
QA_PROMPT = (
    BASE_RULES
    + "\n\nОтвечай на вопрос ТОЛЬКО по приведённым фрагментам транскрипта. "
    "Сначала найди во фрагментах все реплики, относящиеся к вопросу, и ответь "
    "по ним, даже если формулировки отличаются от слов вопроса. "
    "К каждому утверждению добавляй таймкод [MM:SS] и спикера. "
    "Говори «В записи этого нет», только если во фрагментах действительно нет "
    "ничего по теме вопроса."
)

# ---------- 7. Переименование спикеров ----------
SPEAKER_NAMES_PROMPT = (
    BASE_RULES
    + "\n\nПо транскрипту определи реальные имена спикеров (обращения, "
    "представления). Верни JSON вида {\"Спикер 1\": \"Имя или null\"}. "
    "Не угадывай: нет явного упоминания — null."
)

# Короткая версия для локальной LLM
SPEAKER_NAMES_LOCAL = (
    LOCAL_BASE_RULES
    + "\n\nОпредели имена спикеров. Верни JSON: {\"Спикер 1\": \"Имя или null\"}. "
    "Не угадывай.\n\nТранскрипт:\n"
)


# ---------- Билдеры ----------
def build_messages(system: str, user_text: str) -> list[dict]:
    """Формат OpenAI-совместимых API."""
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": user_text},
    ]


def build_local_messages(system: str, user_text: str) -> list[dict]:
    """Для локальной LLM: системный промпт короче."""
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": user_text},
    ]


def build_minutes_messages(transcript: str, context: str = "") -> list[dict]:
    user = (f"Контекст: {context}\n\n" if context else "") + f"Транскрипт:\n{transcript}"
    return build_messages(MINUTES_PROMPT, user)


def build_qa_messages(question: str, fragments: list[str]) -> list[dict]:
    joined = "\n---\n".join(fragments)
    return build_messages(QA_PROMPT, f"Фрагменты:\n{joined}\n\nВопрос: {question}")


def build_hex_format_messages(transcript: str) -> list[dict]:
    """Для преобразования в формат Hex.team."""
    return build_local_messages(HEX_FORMAT_PROMPT, transcript)


# ---------- Конвертация форматов ----------
def ms_to_mmss(ms: int) -> str:
    """Миллисекунды -> [MM:SS]."""
    total_sec = ms // 1000
    return f"[{total_sec // 60:02d}:{total_sec % 60:02d}]"


def mmss_to_ms(mmss: str) -> int:
    """[MM:SS] -> миллисекунды."""
    parts = mmss.strip("[]").split(":")
    return (int(parts[0]) * 60 + int(parts[1])) * 1000


def to_hex_format(segments: list[dict]) -> dict:
    """Конвертирует внутренний формат в HEX_SCHEMA."""
    transcript = []
    for seg in segments:
        transcript.append({
            "speaker_id": seg.get("speaker_id", "speaker_1"),
            "start_ms": seg.get("start_ms", 0),
            "end_ms": seg.get("end_ms", 0),
            "text": seg.get("text", ""),
        })
    return {"transcript": transcript}
