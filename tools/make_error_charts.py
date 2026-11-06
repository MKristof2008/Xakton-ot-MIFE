#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Графики анализа ошибок из docs/analysis/ERROR_ANALYSIS.md.
Данные — только из таблицы реестра ошибок, ничего не выдумано."""
import re
from collections import Counter
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "analysis" / "ERROR_ANALYSIS.md"
IMG = ROOT / "docs" / "img"

types, statuses = [], []
in_registry = False
for line in SRC.read_text(encoding="utf-8").splitlines():
    if line.startswith("## Реестр ошибок"):
        in_registry = True
        continue
    if in_registry and line.startswith("## "):
        break
    if in_registry:
        m = re.match(r"\| \d+ \| (E\d) \| .* \| .* \| (\S+)", line)
        if m:
            types.append(m.group(1))
            st = m.group(2).strip("*")
            statuses.append("Исправлена" if "Исправлена" in st else
                            "Частично" if "Частично" in st else "Открыта")

if not types:
    raise SystemExit("реестр пуст")

tc = Counter(types)
sc = Counter(statuses)

fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11, 4.2))
labels = sorted(tc)
ax1.bar(labels, [tc[k] for k in labels], color="#c0504d")
ax1.set_title("Ошибки по типам")
ax1.set_ylabel("кол-во случаев")

order = ["Открыта", "Частично", "Исправлена"]
colors = {"Открыта": "#d9534f", "Частично": "#f0ad4e", "Исправлена": "#5cb85c"}
ax2.bar(order, [sc.get(k, 0) for k in order], color=[colors[k] for k in order])
ax2.set_title("Статус ошибок")

fig.tight_layout()
IMG.mkdir(parents=True, exist_ok=True)
fig.savefig(IMG / "errors_by_type.png", dpi=120)

fig2, ax = plt.subplots(figsize=(7, 4))
ax.bar([f"{t}" for t in labels], [tc[k] for k in labels], color="#4b6cb7")
fig2.savefig(IMG / "errors_status.png", dpi=120)  # placeholder replaced below
plt.close("all")

# errors_status.png: статус как отдельный файл
fig3, ax3 = plt.subplots(figsize=(6, 4))
ax3.bar(order, [sc.get(k, 0) for k in order], color=[colors[k] for k in order])
ax3.set_title("Статус ошибок (все источники)")
ax3.set_ylabel("кол-во")
fig3.tight_layout()
fig3.savefig(IMG / "errors_status.png", dpi=120)
print(f"OK: {len(types)} ошибок, типы={dict(tc)}, статусы={dict(sc)}")
