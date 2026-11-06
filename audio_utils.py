"""Декодирование аудио и мел-спектральные признаки (numpy/scipy).

ASR и диаризация работают с одним представлением: 16 кГц, моно, float32.
Декодер — ffmpeg (системный или из пакета imageio-ffmpeg).
"""
import os
import shutil
import subprocess

import numpy as np

SAMPLE_RATE = 16_000

AUDIO_EXTS = {".mp3", ".mp4", ".m4a", ".wav", ".ogg", ".oga", ".opus",
              ".flac", ".webm", ".aac", ".wma", ".mkv", ".mpga"}


def is_audio(path: str | os.PathLike) -> bool:
    return os.path.splitext(str(path))[1].lower() in AUDIO_EXTS


def ffmpeg_exe() -> str:
    """Системный ffmpeg, либо бинарник из imageio-ffmpeg."""
    env = os.getenv("FFMPEG_BINARY")
    if env and os.path.exists(env):
        return env
    found = shutil.which("ffmpeg")
    if found:
        return found
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception as exc:  # pragma: no cover - зависит от окружения
        raise RuntimeError(
            "ffmpeg не найден. Поставьте ffmpeg в PATH или `pip install imageio-ffmpeg`."
        ) from exc


def decode_audio(path: str | os.PathLike, sr: int = SAMPLE_RATE) -> np.ndarray:
    """Любой аудиоконтейнер -> моно float32 в диапазоне [-1, 1]."""
    cmd = [
        ffmpeg_exe(), "-nostdin", "-v", "error",
        "-i", str(path),
        "-f", "f32le", "-acodec", "pcm_f32le",
        "-ac", "1", "-ar", str(sr), "-",
    ]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        msg = proc.stderr.decode("utf-8", "replace").strip()[:500]
        raise RuntimeError(f"ffmpeg не смог прочитать {path}: {msg}")
    audio = np.frombuffer(proc.stdout, dtype=np.float32).copy()
    if audio.size == 0:
        raise RuntimeError(f"В файле нет аудиодорожки: {path}")
    peak = float(np.max(np.abs(audio)))
    if peak > 1.0:
        audio = audio / peak
    return audio


def write_wav(path: str | os.PathLike, audio: np.ndarray, sr: int = SAMPLE_RATE) -> None:
    """Сохранить в 16-битный WAV без зависимостей — для отладки и тестов."""
    import wave

    pcm = np.clip(audio, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(sr)
        f.writeframes(pcm.tobytes())


def duration_seconds(audio: np.ndarray, sr: int = SAMPLE_RATE) -> float:
    return len(audio) / float(sr)


# ---------- мел-спектр и MFCC ----------

def _mel_filterbank(sr: int, n_fft: int, n_mels: int, fmin: float, fmax: float) -> np.ndarray:
    def hz2mel(f):
        return 2595.0 * np.log10(1.0 + f / 700.0)

    def mel2hz(m):
        return 700.0 * (10.0 ** (m / 2595.0) - 1.0)

    n_bins = n_fft // 2 + 1
    edges = mel2hz(np.linspace(hz2mel(fmin), hz2mel(fmax), n_mels + 2))
    bins = np.clip(np.floor((n_fft + 1) * edges / sr).astype(int), 0, n_bins - 1)
    fb = np.zeros((n_mels, n_bins), dtype=np.float32)
    for i in range(n_mels):
        left, center, right = int(bins[i]), int(bins[i + 1]), int(bins[i + 2])
        if center <= left:
            center = left + 1
        if right <= center:
            right = center + 1
        right = min(right, n_bins)
        for j in range(left, min(center, n_bins)):
            fb[i, j] = (j - left) / float(center - left)
        for j in range(center, right):
            fb[i, j] = (right - j) / float(right - center)
    return fb


def _dct_matrix(n_out: int, n_in: int) -> np.ndarray:
    k = np.arange(n_out)[:, None]
    n = np.arange(n_in)[None, :]
    return np.cos(np.pi * k * (2 * n + 1) / (2.0 * n_in)).astype(np.float32)


def _frames(audio: np.ndarray, win: int, hop: int) -> np.ndarray:
    if len(audio) < win:
        audio = np.pad(audio, (0, win - len(audio)))
    n = 1 + (len(audio) - win) // hop
    idx = np.arange(win)[None, :] + hop * np.arange(n)[:, None]
    return audio[idx]


def log_mel(audio: np.ndarray, sr: int = SAMPLE_RATE, n_mels: int = 40,
            n_fft: int = 512, win: int = 400, hop: int = 160) -> np.ndarray:
    """Лог-мел-спектр: (кадры, n_mels)."""
    frames = _frames(np.asarray(audio, dtype=np.float32), win, hop)
    window = np.hamming(win).astype(np.float32)
    spec = np.abs(np.fft.rfft(frames * window, n_fft)) ** 2
    fb = _mel_filterbank(sr, n_fft, n_mels, 20.0, sr / 2.0)
    return np.log(np.maximum(spec @ fb.T, 1e-10)).astype(np.float32)


def mfcc(audio: np.ndarray, sr: int = SAMPLE_RATE, n_mfcc: int = 20,
         n_mels: int = 40, hop: int = 160) -> np.ndarray:
    """MFCC без c0 (энергия): (кадры, n_mfcc)."""
    mel = log_mel(audio, sr=sr, n_mels=n_mels, hop=hop)
    dct = _dct_matrix(n_mfcc + 1, n_mels)[1:]
    return (mel @ dct.T).astype(np.float32)


def delta(feat: np.ndarray, width: int = 2) -> np.ndarray:
    """Дельта-признаки (регрессия по времени), как в стандартном MFCC-пайплайне."""
    if len(feat) < 2:
        return np.zeros_like(feat)
    pad = np.pad(feat, ((width, width), (0, 0)), mode="edge")
    denom = 2.0 * sum(i * i for i in range(1, width + 1))
    out = np.zeros_like(feat)
    for i in range(1, width + 1):
        out += i * (pad[width + i:width + i + len(feat)] - pad[width - i:width - i + len(feat)])
    return (out / denom).astype(np.float32)


def frame_hop_seconds(hop: int = 160, sr: int = SAMPLE_RATE) -> float:
    return hop / float(sr)