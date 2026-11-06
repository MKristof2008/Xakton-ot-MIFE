"""Полный локальный пайплайн по ТЗ HEX: аудио -> ASR -> диаризация -> JSON.

Использование:
    py run_local.py meeting.mp3 [--model base] [--speakers 3] [-o result.json]

Всё работает локально, без облачных API (после первой загрузки моделей).
"""

from __future__ import annotations

import argparse
import json
import sys
import time


def main() -> None:
    parser = argparse.ArgumentParser(description="Локальный нейро-стенографист")
    parser.add_argument("audio", help="путь к аудиофайлу (mp3/wav/m4a)")
    parser.add_argument("--model", default="base", help="размер whisper-модели (tiny/base/small)")
    parser.add_argument("--speakers", type=int, default=None, help="число спикеров (если известно)")
    parser.add_argument("--skip-diarization", action="store_true", help="пропустить диаризацию")
    parser.add_argument("-o", "--output", default=None, help="куда сохранить JSON")
    args = parser.parse_args()

    from metrics import RtfTimer, audio_duration_sec

    timer = RtfTimer()

    # 1. ASR
    print("[1/3] Распознавание речи...", file=sys.stderr)
    from asr import transcribe
    segments = transcribe(args.audio, model_size=args.model)
    print(f"      {len(segments)} сегментов", file=sys.stderr)

    # 2. Диаризация
    if args.skip_diarization:
        for seg in segments:
            seg["speaker_id"] = "speaker_1"
    else:
        print("[2/3] Диаризация...", file=sys.stderr)
        from diarization import diarize
        segments = diarize(args.audio, segments, num_speakers=args.speakers)

    # 3. Сборка HEX-JSON
    print("[3/3] Сборка результата...", file=sys.stderr)
    from output import build_result, save_result, validate_hex
    result = build_result(segments)

    out_path = args.output or args.audio.rsplit(".", 1)[0] + ".hex.json"
    save_result(result, out_path)

    rtf = timer.rtf(audio_duration_sec(args.audio)) if args.audio.endswith(".wav") else None
    print(f"Готово: {out_path}", file=sys.stderr)
    if rtf is not None:
        print(f"RTF: {rtf:.2f}", file=sys.stderr)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
