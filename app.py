"""CLI-пайплайн: аудио/транскрипт -> протокол / ответы на вопросы.

Использование:
  py app.py transcribe audio.mp3            # аудио -> транскрипт со спикерами
  py app.py diarize audio.mp3               # только диаризация (кто когда)
  py app.py minutes audio.mp3               # аудио -> протокол
  py app.py minutes transcript.txt          # транскрипт -> протокол
  py app.py clean transcript.txt            # только очистка ASR
  py app.py ask transcript.txt "Что решили про релиз?"
  py app.py speakers transcript.txt         # реальные имена спикеров
  py app.py tasks document.txt              # извлечь кейсы/задачи из документа

Локальные шаги (transcribe, diarize) не требуют AITUNNEL_API_KEY.
"""
import argparse
import json
import sys
from pathlib import Path

import audio_utils

AUDIO_EXTS = audio_utils.AUDIO_EXTS


def _load_pipeline():
    """Импорт LLM-части только когда она реально нужна."""
    import pipeline

    return pipeline


def _read_text(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def _diarize_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("source", help="Аудиофайл")
    parser.add_argument("-k", "--num-speakers", type=int,
                        help="Число спикеров; по умолчанию определяется автоматически")
    parser.add_argument("--max-speakers", type=int, default=8,
                        help="Верхняя граница при автоопределении (по умолчанию 8)")
    parser.add_argument("--embedding", choices=["ecapa", "mfcc"],
                        help="Эмбеддинги говорящего: ecapa точнее, mfcc без torch")
    parser.add_argument("--asr-backend", choices=["local", "aitunnel"], default="local",
                        help="Бэкенд распознавания речи (по умолчанию local)")
    parser.add_argument("--model", help="Размер модели whisper для local (tiny/base/small/medium)")
    parser.add_argument("--language", help="Язык записи, например ru или en")
    parser.add_argument("-o", "--out", help="Куда сохранить результат")


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        description="Аудио -> транскрипт со спикерами -> протокол (ASR + диаризация)"
    )
    sub = ap.add_subparsers(dest="cmd", required=True)

    t = sub.add_parser("transcribe", help="Аудио -> транскрипт со спикерами")
    _diarize_args(t)
    t.add_argument("--no-diarization", action="store_true",
                   help="Только распознавание, без разделения по спикерам")
    t.add_argument("--json", action="store_true", help="Вывести JSON вместо текста")

    d = sub.add_parser("diarize", help="Только диаризация: кто говорил когда")
    _diarize_args(d)

    m = sub.add_parser("minutes", help="Составить протокол")
    m.add_argument("source", help="Аудиофайл или .txt с транскриптом")
    m.add_argument("--context", default="", help="Тема встречи, участники")
    m.add_argument("--no-clean", action="store_true", help="Пропустить очистку ASR")
    m.add_argument("-o", "--out", help="Куда сохранить JSON (по умолчанию stdout)")
    m.add_argument("--save-transcript", help="Сохранить транскрипт в файл")
    m.add_argument("-k", "--num-speakers", type=int, help="Число спикеров в записи")
    m.add_argument("--embedding", choices=["ecapa", "mfcc"])
    m.add_argument("--asr-backend", choices=["local", "aitunnel"], default="local")
    m.add_argument("--model", help="Размер модели whisper для local")
    m.add_argument("--language", help="Язык записи")

    c = sub.add_parser("clean", help="Очистить транскрипт от ошибок ASR")
    c.add_argument("source", help=".txt с транскриптом")
    c.add_argument("-o", "--out", help="Куда сохранить (по умолчанию stdout)")

    q = sub.add_parser("ask", help="Вопрос к записи")
    q.add_argument("source", help=".txt с транскриптом")
    q.add_argument("question", help="Вопрос по содержанию записи")

    s = sub.add_parser("speakers", help="Определить реальные имена спикеров")
    s.add_argument("source", help=".txt с транскриптом")

    k = sub.add_parser("tasks", help="Извлечь кейсы/задачи из документа")
    k.add_argument("source", help=".txt с текстом документа")
    k.add_argument("-o", "--out", help="Куда сохранить JSON (по умолчанию stdout)")

    return ap


def _emit(result: str, out: str | None) -> None:
    if out:
        Path(out).write_text(result, encoding="utf-8")
        print(f"-> {out}")
    else:
        print(result)


def cmd_transcribe(args: argparse.Namespace) -> None:
    import speecher as asr

    transcript, result = asr.run(
        args.source,
        backend=args.asr_backend,
        num_speakers=args.num_speakers,
        max_speakers=args.max_speakers,
        language=args.language,
        model=args.model,
        diarization=not args.no_diarization,
        embedding=args.embedding,
    )
    if args.json:
        payload = transcript.as_dict()
        if result is not None:
            payload["diarization"] = result.as_dict()
        _emit(json.dumps(payload, ensure_ascii=False, indent=2), args.out)
        return
    _emit(transcript.as_text(with_speaker=not args.no_diarization), args.out)

    speakers = len(transcript.speakers)
    status = f"спикеров: {speakers}" if speakers else "без диаризации"
    print(f"\n[{status}, backend {transcript.backend}/{transcript.model}]", file=sys.stderr)


def cmd_diarize(args: argparse.Namespace) -> None:
    import diarize

    result = diarize.diarize(
        args.source,
        num_speakers=args.num_speakers,
        max_speakers=args.max_speakers,
        backend=args.embedding,
    )
    payload = result.as_dict()
    payload["rttm"] = result.to_rttm(Path(args.source).stem)
    _emit(json.dumps(payload, ensure_ascii=False, indent=2), args.out)
    print(f"\n[спикеров: {result.num_speakers}, backend {result.backend}]", file=sys.stderr)


def cmd_minutes(args: argparse.Namespace) -> None:
    pipeline = _load_pipeline()
    path = Path(args.source)
    if audio_utils.is_audio(path):
        transcript = pipeline.transcribe_audio(
            str(path),
            model=args.model,
            backend=args.asr_backend,
            num_speakers=args.num_speakers,
            embedding=args.embedding,
            language=args.language,
        )
    else:
        transcript = path.read_text(encoding="utf-8")

    if args.save_transcript:
        Path(args.save_transcript).write_text(transcript, encoding="utf-8")
    if not args.no_clean:
        transcript = pipeline.clean_transcript(transcript)
    minutes = pipeline.make_minutes(transcript, args.context)
    _emit(json.dumps(minutes, ensure_ascii=False, indent=2), args.out)


def main() -> None:
    args = build_parser().parse_args()

    if args.cmd == "transcribe":
        cmd_transcribe(args)
        return
    if args.cmd == "diarize":
        cmd_diarize(args)
        return
    if args.cmd == "minutes":
        cmd_minutes(args)
        return

    pipeline = _load_pipeline()
    if args.cmd == "clean":
        result = pipeline.clean_transcript(_read_text(args.source))
    elif args.cmd == "speakers":
        result = json.dumps(pipeline.detect_speakers(_read_text(args.source)),
                            ensure_ascii=False, indent=2)
    elif args.cmd == "tasks":
        result = json.dumps(pipeline.extract_tasks(_read_text(args.source)),
                            ensure_ascii=False, indent=2)
    else:
        result = pipeline.ask(args.question, [_read_text(args.source)])

    _emit(result, getattr(args, "out", None))


if __name__ == "__main__":
    sys.exit(main())