"""Тесты фильтра галлюцинаций Whisper и валидации границ в HEX-JSON.

Пороги взяты из замеров на контрольном наборе: у настоящей речи
no_speech_prob не превышал 0.063, у галлюцинации был 0.938.
Здесь проверяется, что фильтр ловит мусор и НЕ трогает живую речь.
"""
from __future__ import annotations

from dataclasses import dataclass

import pytest

import asr
import output


@dataclass
class FakeSegment:
    """Минимальная заглушка вместо faster_whisper.Segment."""

    start: float
    end: float
    text: str
    no_speech_prob: float = 0.01
    avg_logprob: float = -0.3
    compression_ratio: float = 1.2


# ---------- признаки галлюцинации ----------

def test_segment_beyond_audio_is_dropped():
    seg = FakeSegment(123.0, 127.0, " Thank you for watching.")
    dropped, reason = asr.is_hallucination(seg, duration=123.764)
    assert dropped
    assert "конец аудио" in reason


def test_high_no_speech_prob_is_dropped():
    seg = FakeSegment(10.0, 12.0, " Thank you for watching.", no_speech_prob=0.94)
    dropped, reason = asr.is_hallucination(seg, duration=200.0)
    assert dropped
    assert "no_speech_prob" in reason


def test_boilerplate_phrase_is_dropped():
    seg = FakeSegment(10.0, 12.0, " Продолжение следует", no_speech_prob=0.01)
    dropped, reason = asr.is_hallucination(seg, duration=200.0)
    assert dropped
    assert "заглушка" in reason


def test_normal_speech_is_kept():
    seg = FakeSegment(10.0, 12.0, " Обсудили релиз в пятницу.", no_speech_prob=0.03)
    dropped, _ = asr.is_hallucination(seg, duration=200.0)
    assert not dropped


def test_high_compression_is_no_longer_a_signal():
    """compression_ratio не должен рубить живую речь: замеры показали,
    что у галлюцинации он был 0.74, а у нормальных сегментов до 2.65."""
    seg = FakeSegment(10.0, 12.0, " Нормальная длинная фраза.", compression_ratio=2.60)
    dropped, _ = asr.is_hallucination(seg, duration=200.0)
    assert not dropped


def test_low_logprob_is_no_longer_a_signal():
    seg = FakeSegment(10.0, 12.0, " Нормальная фраза.", avg_logprob=-0.87)
    dropped, _ = asr.is_hallucination(seg, duration=200.0)
    assert not dropped


def test_end_tolerance_allows_rounding():
    seg = FakeSegment(120.0, 123.9, " Нормальная фраза.")
    dropped, _ = asr.is_hallucination(seg, duration=123.764)
    assert not dropped


def test_no_duration_still_filters_by_no_speech():
    seg = FakeSegment(10.0, 12.0, " Thank you for watching.", no_speech_prob=0.95)
    dropped, _ = asr.is_hallucination(seg, duration=None)
    assert dropped


# ---------- валидация границ в HEX-JSON ----------

def test_validate_flags_segment_beyond_audio():
    data = {"transcript": [
        {"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 5000, "text": "норма"},
        {"speaker_id": "speaker_1", "start_ms": 123000, "end_ms": 127000,
         "text": "Thank you for watching."},
    ]}
    errors = output.validate_hex(data, duration_ms=123764)
    assert any("кончается на" in e for e in errors)


def test_validate_without_duration_keeps_old_behaviour():
    """Без duration_ms проверка границ не выполняется — обратная совместимость."""
    data = {"transcript": [
        {"speaker_id": "speaker_1", "start_ms": 123000, "end_ms": 127000, "text": "x"},
    ]}
    assert output.validate_hex(data) == []


def test_validate_accepts_normal_segments():
    data = {"transcript": [
        {"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 5000, "text": "привет"},
    ]}
    assert output.validate_hex(data, duration_ms=6000) == []


def test_validate_rejects_negative_start():
    data = {"transcript": [
        {"speaker_id": "speaker_1", "start_ms": -100, "end_ms": 5000, "text": "x"},
    ]}
    errors = output.validate_hex(data, duration_ms=60000)
    assert any("отрицательный" in e for e in errors)


def test_build_result_drops_out_of_bounds():
    segments = [
        {"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 5000, "text": "норма"},
        {"speaker_id": "speaker_1", "start_ms": 123000, "end_ms": 127000,
         "text": "Thank you for watching."},
    ]
    result = output.build_result(segments, duration_ms=123764)
    assert len(result["transcript"]) == 1
    assert result["transcript"][0]["text"] == "норма"


def test_build_result_keeps_everything_when_duration_unknown():
    segments = [
        {"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 5000, "text": "a"},
        {"speaker_id": "speaker_1", "start_ms": 6000, "end_ms": 7000, "text": "b"},
    ]
    result = output.build_result(segments)
    assert len(result["transcript"]) == 2


def test_build_result_drop_can_be_disabled():
    segments = [
        {"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 5000, "text": "норма"},
        {"speaker_id": "speaker_1", "start_ms": 123000, "end_ms": 127000, "text": "мусор"},
    ]
    result = output.build_result(segments, duration_ms=123764, drop_out_of_bounds=False)
    assert len(result["transcript"]) == 2