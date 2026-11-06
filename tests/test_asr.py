"""Тесты ASR-слоя: детерминизм, язык, кэш моделей, ошибки ввода.

Закрывают пять проблем, найденных замерами:
1. Результат был недетерминирован: один файл давал 18, 18 и 21 сегмент из-за
   temperature-fallback (случайная выборка при неуверенности).
2. language="ru" стоял по умолчанию, и на английском аудио модель выдумывала
   русские фразы.
3. Модель грузилась на каждый вызов (0.5-0.95 с потерь).
4. Ошибки ввода давали сырой traceback PyAV вместо понятного сообщения.
5. float16 на CPU падал с ValueError, хотя был указан как допустимый.

Тесты не требуют моделей: load_model подменяется заглушкой.
"""
from __future__ import annotations

import pytest

import asr


@pytest.fixture()
def audio_file(tmp_path):
    """Реальный файл: transcribe проверяет существование до вызова модели."""
    path = tmp_path / "a.wav"
    path.write_bytes(b"RIFF" + b"0" * 100)
    return path


# ---------- 1. детерминизм ----------

def test_temperature_default_is_zero():
    """При temperature=0 fallback-выборка выключена, результат воспроизводим."""
    assert asr.TEMPERATURE == 0.0


def test_transcribe_passes_temperature_to_model(monkeypatch, audio_file):
    calls: dict = {}

    class FakeModel:
        def transcribe(self, path, **kwargs):
            calls.update(kwargs)
            return iter([]), type("I", (), {"language": "ru"})()

    monkeypatch.setattr(asr, "load_model", lambda *a, **k: FakeModel())
    monkeypatch.setattr(asr, "audio_duration_sec", lambda p: 1.0)
    asr.transcribe(str(audio_file), drop_hallucinations=False)
    assert calls["temperature"] == 0.0


def test_transcribe_is_repeatable_with_stub(monkeypatch, audio_file):
    """Одинаковые вызовы дают одинаковый результат при фиксированной модели."""
    class FakeSegment:
        def __init__(self, start, end, text):
            self.start, self.end, self.text = start, end, text
            self.no_speech_prob = 0.01

    class FakeModel:
        def transcribe(self, path, **kwargs):
            return iter([FakeSegment(0.0, 1.0, " привет")]), type("I", (), {"language": "ru"})()

    monkeypatch.setattr(asr, "load_model", lambda *a, **k: FakeModel())
    monkeypatch.setattr(asr, "audio_duration_sec", lambda p: 1.0)
    first = asr.transcribe(str(audio_file))
    second = asr.transcribe(str(audio_file))
    assert first == second


# ---------- 2. язык ----------

def test_language_defaults_to_none():
    """Автоопределение вместо жёсткого 'ru'."""
    import inspect

    param = inspect.signature(asr.transcribe).parameters["language"]
    assert param.default is None


def test_language_none_is_passed_through(monkeypatch, audio_file):
    calls: dict = {}

    class FakeModel:
        def transcribe(self, path, **kwargs):
            calls.update(kwargs)
            return iter([]), type("I", (), {"language": "en"})()

    monkeypatch.setattr(asr, "load_model", lambda *a, **k: FakeModel())
    monkeypatch.setattr(asr, "audio_duration_sec", lambda p: 1.0)
    asr.transcribe(str(audio_file), drop_hallucinations=False)
    assert calls["language"] is None


def test_detected_language_is_exposed(monkeypatch, audio_file):
    class FakeModel:
        def transcribe(self, path, **kwargs):
            return iter([]), type("I", (), {"language": "en"})()

    monkeypatch.setattr(asr, "load_model", lambda *a, **k: FakeModel())
    monkeypatch.setattr(asr, "audio_duration_sec", lambda p: 1.0)
    asr.transcribe(str(audio_file), drop_hallucinations=False)
    assert asr.transcribe.last_language == "en"


def test_explicit_language_is_respected(monkeypatch, audio_file):
    calls: dict = {}

    class FakeModel:
        def transcribe(self, path, **kwargs):
            calls.update(kwargs)
            return iter([]), type("I", (), {"language": "ru"})()

    monkeypatch.setattr(asr, "load_model", lambda *a, **k: FakeModel())
    monkeypatch.setattr(asr, "audio_duration_sec", lambda p: 1.0)
    asr.transcribe(str(audio_file), language="ru", drop_hallucinations=False)
    assert calls["language"] == "ru"


# ---------- 3. кэш моделей ----------

def test_load_model_caches(monkeypatch):
    created = []

    class FakeWhisperModel:
        def __init__(self, size, device, compute_type):
            created.append((size, device, compute_type))

    monkeypatch.setitem(__import__("sys").modules, "faster_whisper",
                        type("M", (), {"WhisperModel": FakeWhisperModel}))
    monkeypatch.setattr(asr, "cuda_available", lambda: False)
    asr.clear_model_cache()

    first = asr.load_model("tiny")
    second = asr.load_model("tiny")
    assert first is second
    assert len(created) == 1

    asr.clear_model_cache()


def test_load_model_separate_keys(monkeypatch):
    created = []

    class FakeWhisperModel:
        def __init__(self, size, device, compute_type):
            created.append((size, device, compute_type))

    monkeypatch.setitem(__import__("sys").modules, "faster_whisper",
                        type("M", (), {"WhisperModel": FakeWhisperModel}))
    monkeypatch.setattr(asr, "cuda_available", lambda: False)
    asr.clear_model_cache()

    asr.load_model("tiny")
    asr.load_model("base")
    assert len(created) == 2

    asr.clear_model_cache()


# ---------- 4. ошибки ввода ----------

def test_missing_file_gives_clear_error():
    with pytest.raises(FileNotFoundError) as err:
        asr.transcribe("/нет/такого/файла.wav", model_size="tiny")
    assert "не найден" in str(err.value)


def test_empty_file_gives_clear_error(tmp_path):
    empty = tmp_path / "empty.wav"
    empty.write_bytes(b"")
    with pytest.raises(ValueError) as err:
        asr.transcribe(str(empty), model_size="tiny")
    assert "пустой" in str(err.value)


def test_validate_input_passes_for_real_file(tmp_path):
    path = tmp_path / "ok.wav"
    path.write_bytes(b"RIFFdata")
    asr._validate_input(str(path))  # не должно бросить


# ---------- 5. compute_type и устройство ----------

@pytest.mark.parametrize("requested,device,expected", [
    ("float16", "cpu", "int8"),
    ("int8_float16", "cpu", "int8"),
    ("float16", "cuda", "float16"),
    ("int8", "cpu", "int8"),
    ("auto", "cpu", "int8"),
    ("auto", "cuda", "float16"),
])
def test_resolve_compute_type(requested, device, expected):
    assert asr.resolve_compute_type(requested, device) == expected


def test_resolve_device_auto(monkeypatch):
    monkeypatch.setattr(asr, "cuda_available", lambda: True)
    assert asr.resolve_device("auto") == "cuda"
    monkeypatch.setattr(asr, "cuda_available", lambda: False)
    assert asr.resolve_device("auto") == "cpu"


def test_resolve_device_explicit():
    assert asr.resolve_device("cpu") == "cpu"
    assert asr.resolve_device("cuda") == "cuda"


def test_cuda_available_returns_bool():
    assert isinstance(asr.cuda_available(), bool)


# ---------- прочее ----------

def test_word_timestamps_are_returned(monkeypatch, audio_file):
    class FakeWord:
        def __init__(self, s, e, w):
            self.start, self.end, self.word = s, e, w

    class FakeSegment:
        def __init__(self):
            self.start, self.end, self.text = 0.0, 1.0, " привет мир"
            self.no_speech_prob = 0.01
            self.words = [FakeWord(0.0, 0.5, " привет"), FakeWord(0.5, 1.0, " мир")]

    class FakeModel:
        def transcribe(self, path, **kwargs):
            return iter([FakeSegment()]), type("I", (), {"language": "ru"})()

    monkeypatch.setattr(asr, "load_model", lambda *a, **k: FakeModel())
    monkeypatch.setattr(asr, "audio_duration_sec", lambda p: 1.0)
    out = asr.transcribe(str(audio_file), word_timestamps=True)
    assert len(out[0]["words"]) == 2
    assert out[0]["words"][0]["start_ms"] == 0