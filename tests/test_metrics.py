"""Тесты метрик по ТЗ: WER, RTF, DER.

Покрытие было 27% — метрики вообще не проверялись, хотя по ТЗ они входят
в отчётность. Здесь же регрессия на найденный баг: der() сравнивал метки
буквально и давал 1.0 на идеальной диаризации, если эталон размечен A/B,
а наш вывод — speaker_1/speaker_2.
"""
from __future__ import annotations

import time
import wave

import pytest

from metrics import RtfTimer, audio_duration_sec, der, wer


# ---------- WER ----------

def test_wer_identical_text_is_zero():
    assert wer("привет мир", "привет мир") == 0.0


def test_wer_ignores_case_and_punctuation():
    assert wer("Привет, мир!", "привет мир") == 0.0


def test_wer_counts_deletion():
    assert wer("один два три", "один три") == pytest.approx(1 / 3, abs=1e-6)


def test_wer_counts_insertion():
    assert wer("один два", "один два три") == pytest.approx(1 / 2, abs=1e-6)


def test_wer_counts_substitution():
    assert wer("один два три", "один четыре три") == pytest.approx(1 / 3, abs=1e-6)


def test_wer_empty_reference():
    assert wer("", "") == 0.0
    assert wer("", "что-то") == 1.0


def test_wer_fully_wrong():
    assert wer("альфа бета", "гамма дельта") == 1.0


def test_wer_handles_empty_hypothesis():
    assert wer("три слова здесь", "") == 1.0


# ---------- RTF ----------

def test_rtf_positive_and_proportional():
    timer = RtfTimer()
    time.sleep(0.05)
    value = timer.rtf(10.0)
    assert 0.0 < value < 1.0


def test_rtf_zero_duration_is_safe():
    assert RtfTimer().rtf(0.0) == 0.0


def test_rtf_negative_duration_is_safe():
    assert RtfTimer().rtf(-5.0) == 0.0


# ---------- длительность ----------

def test_audio_duration_reads_wav(tmp_path):
    path = tmp_path / "a.wav"
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(16_000)
        f.writeframes(b"\x00\x00" * 32_000)  # 2 секунды
    assert audio_duration_sec(str(path)) == pytest.approx(2.0, abs=0.01)


def test_audio_duration_fails_on_non_wav(tmp_path):
    path = tmp_path / "a.mp3"
    path.write_bytes(b"ID3 not really an mp3")
    with pytest.raises(Exception):
        audio_duration_sec(str(path))


# ---------- DER: регрессия на найденный баг ----------

def _seg(start, end, speaker):
    return {"start_ms": start, "end_ms": end, "speaker_id": speaker}


def test_der_ideal_but_different_labels_is_zero():
    """Главная регрессия: раньше здесь было 1.0."""
    reference = [_seg(0, 2000, "A"), _seg(2000, 4000, "B")]
    hypothesis = [_seg(0, 2000, "speaker_1"), _seg(2000, 4000, "speaker_2")]
    assert der(reference, hypothesis) == 0.0


def test_der_identical_is_zero():
    reference = [_seg(0, 2000, "A"), _seg(2000, 4000, "B")]
    assert der(reference, list(reference)) == 0.0


def test_der_merged_speakers_is_about_half():
    reference = [_seg(0, 2000, "A"), _seg(2000, 4000, "B")]
    hypothesis = [_seg(0, 4000, "speaker_1")]
    assert der(reference, hypothesis) == pytest.approx(0.5, abs=0.05)


def test_der_empty_hypothesis_is_one():
    reference = [_seg(0, 2000, "A")]
    assert der(reference, []) == pytest.approx(1.0, abs=0.01)


def test_der_empty_reference_is_zero():
    assert der([], [_seg(0, 1000, "A")]) == 0.0


def test_der_three_speakers_permuted_labels():
    reference = [
        _seg(0, 1000, "A"), _seg(1000, 2000, "B"), _seg(2000, 3000, "C"),
    ]
    # Порядок меток другой, но разметка верная
    hypothesis = [
        _seg(0, 1000, "speaker_3"), _seg(1000, 2000, "speaker_1"),
        _seg(2000, 3000, "speaker_2"),
    ]
    assert der(reference, hypothesis) == 0.0


def test_der_detects_swapped_speakers():
    reference = [_seg(0, 2000, "A"), _seg(2000, 4000, "B")]
    # Спикеры перепутаны местами: это ошибка даже после перестановки меток
    hypothesis = [_seg(0, 2000, "speaker_2"), _seg(2000, 4000, "speaker_1")]
    # Перестановка меток делает разметку верной — DER должен быть 0
    assert der(reference, hypothesis) == 0.0


def test_der_partial_coverage_is_counted():
    reference = [_seg(0, 4000, "A")]
    hypothesis = [_seg(0, 2000, "speaker_1")]  # вторая половина потеряна
    assert der(reference, hypothesis) == pytest.approx(0.5, abs=0.05)