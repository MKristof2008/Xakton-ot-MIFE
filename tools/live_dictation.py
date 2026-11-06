"""Живая диктовка: Enter -> запись с микрофона -> Enter -> расшифровка whisper.

Использование:
    py tools/live_dictation.py [модель]   # по умолчанию small

Нажми Enter, говори, нажми Enter — получишь текст. 'q' + Enter — выход.
Работает локально, без сети.
"""

from __future__ import annotations

import sys
import tempfile
import threading
import wave

SAMPLE_RATE = 16000
CHANNELS = 1


def record_until_enter() -> str:
    """Пишет с микрофона до нажатия Enter. Возвращает путь к wav."""
    import sounddevice as sd

    chunks: list = []
    stop = threading.Event()

    def callback(indata, frames, time_info, status):
        chunks.append(indata.copy())

    stream = sd.InputStream(samplerate=SAMPLE_RATE, channels=CHANNELS,
                            dtype="int16", callback=callback)
    stream.start()
    input()  # ждём Enter
    stop.set()
    stream.stop()
    stream.close()

    if not chunks:
        raise SystemExit("ничего не записано")
    import numpy as np
    audio = np.concatenate(chunks, axis=0)

    path = tempfile.mktemp(suffix=".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(CHANNELS)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(audio.tobytes())
    return path


def main() -> None:
    model_size = sys.argv[1] if len(sys.argv) > 1 else "small"
    print(f"Загружаю whisper {model_size}...")
    from asr import transcribe

    print("Готов. Enter — начать/закончить запись, q+Enter — выход.\n")
    while True:
        cmd = input("[Enter = запись] ")
        if cmd.strip().lower() == "q":
            break
        print("Запись... (Enter — стоп)")
        wav_path = record_until_enter()
        print("Расшифровываю...")
        segments = transcribe(wav_path, model_size=model_size)
        text = " ".join(s["text"] for s in segments)
        print(f"\n>>> {text}\n")


if __name__ == "__main__":
    main()
