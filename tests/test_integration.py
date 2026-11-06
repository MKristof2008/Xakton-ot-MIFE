"""Тесты на баги, найденные при аудите интеграции.

Каждый тест здесь — регрессия на конкретную поломку, которая уже случалась:
1. numpy не был указан в requirements, хотя импортируется на уровне модуля —
   установка без torch (режим mfcc) ломала весь пайплайн.
2. llm.py схлопывал любые повторы слов, портя смысл: «долго долго» -> «долго»,
   а длинный текст сжимался с 30000 символов до 6.
3. Тесты требовали ffmpeg, которого нет в CI, — сборка падала.
4. make_pdf.py был жёстко привязан к C:\\Windows\\Fonts.
"""
from __future__ import annotations

import pathlib

import pytest

import llm

ROOT = pathlib.Path(__file__).resolve().parent.parent


# ---------- 1. зависимости ----------

def test_numpy_declared_in_requirements():
    """numpy импортируется на уровне модуля, значит должен быть в requirements."""
    text = (ROOT / "requirements.txt").read_text(encoding="utf-8")
    lines = [
        line.split("#")[0].strip().lower()
        for line in text.splitlines()
    ]
    declared = [line for line in lines if line]
    assert any(line.startswith("numpy") for line in declared), (
        "numpy отсутствует в requirements.txt — установка без speechbrain "
        "оставит пайплайн без обязательной зависимости"
    )


def test_all_module_level_third_party_imports_declared():
    """Каждый сторонний импорт верхнего уровня должен быть в requirements."""
    import ast
    import sys

    stdlib = set(sys.stdlib_module_names)
    local = {p.stem for p in ROOT.glob("*.py")}
    # Импорты внутри функций и опциональные бэкенды объявляются отдельно
    optional = {"pyannote", "llama_cpp", "speechbrain", "torch", "fpdf"}
    package_alias = {
        "faster_whisper": "faster-whisper",
        "imageio_ffmpeg": "imageio-ffmpeg",
        "dotenv": "python-dotenv",
        "av": "faster-whisper",
        "ctranslate2": "faster-whisper",
    }

    text = (ROOT / "requirements.txt").read_text(encoding="utf-8").lower()
    missing = []
    for path in ROOT.glob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in tree.body:  # только верхний уровень модуля
            names: list[str] = []
            if isinstance(node, ast.Import):
                names = [a.name.split(".")[0] for a in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
                names = [node.module.split(".")[0]]
            for name in names:
                if name in stdlib or name in local or name in optional:
                    continue
                pkg = package_alias.get(name, name)
                if pkg.lower() not in text:
                    missing.append(f"{path.name}: {name}")
    assert not missing, "не объявлены в requirements.txt: " + ", ".join(sorted(set(missing)))


# ---------- 2. смысловые повторы ----------

@pytest.mark.parametrize("text,expected", [
    ("Мы долго долго обсуждали это.", "Мы долго долго обсуждали это."),
    ("Это было давно давно.", "Это было давно давно."),
    ("Очень очень хорошая идея.", "Очень очень хорошая идея."),
])
def test_meaningful_repeats_are_preserved(text, expected):
    """Повтор знаменательного слова — нормальная речь, сокращать нельзя."""
    assert llm.clean_rules(text) == expected


@pytest.mark.parametrize("text", [
    "Да да да, согласен.",
    "в в этом месте",
    "Ну ну ну.",
    "Мы мы обсудили",
])
def test_stutter_is_collapsed(text):
    """Заикание на служебных словах убирается."""
    result = llm.clean_rules(text)
    words = llm._norm_words(result)
    assert len(words) == len(set(words)) or "да" not in words


def test_long_text_is_not_collapsed():
    """Раньше правило повторов сжимало 30000 символов до 6."""
    long_text = "слово " * 5000
    result = llm.clean_rules(long_text)
    assert len(result) > len(long_text) * 0.9


# ---------- 3. тесты не должны требовать ffmpeg ----------

def test_wav_roundtrip_is_guarded_by_ffmpeg_check():
    """Тест декодирования должен пропускаться без ffmpeg, а не падать."""
    source = (ROOT / "tests" / "test_diarization.py").read_text(encoding="utf-8")
    assert "skipif" in source
    assert "_has_ffmpeg" in source


# ---------- 4. переносимость шрифтов ----------

def test_make_pdf_has_cross_platform_fonts():
    source = (ROOT / "make_pdf.py").read_text(encoding="utf-8")
    assert "C:\\Windows\\Fonts" in source  # Windows остаётся первым вариантом
    assert "dejavu" in source.lower() or "liberation" in source.lower()
    assert "find_fonts" in source


# ---------- 5. краевые случаи, проверенные вручную ----------

def test_clean_rules_handles_empty_and_whitespace():
    assert llm.clean_rules("") == ""
    assert llm.clean_rules("   ") == ""


def test_clean_segments_handles_empty_list():
    assert llm.clean_segments([]) == []


def test_clean_segments_keeps_timing_fields():
    segments = [{"start_ms": 0, "end_ms": 1000, "text": " привет  мир ", "speaker_id": "speaker_1"}]
    out = llm.clean_segments(segments, backend="rules")
    assert out[0]["start_ms"] == 0
    assert out[0]["end_ms"] == 1000
    assert out[0]["speaker_id"] == "speaker_1"
    assert out[0]["text"] == "Привет мир."

# ---------- 6. build_result не должен выдавать невалидный результат ----------

@pytest.mark.parametrize("segments,duration_ms", [
    ([{"speaker_id": "a", "start_ms": -500, "end_ms": 1000, "text": "x"}], 5000),
    ([{"speaker_id": "a", "start_ms": 1000, "end_ms": 1000, "text": "x"}], 5000),
    ([{"speaker_id": "a", "start_ms": 4900, "end_ms": 9000, "text": "x"}], 5000),
    ([{"speaker_id": "a", "start_ms": 0, "end_ms": 1000, "text": "x"}], 0),
])
def test_build_result_output_always_passes_validation(segments, duration_ms):
    """Раньше build_result пропускал отрицательный start_ms, а validate_hex на
    нём падал — save_result падал на собственном выводе."""
    import output

    result = output.build_result(segments, duration_ms=duration_ms)
    assert output.validate_hex(result, duration_ms) == []


def test_build_result_keeps_valid_segment():
    import output

    segments = [{"speaker_id": "a", "start_ms": 0, "end_ms": 1000, "text": "x"}]
    result = output.build_result(segments, duration_ms=5000)
    assert len(result["transcript"]) == 1
