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
from pathlib import Path


def _force_utf8_console() -> None:
    """Windows-консоль по умолчанию cp1251 — печать русского JSON падает.

    Переключаем stdout/stderr на UTF-8 и не падаем на непечатаемых символах:
    вывод в консоль не должен ломать уже готовый результат.

    Функция идемпотентна: повторный вызов ничего не делает. Иначе обёртка
    накладывалась бы на обёртку и ломала перехват вывода (pytest, subprocess).
    """
    for name in ("stdout", "stderr"):
        stream = getattr(sys, name, None)
        buffer = getattr(stream, "buffer", None)
        if buffer is None:
            continue
        encoding = (getattr(stream, "encoding", "") or "").lower()
        if encoding.replace("-", "") == "utf8":
            continue  # уже UTF-8, второй раз оборачивать нельзя
        try:
            setattr(sys, name, io.TextIOWrapper(buffer, encoding="utf-8", errors="replace"))
        except Exception:
            continue  # поток уже закрыт или заменён — не критично для работы


def main() -> None:
    _force_utf8_console()
    parser = argparse.ArgumentParser(description="Локальный нейро-стенографист")
    parser.add_argument("audio", help="путь к аудиофайлу (mp3/wav/m4a)")
    parser.add_argument("--model", default="base", help="размер whisper-модели (tiny/base/small)")
    parser.add_argument("--speakers", type=int, default=None, help="число спикеров (если известно)")
    parser.add_argument("--skip-diarization", action="store_true", help="пропустить диаризацию")
    parser.add_argument("--no-llm", action="store_true", help="пропустить постобработку текста")
    parser.add_argument("--language", default=None,
                        help="язык записи: ru, en и т.д. Без флага — автоопределение")
    parser.add_argument("-o", "--output", default=None, help="куда сохранить JSON")
    parser.add_argument("--denoise", action="store_true",
                        help="шумоподавление перед распознаванием (спектральное "
                             "вычитание + режекция гула). Помогает на шумных "
                             "записях, на чистых отключается автоматически")
    args = parser.parse_args()

    from metrics import RtfTimer, audio_duration_sec

    timer = RtfTimer()

    # 0. Шумоподавление (по флагу --denoise)
    audio_path = args.audio
    temp_wav = None
    if args.denoise:
        import audio_utils
        import denoise as dn

        source = audio_utils.decode_audio(args.audio)
        cleaned = dn.denoise(source)
        unchanged = (len(source) == len(cleaned)
                     and float(abs(source - cleaned).max()) < 1e-7)
        if unchanged:
            print(f"[0/4] Шумоподавление пропущено: SNR {dn.snr_estimate(source):.0f} дБ "
                  f"(запись чистая или шум слишком сильный)", file=sys.stderr)
        else:
            temp_wav = Path(args.audio).with_suffix(".denoised.wav")
            audio_utils.write_wav(temp_wav, cleaned)
            audio_path = str(temp_wav)
            print(f"[0/4] Шумоподавление: SNR {dn.snr_estimate(source):.1f} -> "
                  f"{dn.snr_estimate(cleaned):.1f} дБ -> {temp_wav.name}",
                  file=sys.stderr)

    # 2. ASR
    print("[1/4] Распознавание речи...", file=sys.stderr)
    from asr import transcribe
    segments = transcribe(audio_path, model_size=args.model, language=args.language)
    if args.language is None:
        print(f"      язык определён: {getattr(transcribe, 'last_language', '?')}",
              file=sys.stderr)
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
    from asr import audio_duration_sec as probe_duration

    # Важно: metrics.audio_duration_sec читает только WAV (модуль wave).
    # Для mp3/m4a он падает, и тогда duration_ms=None — то есть защита от
    # сегментов за концом записи молча отключается. Поэтому основной путь —
    # asr.audio_duration_sec, который работает через ffmpeg/PyAV.
    duration = None
    for probe in (probe_duration, audio_duration_sec):
        try:
            duration = probe(audio_path)
            if duration and duration > 0:
                break
        except Exception:
            duration = None
    duration_ms = int(duration * 1000) if duration else None
    if duration_ms is None:
        print("      ВНИМАНИЕ: длительность аудио не определена, "
              "проверка границ отключена", file=sys.stderr)

    result = build_result(segments, duration_ms=duration_ms)

    out_path = args.output or args.audio.rsplit(".", 1)[0] + ".hex.json"
    save_result(result, out_path, duration_ms=duration_ms)

    rtf = timer.rtf(duration) if duration else None
    print(f"Готово: {out_path}", file=sys.stderr)
    if rtf is not None:
        print(f"RTF: {rtf:.2f}", file=sys.stderr)
    print(json.dumps(result, ensure_ascii=False, indent=2))

    # Временный очищенный WAV не оставляем: он нужен был только для ASR
    if temp_wav is not None:
        try:
            temp_wav.unlink()
        except OSError:
            pass


if __name__ == "__main__":
    main()
