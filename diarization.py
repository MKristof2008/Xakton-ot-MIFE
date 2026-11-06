"""Диаризация (кто говорит) на pyannote/speaker-diarization-3.1, локально.

Вход: аудиофайл + сегменты от ASR.
Выход: те же сегменты с полем speaker_id ("speaker_1", ...).

Требует HF_TOKEN в окружении для первой загрузки gated-модели
(нужно принять условия на https://huggingface.co/pyannote/speaker-diarization-3.1).
Дальше работает офлайн из кэша.
"""

from __future__ import annotations

import os

DEFAULT_MODEL = "pyannote/speaker-diarization-3.1"


def _load_pipeline(model_name: str = DEFAULT_MODEL):
    from pyannote.audio import Pipeline

    token = os.environ.get("HF_TOKEN")
    pipeline = Pipeline.from_pretrained(model_name, token=token)
    return pipeline


def diarize(audio_path: str, segments: list[dict],
            num_speakers: int | None = None,
            model_name: str = DEFAULT_MODEL) -> list[dict]:
    """Добавляет speaker_id к каждому сегменту ASR.

    Совпадение: сегмент получает спикера, чей интервал максимально
    пересекается с серединой сегмента (устойчиво к перебиваниям).
    """
    pipeline = _load_pipeline(model_name)
    kwargs = {}
    if num_speakers:
        kwargs["num_speakers"] = num_speakers
    diarization = pipeline(audio_path, **kwargs)

    # Собираем список реплик: (start_ms, end_ms, speaker)
    turns = []
    for turn, _track, speaker in diarization.itertracks(yield_label=True):
        turns.append((int(turn.start * 1000), int(turn.end * 1000), speaker))

    # Нумеруем спикеров в порядке первого появления
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
    fake_segments = [
        {"start_ms": 0, "end_ms": 3000, "text": "..."},
        {"start_ms": 3500, "end_ms": 6000, "text": "..."},
    ]
    out = diarize(path, fake_segments)
    print(json.dumps(out, ensure_ascii=False, indent=2))
