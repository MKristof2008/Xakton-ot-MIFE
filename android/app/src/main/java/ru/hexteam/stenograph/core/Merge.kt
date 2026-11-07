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

        // Сегмент ASR накрывает смену говорящего: whisper режет по паузам и по
        // 30-секундным окнам, а не по голосам. Если просто взять «кто больше
        // пересекается», вся реплика уходит одному — диалог становится
        // монологом. Дробим сегмент, но ТОЛЬКО по паузам в речи: иначе
        // получаются обрывки вида «давно» / «группы», отданные другому голосу.
        return segments
            .sortedBy { it.startMs }
            .flatMap { seg -> splitAtPauses(seg, speakers, mapping) }
            .filter { it.text.isNotEmpty() }
            .sortedBy { it.startMs }
    }

    /**
     * Режет сегмент по смене говорящего, только если смена попадает на паузу.
     *
     * Без пословных таймкодов единственный надёжный сигнал границы — пауза в
     * тексте (запятая, точка, тире) или достаточно длинный интервал тишины.
     * Резать «по времени» посреди фразы нельзя: на замерах это давало
     * «...в сторону лесов работой» + «давно» разным говорящим.
     */
    private fun splitAtPauses(
        segment: AsrSegment,
        speakers: List<SpeakerSegment>,
        mapping: Map<String, String>,
    ): List<TranscriptLine> {
        val text = segment.text.trim()
        if (text.isEmpty()) return emptyList()

        if (speakers.isEmpty()) {
            return listOf(TranscriptLine(UNKNOWN_SPEAKER, segment.startMs, segment.endMs, text))
        }

        val overlaps = speakers
            .mapNotNull { spk ->
                val from = maxOf(segment.startMs, spk.startMs)
                val to = minOf(segment.endMs, spk.endMs)
                if (to > from) Triple(spk, from, to) else null
            }
            .sortedBy { it.second }

        val owner = bestOverlap(segment, speakers)
        val ownerId = owner?.let { mapping[it.speaker] ?: UNKNOWN_SPEAKER } ?: UNKNOWN_SPEAKER

        // Один говорящий или границы нет — отдаём целиком владельцу большинства.
        val distinct = overlaps.map { mapping[it.first.speaker] ?: UNKNOWN_SPEAKER }.distinct()
        if (distinct.size <= 1) {
            return listOf(TranscriptLine(ownerId, segment.startMs, segment.endMs, text))
        }

        // Ищем точку разрыва: конец предложения внутри сегмента, ближайший
        // к границе смены говорящего. Если такой нет — не режем.
        val boundary = overlaps.drop(1).firstOrNull()?.second ?: return listOf(
            TranscriptLine(ownerId, segment.startMs, segment.endMs, text),
        )
        val fraction = ((boundary - segment.startMs).toDouble() /
            (segment.endMs - segment.startMs).coerceAtLeast(1L)).coerceIn(0.0, 1.0)

        val cut = sentenceCut(text, fraction) ?: return listOf(
            TranscriptLine(ownerId, segment.startMs, segment.endMs, text),
        )

        val firstPart = text.substring(0, cut).trim()
        val secondPart = text.substring(cut).trim()
        if (firstPart.isEmpty() || secondPart.isEmpty()) {
            return listOf(TranscriptLine(ownerId, segment.startMs, segment.endMs, text))
        }

        val firstId = overlaps.first().let { mapping[it.first.speaker] ?: UNKNOWN_SPEAKER }
        val secondId = overlaps.last().let { mapping[it.first.speaker] ?: UNKNOWN_SPEAKER }
        if (firstId == secondId) {
            return listOf(TranscriptLine(ownerId, segment.startMs, segment.endMs, text))
        }

        return listOf(
            TranscriptLine(firstId, segment.startMs, boundary, firstPart),
            TranscriptLine(secondId, boundary, segment.endMs, secondPart),
        )
    }

    /**
     * Позиция конца предложения, ближайшая к ожидаемой доле текста.
     * null — подходящего разрыва нет, резать не стоит.
     */
    private fun sentenceCut(text: String, fraction: Double): Int? {
        val target = (text.length * fraction).toInt()
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE
        for ((i, ch) in text.withIndex()) {
            if (ch !in ".!?…") continue
            // Хотя бы по три слова с каждой стороны: иначе получаются обрывки.
            val left = text.substring(0, i).count { it == ' ' }
            val right = text.substring(i).count { it == ' ' }
            if (left < 3 || right < 3) continue
            val distance = kotlin.math.abs(i - target)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i + 1
            }
        }
        return best
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
