package ru.hexteam.stenograph.core

import ru.hexteam.stenograph.engine.AsrEngine
import ru.hexteam.stenograph.engine.DiarizationEngine
import ru.hexteam.stenograph.engine.LlmEngine

/**
 * Сквозной пайплайн: аудио → ASR → диаризация → сшивка → LLM → готовый JSON.
 *
 * Все вычисления идут на устройстве. Ни одного сетевого вызова.
 */
class Pipeline(
    private val asr: AsrEngine,
    private val diarization: DiarizationEngine,
    private val llm: LlmEngine,
) {

    data class Result(
        val lines: List<TranscriptLine>,
        val json: String,
        val durationMs: Long,
        val processingMs: Long,
    ) {
        val rtf: Double
            get() = if (durationMs <= 0L) 0.0 else processingMs.toDouble() / durationMs
    }

    /**
     * @param onStage колбэк для отображения прогресса
     */
    suspend fun run(
        audioPath: String,
        expectedSpeakers: Int? = null,
        useLlm: Boolean = true,
        onStage: (PipelineStage) -> Unit = {},
    ): Result {
        val started = System.currentTimeMillis()

        onStage(PipelineStage.ASR)
        val segments = asr.transcribe(audioPath)
        if (segments.isEmpty()) {
            return Result(emptyList(), TranscriptJson.toJson(emptyList()), 0L, 0L)
        }

        onStage(PipelineStage.DIARIZATION)
        val speakers = diarization.diarize(audioPath, expectedSpeakers)

        onStage(PipelineStage.MERGE)
        var lines = Merge.merge(segments, speakers)

        if (useLlm && lines.isNotEmpty()) {
            onStage(PipelineStage.LLM)
            lines = applyLlm(lines)
        }

        onStage(PipelineStage.DONE)
        val finished = System.currentTimeMillis()

        // Длительность записи — по последнему сегменту, пока нет метаданных файла.
        val durationMs = lines.maxOfOrNull { it.endMs } ?: 0L

        return Result(
            lines = lines,
            json = TranscriptJson.toJson(lines),
            durationMs = durationMs,
            processingMs = finished - started,
        )
    }

    /**
     * Пунктуация через локальную LLM: текст чистится построчно,
     * разметка «спикер + таймкод» сохраняется без изменений.
     */
    private suspend fun applyLlm(lines: List<TranscriptLine>): List<TranscriptLine> {
        return lines.map { line ->
            val cleaned = runCatching { llm.clean(line.text) }.getOrDefault(line.text)
            line.copy(text = cleaned.trim().ifEmpty { line.text })
        }
    }
}
