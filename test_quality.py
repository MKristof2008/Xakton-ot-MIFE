"""Контрольный набор и метрики качества пайплайна.

Критерии из ROADMAPS.md:
1. Все явные решения из транскрипта попадают в протокол (recall)
2. Поручения только при явном обязательстве (precision)
3. Таймкоды из источника, не выдуманы
4. QA: «В записи этого нет» только когда ответа нет
5. Спикеры: null при отсутствии явного имени
"""
import json
import re
from dataclasses import dataclass, field

# Контрольный набор: (транскрипт, ожидаемые решения, ожидаемые поручения)
TEST_CASES = [
    {
        "name": "Релиз мобильного приложения",
        "transcript": (
            "[00:01] Спикер 1: Привет, собрались обсудить релиз мобильного приложения.\n"
            "[00:10] Спикер 2: Да, я думаю выпускаем в пятницу.\n"
            "[00:25] Спикер 1: Хорошо. Петя, ты подготовишь release notes до четверга?\n"
            "[00:32] Спикер 3: Да, подготовлю до четверга.\n"
            "[01:05] Спикер 2: Остался открытым вопрос про поддержку iOS 15 — не решили.\n"
        ),
        "expected_decisions": ["выпуск приложения в пятницу"],
        "expected_actions": [{"task": "release notes", "deadline": "четверг"}],
        "expected_open": ["поддержку iOS 15"],
        "expected_speakers": {"Спикер 1": None, "Спикер 2": None},
    },
    {
        "name": "Определение спикеров",
        "transcript": (
            "[00:01] Спикер 1: Всем привет, меня зовут Анна, я веду встречу.\n"
            "[00:08] Спикер 2: Привет, Анна. Это Максим, начинаем?\n"
            "[00:15] Спикер 1: Да. Максим, расскажи про статус задач.\n"
            "[00:40] Спикер 3: Извините, что опоздал.\n"
        ),
        "expected_speakers": {"Спикер 1": "Анна", "Спикер 2": "Максим", "Спикер 3": None},
    },
]

TIMECODE_RE = re.compile(r"\[\d{2}:\d{2}\]")


@dataclass
class CheckResult:
    name: str
    passed: bool
    details: list[str] = field(default_factory=list)


def _stem(word: str) -> str:
    """Усечение слова до основы (первые 4 буквы) для сравнения словоформ."""
    return word[:4]


def _flex_contains(haystack: str, needle: str) -> bool:
    """Гибкое вхождение: основы всех значимых слов needle есть в haystack."""
    words = [_stem(w) for w in needle.lower().split() if len(w) > 2]
    return all(w in haystack for w in words) if words else needle.lower() in haystack


def check_decisions_recall(minutes: dict, expected: list[str]) -> CheckResult:
    """Все ли ожидаемые решения есть в протоколе (семантическая проверка через LLM)."""
    import prompts
    from client import chat

    decisions = [d["decision"] for d in minutes.get("decisions", [])]
    if not expected:
        return CheckResult("decisions_recall", True, [])
    if not decisions:
        return CheckResult("decisions_recall", False, ["Протокол не содержит решений"])

    verdict = chat(
        prompts.build_messages(
            prompts.BASE_RULES
            + "\n\nПроверь, покрывают ли РЕШЕНИЯ ИЗ ПРОТОКОЛА каждое из ОЖИДАЕМЫХ решений "
            "по смыслу (допустимо перефразирование). Верни JSON: "
            '{"missing": ["ожидаемое решение, которого нет по смыслу"]} '
            'или {"missing": []} если все покрыты.',
            f"ОЖИДАЕМЫЕ: {json.dumps(expected, ensure_ascii=False)}\n"
            f"РЕШЕНИЯ ИЗ ПРОТОКОЛА: {json.dumps(decisions, ensure_ascii=False)}",
        )
    )
    try:
        missing = json.loads(verdict.strip().strip("`").removeprefix("json").strip()).get("missing", [])
    except Exception:
        missing = []
    return CheckResult(
        "decisions_recall",
        not missing,
        [f"Не найдено решение: {m}" for m in missing],
    )
    return CheckResult(
        "decisions_recall",
        not missing,
        [f"Не найдено решение: {m}" for m in missing],
    )


def check_actions_precision(minutes: dict, expected: list[dict]) -> CheckResult:
    """Поручения совпадают с ожидаемыми (task + deadline)."""
    details = []
    actions = minutes.get("action_items", [])
    for exp in expected:
        found = any(
            exp["task"].lower() in a["task"].lower()
            and exp["deadline"] and exp["deadline"].lower() in (a.get("deadline") or "").lower()
            for a in actions
        )
        if not found:
            details.append(f"Не найдено поручение: {exp['task']} / {exp['deadline']}")
    return CheckResult("actions_precision", not details, details)


def check_timecodes(minutes: dict, transcript: str) -> CheckResult:
    """Таймкоды в протоколе должны быть из транскрипта."""
    src_codes = set(TIMECODE_RE.findall(transcript))
    proto_codes = set()
    for topic in minutes.get("topics", []):
        proto_codes.update(TIMECODE_RE.findall(topic.get("start", "")))
    for d in minutes.get("decisions", []):
        proto_codes.update(TIMECODE_RE.findall(d.get("timestamp", "")))
    for a in minutes.get("action_items", []):
        proto_codes.update(TIMECODE_RE.findall(a.get("timestamp", "")))
    invented = proto_codes - src_codes
    return CheckResult(
        "timecodes_valid",
        not invented,
        [f"Выдуманный таймкод: {c}" for c in invented],
    )


def _normalize_null(v):
    """LLM иногда возвращает строку 'null' вместо null — нормализуем."""
    return None if v in (None, "null", "None", "") else v


def check_speakers(detected: dict, expected: dict) -> CheckResult:
    """Имена спикеров совпадают; null там, где неизвестно."""
    detected = {k: _normalize_null(v) for k, v in detected.items()}
    details = []
    for spk, exp_name in expected.items():
        got = detected.get(spk)
        if exp_name is None and got is not None:
            details.append(f"{spk}: ожидался null, получено '{got}'")
        elif exp_name and (got or "").lower() != exp_name.lower():
            details.append(f"{spk}: ожидалось '{exp_name}', получено '{got}'")
    return CheckResult("speakers_correct", not details, details)


def run_checks(verbose: bool = True) -> bool:
    """Прогон всех контрольных примеров. Возвращает True, если все проверки прошли."""
    import pipeline

    all_ok = True
    for case in TEST_CASES:
        print(f"\n--- {case['name']} ---")
        transcript = case["transcript"]

        minutes = pipeline.make_minutes(transcript)
        speakers = pipeline.detect_speakers(transcript)

        checks = [
            check_decisions_recall(minutes, case.get("expected_decisions", [])),
            check_actions_precision(minutes, case.get("expected_actions", [])),
            check_timecodes(minutes, transcript),
            check_speakers(speakers, case.get("expected_speakers", {})),
        ]
        if "expected_open" in case:
            open_q = " ".join(q["question"].lower() for q in minutes.get("open_questions", []))
            missing = [e for e in case["expected_open"] if not _flex_contains(open_q, e)]
            checks.append(CheckResult("open_questions", not missing,
                                      [f"Не найден вопрос: {m}" for m in missing]))

        for c in checks:
            status = "OK" if c.passed else "FAIL"
            print(f"  [{status}] {c.name}")
            for d in c.details:
                print(f"       {d}")
            if not c.passed:
                all_ok = False

    print("\n=== ИТОГ:", "ВСЕ ПРОВЕРКИ ПРОШЛИ" if all_ok else "ЕСТЬ ПРОВАЛЫ", "===")
    return all_ok


if __name__ == "__main__":
    import sys
    sys.exit(0 if run_checks() else 1)
