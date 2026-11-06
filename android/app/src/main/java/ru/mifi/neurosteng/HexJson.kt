package ru.mifi.neurosteng

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Формат вывода по ТЗ (совпадает с output.py из корня репозитория):
 * {"transcript":[{"speaker_id":"speaker_1","start_ms":0,"end_ms":4200,"text":"..."}]}
 */
object HexJson {

    @Serializable
    data class Segment(
        val speaker_id: String,
        val start_ms: Int,
        val end_ms: Int,
        val text: String,
    )

    @Serializable
    data class Result(val transcript: List<Segment>)

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(segments: List<Segment>): String = json.encodeToString(Result(segments))

    /** Проверка перед сохранением: те же правила, что в output.py. */
    fun validate(segments: List<Segment>): List<String> {
        val errors = mutableListOf<String>()
        val pattern = Regex("""speaker_\d+""")
        segments.forEachIndexed { i, s ->
            if (!pattern.matches(s.speaker_id)) {
                errors += "сегмент $i: speaker_id '${s.speaker_id}' не по шаблону speaker_N"
            }
            if (s.end_ms <= s.start_ms) {
                errors += "сегмент $i: end_ms <= start_ms"
            }
        }
        return errors
    }

    /** Спикеры в порядке появления -> speaker_1, speaker_2, ... */
    fun normalizeSpeakers(segments: List<Segment>): List<Segment> {
        val mapping = linkedMapOf<String, String>()
        return segments.map { s ->
            val id = mapping.getOrPut(s.speaker_id) { "speaker_${mapping.size + 1}" }
            s.copy(speaker_id = id)
        }
    }

    /**
     * Отбрасывает сегменты за пределами записи — та же защита от галлюцинаций
     * whisper, что в asr.py и output.py.
     */
    fun dropOutOfBounds(segments: List<Segment>, durationMs: Int): List<Segment> =
        segments.filter { it.end_ms <= durationMs + 250 && it.start_ms >= 0 }
}