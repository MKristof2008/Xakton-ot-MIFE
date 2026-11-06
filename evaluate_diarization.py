"""Оценка диаризации по ТЗ: DER на контрольном наборе.

Считает DER для пайплайна `diarization.diarize` в формате ТЗ (start_ms/end_ms/
speaker_id) и печатает итог. Для сравнения — тот же прогон моего движка
напрямую (`diarize.diarize`).

Запуск:
    py evaluate_diarization.py
"""
from __future__ import annotations

import io
import json
import sys
from pathlib import Path

# Windows-консоль: печатаем русский текст без падений на cp1251
for _name in ("stdout", "stderr"):
    _stream = getattr(sys, _name, None)
    if getattr(_stream, "buffer", None) is not None:
        setattr(sys, _name, io.TextIOWrapper(_stream.buffer, encoding="utf-8", errors="replace"))

SAMPLE_RATE = 16_000
TOLERANCE_SEC = 0.25


def _der(ref_turns: list[tuple[float, float, str]],
         hyp_turns: list[tuple[float, float, str]],
         step: float = 0.01) -> dict:
    """DER по сетке времени с исключением стыков ±TOLERANCE_SEC."""
    import numpy as np

    end = max([e for _, e, _ in ref_turns] + [e for _, e, _ in hyp_turns] + [0.0])
    grid = np.arange(0.0, end, step)
    ref = np.full(len(grid), "", dtype=object)
    for start, stop, spk in ref_turns:
        ref[(grid >= start) & (grid < stop)] = spk
    hyp = np.full(len(grid), "", dtype=object)
    for start, stop, spk in hyp_turns:
        hyp[(grid >= start) & (grid < stop)] = spk

    ignore = np.zeros(len(grid), dtype=bool)
    for turns in (ref_turns, hyp_turns):
        for start, stop, _ in turns:
            ignore |= (grid >= start - TOLERANCE_SEC) & (grid < start + TOLERANCE_SEC)
            ignore |= (grid >= stop - TOLERANCE_SEC) & (grid < stop + TOLERANCE_SEC)

    scored = ~ignore
    total = max(1, int((ref[scored] != "").sum()))
    miss = int(((ref[scored] != "") & (hyp[scored] == "")).sum())
    fa = int(((ref[scored] == "") & (hyp[scored] != "")).sum())

    both = (ref[scored] != "") & (hyp[scored] != "")
    ref_labels = sorted({r for r in ref[scored] if r})
    hyp_labels = sorted({h for h in hyp[scored] if h})
    confusion = 0
    if both.any() and ref_labels and hyp_labels:
        counts = {}
        for r in ref_labels:
            for h in hyp_labels:
                counts[(r, h)] = int((both & (ref[scored] == r) & (hyp[scored] == h)).sum())
        # жадное сопоставление меток: самая большая пара, затем следующая
        pairs = sorted(counts.items(), key=lambda kv: -kv[1])
        used_r, used_h, correct = set(), set(), 0
        for (r, h), n in pairs:
            if r in used_r or h in used_h:
                continue
            used_r.add(r)
            used_h.add(h)
            correct += n
        confusion = int(both.sum()) - correct

    return {
        "der": round((miss + fa + confusion) / total, 4),
        "miss_sec": round(miss * step, 2),
        "false_alarm_sec": round(fa * step, 2),
        "confusion_sec": round(confusion * step, 2),
        "ref_speech_sec": round(total * step, 2),
    }


def load_case(truth_path: Path) -> list[tuple[float, float, str]]:
    raw = json.loads(truth_path.read_text(encoding="utf-8"))
    return [(t["start"], t["end"], t["speaker"]) for t in raw]


def segments_from_truth(truth: list[tuple[float, float, str]]) -> list[dict]:
    """Режем эталон на сегменты ASR — так диаризация получает вход по ТЗ."""
    return [
        {"start_ms": int(start * 1000), "end_ms": int(stop * 1000), "text": "..."}
        for start, stop, _ in truth
    ]


def main() -> int:
    import diarization

    data_dir = Path(__file__).parent / "testdata"
    cases = sorted(data_dir.glob("*.truth.json"))
    if not cases:
        print(f"Нет контрольных файлов в {data_dir}", file=sys.stderr)
        return 1

    backend = diarization.choose_backend()
    print(f"Бэкенд диаризации: {backend}\n")

    ok = True
    for truth_path in cases:
        audio = truth_path.with_name(truth_path.name.replace(".truth.json", ".wav"))
        if not audio.exists():
            print(f"пропуск {truth_path.name}: нет {audio.name}")
            continue

        truth = load_case(truth_path)
        segments = segments_from_truth(truth)

        out = diarization.diarize(str(audio), segments, num_speakers=None)
        hyp = [(s["start_ms"] / 1000, s["end_ms"] / 1000, s["speaker_id"]) for s in out]

        expected = len({t[2] for t in truth})
        found = len({h[2] for h in hyp})
        metrics = _der(truth, hyp)

        status = "OK" if found == expected else f"СПИКЕРЫ {found} вместо {expected}"
        ok = ok and found == expected
        print(f"{audio.name}: DER {metrics['der']:.4f} | спикеров {found}/{expected} [{status}]")
        print(f"    пропуск {metrics['miss_sec']}с, ложные {metrics['false_alarm_sec']}с, "
              f"путаница {metrics['confusion_sec']}с, речи {metrics['ref_speech_sec']}с")

    print("\nИТОГ:", "все случаи верны" if ok else "есть расхождения")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())