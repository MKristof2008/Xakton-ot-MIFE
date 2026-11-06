"""Пайплайн «аудио -> транскрипт -> протокол» поверх client.py.

Этапы:
1. transcribe_audio — аудиофайл -> текст с таймкодами и спикерами
   (asr.py + диаризация из diarize.py)
2. clean_transcript — исправление ошибок ASR
3. make_minutes — протокол; для длинных транскриптов map-reduce
   (CHUNK_PROMPT по фрагментам, MERGE_PROMPT для объединения)
4. ask — вопрос к записи по фрагментам (QA_PROMPT)
"""
import json

import prompts
from speecher import DEFAULT_LANGUAGE
from client import chat

# Порог длины транскрипта (символов), после которого включается map-reduce
CHUNK_SIZE = 12_000

# ---------- Извлечение задач из кейсов/документов (хакатон МИФИ) ----------
TASKS_SCHEMA = {
    "cases": [
        {
            "case": "название кейса/задачи",
            "source": "источник (компания/документ)",
            "goal": "что нужно сделать",
            "requirements": ["требование или ограничение"],
            "data": "какие данные/инструменты нужны или null",
            "skills": ["Python", "SQL"],
            "difficulty": "easy | medium | hard | null",
        }
    ]
}

TASKS_PROMPT = (
    prompts.BASE_RULES
    + "\n\nЗадача: извлеки из текста кейсы и практические задачи (для хакатона, "
    "стажировки или внедрения). Для каждого кейса укажи цель, требования, "
    "нужные данные и навыки. Не выдумывай: если чего-то нет в тексте — null "
    "или пустой список.\n"
    "Верни СТРОГО валидный JSON без markdown и пояснений по схеме:\n"
    + json.dumps(TASKS_SCHEMA, ensure_ascii=False, indent=2)
)

TASKS_MERGE_PROMPT = (
    prompts.BASE_RULES
    + "\n\nНиже JSON-списки кейсов, извлечённые из нескольких документов. "
    "Объедини в один список: убери дубли, сгруппируй похожие кейсы, "
    "сохрани источники. Верни СТРОГО валидный JSON по схеме:\n"
    + json.dumps(TASKS_SCHEMA, ensure_ascii=False, indent=2)
)


def extract_tasks(text: str) -> dict:
    """Текст документа -> список кейсов/задач (dict). Длинные тексты — map-reduce."""
    if len(text) <= CHUNK_SIZE:
        raw = chat(prompts.build_messages(TASKS_PROMPT, text))
    else:
        partials = [
            chat(prompts.build_messages(TASKS_PROMPT, chunk))
            for chunk in _chunks(text)
        ]
        raw = chat(
            prompts.build_messages(
                TASKS_MERGE_PROMPT,
                "Фрагменты (JSON по порядку):\n" + "\n---\n".join(partials),
            )
        )
    return json.loads(_strip_markdown(raw))


def transcribe_audio(audio_path: str, model: str | None = None,
                     backend: str | None = None,
                     num_speakers: int | None = None,
                     embedding: str | None = None,
                     language: str | None = None,
                     diarization: bool = True,
                     return_result: bool = False):
    """Аудиофайл -> текст `[MM:SS] Спикер N: ...`.

    backend — "local" (faster-whisper, по умолчанию) или "aitunnel";
    embedding — "ecapa" (точнее) или "mfcc" (без torch/весов).
    При return_result=True возвращает (transcript, diarization_result).
    """
    import speecher as asr

    transcript, result = asr.run(
        audio_path,
        backend=backend or "local",
        num_speakers=num_speakers,
        language=language or DEFAULT_LANGUAGE,
        model=model,
        diarization=diarization,
        embedding=embedding,
    )
    text = transcript.as_text(with_speaker=diarization)
    if return_result:
        return text, result
    return text


def clean_transcript(transcript: str) -> str:
    return chat(prompts.build_messages(prompts.CLEAN_PROMPT, transcript))


def _chunks(transcript: str, size: int = CHUNK_SIZE) -> list[str]:
    """Режет транскрипт по строкам, не разрывая реплики."""
    chunks, current = [], ""
    for line in transcript.splitlines():
        if current and len(current) + len(line) + 1 > size:
            chunks.append(current)
            current = ""
        current += line + "\n"
    if current.strip():
        chunks.append(current)
    return chunks


def make_minutes(transcript: str, context: str = "") -> dict:
    """Транскрипт -> протокол (dict). Длинные записи — через map-reduce."""
    if len(transcript) <= CHUNK_SIZE:
        raw = chat(prompts.build_minutes_messages(transcript, context))
    else:
        partials = []
        for chunk in _chunks(transcript):
            partials.append(chat(prompts.build_messages(prompts.CHUNK_PROMPT, chunk)))
        raw = chat(
            prompts.build_messages(
                prompts.MERGE_PROMPT,
                "Фрагменты (JSON по порядку):\n" + "\n---\n".join(partials),
            )
        )
    return json.loads(_strip_markdown(raw))


def ask(question: str, fragments: list[str]) -> str:
    return chat(prompts.build_qa_messages(question, fragments))


def detect_speakers(transcript: str) -> dict[str, str | None]:
    """Определить реальные имена спикеров: {'Спикер 1': 'Имя' | None, ...}."""
    raw = chat(prompts.build_messages(prompts.SPEAKER_NAMES_PROMPT, transcript))
    return json.loads(_strip_markdown(raw))


def _strip_markdown(text: str) -> str:
    """Модель иногда оборачивает JSON в ```json ... ``` — снимаем обёртку."""
    t = text.strip()
    if t.startswith("```"):
        t = t.split("\n", 1)[1] if "\n" in t else t[3:]
        if t.endswith("```"):
            t = t[:-3]
    return t.strip()
