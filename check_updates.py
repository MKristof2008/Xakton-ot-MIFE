"""Проверка обновлений на GitHub: что нового, и не сломалось ли качество.

Задача — за один короткий прогон понять:
1. появились ли новые коммиты и какие файлы затронуты;
2. трогают ли они ASR/диаризацию (наши зоны ответственности);
3. не хуже ли их версия нашей: детерминированные тесты + DER на контроле.

Ничего не меняет и не делает merge — только докладывает.

Запуск:
    py check_updates.py            # отчёт человеку
    py check_updates.py --quiet    # одна строка, для cron
Код возврата: 0 — нового нет или всё в порядке, 1 — есть на что смотреть.
"""
from __future__ import annotations

import io
import json
import subprocess
import sys
from pathlib import Path

REMOTE = "origin"
BRANCH = "main"

# Файлы, за которые отвечаем мы; изменения в них требуют внимания.
WATCHED = {
    "asr.py", "speecher.py", "diarize.py", "diarization.py", "embeddings.py",
    "audio_utils.py", "output.py", "metrics.py", "run_local.py", "output.py",
    "process_meeting.py", "app.py", "pipeline.py",
}

DER_LIMIT = 0.15  # выше этого считаем, что качество диаризации деградировало


def _force_utf8() -> None:
    for name in ("stdout", "stderr"):
        stream = getattr(sys, name, None)
        if getattr(stream, "buffer", None) is not None:
            setattr(sys, name, io.TextIOWrapper(stream.buffer, encoding="utf-8", errors="replace"))


def _git(*args: str) -> str:
    proc = subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8",
                          errors="replace")
    return proc.stdout.strip() if proc.returncode == 0 else ""


def fetch() -> bool:
    proc = subprocess.run(["git", "fetch", "--quiet", REMOTE],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        print(f"git fetch не удался: {proc.stderr.strip()[:300]}", file=sys.stderr)
        return False
    return True


def _changed_files(base: str, head: str) -> set[str]:
    out = _git("diff", "--name-only", base, head)
    return {line.strip() for line in out.splitlines() if line.strip()}


def run_tests() -> tuple[bool, str]:
    proc = subprocess.run([sys.executable, "-m", "pytest", "tests", "-q"],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    tail = (proc.stdout or proc.stderr).strip().splitlines()
    return proc.returncode == 0, (tail[-1] if tail else "нет вывода")


def run_der() -> tuple[bool, str]:
    """DER на контрольном наборе — ловит регрессию в диаризации."""
    proc = subprocess.run([sys.executable, "evaluate_diarization.py"],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        tail = (proc.stdout or proc.stderr).strip().splitlines()
        return False, (tail[-1] if tail else "ошибка")

    worst = 0.0
    counts_ok = True
    for line in proc.stdout.splitlines():
        if "DER" not in line:
            continue
        try:
            worst = max(worst, float(line.split("DER")[1].split("|")[0].strip()))
        except (IndexError, ValueError):
            continue
        if "СПИКЕРЫ" in line:
            counts_ok = False
    return counts_ok and worst <= DER_LIMIT, f"макс DER {worst:.4f}"


def main() -> int:
    _force_utf8()
    quiet = "--quiet" in sys.argv

    if not fetch():
        return 1

    local = _git("rev-parse", "HEAD")
    remote = _git("rev-parse", f"{REMOTE}/{BRANCH}")
    if local == remote:
        if quiet:
            print("обновлений нет")
        else:
            print(f"Обновлений нет. Локально и на {REMOTE}/{BRANCH}: {local[:7]}")
        # даже без новых коммитов проверяем, что наше дерево не сломано
        ok_tests, tests_msg = run_tests()
        ok_der, der_msg = run_der()
        if not (ok_tests and ok_der):
            print(f"ВНИМАНИЕ: локальные проверки красные — тесты: {tests_msg}; {der_msg}")
            return 1
        return 0

    behind = _git("rev-list", "--count", f"HEAD..{REMOTE}/{BRANCH}")
    ahead = _git("rev-list", "--count", f"{REMOTE}/{BRANCH}..HEAD")
    commits = _git("log", "--oneline", f"HEAD..{REMOTE}/{BRANCH}").splitlines()
    files = _changed_files("HEAD", f"{REMOTE}/{BRANCH}")
    watched = sorted(files & WATCHED)

    print(f"Новых коммитов: {len(commits)} (мы впереди на {ahead or 0})")
    for line in commits[:10]:
        print(f"  {line}")
    if len(commits) > 10:
        print(f"  ... и ещё {len(commits) - 10}")

    if watched:
        print(f"\nЗатронуты наши файлы: {', '.join(watched)}")
    else:
        print("\nНаши файлы (ASR/диаризация) не затронуты.")

    ok_tests, tests_msg = run_tests()
    ok_der, der_msg = run_der()
    print(f"\nТесты: {'OK' if ok_tests else 'ПАДАЮТ'} — {tests_msg}")
    print(f"DER: {'OK' if ok_der else 'ПРОБЛЕМА'} — {der_msg}")

    needs_attention = bool(watched) or not ok_tests or not ok_der
    print("\nВЫВОД:", "нужно посмотреть и, возможно, поправить их правки"
          if needs_attention else "их обновления безопасны, можно подтягивать")
    return 1 if needs_attention else 0


if __name__ == "__main__":
    sys.exit(main())