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

# Цены gpt-4o-mini, $ за 1M токенов (input/output)
PRICE_IN, PRICE_OUT = 0.15, 0.60


class Stats:
    """Накопленная статистика запросов: токены, стоимость, время."""
    total_in = total_out = 0
    total_cost = 0.0
    total_time = 0.0
    calls = 0

    @classmethod
    def add(cls, usage: dict, elapsed: float) -> None:
        pin = usage.get("prompt_tokens", 0)
        pout = usage.get("completion_tokens", 0)
        cls.total_in += pin
        cls.total_out += pout
        cls.total_cost += pin / 1e6 * PRICE_IN + pout / 1e6 * PRICE_OUT
        cls.total_time += elapsed
        cls.calls += 1

    @classmethod
    def summary(cls) -> str:
        return (
            f"запросов: {cls.calls}, токены: {cls.total_in} in / {cls.total_out} out, "
            f"стоимость: ${cls.total_cost:.4f}, время: {cls.total_time:.1f} с"
        )


def chat(messages: list[dict], model: str | None = None, temperature: float = 0.2) -> str:
    """Один запрос к chat/completions, возвращает текст ответа."""
    import time

    t0 = time.monotonic()
    r = requests.post(
        f"{BASE_URL}/chat/completions",
        headers={"Authorization": f"Bearer {API_KEY}"},
        json={"model": model or MODEL, "messages": messages, "temperature": temperature},
        timeout=120,
    )
    r.raise_for_status()
    data = r.json()
    Stats.add(data.get("usage", {}), time.monotonic() - t0)
    return data["choices"][0]["message"]["content"]


def clean_transcript(transcript: str) -> str:
    return chat(prompts.build_messages(prompts.CLEAN_PROMPT, transcript))


def make_minutes(transcript: str, context: str = "") -> str:
    return chat(prompts.build_minutes_messages(transcript, context))


def ask(question: str, fragments: list[str]) -> str:
    return chat(prompts.build_qa_messages(question, fragments))


if __name__ == "__main__":
    demo = "[00:01] Спикер 1: привет, собрались обсудить релиз.\n[00:10] Спикер 2: да, выпускаем в пятницу."
    print(make_minutes(demo))
