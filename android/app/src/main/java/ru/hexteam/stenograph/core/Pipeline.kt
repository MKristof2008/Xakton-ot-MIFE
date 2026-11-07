package ru.hexteam.stenograph.core

import ru.hexteam.stenograph.engine.AsrEngine
import ru.hexteam.stenograph.engine.SpeakerEmbeddingEngine
import ru.hexteam.stenograph.engine.VoiceProfileStore
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
        /**
         * Отпечатки голосов говорящих: «speaker_1» → вектор.
         * Нужны, чтобы сохранить голос в базу или сверить со следующей записью.
         */
        val voiceEmbeddings: Map<String, FloatArray> = emptyMap(),
        /** Уверенные совпадения с базой голосов: «speaker_1» → имя. */
        val recognizedSpeakers: Map<String, String> = emptyMap(),
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
        /**
         * Пайплайн отпечатков голоса. null — голос не определяется
         * (экономит память на слабых устройствах).
         */
        voiceEngine: SpeakerEmbeddingEngine? = null,
        /** База голосов для узнавания. null — только считать отпечатки. */
        voiceStore: VoiceProfileStore? = null,
    ): Result {
        val started = System.currentTimeMillis()

        onStage(PipelineStage.ASR)
        // Память: модели весят много (ASR ~190 МБ, диаризация ~40 МБ,
        // LLM ~478 МБ). Держать их одновременно нельзя — на длинной записи
        // Android убивает процесс. Поэтому каждая модель выгружается сразу
        // после своего этапа, а результат этапа остаётся в обычных объектах.
        val segments = try {
            asr.transcribe(audioPath)
        } finally {
            asr.release()
        }
        if (segments.isEmpty()) {
            return Result(emptyList(), TranscriptJson.toJson(emptyList()), 0L, 0L)
        }

        onStage(PipelineStage.DIARIZATION)
        val speakers = try {
            diarization.diarize(audioPath, expectedSpeakers)
        } finally {
            diarization.release()
        }

        // Отпечатки голосов: считаются по тем же сегментам диаризации.
        // Делаем до слияния, пока модель эмбеддингов ещё загружена.
        var voiceEmbeddings: Map<String, FloatArray> = emptyMap()
        var recognized: Map<String, String> = emptyMap()
        if (voiceEngine != null && speakers.isNotEmpty()) {
            voiceEmbeddings = runCatching {
                voiceEngine.embed(audioPath, speakers)
            }.getOrElse {
                // Отпечаток — улучшение, а не основа: без него расшифровка
                // остаётся полной, просто говорящие будут безымянными.
                emptyMap()
            }
            if (voiceStore != null && voiceEmbeddings.isNotEmpty()) {
                recognized = voiceStore.matchAll(voiceEmbeddings)
                    .mapValues { it.value.name }
            }
        }

        onStage(PipelineStage.MERGE)
        var lines = Merge.merge(segments, speakers)

        if (useLlm && lines.isNotEmpty()) {
            onStage(PipelineStage.LLM)
            lines = try {
                applyLlm(lines)
            } finally {
                llm.release()
            }
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
            voiceEmbeddings = voiceEmbeddings,
            recognizedSpeakers = recognized,
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
