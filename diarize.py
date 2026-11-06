"""Диаризация: кто говорил когда.

Этапы:
1. VAD (Silero из faster-whisper, иначе энергетический) -> участки речи
2. Окна 1.5 с внутри участков -> эмбеддинги говорящего (см. embeddings.py)
3. Агломеративная кластеризация (average linkage, косинус); число спикеров
   задаётся аргументом либо выбирается по силуэту
4. Окна -> реплики: голосование по участкам речи, сглаживание вставок

Зависимости: numpy; для Silero VAD и ECAPA — опциональные пакеты.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field

import numpy as np

import audio_utils
import embeddings
from audio_utils import SAMPLE_RATE

# Окна для эмбеддингов
WINDOW_SEC = 1.5
WINDOW_HOP_SEC = 0.75
MIN_WINDOW_SEC = 0.30
MAX_WINDOWS = 4000

# Склейка и сглаживание реплик
MIN_GAP_SEC = 0.20        # пауза короче — реплики одного спикера склеиваются
SMOOTH_MIN_SEC = 0.35     # вставка чужого спикера короче — переголосовать
MIN_CLUSTER_SPEECH = 1.0  # кластер с меньшим объёмом речи — выброс
SMOOTH_PASSES = 10
# Допуск силуэта при выборе числа спикеров: предпочитаем меньшее k,
# если качество разбиения отличается незначительно.
SILHOUETTE_TOLERANCE = 0.05
# Ниже этого силуэта разбиение считаем неотчётливым и объявляем монолог.
MIN_SILHOUETTE = 0.15


@dataclass
class Turn:
    start: float
    end: float
    speaker: str

    @property
    def duration(self) -> float:
        return max(0.0, self.end - self.start)

    def as_dict(self) -> dict:
        return {"start": round(self.start, 2), "end": round(self.end, 2),
                "speaker": self.speaker}


@dataclass
class DiarizationResult:
    turns: list[Turn]
    num_speakers: int
    duration: float
    backend: str = "mfcc"
    speech_seconds: float = 0.0
    clusters: list[dict] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "num_speakers": self.num_speakers,
            "backend": self.backend,
            "duration": round(self.duration, 2),
            "speech_seconds": round(self.speech_seconds, 2),
            "clusters": self.clusters,
            "turns": [t.as_dict() for t in self.turns],
        }

    def to_rttm(self, uri: str = "audio") -> str:
        return "\n".join(
            f"SPEAKER {uri} 1 {t.start:.3f} {t.duration:.3f} <NA> <NA> {t.speaker} <NA> <NA>"
            for t in self.turns
        )

    def speaker_totals(self) -> dict[str, float]:
        totals: dict[str, float] = {}
        for t in self.turns:
            totals[t.speaker] = totals.get(t.speaker, 0.0) + t.duration
        return dict(sorted(totals.items(), key=lambda kv: -kv[1]))


# ---------- 1. VAD ----------

def _energy_vad(audio: np.ndarray, sr: int = SAMPLE_RATE) -> list[tuple[float, float]]:
    """Энергетический VAD: кадры 30 мс, адаптивный порог по громким кадрам."""
    win = int(0.03 * sr)
    hop = int(0.01 * sr)
    if len(audio) < win:
        return []
    db = 20.0 * np.log10(np.maximum(np.abs(audio), 1e-8))
    n = 1 + (len(db) - win) // hop
    frames = np.lib.stride_tricks.as_strided(
        db, shape=(n, win), strides=(db.strides[0] * hop, db.strides[0])
    )
    energy = frames.mean(axis=1)
    loud = energy[energy > np.percentile(energy, 60)]
    if loud.size == 0:
        return []
    threshold = max(np.median(loud) - 18.0, float(np.percentile(energy, 30)) + 3.0)

    regions: list[tuple[float, float]] = []
    start = None
    for i, voiced in enumerate(energy > threshold):
        if voiced and start is None:
            start = i
        elif not voiced and start is not None:
            regions.append((start * hop / sr, i * hop / sr))
            start = None
    if start is not None:
        regions.append((start * hop / sr, len(energy) * hop / sr))
    return regions


def _silero_vad(audio: np.ndarray, sr: int = SAMPLE_RATE) -> list[tuple[float, float]] | None:
    """Silero VAD из faster-whisper. None, если пакета нет."""
    try:
        from faster_whisper.vad import VadOptions, get_speech_timestamps
    except Exception:
        return None
    if sr != SAMPLE_RATE:
        return None
    try:
        chunks = get_speech_timestamps(
            audio,
            VadOptions(min_silence_duration_ms=300, speech_pad_ms=100),
            sampling_rate=SAMPLE_RATE,
        )
    except Exception:
        return None
    return [(c["start"] / SAMPLE_RATE, c["end"] / SAMPLE_RATE) for c in chunks]


def speech_regions(audio: np.ndarray,
                   sr: int = SAMPLE_RATE) -> tuple[list[tuple[float, float]], str]:
    """Участки речи (секунды) и имя использованного VAD."""
    regions = _silero_vad(audio, sr)
    backend = "silero"
    if not regions:
        regions = _energy_vad(audio, sr)
        backend = "energy"

    cleaned: list[tuple[float, float]] = []
    for start, end in sorted(regions):
        if end - start < 0.10:
            continue
        if cleaned and start - cleaned[-1][1] < MIN_GAP_SEC:
            cleaned[-1] = (cleaned[-1][0], max(cleaned[-1][1], end))
        else:
            cleaned.append((start, end))
    return cleaned, backend


# ---------- 2. Окна ----------

def windows_for_region(start: float, end: float) -> list[tuple[float, float]]:
    """Нарезает участок речи на перекрывающиеся окна фиксированной длины.

    Хвост участка не теряется: короткий остаток дописывается к последнему
    окну, длинный — становится отдельным окном.
    """
    if end - start <= WINDOW_SEC:
        return [(start, end)]
    out = []
    t = start
    while t + WINDOW_SEC <= end + 1e-6:
        out.append((t, t + WINDOW_SEC))
        t += WINDOW_HOP_SEC

    tail = end - out[-1][1]
    if tail > 1e-6:
        if tail <= MIN_WINDOW_SEC:
            out[-1] = (out[-1][0], end)
        else:
            out.append((end - WINDOW_SEC, end))
    return out


# ---------- 3. Кластеризация ----------

def _agglomerative(cossim: np.ndarray, stop_clusters: int) -> list[tuple[int, int, float]]:
    """Average-linkage по косинусной близости. Возвращает историю слияний.

    Слияние — (слот_a, слот_b, расстояние): кластер из слота b вливается в a.
    Разбиение на любом шаге восстанавливается как компоненты связности уже
    сделанных слияний, поэтому историю можно обрезать на любом k.
    """
    n = cossim.shape[0]
    if n <= stop_clusters:
        return []
    dist = 1.0 - cossim.astype(np.float64)
    np.fill_diagonal(dist, np.inf)
    size = np.ones(n, dtype=np.float64)
    history: list[tuple[int, int, float]] = []

    for _ in range(n - stop_clusters):
        i, j = divmod(int(np.argmin(dist)), n)
        if i > j:
            i, j = j, i
        history.append((i, j, float(dist[i, j])))

        row = (size[i] * dist[i, :] + size[j] * dist[j, :]) / (size[i] + size[j])
        dist[i, :] = row
        dist[:, i] = row
        dist[i, i] = np.inf
        dist[j, :] = np.inf
        dist[:, j] = np.inf
        size[i] += size[j]
    return history


def labels_from_history(n: int, history: list[tuple[int, int, float]], k: int) -> np.ndarray:
    """Метки кластеров, если остановить агломерацию на k кластерах."""
    parent = list(range(n))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for idx, (a, b, _d) in enumerate(history):
        if n - idx <= k:
            break
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra

    raw = np.array([find(i) for i in range(n)])
    uniq, counts = np.unique(raw, return_counts=True)
    remap = {int(o): i for i, o in enumerate(uniq[np.argsort(-counts)])}
    return np.array([remap[int(v)] for v in raw])


def _silhouette(cossim: np.ndarray, labels: np.ndarray) -> float:
    """Средний силуэт по косинусному расстоянию."""
    uniq = np.unique(labels)
    if len(uniq) < 2:
        return -1.0
    dist = 1.0 - cossim
    scores = []
    for lab in uniq:
        idx = np.where(labels == lab)[0]
        if len(idx) < 2:
            continue
        a = dist[np.ix_(idx, idx)].sum(axis=1) / (len(idx) - 1)
        b = np.min(
            [dist[np.ix_(idx, np.where(labels == u)[0])].mean(axis=1)
             for u in uniq if u != lab],
            axis=0,
        )
        scores.append(float(np.mean((b - a) / np.maximum(np.maximum(a, b), 1e-9))))
    return float(np.mean(scores)) if scores else -1.0


def cluster_windows(emb: np.ndarray, num_speakers: int | None = None,
                    max_speakers: int = 8,
                    weights: np.ndarray | None = None,
                    tolerance: float = SILHOUETTE_TOLERANCE) -> tuple[np.ndarray, int]:
    """Эмбеддинги окон -> метки спикеров (0..K-1).

    При известном K разбиение берётся прямо из истории слияний. Иначе K
    выбирается по силуэту, но с двумя оговорками: дробление одного голоса
    силуэт поощряет, поэтому (1) побеждает наименьшее k в пределах допуска
    tolerance и (2) если лучшее разбиение всё равно неотчётливое (ниже
    MIN_SILHOUETTE), запись считается монологом.
    """
    n = emb.shape[0]
    if n == 0:
        return np.zeros(0, dtype=int), 0
    if n == 1:
        return np.zeros(1, dtype=int), 1

    cossim = np.clip(emb @ emb.T, -1.0, 1.0)
    if weights is None:
        weights = np.ones(n, dtype=np.float64)

    if num_speakers:
        k_target = max(1, min(int(num_speakers), n))
        if k_target == 1:
            return np.zeros(n, dtype=int), 1
        history = _agglomerative(cossim, stop_clusters=k_target)
        labels = labels_from_history(n, history, k_target)
        labels = drop_small_clusters(labels, emb, weights)
        return labels, int(len(np.unique(labels)))

    upper = max(2, min(max_speakers, n))
    history = _agglomerative(cossim, stop_clusters=1)

    # Монолог: второго голоса нет, если уже при k=2 один кластер оказывается
    # ничтожным по объёму речи и растворяется в первом.
    probe = labels_from_history(n, history, 2)
    if len(np.unique(drop_small_clusters(probe, emb, weights))) < 2:
        return np.zeros(n, dtype=int), 1

    scored: list[tuple[int, float, np.ndarray]] = []
    for k in range(2, upper + 1):
        labels = labels_from_history(n, history, k)
        # вырожденные разбиения (лишний кластер-сирота) отбрасываем сразу
        if len(np.unique(labels)) < k:
            continue
        labels = drop_small_clusters(labels, emb, weights)
        if len(np.unique(labels)) < 2:
            continue
        scored.append((k, _silhouette(cossim, labels), labels))

    if not scored:
        return np.zeros(n, dtype=int), 1

    best_score = max(score for _, score, _ in scored)
    if best_score < MIN_SILHOUETTE:
        return np.zeros(n, dtype=int), 1

    for _k, score, labels in scored:  # по возрастанию k
        if score >= best_score - tolerance:
            return labels, int(len(np.unique(labels)))
    best = max(scored, key=lambda item: item[1])
    return best[2], int(len(np.unique(best[2])))


def drop_small_clusters(labels: np.ndarray, emb: np.ndarray,
                        weights: np.ndarray) -> np.ndarray:
    """Кластеры с ничтожным объёмом речи сливаем с ближайшим крупным."""
    labels = labels.copy()
    totals: dict[int, float] = {}
    for lab, w in zip(labels, weights):
        totals[int(lab)] = totals.get(int(lab), 0.0) + float(w)

    target = max(MIN_CLUSTER_SPEECH, 0.02 * float(np.sum(weights)))
    major = [lab for lab, total in totals.items() if total >= target]
    if not major:
        major = [max(totals, key=totals.get)]

    centroids = {}
    for lab in major:
        c = emb[labels == lab].mean(axis=0)
        centroids[lab] = c / (np.linalg.norm(c) + 1e-9)

    for lab, total in totals.items():
        if total >= target:
            continue
        idx = np.where(labels == lab)[0]
        if idx.size == 0:
            continue
        centroid = emb[idx].mean(axis=0)
        nearest = max(major, key=lambda m: float(centroid @ centroids[m]))
        labels[idx] = nearest

    uniq = np.unique(labels)
    remap = {int(u): i for i, u in enumerate(uniq)}
    return np.array([remap[int(v)] for v in labels])


# ---------- 4. Окна -> реплики ----------

def _merge_adjacent(turns: list[Turn]) -> list[Turn]:
    """Склеивает соседние реплики одного спикера, закрывая короткие паузы."""
    merged: list[Turn] = []
    for turn in turns:
        if (merged and turn.speaker == merged[-1].speaker
                and turn.start - merged[-1].end <= MIN_GAP_SEC):
            merged[-1].end = max(merged[-1].end, turn.end)
        else:
            merged.append(Turn(turn.start, turn.end, turn.speaker))
    return merged


def _smooth(turns: list[Turn]) -> list[Turn]:
    """Переголосовывает короткие вставки в пользу окружения того же спикера."""
    out = [Turn(t.start, t.end, t.speaker) for t in turns]
    for _ in range(SMOOTH_PASSES):
        changed = False
        for i in range(1, len(out) - 1):
            cur = out[i]
            if cur.duration >= SMOOTH_MIN_SEC:
                continue
            if out[i - 1].speaker == out[i + 1].speaker != cur.speaker:
                cur.speaker = out[i - 1].speaker
                changed = True
        out = _merge_adjacent(out)
        if not changed:
            break
    return out


def _name_speakers(turns: list[Turn], labels: np.ndarray) -> list[Turn]:
    """Имена по порядку первого появления: Спикер 1, Спикер 2, ..."""
    order: dict[int, str] = {}
    for lab in labels:
        if int(lab) not in order:
            order[int(lab)] = f"Спикер {len(order) + 1}"
    return [Turn(t.start, t.end, order[int(lab)]) for t, lab in zip(turns, labels)]


def _region_speakers(regions: list[tuple[float, float]], window_region: list[int],
                     labels: np.ndarray, weights: np.ndarray) -> list[int]:
    """Класс участка речи — по взвешенному голосованию его окон."""
    votes: dict[int, dict[int, float]] = {}
    for ri, lab, w in zip(window_region, labels, weights):
        votes.setdefault(ri, {})
        votes[ri][int(lab)] = votes[ri].get(int(lab), 0.0) + float(w)
    return [max(votes[ri], key=votes[ri].get) if ri in votes else -1
            for ri in range(len(regions))]


# ---------- Публичный API ----------

def diarize_array(audio: np.ndarray, sr: int = SAMPLE_RATE,
                  num_speakers: int | None = None, max_speakers: int = 8,
                  backend: str | None = None) -> DiarizationResult:
    """Диаризация сигнала (моно, sr)."""
    duration = audio_utils.duration_seconds(audio, sr)
    regions, vad_backend = speech_regions(audio, sr)
    if not regions:
        return DiarizationResult([], 0, duration, f"no-vad/{vad_backend}")

    windows: list[tuple[float, float]] = []
    window_region: list[int] = []
    for ri, (start, end) in enumerate(regions):
        for w in windows_for_region(start, end):
            windows.append(w)
            window_region.append(ri)

    if len(windows) > MAX_WINDOWS:
        step = int(np.ceil(len(windows) / MAX_WINDOWS))
        keep = list(range(0, len(windows), step))
        windows = [windows[i] for i in keep]
        window_region = [window_region[i] for i in keep]

    embedder = embeddings.make_embedder(backend, sr)
    embedder.prepare(audio)
    emb = embedder.embed(audio, windows)
    weights = np.array([max(MIN_WINDOW_SEC, e - s) for s, e in windows], dtype=np.float64)

    labels, k = cluster_windows(emb, num_speakers, max_speakers, weights=weights,
                                tolerance=getattr(embedder, "silhouette_tolerance",
                                                  SILHOUETTE_TOLERANCE))
    if k == 0:
        return DiarizationResult([], 0, duration, f"{embedder.name}/{vad_backend}")

    region_labels = _region_speakers(regions, window_region, labels, weights)
    turns = [Turn(s, e, f"c{lab}") for (s, e), lab in zip(regions, region_labels) if lab >= 0]
    turns = _smooth(turns)

    turn_labels = np.array([int(t.speaker[1:]) for t in turns])
    turns = _name_speakers(turns, turn_labels)

    clusters = []
    for lab in sorted(set(turn_labels)):
        total = sum(t.duration for t, l in zip(turns, turn_labels) if l == lab)
        clusters.append({"label": f"c{lab}", "speech_seconds": round(total, 2)})

    return DiarizationResult(
        turns=turns,
        num_speakers=len({t.speaker for t in turns}),
        duration=duration,
        backend=f"{embedder.name}/{vad_backend}",
        speech_seconds=round(sum(t.duration for t in turns), 2),
        clusters=clusters,
    )


def diarize(path: str, num_speakers: int | None = None, max_speakers: int = 8,
            backend: str | None = None) -> DiarizationResult:
    """Диаризация файла (любой формат, который читает ffmpeg)."""
    audio = audio_utils.decode_audio(path, SAMPLE_RATE)
    return diarize_array(audio, SAMPLE_RATE, num_speakers, max_speakers, backend)


def turn_at(turns: list[Turn], start: float, end: float) -> Turn | None:
    """Реплика с максимальным перекрытием [start, end]."""
    best, best_overlap = None, 0.0
    for turn in turns:
        overlap = min(end, turn.end) - max(start, turn.start)
        if overlap > best_overlap:
            best, best_overlap = turn, overlap
    return best


# ---------- Метрики качества ----------

def der(reference: list[tuple[float, float, str]], hypothesis: list[Turn],
        collar: float = 0.25, step: float = 0.01) -> dict:
    """Diarization Error Rate: доля времени с неверным числом/меткой спикера.

    Считается по сетке с шагом step; стыки длиной collar игнорируются,
    метки сопоставляются жадной перестановкой (оптимальной для 1-2 спикеров).
    """
    if not reference:
        return {"der": 0.0, "miss": 0.0, "false_alarm": 0.0, "confusion": 0.0}

    end_time = max([e for _, e, _ in reference] + [t.end for t in hypothesis] + [0.0])
    grid = np.arange(0.0, end_time, step)
    ref_labels = np.full(len(grid), "", dtype=object)
    for start, end, spk in reference:
        ref_labels[(grid >= start) & (grid < end)] = spk
    hyp_labels = np.full(len(grid), "", dtype=object)
    for turn in hypothesis:
        hyp_labels[(grid >= turn.start) & (grid < turn.end)] = turn.speaker

    # исключаем стыки реплик ±collar
    ignore = np.zeros(len(grid), dtype=bool)
    for start, end, _ in reference:
        ignore |= (grid >= start - collar) & (grid < start + collar)
        ignore |= (grid >= end - collar) & (grid < end + collar)
    for turn in hypothesis:
        ignore |= (grid >= turn.start - collar) & (grid < turn.start + collar)
        ignore |= (grid >= turn.end - collar) & (grid < turn.end + collar)

    scored = ~ignore
    total_ref = max(1, int(np.sum(ref_labels[scored] != "")))
    miss = int(np.sum((ref_labels[scored] != "") & (hyp_labels[scored] == "")))
    false_alarm = int(np.sum((ref_labels[scored] == "") & (hyp_labels[scored] != "")))

    # лучшая перестановка меток по совпадениям
    ref_set = sorted({r for r in ref_labels[scored] if r})
    hyp_set = sorted({h for h in hyp_labels[scored] if h})
    confusion = 0
    both = (ref_labels[scored] != "") & (hyp_labels[scored] != "")
    if both.any() and ref_set and hyp_set:
        counts = np.zeros((len(ref_set), len(hyp_set)), dtype=int)
        for ri, r in enumerate(ref_set):
            for hi, h in enumerate(hyp_set):
                counts[ri, hi] = int(np.sum(both & (ref_labels[scored] == r) & (hyp_labels[scored] == h)))
        from itertools import permutations

        best = None
        for perm in permutations(range(max(len(ref_set), len(hyp_set)))):
            score = sum(counts[ri, perm[ri]] for ri in range(len(ref_set))
                        if perm[ri] < len(hyp_set))
            if best is None or score > best:
                best = score
        confusion = int(np.sum(both)) - int(best or 0)

    return {
        "der": round((miss + false_alarm + confusion) / total_ref, 4),
        "miss": miss, "false_alarm": false_alarm, "confusion": confusion,
        "ref_seconds": round(total_ref * step, 2),
    }