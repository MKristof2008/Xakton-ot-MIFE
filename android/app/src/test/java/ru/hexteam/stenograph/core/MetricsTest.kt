package ru.hexteam.stenograph.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsTest {

    // ---------- WER ----------

    @Test
    fun `identical text gives zero wer`() {
        assertEquals(0.0, Metrics.wer("привет мир", "привет мир"), 1e-9)
    }

    @Test
    fun `one substitution in two words gives half`() {
        // 2 слова в эталоне, 1 замена → WER = 0.5
        assertEquals(0.5, Metrics.wer("привет мир", "привет дом"), 1e-9)
    }

    @Test
    fun `punctuation and case are ignored`() {
        assertEquals(0.0, Metrics.wer("Привет, мир!", "привет мир"), 1e-9)
    }

    @Test
    fun `insertion increases wer`() {
        // Эталон 2 слова, гипотеза 3 → одна вставка → 0.5
        assertEquals(0.5, Metrics.wer("привет мир", "привет большой мир"), 1e-9)
    }

    @Test
    fun `empty hypothesis gives wer one`() {
        assertEquals(1.0, Metrics.wer("привет мир", ""), 1e-9)
    }

    // ---------- RTF ----------

    @Test
    fun `rtf below one means faster than realtime`() {
        assertEquals(0.5, Metrics.rtf(60.0, 30.0), 1e-9)
    }

    @Test
    fun `rtf above one means slower`() {
        assertEquals(2.0, Metrics.rtf(60.0, 120.0), 1e-9)
    }

    @Test
    fun `zero duration does not divide by zero`() {
        assertTrue(Metrics.rtf(0.0, 10.0).isInfinite())
    }

    // ---------- DER ----------

    @Test
    fun `perfect diarization gives zero der`() {
        val reference = listOf(
            SpeakerSegment(0, 5000, "A"),
            SpeakerSegment(5000, 10000, "B"),
        )
        assertEquals(0.0, Metrics.der(reference, reference), 1e-6)
    }

    @Test
    fun `missing speech counts as error`() {
        val reference = listOf(SpeakerSegment(0, 10000, "A"))
        val hypothesis = listOf(SpeakerSegment(0, 5000, "A"))

        val der = Metrics.der(reference, hypothesis)

        // Половина эталонной речи не размечена → DER около 0.5
        assertEquals(0.5, der, 0.05)
    }

    @Test
    fun `swapped speaker labels are not an error`() {
        // Идентификаторы говорящих произвольны: если A и B просто переименованы
        // местами, диаризация выполнена верно. DER должен быть нулевым.
        val reference = listOf(
            SpeakerSegment(0, 5000, "A"),
            SpeakerSegment(5000, 10000, "B"),
        )
        val hypothesis = listOf(
            SpeakerSegment(0, 5000, "B"),
            SpeakerSegment(5000, 10000, "A"),
        )

        assertEquals(0.0, Metrics.der(reference, hypothesis), 0.05)
    }

    @Test
    fun `merged speakers count as confusion`() {
        // Эталон: два разных говорящих. Гипотеза: всё приписано одному.
        // Это настоящая ошибка — второй говорящий не разделён.
        val reference = listOf(
            SpeakerSegment(0, 5000, "A"),
            SpeakerSegment(5000, 10000, "B"),
        )
        val hypothesis = listOf(
            SpeakerSegment(0, 10000, "A"),
        )

        val der = Metrics.der(reference, hypothesis)
        assertTrue("DER должен быть около 0.5, получено $der", der > 0.3)
    }

    @Test
    fun `empty reference with empty hypothesis gives zero`() {
        assertEquals(0.0, Metrics.der(emptyList(), emptyList()), 1e-9)
    }
}
