#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сводный график RTF по живым прогонам из docs/runs/README.md.
Данные берутся только из таблицы в README — ничего не выдумано."""
import re
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
README = ROOT / "docs" / "runs" / "README.md"
OUT = ROOT / "docs" / "img" / "runs_rtf.png"

rows = []
for line in README.read_text(encoding="utf-8").splitlines():
    m = re.match(r"\| \[?(run-\d+)\]?.*\| ([\w.\-]+) \| \w+ \| ([\d ]+) \| ([\d ]+) \| ([\d.]+) \|", line)
    if m and "TODO" not in line:
        rows.append((m.group(1), m.group(2), float(m.group(5))))

if not rows:
    raise SystemExit("нет данных для графика")

names = [f"{r[0]}\n{r[1]}" for r in rows]
rtf = [r[2] for r in rows]

fig, ax = plt.subplots(figsize=(8, 4.5))
bars = ax.bar(names, rtf, color=["#d9534f" if v > 1 else "#5cb85c" for v in rtf])
ax.axhline(1.0, color="green", linestyle="--", label="цель RTF = 1")
for b, v in zip(bars, rtf):
    ax.text(b.get_x() + b.get_width() / 2, v + max(rtf) * 0.02, f"{v:.2f}", ha="center")
ax.set_ylabel("RTF (меньше — лучше)")
ax.set_title("Живые прогоны на SM-S938B: RTF по моделям")
ax.legend()
fig.tight_layout()
OUT.parent.mkdir(parents=True, exist_ok=True)
fig.savefig(OUT, dpi=120)
print(f"OK: {OUT} ({len(rows)} прогонов)")
