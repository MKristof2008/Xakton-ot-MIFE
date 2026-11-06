"""Локальное распознавание речи (ASR) на faster-whisper.

Вход: аудиофайл (mp3, wav, m4a).
Выход: list[dict] — [{"start_ms": 1250, "end_ms": 4380, "text": "..."}]

Работает полностью офлайн после первой загрузки модели.
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


def transcribe(audio_path: str, model_size: str = DEFAULT_MODEL_SIZE,
               language: str = "ru", device: str = "auto",
               compute_type: str = "int8") -> list[dict]:
    """Возвращает сегменты с таймкодами в миллисекундах.

    device: "auto" | "cpu" | "cuda". compute_type: "int8" (быстро) | "float16" (GPU).
    """
    from faster_whisper import WhisperModel

    if device == "auto":
        device = "cuda" if _cuda_available() else "cpu"
    model = WhisperModel(model_size, device=device, compute_type=compute_type)

    segments, _info = model.transcribe(
        audio_path,
        language=language,
        vad_filter=True,  # вырезает тишину, ускоряет и улучшает границы
        beam_size=5,
    )

    result = []
    for seg in segments:
        text = seg.text.strip()
        if not text:
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
    segs = transcribe(path, model_size=size)
    elapsed = time.perf_counter() - t0
    print(json.dumps(segs, ensure_ascii=False, indent=2))
    print(f"--- {len(segs)} сегментов, {elapsed:.1f} сек ---", file=sys.stderr)
