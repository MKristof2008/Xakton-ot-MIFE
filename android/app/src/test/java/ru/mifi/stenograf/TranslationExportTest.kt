package ru.mifi.stenograf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mifi.stenograf.llm.Translator
import ru.mifi.stenograf.output.HexJson
import ru.mifi.stenograf.output.Replica

class TranslationExportTest {

    private fun reps() = listOf(
        Replica("speaker_0", 0, 3_000, "Добрый день, коллеги."),
        Replica("speaker_1", 3_000, 7_500, "Начнём с обсуждения релиза."),
    )

    @Test
    fun `направление перевода определяется по тексту`() {
        assertEquals(Translator.Direction.RU_EN, Translator.directionOf("Привет, как дела?"))
        assertEquals(Translator.Direction.EN_RU, Translator.directionOf("Hello there, team."))
    }

    @Test
    fun `английское слово внутри русской фразы не переворачивает направление`() {
        // Названия технологий латиницей — обычное дело в русской речи.
        assertEquals(Translator.Direction.RU_EN, Translator.directionOf("Релиз Android переносим"))
    }

    @Test
    fun `текст с переводом содержит обе строки`() {
        val pairs = reps().zipWithNulls()
        val text = HexJson.toTextWithTranslations(pairs)
        assertTrue(text.contains("Добрый день, коллеги."))
        assertTrue(text.contains("→ Good afternoon, colleagues."))
    }

    @Test
    fun `без перевода строка остаётся прежней`() {
        val text = HexJson.toTextWithTranslations(reps().map { it to null })
        assertFalse(text.contains("→"))
        assertTrue(text.contains("[00:00] Спикер 0: Добрый день, коллеги."))
    }

    @Test
    fun `srt нумерует реплики и ставит миллисекунды`() {
        val srt = HexJson.toSrt(reps().map { it to null })
        assertTrue(srt.startsWith("1\n00:00:00,000 --> 00:00:03,000"))
        assertTrue(srt.contains("2\n00:00:03,000 --> 00:00:07,500"))
    }

    @Test
    fun `srt с переводом содержит обе строки под репликой`() {
        val pairs = reps().mapIndexed { i, r ->
            r to if (i == 0) "Good afternoon, colleagues." else null
        }
        val srt = HexJson.toSrt(pairs)
        assertTrue(srt.contains("Good afternoon, colleagues."))
        // Вторая реплика перевода не имеет — лишней строки быть не должно.
        assertEquals(1, Regex("Good afternoon").findAll(srt).count())
    }

    private fun List<Replica>.zipWithNulls(): List<Pair<Replica, String?>> = listOf(
        this[0] to "Good afternoon, colleagues.",
        this[1] to null,
    )
}