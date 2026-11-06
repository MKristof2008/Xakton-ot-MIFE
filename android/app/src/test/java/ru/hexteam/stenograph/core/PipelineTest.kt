package ru.hexteam.stenograph.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hexteam.stenograph.engine.Stubs

/**
 * Сквозная проверка пайплайна на заглушках.
 *
 * Доказывает, что весь путь «аудио → распознавание → диаризация → сшивка → JSON»
 * работает без моделей и без сети — это требование ТЗ для этапа разработки.
 */
class PipelineTest {

    private val pipeline = Pipeline(
        asr = Stubs.StubAsr(),
        diarization = Stubs.StubDiarization(),
        llm = Stubs.StubLlm(),
    )

    @Test
    fun `full pipeline produces valid transcript`() = runBlocking {
        val stages = mutableListOf<PipelineStage>()

        val result = pipeline.run(
            audioPath = "test.wav",
            onStage = { stages += it },
        )

        assertTrue("должны получиться реплики", result.lines.isNotEmpty())

        // Все этапы пройдены по порядку
        assertEquals(
            listOf(
                PipelineStage.ASR,
                PipelineStage.DIARIZATION,
                PipelineStage.MERGE,
                PipelineStage.LLM,
                PipelineStage.DONE,
            ),
            stages,
        )

        // JSON соответствует схеме ТЗ
        val array = JSONObject(result.json).getJSONArray("transcript")
        assertEquals(result.lines.size, array.length())

        val first = array.getJSONObject(0)
        assertEquals(
            setOf("speaker_id", "start_ms", "end_ms", "text"),
            first.keys().asSequence().toSet(),
        )
        assertTrue(first.getString("text").isNotBlank())
        assertTrue(first.getLong("end_ms") > first.getLong("start_ms"))
    }

    @Test
    fun `speakers are assigned from diarization`() = runBlocking {
        val result = pipeline.run("test.wav")

        val speakers = result.lines.map { it.speakerId }.distinct()
        assertTrue("должно быть больше одного говорящего, получено $speakers", speakers.size > 1)
        assertTrue(speakers.none { it == Merge.UNKNOWN_SPEAKER })
    }

    @Test
    fun `llm punctuation can be skipped`() = runBlocking {
        val withLlm = pipeline.run("test.wav", useLlm = true)
        val withoutLlm = pipeline.run("test.wav", useLlm = false)

        // Тексты отличаются: с LLM появляется пунктуация
        assertTrue(
            "пунктуация должна менять текст",
            withLlm.lines.first().text != withoutLlm.lines.first().text,
        )
        // Но число реплик и таймкоды сохраняются
        assertEquals(withLlm.lines.size, withoutLlm.lines.size)
        assertEquals(withLlm.lines.first().startMs, withoutLlm.lines.first().startMs)
    }

    @Test
    fun `rtf is computed from processing time`() = runBlocking {
        val result = pipeline.run("test.wav")
        assertTrue("RTF должен быть неотрицательным", result.rtf >= 0.0)
    }

    @Test
    fun `prompt lines have required format`() = runBlocking {
        val result = pipeline.run("test.wav")
        val prompt = Merge.toPromptLines(result.lines)

        // Каждая строка вида «[MM:SS] Спикер N: текст»
        prompt.lines().filter { it.isNotBlank() }.forEach { line ->
            assertTrue(
                "строка не соответствует формату: $line",
                Regex("""^\[\d{2}:\d{2}\] Спикер \d+: .+""").matches(line),
            )
        }
    }
}
