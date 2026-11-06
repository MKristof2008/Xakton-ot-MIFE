"""Собирает тестовый диалог с 2 спикерами из записей VoxForge.

Берём wav двух разных дикторов, чередуем реплики с паузами.
Выход: artifacts/test-audio/dialog_2spk.wav (16 кГц моно)
"""

from __future__ import annotations

import wave
from pathlib import Path

ROOT = Path(__file__).parent.parent / "artifacts" / "test-audio"

SPEAKER_A = ROOT / "1-20121125-pgp" / "wav"
SPEAKER_B = ROOT / "1981-20120705-haq" / "wav"
OUT = ROOT / "dialog_2spk.wav"

PAUSE_SEC = 0.4
REPLICAS_PER_SPEAKER = 4


def read_wav(path: Path) -> tuple[bytes, int, int]:
    with wave.open(str(path), "rb") as w:
        return w.readframes(w.getnframes()), w.getframerate(), w.getsampwidth()


def main() -> None:
    a_files = sorted(SPEAKER_A.glob("*.wav"))[:REPLICAS_PER_SPEAKER]
    b_files = sorted(SPEAKER_B.glob("*.wav"))[:REPLICAS_PER_SPEAKER]
    if not a_files or not b_files:
        raise SystemExit("нет wav в artifacts/test-audio")

    frames_a = [read_wav(f) for f in a_files]
    frames_b = [read_wav(f) for f in b_files]
    rate, width = frames_a[0][1], frames_a[0][2]

    pause = b"\x00" * int(rate * PAUSE_SEC) * width
    out = bytearray()
    timeline = []  # (speaker, start_ms, end_ms) — эталон диаризации
    pos_ms = 0
    for i in range(REPLICAS_PER_SPEAKER):
        for spk, frames in (("speaker_1", frames_a[i][0]), ("speaker_2", frames_b[i][0])):
            out += frames
            dur_ms = len(frames) // width * 1000 // rate
            timeline.append((spk, pos_ms, pos_ms + dur_ms))
            pos_ms += dur_ms
            out += pause
            pos_ms += int(PAUSE_SEC * 1000)

    with wave.open(str(OUT), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(width)
        w.setframerate(rate)
        w.writeframes(bytes(out))

    print(f"OK: {OUT}  ({pos_ms/1000:.1f} сек, {len(timeline)} реплик)")
    ref = OUT.with_suffix(".ref.txt")
    ref.write_text("\n".join(f"{s} {a} {b}" for s, a, b in timeline), encoding="utf-8")
    print(f"эталон диаризации: {ref}")


if __name__ == "__main__":
    main()
