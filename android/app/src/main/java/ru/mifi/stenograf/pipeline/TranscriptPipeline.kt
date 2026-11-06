package ru.mifi.stenograf.pipeline

import android.content.Context
import android.net.Uri
import ru.mifi.stenograf.asr.AsrEngine
import ru.mifi.stenograf.audio.AudioDecoder
import ru.mifi.stenograf.diar.Diarizer
import ru.mifi.stenograf.llm.LlmPost
import ru.mifi.stenograf.output.Merge
import ru.mifi.stenograf.output.Replica
import ru.mifi.stenograf.output.SpeakerTurn

/** Этапы обработки — для экрана прогресса. */
enum class Stage(val label: String) {
    DECODE("Декодирование аудио"),
    ASR("Распознавание речи"),
    DIAR("Определение говорящих"),
    MERGE("Сборка реплик"),
    LLM("Обработка текста"),
    DONE("Готово"),
}

data class PipelineProgress(
    val stage: Stage,
    val stageFraction: Float,
    val overallFraction: Float,
    val message: String = "",
)

data class TranscriptResult(
    val replicas: List<Replica>,
    val durationMs: Long,
    val processingMs: Long,
    val stagesMs: Map<Stage, Long>,
    val speakerCount: Int,
) {
    /** RTF = время_обработки / длительность_аудио. Меньше 1.0 — быстрее реального времени. */
    val rtf: Float get() = if (durationMs > 0) processingMs.toFloat() / durationMs else 0f
}

/**
 * Сквозной офлайн-пайплайн: аудио → реплики.
 * Ни одного сетевого вызова: только MediaCodec, sherpa-onnx и (опционально) локальная LLM.
 */
class TranscriptPipeline(private val context: Context) {

    // Веса этапов для общего прогресса: диаризация самая долгая.
    private val weights = mapOf(
        Stage.DECODE to 0.05f,
        Stage.ASR to 0.45f,
        Stage.DIAR to 0.40f,
        Stage.MERGE to 0.03f,
        Stage.LLM to 0.07f,
    )

    fun run(
        uri: Uri,
        numSpeakers: Int?,
        onProgress: (PipelineProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): TranscriptResult {
        val started = System.currentTimeMillis()
        val stagesMs = mutableMapOf<Stage, Long>()
        var done = 0f

        fun report(stage: Stage, frac: Float, msg: String = "") {
            val overall = done + weights.getValue(stage) * frac.coerceIn(0f, 1f)
            onProgress(PipelineProgress(stage, frac, overall, msg))
        }

        fun checkCancel() = check(!isCancelled()) { "Обработка отменена" }

        // 1. Декодирование
        report(Stage.DECODE, 0f, "Читаем файл")
        var t = System.currentTimeMillis()
        val pcm = AudioDecoder.decode(context, uri)
        stagesMs[Stage.DECODE] = System.currentTimeMillis() - t
        done += weights.getValue(Stage.DECODE)
        checkCancel()

        // 2. ASR
        report(Stage.ASR, 0f, "Распознаём речь (${pcm.durationMs / 1000} с аудио)")
        t = System.currentTimeMillis()
        val asr = AsrEngine(context).use { engine ->
            engine.transcribe(pcm.samples) { f -> report(Stage.ASR, f, "Распознаём речь") }
        }
        stagesMs[Stage.ASR] = System.currentTimeMillis() - t
        done += weights.getValue(Stage.ASR)
        checkCancel()

        // 3. Диаризация
        report(Stage.DIAR, 0f, "Определяем говорящих")
        t = System.currentTimeMillis()
        val turns: List<SpeakerTurn> = runCatching {
            Diarizer(context).diarize(pcm.samples, numSpeakers) { f ->
                report(Stage.DIAR, f, "Определяем говорящих")
            }
        }.getOrElse { e ->
            // Диаризация не должна убивать весь результат: отдаём одного спикера.
            onProgress(PipelineProgress(Stage.DIAR, 1f, done + weights.getValue(Stage.DIAR),
                "Диаризация недоступна: ${e.message ?: "ошибка"}"))
            listOf(SpeakerTurn(0, (pcm.samples.size * 1000L / 16_000).toInt(), "raw_0"))
        }
        stagesMs[Stage.DIAR] = System.currentTimeMillis() - t
        done += weights.getValue(Stage.DIAR)
        checkCancel()

        // 4. Слияние
        report(Stage.MERGE, 0.5f, "Собираем реплики")
        t = System.currentTimeMillis()
        var replicas = Merge.build(asr, turns)
        stagesMs[Stage.MERGE] = System.currentTimeMillis() - t
        done += weights.getValue(Stage.MERGE)
        checkCancel()

        // 5. Локальная LLM: пунктуация и регистр, без перефразирования
        report(Stage.LLM, 0f, "Обрабатываем текст локальной моделью")
        t = System.currentTimeMillis()
        replicas = LlmPost(context).polish(replicas) { f ->
            report(Stage.LLM, f, "Обрабатываем текст локальной моделью")
        }
        stagesMs[Stage.LLM] = System.currentTimeMillis() - t
        done += weights.getValue(Stage.LLM)

        report(Stage.DONE, 1f, "Готово")

        return TranscriptResult(
            replicas = replicas,
            durationMs = pcm.durationMs,
            processingMs = System.currentTimeMillis() - started,
            stagesMs = stagesMs,
            speakerCount = replicas.map { it.speakerId }.distinct().size,
        )
    }
}

/** use{} для AsrEngine: гарантированно освобождает нативную модель. */
private inline fun <T> AsrEngine.use(block: (AsrEngine) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }