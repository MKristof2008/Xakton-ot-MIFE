"""Сравнение WER двух гипотез ASR по одному эталону.

Зачем отдельный скрипт: замеры на устройстве печатают гипотезу в logcat,
а эталон лежит в artifacts/. Считать WER на ПК проще и надёжнее, чем
тащить эталон в тестовый APK для каждого прогона.

Нормализация та же, что в WerTest.kt (регистр, пунктуация, ё/е), иначе
цифры с телефона и с ПК нельзя сравнивать между собой.

Использование:
    python tools/wer_compare.py <эталон.txt> <гипотеза1.txt> [гипотеза2.txt ...]
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


def normalize(text: str) -> str:
    text = text.lower().replace("ё", "е")
    text = re.sub(r"[^\w\s]", " ", text, flags=re.UNICODE)
    return re.sub(r"\s+", " ", text).strip()


def wer(reference: str, hypothesis: str) -> float:
    ref = normalize(reference).split()
    hyp = normalize(hypothesis).split()
    if not ref:
        return 0.0 if not hyp else 1.0

    prev = list(range(len(hyp) + 1))
    for i, rw in enumerate(ref, start=1):
        cur = [i] + [0] * len(hyp)
        for j, hw in enumerate(hyp, start=1):
            cost = 0 if rw == hw else 1
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        prev = cur
    return prev[len(hyp)] / len(ref)


def main() -> None:
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    reference = Path(sys.argv[1]).read_text(encoding="utf-8")
    print(f"эталон: {len(normalize(reference).split())} слов\n")

    for path in sys.argv[2:]:
        hyp = Path(path).read_text(encoding="utf-8")
        print(f"{Path(path).name}: WER = {wer(reference, hyp):.4f}")


if __name__ == "__main__":
    main()
