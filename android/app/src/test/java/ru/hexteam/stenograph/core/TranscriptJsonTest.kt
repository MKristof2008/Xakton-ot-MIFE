package ru.hexteam.stenograph.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptJsonTest {

    private val lines = listOf(
        TranscriptLine("speaker_1", 1250, 4380, "Добрый день!"),
        TranscriptLine("speaker_2", 5000, 7000, "Здравствуйте."),
    )

    @Test
    fun `json has exactly the fields from specification`() {
        val raw = TranscriptJson.toJson(lines)
        val root = JSONObject(raw)
        val array = root.getJSONArray("transcript")

        assertEquals(2, array.length())

        val first = array.getJSONObject(0)
        assertEquals("speaker_1", first.getString("speaker_id"))
        assertEquals(1250L, first.getLong("start_ms"))
        assertEquals(4380L, first.getLong("end_ms"))
        assertEquals("Добрый день!", first.getString("text"))

        // Лишних полей быть не должно: схема ТЗ строгая.
        assertEquals(
            setOf("speaker_id", "start_ms", "end_ms", "text"),
            first.keys().asSequence().toSet(),
        )
    }

    @Test
    fun `lines are sorted by start time`() {
        val unsorted = listOf(
            TranscriptLine("speaker_2", 5000, 7000, "второй"),
            TranscriptLine("speaker_1", 1000, 2000, "первый"),
        )

        val raw = TranscriptJson.toJson(unsorted)
        val array = JSONObject(raw).getJSONArray("transcript")

        assertEquals(1000L, array.getJSONObject(0).getLong("start_ms"))
        assertEquals(5000L, array.getJSONObject(1).getLong("start_ms"))
    }

    @Test
    fun `roundtrip keeps data`() {
        val raw = TranscriptJson.toJson(lines)
        val restored = TranscriptJson.fromJson(raw)

        assertEquals(lines, restored)
    }

    @Test
    fun `empty list gives empty transcript array`() {
        val raw = TranscriptJson.toJson(emptyList())
        val array = JSONObject(raw).getJSONArray("transcript")

        assertEquals(0, array.length())
        assertTrue(raw.contains("\"transcript\""))
    }
}
