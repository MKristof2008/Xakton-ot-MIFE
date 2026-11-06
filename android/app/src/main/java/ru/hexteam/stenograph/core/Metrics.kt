package ru.hexteam.stenograph.core

import kotlin.math.max
import kotlin.math.min

/**
 * Метрики качества — то, чем организаторы оценивают решение.
 *
 * WER — Word Error Rate, точность текста (40 баллов).
 * DER — Diarization Error Rate, точность разделения говорящих (25 баллов).
 * RTF — Real-Time Factor, скорость обработки (10 баллов).
 *
 * Реализация без внешних зависимостей: только стандартная библиотека Kotlin,
 * чтобы метрики считались и в юнит-тестах на JVM, и на устройстве.
 */
object Metrics {

    // ---------- WER ----------

    /**
     * Word Error Rate = (S + D + I) / N, где N — число слов в эталоне.
     * Сравнение регистронезависимое, пунктуация отбрасывается.
     */
    fun wer(reference: String, hypothesis: String): Double {
        val ref = tokenize(reference)
        val hyp = tokenize(hypothesis)
        if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0

        val distance = editDistance(ref, hyp)
        return distance.toDouble() / ref.size
    }

    /** Расстояние Левенштейна по словам: замены, вставки, удаления. */
    private fun editDistance(a: List<String>, b: List<String>): Int {
        var prev = IntArray(b.size + 1) { it }
        var curr = IntArray(b.size + 1)

        for (i in 1..a.size) {
            curr[0] = i
            for (j in 1..b.size) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,        // удаление
                    curr[j - 1] + 1,    // вставка
                    prev[j - 1] + cost, // замена
                )
            }
            val swap = prev
            prev = curr
            curr = swap
        }
        return prev[b.size]
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    // ---------- RTF ----------

    /**
     * Real-Time Factor = время обработки / длительность аудио.
     * Меньше 1.0 — быстрее реального времени.
     */
    fun rtf(audioDurationSec: Double, processingTimeSec: Double): Double {
        if (audioDurationSec <= 0.0) return Double.POSITIVE_INFINITY
        return processingTimeSec / audioDurationSec
    }

    // ---------- DER ----------

    /**
     * Diarization Error Rate — доля времени, размеченного неверно.
     *
     * Считается по трём составляющим:
     *  - пропуск речи (miss): эталон говорит, гипотеза молчит;
     *  - ложная речь (false alarm): гипотеза говорит, эталон молчит;
     *  - путаница говорящих (confusion): речь размечена, но не тем говорящим.
     *
     * Реализация поточечная с шагом [stepMs], что для оценок на минутах аудио
     * даёт достаточную точность и не требует выравнивания интервалов.
     */
    fun der(
        reference: List<SpeakerSegment>,
        hypothesis: List<SpeakerSegment>,
        stepMs: Long = 10L,
    ): Double {
        if (reference.isEmpty()) return if (hypothesis.isEmpty()) 0.0 else 1.0

        val totalRefMs = reference.sumOf { it.endMs - it.startMs }.coerceAtLeast(1L)
        var missMs = 0L
        var falseAlarmMs = 0L
        var confusionMs = 0L

        // Отображение эталонного говорящего в гипотетического — жадное, по объёму совпадений.
        val mapping = bestSpeakerMapping(reference, hypothesis, stepMs)

        var t = reference.minOf { it.startMs }
        val end = maxOf(reference.maxOf { it.endMs }, hypothesis.maxOfOrNull { it.endMs } ?: 0L)

        while (t < end) {
            val refSpeaker = speakerAt(reference, t)
            val hypSpeaker = speakerAt(hypothesis, t)
            when {
                refSpeaker == null && hypSpeaker != null -> falseAlarmMs += stepMs
                refSpeaker != null && hypSpeaker == null -> missMs += stepMs
                refSpeaker != null && hypSpeaker != null -> {
                    if (mapping[refSpeaker] != hypSpeaker) confusionMs += stepMs
                }
            }
            t += stepMs
        }

        return (missMs + falseAlarmMs + confusionMs).toDouble() / totalRefMs
    }

    /** Жадное сопоставление: каждому эталонному говорящему — гипотеза с максимальным совпадением. */
    private fun bestSpeakerMapping(
        reference: List<SpeakerSegment>,
        hypothesis: List<SpeakerSegment>,
        stepMs: Long,
    ): Map<String, String> {
        val refSpeakers = reference.map { it.speaker }.distinct()
        val hypSpeakers = hypothesis.map { it.speaker }.distinct()
        val scores = mutableMapOf<Pair<String, String>, Long>()

        var t = reference.minOf { it.startMs }
        val end = reference.maxOf { it.endMs }
        while (t < end) {
            val r = speakerAt(reference, t)
            val h = speakerAt(hypothesis, t)
            if (r != null && h != null) {
                scores[r to h] = (scores[r to h] ?: 0L) + stepMs
            }
            t += stepMs
        }

        val result = mutableMapOf<String, String>()
        val taken = mutableSetOf<String>()
        scores.entries
            .sortedByDescending { it.value }
            .forEach { (pair, _) ->
                val (r, h) = pair
                if (!result.containsKey(r) && h !in taken) {
                    result[r] = h
                    taken += h
                }
            }
        // Несопоставленные эталонные говорящие не получают пары — любое совпадение
        // с ними считается путаницей.
        refSpeakers.forEach { r ->
            if (!result.containsKey(r)) {
                val free = hypSpeakers.firstOrNull { it !in taken }
                if (free != null) {
                    result[r] = free
                    taken += free
                }
            }
        }
        return result
    }

    private fun speakerAt(segments: List<SpeakerSegment>, timeMs: Long): String? =
        segments.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }?.speaker

    // ---------- Сводка ----------

    data class Report(
        val wer: Double,
        val der: Double,
        val rtf: Double,
        val audioDurationSec: Double,
        val processingSec: Double,
    ) {
        fun toCsvRow(file: String): String = buildString {
            append(file).append(',')
            append("%.2f".format(audioDurationSec)).append(',')
            append("%.2f".format(processingSec)).append(',')
            append("%.3f".format(rtf)).append(',')
            append("%.4f".format(wer)).append(',')
            append("%.4f".format(der))
        }
    }

    fun report(
        referenceText: String,
        hypothesisText: String,
        referenceSpeakers: List<SpeakerSegment>,
        hypothesisSpeakers: List<SpeakerSegment>,
        audioDurationSec: Double,
        processingSec: Double,
    ): Report = Report(
        wer = wer(referenceText, hypothesisText),
        der = der(referenceSpeakers, hypothesisSpeakers),
        rtf = rtf(audioDurationSec, processingSec),
        audioDurationSec = audioDurationSec,
        processingSec = processingSec,
    )
}
