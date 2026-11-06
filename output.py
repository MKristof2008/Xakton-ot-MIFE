"""Финальная сборка результата в JSON по схеме HEX.

Выходной формат (строго по ТЗ):
{
  "transcript": [
    {"speaker_id": "speaker_1", "start_ms": 1250, "end_ms": 4380, "text": "..."}
  ]
}
"""

from __future__ import annotations

import json
import re

from prompts import to_hex_format

# Допуск на округление при проверке, что сегмент не выходит за конец записи.
END_TOLERANCE_MS = 250


def merge_adjacent(segments: list[dict], max_gap_ms: int = 300,
                   max_len_ms: int = 15000) -> list[dict]:
    """Склеивает подряд идущие сегменты одного спикера с маленькой паузой."""
    if not segments:
        return []
    merged = [dict(segments[0])]
    for seg in segments[1:]:
        last = merged[-1]
        same_speaker = seg.get("speaker_id") == last.get("speaker_id")
        gap = seg["start_ms"] - last["end_ms"]
        new_len = seg["end_ms"] - last["start_ms"]
        if same_speaker and 0 <= gap <= max_gap_ms and new_len <= max_len_ms:
            last["end_ms"] = seg["end_ms"]
            last["text"] = (last["text"].rstrip() + " " + seg["text"].lstrip()).strip()
        else:
            merged.append(dict(seg))
    return merged


def normalize_speakers(segments: list[dict]) -> list[dict]:
    """Приводит speaker_id к виду speaker_1, speaker_2, ... в порядке появления."""
    mapping: dict[str, str] = {}
    for seg in segments:
        raw = str(seg.get("speaker_id") or "unknown")
        if raw not in mapping:
            mapping[raw] = f"speaker_{len(mapping) + 1}"
        seg["speaker_id"] = mapping[raw]
    return segments


def validate_hex(data: dict, duration_ms: int | None = None) -> list[str]:
    """Проверяет JSON на соответствие HEX-схеме. Возвращает список ошибок (пусто = ок).

    duration_ms — длительность аудио. Если известна, проверяется и физика:
    сегмент не может начинаться или кончаться за пределами записи. Схемная
    проверка такой случай пропускала, и галлюцинация Whisper («Thank you for
    watching.» на 123-127с при 123.8с аудио) доходила до финального JSON.
    """
    errors = []
    if not isinstance(data, dict) or "transcript" not in data:
        return ["нет ключа 'transcript'"]
    if not isinstance(data["transcript"], list):
        return ["'transcript' не список"]
    for i, seg in enumerate(data["transcript"]):
        for key in ("speaker_id", "start_ms", "end_ms", "text"):
            if key not in seg:
                errors.append(f"сегмент {i}: нет поля '{key}'")
        if not re.fullmatch(r"speaker_\d+", str(seg.get("speaker_id", ""))):
            errors.append(f"сегмент {i}: speaker_id '{seg.get('speaker_id')}' не по шаблону speaker_N")
        if not isinstance(seg.get("start_ms"), int) or not isinstance(seg.get("end_ms"), int):
            errors.append(f"сегмент {i}: start_ms/end_ms должны быть int")
        elif seg["end_ms"] <= seg["start_ms"]:
            errors.append(f"сегмент {i}: end_ms <= start_ms")
        elif duration_ms is not None:
            if seg["start_ms"] < 0:
                errors.append(f"сегмент {i}: start_ms отрицательный")
            if seg["end_ms"] > duration_ms + END_TOLERANCE_MS:
                errors.append(
                    f"сегмент {i}: кончается на {seg['end_ms']} мс, "
                    f"а запись длится {duration_ms} мс"
                )
    return errors


def build_result(segments: list[dict], duration_ms: int | None = None,
                 drop_out_of_bounds: bool = True) -> dict:
    """Полный пайплайн постобработки: нормализация спикеров -> склейка -> HEX-JSON.

    duration_ms — длительность аудио; если задана, сегменты за её пределами
    отбрасываются как галлюцинации (drop_out_of_bounds=True).
    """
    if duration_ms is not None and drop_out_of_bounds:
        segments = [
            s for s in segments
            if s.get("end_ms", 0) > 0 and s.get("start_ms", 0) < duration_ms
            and s.get("end_ms", 0) <= duration_ms + END_TOLERANCE_MS
        ]
    segments = normalize_speakers(segments)
    segments = merge_adjacent(segments)
    return to_hex_format(segments)


def save_result(data: dict, path: str, duration_ms: int | None = None) -> None:
    """Сохраняет JSON с валидацией. Падает с ошибкой, если схема нарушена."""
    errors = validate_hex(data, duration_ms)
    if errors:
        raise ValueError("HEX-JSON невалиден:\n" + "\n".join(errors))
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    demo = [
        {"speaker_id": "SPEAKER_00", "start_ms": 0, "end_ms": 2000, "text": "Привет всем."},
        {"speaker_id": "SPEAKER_00", "start_ms": 2100, "end_ms": 4000, "text": "Начнём встречу."},
        {"speaker_id": "SPEAKER_01", "start_ms": 4500, "end_ms": 6000, "text": "Давайте начнём."},
    ]
    result = build_result(demo)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    errors = validate_hex(result)
    print("валидация:", errors or "OK")
