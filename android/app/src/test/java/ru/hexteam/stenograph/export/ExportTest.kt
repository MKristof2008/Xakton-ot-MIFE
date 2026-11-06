package ru.hexteam.stenograph.export

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hexteam.stenograph.core.TranscriptLine

/**
 * Проверки формата экспорта по ТЗ:
 * порядок реплик по start_ms, целые миллисекунды, кириллица без escape-мусора,
 * валидный JSON, лишних полей нет.
 */
class ExportTest {

    private val lines = listOf(
        TranscriptLine("speaker_2", 4380, 6100, "давайте обсудим сроки"),
        TranscriptLine("speaker_1", 1250, 4380, "Добрый день!"),
        TranscriptLine("speaker_1", 6100, 9800, "начнём совещание"),
    )

    @Test
    fun `json соответствует схеме ТЗ`() {
        val root = JSONObject(Export.toJson(lines))

        // Ровно одно поле верхнего уровня
        assertEquals(setOf("transcript"), root.keySet())

        val array = root.getJSONArray("transcript")
        assertEquals(3, array.length())

        val first = array.getJSONObject(0)
        // Лишних полей нет
        assertEquals(
            setOf("speaker_id", "start_ms", "end_ms", "text"),
            first.keySet(),
        )
        // Типы значений
        assertTrue(first.get("start_ms") is Int || first.get("start_ms") is Long)
        assertTrue(first.get("end_ms") is Int || first.get("end_ms") is Long)
        assertTrue(first.get("speaker_id") is String)
        assertTrue(first.get("text") is String)
    }

    @Test
    fun `реплики упорядочены по start_ms`() {
        val array = JSONObject(Export.toJson(lines)).getJSONArray("transcript")
        var previous = -1L
        for (i in 0 until array.length()) {
            val start = array.getJSONObject(i).getLong("start_ms")
            assertTrue("start_ms должен не убывать", start >= previous)
            previous = start
        }
        // Первая реплика — самая ранняя из исходных
        assertEquals(1250L, array.getJSONObject(0).getLong("start_ms"))
    }

    @Test
    fun `end_ms не меньше start_ms`() {
        val array = JSONObject(Export.toJson(lines)).getJSONArray("transcript")
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            assertTrue(item.getLong("end_ms") >= item.getLong("start_ms"))
        }
    }

    @Test
    fun `кириллица не экранируется`() {
        val raw = Export.toJson(lines)
        assertTrue(raw.contains("Добрый день!"))
        assertTrue(!raw.contains("\\u"))
    }

    @Test
    fun `millis целые, не дробные`() {
        val raw = Export.toJson(lines)
        assertTrue(!raw.contains("1250.0"))
        assertTrue(raw.contains("1250"))
    }

    @Test
    fun `пустой транскрипт — валидный пустой массив`() {
        val root = JSONObject(Export.toJson(emptyList()))
        assertEquals(0, root.getJSONArray("transcript").length())
    }

    @Test
    fun `txt содержит таймкоды и имена говорящих`() {
        val txt = Export.toTxt(lines)
        assertTrue(txt.contains("[00:01]"))
        assertTrue(txt.contains("Спикер 2"))
        // Переименование применяется к отображению
        val renamed = Export.toTxt(lines, mapOf("speaker_1" to "Анна"))
        assertTrue(renamed.contains("Анна"))
    }

    @Test
    fun `srt формат времени и порядок`() {
        val srt = Export.toSrt(lines)
        val blocks = srt.trim().split("\n\n")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0].startsWith("1\n00:00:01,250 --> 00:00:04,380"))
        assertTrue(blocks[0].contains("Спикер 1: Добрый день!"))
    }

    @Test
    fun `json читается обратно без потерь`() {
        val raw = Export.toJson(lines)
        val array: JSONArray = JSONObject(raw).getJSONArray("transcript")
        val restored = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            TranscriptLine(
                o.getString("speaker_id"),
                o.getLong("start_ms"),
                o.getLong("end_ms"),
                o.getString("text"),
            )
        }
        assertEquals(lines.sortedBy { it.startMs }, restored)
    }
}
