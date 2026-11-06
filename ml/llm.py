"""Протокол совещания из HEX-транскрипта через локальную LLM (llama.cpp).

Публичный API:
- make_minutes(data, ...)      -> протокол (summary, темы, решения, задачи...)
- ask(data, question, ...)     -> ответ на вопрос по записи + таймкод
- clean_transcript(data, ...)  -> пунктуация/регистр (re-export из tools.llm_clean)

Длинные записи: chunk (по токенам) -> протокол фрагмента -> merge, таймкоды
сохраняются (они глобальные, в мс от начала записи).
Анти-выдумка: валидация схемы + жёсткий промпт; retry при битом JSON.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))  # для tools.llm_clean

from tools.llm_clean import (  # noqa: E402
    DEFAULT_LLAMA,
    DEFAULT_MODEL,
    clean_transcript,
    run_llm,
)
from ml.prompts import (  # noqa: E402
    MINUTES_SCHEMA,
    QA_SCHEMA,
    build_merge_prompt,
    build_minutes_prompt,
    build_qa_prompt,
)

# Примерный бюджет чанка по символам транскрипта (≈ контекст минус промпт/ответ).
# Qwen2.5-1.5B: 32k контекст, но на CPU длинные промпты медленные — 12k символов.
CHUNK_CHARS = 12000
MAX_RETRIES = 2


# ---------- Валидация ----------
class ValidationError(ValueError):
    pass


def _check_int_or_null(v, field: str) -> None:
    if v is not None and not isinstance(v, int):
        raise ValidationError(f"{field}: ожидался int или null, получено {v!r}")


def _check_str_or_null(v, field: str) -> None:
    if v is not None and not isinstance(v, str):
        raise ValidationError(f"{field}: ожидалась строка или null, получено {v!r}")


def validate_minutes(obj: dict) -> dict:
    """Проверяет и нормализует протокол по MINUTES_SCHEMA. Бросает ValidationError."""
    if not isinstance(obj, dict):
        raise ValidationError("ответ не JSON-объект")
    for key in MINUTES_SCHEMA:
        if key not in obj:
            raise ValidationError(f"нет ключа {key!r}")
    _check_str_or_null(obj["title"], "title")
    _check_str_or_null(obj["summary"], "summary")
    for coll, item_keys in (
        ("topics", ("name", "start_ms", "key_points")),
        ("decisions", ("decision", "start_ms")),
        ("action_items", ("task", "owner", "deadline", "start_ms")),
        ("open_questions", ("question", "start_ms")),
    ):
        if not isinstance(obj[coll], list):
            raise ValidationError(f"{coll}: ожидался список")
        for i, item in enumerate(obj[coll]):
            if not isinstance(item, dict):
                raise ValidationError(f"{coll}[{i}]: не объект")
            for k in item_keys:
                if k not in item:
                    raise ValidationError(f"{coll}[{i}]: нет ключа {k!r}")
            _check_int_or_null(item["start_ms"], f"{coll}[{i}].start_ms")
            if coll == "action_items":
                _check_str_or_null(item["owner"], f"action_items[{i}].owner")
                _check_str_or_null(item["deadline"], f"action_items[{i}].deadline")
    return obj


def validate_qa(obj: dict) -> dict:
    if not isinstance(obj, dict):
        raise ValidationError("ответ не JSON-объект")
    if "answer" not in obj or "start_ms" not in obj:
        raise ValidationError("нужны ключи answer и start_ms")
    _check_str_or_null(obj["answer"], "answer")
    _check_int_or_null(obj["start_ms"], "start_ms")
    return obj


# ---------- Парсинг JSON из ответа LLM ----------
def extract_json(text: str) -> dict:
    """Вытаскивает первый JSON-объект из ответа (модель может добавить мусор)."""
    text = text.strip()
    text = re.sub(r"^```(?:json)?|```$", "", text, flags=re.MULTILINE).strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    start = text.find("{")
    if start == -1:
        raise ValidationError("в ответе нет JSON")
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return json.loads(text[start : i + 1])
    raise ValidationError("незакрытый JSON")


# ---------- LLM-вызов с retry ----------
def _ask_json(prompt: str, validate, llama: Path, model: Path,
              max_tokens: int = 1500) -> dict:
    last_err: Exception | None = None
    for attempt in range(1 + MAX_RETRIES):
        p = prompt
        if attempt > 0:
            p = (
                prompt
                + "\n\nВНИМАНИЕ: предыдущий ответ был невалидным ("
                + str(last_err)
                + "). Ответь СТРОГО одним валидным JSON-объектом по схеме."
            )
        raw = run_llm(p, llama, model, max_tokens=max_tokens)
        try:
            return validate(extract_json(raw))
        except (ValidationError, json.JSONDecodeError) as e:
            last_err = e
    raise RuntimeError(f"LLM не выдала валидный JSON за {1 + MAX_RETRIES} попыток: {last_err}")


# ---------- Чанкование ----------
def split_chunks(segments: list[dict], max_chars: int = CHUNK_CHARS) -> list[list[dict]]:
    """Режет сегменты на чанки по суммарной длине текста. Таймкоды не трогаем."""
    chunks: list[list[dict]] = []
    cur: list[dict] = []
    cur_len = 0
    for seg in segments:
        n = len(seg["text"]) + 32  # + служебная разметка строки
        if cur and cur_len + n > max_chars:
            chunks.append(cur)
            cur, cur_len = [], 0
        cur.append(seg)
        cur_len += n
    if cur:
        chunks.append(cur)
    return chunks


# ---------- Публичный API ----------
def make_minutes(data: dict, llama: Path = DEFAULT_LLAMA,
                 model: Path = DEFAULT_MODEL) -> dict:
    """Протокол совещания из HEX-транскрипта. Длинный — chunk/merge."""
    segments = data["transcript"]
    chunks = split_chunks(segments)
    if len(chunks) == 1:
        return _ask_json(build_minutes_prompt(segments), validate_minutes, llama, model)
    partials = [
        _ask_json(build_minutes_prompt(ch, chunk=True), validate_minutes, llama, model)
        for ch in chunks
    ]
    return _ask_json(build_merge_prompt(partials), validate_minutes, llama, model,
                     max_tokens=2000)


def ask(data: dict, question: str, llama: Path = DEFAULT_LLAMA,
        model: Path = DEFAULT_MODEL) -> dict:
    """Вопрос по записи -> {"answer": str, "start_ms": int|null}."""
    return _ask_json(build_qa_prompt(data["transcript"], question), validate_qa,
                     llama, model, max_tokens=600)


__all__ = [
    "make_minutes", "ask", "clean_transcript", "validate_minutes", "validate_qa",
    "split_chunks", "extract_json",
]


def main() -> None:
    import argparse

    ap = argparse.ArgumentParser(description="Протокол совещания из HEX-транскрипта")
    ap.add_argument("input", help="входной JSON (HEX-формат)")
    ap.add_argument("-o", "--output", help="куда сохранить протокол (stdout по умолчанию)")
    ap.add_argument("--ask", help="вместо протокола — ответить на вопрос по записи")
    ap.add_argument("--llama", default=str(DEFAULT_LLAMA))
    ap.add_argument("--model", default=str(DEFAULT_MODEL))
    args = ap.parse_args()

    data = json.loads(Path(args.input).read_text(encoding="utf-8"))
    llama, model = Path(args.llama), Path(args.model)
    result = ask(data, args.ask, llama, model) if args.ask else make_minutes(data, llama, model)
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(text, encoding="utf-8")
        print(f"[llm] сохранено: {args.output}")
    else:
        print(text)


if __name__ == "__main__":
    main()
