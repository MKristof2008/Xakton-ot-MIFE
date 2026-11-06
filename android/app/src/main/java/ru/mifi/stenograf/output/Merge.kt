package ru.mifi.stenograf.output

/**
 * Слияние ASR-сегментов с интервалами диаризации и приведение к схеме ТЗ.
 *
 * Портировано из Python-эталона репозитория:
 *  - diarization.py::_best_speaker — спикер по максимальному пересечению
 *  - output.py::merge_adjacent — склейка соседних реплик одного спикера
 *  - output.py::normalize_speakers — нумерация speaker_N по первому появлению
 */
object Merge {

    /** Пауза, до которой соседние реплики одного спикера склеиваются (output.py). */
    private const val MAX_GAP_MS = 300

    /** Максимальная длина склеенной реплики (output.py). */
    private const val MAX_LEN_MS = 15_000

    /** Минимальная длительность куска при разрезании по смене говорящего. */
    private const val MIN_PIECE_MS = 150

    /**
     * Склеивает соседние turn-ы одного говорящего в одну реплику.
     *
     * Зачем: диаризация режет речь на мелкие куски (по 1–3 с), а распознавать
     * выгоднее целыми фразами — Whisper на обрывке теряет контекст и точность.
     * Куски одного человека, разделённые паузой не больше [maxGapMs], — это
     * одна реплика. Длинные реплики дробим, чтобы не выйти за окно модели.
     */
    fun groupTurns(
        turns: List<SpeakerTurn>,
        maxGapMs: Int = 700,
        maxLenMs: Int = 25_000,
    ): List<SpeakerTurn> {
        if (turns.isEmpty()) return emptyList()
        val sorted = turns.sortedBy { it.startMs }
        val out = mutableListOf<SpeakerTurn>()
        var cur = sorted.first()

        for (t in sorted.drop(1)) {
            val sameSpeaker = t.speakerRaw == cur.speakerRaw
            val gap = t.startMs - cur.endMs
            val mergedLen = t.endMs - cur.startMs
            if (sameSpeaker && gap <= maxGapMs && mergedLen <= maxLenMs) {
                cur = cur.copy(endMs = maxOf(cur.endMs, t.endMs))
            } else {
                out += cur
                cur = t
            }
        }
        out += cur

        // Длинные реплики режем поровну: распознавание идёт окнами модели.
        return out.flatMap { u ->
            val len = u.endMs - u.startMs
            if (len <= maxLenMs) listOf(u)
            else {
                val parts = (len + maxLenMs - 1) / maxLenMs
                (0 until parts).map { i ->
                    val s = u.startMs + len * i / parts
                    val e = u.startMs + len * (i + 1) / parts
                    SpeakerTurn(s, e, u.speakerRaw)
                }
            }
        }.filter { it.endMs > it.startMs }
    }

    /**
     * Сборка финальных реплик из уже распознанных по говорящим отрезков.
     * Текст каждой пары получен из своей реплики, поэтому делить слова между
     * спикерами не нужно — это и есть главное отличие от [build].
     */
    fun buildFromRecognized(
        recognized: List<Pair<SpeakerTurn, List<AsrSegment>>>,
    ): List<Replica> {
        val items = mutableListOf<Pair<AsrSegment, String>>()
        for ((turn, segments) in recognized.sortedBy { it.first.startMs }) {
            for (seg in segments) {
                val text = seg.text.trim()
                if (text.isEmpty()) continue
                items += AsrSegment(seg.startMs, seg.endMs, text) to turn.speakerRaw
            }
        }
        val normalized = normalizeSpeakers(items)
        return mergeAdjacent(normalized).filter { it.text.isNotBlank() && it.endMs > it.startMs }
    }

    /**
     * Сегмент ASR может накрывать двух говорящих — режем его по границам turn-ов.
     * Без этого точность меток падает: реплика приписывается одному человеку целиком.
     */
    fun splitByTurns(asr: AsrSegment, turns: List<SpeakerTurn>): List<Pair<AsrSegment, String>> {
        if (turns.isEmpty()) return listOf(asr to "unknown")

        val cuts = mutableListOf<Pair<Int, String>>() // (граница, новый спикер)
        for (t in turns) {
            if (t.startMs > asr.startMs && t.startMs < asr.endMs) cuts += t.startMs to t.speakerRaw
        }
        if (cuts.isEmpty()) return listOf(asr to bestSpeaker(asr.startMs, asr.endMs, turns))

        val bounds = mutableListOf(asr.startMs)
        bounds += cuts.map { it.first }
        bounds += asr.endMs

        val out = mutableListOf<Pair<AsrSegment, String>>()
        for (i in 0 until bounds.size - 1) {
            val s = bounds[i]
            val e = bounds[i + 1]
            if (e - s < MIN_PIECE_MS) continue
            out += AsrSegment(s, e, asr.text) to bestSpeaker(s, e, turns)
        }
        return out.ifEmpty { listOf(asr to bestSpeaker(asr.startMs, asr.endMs, turns)) }
    }

    /** Спикер с максимальным пересечением по времени; иначе — ближайший по середине. */
    fun bestSpeaker(startMs: Int, endMs: Int, turns: List<SpeakerTurn>): String {
        var bestSpeaker: String? = null
        var bestOverlap = 0
        for (t in turns) {
            val overlap = minOf(endMs, t.endMs) - maxOf(startMs, t.startMs)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestSpeaker = t.speakerRaw
            }
        }
        if (bestSpeaker != null) return bestSpeaker
        val mid = (startMs + endMs) / 2
        return turns.minByOrNull { minOf(kotlin.math.abs(mid - it.startMs), kotlin.math.abs(mid - it.endMs)) }
            ?.speakerRaw ?: "unknown"
    }

    /**
     * Полный проход: слияние + нормализация + склейка. Возвращает реплики в формате ТЗ.
     * Текст одного сегмента, разрезанного на двух говорящих, делится по словам поровну —
     * это эвристика, но она не выдумывает слов и сохраняет их порядок.
     */
    fun build(asr: List<AsrSegment>, turns: List<SpeakerTurn>): List<Replica> {
        val withSpeaker = mutableListOf<Pair<AsrSegment, String>>()

        for (seg in asr) {
            val pieces = splitByTurns(seg, turns)
            if (pieces.size == 1) {
                withSpeaker += pieces
                continue
            }
            // Текст разрезанного сегмента делим по словам пропорционально длительности.
            // Группируем строго внутри одного сегмента ASR: соседние сегменты с
            // совпадающими границами — это разные фразы, их склеивать в один текст нельзя.
            val words = seg.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            val totalMs = pieces.sumOf { (it.first.endMs - it.first.startMs).coerceAtLeast(1) }
            var taken = 0
            for ((idx, piece) in pieces.withIndex()) {
                val (pseg, spk) = piece
                val share = if (idx == pieces.lastIndex) {
                    words.size - taken
                } else {
                    val ms = (pseg.endMs - pseg.startMs).coerceAtLeast(1)
                    (ms.toLong() * words.size / totalMs).toInt().coerceAtLeast(0)
                }
                val to = (taken + share).coerceAtMost(words.size)
                withSpeaker += AsrSegment(pseg.startMs, pseg.endMs, words.subList(taken, to).joinToString(" ")) to spk
                taken = to
            }
        }

        val normalized = normalizeSpeakers(withSpeaker)
        val merged = mergeAdjacent(normalized)
        return merged.filter { it.text.isNotBlank() && it.endMs > it.startMs }
    }

    /** speaker_1, speaker_2, … в порядке первого появления (output.py::normalize_speakers). */
    private fun normalizeSpeakers(items: List<Pair<AsrSegment, String>>): List<Replica> {
        val mapping = LinkedHashMap<String, String>()
        return items.map { (seg, raw) ->
            val id = mapping.getOrPut(raw) { "speaker_${mapping.size + 1}" }
            Replica(id, seg.startMs, seg.endMs, seg.text.trim())
        }
    }

    /**
     * Склейка подряд идущих реплик одного спикера с маленькой паузой.
     * Возвращает исходный список, если склейка не нужна — не мутируем на месте.
     */
    private fun mergeAdjacent(reps: List<Replica>): List<Replica> {
        if (reps.isEmpty()) return reps
        val out = mutableListOf(reps.first())
        for (rep in reps.drop(1)) {
            val last = out.last()
            val gap = rep.startMs - last.endMs
            val newLen = rep.endMs - last.startMs
            if (rep.speakerId == last.speakerId && gap in 0..MAX_GAP_MS && newLen <= MAX_LEN_MS) {
                out[out.lastIndex] = last.copy(
                    endMs = rep.endMs,
                    text = (last.text.trimEnd() + " " + rep.text.trimStart()).trim(),
                )
            } else {
                out += rep
            }
        }
        return out
    }
}