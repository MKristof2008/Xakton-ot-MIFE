"""График подбора порога кластеризации диаризации.

Данные — реальные замеры на SM-S938B (ThresholdSweepTest, запись dialog_2spk,
эталон: 2 говорящих, 8 интервалов). Смысл графика: показать, что порог
выбран по цифрам, а не на глаз, и что плато 0.85–0.95 устойчиво.

Запуск: py -3 -I tools/make_diar_chart.py
"""
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "img" / "diar_threshold.png"

# Замеры ThresholdSweepTest (см. logcat / docs/benchmarks/measurements.csv).
# Порог, найденное число говорящих, DER.
MEASUREMENTS = [
    (0.30, 12, 0.815),
    (0.40, 9, 0.615),
    (0.45, 8, 0.554),
    (0.50, 5, 0.404),
    (0.55, 5, 0.404),
    (0.60, 5, 0.404),
    (0.70, 3, 0.167),
    (0.75, 3, 0.167),
    (0.80, 3, 0.167),
    (0.85, 2, 0.082),
    (0.90, 2, 0.082),
    (0.95, 2, 0.082),
]

TRUE_SPEAKERS = 2
CHOSEN = 0.90


def main() -> None:
    thresholds = [m[0] for m in MEASUREMENTS]
    ders = [m[2] for m in MEASUREMENTS]

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(8, 6), sharex=True,
                                   gridspec_kw={"height_ratios": [2, 1.2]})

    ax1.plot(thresholds, ders, marker="o", color="#4C72B0", label="DER")
    ax1.axvline(CHOSEN, color="#C44E52", linestyle="--", label=f"выбран порог {CHOSEN}")
    ax1.axhline(0.25, color="gray", linestyle=":", label="цель DER 0.25")
    ax1.set_ylabel("DER (ниже лучше)")
    ax1.set_title("Диаризация: подбор порога кластеризации (SM-S938B, dialog_2spk)")
    ax1.legend(fontsize=8)
    ax1.grid(alpha=0.3)

    ax2.step(thresholds, [m[1] for m in MEASUREMENTS], where="post",
             marker="s", color="#55A868", label="найдено говорящих")
    ax2.axhline(TRUE_SPEAKERS, color="black", linestyle="--",
                label=f"в записи {TRUE_SPEAKERS}")
    ax2.axvline(CHOSEN, color="#C44E52", linestyle="--")
    ax2.set_xlabel("порог кластеризации")
    ax2.set_ylabel("говорящих")
    ax2.legend(fontsize=8)
    ax2.grid(alpha=0.3)

    fig.tight_layout()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUT, dpi=150)
    plt.close(fig)
    print(f"ок: {OUT}")


if __name__ == "__main__":
    main()
