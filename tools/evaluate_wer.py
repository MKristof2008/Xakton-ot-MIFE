"""Оценка WER на тестовом наборе VoxForge RU (artifacts/test-audio/).

Эталон: файлы etc/PROMPTS. Сравниваем с выходом asr.transcribe.
Запуск: py evaluate_wer.py [model_size]
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

AUDIO_ROOT = Path(__file__).parent / "artifacts" / "test-audio"


def load_cases(root: Path) -> list[tuple[Path, str]]:
    """Пары (wav, эталонный текст) из всех подпапок с etc/PROMPTS."""
    cases = []
    for prompts_file in root.glob("*/etc/PROMPTS"):
        speaker_dir = prompts_file.parent.parent
        for line in prompts_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            m = re.match(r"\S+/(\S+)\s+(.*)", line)
            if not m:
                continue
            wav_name, text = m.group(1), m.group(2)
            wav_path = speaker_dir / "wav" / f"{wav_name}.wav"
            if wav_path.exists():
                cases.append((wav_path, text))
    return cases


def main() -> None:
    model_size = sys.argv[1] if len(sys.argv) > 1 else "base"
    cases = load_cases(AUDIO_ROOT)
    if not cases:
        print("Нет тестовых данных в", AUDIO_ROOT)
        sys.exit(1)
    print(f"Набор: {len(cases)} записей, модель whisper: {model_size}")

    from asr import transcribe
    from metrics import wer

    total_wer = 0.0
    for wav_path, reference in cases:
        segments = transcribe(str(wav_path), model_size=model_size)
        hypothesis = " ".join(s["text"] for s in segments)
        score = wer(reference, hypothesis)
        total_wer += score
        status = "OK " if score < 0.2 else "BAD"
        print(f"[{status}] WER={score:.3f}  {wav_path.name}")
        if score >= 0.2:
            print(f"      эталон: {reference[:80]}")
            print(f"      выход:  {hypothesis[:80]}")

    avg = total_wer / len(cases)
    print(f"\n=== Средний WER: {avg:.3f} (цель: чем меньше, тем лучше; <0.15 — хорошо) ===")


if __name__ == "__main__":
    main()
