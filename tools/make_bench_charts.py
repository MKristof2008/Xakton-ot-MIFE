"""Графики для отчёта «Результаты собственных тестов».

ВСЕ данные — из docs/benchmarks/measurements.csv (кроме шагов DER и размеров
кандидатов LLM: они взяты из docs/ML_MODELS.md и подписаны источниками в коде).
Ничего не додумываем: если замера нет — точки на графике нет.

Графики (docs/img/):
  bench_wer.png       — WER моделей распознавания, замер на телефоне
  bench_rtf.png       — RTF: скорость обработки
  bench_pareto.png    — компромисс: WER против размера модели (выбор квантования)
  bench_der.png       — DER диаризации: что дала постобработка (шаги)
  bench_llm_role.png  — A/B роли LLM: что модель делает в пайплайне (ПК)
  bench_llm_size.png  — кандидаты LLM: размер против памяти телефона

Запуск: py -3 -I tools/make_bench_charts.py
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

# человеческие подписи вместо технических id из CSV
PRETTY = {
    "whisper-tiny": "tiny",
    "whisper-base": "base",
    "whisper-small": "small f16",
    "whisper-small-q5_1": "small q5_1",
    "qwen2.5-0.5b-instruct": "Qwen2.5-0.5B",
    "qwen2.5-1.5b-instruct": "Qwen2.5-1.5B",
    "sherpa-pyannote3.0+3dspeaker": "pyannote-3.0 + 3D-Speaker",
}

# конфигурация, которая реально стоит в APK
CHOSEN_MODEL = "whisper-small-q5_1"

BLUE = "#3D6FB5"
RED = "#C0392B"
GREEN = "#2E7D5B"
ORANGE = "#C97B3C"
GREY = "#9AA3AE"


def load_rows(path: Path) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return [r for r in csv.DictReader(f) if any((v or "").strip() for v in r.values())]


def fnum(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def clean_axes(ax):
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", color="#E3E7EC", linewidth=0.8, zorder=0)
    ax.set_axisbelow(True)


def star(r) -> str:
    return " ★" if r.get("model") == CHOSEN_MODEL and r.get("file") == "voxforge-ru-20" else ""


def bar_chart(vals, labels, title, ylabel, out, color=BLUE, ref=None, ref_label=None,
              fmt="{:.2f}", pad=0.20, figsize=None):
    if not vals:
        print(f"нет данных для {out.name}")
        return
    fig, ax = plt.subplots(figsize=figsize or (max(6.4, len(vals) * 1.35), 4.3))
    bars = ax.bar(range(len(vals)), vals, color=color, width=0.58, zorder=2)
    ax.set_xticks(range(len(labels)), labels, fontsize=10)
    ax.set_ylabel(ylabel, fontsize=10)
    ax.set_title(title, fontsize=12.5, pad=14)
    clean_axes(ax)
    for b, v in zip(bars, vals):
        ax.text(b.get_x() + b.get_width() / 2, v, fmt.format(v),
                ha="center", va="bottom", fontsize=10.5, fontweight="normal")
    top = max(vals) * (1 + pad)
    if ref is not None:
        ax.axhline(ref, color=RED, linestyle="--", linewidth=1.1, zorder=3)
        # подпись справа над линией, на белой подложке: иначе она ложится
        # на столбцы, которые выше ориентира, и её не читаешь
        ax.text(0.995, ref, ref_label or f"{ref:g}", transform=ax.get_yaxis_transform(),
                ha="right", va="bottom", fontsize=9, color=RED, zorder=4,
                bbox=dict(boxstyle="square,pad=0.22", facecolor="white",
                          edgecolor="none", alpha=0.92))
        top = max(top, ref * 1.16)
    ax.set_ylim(0, top)
    fig.tight_layout()
    fig.savefig(out, dpi=160, facecolor="white")
    plt.close(fig)
    print(f"ок: {out.name}")


def asr_rows(rows):
    return [r for r in rows if r.get("config") == "asr"]


def chart_wer(rows, out):
    """WER только по распознаванию — 20 записей, 273 слова, замер на телефоне."""
    pts = [(PRETTY.get(r["model"], r["model"]) + star(r), fnum(r["wer"]))
           for r in asr_rows(rows)
           if r.get("file") == "voxforge-ru-20" and fnum(r["wer"]) is not None]
    pts.sort(key=lambda p: p[1])
    bar_chart([v for _, v in pts], [n for n, _ in pts],
              "Ошибка распознавания речи (WER)\n20 записей русской речи · 273 слова · замер на телефоне",
              "WER — доля ошибок (ниже лучше)", out,
              ref=0.15, ref_label="ориентир 0.15")


def chart_rtf(rows, out):
    """RTF. Для small — замер на диалоге 80 с (реальный сценарий), для tiny/base — 20 записей."""
    entries = []
    for r in asr_rows(rows):
        m = r["model"]
        t = fnum(r["rtf"])
        if t is None:
            continue
        # на 20 коротких записях whisper паддит каждый файл до 30 с → RTF завышен.
        # если есть замер на диалоге, берём его и честно подписываем файл.
        if m in ("whisper-small", CHOSEN_MODEL) and r.get("file") == "dialog_2spk":
            entries.append((f"{PRETTY[m]}{' ★' if m == CHOSEN_MODEL else ''}\nдиалог 80 с", t))
        elif m in ("whisper-tiny", "whisper-base") and r.get("file") == "voxforge-ru-20":
            entries.append((f"{PRETTY[m]}\n20 записей", t))
    entries.sort(key=lambda e: e[1])
    bar_chart([v for _, v in entries], [n for n, _ in entries],
              "Скорость обработки (RTF)\nRTF 0.31 = 80-секундная запись обработана за 25 секунд",
              "RTF — время обработки / длительность", out, color=GREEN,
              ref=1.0, ref_label="RTF = 1 — реальное время")


def chart_pareto(rows, out):
    """Парето по компромиссу, который реально выбирали: WER против размера модели.

    RTF вынесен в отдельный график: он замерен на разных файлах, и смешивать
    величины с разной методикой на одной оси — способ соврать не заметив.
    """
    pts = []
    for r in asr_rows(rows):
        if r.get("file") != "voxforge-ru-20":
            continue
        w, size = fnum(r["wer"]), fnum(r["model_size_mb"])
        if w is not None and size is not None:
            pts.append((r["model"], w, size))
    if not pts:
        print(f"нет данных для {out.name}")
        return
    pts.sort(key=lambda p: p[2])

    fig, ax = plt.subplots(figsize=(7.4, 4.9))
    by = {}
    for m, w, size in pts:
        by[m] = (w, size)
        chosen = m == CHOSEN_MODEL
        ax.scatter(size, w, s=170 if chosen else 85,
                   color=RED if chosen else BLUE, zorder=3, edgecolor="white", linewidth=1.5)
        ax.annotate(f"{PRETTY.get(m, m)}{' ★ в сборке' if chosen else ''}",
                    (size, w), textcoords="offset points", xytext=(10, 9), fontsize=10)

    if "whisper-small" in by and CHOSEN_MODEL in by:
        w1, s1 = by[CHOSEN_MODEL]
        w2, s2 = by["whisper-small"]
        ax.annotate("", xy=(s1, w1), xytext=(s2, w2),
                    arrowprops=dict(arrowstyle="->", color=RED, linestyle="--", linewidth=1.4))
        ax.text((s1 + s2) / 2, (w1 + w2) / 2 - 0.030,
                "квантование: +0.8 п.п. ошибки,\n−284 МБ", ha="center", va="top",
                fontsize=9.5, color=RED)

    ax.set_xlabel("Размер модели, МБ (ниже лучше)", fontsize=10)
    ax.set_ylabel("WER — ошибка распознавания (ниже лучше)", fontsize=10)
    ax.set_title("Компромисс точность / вес модели\n20 записей русской речи · замер на телефоне",
                 fontsize=12.5, pad=14)
    clean_axes(ax)
    ax.grid(axis="x", color="#E3E7EC", linewidth=0.8)
    ax.margins(x=0.20, y=0.24)
    fig.tight_layout()
    fig.savefig(out, dpi=160, facecolor="white")
    plt.close(fig)
    print(f"ок: {out.name}")


def chart_der(out):
    """DER диаризации по шагам обработки — источник: docs/ML_MODELS.md.

    Главная мысль графика: 92 % улучшения дала постобработка, а не смена модели.
    """
    steps = [
        ("сырой вывод\nsherpa-onnx", 0.665),
        ("+ выравнивание\nметок в метрике", 0.414),
        ("+ склейка пауз\nодного говорящего", 0.372),
        ("+ деление пауз\nмежду говорящими", 0.082),
    ]
    vals = [v for _, v in steps]
    fig, ax = plt.subplots(figsize=(7.6, 4.3))
    bars = ax.bar(range(len(vals)), vals, color=[GREY, GREY, GREY, GREEN], width=0.58, zorder=2)
    ax.set_xticks(range(len(vals)), [n for n, _ in steps], fontsize=9.5)
    ax.set_ylabel("DER — доля ошибок (ниже лучше)", fontsize=10)
    ax.set_title("Разделение говорящих: 0.665 → 0.082\nдиалог 2 человека · 79.8 с · допуск 250 мс",
                 fontsize=12.5, pad=14)
    clean_axes(ax)
    for i, (b, v) in enumerate(zip(bars, vals)):
        label = f"{v:.3f}"
        if i > 0:
            delta = (vals[i - 1] - v) / vals[i - 1] * 100
            label += f"\n−{delta:.0f} %"
        ax.text(b.get_x() + b.get_width() / 2, v, label, ha="center", va="bottom",
                fontsize=10, fontweight="normal")
    ax.set_ylim(0, max(vals) * 1.30)
    fig.tight_layout()
    fig.savefig(out, dpi=160, facecolor="white")
    plt.close(fig)
    print(f"ок: {out.name}")


def chart_llm_role(rows, out):
    """A/B роли LLM на ПК. Показывает, почему режим «правка слов» отклонён."""
    variants = {
        "dialog_2spk-no-llm": "без LLM\n(базовая линия)",
        "dialog_2spk-full-edit": "полная правка\nслов моделью",
        "dialog_2spk-punct-only": "только пунктуация\nи регистр",
    }
    found = {}
    for r in rows:
        if r.get("config") != "llm":
            continue
        key = r.get("file")
        w = fnum(r["wer"])
        if key in variants and w is not None:
            found[key] = w
    if not found:
        print(f"нет данных для {out.name}")
        return
    order = [k for k in variants if k in found]
    labels = [variants[k] for k in order]
    vals = [found[k] for k in order]
    colors = [GREY if k != "dialog_2spk-full-edit" else RED for k in order]

    fig, ax = plt.subplots(figsize=(6.8, 4.3))
    bars = ax.bar(range(len(vals)), vals, color=colors, width=0.55, zorder=2)
    ax.set_xticks(range(len(vals)), labels, fontsize=10)
    ax.set_ylabel("WER", fontsize=10)
    ax.set_title("Что делает языковая модель: A/B-замер\nдиалог 79.8 с · ПК · Qwen2.5-1.5B",
                 fontsize=12.5, pad=14)
    clean_axes(ax)
    for b, v in zip(bars, vals):
        ax.text(b.get_x() + b.get_width() / 2, v, f"{v:.4f}",
                ha="center", va="bottom", fontsize=10, fontweight="normal")
    ax.set_ylim(0, max(vals) * 1.30)
    fig.tight_layout()
    fig.savefig(out, dpi=160, facecolor="white")
    plt.close(fig)
    print(f"ок: {out.name}")


def chart_llm_size(rows, out):
    """Кандидаты локальной LLM: размер против бюджета памяти телефона.

    Размеры — оценки Q4 из docs/ML_MODELS.md. Бюджет — измеренный пик ОЗУ
    сквозного прогона (873 МБ, measurements.csv). Качество замерено только
    у Qwen2.5, поэтому график про размер и про то, что влезает.
    """
    peak = None
    for r in rows:
        if r.get("config") == "pipeline":
            peak = max(peak or 0, fnum(r["ram_mb"]) or 0)

    candidates = [
        ("Qwen2.5-0.5B\nв сборке", 469, True),
        ("Qwen2.5-1.5B\nA/B на ПК", 1000, False),
        ("Gemma-2-2B\nне замерялась", 1600, False),
        ("Phi-3-mini\nне замерялась", 2300, False),
    ]
    labels = [c[0] for c in candidates]
    vals = [c[1] for c in candidates]
    colors = [RED if c[2] else "#C2C8D0" for c in candidates]

    fig, ax = plt.subplots(figsize=(7.4, 4.5))
    bars = ax.bar(range(len(vals)), vals, color=colors, width=0.56, zorder=2)
    ax.set_xticks(range(len(labels)), labels, fontsize=9.5)
    ax.set_ylabel("Размер модели, МБ", fontsize=10)
    ax.set_title("Кандидаты языковой модели: что влезает в телефон\n"
                 "оценки размера Q4 · пунктир — измеренный пик памяти",
                 fontsize=12.5, pad=14)
    clean_axes(ax)
    for b, v in zip(bars, vals):
        ax.text(b.get_x() + b.get_width() / 2, v + 45, f"{v} МБ",
                ha="center", va="bottom", fontsize=10, fontweight="normal")
    if peak:
        ax.axhline(peak, color=BLUE, linestyle="--", linewidth=1.2, zorder=3)
        ax.text(0.995, peak, f"пик ОЗУ сквозного прогона — {int(peak)} МБ",
                transform=ax.get_yaxis_transform(), ha="right", va="bottom",
                fontsize=9, color=BLUE)
    ax.set_ylim(0, max(vals + ([peak] if peak else [])) * 1.26)
    fig.tight_layout()
    fig.savefig(out, dpi=160, facecolor="white")
    plt.close(fig)
    print(f"ок: {out.name}")


def main():
    if not CSV_PATH.exists():
        sys.exit(f"нет файла {CSV_PATH}")
    IMG_DIR.mkdir(parents=True, exist_ok=True)
    rows = load_rows(CSV_PATH)
    if not rows:
        sys.exit("CSV пуст — замеров ещё нет, графики не строим")
    chart_wer(rows, IMG_DIR / "bench_wer.png")
    chart_rtf(rows, IMG_DIR / "bench_rtf.png")
    chart_pareto(rows, IMG_DIR / "bench_pareto.png")
    chart_der(IMG_DIR / "bench_der.png")
    chart_llm_role(rows, IMG_DIR / "bench_llm_role.png")
    chart_llm_size(rows, IMG_DIR / "bench_llm_size.png")


if __name__ == "__main__":
    main()