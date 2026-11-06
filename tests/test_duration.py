"""Тесты длительности аудио: от неё зависит защита от галлюцинаций.

Регрессия, которую эти тесты закрывают: metrics.audio_duration_sec читает
только WAV (модуль wave). Для mp3 он падает с «file does not start with RIFF
id», run_local.py ловил исключение, duration_ms становился None — и проверка
границ молча отключалась. То есть на самом частом формате (mp3) защита от
сегментов за концом записи не работала.
"""
from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest

import asr
import metrics
import output


def _ffmpeg() -> str:
    found = shutil.which("ffmpeg")
    if found:
        return found
    imageio_ffmpeg = pytest.importorskip("imageio_ffmpeg")
    return imageio_ffmpeg.get_ffmpeg_exe()


@pytest.fixture()
def wav_file(tmp_path: Path) -> Path:
    """WAV 1 секунда, 16 кГц, моно — через wave, без внешних зависимостей."""
    import wave

    import numpy as np

    path = tmp_path / "probe.wav"
    samples = (np.sin(2 * np.pi * 220 * np.arange(16_000) / 16_000) * 0.3 * 32767).astype("<i2")
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(16_000)
        f.writeframes(samples.tobytes())
    return path


@pytest.fixture()
def mp3_file(wav_file: Path, tmp_path: Path) -> Path:
    """mp3 из WAV через ffmpeg. Без ffmpeg тест пропускается."""
    path = tmp_path / "probe.mp3"
    subprocess.run(
        [_ffmpeg(), "-y", "-v", "error", "-i", str(wav_file), str(path)],
        check=True,
        capture_output=True,
    )
    return path


def test_metrics_duration_reads_wav(wav_file: Path):
    assert metrics.audio_duration_sec(str(wav_file)) == pytest.approx(1.0, abs=0.05)


def test_metrics_duration_fails_on_mp3(mp3_file: Path):
    """Фиксируем известное ограничение метрик: mp3 они не читают."""
    with pytest.raises(Exception):
        metrics.audio_duration_sec(str(mp3_file))


def test_asr_duration_reads_mp3(mp3_file: Path):
    """Главный путь: asr.audio_duration_sec работает через ffmpeg/PyAV."""
    assert asr.audio_duration_sec(str(mp3_file)) == pytest.approx(1.0, abs=0.15)


def test_asr_duration_reads_wav(wav_file: Path):
    assert asr.audio_duration_sec(str(wav_file)) == pytest.approx(1.0, abs=0.05)


def test_asr_duration_returns_none_for_missing_file(tmp_path: Path):
    assert asr.audio_duration_sec(str(tmp_path / "нет.wav")) is None


def test_mp3_duration_enables_boundary_check(mp3_file: Path):
    """Для mp3 длительность определена, значит проверка границ включается."""
    duration = asr.audio_duration_sec(str(mp3_file))
    assert duration is not None
    duration_ms = int(duration * 1000)

    bad = {"transcript": [
        {"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 500, "text": "ок"},
        {"speaker_id": "speaker_1", "start_ms": duration_ms, "end_ms": duration_ms + 4000,
         "text": "Thank you for watching."},
    ]}
    assert output.validate_hex(bad, duration_ms=duration_ms)