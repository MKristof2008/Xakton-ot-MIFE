"""Диаризация (кто говорит) — интерфейс из ТЗ, три бэкенда.

Вход: аудиофайл + сегменты от ASR.
Выход: те же сегменты с полем speaker_id ("speaker_1", ...).

Бэкенды (по убыванию точности, выбираются автоматически):
- pyannote  — pyannote/speaker-diarization-3.1. Требует pyannote.audio,
              HF_TOKEN и принятых условий на huggingface.co.
- speechbrain — ECAPA-TDNN + кластеризация (diarize.py). Без токенов,
              только torch. Работает сразу после установки.
- mfcc      — резервный, чистый numpy. Слаб на реальных голосах,
              нужен только чтобы пайплайн не падал.

Почему не только pyannote: это gated-модель, без HF_TOKEN она не грузится,
и пайплайн из ТЗ падал на шаге 2. Теперь при отсутствии токена берётся
speechbrain, а если и его нет — mfcc. Явный выбор: DIARIZATION_BACKEND.
"""

from __future__ import annotations

import os

DEFAULT_MODEL = "pyannote/speaker-diarization-3.1"
BACKEND_ENV = "DIARIZATION_BACKEND"


# ---------- pyannote ----------

def _load_pipeline(model_name: str = DEFAULT_MODEL):
    from pyannote.audio import Pipeline

    token = os.environ.get("HF_TOKEN")
    pipeline = Pipeline.from_pretrained(model_name, token=token)
    return pipeline


def _diarize_pyannote(audio_path: str, segments: list[dict],
                      num_speakers: int | None, model_name: str) -> list[list]:
    """Реплики в формате [(start_ms, end_ms, метка)]."""
    pipeline = _load_pipeline(model_name)
    kwargs = {}
    if num_speakers:
        kwargs["num_speakers"] = num_speakers
    diarization = pipeline(audio_path, **kwargs)

    turns = []
    for turn, _track, speaker in diarization.itertracks(yield_label=True):
        turns.append((int(turn.start * 1000), int(turn.end * 1000), speaker))
    return turns


# ---------- speechbrain / mfcc ----------

def _diarize_local(audio_path: str, num_speakers: int | None,
                   embedding: str | None) -> list[list]:
    import diarize as local

    result = local.diarize(audio_path, num_speakers=num_speakers, backend=embedding)
    return [(int(t.start * 1000), int(t.end * 1000), t.speaker) for t in result.turns]


def _available(backend: str) -> bool:
    if backend == "pyannote":
        try:
            import pyannote.audio  # noqa: F401

            return bool(os.environ.get("HF_TOKEN"))
        except Exception:
            return False
    if backend == "speechbrain":
        try:
            import speechbrain  # noqa: F401
            import torch  # noqa: F401

            return True
        except Exception:
            return False
    return backend == "mfcc"


def choose_backend(requested: str | None = None) -> str:
    """Выбирает первый доступный бэкенд, не роняя пайплайн."""
    if requested:
        if not _available(requested):
            raise RuntimeError(
                f"Бэкенд диаризации '{requested}' недоступен. "
                "pyannote требует pyannote.audio и HF_TOKEN; "
                "speechbrain — пакеты torch и speechbrain."
            )
        return requested

    env = os.environ.get(BACKEND_ENV)
    if env:
        return env

    for candidate in ("pyannote", "speechbrain", "mfcc"):
        if _available(candidate):
            return candidate
    return "mfcc"


# ---------- общий API ----------

def diarize(audio_path: str, segments: list[dict],
            num_speakers: int | None = None,
            model_name: str = DEFAULT_MODEL,
            backend: str | None = None,
            embedding: str | None = None) -> list[dict]:
    """Добавляет speaker_id к каждому сегменту ASR.

    Совпадение: сегмент получает спикера с максимальным пересечением по
    времени; если пересечений нет (реплика в паузе) — ближайшего по середине.
    """
    chosen = choose_backend(backend)
    if chosen == "pyannote":
        turns = _diarize_pyannote(audio_path, segments, num_speakers, model_name)
    else:
        turns = _diarize_local(audio_path, num_speakers,
                               embedding if chosen != "pyannote" else None)

    speaker_map: dict[str, str] = {}
    result = []
    for seg in segments:
        best = _best_speaker(seg["start_ms"], seg["end_ms"], turns)
        if best not in speaker_map:
            speaker_map[best] = f"speaker_{len(speaker_map) + 1}"
        result.append({**seg, "speaker_id": speaker_map[best]})
    return result


def _best_speaker(start_ms: int, end_ms: int, turns: list[tuple[int, int, str]]) -> str:
    """Спикер с максимальным пересечением по времени; иначе ближайший по середине."""
    best_speaker = None
    best_overlap = 0
    for t_start, t_end, speaker in turns:
        overlap = min(end_ms, t_end) - max(start_ms, t_start)
        if overlap > best_overlap:
            best_overlap = overlap
            best_speaker = speaker
    if best_speaker is not None:
        return best_speaker
    # Нет пересечений (реплика в паузе) — берём ближайший интервал
    mid = (start_ms + end_ms) // 2
    if not turns:
        return "unknown"
    return min(turns, key=lambda t: min(abs(mid - t[0]), abs(mid - t[1])))[2]


if __name__ == "__main__":
    import json
    import sys

    path = sys.argv[1]
    backend = sys.argv[2] if len(sys.argv) > 2 else None
    fake_segments = [
        {"start_ms": 0, "end_ms": 3000, "text": "..."},
        {"start_ms": 3500, "end_ms": 6000, "text": "..."},
    ]
    print(f"бэкенд: {choose_backend(backend)}", file=sys.stderr)
    out = diarize(path, fake_segments, backend=backend)
    print(json.dumps(out, ensure_ascii=False, indent=2))