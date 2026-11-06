"""A/B сравнение версий промптов на контрольном наборе.

Критерий из ROADMAPS: версии сравниваются на одном контрольном наборе.
Использование:
  py compare_prompts.py                 # сравнить текущие промпты сами с собой (baseline)
"""
import json

import prompts
from client import chat, Stats
from test_quality import TEST_CASES, check_decisions_recall, check_actions_precision
import pipeline


def run_version(name: str, minutes_prompt: str) -> dict:
    """Прогон контрольного набора с заданным промптом протокола."""
    results = []
    for case in TEST_CASES:
        if "expected_decisions" not in case:
            continue
        raw = chat(prompts.build_messages(minutes_prompt, case["transcript"]))
        try:
            minutes = pipeline.json.loads(pipeline._strip_markdown(raw))
        except Exception:
            minutes = {}
        checks = [
            check_decisions_recall(minutes, case["expected_decisions"]),
            check_actions_precision(minutes, case["expected_actions"]),
        ]
        results.append({
            "case": case["name"],
            "passed": sum(c.passed for c in checks),
            "total": len(checks),
        })
    passed = sum(r["passed"] for r in results)
    total = sum(r["total"] for r in results)
    return {
        "version": name,
        "score": f"{passed}/{total}",
        "stats": Stats.summary(),
        "details": results,
    }


def main() -> None:
    # v1: текущий промпт
    v1 = run_version("v1 (current)", prompts.MINUTES_PROMPT)
    print(json.dumps(v1, ensure_ascii=False, indent=2))

    # Сюда добавляем новые версии промптов для сравнения:
    # v2 = run_version("v2 (new)", NEW_PROMPT)


if __name__ == "__main__":
    main()
