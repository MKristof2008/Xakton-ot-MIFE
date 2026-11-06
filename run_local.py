"""Полный локальный пайплайн по ТЗ HEX: аудио -> ASR -> диаризация -> JSON.

Использование:
    py run_local.py meeting.mp3 [--model base] [--speakers 3] [-o result.json]

Всё работает локально, без облачных API (после первой загрузки моделей).
"""

from __future__ import annotations

import argparse
import io
import json
import sys


def _force_utf8_console() -> None:
    """Windows-консоль по умолчанию cp1251 — печать русского JSON падает.

    Переключаем stdout/stderr на UTF-8 и не падаем на непечатаемых символах:
    вывод в консоль не должен ломать уже готовый результат.
    """
    for name in ("stdout", "stderr"):
        stream = getattr(sys, name, None)
        buffer = getattr(stream, "buffer", None)
        if buffer is None:
            continue
        setattr(sys, name, io.TextIOWrapper(buffer, encoding="utf-8", errors="replace"))


def main() -> None:
    _force_utf8_console()
    parser = argparse.ArgumentParser(description="Локальный нейро-стенографист")
    parser.add_argument("audio", help="путь к аудиофайлу (mp3/wav/m4a)")
    parser.add_argument("--model", default="base", help="размер whisper-модели (tiny/base/small)")
    parser.add_argument("--speakers", type=int, default=None, help="число спикеров (если известно)")
    parser.add_argument("--skip-diarization", action="store_true", help="пропустить диаризацию")
    parser.add_argument("--no-llm", action="store_true", help="пропустить постобработку текста")
    parser.add_argument("--language", default="ru", help="язык записи (по умолчанию ru)")
    parser.add_argument("-o", "--output", default=None, help="куда сохранить JSON")
    args = parser.parse_args()

    from metrics import RtfTimer, audio_duration_sec

    timer = RtfTimer()

    # 1. ASR
    print("[1/4] Распознавание речи...", file=sys.stderr)
    from asr import transcribe
    segments = transcribe(args.audio, model_size=args.model, language=args.language)
    print(f"      {len(segments)} сегментов", file=sys.stderr)

    # 2. Диаризация
    if args.skip_diarization:
        for seg in segments:
            seg["speaker_id"] = "speaker_1"
    else:
        print("[2/4] Диаризация...", file=sys.stderr)
        from diarization import diarize
        segments = diarize(args.audio, segments, num_speakers=args.speakers)

    # 3. Постобработка текста
    if not args.no_llm:
        print("[3/4] Постобработка текста...", file=sys.stderr)
        import llm as text_llm
        backend = text_llm.available_backend()
        print(f"      бэкенд: {backend}", file=sys.stderr)
        segments = text_llm.clean_segments(segments, backend)
    else:
        print("[3/4] Постобработка пропущена", file=sys.stderr)

    # 4. Сборка HEX-JSON
    print("[4/4] Сборка результата...", file=sys.stderr)
    from output import build_result, save_result
    from metrics import audio_duration_sec

    try:
        duration_ms = int(audio_duration_sec(args.audio) * 1000)
    except Exception:
        duration_ms = None
    result = build_result(segments, duration_ms=duration_ms)

    out_path = args.output or args.audio.rsplit(".", 1)[0] + ".hex.json"
    save_result(result, out_path, duration_ms=duration_ms)

    try:
        rtf = timer.rtf(audio_duration_sec(args.audio))
    except Exception:
        rtf = None  # не wav — длительность без ffmpeg не узнать
    print(f"Готово: {out_path}", file=sys.stderr)
    if rtf is not None:
        print(f"RTF: {rtf:.2f}", file=sys.stderr)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
