"""Тесты интерфейса диаризации из ТЗ (diarization.py).

Модуль был на 0% покрытия, хотя это главный интерфейс сдачи: по ТЗ он получает
сегменты ASR и возвращает их же с полем speaker_id. Тесты не требуют моделей:
подменяем бэкенд через monkeypatch, проверяя логику сопоставления.
"""
from __future__ import annotations

import pytest

import diarization


@pytest.fixture()
def fake_turns(monkeypatch):
    """Подменяет локальную диаризацию предсказуемыми репликами (в мс)."""
    def fake(audio_path, num_speakers, embedding):
        return [
            (0, 1000, "A"),
            (1000, 2000, "B"),
            (2000, 3000, "A"),
        ]

    monkeypatch.setattr(diarization, "_diarize_local", fake)
    monkeypatch.setattr(diarization, "choose_backend", lambda backend=None: "mfcc")
    return fake


# ---------- выбор бэкенда ----------

def test_choose_backend_accepts_available():
    assert diarization.choose_backend("mfcc") == "mfcc"


def test_choose_backend_rejects_unavailable():
    with pytest.raises(RuntimeError) as err:
        diarization.choose_backend("pyannote")
    assert "недоступен" in str(err.value)


def test_choose_backend_error_explains_requirements():
    with pytest.raises(RuntimeError) as err:
        diarization.choose_backend("pyannote")
    message = str(err.value)
    assert "HF_TOKEN" in message or "pyannote.audio" in message


def test_choose_backend_never_returns_unavailable(monkeypatch):
    """Автовыбор обязан вернуть рабочий бэкенд, а не упасть."""
    monkeypatch.setattr(diarization, "_available", lambda name: name == "mfcc")
    assert diarization.choose_backend() == "mfcc"


def test_choose_backend_env_var(monkeypatch):
    monkeypatch.setenv("DIARIZATION_BACKEND", "mfcc")
    assert diarization.choose_backend() == "mfcc"


# ---------- сопоставление сегментов ----------

def test_diarize_keeps_input_fields(fake_turns):
    segments = [{"start_ms": 100, "end_ms": 900, "text": "привет"}]
    out = diarization.diarize("audio.mp3", segments)
    assert out[0]["text"] == "привет"
    assert out[0]["start_ms"] == 100
    assert out[0]["end_ms"] == 900


def test_diarize_adds_speaker_id_in_tz_format(fake_turns):
    segments = [{"start_ms": 100, "end_ms": 900, "text": "x"}]
    out = diarization.diarize("audio.mp3", segments)
    assert out[0]["speaker_id"].startswith("speaker_")


def test_diarize_does_not_mutate_input(fake_turns):
    segments = [{"start_ms": 100, "end_ms": 900, "text": "x"}]
    diarization.diarize("audio.mp3", segments)
    assert "speaker_id" not in segments[0]


def test_diarize_assigns_max_overlap(fake_turns):
    """Сегмент 0-900 попадает в реплику A (0-1000)."""
    segments = [{"start_ms": 0, "end_ms": 900, "text": "a"}]
    out = diarization.diarize("audio.mp3", segments)
    assert out[0]["speaker_id"] == "speaker_1"


def test_diarize_numbers_speakers_by_first_appearance(fake_turns):
    segments = [
        {"start_ms": 0, "end_ms": 500, "text": "первый"},
        {"start_ms": 1200, "end_ms": 1800, "text": "второй"},
        {"start_ms": 2200, "end_ms": 2800, "text": "снова первый"},
    ]
    out = diarization.diarize("audio.mp3", segments)
    assert out[0]["speaker_id"] == "speaker_1"
    assert out[1]["speaker_id"] == "speaker_2"
    assert out[2]["speaker_id"] == "speaker_1"


def test_diarize_handles_empty_segments(fake_turns):
    assert diarization.diarize("audio.mp3", []) == []


# ---------- _best_speaker напрямую ----------

def test_best_speaker_picks_overlap():
    turns = [(0, 1000, "A"), (1000, 2000, "B")]
    assert diarization._best_speaker(800, 1500, turns) == "B"


def test_best_speaker_falls_back_to_nearest():
    """Сегмент в паузе: берётся ближайшая реплика по середине."""
    turns = [(0, 1000, "A"), (5000, 6000, "B")]
    assert diarization._best_speaker(2000, 2200, turns) == "A"


def test_best_speaker_without_turns_is_unknown():
    assert diarization._best_speaker(0, 1000, []) == "unknown"


def test_diarize_without_turns_marks_unknown(monkeypatch):
    monkeypatch.setattr(diarization, "_diarize_local", lambda *a: [])
    monkeypatch.setattr(diarization, "choose_backend", lambda backend=None: "mfcc")
    out = diarization.diarize("audio.mp3", [{"start_ms": 0, "end_ms": 100, "text": "x"}])
    assert out[0]["speaker_id"] == "speaker_1"  # unknown -> первый в порядке появления


# ---------- доступность бэкендов ----------

def test_available_backends_include_mfcc():
    assert diarization._available("mfcc") is True


def test_pyannote_unavailable_without_token(monkeypatch):
    monkeypatch.delenv("HF_TOKEN", raising=False)
    assert diarization._available("pyannote") is False