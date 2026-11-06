package ru.mifi.stenograf.output

/** Сегмент распознавания речи. Время — миллисекунды от начала файла. */
data class AsrSegment(
    val startMs: Int,
    val endMs: Int,
    val text: String,
)

/** Интервал, на котором говорит один человек (результат диаризации). */
data class SpeakerTurn(
    val startMs: Int,
    val endMs: Int,
    val speakerRaw: String,
)

/** Готовая реплика: формат выхода по ТЗ Hex.team (слайд 14). */
data class Replica(
    val speakerId: String,
    val startMs: Int,
    val endMs: Int,
    val text: String,
) {
    /** "[MM:SS] Спикер N: текст" — как в prompts.py. */
    fun displayLine(): String {
        val n = speakerId.substringAfterLast('_').toIntOrNull() ?: 0
        return "[${timecode(startMs)}] Спикер $n: $text"
    }

    companion object {
        fun timecode(ms: Int): String {
            val totalSec = ms / 1000
            return "%02d:%02d".format(totalSec / 60, totalSec % 60)
        }
    }
}