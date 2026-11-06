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


def der(reference: list[dict], hypothesis: list[dict], step_ms: int = 100) -> float:
    """Упрощённый DER: доля времени с неверно определённым спикером.

    reference/hypothesis: сегменты [{"start_ms", "end_ms", "speaker_id"}].

    Метки сопоставляются жадной перестановкой по объёму совпадений: в ТЗ наш
    вывод всегда подписан speaker_N, а эталон может быть размечен как A/B/C.
    Раньше метки сравнивались буквально, и идеальная диаризация с другими
    именами давала DER 1.0 вместо 0.0.
    """
    if not reference:
        return 0.0

    end = max(s["end_ms"] for s in reference)

    # Сетка времени: на каждом шаге — кто говорил по эталону и по гипотезе
    ref_at: list[set[str]] = []
    hyp_at: list[set[str]] = []
    for t in range(0, end, step_ms):
        ref_at.append({s["speaker_id"] for s in reference if s["start_ms"] <= t < s["end_ms"]})
        hyp_at.append({s["speaker_id"] for s in hypothesis if s["start_ms"] <= t < s["end_ms"]})

    scored = [i for i, r in enumerate(ref_at) if r]
    if not scored:
        return 0.0

    # Матрица совпадений: сколько шагов каждая пара (эталон, гипотеза) провела вместе
    ref_labels = sorted({lab for r in ref_at for lab in r})
    hyp_labels = sorted({lab for h in hyp_at for lab in h})
    overlap: dict[tuple[str, str], int] = {}
    for i in scored:
        for r in ref_at[i]:
            for h in hyp_at[i]:
                overlap[(r, h)] = overlap.get((r, h), 0) + 1

    # Жадное сопоставление: самая крупная пара, затем следующая
    mapping: dict[str, str] = {}
    used_hyp: set[str] = set()
    for (r, h), _count in sorted(overlap.items(), key=lambda kv: -kv[1]):
        if r in mapping or h in used_hyp:
            continue
        mapping[r] = h
        used_hyp.add(h)

    wrong = 0
    for i in scored:
        expected = {mapping.get(r, "<нет>") for r in ref_at[i]}
        if expected != hyp_at[i]:
            wrong += 1
    return wrong / len(scored)
