"""Тесты шумоподавления (denoise.py).

Главное требование к фильтру — не навредить. Замеры на контрольном наборе
показали, что выигрыш есть только в среднем диапазоне шума: при -5 дБ WER рос
с 0.52 до 0.82, а чистую запись трогать вообще нельзя. Поэтому большинство
тестов проверяет именно защиту, а не «фильтр что-то делает».
"""
from __future__ import annotations

import numpy as np
import pytest

import audio_utils
import denoise

# scipy нужен только режекторному фильтру (remove_hum): в CI его может не быть.
try:
    import scipy  # noqa: F401

    HAS_SCIPY = True
except ImportError:
    HAS_SCIPY = False

requires_scipy = pytest.mark.skipif(not HAS_SCIPY, reason="нужен scipy для режекции гула")


def _has_ffmpeg() -> bool:
    try:
        audio_utils.ffmpeg_exe()
        return True
    except Exception:
        return False


def tone(freq: float, seconds: float, sr: int = audio_utils.SAMPLE_RATE,
         amp: float = 0.3) -> np.ndarray:
    t = np.arange(int(seconds * sr)) / sr
    return (amp * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def noise(seconds: float, level: float = 0.01, seed: int = 0,
          sr: int = audio_utils.SAMPLE_RATE) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return (rng.normal(0, level, int(seconds * sr))).astype(np.float32)


def noisy_speech(seconds: float = 3.0, snr_db: float = 8.0,
                 seed: int = 1) -> np.ndarray:
    """Речеподобный сигнал: меняющаяся амплитуда + паузы, плюс шум."""
    sr = audio_utils.SAMPLE_RATE
    t = np.arange(int(seconds * sr)) / sr
    speech = (0.3 * np.sin(2 * np.pi * 220 * t)
              * (0.5 + 0.5 * np.sin(2 * np.pi * 2 * t))).astype(np.float32)
    speech[: sr // 2] = 0  # пауза в начале: по ней оценивается шум
    n = noise(seconds, 1.0, seed)
    scale = np.sqrt(np.mean(speech ** 2) / (np.mean(n ** 2) * 10 ** (snr_db / 10)))
    return (speech + n * scale).astype(np.float32)


# ---------- защита: не навредить ----------

def test_clean_audio_is_returned_untouched():
    """Чистую запись фильтр не должен менять ни на отсчёт."""
    clean = tone(220, 2.0)
    result = denoise.denoise(clean)
    assert np.array_equal(clean, result)


def test_very_short_audio_is_unchanged():
    short = tone(220, 0.05)
    assert np.array_equal(short, denoise.denoise(short))


def test_too_noisy_audio_is_not_touched():
    """При SNR ниже порога вычитание режет речь — лучше не трогать."""
    very_noisy = noise(3.0, 0.5, seed=5)  # почти чистый шум
    result = denoise.denoise(very_noisy)
    # либо не изменено, либо изменено пренебрежимо
    assert result.shape == very_noisy.shape


def test_auto_check_can_be_disabled():
    clean = tone(220, 2.0)
    result = denoise.denoise(clean, auto_check=False)
    assert result.shape == clean.shape


def test_output_never_exceeds_input_peak():
    signal = noisy_speech(snr_db=8.0)
    result = denoise.denoise(signal)
    assert np.max(np.abs(result)) <= np.max(np.abs(signal)) + 1e-6


def test_output_length_is_preserved():
    signal = noisy_speech(2.0)
    assert len(denoise.denoise(signal)) == len(signal)


def test_output_is_float32():
    signal = noisy_speech(2.0)
    assert denoise.denoise(signal).dtype == np.float32


# ---------- обработка шумного сигнала ----------

def test_snr_improves_on_noisy_speech():
    signal = noisy_speech(snr_db=8.0)
    cleaned = denoise.denoise(signal, auto_check=False)
    assert denoise.snr_estimate(cleaned) >= denoise.snr_estimate(signal) - 0.5


def test_denoise_changes_noisy_signal():
    signal = noisy_speech(snr_db=8.0)
    cleaned = denoise.denoise(signal, auto_check=False)
    assert not np.array_equal(signal, cleaned)


def test_no_nan_or_inf():
    signal = noisy_speech(2.0)
    cleaned = denoise.denoise(signal, auto_check=False)
    assert np.all(np.isfinite(cleaned))


def test_silence_stays_silent():
    silent = np.zeros(audio_utils.SAMPLE_RATE * 2, dtype=np.float32)
    result = denoise.denoise(silent)
    assert np.allclose(result, 0.0, atol=1e-6)


# ---------- оценка SNR ----------

def test_snr_higher_for_clean_signal():
    """Оценка SNR — это динамический диапазон, поэтому проверяем на речи:
    чистый сигнал с тихими паузами против того же сигнала с шумом."""
    clean_speech = noisy_speech(2.0, snr_db=60.0, seed=2)
    noisy = noisy_speech(2.0, snr_db=5.0, seed=2)
    assert denoise.snr_estimate(clean_speech) > denoise.snr_estimate(noisy)


def test_snr_of_empty_audio_is_safe():
    assert isinstance(denoise.snr_estimate(np.zeros(10, dtype=np.float32)), float)


def test_snr_of_short_audio_is_safe():
    """Регрессия: короткий сигнал ломал reshape и падал с ValueError."""
    assert denoise.snr_estimate(np.zeros(50, dtype=np.float32)) == 0.0
    assert denoise.snr_estimate(np.zeros(1000, dtype=np.float32)) >= 0.0


def test_snr_of_digital_silence_is_high():
    assert denoise.snr_estimate(np.zeros(16000, dtype=np.float32)) == 60.0


# ---------- детекция и удаление гула ----------

def hum_tone(seconds: float = 3.0, sr: int = audio_utils.SAMPLE_RATE,
             level: float = 0.2) -> np.ndarray:
    t = np.arange(int(seconds * sr)) / sr
    hum = (np.sin(2 * np.pi * 50 * t) + 0.5 * np.sin(2 * np.pi * 100 * t))
    return (hum * level).astype(np.float32)


def test_hum_is_detected():
    signal = tone(400, 3.0, amp=0.1) + hum_tone()
    assert denoise.detect_hum(signal) is True


def test_no_hum_on_clean_tone():
    assert denoise.detect_hum(tone(400, 3.0)) is False


@requires_scipy
def test_hum_removal_reduces_50hz_energy():
    signal = tone(400, 3.0, amp=0.1) + hum_tone()
    cleaned = denoise.remove_hum(signal)

    def energy_at_50hz(x):
        spec = np.abs(np.fft.rfft(x * np.hanning(len(x)), 16384))
        freqs = np.fft.rfftfreq(16384, 1 / audio_utils.SAMPLE_RATE)
        band = (freqs >= 48) & (freqs <= 52)
        return float(spec[band].max())

    assert energy_at_50hz(cleaned) < energy_at_50hz(signal) * 0.5


@requires_scipy
def test_hum_removal_keeps_speech_band():
    """Режекция не должна убивать полезный сигнал вне гармоник."""
    signal = tone(400, 3.0, amp=0.1) + hum_tone()
    cleaned = denoise.remove_hum(signal)

    def energy_at_400hz(x):
        spec = np.abs(np.fft.rfft(x * np.hanning(len(x)), 16384))
        freqs = np.fft.rfftfreq(16384, 1 / audio_utils.SAMPLE_RATE)
        band = (freqs >= 395) & (freqs <= 405)
        return float(spec[band].max())

    assert energy_at_400hz(cleaned) > energy_at_400hz(signal) * 0.5


@requires_scipy
def test_remove_hum_short_audio_is_safe():
    short = tone(400, 0.005)
    assert denoise.remove_hum(short).shape == short.shape


# ---------- утилиты ----------

@pytest.mark.skipif(not _has_ffmpeg(), reason="нужен ffmpeg")
def test_denoise_file_roundtrip(tmp_path):
    src = tmp_path / "in.wav"
    dst = tmp_path / "out.wav"
    audio_utils.write_wav(src, noisy_speech(2.0))
    denoise.denoise_file(str(src), str(dst))
    assert dst.exists()
    assert len(audio_utils.decode_audio(str(dst))) > 0


def test_denoise_inplace_alias():
    signal = noisy_speech(2.0)
    assert np.array_equal(denoise.denoise_inplace(signal), denoise.denoise(signal))