"""Шумоподавление: спектральное вычитание с оценкой шума по паузам.

Зачем: на синтетическом контроле Whisper устойчив к ровному белому шуму, но
при экстремальном шуме WER растёт с 0.09 до 0.32 (0 дБ) и 0.52 (-5 дБ), а на
реальных записях с кондиционером, улицей и гулом сети деградация начинается
раньше. Диаризация при этом устойчива: ECAPA-эмбеддинги остаются разделимыми.

Метод: классическое спектральное вычитание (Boll, 1979), без нейросетей и
лишних зависимостей — только numpy.

1. Шум оценивается по самым тихим кадрам: там, где нет речи, остаётся только
   фон. Порог — перцентиль энергии.
2. Из спектра мощности вычитается доля шумового спектра (over-subtraction).
3. Остаток ограничивается снизу «полом» (spectral floor), чтобы не появился
   музыкальный шум — характерный артефакт чистого вычитания.

Ограничение честно: это не нейросетевой денойзер. На записях с речью,
наложенной на громкий нестационарный шум (другая речь, музыка), выигрыш будет
скромным, а при слишком агрессивных настройках — вред. Поэтому есть
автоматическая проверка: если после обработки оценка SNR не улучшилась,
возвращается исходный сигнал.
"""
from __future__ import annotations

import numpy as np

SAMPLE_RATE = 16_000

# Параметры спектрального вычитания
N_FFT = 512
HOP = 160           # 10 мс
WIN = 400           # 25 мс
OVER_SUBTRACTION = 1.5   # насколько агрессивно вычитаем шум
SPECTRAL_FLOOR = 0.05    # минимум от исходной амплитуды (против «музыкального» шума)
NOISE_PERCENTILE = 20    # кадры ниже этого перцентиля энергии считаем шумом

# Пороги автоотключения (замерено на контрольном наборе)
# CLEAN_SNR_DB: выше — запись чистая, трогать незачем.
# MIN_SNR_DB: ниже — шум сильнее речи, вычитание её режет (при -5 дБ WER
#   рос с 0.52 до 0.82). Обработка имеет смысл только в среднем диапазоне.
CLEAN_SNR_DB = 25.0
MIN_SNR_DB = 5.5


def _stft(audio: np.ndarray) -> np.ndarray:
    """STFT с окном Ханна: (кадры, частоты), комплексный."""
    if len(audio) < WIN:
        audio = np.pad(audio, (0, WIN - len(audio)))
    n_frames = 1 + (len(audio) - WIN) // HOP
    idx = np.arange(WIN)[None, :] + HOP * np.arange(n_frames)[:, None]
    frames = audio[idx] * np.hanning(WIN).astype(np.float32)
    return np.fft.rfft(frames, N_FFT, axis=1)


def _istft(spec: np.ndarray, length: int) -> np.ndarray:
    """Обратное STFT с перекрытием и нормировкой на сумму окон."""
    frames = np.fft.irfft(spec, N_FFT, axis=1)[:, :WIN]
    window = np.hanning(WIN).astype(np.float32)
    out = np.zeros(length + WIN, dtype=np.float32)
    norm = np.zeros(length + WIN, dtype=np.float32)
    for i, frame in enumerate(frames):
        start = i * HOP
        out[start:start + WIN] += frame * window
        norm[start:start + WIN] += window ** 2
    out = out / np.maximum(norm, 1e-8)
    return out[:length]


def estimate_noise_spectrum(spec: np.ndarray,
                            percentile: float = NOISE_PERCENTILE) -> np.ndarray:
    """Спектр шума как среднее по самым тихим кадрам.

    Тихие кадры — это паузы между словами; в них остаётся только фон.
    """
    power = np.abs(spec) ** 2
    energy = power.mean(axis=1)
    threshold = np.percentile(energy, percentile)
    quiet = spec[energy <= threshold]
    if len(quiet) == 0:
        return np.zeros(spec.shape[1], dtype=np.complex64)
    return quiet.mean(axis=0)


def snr_estimate(audio: np.ndarray) -> float:
    """Грубая оценка отношения сигнал/шум в дБ.

    Сравниваем энергию громких кадров (речь) с энергией тихих (шумовой пол).
    Это не настоящий SNR, а динамический диапазон: он совпадает с SNR только
    когда в записи есть паузы, что для речи выполняется.

    Честное ограничение: на постоянном тоне без пауз оценка даёт ~0 дБ,
    потому что громких и тихих кадров там одинаково. Для реальной речи
    с паузами и для речи с шумом оценка работает.
    """
    audio = np.asarray(audio, dtype=np.float32)
    if len(audio) < HOP * 2:
        return 0.0

    n_frames = len(audio) // HOP
    frames = audio[: n_frames * HOP].reshape(n_frames, HOP)
    energy = (frames.astype(np.float64) ** 2).mean(axis=1)
    if len(energy) == 0:
        return 0.0

    loud = float(np.percentile(energy, 90))
    quiet = float(np.percentile(energy, 10))
    if quiet <= 1e-10:
        return 60.0  # цифровая тишина в паузах — запись чистая
    return float(10 * np.log10(max(loud, 1e-12) / quiet))


def remove_hum(audio: np.ndarray, sr: int = SAMPLE_RATE,
               base_freq: float = 50.0, harmonics: int = 3,
               quality: float = 30.0) -> np.ndarray:
    """Убирает гул сети: узкие режекторные фильтры на 50 Гц и гармоники.

    Спектральное вычитание с гулом справляется плохо: он узкополосный и
    стационарный, а вычитание работает по широкой полосе. Замеры показали,
    что на hum_10db вычитание даже ухудшало WER. Режекторный фильтр убирает
    именно гармоники, не трогая остальной спектр.

    quality — добротность: выше значит уже вырез. 30 даёт полосу ~1.7 Гц
    на 50 Гц, что перекрывает нестабильность сети и не задевает речь.

    Требует scipy. Без него возвращает сигнал без изменений: гул останется,
    но пайплайн не упадёт.
    """
    try:
        from scipy.signal import filtfilt, iirnotch
    except ImportError:
        return np.asarray(audio, dtype=np.float32)

    audio = np.asarray(audio, dtype=np.float32)
    if len(audio) < 256:
        return audio

    out = audio
    for k in range(1, harmonics + 1):
        freq = base_freq * k
        if freq >= sr / 2 - 50:  # выше Найквиста не фильтруем
            break
        b, a = iirnotch(freq, quality, sr)
        out = filtfilt(b, a, out).astype(np.float32)
    return out


def detect_hum(audio: np.ndarray, sr: int = SAMPLE_RATE,
               base_freq: float = 50.0, n_fft: int = 8192) -> bool:
    """Есть ли в записи заметный гул сети.

    Нужен длинный FFT: при n_fft=512 разрешение 31 Гц, и полосы вокруг 50 Гц
    просто не существует — детектор молча не срабатывал. При 8192 разрешение
    ~2 Гц, гармоника видна отдельным пиком.

    Сравниваем пик у 50 Гц с медианой соседних полос: у гула отношение
    заметно выше, у обычной речи спектр там ровный.
    """
    if len(audio) < n_fft:
        audio = np.pad(audio, (0, n_fft - len(audio)))
    frames = []
    for start in range(0, len(audio) - n_fft + 1, n_fft // 2):
        chunk = audio[start:start + n_fft] * np.hanning(n_fft)
        frames.append(np.abs(np.fft.rfft(chunk)) ** 2)
    if not frames:
        return False
    power = np.mean(frames, axis=0)
    freqs = np.fft.rfftfreq(n_fft, 1 / sr)

    band = (freqs >= base_freq - 3) & (freqs <= base_freq + 3)
    around = ((freqs >= base_freq - 15) & (freqs < base_freq - 5)) | \
             ((freqs > base_freq + 5) & (freqs <= base_freq + 15))
    if not band.any() or not around.any():
        return False
    peak = float(power[band].max())
    floor = float(np.median(power[around]))
    return floor > 0 and peak / floor > 3.0


def denoise(audio: np.ndarray, sr: int = SAMPLE_RATE,
            over_subtraction: float = OVER_SUBTRACTION,
            floor: float = SPECTRAL_FLOOR,
            auto_check: bool = True,
            min_snr_db: float = MIN_SNR_DB) -> np.ndarray:
    """Спектральное вычитание шума. Возвращает очищенный сигнал той же длины.

    auto_check=True (по умолчанию) отказывается от обработки в трёх случаях:
    - запись и так чистая (SNR выше 40 дБ) — трогать незачем;
    - шум настолько сильный, что вычитание режет речь (SNR ниже min_snr_db):
      замеры показали, что при -5 дБ WER растёт с 0.52 до 0.82;
    - обработка не улучшила оценку SNR.
    Лучше вернуть исходник, чем испортить запись: чистую речь фильтр не меняет
    ни на отсчёт (проверено).
    """
    audio = np.asarray(audio, dtype=np.float32)
    if len(audio) < WIN * 2:
        return audio  # слишком коротко, оценивать шум не на чем

    snr_in = snr_estimate(audio)
    if auto_check:
        if snr_in > CLEAN_SNR_DB:
            return audio  # уже чисто
        if snr_in < min_snr_db:
            return audio  # вычитание только навредит

    # Гул сети убираем отдельно: он узкополосный, вычитание его не берёт
    if detect_hum(audio, sr):
        audio = remove_hum(audio, sr)

    spec = _stft(audio)
    noise = estimate_noise_spectrum(spec)

    # Вычитание по мощности, фаза остаётся исходной
    magnitude = np.abs(spec)
    noise_mag = np.abs(noise)[None, :]
    cleaned_mag = magnitude - over_subtraction * noise_mag
    cleaned_mag = np.maximum(cleaned_mag, floor * magnitude + 1e-10)

    phase = np.exp(1j * np.angle(spec))
    cleaned = _istft(cleaned_mag * phase, len(audio)).astype(np.float32)

    if auto_check and snr_estimate(cleaned) <= snr_in:
        return audio  # обработка не помогла — не портим запись

    # Не даём выйти за пределы исходной громкости
    peak_in = float(np.max(np.abs(audio))) or 1.0
    peak_out = float(np.max(np.abs(cleaned))) or 1.0
    if peak_out > peak_in:
        cleaned = cleaned * (peak_in / peak_out)
    return cleaned


def denoise_file(src: str, dst: str, sr: int = SAMPLE_RATE, **kwargs) -> None:
    """Очищает аудиофайл и сохраняет результат в WAV 16 кГц моно."""
    import audio_utils

    audio = audio_utils.decode_audio(src, sr)
    cleaned = denoise(audio, sr, **kwargs)
    audio_utils.write_wav(dst, cleaned, sr)


def denoise_inplace(audio: np.ndarray, sr: int = SAMPLE_RATE, **kwargs) -> np.ndarray:
    """Псевдоним denoise для читаемости в пайплайне."""
    return denoise(audio, sr, **kwargs)


if __name__ == "__main__":
    import io
    import sys

    for _n in ("stdout", "stderr"):
        _s = getattr(sys, _n, None)
        if getattr(_s, "buffer", None) is not None:
            setattr(sys, _n, io.TextIOWrapper(_s.buffer, encoding="utf-8", errors="replace"))

    if len(sys.argv) < 3:
        print("использование: py denoise.py вход.wav выход.wav", file=sys.stderr)
        raise SystemExit(2)

    import audio_utils

    src, dst = sys.argv[1], sys.argv[2]
    before = audio_utils.decode_audio(src)
    after = denoise(before)
    audio_utils.write_wav(dst, after)
    print(f"SNR до:    {snr_estimate(before):.1f} дБ")
    print(f"SNR после: {snr_estimate(after):.1f} дБ")
    print(f"сохранено: {dst}")