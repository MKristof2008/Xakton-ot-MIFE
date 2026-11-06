package ru.hexteam.stenograph.core

/**
 * Сшивка результатов ASR и диаризации. Контракт `merge(segments, speakers)`.
 *
 * Правила:
 *  - сегмент ASR получает говорящего с максимальным пересечением по времени;
 *  - если пересечения нет — говорящий неизвестен, идентификатор `speaker_unknown`;
 *  - идентификаторы нормализуются к виду `speaker_N`.
 */
object Merge {

    const val UNKNOWN_SPEAKER = "speaker_unknown"

    fun merge(
        segments: List<AsrSegment>,
        speakers: List<SpeakerSegment>,
    ): List<TranscriptLine> {
        if (segments.isEmpty()) return emptyList()

        val mapping = normalizeSpeakerIds(speakers)

        return segments
            .sortedBy { it.startMs }
            .map { seg ->
                val owner = bestOverlap(seg, speakers)
                TranscriptLine(
                    speakerId = owner?.let { mapping[it.speaker] ?: UNKNOWN_SPEAKER }
                        ?: UNKNOWN_SPEAKER,
                    startMs = seg.startMs,
                    endMs = seg.endMs,
                    text = seg.text.trim(),
                )
            }
            .filter { it.text.isNotEmpty() }
    }

    /** Говорящий с наибольшим пересечением по времени; null, если пересечения нет. */
    private fun bestOverlap(segment: AsrSegment, speakers: List<SpeakerSegment>): SpeakerSegment? {
        var best: SpeakerSegment? = null
        var bestOverlap = 0L
        for (spk in speakers) {
            val from = maxOf(segment.startMs, spk.startMs)
            val to = minOf(segment.endMs, spk.endMs)
            val overlap = to - from
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = spk
            }
        }
        return best
    }

    /**
     * Приводит разные варианты идентификаторов к единому виду `speaker_N`.
     * Порядок номеров — по первому появлению в записи, чтобы нумерация была стабильной.
     */
    private fun normalizeSpeakerIds(speakers: List<SpeakerSegment>): Map<String, String> {
        val order = linkedMapOf<String, String>()
        speakers
            .sortedBy { it.startMs }
            .forEach { spk ->
                if (!order.containsKey(spk.speaker)) {
                    order[spk.speaker] = "speaker_${order.size + 1}"
                }
            }
        return order
    }

    /** `speaker_1` → «Спикер 1», для человекочитаемого вывода. */
    fun humanize(speakerId: String): String {
        val digits = speakerId.takeLastWhile { it.isDigit() }
        return if (digits.isEmpty()) "Спикер ?" else "Спикер $digits"
    }

    /** Таймкод `[MM:SS]` из миллисекунд. */
    fun timecode(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "[%02d:%02d]".format(minutes, seconds)
    }

    /** Строки вида `[MM:SS] Спикер N: текст` — контракт для LLM. */
    fun toPromptLines(lines: List<TranscriptLine>): String =
        lines.joinToString("\n") { line ->
            "${timecode(line.startMs)} ${humanize(line.speakerId)}: ${line.text}"
        }
}
