"""Локальное распознавание речи (ASR) на faster-whisper.

Вход: аудиофайл (mp3, wav, m4a).
Выход: list[dict] — [{"start_ms": 1250, "end_ms": 4380, "text": "..."}]

Работает полностью офлайн после первой загрузки модели.

Фильтр галлюцинаций: Whisper на тишине и в конце записи додумывает фразы
вида «Thank you for watching.», «Продолжение следует». Признаки, по которым
такие сегменты отбрасываются:
- сегмент выходит за конец аудио (физически невозможен);
- no_speech_prob выше порога — модель сама считает, что речи тут нет;
- avg_logprob сильно ниже обычного — уверенность падает;
- compression_ratio выше порога — модель зациклилась и повторяет себя.
Пороги подобраны по замерам: у настоящей речи no_speech_prob не превышал
0.063, у галлюцинации был 0.938.
"""

from __future__ import annotations

# faster-whisper <= 1.2.1 передаёт metadata_errors в av.open,
# чего нет в PyAV >= 13. Глушим параметр через монкейпатч.
def _patch_pyav_compat() -> None:
    try:
        import av

        _orig_open = av.open

        def _open(file, mode="r", **kwargs):
            kwargs.pop("metadata_errors", None)
            return _orig_open(file, mode=mode, **kwargs)

        av.open = _open
        # faster_whisper.audio держит тот же объект модуля av, этого достаточно.
    except Exception:
        pass


_patch_pyav_compat()

# Размер модели по умолчанию: base — компромисс скорость/качество для Android-порта.
DEFAULT_MODEL_SIZE = "base"

# --- Пороги фильтра галлюцинаций ---
# Замерено на контрольном наборе (43 сегмента): у настоящей речи
# no_speech_prob не превышал 0.063, у галлюцинации был 0.938.
# compression_ratio и avg_logprob в фильтр НЕ входят: замеры показали, что они
# не разделяют случаи (у галлюцинации compression=0.74, у живой речи до 2.65),
# то есть давали только ложные срабатывания.
NO_SPEECH_LIMIT = 0.60       # выше — модель уверена, что это не речь
END_TOLERANCE_SEC = 0.25     # допуск на округление границы аудио
# Фразы, которые Whisper выдаёт на тишине. Сравнение без регистра и пунктуации.
BOILERPLATE = (
    "thank you for watching",
    "thanks for watching",
    "please subscribe",
    "subscribe to my channel",
    "продолжение следует",
    "спасибо за просмотр",
    "подписывайтесь на канал",
    "ставьте лайки",
    "субтитры подготовил",
    "субтитры делал",
)


def audio_duration_sec(audio_path: str) -> float | None:
    """Длительность аудио. None, если узнать не удалось."""
    try:
        import av

        with av.open(audio_path) as container:
            if container.duration is not None:
                return container.duration / 1_000_000  # av отдаёт микросекунды
            stream = container.streams.audio[0]
            if stream.duration is not None and stream.time_base is not None:
                return float(stream.duration * stream.time_base)
    except Exception:
        pass
    try:
        import wave

        with wave.open(audio_path, "rb") as w:
            return w.getnframes() / w.getframerate()
    except Exception:
        return None


def _normalize(text: str) -> str:
    import re

    return re.sub(r"[^\w\s]", " ", text.lower(), flags=re.UNICODE).strip()


def is_hallucination(seg, duration: float | None = None) -> tuple[bool, str]:
    """Проверяет один сегмент faster-whisper. Возвращает (отбросить, причина).

    Два надёжных признака (проверены замерами):
    - сегмент выходит за конец аудио — физически невозможен;
    - no_speech_prob выше порога — модель сама считает, что речи нет.
    """
    if duration is not None and seg.end > duration + END_TOLERANCE_SEC:
        return True, f"выходит за конец аудио ({seg.end:.1f}с > {duration:.1f}с)"

    no_speech = float(getattr(seg, "no_speech_prob", 0.0) or 0.0)
    if no_speech > NO_SPEECH_LIMIT:
        return True, f"no_speech_prob={no_speech:.2f}"

    normalized = _normalize(seg.text or "")
    if normalized and any(phrase in normalized for phrase in BOILERPLATE):
        return True, "типовая фраза-заглушка"

    return False, ""


def transcribe(audio_path: str, model_size: str = DEFAULT_MODEL_SIZE,
               language: str = "ru", device: str = "auto",
               compute_type: str = "int8", drop_hallucinations: bool = True,
               on_drop=None) -> list[dict]:
    """Возвращает сегменты с таймкодами в миллисекундах.

    device: "auto" | "cpu" | "cuda". compute_type: "int8" (быстро) | "float16" (GPU).
    drop_hallucinations=False отключает фильтр (для сравнения и отладки).
    on_drop(причина, текст) вызывается на каждом отброшенном сегменте.
    """
    from faster_whisper import WhisperModel

    if device == "auto":
        device = "cuda" if _cuda_available() else "cpu"
    model = WhisperModel(model_size, device=device, compute_type=compute_type)

    duration = audio_duration_sec(audio_path) if drop_hallucinations else None

    segments, _info = model.transcribe(
        audio_path,
        language=language,
        vad_filter=True,  # вырезает тишину, ускоряет и улучшает границы
        beam_size=5,
        condition_on_previous_text=False,  # меньше зацикливания на своей же истории
    )

    result = []
    for seg in segments:
        text = seg.text.strip()
        if not text:
            continue
        if drop_hallucinations:
            dropped, reason = is_hallucination(seg, duration)
            if dropped:
                if on_drop:
                    on_drop(reason, text)
                continue
        result.append({
            "start_ms": int(seg.start * 1000),
            "end_ms": int(seg.end * 1000),
            "text": text,
        })
    return result


def _cuda_available() -> bool:
    try:
        import ctranslate2
        return ctranslate2.get_cuda_device_count() > 0
    except Exception:
        return False


if __name__ == "__main__":
    import json
    import sys
    import time

    path = sys.argv[1]
    size = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_MODEL_SIZE
    t0 = time.perf_counter()
    dropped: list[tuple[str, str]] = []
    segs = transcribe(path, model_size=size,
                      on_drop=lambda reason, text: dropped.append((reason, text)))
    elapsed = time.perf_counter() - t0
    print(json.dumps(segs, ensure_ascii=False, indent=2))
    for reason, text in dropped:
        print(f"отброшено ({reason}): {text!r}", file=sys.stderr)
    print(f"--- {len(segs)} сегментов, {len(dropped)} отброшено, {elapsed:.1f} сек ---",
          file=sys.stderr)