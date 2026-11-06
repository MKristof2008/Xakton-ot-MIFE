"""Строит графики ML-метрик в docs/img/ из РЕАЛЬНЫХ измерений репозитория.

Цифры берутся из docs/ML_MODELS.md и docs/TEST_RESULTS.md (см. источники
в комментариях). Ничего не выдумываем: где нет измерения — столбец не рисуем.
Запуск: py -3 tools/make_ml_charts.py
"""

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

IMG = "docs/img"

# --- Реальные измерения (источники в docs/ML_MODELS.md) ---

# WER моделей Whisper на русском тест-наборе (ПК, CPU, faster-whisper int8).
# tiny: 20 записей VoxForge. small: 20 записей (0.161) и диалог 79.8 с (0.1443).
WER_MODELS = {
    "Whisper tiny\n(75 МБ)": 0.345,
    "Whisper small\n(480 МБ)": 0.161,
}

# A/B-замер роли LLM на dialog_2spk.wav (79.8 с, whisper small int8).
WER_LLM = {
    "ASR без LLM": 0.1443,
    "ASR + LLM\n(полная правка)": 0.1546,
    "ASR + LLM\n(только пунктуация)": 0.1443,
}

# RTF whisper small int8 на ПК (CPU, 5 записей): 0.81.
# RTF на телефоне: TODO — появится после замера logcat (тег WhisperJNI).
RTF_PC = 0.81


def bar_chart(values, title, ylabel, path, color="#4c72b0", target=None, target_label=None):
    fig, ax = plt.subplots(figsize=(6, 4))
    names = list(values.keys())
    ys = list(values.values())
    bars = ax.bar(names, ys, color=color)
    ax.set_title(title)
    ax.set_ylabel(ylabel)
    for b, y in zip(bars, ys):
        ax.text(b.get_x() + b.get_width() / 2, y + max(ys) * 0.02, f"{y:g}",
                ha="center", fontsize=10)
    if target is not None:
        ax.axhline(target, color="red", linestyle="--", linewidth=1)
        ax.text(0.99, target, target_label or f"цель {target:g}",
                transform=ax.get_yaxis_transform(), ha="right", va="bottom", color="red")
    ax.set_ylim(0, max(ys) * 1.25 if target is None else max(max(ys), target) * 1.25)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)
    print("записан", path)


def main():
    import os
    os.makedirs(IMG, exist_ok=True)

    bar_chart(
        WER_MODELS,
        "WER моделей Whisper (русская речь, ниже = лучше)",
        "WER",
        f"{IMG}/wer_models.png",
        target=0.15,
        target_label="цель < 0.15",
    )
    bar_chart(
        WER_LLM,
        "Роль LLM в пайплайне: A/B-замер WER (dialog_2spk.wav, 79.8 с)",
        "WER",
        f"{IMG}/wer_llm_ab.png",
        color="#55a868",
    )
    bar_chart(
        {"ПК (CPU)\nwhisper small int8": RTF_PC},
        "Скорость распознавания RTF (ниже = быстрее)",
        "RTF",
        f"{IMG}/rtf.png",
        color="#c44e52",
        target=1.0,
        target_label="цель RTF < 1",
    )


if __name__ == "__main__":
    main()
