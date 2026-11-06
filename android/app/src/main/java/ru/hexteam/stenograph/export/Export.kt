package ru.hexteam.stenograph.export

import ru.hexteam.stenograph.core.Merge
import ru.hexteam.stenograph.core.TranscriptJson
import ru.hexteam.stenograph.core.TranscriptLine

/**
 * Форматы экспорта результата. Чистый Kotlin без Android-зависимостей —
 * покрывается юнит-тестами на JVM.
 *
 * JSON — строго по схеме ТЗ (делегирует [TranscriptJson] из core).
 * TXT и SRT — дополнительные человекочитаемые форматы.
 */
object Export {

    /** JSON по схеме ТЗ: {"transcript":[{"speaker_id","start_ms","end_ms","text"}]}. */
    fun toJson(lines: List<TranscriptLine>): String = TranscriptJson.toJson(lines)

    /** Читаемый текст: [MM:SS] Спикер N + реплика. */
    fun toTxt(lines: List<TranscriptLine>, labels: Map<String, String> = emptyMap()): String =
        lines.sortedBy { it.startMs }.joinToString("\n\n") { line ->
            "${Merge.timecode(line.startMs)} ${labelFor(line.speakerId, labels)}\n${line.text}"
        }

    /** Субтитры SRT: нумерация, `HH:MM:SS,mmm --> HH:MM:SS,mmm`, спикер + текст. */
    fun toSrt(lines: List<TranscriptLine>, labels: Map<String, String> = emptyMap()): String =
        buildString {
            lines.sortedBy { it.startMs }.forEachIndexed { index, line ->
                appendLine(index + 1)
                appendLine("${srtTime(line.startMs)} --> ${srtTime(line.endMs)}")
                appendLine("${labelFor(line.speakerId, labels)}: ${line.text}")
                appendLine()
            }
        }

    private fun labelFor(speakerId: String, labels: Map<String, String>): String =
        labels[speakerId] ?: Merge.humanize(speakerId)

    internal fun srtTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0)
        val h = clamped / 3_600_000
        val m = clamped % 3_600_000 / 60_000
        val s = clamped % 60_000 / 1000
        val millis = clamped % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
    }
}
