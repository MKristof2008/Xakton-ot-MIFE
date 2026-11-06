"""Пайплайн «аудио -> транскрипт -> протокол» поверх client.py.

Этапы:
1. transcribe_audio — аудиофайл -> текст через AITunnel Whisper
2. clean_transcript — исправление ошибок ASR
3. make_minutes — протокол; для длинных транскриптов map-reduce
   (CHUNK_PROMPT по фрагментам, MERGE_PROMPT для объединения)
4. ask — вопрос к записи по фрагментам (QA_PROMPT)
"""
import json
import os

import requests

import prompts
from client import API_KEY, BASE_URL, chat

# Порог длины транскрипта (символов), после которого включается map-reduce
CHUNK_SIZE = 12_000


def transcribe_audio(audio_path: str, model: str | None = None) -> str:
    """Аудиофайл -> текст через /audio/transcriptions (Whisper-совместимый API)."""
    with open(audio_path, "rb") as f:
        r = requests.post(
            f"{BASE_URL}/audio/transcriptions",
            headers={"Authorization": f"Bearer {API_KEY}"},
            files={"file": (os.path.basename(audio_path), f)},
            data={
                "model": model or os.getenv("AITUNNEL_STT_MODEL", "whisper-1"),
                "response_format": "verbose_json",
                "timestamp_granularities[]": "segment",
            },
            timeout=600,
        )
    r.raise_for_status()
    data = r.json()
    segments = data.get("segments")
    if not segments:
        return data.get("text", "")
    lines = []
    for seg in segments:
        start = int(seg.get("start", 0))
        ts = f"[{start // 60:02d}:{start % 60:02d}]"
        lines.append(f"{ts} {seg.get('text', '').strip()}")
    return "\n".join(lines)


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


def _strip_markdown(text: str) -> str:
    """Модель иногда оборачивает JSON в ```json ... ``` — снимаем обёртку."""
    t = text.strip()
    if t.startswith("```"):
        t = t.split("\n", 1)[1] if "\n" in t else t[3:]
        if t.endswith("```"):
            t = t[:-3]
    return t.strip()
