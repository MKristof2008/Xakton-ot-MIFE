"""Тесты точки входа run_local.py (пайплайн по ТЗ).

Модуль был на 0% покрытия, хотя это то, что запускает жюри. Модели не грузим:
шаги ASR/диаризации/LLM подменяются через monkeypatch, проверяется склейка
шагов, флаги, формат вывода и обработка ошибок.
"""
from __future__ import annotations

import json
import sys

import pytest

import run_local


def _run(monkeypatch, argv: list[str]) -> int:
    monkeypatch.setattr(sys, "argv", ["run_local.py", *argv])
    try:
        run_local.main()
    except SystemExit as exc:
        return int(exc.code or 0)
    return 0


@pytest.fixture()
def stubs(monkeypatch):
    """Подменяет все тяжёлые шаги пайплайна детерминированными заглушками."""
    calls: dict[str, object] = {}

    def fake_transcribe(audio_path, model_size="base", language="ru", **kwargs):
        calls["transcribe"] = {"model": model_size, "language": language}
        return [
            {"start_ms": 0, "end_ms": 1000, "text": "привет"},
            {"start_ms": 1000, "end_ms": 2000, "text": "да да начнем"},
        ]

    def fake_diarize(audio_path, segments, num_speakers=None, **kwargs):
        calls["diarize"] = {"num_speakers": num_speakers}
        return [{**s, "speaker_id": f"speaker_{i + 1}"} for i, s in enumerate(segments)]

    monkeypatch.setattr("asr.transcribe", fake_transcribe)
    monkeypatch.setattr("diarization.diarize", fake_diarize)
    monkeypatch.setitem(sys.modules, "asr", sys.modules.get("asr") or __import__("asr"))
    monkeypatch.setattr("asr.audio_duration_sec", lambda path: 2.0)
    return calls


def test_runs_full_pipeline(monkeypatch, stubs, tmp_path):
    out = tmp_path / "result.json"
    code = _run(monkeypatch, ["audio.wav", "--model", "tiny", "--no-llm", "-o", str(out)])
    assert code == 0
    assert out.exists()
    data = json.loads(out.read_text(encoding="utf-8"))
    assert "transcript" in data
    assert len(data["transcript"]) == 2  # разные спикеры, склейки нет
    assert all(s["speaker_id"].startswith("speaker_") for s in data["transcript"])


def test_transcribe_receives_model_and_language(monkeypatch, stubs, tmp_path):
    out = tmp_path / "r.json"
    _run(monkeypatch, ["audio.wav", "--model", "tiny", "--language", "en",
                       "--no-llm", "-o", str(out)])
    assert stubs["transcribe"]["model"] == "tiny"
    assert stubs["transcribe"]["language"] == "en"


def test_speakers_flag_is_passed(monkeypatch, stubs, tmp_path):
    out = tmp_path / "r.json"
    _run(monkeypatch, ["audio.wav", "--speakers", "3", "--no-llm", "-o", str(out)])
    assert stubs["diarize"]["num_speakers"] == 3


def test_skip_diarization_gives_single_speaker(monkeypatch, stubs, tmp_path):
    out = tmp_path / "r.json"
    _run(monkeypatch, ["audio.wav", "--skip-diarization", "--no-llm", "-o", str(out)])
    data = json.loads(out.read_text(encoding="utf-8"))
    assert {s["speaker_id"] for s in data["transcript"]} == {"speaker_1"}


def test_no_llm_skips_postprocessing(monkeypatch, stubs, tmp_path):
    out = tmp_path / "r.json"
    _run(monkeypatch, ["audio.wav", "--no-llm", "-o", str(out)])
    data = json.loads(out.read_text(encoding="utf-8"))
    # без постобработки текст остаётся как от ASR
    assert data["transcript"][0]["text"] == "привет"


def test_llm_postprocessing_applies_rules(monkeypatch, stubs, tmp_path):
    out = tmp_path / "r.json"
    _run(monkeypatch, ["audio.wav", "-o", str(out)])
    data = json.loads(out.read_text(encoding="utf-8"))
    assert data["transcript"][0]["text"].endswith(".")


def test_default_output_path_next_to_audio(monkeypatch, stubs, tmp_path):
    audio = tmp_path / "meeting.wav"
    audio.write_bytes(b"RIFF")
    monkeypatch.chdir(tmp_path)
    _run(monkeypatch, ["meeting.wav", "--no-llm"])
    assert (tmp_path / "meeting.hex.json").exists()


def test_output_passes_validation(monkeypatch, stubs, tmp_path):
    """Результат пайплайна обязан проходить validate_hex с границами."""
    import output

    out = tmp_path / "r.json"
    _run(monkeypatch, ["audio.wav", "--no-llm", "-o", str(out)])
    data = json.loads(out.read_text(encoding="utf-8"))
    assert output.validate_hex(data, duration_ms=2000) == []


def test_utf8_console_helper_is_safe():
    """Не должен падать, даже если у потока нет buffer (например, в тестах)."""
    run_local._force_utf8_console()


def test_missing_argument_exits_nonzero(monkeypatch):
    monkeypatch.setattr(sys, "argv", ["run_local.py"])
    with pytest.raises(SystemExit) as exc:
        run_local.main()
    assert exc.value.code != 0