"""MVP «Нейро-стенографист»: аудио/текст -> транскрипт -> протокол -> отчёт.

Один скрипт, полный цикл:
  py process_meeting.py audio.mp3          # аудио
  py process_meeting.py transcript.txt     # текст
  py process_meeting.py transcript.txt --no-clean   # без очистки ASR

Результат: minutes.json + minutes.md рядом с входным файлом.
"""
import argparse
import json
import sys
from pathlib import Path

import pipeline

AUDIO_EXTS = {".mp3", ".mp4", ".m4a", ".wav", ".ogg", ".flac", ".webm"}


def load_transcript(path: Path) -> str:
    """Аудио — транскрибируем, .txt — читаем."""
    if path.suffix.lower() in AUDIO_EXTS:
        print(f"[1/4] Транскрибация аудио: {path.name} ...")
        return pipeline.transcribe_audio(str(path))
    print(f"[1/4] Читаю транскрипт: {path.name}")
    return path.read_text(encoding="utf-8")


def to_markdown(m: dict) -> str:
    """Протокол -> читаемый markdown."""
    lines = [f"# {m.get('title', 'Протокол встречи')}", ""]
    if m.get("summary"):
        lines += ["## Резюме", m["summary"], ""]
    if m.get("topics"):
        lines.append("## Темы")
        for t in m["topics"]:
            lines.append(f"- **{t['name']}** [{t.get('start', '?')}]")
            for p in t.get("key_points", []):
                lines.append(f"  - {p}")
        lines.append("")
    if m.get("decisions"):
        lines.append("## Решения")
        for d in m["decisions"]:
            lines.append(f"- {d['decision']} [{d.get('timestamp', '?')}]")
        lines.append("")
    if m.get("action_items"):
        lines.append("## Поручения")
        for a in m["action_items"]:
            owner = a.get("owner") or "—"
            deadline = a.get("deadline") or "—"
            lines.append(f"- [ ] {a['task']} | {owner} | {deadline} [{a.get('timestamp', '?')}]")
        lines.append("")
    if m.get("open_questions"):
        lines.append("## Открытые вопросы")
        for q in m["open_questions"]:
            lines.append(f"- {q['question']} [{q.get('timestamp', '?')}]")
        lines.append("")
    return "\n".join(lines)


def main() -> None:
    ap = argparse.ArgumentParser(description="Нейро-стенографист: аудио -> протокол")
    ap.add_argument("source", help="Аудиофайл или .txt с транскриптом")
    ap.add_argument("--no-clean", action="store_true", help="Пропустить очистку ASR")
    ap.add_argument("--context", default="", help="Тема встречи, участники")
    args = ap.parse_args()

    src = Path(args.source)
    if not src.exists():
        sys.exit(f"Файл не найден: {src}")

    # 1. Транскрипт
    transcript = load_transcript(src)
    transcript_path = src.with_suffix(".transcript.txt")
    transcript_path.write_text(transcript, encoding="utf-8")
    print(f"      -> {transcript_path.name}")

    # 2. Очистка ASR
    if not args.no_clean:
        print("[2/4] Очистка ASR ...")
        transcript = pipeline.clean_transcript(transcript)
        clean_path = src.with_suffix(".clean.txt")
        clean_path.write_text(transcript, encoding="utf-8")
        print(f"      -> {clean_path.name}")
    else:
        print("[2/4] Очистка пропущена")

    # 3. Протокол
    print("[3/4] Составляю протокол ...")
    minutes = pipeline.make_minutes(transcript, args.context)

    # 4. Спикеры
    print("[4/4] Определяю спикеров ...")
    speakers = pipeline.detect_speakers(transcript)
    minutes["speakers"] = speakers

    # Сохраняем
    json_path = src.with_suffix(".minutes.json")
    md_path = src.with_suffix(".minutes.md")
    json_path.write_text(json.dumps(minutes, ensure_ascii=False, indent=2), encoding="utf-8")
    md_path.write_text(to_markdown(minutes), encoding="utf-8")
    print(f"      -> {json_path.name}")
    print(f"      -> {md_path.name}")
    print("\nГотово.")


if __name__ == "__main__":
    main()
