package ru.mifi.stenograf.output

import org.json.JSONArray
import org.json.JSONObject

/**
 * Экспорт в JSON строго по схеме ТЗ (слайд 14) + валидатор.
 * Правила валидации перенесены из output.py::validate_hex.
 */
object HexJson {

    fun toJson(replicas: List<Replica>): String {
        val arr = JSONArray()
        for (r in replicas) {
            arr.put(
                JSONObject()
                    .put("speaker_id", r.speakerId)
                    .put("start_ms", r.startMs)
                    .put("end_ms", r.endMs)
                    .put("text", r.text),
            )
        }
        return JSONObject()
            .put("transcript", arr)
            .toString(2)
    }

    /** Возвращает список нарушений схемы; пустой список — всё корректно. */
    fun validate(json: String): List<String> {
        val root = runCatching { JSONObject(json) }.getOrElse { return listOf("невалидный JSON: ${it.message}") }
        if (!root.has("transcript")) return listOf("нет ключа 'transcript'")
        val arr = root.optJSONArray("transcript") ?: return listOf("'transcript' не массив")
        val errors = mutableListOf<String>()
        if (arr.length() == 0) errors += "пустой transcript"
        var prevStart = -1
        for (i in 0 until arr.length()) {
            val seg = arr.optJSONObject(i)
            if (seg == null) {
                errors += "сегмент $i: не объект"
                continue
            }
            if (!Regex("speaker_\\d+").matches(seg.optString("speaker_id"))) {
                errors += "сегмент $i: speaker_id '${seg.optString("speaker_id")}' не по шаблону speaker_N"
            }
            if (!seg.has("start_ms") || !seg.has("end_ms")) {
                errors += "сегмент $i: нет start_ms/end_ms"
                continue
            }
            val start = seg.optInt("start_ms", -1)
            val end = seg.optInt("end_ms", -1)
            if (start < 0) errors += "сегмент $i: start_ms < 0"
            if (end <= start) errors += "сегмент $i: end_ms <= start_ms"
            if (start < prevStart) errors += "сегмент $i: нарушен порядок по времени"
            prevStart = start
            if (seg.optString("text").isBlank()) errors += "сегмент $i: пустой text"
        }
        return errors
    }

    /** Человекочитаемый текст: "[MM:SS] Спикер N: реплика". */
    fun toText(replicas: List<Replica>): String = replicas.joinToString("\n") { it.displayLine() }
}