package ru.mifi.stenograf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mifi.stenograf.output.AsrSegment
import ru.mifi.stenograf.output.HexJson
import ru.mifi.stenograf.output.Merge
import ru.mifi.stenograf.output.SpeakerTurn

class MergeTest {

    private fun turns(vararg t: Triple<Int, Int, String>) =
        t.map { SpeakerTurn(it.first, it.second, it.third) }

    @Test
    fun `спикер выбирается по максимальному пересечению`() {
        val t = turns(Triple(0, 1000, "a"), Triple(1000, 2000, "b"))
        assertEquals("b", Merge.bestSpeaker(1200, 1800, t))
        assertEquals("a", Merge.bestSpeaker(100, 900, t))
    }

    @Test
    fun `без пересечения берётся ближайший по средней точке`() {
        val t = turns(Triple(0, 1000, "a"), Triple(5000, 6000, "b"))
        assertEquals("b", Merge.bestSpeaker(4200, 4600, t))
    }

    @Test
    fun `сегмент на двух говорящих режется по границе`() {
        val t = turns(Triple(0, 1000, "a"), Triple(1000, 3000, "b"))
        val pieces = Merge.splitByTurns(AsrSegment(500, 2500, "раз два три четыре"), t)
        assertEquals(2, pieces.size)
        assertEquals("a", pieces[0].second)
        assertEquals("b", pieces[1].second)
    }

    @Test
    fun `спикеры нумеруются в порядке первого появления`() {
        val asr = listOf(
            AsrSegment(0, 1000, "первый"),
            AsrSegment(1000, 2000, "второй"),
            AsrSegment(2000, 3000, "снова первый"),
        )
        val t = turns(Triple(0, 1000, "raw_5"), Triple(1000, 2000, "raw_9"), Triple(2000, 3000, "raw_5"))
        val reps = Merge.build(asr, t)
        assertEquals("speaker_1", reps[0].speakerId)
        assertEquals("speaker_2", reps[1].speakerId)
    }

    @Test
    fun `соседние реплики одного спикера с малой паузой склеиваются`() {
        val asr = listOf(AsrSegment(0, 1000, "привет"), AsrSegment(1100, 2000, "как дела"))
        val t = turns(Triple(0, 2000, "a"))
        val reps = Merge.build(asr, t)
        assertEquals(1, reps.size)
        assertEquals("привет как дела", reps[0].text)
    }

    @Test
    fun `пустые сегменты отбрасываются`() {
        val asr = listOf(AsrSegment(0, 1000, "  "), AsrSegment(1000, 2000, "текст"))
        val reps = Merge.build(asr, turns(Triple(0, 2000, "a")))
        assertEquals(1, reps.size)
        assertEquals("текст", reps[0].text)
    }

    @Test
    fun `JSON проходит валидацию схемы ТЗ`() {
        val reps = Merge.build(
            listOf(AsrSegment(1250, 4380, "Добрый день!")),
            turns(Triple(0, 5000, "a")),
        )
        val json = HexJson.toJson(reps)
        assertTrue(HexJson.validate(json).toString(), HexJson.validate(json).isEmpty())
        assertTrue(json.contains("\"speaker_id\""))
        assertTrue(json.contains("\"start_ms\""))
    }

    @Test
    fun `валидатор ловит нарушения схемы`() {
        assertTrue(HexJson.validate("""{"transcript":[]}""").isNotEmpty())
        assertTrue(HexJson.validate("""{"segments":[]}""").isNotEmpty())
        assertTrue(
            HexJson.validate(
                """{"transcript":[{"speaker_id":"S1","start_ms":0,"end_ms":10,"text":"x"}]}""",
            ).any { it.contains("speaker_N") },
        )
    }
}