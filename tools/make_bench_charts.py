"""Графики бенчмарка из docs/benchmarks/measurements.csv (протокол капитана).

Графики строятся ТОЛЬКО из реальных строк CSV. Пустые колонки (нет данных)
в график не попадают — точки/столбцы рисуются лишь там, где есть значения.

Графики:
  docs/img/bench_wer.png    — WER по конфигурациям (столбцы, подписи)
  docs/img/bench_rtf.png    — RTF по конфигурациям + линия RTF=1
  docs/img/bench_pareto.png — Парето: WER (X) vs RTF (Y)
  docs/img/bench_der.png    — DER по записям

Запуск: py -3 tools/make_bench_charts.py
"""
import csv
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
CSV_PATH = ROOT / "docs" / "benchmarks" / "measurements.csv"
IMG_DIR = ROOT / "docs" / "img"


def label(row: dict) -> str:
    """Подпись конфигурации для графика.

    Колонка config — это класс замера (asr/diarization/llm), одинаковая
    у всех строк, поэтому на графике подписываемся моделью и уточнением:
    без этого все точки Парето были подписаны «asr».
    """
    parts = [row.get("config", ""), row.get("model", "")]
    note = (row.get("notes") or "").lower()
    if "выбрана" in note:
        parts.append("★")
    if row.get("file") and row["file"] != "voxforge-ru-20":
        parts.append(f"[{row['file']}]")
    return " ".join(p for p in parts if p)


def load_rows(path: Path) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f) if any((v or "").strip() for v in r.values())]
    return rows


def fnum(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def chart_bars(rows, key, title, ylabel, out, ref_line=None, ref_label=None):
    pts = [(label(r), fnum(r[key])) for r in rows]
    pts = [(c, v) for c, v in pts if v is not None]
    if not pts:
        print(f"нет данных для {out}")
        return
    names = [c for c, _ in pts]
    vals = [v for _, v in pts]
    fig, ax = plt.subplots(figsize=(max(6, len(names) * 1.2), 4.5))
    bars = ax.bar(range(len(vals)), vals, color="#4C72B0")
    ax.set_xticks(range(len(names)), names, rotation=30, ha="right", fontsize=8)
    ax.set_title(title)
    ax.set_ylabel(ylabel)
    for b, v in zip(bars, vals):
        ax.text(b.get_x() + b.get_width() / 2, v, f"{v:.2f}", ha="center", va="bottom", fontsize=8)
    if ref_line is not None:
        ax.axhline(ref_line, color="red", linestyle="--", label=ref_label)
        ax.legend()
    ax.margins(y=0.15)
    fig.tight_layout()
    fig.savefig(out, dpi=150)
    plt.close(fig)
    print(f"ок: {out}")


def chart_pareto(rows, out):
    pts = [
        (label(r), fnum(r["wer"]), fnum(r["rtf"]))
        for r in rows
        if fnum(r["wer"]) is not None and fnum(r["rtf"]) is not None
    ]
    if not pts:
        print(f"нет данных для {out}")
        return
    fig, ax = plt.subplots(figsize=(7, 5))
    for name, wer, rtf in pts:
        ax.scatter(wer, rtf, s=60)
        ax.annotate(name, (wer, rtf), textcoords="offset points", xytext=(6, 6), fontsize=8)
    ax.axhline(1.0, color="red", linestyle="--", label="RTF = 1")
    ax.set_xlabel("WER (ниже лучше)")
    ax.set_ylabel("RTF (ниже лучше)")
    ax.set_title("Парето: WER против RTF (по конфигурациям)")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out, dpi=150)
    plt.close(fig)
    print(f"ок: {out}")


def chart_der_by_file(rows, out):
    pts = [
        (f"{r['config']} / {Path(r['file']).name}", fnum(r["der"]))
        for r in rows
        if fnum(r["der"]) is not None
    ]
    if not pts:
        print(f"нет данных для {out}")
        return
    names = [c for c, _ in pts]
    vals = [v for _, v in pts]
    fig, ax = plt.subplots(figsize=(max(7, len(names) * 1.3), 4.5))
    bars = ax.bar(range(len(vals)), vals, color="#DD8452")
    ax.set_xticks(range(len(names)), names, rotation=30, ha="right", fontsize=8)
    ax.set_title("DER по записям")
    ax.set_ylabel("DER (ниже лучше)")
    for b, v in zip(bars, vals):
        ax.text(b.get_x() + b.get_width() / 2, v, f"{v:.3f}", ha="center", va="bottom", fontsize=8)
    ax.margins(y=0.15)
    fig.tight_layout()
    fig.savefig(out, dpi=150)
    plt.close(fig)
    print(f"ок: {out}")


def main():
    if not CSV_PATH.exists():
        sys.exit(f"нет файла {CSV_PATH}")
    IMG_DIR.mkdir(parents=True, exist_ok=True)
    rows = load_rows(CSV_PATH)
    if not rows:
        sys.exit("CSV пуст — замеров ещё нет, графики не строим")
    chart_bars(rows, "wer", "WER по конфигурациям", "WER (ниже лучше)", IMG_DIR / "bench_wer.png")
    chart_bars(
        rows, "rtf", "RTF по конфигурациям", "RTF (ниже лучше)", IMG_DIR / "bench_rtf.png",
        ref_line=1.0, ref_label="RTF = 1 (реальное время)",
    )
    chart_pareto(rows, IMG_DIR / "bench_pareto.png")
    chart_der_by_file(rows, IMG_DIR / "bench_der.png")


if __name__ == "__main__":
    main()
