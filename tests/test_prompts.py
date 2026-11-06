"""Тесты промптов и конвертации в HEX-формат.

Проверяем то, что важно для интеграции: билдеры дают правильную структуру
сообщений, а to_hex_format честно переносит поля по ТЗ, не выдумывая данные.
"""
from __future__ import annotations

import prompts


# ---------- структура сообщений ----------

def test_build_messages_structure():
    messages = prompts.build_messages("система", "текст")
    assert len(messages) == 2
    assert messages[0] == {"role": "system", "content": "система"}
    assert messages[1] == {"role": "user", "content": "текст"}


def test_build_local_messages_structure():
    messages = prompts.build_local_messages("система", "текст")
    assert [m["role"] for m in messages] == ["system", "user"]


def test_build_minutes_messages_without_context():
    messages = prompts.build_minutes_messages("транскрипт")
    assert "транскрипт" in messages[1]["content"]
    assert "Контекст:" not in messages[1]["content"]


def test_build_minutes_messages_with_context():
    messages = prompts.build_minutes_messages("транскрипт", "тема встречи")
    assert "Контекст: тема встречи" in messages[1]["content"]


def test_build_qa_messages_joins_fragments():
    messages = prompts.build_qa_messages("Что решили?", ["часть1", "часть2"])
    content = messages[1]["content"]
    assert "часть1" in content and "часть2" in content
    assert "Что решили?" in content


def test_build_hex_format_messages():
    messages = prompts.build_hex_format_messages("текст")
    assert messages[1]["content"] == "текст"


# ---------- to_hex_format ----------

def test_to_hex_format_maps_fields():
    segments = [{"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 1000, "text": "привет"}]
    result = prompts.to_hex_format(segments)
    assert result == {"transcript": [
        {"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 1000, "text": "привет"}
    ]}


def test_to_hex_format_drops_extra_fields():
    segments = [{
        "speaker_id": "speaker_1", "start_ms": 0, "end_ms": 1000,
        "text": "x", "confidence": 0.9, "words": [],
    }]
    out = prompts.to_hex_format(segments)["transcript"][0]
    assert set(out) == {"speaker_id", "start_ms", "end_ms", "text"}


def test_to_hex_format_defaults_missing_fields():
    out = prompts.to_hex_format([{}])["transcript"][0]
    assert out["speaker_id"] == "speaker_1"
    assert out["start_ms"] == 0
    assert out["end_ms"] == 0
    assert out["text"] == ""


def test_to_hex_format_empty_list():
    assert prompts.to_hex_format([]) == {"transcript": []}


# ---------- промпты не пустые и содержат ключевые требования ----------

def test_prompts_are_non_empty():
    for name in ("BASE_RULES", "CLEAN_PROMPT", "MINUTES_PROMPT", "CHUNK_PROMPT",
                 "MERGE_PROMPT", "QA_PROMPT", "SPEAKER_NAMES_PROMPT"):
        value = getattr(prompts, name)
        assert isinstance(value, str) and len(value) > 20, name


def test_base_rules_forbid_inventing():
    text = prompts.BASE_RULES.lower()
    assert "не выдумывай" in text
    assert "null" in text


def test_minutes_prompt_requires_json_schema():
    assert "json" in prompts.MINUTES_PROMPT.lower()
    assert "action_items" in prompts.MINUTES_PROMPT


def test_qa_prompt_has_refusal_phrase():
    assert "В записи этого нет" in prompts.QA_PROMPT