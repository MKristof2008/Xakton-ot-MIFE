"""Постобработка текста локальной LLM (пункт 3 ТЗ).

Вход: сегменты ASR с speaker_id.
Выход: те же сегменты с исправленным текстом.

Задачи по ТЗ: пунктуация, капитализация, исправление ошибок распознавания.
НЕ перефразировать, сохранять смысл и таймкоды.

Два режима:
- llama — llama.cpp + GGUF-модель (Gemma-2-2B, Phi-3-mini, Qwen2.5).
  Нужен пакет llama-cpp-python и файл модели; ставится отдельно.
- rules — без модели вообще: капитализация, терминальная пунктуация,
  схлопывание повторов, пробелы. Работает всегда и ничего не выдумывает.

Режим выбирается автоматически: если llama-cpp есть и модель найдена —
llama, иначе rules. Явно: LLM_BACKEND=llama|rules|none.

Главное правило: если модель вернула текст, который слишком сильно
отличается от исходного, результат откатывается к правилам. Иначе LLM
начнёт «улучшать» расшифровку и подменит смысл — это прямо запрещено ТЗ.
"""

from __future__ import annotations

import os
import re

BACKEND_ENV = "LLM_BACKEND"
MODEL_ENV = "LLM_MODEL_PATH"
DEFAULT_MODEL = os.getenv(MODEL_ENV, "")

# Насколько текст может измениться после LLM, прежде чем откатить правку.
# 0.45 — правка пунктуации и регистра даёт доли, пересказ даёт больше.
MAX_EDIT_RATIO = 0.45
MAX_TOKENS = 256

SYSTEM_PROMPT = (
    "Ты приводишь в порядок расшифровку встречи. Правила:\n"
    "1. Расставь знаки препинания и заглавные буквы.\n"
    "2. Исправь только очевидные ошибки распознавания речи.\n"
    "3. НЕ перефразируй, НЕ сокращай, НЕ добавляй ничего от себя.\n"
    "4. Сохрани все слова и их порядок, насколько возможно.\n"
    "5. Верни только исправленный текст одной строкой, без пояснений."
)

# Частые ошибки распознавания русского: заменяем аккуратно, по границам слов.
COMMON_FIXES = {
    "тобиш": "то есть",
    "кароче": "короче",
    "щас": "сейчас",
    "ваще": "вообще",
    "вообщем": "в общем",
}

# Заикание ASR: «в в этом», «ну ну». Схлопываем ТОЛЬКО эти служебные слова.
# Общее правило «любой повтор -> одно слово» портило смысл: «долго долго
# обсуждали» и «давно давно» — нормальная русская речь, её сокращать нельзя.
FILLER_WORDS = {
    "в", "и", "а", "но", "на", "не", "ну", "да", "нет", "то", "же",
    "вот", "это", "как", "что", "мы", "я", "он", "она", "так",
}


def collapse_stutter(text: str) -> str:
    """Убирает повторы служебных слов, не трогая смысловые повторы."""
    def repl(match: re.Match) -> str:
        word = match.group(1)
        return word if word.lower() in FILLER_WORDS else match.group(0)

    return re.sub(r"\b(\w+)(?:\s+\1\b)+", repl, text, flags=re.IGNORECASE)


def _norm_words(text: str) -> list[str]:
    return re.sub(r"[^\w\s]", " ", text.lower(), flags=re.UNICODE).split()


def edit_ratio(original: str, edited: str) -> float:
    """Доля изменённых слов (Левенштейн по словам / длина исходника)."""
    a, b = _norm_words(original), _norm_words(edited)
    if not a:
        return 0.0 if not b else 1.0
    prev = list(range(len(b) + 1))
    for i, wa in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, wb in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (wa != wb))
        prev = cur
    return prev[-1] / len(a)


# ---------- правила ----------

def clean_rules(text: str) -> str:
    """Косметика без модели: регистр, повторы, пробелы, терминальная точка."""
    if not text:
        return text

    cleaned = re.sub(r"\s+", " ", text).strip()

    # «в в этом» -> «в этом»: только служебные слова, смысловые повторы не трогаем
    cleaned = collapse_stutter(cleaned)

    for wrong, right in COMMON_FIXES.items():
        cleaned = re.sub(rf"\b{wrong}\b", right, cleaned, flags=re.IGNORECASE)

    if cleaned and cleaned[0].islower():
        cleaned = cleaned[0].upper() + cleaned[1:]

    if cleaned and cleaned[-1] not in ".!?…":
        cleaned += "."

    # Пробелы перед знаками препинания
    cleaned = re.sub(r"\s+([,.;:!?])", r"\1", cleaned)
    return cleaned


# ---------- llama.cpp ----------

def _load_llama(model_path: str):
    from llama_cpp import Llama

    return Llama(
        model_path=model_path,
        n_ctx=2048,
        n_threads=max(1, (os.cpu_count() or 2) // 2),
        verbose=False,
    )


def clean_llama(text: str, llm) -> str:
    """Прогон через локальную GGUF-модель. Возвращает текст или '' при сбое."""
    out = llm.create_chat_completion(
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": text},
        ],
        temperature=0.0,
        max_tokens=MAX_TOKENS,
    )
    try:
        return (out["choices"][0]["message"]["content"] or "").strip()
    except (KeyError, IndexError, TypeError):
        return ""


# ---------- выбор режима ----------

def available_backend() -> str:
    """llama, если есть пакет и файл модели; иначе rules."""
    backend = os.getenv(BACKEND_ENV)
    if backend in {"llama", "rules", "none"}:
        return backend
    try:
        import llama_cpp  # noqa: F401

        if DEFAULT_MODEL and os.path.exists(DEFAULT_MODEL):
            return "llama"
    except Exception:
        pass
    return "rules"


def _make_cleaner(backend: str):
    """Возвращает функцию очистки одного текста."""
    if backend == "none":
        return lambda text: text
    if backend == "rules":
        return clean_rules

    try:
        llm = _load_llama(DEFAULT_MODEL)
    except Exception:
        return clean_rules

    def clean(text: str) -> str:
        edited = clean_llama(text, llm)
        if not edited:
            return clean_rules(text)
        # защита от пересказа: слишком большая правка считается порчей смысла
        if edit_ratio(text, edited) > MAX_EDIT_RATIO:
            return clean_rules(text)
        return edited

    return clean


def clean_segments(segments: list[dict], backend: str | None = None) -> list[dict]:
    """Очищает текст сегментов. Таймкоды и speaker_id не меняются.

    segments: [{"start_ms", "end_ms", "text", "speaker_id"?}]
    """
    chosen = backend or available_backend()
    clean = _make_cleaner(chosen)
    result = []
    for seg in segments:
        text = seg.get("text", "")
        cleaned = clean(text)
        result.append({**seg, "text": cleaned})
    return result


def clean_text(text: str, backend: str | None = None) -> str:
    """То же для одной строки."""
    return _make_cleaner(backend or available_backend())(text)


if __name__ == "__main__":
    import io
    import json
    import sys

    for name in ("stdout", "stderr"):
        stream = getattr(sys, name, None)
        if getattr(stream, "buffer", None) is not None:
            setattr(sys, name, io.TextIOWrapper(stream.buffer, encoding="utf-8", errors="replace"))

    backend = available_backend()
    print(f"Бэкенд постобработки: {backend}", file=sys.stderr)

    if len(sys.argv) > 1:
        path = sys.argv[1]
        if path.endswith(".json"):
            data = json.load(open(path, encoding="utf-8"))
            segs = data.get("transcript", data if isinstance(data, list) else [])
            print(json.dumps(clean_segments(segs, backend), ensure_ascii=False, indent=2))
        else:
            print(clean_text(open(path, encoding="utf-8").read(), backend))
    else:
        demo = [
            {"start_ms": 0, "end_ms": 3000, "text": "привет  всем это   тест", "speaker_id": "speaker_1"},
            {"start_ms": 3000, "end_ms": 6000, "text": "тобиш мы в в пятницу", "speaker_id": "speaker_2"},
        ]
        print(json.dumps(clean_segments(demo, backend), ensure_ascii=False, indent=2))