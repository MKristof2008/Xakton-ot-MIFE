"""Клиент AITunnel (OpenAI-совместимый API) для пайплайна
«аудио -> транскрипт -> протокол». Читает настройки из .env."""
import os

import requests
from dotenv import load_dotenv

import prompts

load_dotenv()

API_KEY = os.environ["AITUNNEL_API_KEY"]
BASE_URL = os.getenv("AITUNNEL_BASE_URL", "https://api.aitunnel.ru/v1")
MODEL = os.getenv("AITUNNEL_MODEL", "gpt-4o-mini")


def chat(messages: list[dict], model: str | None = None, temperature: float = 0.2) -> str:
    """Один запрос к chat/completions, возвращает текст ответа."""
    r = requests.post(
        f"{BASE_URL}/chat/completions",
        headers={"Authorization": f"Bearer {API_KEY}"},
        json={"model": model or MODEL, "messages": messages, "temperature": temperature},
        timeout=120,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"]


def clean_transcript(transcript: str) -> str:
    return chat(prompts.build_messages(prompts.CLEAN_PROMPT, transcript))


def make_minutes(transcript: str, context: str = "") -> str:
    return chat(prompts.build_minutes_messages(transcript, context))


def ask(question: str, fragments: list[str]) -> str:
    return chat(prompts.build_qa_messages(question, fragments))


if __name__ == "__main__":
    demo = "[00:01] Спикер 1: привет, собрались обсудить релиз.\n[00:10] Спикер 2: да, выпускаем в пятницу."
    print(make_minutes(demo))
