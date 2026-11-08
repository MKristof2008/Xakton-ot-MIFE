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

    /** Массив реплик без обёртки — для истории и других мест, где нужен только список. */
    fun toJsonArray(replicas: List<Replica>): JSONArray {
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
        return arr
    }

    /** Человекочитаемый текст: "[MM:SS] Спикер N: реплика". */
    fun toText(replicas: List<Replica>): String = replicas.joinToString("\n") { it.displayLine() }

    /**
     * Тот же текст с переводом под оригиналом и с именами говорящих, если их
     * задали на экране результата.
     */
    fun toTextWithTranslations(
        pairs: List<Pair<Replica, String?>>,
        speakerLabel: (String) -> String = { "Спикер ${it.substringAfterLast('_')}" },
    ): String = pairs.joinToString("\n") { (rep, tr) ->
        val line = "[${Replica.timecode(rep.startMs)}] ${speakerLabel(rep.speakerId)}: ${rep.text}"
        if (tr.isNullOrBlank()) line else line + "\n    → " + tr
    }

    /**
     * Субтитры SRT: тот же формат, что у YouTube и плееров. Нужен, когда
     * расшифровку просят «как субтитры к видео», а это просят часто.
     */
    fun toSrt(
        pairs: List<Pair<Replica, String?>>,
        speakerLabel: (String) -> String = { "Спикер ${it.substringAfterLast('_')}" },
    ): String = buildString {
        pairs.forEachIndexed { i, (rep, tr) ->
            append(i + 1).append('\n')
            append(srtTime(rep.startMs)).append(" --> ").append(srtTime(rep.endMs)).append('\n')
            append(speakerLabel(rep.speakerId)).append(": ").append(rep.text).append('\n')
            if (!tr.isNullOrBlank()) append(tr).append('\n')
            append('\n')
        }
    }.trimEnd()

    private fun srtTime(ms: Int): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val msec = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, msec)
    }

    /** JSON для ТЗ остаётся без переводов, но с ними удобно отдавать заказчику. */
    fun toJsonWithTranslations(pairs: List<Pair<Replica, String?>>): String {
        val arr = JSONArray()
        for ((r, tr) in pairs) {
            val o = JSONObject()
                .put("speaker_id", r.speakerId)
                .put("start_ms", r.startMs)
                .put("end_ms", r.endMs)
                .put("text", r.text)
            if (!tr.isNullOrBlank()) o.put("translation", tr)
            arr.put(o)
        }
        return JSONObject().put("transcript", arr).toString(2)
    }
}