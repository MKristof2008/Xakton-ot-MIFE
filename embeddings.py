"""Эмбеддинги говорящего: два взаимозаменяемых бэкенда.

- mfcc  — MFCC + дельты, mean/std-пулинг, CMVN по файлу. Только numpy,
          без весов и внешних сервисов; базовое качество.
- ecapa — speechbrain/spkrec-ecapa-voxceleb (192-мерные эмбеддинги).
          Заметно точнее на реальных записях; нужен torch + speechbrain.

Бэкенд задаётся аргументом или переменной окружения EMBEDDING_BACKEND.
Если ecapa недоступен, происходит откат на mfcc с предупреждением.
"""
from __future__ import annotations

import os
import warnings

import numpy as np

import audio_utils
from audio_utils import SAMPLE_RATE

DEFAULT_BACKEND = os.getenv("EMBEDDING_BACKEND", "auto")
ECAPA_SOURCE = os.getenv("ECAPA_SOURCE", "speechbrain/spkrec-ecapa-voxceleb")
ECAPA_CACHE = os.getenv("ECAPA_CACHE", ".ecapa_cache")


def _l2(v: np.ndarray) -> np.ndarray:
    return v / (np.linalg.norm(v) + 1e-9)


def _allow_copy_instead_of_symlink() -> None:
    """Windows без прав на symlink: speechbrain копирует файлы вместо ссылок."""
    try:
        import shutil

        import speechbrain.utils.fetching as fetching
    except Exception:
        return
    if getattr(fetching, "_copy_patch_applied", False):
        return

    def link_with_strategy(src, dst, local_strategy=None):
        src, dst = str(src), str(dst)
        os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
        if os.path.exists(dst):
            os.remove(dst)
        shutil.copyfile(src, dst)

    fetching.link_with_strategy = link_with_strategy
    fetching._copy_patch_applied = True


class MfccEmbedder:
    """MFCC + дельты, пулинг mean/std по окну, L2-нормировка."""

    name = "mfcc"
    # Разделимость голосов у MFCC слабая, силуэт «плывёт» от k к k, поэтому
    # допуск широкий: предпочитаем меньшее число спикеров.
    silhouette_tolerance = 0.05

    def __init__(self, sr: int = SAMPLE_RATE):
        self.sr = sr
        self._feats: np.ndarray | None = None

    def prepare(self, audio: np.ndarray) -> None:
        """Считает кадровые признаки один раз на файл (CMVN по всему файлу)."""
        feat = audio_utils.mfcc(audio, sr=self.sr)
        feat = np.hstack([feat, audio_utils.delta(feat)])
        feat = (feat - feat.mean(axis=0, keepdims=True)) / (feat.std(axis=0, keepdims=True) + 1e-6)
        self._feats = (feat / (np.linalg.norm(feat, axis=1, keepdims=True) + 1e-9)).astype(np.float32)

    def embed(self, audio: np.ndarray, windows: list[tuple[float, float]]) -> np.ndarray:
        if self._feats is None:
            self.prepare(audio)
        hop = audio_utils.frame_hop_seconds(sr=self.sr)
        out = np.zeros((len(windows), self._feats.shape[1] * 2), dtype=np.float32)
        for i, (start, end) in enumerate(windows):
            a = max(0, int(start / hop))
            b = min(len(self._feats), max(a + 1, int(end / hop)))
            chunk = self._feats[a:b]
            out[i] = _l2(np.concatenate([chunk.mean(axis=0), chunk.std(axis=0)]))
        return out


class EcapaEmbedder:
    """speechbrain ECAPA-TDNN, обученный на VoxCeleb."""

    name = "ecapa"
    # Эмбеддинги хорошо разделяют голоса, силуэт устойчив — допуск узкий,
    # иначе два реальных спикера схлопываются в одного.
    silhouette_tolerance = 0.01

    def __init__(self, sr: int = SAMPLE_RATE, source: str | None = None):
        import torch
        from speechbrain.inference.speaker import EncoderClassifier

        _allow_copy_instead_of_symlink()
        self.sr = sr
        self._torch = torch
        self._model = EncoderClassifier.from_hparams(
            source=source or ECAPA_SOURCE,
            savedir=ECAPA_CACHE,
            run_opts={"device": "cpu"},
        )
        self._model.eval()

    def prepare(self, audio: np.ndarray) -> None:
        self._audio = np.asarray(audio, dtype=np.float32)

    def embed(self, audio: np.ndarray, windows: list[tuple[float, float]]) -> np.ndarray:
        out = np.zeros((len(windows), 192), dtype=np.float32)
        min_len = int(0.2 * self.sr)
        with self._torch.no_grad():
            for i, (start, end) in enumerate(windows):
                a = max(0, int(start * self.sr))
                b = min(len(audio), int(end * self.sr))
                chunk = np.asarray(audio[a:b], dtype=np.float32)
                if chunk.size < min_len:
                    chunk = np.pad(chunk, (0, min_len - chunk.size))
                tensor = self._torch.from_numpy(chunk).unsqueeze(0)
                emb = self._model.encode_batch(tensor).squeeze().cpu().numpy().reshape(-1)
                out[i] = _l2(emb.astype(np.float32))
        return out


def make_embedder(backend: str | None = None, sr: int = SAMPLE_RATE):
    """Создаёт эмбеддер.

    backend=None или "auto" — берём самый точный из доступных: ECAPA, иначе MFCC.
    Явный "ecapa" при недоступности пакета откатывается на MFCC с предупреждением.
    """
    backend = (backend or DEFAULT_BACKEND).lower()
    if backend in {"ecapa", "auto"}:
        try:
            return EcapaEmbedder(sr)
        except Exception as exc:
            if backend == "ecapa":
                warnings.warn(
                    f"ECAPA недоступен ({type(exc).__name__}: {exc}); использую MFCC.",
                    stacklevel=2,
                )
    return MfccEmbedder(sr)


def available_backends() -> dict[str, bool]:
    """Что реально можно использовать в этом окружении."""
    result = {"mfcc": True, "ecapa": False}
    try:
        import speechbrain  # noqa: F401
        import torch  # noqa: F401

        result["ecapa"] = True
    except Exception:
        pass
    return result