package ru.hexteam.stenograph.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Экспорт в JSON строго по схеме ТЗ:
 *
 * {
 *   "transcript": [
 *     { "speaker_id": "speaker_1", "start_ms": 1250, "end_ms": 4380, "text": "..." }
 *   ]
 * }
 *
 * Порядок реплик — по возрастанию start_ms. Лишних полей нет.
 */
object TranscriptJson {

    fun toJson(lines: List<TranscriptLine>): String {
        val array = JSONArray()
        lines
            .sortedBy { it.startMs }
            .forEach { line ->
                array.put(
                    JSONObject().apply {
                        put("speaker_id", line.speakerId)
                        put("start_ms", line.startMs)
                        put("end_ms", line.endMs)
                        put("text", line.text)
                    }
                )
            }
        return JSONObject().put("transcript", array).toString(2)
    }

    /** Разбор JSON обратно — нужно для проверки и для чтения сохранённых файлов. */
    fun fromJson(raw: String): List<TranscriptLine> {
        val root = JSONObject(raw)
        val array = root.optJSONArray("transcript") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            TranscriptLine(
                speakerId = item.getString("speaker_id"),
                startMs = item.getLong("start_ms"),
                endMs = item.getLong("end_ms"),
                text = item.getString("text"),
            )
        }
    }

    /** Человекочитаемая расшифровка для показа в интерфейсе. */
    fun toPlainText(lines: List<TranscriptLine>): String =
        lines.joinToString("\n\n") { line ->
            "${Merge.timecode(line.startMs)} ${Merge.humanize(line.speakerId)}\n${line.text}"
        }
}
