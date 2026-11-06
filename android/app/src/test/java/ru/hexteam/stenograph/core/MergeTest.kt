package ru.hexteam.stenograph.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {

    @Test
    fun `segment gets speaker with largest overlap`() {
        val segments = listOf(
            AsrSegment(0, 1000, "привет"),
            AsrSegment(1000, 2000, "как дела"),
        )
        val speakers = listOf(
            SpeakerSegment(0, 1200, "speaker_1"),
            SpeakerSegment(1200, 2000, "speaker_2"),
        )

        val lines = Merge.merge(segments, speakers)

        assertEquals(2, lines.size)
        assertEquals("speaker_1", lines[0].speakerId)
        assertEquals("speaker_2", lines[1].speakerId)
    }

    @Test
    fun `segment without overlap gets unknown speaker`() {
        val segments = listOf(AsrSegment(5000, 6000, "тишина"))
        val speakers = listOf(SpeakerSegment(0, 1000, "speaker_1"))

        val lines = Merge.merge(segments, speakers)

        assertEquals(1, lines.size)
        assertEquals(Merge.UNKNOWN_SPEAKER, lines[0].speakerId)
    }

    @Test
    fun `empty asr gives empty result`() {
        assertTrue(Merge.merge(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `timecode formats milliseconds`() {
        assertEquals("[00:00]", Merge.timecode(0))
        assertEquals("[00:01]", Merge.timecode(1250))
        assertEquals("[01:05]", Merge.timecode(65_000))
        assertEquals("[10:00]", Merge.timecode(600_000))
    }

    @Test
    fun `humanize converts speaker id`() {
        assertEquals("Спикер 1", Merge.humanize("speaker_1"))
        assertEquals("Спикер 2", Merge.humanize("speaker_2"))
        assertEquals("Спикер ?", Merge.humanize("unknown"))
    }

    @Test
    fun `speaker ids are normalized by first appearance`() {
        val segments = listOf(
            AsrSegment(0, 1000, "a"),
            AsrSegment(1000, 2000, "b"),
        )
        val speakers = listOf(
            SpeakerSegment(1000, 2000, "SPEAKER_00"),
            SpeakerSegment(0, 1000, "SPEAKER_01"),
        )

        val lines = Merge.merge(segments, speakers)

        // Нумерация идёт по времени первого появления, а не по исходному имени.
        assertEquals("speaker_1", lines[0].speakerId)
        assertEquals("speaker_2", lines[1].speakerId)
    }

    @Test
    fun `empty text segments are dropped`() {
        val segments = listOf(
            AsrSegment(0, 1000, "   "),
            AsrSegment(1000, 2000, "слово"),
        )
        val speakers = listOf(SpeakerSegment(0, 2000, "speaker_1"))

        val lines = Merge.merge(segments, speakers)

        assertEquals(1, lines.size)
        assertEquals("слово", lines[0].text)
    }
}
