"""Клиент AITunnel (OpenAI-совместимый API) для пайплайна
«аудио -> транскрипт -> протокол». Читает настройки из .env.

Ключ читается лениво: импорт модуля не требует .env, поэтому локальный
ASR и диаризация работают без ключа. Ошибка возникает только в момент
запроса к API.
"""
import os

import requests
from dotenv import load_dotenv

import prompts

load_dotenv()

DEFAULT_BASE_URL = "https://api.aitunnel.ru/v1"
DEFAULT_MODEL = "gpt-4o-mini"


def api_key() -> str:
    """Ключ AITunnel или понятная ошибка вместо KeyError."""
    key = os.getenv("AITUNNEL_API_KEY")
    if not key:
        raise RuntimeError(
            "Не задан AITUNNEL_API_KEY. Скопируйте .env.example в .env и впишите "
            "ключ, либо работайте локально (--backend local без LLM-шагов)."
        )
    return key


def base_url() -> str:
    return os.getenv("AITUNNEL_BASE_URL", DEFAULT_BASE_URL)


def model_name() -> str:
    return os.getenv("AITUNNEL_MODEL", DEFAULT_MODEL)

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
        f"{base_url()}/chat/completions",
        headers={"Authorization": f"Bearer {api_key()}"},
        json={
            "model": model or model_name(),
            "messages": messages,
            "temperature": temperature,
        },
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