"""ASR-слой: аудио -> сегменты с таймкодами -> транскрипт с диаризацией.

Два бэкенда распознавания:
- local    — faster-whisper (CTranslate2), без внешнего API и ключей
- aitunnel — /audio/transcriptions (OpenAI-совместимый Whisper)

Спикеры назначаются по перекрытию сегмента/слова с репликами из diarize.py.
Формат результата совпадает с тем, что ждут промпты: `[MM:SS] Спикер N: текст`.

Отличие от asr.py: тот отдаёт плоский список сегментов в формате ТЗ
(start_ms/end_ms/text) и используется пайплайном run_local.py. Здесь —
объектная модель с словами, спикерами и бэкендами, она питает app.py.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

import audio_utils
import diarize as diar
from audio_utils import SAMPLE_RATE

DEFAULT_LOCAL_MODEL = os.getenv("ASR_LOCAL_MODEL", "small")
DEFAULT_LANGUAGE = os.getenv("ASR_LANGUAGE", "ru")

# Сегмент, у которого нет валидных слов, относится к реплике с макс. перекрытием
MIN_WORD_OVERLAP_SEC = 0.02


@dataclass
class Word:
    start: float
    end: float
    text: str


@dataclass
class Segment:
    start: float
    end: float
    text: str
    words: list[Word] = field(default_factory=list)
    speaker: str | None = None


@dataclass
class Transcript:
    segments: list[Segment]
    language: str
    duration: float
    backend: str
    model: str
    speakers: list[str] = field(default_factory=list)

    @property
    def text(self) -> str:
        return " ".join(s.text.strip() for s in self.segments if s.text.strip())

    def as_lines(self, with_speaker: bool = True) -> list[str]:
        lines = []
        for s in self.segments:
            ts = format_timecode(s.start)
            text = s.text.strip()
            if not text:
                continue
            lines.append(f"[{ts}] {s.speaker}: {text}" if with_speaker and s.speaker
                         else f"[{ts}] {text}")
        return lines

    def as_text(self, with_speaker: bool = True) -> str:
        return "\n".join(self.as_lines(with_speaker))

    def as_dict(self) -> dict:
        return {
            "backend": self.backend,
            "model": self.model,
            "language": self.language,
            "duration": round(self.duration, 2),
            "speakers": self.speakers,
            "segments": [
                {"start": round(s.start, 2), "end": round(s.end, 2),
                 "speaker": s.speaker, "text": s.text.strip()}
                for s in self.segments
            ],
        }


def format_timecode(seconds: float) -> str:
    total = int(round(max(0.0, seconds)))
    return f"{total // 60:02d}:{total % 60:02d}"


# ---------- Локальный ASR ----------

def transcribe_local(path: str, model_size: str | None = None,
                     language: str | None = None, device: str = "cpu",
                     compute_type: str = "int8", word_timestamps: bool = True,
                     on_progress=None, drop_hallucinations: bool = True) -> Transcript:
    """faster-whisper на CPU/GPU. Первый запуск скачивает модель в кэш HF.

    Галлюцинации (фразы на тишине, сегменты за концом записи) отбрасываются
    тем же фильтром, что и в asr.py, — иначе мусор доходит до протокола.
    """
    from faster_whisper import WhisperModel

    import asr as asr_gtz

    model_size = model_size or DEFAULT_LOCAL_MODEL
    audio = audio_utils.decode_audio(path, SAMPLE_RATE)
    duration = audio_utils.duration_seconds(audio)
    model = WhisperModel(model_size, device=device, compute_type=compute_type)
    segments_iter, info = model.transcribe(
        audio,
        language=language or DEFAULT_LANGUAGE,
        beam_size=5,
        vad_filter=True,
        word_timestamps=word_timestamps,
        condition_on_previous_text=False,
    )

    segments: list[Segment] = []
    for seg in segments_iter:
        if drop_hallucinations:
            dropped, _reason = asr_gtz.is_hallucination(seg, duration)
            if dropped:
                continue
        words = [
            Word(float(w.start), float(w.end), w.word)
            for w in (getattr(seg, "words", None) or [])
            if w.start is not None and w.end is not None
        ]
        segments.append(Segment(float(seg.start), float(seg.end), seg.text.strip(), words))
        if on_progress and segments:
            on_progress(min(1.0, segments[-1].end / max(duration, 1e-6)))

    return Transcript(
        segments=segments,
        language=getattr(info, "language", language or DEFAULT_LANGUAGE),
        duration=float(getattr(info, "duration", duration) or duration),
        backend="local",
        model=model_size,
    )


# ---------- AITunnel ASR ----------

def transcribe_aitunnel(path: str, model: str | None = None,
                        language: str | None = None,
                        base_url: str | None = None,
                        api_key: str | None = None) -> Transcript:
    """OpenAI-совместимый /audio/transcriptions (verbose_json, посегментно)."""
    import requests

    base_url = base_url or os.getenv("AITUNNEL_BASE_URL", "https://api.aitunnel.ru/v1")
    api_key = api_key or os.getenv("AITUNNEL_API_KEY")
    if not api_key:
        raise RuntimeError(
            "Для бэкенда aitunnel нужен AITUNNEL_API_KEY в .env. "
            "Без ключа используйте --backend local."
        )
    model = model or os.getenv("AITUNNEL_STT_MODEL", "whisper-1")

    with open(path, "rb") as f:
        r = requests.post(
            f"{base_url}/audio/transcriptions",
            headers={"Authorization": f"Bearer {api_key}"},
            files={"file": (Path(path).name, f)},
            data={
                "model": model,
                "response_format": "verbose_json",
                "timestamp_granularities[]": "segment",
                "language": language or DEFAULT_LANGUAGE,
            },
            timeout=900,
        )
    r.raise_for_status()
    data = r.json()

    segments = [
        Segment(float(s.get("start", 0)), float(s.get("end", 0)), (s.get("text") or "").strip())
        for s in (data.get("segments") or [])
    ]
    if not segments and data.get("text"):
        segments = [Segment(0.0, float(data.get("duration") or 0), data["text"].strip())]
    audio_duration = float(data.get("duration") or (segments[-1].end if segments else 0.0))

    return Transcript(
        segments=segments,
        language=data.get("language") or language or DEFAULT_LANGUAGE,
        duration=audio_duration,
        backend="aitunnel",
        model=model,
    )


def transcribe(path: str, backend: str = "local", model: str | None = None,
               **kwargs) -> Transcript:
    """Единая точка входа: `model` означает размер whisper для local и имя
    модели для aitunnel."""
    if backend == "local":
        return transcribe_local(path, model_size=model, **kwargs)
    if backend == "aitunnel":
        allowed = {k: v for k, v in kwargs.items()
                   if k in {"language", "base_url", "api_key"}}
        return transcribe_aitunnel(path, model=model, **allowed)
    raise ValueError(f"Неизвестный бэкенд ASR: {backend}")


# ---------- Назначение спикеров ----------

def _word_speaker(word: Word, turns: list[diar.Turn]) -> str | None:
    turn = diar.turn_at(turns, word.start, word.end)
    if turn is None:
        return None
    overlap = min(word.end, turn.end) - max(word.start, turn.start)
    if overlap < MIN_WORD_OVERLAP_SEC:
        return None
    return turn.speaker


def assign_speakers(transcript: Transcript, result: diar.DiarizationResult) -> Transcript:
    """Проставляет speaker каждому сегменту; сегменты режутся по смене спикера."""
    turns = result.turns
    if not turns:
        return transcript

    new_segments: list[Segment] = []
    for seg in transcript.segments:
        if seg.words:
            # группируем слова по спикеру, сохраняя порядок
            chunks: list[tuple[str | None, list[Word]]] = []
            for w in seg.words:
                spk = _word_speaker(w, turns)
                if chunks and chunks[-1][0] == spk:
                    chunks[-1][1].append(w)
                else:
                    chunks.append((spk, [w]))
            # спикер неизвестен у отдельных слов — тянем от соседей
            known = [spk for spk, _ in chunks if spk]
            fallback = known[0] if known else (diar.turn_at(turns, seg.start, seg.end) or diar.Turn(seg.start, seg.end, "")).speaker
            for spk, words in chunks:
                speaker = spk or fallback or None
                text = "".join(w.text for w in words).strip()
                if not text:
                    continue
                new_segments.append(Segment(words[0].start, words[-1].end, text, list(words), speaker))
        else:
            turn = diar.turn_at(turns, seg.start, seg.end)
            new_segments.append(Segment(seg.start, seg.end, seg.text,
                                        [], turn.speaker if turn else None))

    # склейка подряд идущих сегментов одного спикера
    merged: list[Segment] = []
    for seg in new_segments:
        if merged and merged[-1].speaker == seg.speaker and seg.start - merged[-1].end < 0.4:
            prev = merged[-1]
            prev.text = f"{prev.text} {seg.text}".strip()
            prev.end = max(prev.end, seg.end)
            prev.words = prev.words + seg.words
        else:
            merged.append(seg)

    transcript.segments = merged
    transcript.speakers = sorted({s.speaker for s in merged if s.speaker},
                                 key=lambda x: int(x.split()[-1]) if x.split()[-1].isdigit() else 0)
    return transcript


def organize_speaker_labels(result: diar.DiarizationResult,
                            transcript: Transcript) -> diar.DiarizationResult:
    """Переименовывает спикеров в порядке первого появления в транскрипте."""
    order: dict[str, str] = {}
    for seg in transcript.segments:
        if seg.speaker and seg.speaker not in order:
            order[seg.speaker] = f"Спикер {len(order) + 1}"
    if not order:
        return result
    for turn in result.turns:
        turn.speaker = order.get(turn.speaker, turn.speaker)
    for seg in transcript.segments:
        if seg.speaker:
            seg.speaker = order.get(seg.speaker, seg.speaker)
    return result


def run(path: str, backend: str = "local", num_speakers: int | None = None,
        max_speakers: int = 8, language: str | None = None,
        model: str | None = None, diarization: bool = True,
        embedding: str | None = None, on_progress=None
        ) -> tuple[Transcript, diar.DiarizationResult | None]:
    """Полный цикл: ASR -> диаризация -> транскрипт с репликами."""
    kwargs: dict = {"language": language}
    if model:
        kwargs["model"] = model
    if backend == "local" and on_progress:
        kwargs["on_progress"] = on_progress
    transcript = transcribe(path, backend=backend, **kwargs)

    if not diarization:
        transcript.speakers = []
        return transcript, None

    audio = audio_utils.decode_audio(path, SAMPLE_RATE)
    result = diar.diarize_array(audio, SAMPLE_RATE, num_speakers, max_speakers, embedding)
    assign_speakers(transcript, result)
    organize_speaker_labels(result, transcript)
    return transcript, result