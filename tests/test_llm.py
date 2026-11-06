"""Тесты постобработки текста (llm.py).

Покрытие было 52%: не проверялись выбор режима, защита от пересказа и
поведение при недоступной модели. Это важные ветки — именно они не дают
LLM испортить расшифровку, что ТЗ прямо запрещает.
"""
from __future__ import annotations

import pytest

import llm


# ---------- выбор режима ----------

def test_backend_none_keeps_text_untouched():
    assert llm.clean_text("  сырой   текст ", backend="none") == "  сырой   текст "


def test_backend_rules_available_without_any_package(monkeypatch):
    monkeypatch.delenv("LLM_BACKEND", raising=False)
    assert llm.available_backend() in {"rules", "llama"}


def test_backend_env_override(monkeypatch):
    monkeypatch.setenv("LLM_BACKEND", "rules")
    assert llm.available_backend() == "rules"


def test_backend_env_none(monkeypatch):
    monkeypatch.setenv("LLM_BACKEND", "none")
    assert llm.available_backend() == "none"


def test_backend_falls_back_to_rules_without_model(monkeypatch):
    """Без пакета llama_cpp или файла модели обязан быть rules."""
    monkeypatch.delenv("LLM_BACKEND", raising=False)
    monkeypatch.setattr(llm, "DEFAULT_MODEL", "")
    assert llm.available_backend() == "rules"


def test_make_cleaner_falls_back_when_model_load_fails(monkeypatch):
    def boom(path):
        raise RuntimeError("модель не загрузилась")

    monkeypatch.setattr(llm, "_load_llama", boom)
    cleaner = llm._make_cleaner("llama")
    assert cleaner("привет  мир") == "Привет мир."


# ---------- защита от пересказа ----------

def test_edit_ratio_identical_is_zero():
    assert llm.edit_ratio("привет мир", "привет мир") == 0.0


def test_edit_ratio_counts_changed_words():
    # одно слово заменено из двух
    assert llm.edit_ratio("привет мир", "привет земля") == pytest.approx(0.5, abs=1e-6)


def test_edit_ratio_ignores_punctuation_and_case():
    assert llm.edit_ratio("привет мир", "Привет, мир!") == 0.0


def test_edit_ratio_empty_reference():
    assert llm.edit_ratio("", "") == 0.0
    assert llm.edit_ratio("", "что-то") == 1.0


def test_paraphrase_is_rejected_and_rules_used(monkeypatch):
    """Если модель переписала смысл, правка откатывается к правилам."""
    class FakeLlama:
        def create_chat_completion(self, **kwargs):
            return {"choices": [{"message": {"content": "Совершенно другой текст про погоду"}}]}

    monkeypatch.setattr(llm, "_load_llama", lambda path: FakeLlama())
    cleaner = llm._make_cleaner("llama")
    result = cleaner("обсудили релиз мобильного приложения")
    # результат должен быть от rules, а не пересказом модели
    assert "погод" not in result.lower()
    assert "релиз" in result.lower()


def test_good_llm_edit_is_accepted(monkeypatch):
    """Небольшая правка (пунктуация) принимается от модели."""
    class FakeLlama:
        def create_chat_completion(self, **kwargs):
            return {"choices": [{"message": {"content": "Привет, мир."}}]}

    monkeypatch.setattr(llm, "_load_llama", lambda path: FakeLlama())
    cleaner = llm._make_cleaner("llama")
    assert cleaner("привет мир") == "Привет, мир."


def test_empty_llm_response_falls_back_to_rules(monkeypatch):
    class FakeLlama:
        def create_chat_completion(self, **kwargs):
            return {"choices": [{"message": {"content": ""}}]}

    monkeypatch.setattr(llm, "_load_llama", lambda path: FakeLlama())
    cleaner = llm._make_cleaner("llama")
    assert cleaner("привет  мир") == "Привет мир."


def test_broken_llm_response_shape_falls_back(monkeypatch):
    class FakeLlama:
        def create_chat_completion(self, **kwargs):
            return {"unexpected": "shape"}

    monkeypatch.setattr(llm, "_load_llama", lambda path: FakeLlama())
    cleaner = llm._make_cleaner("llama")
    assert cleaner("привет  мир") == "Привет мир."


# ---------- clean_segments ----------

def test_clean_segments_preserves_timing_and_speaker():
    segments = [
        {"start_ms": 0, "end_ms": 1000, "text": "привет", "speaker_id": "speaker_2"},
        {"start_ms": 1000, "end_ms": 2000, "text": "мир", "speaker_id": "speaker_1"},
    ]
    out = llm.clean_segments(segments, backend="rules")
    assert [s["speaker_id"] for s in out] == ["speaker_2", "speaker_1"]
    assert [s["start_ms"] for s in out] == [0, 1000]
    assert out[0]["text"] == "Привет."


def test_clean_segments_does_not_mutate_input():
    segments = [{"start_ms": 0, "end_ms": 1000, "text": "привет"}]
    llm.clean_segments(segments, backend="rules")
    assert segments[0]["text"] == "привет"


def test_clean_segments_handles_missing_text():
    out = llm.clean_segments([{"start_ms": 0, "end_ms": 1}], backend="rules")
    assert out[0]["text"] == ""


# ---------- clean_rules на реальных случаях ----------

@pytest.mark.parametrize("text,expected", [
    ("привет  мир", "Привет мир."),
    ("  пробелы вокруг  ", "Пробелы вокруг."),
    ("уже с точкой.", "Уже с точкой."),
    ("вопрос?", "Вопрос?"),
])
def test_clean_rules_formatting(text, expected):
    assert llm.clean_rules(text) == expected


def test_clean_rules_fixes_common_asr_errors():
    assert "то есть" in llm.clean_rules("тобиш вот так").lower()
    assert "сейчас" in llm.clean_rules("щас сделаю").lower()


def test_clean_rules_removes_space_before_punctuation():
    assert llm.clean_rules("привет , мир .") == "Привет, мир."