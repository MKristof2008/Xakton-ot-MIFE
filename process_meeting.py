"""MVP «Нейро-стенографист»: аудио/текст -> транскрипт -> протокол -> отчёт.

Один скрипт, полный цикл:
  py process_meeting.py audio.mp3          # аудио (ASR + диаризация)
  py process_meeting.py transcript.txt     # текст
  py process_meeting.py transcript.txt --no-clean   # без очистки ASR

Результат: minutes.json + minutes.md рядом с входным файлом,
для аудио ещё <имя>.transcript.json с репликами спикеров.
"""
import argparse
import json
import sys
from pathlib import Path

import audio_utils
import pipeline

AUDIO_EXTS = audio_utils.AUDIO_EXTS


def load_transcript(path: Path, args: argparse.Namespace) -> tuple[str, object]:
    """Аудио — распознаём с диаризацией, .txt — читаем. Возвращает (текст, диаризация)."""
    if path.suffix.lower() in AUDIO_EXTS:
        print(f"[1/4] Распознавание речи и диаризация: {path.name} ...")
        text, result = pipeline.transcribe_audio(
            str(path),
            model=args.model,
            backend=args.asr_backend,
            num_speakers=args.num_speakers,
            embedding=args.embedding,
            language=args.language,
            return_result=True,
        )
        if result is not None:
            print(f"      спикеров: {result.num_speakers} ({result.backend})")
        return text, result
    print(f"[1/4] Читаю транскрипт: {path.name}")
    return path.read_text(encoding="utf-8"), None


def transcript_speakers(transcript: str) -> list[str]:
    """Спикеры, реально присутствующие в транскрипте (в порядке появления)."""
    seen: list[str] = []
    for line in transcript.splitlines():
        rest = line.split("] ", 1)[-1]
        speaker, sep, _text = rest.partition(":")
        if sep and speaker and speaker not in seen:
            seen.append(speaker.strip())
    return seen


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
    ap.add_argument("-k", "--num-speakers", type=int,
                    help="Число спикеров; по умолчанию определяется автоматически")
    ap.add_argument("--embedding", choices=["ecapa", "mfcc"],
                    help="Эмбеддинги говорящего: ecapa точнее, mfcc без torch")
    ap.add_argument("--asr-backend", choices=["local", "aitunnel"], default="local",
                    help="Бэкенд распознавания речи (по умолчанию local)")
    ap.add_argument("--model", help="Размер модели whisper для local (tiny/base/small/medium)")
    ap.add_argument("--language", help="Язык записи, например ru или en")
    args = ap.parse_args()

    src = Path(args.source)
    if not src.exists():
        sys.exit(f"Файл не найден: {src}")

    # 1. Транскрипт (для аудио — со спикерами)
    transcript, diarization = load_transcript(src, args)
    transcript_path = src.with_suffix(".transcript.txt")
    transcript_path.write_text(transcript, encoding="utf-8")
    print(f"      -> {transcript_path.name}")

    if diarization is not None:
        diar_path = src.with_suffix(".diarization.json")
        diar_path.write_text(
            json.dumps(diarization.as_dict(), ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(f"      -> {diar_path.name}")

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

    # 4. Спикеры: имена поверх акустической диаризации
    print("[4/4] Определяю имена спикеров ...")
    speakers = pipeline.detect_speakers(transcript)
    if diarization is not None and speakers:
        # акустика уже разделила голоса; LLM только подставляет имена
        minutes["speaker_roles"] = speakers
        minutes["speakers"] = {spk: speakers.get(spk) for spk in transcript_speakers(transcript)}
    else:
        minutes["speakers"] = speakers

    # Сохраняем
    json_path = src.with_suffix(".minutes.json")
    md_path = src.with_suffix(".minutes.md")
    json_path.write_text(json.dumps(minutes, ensure_ascii=False, indent=2), encoding="utf-8")
    md_path.write_text(to_markdown(minutes), encoding="utf-8")
    print(f"      -> {json_path.name}")
    print(f"      -> {md_path.name}")

    from client import Stats
    print(f"\nСтатистика API: {Stats.summary()}")
    print("Готово.")


if __name__ == "__main__":
    main()
