"""Тесты ASR-слоя (speecher) и диаризации: без сетей и без скачивания моделей.

Проверяем то, что можно проверить детерминированно: форму сигнала, VAD,
кластеризацию, метки, метрику DER и логику выбора числа спикеров.
Нейросетевые эмбеддинги здесь не используются — только MFCC-бэкенд и
искусственные векторы.
"""
import numpy as np
import pytest

import speecher as asr
import audio_utils
import diarize
import embeddings


def tone(freq: float, seconds: float, sr: int = audio_utils.SAMPLE_RATE,
         amp: float = 0.3) -> np.ndarray:
    t = np.arange(int(seconds * sr)) / sr
    return (amp * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def silence(seconds: float, sr: int = audio_utils.SAMPLE_RATE) -> np.ndarray:
    return np.zeros(int(seconds * sr), dtype=np.float32)


# ---------- audio_utils ----------

def test_log_mel_shape():
    audio = tone(220, 1.0)
    mel = audio_utils.log_mel(audio)
    assert mel.ndim == 2
    assert mel.shape[1] == 40
    assert mel.shape[0] > 50


def test_mfcc_and_delta_shapes():
    audio = tone(220, 0.5)
    feat = audio_utils.mfcc(audio, n_mfcc=20)
    assert feat.shape[1] == 20
    d = audio_utils.delta(feat)
    assert d.shape == feat.shape


def test_delta_of_short_input_is_safe():
    feat = audio_utils.mfcc(tone(220, 0.05))
    assert feat.shape[0] >= 1
    assert audio_utils.delta(feat).shape == feat.shape


def test_is_audio():
    assert audio_utils.is_audio("a.MP3")
    assert audio_utils.is_audio("b.wav")
    assert not audio_utils.is_audio("c.txt")


def _has_ffmpeg() -> bool:
    try:
        audio_utils.ffmpeg_exe()
        return True
    except Exception:
        return False


@pytest.mark.skipif(not _has_ffmpeg(), reason="нужен ffmpeg (в CI ставится отдельно)")
def test_wav_roundtrip(tmp_path):
    audio = tone(440, 0.5)
    path = tmp_path / "x.wav"
    audio_utils.write_wav(path, audio)
    decoded = audio_utils.decode_audio(path)
    assert len(decoded) == pytest.approx(len(audio), abs=2000)
    assert np.max(np.abs(decoded)) < 1.0


def test_wav_written_without_ffmpeg(tmp_path):
    """Запись WAV работает на чистом stdlib — не зависит от ffmpeg."""
    import wave

    audio = tone(440, 0.25)
    path = tmp_path / "y.wav"
    audio_utils.write_wav(path, audio)
    with wave.open(str(path), "rb") as f:
        assert f.getframerate() == audio_utils.SAMPLE_RATE
        assert f.getnchannels() == 1
        assert f.getsampwidth() == 2
        assert f.getnframes() == len(audio)


# ---------- VAD ----------

def test_speech_regions_finds_two_bursts():
    audio = np.concatenate([silence(0.5), tone(220, 1.0), silence(0.8), tone(300, 0.7), silence(0.5)])
    regions, backend = diarize.speech_regions(audio)
    assert backend in {"silero", "energy"}
    assert len(regions) >= 2
    assert all(start < end for start, end in regions)
    assert regions[0][0] < regions[-1][1]


def test_speech_regions_empty_on_silence():
    regions, _ = diarize.speech_regions(silence(2.0))
    assert regions == []


# ---------- окна ----------

def test_windows_cover_region():
    wins = diarize.windows_for_region(1.0, 5.0)
    assert wins[0][0] == 1.0
    assert wins[-1][1] == 5.0
    # окна покрывают участок без разрывов и перекрываются
    for (_, prev_end), (next_start, _) in zip(wins, wins[1:]):
        assert next_start < prev_end
    # все окна, кроме последнего, ровно эталонной длины; последнее может быть
    # чуть шире, чтобы захватить короткий хвост участка
    for start, end in wins[:-1]:
        assert end - start == pytest.approx(diarize.WINDOW_SEC)
    assert wins[-1][1] - wins[-1][0] <= diarize.WINDOW_SEC + diarize.MIN_WINDOW_SEC + 1e-6


def test_short_region_is_one_window():
    assert diarize.windows_for_region(0.0, 0.5) == [(0.0, 0.5)]


# ---------- кластеризация ----------

def test_agglomerative_prefers_close_pairs():
    # два чётких кластера по три вектора
    a = np.array([[1.0, 0.0], [0.98, 0.02], [0.99, 0.01]])
    b = np.array([[0.0, 1.0], [0.02, 0.98], [0.01, 0.99]])
    emb = np.vstack([a, b])
    emb = emb / np.linalg.norm(emb, axis=1, keepdims=True)
    labels, k = diarize.cluster_windows(emb, num_speakers=2)
    assert k == 2
    assert labels[0] == labels[1] == labels[2]
    assert labels[3] == labels[4] == labels[5]
    assert labels[0] != labels[3]


def test_single_vector_is_one_speaker():
    labels, k = diarize.cluster_windows(np.array([[1.0, 0.0]]))
    assert k == 1
    assert labels.tolist() == [0]


def test_labels_from_history_respects_k():
    emb = np.array([[1.0, 0.0], [0.99, 0.01], [0.0, 1.0], [0.01, 0.99]], dtype=np.float32)
    emb /= np.linalg.norm(emb, axis=1, keepdims=True)
    cos = np.clip(emb @ emb.T, -1, 1)
    hist = diarize._agglomerative(cos, stop_clusters=1)
    assert len(diarize.labels_from_history(4, hist, 1)) == 4
    assert len(set(diarize.labels_from_history(4, hist, 2))) == 2


def test_silhouette_positive_for_separated_clusters():
    a = np.array([[1.0, 0.0], [0.99, 0.01], [0.98, 0.02]])
    b = np.array([[0.0, 1.0], [0.01, 0.99], [0.02, 0.98]])
    emb = np.vstack([a, b])
    emb /= np.linalg.norm(emb, axis=1, keepdims=True)
    cos = np.clip(emb @ emb.T, -1, 1)
    labels = np.array([0, 0, 0, 1, 1, 1])
    assert diarize._silhouette(cos, labels) > 0.5


def test_drop_small_clusters_merges_orphan():
    emb = np.array([[1.0, 0.0], [0.99, 0.01], [0.0, 1.0]], dtype=np.float32)
    emb /= np.linalg.norm(emb, axis=1, keepdims=True)
    labels = np.array([0, 0, 1])
    weights = np.array([2.0, 2.0, 0.01])
    merged = diarize.drop_small_clusters(labels, emb, weights)
    assert len(set(merged.tolist())) == 1


# ---------- метрики ----------

def test_der_perfect_match_is_zero():
    truth = [(0.0, 2.0, "A"), (2.0, 4.0, "B")]
    hyp = [diarize.Turn(0.0, 2.0, "Спикер 1"), diarize.Turn(2.0, 4.0, "Спикер 2")]
    assert diarize.der(truth, hyp)["der"] == pytest.approx(0.0, abs=1e-3)


def test_der_counts_confusion():
    truth = [(0.0, 2.0, "A"), (2.0, 4.0, "B")]
    hyp = [diarize.Turn(0.0, 4.0, "Спикер 1")]
    result = diarize.der(truth, hyp)
    assert result["der"] > 0.4


def test_der_detects_missing_speech():
    truth = [(0.0, 2.0, "A")]
    hyp = []
    result = diarize.der(truth, hyp)
    assert result["miss"] > 0
    assert result["der"] == pytest.approx(1.0, abs=0.05)


# ---------- реплики ----------

def test_merge_adjacent_closes_short_gaps():
    turns = [diarize.Turn(0.0, 1.0, "A"), diarize.Turn(1.1, 2.0, "A")]
    merged = diarize._merge_adjacent(turns)
    assert len(merged) == 1
    assert merged[0].end == 2.0


def test_smooth_removes_short_interjection():
    turns = [
        diarize.Turn(0.0, 2.0, "A"),
        diarize.Turn(2.0, 2.1, "B"),
        diarize.Turn(2.1, 4.0, "A"),
    ]
    out = diarize._smooth(turns)
    assert len(out) == 1
    assert out[0].speaker == "A"


def test_names_follow_first_appearance():
    turns = [diarize.Turn(0.0, 1.0, "c2"), diarize.Turn(1.0, 2.0, "c0")]
    named = diarize._name_speakers(turns, np.array([2, 0]))
    assert named[0].speaker == "Спикер 1"
    assert named[1].speaker == "Спикер 2"


def test_turn_at_picks_max_overlap():
    turns = [diarize.Turn(0.0, 1.0, "A"), diarize.Turn(1.0, 5.0, "B")]
    assert diarize.turn_at(turns, 0.8, 2.0).speaker == "B"


def test_rttm_format():
    result = diarize.DiarizationResult(
        turns=[diarize.Turn(0.0, 1.5, "Спикер 1")], num_speakers=1, duration=1.5
    )
    line = result.to_rttm("rec")
    assert line.startswith("SPEAKER rec 1 0.000 1.500")
    assert "Спикер 1" in line


# ---------- диаризация целиком на MFCC ----------

def test_diarize_two_tones_gives_two_speakers():
    """Два разных тона как два «голоса» — проверяем связность пайплайна."""
    sr = audio_utils.SAMPLE_RATE
    audio = np.concatenate([
        tone(160, 1.2), silence(0.5), tone(1500, 1.2), silence(0.5), tone(160, 1.2),
    ])
    result = diarize.diarize_array(audio, sr, backend="mfcc")
    assert result.num_speakers >= 1
    assert result.turns
    assert result.backend.startswith("mfcc/")
    assert all(t.end > t.start for t in result.turns)


def test_diarize_empty_audio_is_safe():
    result = diarize.diarize_array(silence(1.0), backend="mfcc")
    assert result.num_speakers == 0
    assert result.turns == []


def test_result_serializes_to_json():
    result = diarize.diarize_array(
        np.concatenate([tone(160, 1.0), silence(0.4), tone(1500, 1.0)]), backend="mfcc"
    )
    payload = result.as_dict()
    assert "turns" in payload and "num_speakers" in payload
    for turn in payload["turns"]:
        assert set(turn) == {"start", "end", "speaker"}


# ---------- выбор бэкенда ----------

def test_make_embedder_returns_mfcc_on_request():
    assert embeddings.make_embedder("mfcc").name == "mfcc"


def test_unknown_embedding_falls_back_to_mfcc():
    assert embeddings.make_embedder("nonexistent").name == "mfcc"


def test_available_backends_always_has_mfcc():
    assert embeddings.available_backends()["mfcc"] is True


# ---------- транскрипт ----------

def test_transcript_lines_format():
    segments = [
        asr.Segment(0.0, 2.0, "привет", [], "Спикер 1"),
        asr.Segment(2.0, 4.0, "да", [], "Спикер 2"),
    ]
    tr = asr.Transcript(segments, "ru", 4.0, "local", "tiny")
    lines = tr.as_lines()
    assert lines[0] == "[00:00] Спикер 1: привет"
    assert lines[1] == "[00:02] Спикер 2: да"


def test_transcript_without_speaker_labels():
    tr = asr.Transcript([asr.Segment(0.0, 1.0, "текст")], "ru", 1.0, "local", "tiny")
    assert tr.as_lines(with_speaker=True)[0] == "[00:00] текст"


def test_timecode_formatting():
    assert asr.format_timecode(0) == "00:00"
    assert asr.format_timecode(65.4) == "01:05"
    assert asr.format_timecode(3600) == "60:00"


def test_assign_speakers_splits_on_voice_change():
    segments = [asr.Segment(0.0, 3.0, "первый и второй", [
        asr.Word(0.0, 1.4, "первый"),
        asr.Word(1.6, 3.0, "второй"),
    ])]
    transcript = asr.Transcript(segments, "ru", 3.0, "local", "tiny")
    result = diarize.DiarizationResult(
        turns=[diarize.Turn(0.0, 1.5, "A"), diarize.Turn(1.5, 3.0, "B")],
        num_speakers=2, duration=3.0,
    )
    out = asr.assign_speakers(transcript, result)
    assert len(out.segments) == 2
    assert out.segments[0].speaker != out.segments[1].speaker


def test_assign_speakers_without_turns_keeps_text():
    tr = asr.Transcript([asr.Segment(0.0, 1.0, "текст")], "ru", 1.0, "local", "tiny")
    result = diarize.DiarizationResult([], 0, 1.0)
    out = asr.assign_speakers(tr, result)
    assert out.segments[0].text == "текст"
    assert out.segments[0].speaker is None