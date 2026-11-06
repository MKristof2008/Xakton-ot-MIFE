"""Генератор контрольного набора для диаризации.

Создаёт WAV-диалоги с известными границами реплик и файлы <имя>.truth.json.
Сами WAV в git не кладём (11 МБ) — набор воспроизводим этим скриптом.

Требуется Windows с голосами SAPI (Irina/Zira/David) и ffmpeg
(подойдёт из пакета imageio-ffmpeg).

Использование:
    py make_testdata.py            # в каталог testdata/
    py make_testdata.py --out dir
"""
from __future__ import annotations

import argparse
import base64
import json
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

SAMPLE_RATE = 16_000

# Русские фразы: три «спикера» получаются сдвигом тона одного голоса Irina.
# Так набор остаётся ОДНОЯЗЫЧНЫМ — на реальной встрече язык тоже один.
RU_PHRASES = [
    "Добрый день, коллеги. Начнём с обсуждения релиза мобильного приложения.",
    "Я думаю, мы успеваем выпустить версию в пятницу вечером.",
    "Хорошо. Тогда нужно подготовить описание изменений до четверга.",
    "Да, я подготовлю документ и пришлю всем участникам.",
    "Ещё остался открытым вопрос про поддержку старых телефонов.",
    "Мне кажется, этим стоит заняться уже после запуска.",
    "Согласен, но давай зафиксируем это как отдельную задачу.",
    "Хорошо, я запишу в план следующего спринта.",
]

EN_PHRASES = [
    "Good morning everyone, let us start with the release of the mobile app.",
    "I believe we can ship the new version on Friday evening.",
    "Fine, then we need to prepare the release notes before Thursday.",
    "Yes, I will write the document and send it to all participants.",
    "There is still an open question about support for older devices.",
    "I think we should deal with that after the launch instead.",
    "Agreed, but let us record it as a separate task for now.",
    "Alright, I will put it into the plan for the next sprint.",
]

# Сдвиг тона в полутонах: 0 — исходный голос, +4 и -4 — два других «спикера».
PITCH_SEMITONES = {"A": 0, "B": 7, "C": -6}


def _run_encoded_powershell(script: str) -> None:
    """PowerShell через EncodedCommand: не ломается на кавычках и кириллице."""
    encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    proc = subprocess.run(
        ["powershell", "-NoProfile", "-EncodedCommand", encoded],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if proc.returncode != 0:
        raise RuntimeError(f"PowerShell failed: {proc.stderr[:400]}")


def synthesize_sapi(phrases: list[str], voice: str, prefix: str, out_dir: Path) -> list[Path]:
    """Синтез фраз через SAPI, каждая — отдельный WAV."""
    lines = [
        "Add-Type -AssemblyName System.Speech",
        "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer",
        f"$s.SelectVoice('{voice}')",
    ]
    for i, text in enumerate(phrases, 1):
        path = out_dir / f"{prefix}{i:02d}.wav"
        safe = text.replace("'", "''")
        lines.append(f"$s.SetOutputToWaveFile('{path}')")
        lines.append(f"$s.Speak('{safe}')")
        lines.append("$s.SetOutputToNull()")
    lines.append("$s.Dispose()")
    _run_encoded_powershell("\n".join(lines))
    return [out_dir / f"{prefix}{i:02d}.wav" for i in range(1, len(phrases) + 1)]


def _ffmpeg() -> str:
    import shutil

    found = shutil.which("ffmpeg")
    if found:
        return found
    import imageio_ffmpeg

    return imageio_ffmpeg.get_ffmpeg_exe()


def wav_sample_rate(path: Path) -> int:
    """Частота дискретизации из заголовка WAV."""
    with wave.open(str(path), "rb") as w:
        return w.getframerate()


def pitch_shift(src: Path, dst: Path, semitones: int) -> Path:
    """Сдвиг тона без изменения длительности.

    asetrate меняет и тон, и скорость, поэтому длительность возвращаем atempo
    (темп = 1/коэффициент). Частоту источника берём из файла: SAPI отдаёт
    22050 Гц, и зашитые 44100 искажали речь вдвое.
    """
    if semitones == 0:
        dst.write_bytes(src.read_bytes())
        return dst

    sr = wav_sample_rate(src)
    factor = 2.0 ** (semitones / 12.0)
    tempo = 1.0 / factor
    subprocess.run(
        [
            _ffmpeg(), "-nostdin", "-v", "error", "-y", "-i", str(src),
            "-af", f"asetrate={int(sr * factor)},atempo={tempo},aresample={SAMPLE_RATE}",
            "-ac", "1", "-ar", str(SAMPLE_RATE), str(dst),
        ],
        check=True,
        capture_output=True,
    )
    return dst


def read_wav_mono(path: Path, sr: int = SAMPLE_RATE) -> np.ndarray:
    """Читает WAV как моно float32 на нужной частоте через ffmpeg."""
    proc = subprocess.run(
        [_ffmpeg(), "-nostdin", "-v", "error", "-i", str(path),
         "-f", "f32le", "-acodec", "pcm_f32le", "-ac", "1", "-ar", str(sr), "-"],
        capture_output=True,
        check=True,
    )
    return np.frombuffer(proc.stdout, dtype=np.float32).copy()


def write_wav(path: Path, audio: np.ndarray, sr: int = SAMPLE_RATE) -> None:
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(sr)
        f.writeframes(pcm.tobytes())


def build_dialog(name: str, speakers: list[str], clips: dict[str, list[Path]],
                 out_dir: Path, gap: float = 0.5, turn_gap: float = 0.2) -> dict:
    """Собирает диалог: спикеры говорят по очереди, реплики помечены в truth.json."""
    parts: list[np.ndarray] = []
    truth: list[dict] = []
    t = 0.0
    n = min(len(clips[s]) for s in speakers)

    for i in range(n):
        for who in speakers:
            audio = read_wav_mono(clips[who][i])
            parts.append(audio)
            truth.append({
                "start": round(t, 3),
                "end": round(t + len(audio) / SAMPLE_RATE, 3),
                "speaker": who,
            })
            t += len(audio) / SAMPLE_RATE
            silence = np.zeros(int(gap * SAMPLE_RATE), dtype=np.float32)
            parts.append(silence)
            t += gap

    audio = np.concatenate(parts) if parts else np.zeros(SAMPLE_RATE, dtype=np.float32)
    write_wav(out_dir / f"{name}.wav", audio)
    meta = {"speakers": speakers, "turns": truth, "language": "ru"}
    (out_dir / f"{name}.truth.json").write_text(
        json.dumps(truth, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    return meta


def main() -> int:
    ap = argparse.ArgumentParser(description="Генерация контрольного набора диаризации")
    ap.add_argument("--out", default="testdata", help="каталог для набора")
    ap.add_argument("--work", default=".testdata_work", help="каталог для сырых фрагментов")
    args = ap.parse_args()

    # Синтез идёт через SAPI и существует только в Windows. Без этой проверки
    # на Linux падало бы с невнятным FileNotFoundError на 'powershell'.
    if sys.platform != "win32":
        print(
            "Генерация набора требует Windows: русские голоса берутся из SAPI.\n"
            "Готовые WAV лежат в testdata/ (в git не входят, но создаются один раз).\n"
            "Альтернатива: подставьте свои записи и <имя>.truth.json.",
            file=sys.stderr,
        )
        return 2

    out_dir = Path(args.out)
    work = Path(args.work)
    out_dir.mkdir(parents=True, exist_ok=True)
    work.mkdir(parents=True, exist_ok=True)

    print("1/3 синтез русских фраз (Irina)...")
    base = synthesize_sapi(RU_PHRASES, "Microsoft Irina Desktop", "ru", work)

    print("2/3 сдвиг тона для трёх различимых голосов...")
    clips: dict[str, list[Path]] = {}
    for who, semitones in PITCH_SEMITONES.items():
        clips[who] = [pitch_shift(b, work / f"v{who}{i:02d}.wav", semitones)
                      for i, b in enumerate(base, 1)]

    print("3/3 сборка диалогов...")
    specs = [
        ("monolog_1spk", ["A"]),
        ("dialog_2spk", ["A", "B"]),
        ("dialog_3spk", ["A", "B", "C"]),
    ]
    for name, speakers in specs:
        meta = build_dialog(name, speakers, clips, out_dir)
        duration = meta["turns"][-1]["end"] if meta["turns"] else 0.0
        print(f"    {name}: {len(speakers)} спикеров, {duration:.1f}с, "
              f"{len(meta['turns'])} реплик")

        # WER-эталон для односписочного набора: 8 фраз подряд
        if name == "monolog_1spk":
            (out_dir / f"{name}.reference.txt").write_text(
                " ".join(RU_PHRASES), encoding="utf-8"
            )

    print(f"\nГотово: {out_dir}")
    print("WAV в git не нужны — исключены через .gitignore, набор воспроизводим.")
    return 0


if __name__ == "__main__":
    sys.exit(main())