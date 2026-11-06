"""Метрики качества: WER (распознавание), RTF (скорость), DER (диаризация).

Без внешних зависимостей — только стандартная библиотека.
"""

from __future__ import annotations

import re
import time


def _normalize(text: str) -> list[str]:
    text = text.lower()
    text = re.sub(r"[^\w\s]", " ", text, flags=re.UNICODE)
    return text.split()


def wer(reference: str, hypothesis: str) -> float:
    """Word Error Rate: (S + D + I) / N. 0.0 — идеально."""
    ref = _normalize(reference)
    hyp = _normalize(hypothesis)
    if not ref:
        return 0.0 if not hyp else 1.0
    # Расстояние Левенштейна по словам
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h))
        prev = cur
    return prev[-1] / len(ref)


class RtfTimer:
    """Замеряет RTF = время_обработки / длительность_аудио. Чем меньше, тем быстрее."""

    def __init__(self) -> None:
        self._t0 = time.perf_counter()

    def rtf(self, audio_duration_sec: float) -> float:
        elapsed = time.perf_counter() - self._t0
        return elapsed / audio_duration_sec if audio_duration_sec > 0 else 0.0


def audio_duration_sec(path: str) -> float:
    """Длительность wav-файла без сторонних библиотек."""
    import wave
    with wave.open(path, "rb") as w:
        return w.getnframes() / w.getframerate()


def der(reference: list[dict], hypothesis: list[dict]) -> float:
    """Упрощённый DER: доля времени с неверно определённым спикером.

    reference/hypothesis: сегменты [{"start_ms", "end_ms", "speaker_id"}].
    Ошибкой считаем время, где множества говорящих не совпадают (грубая оценка).
    """
    if not reference:
        return 0.0

    def total_time(segs: list[dict]) -> float:
        return sum(max(0, s["end_ms"] - s["start_ms"]) for s in segs)

    # Семплируем шкалу времени шагом 100 мс
    step = 100
    end = max(s["end_ms"] for s in reference)
    wrong = 0
    total = 0
    for t in range(0, end, step):
        ref_spk = {s["speaker_id"] for s in reference if s["start_ms"] <= t < s["end_ms"]}
        hyp_spk = {s["speaker_id"] for s in hypothesis if s["start_ms"] <= t < s["end_ms"]}
        if not ref_spk:
            continue
        total += 1
        if ref_spk != hyp_spk:
            wrong += 1
    if total == 0:
        return 0.0
    return wrong / total
