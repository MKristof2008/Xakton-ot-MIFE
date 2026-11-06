"""CLI-пайплайн: аудио/транскрипт -> протокол / ответы на вопросы.

Использование:
  py app.py minutes audio.mp3              # аудио -> протокол
  py app.py minutes transcript.txt         # транскрипт -> протокол
  py app.py clean transcript.txt           # только очистка ASR
  py app.py ask transcript.txt "Что решили про релиз?"
"""
import argparse
import json
import sys
from pathlib import Path

import pipeline

AUDIO_EXTS = {".mp3", ".mp4", ".m4a", ".wav", ".ogg", ".flac", ".webm"}


def load_transcript(path: str) -> str:
    """Путь к аудио — транскрибируем, к .txt — читаем."""
    p = Path(path)
    if p.suffix.lower() in AUDIO_EXTS:
        return pipeline.transcribe_audio(str(p))
    return p.read_text(encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser(description="Аудио -> транскрипт -> протокол (AITunnel)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    m = sub.add_parser("minutes", help="Составить протокол")
    m.add_argument("source", help="Аудиофайл или .txt с транскриптом")
    m.add_argument("--context", default="", help="Тема встречи, участники")
    m.add_argument("--no-clean", action="store_true", help="Пропустить очистку ASR")
    m.add_argument("-o", "--out", help="Куда сохранить JSON (по умолчанию stdout)")
    m.add_argument("--save-transcript", help="Сохранить транскрипт в файл")

    c = sub.add_parser("clean", help="Очистить транскрипт от ошибок ASR")
    c.add_argument("source", help=".txt с транскриптом")
    c.add_argument("-o", "--out", help="Куда сохранить (по умолчанию stdout)")

    q = sub.add_parser("ask", help="Вопрос к записи")
    q.add_argument("source", help=".txt с транскриптом")
    q.add_argument("question", help="Вопрос по содержанию записи")

    args = ap.parse_args()

    if args.cmd == "minutes":
        transcript = load_transcript(args.source)
        if args.save_transcript:
            Path(args.save_transcript).write_text(transcript, encoding="utf-8")
        if not args.no_clean:
            transcript = pipeline.clean_transcript(transcript)
        minutes = pipeline.make_minutes(transcript, args.context)
        result = json.dumps(minutes, ensure_ascii=False, indent=2)
    elif args.cmd == "clean":
        result = pipeline.clean_transcript(Path(args.source).read_text(encoding="utf-8"))
    else:
        transcript = Path(args.source).read_text(encoding="utf-8")
        result = pipeline.ask(args.question, [transcript])

    if getattr(args, "out", None):
        Path(args.out).write_text(result, encoding="utf-8")
    else:
        print(result)


if __name__ == "__main__":
    sys.exit(main())
