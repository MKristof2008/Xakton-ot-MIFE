package ru.mifi.stenograf.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import ru.mifi.stenograf.asr.AsrEngine
import ru.mifi.stenograf.audio.AudioDecoder
import ru.mifi.stenograf.diar.Diarizer
import ru.mifi.stenograf.llm.LlmPost
import ru.mifi.stenograf.output.AsrSegment
import ru.mifi.stenograf.output.Merge
import ru.mifi.stenograf.output.Replica
import ru.mifi.stenograf.output.SpeakerTurn

/** Этапы обработки — для экрана прогресса. */
enum class Stage(val label: String) {
    DECODE("Декодирование аудио"),
    DIAR("Определение говорящих"),
    ASR("Распознавание речи"),
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
 *
 * Порядок принципиален: СНАЧАЛА диаризация, ПОТОМ распознавание по каждой реплике.
 *
 * Почему не наоборот. Whisper отдаёт длинные сегменты (20–30 с), которые накрывают
 * несколько смен говорящего. Если распознать всё аудио целиком и потом приписать
 * текст спикерам, строку приходится делить между людьми — по времени или поровну
 * по словам. И то и другое перемешивает речь: слова одного человека уезжают
 * другому. Распознавание внутри границ реплики такой проблемы не имеет по
 * построению: каждое слово физически принадлежит одному говорящему.
 *
 * Ни одного сетевого вызова: только MediaCodec, sherpa-onnx и локальная LLM.
 */
class TranscriptPipeline(private val context: Context) {

    companion object {
        private const val TAG = "Stenograf"

        /** Длинные реплики режем на куски такой длины (мс): Whisper работает окнами. */
        private const val MAX_UTTERANCE_MS = 25_000

        /** Пауза внутри речи одного человека, до которой куски склеиваются (мс). */
        private const val JOIN_GAP_MS = 700

        /** Минимальная длина фрагмента для распознавания: 0.5 с при 16 кГц. */
        private const val MIN_UTTERANCE_SAMPLES = 8_000

        /**
         * Ниже этой длины звука (мс) короткий текст почти всегда выдуман моделью:
         * настоящая реплика из одного-двух слов звучит не меньше 300 мс.
         */
        private const val SUSPICIOUS_MS = 400
    }

    private val weights = mapOf(
        Stage.DECODE to 0.04f,
        Stage.DIAR to 0.42f,
        Stage.ASR to 0.46f,
        Stage.MERGE to 0.03f,
        Stage.LLM to 0.05f,
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
            // Stage.DONE не имеет веса: это финальный маркер, а не этап с работой.
            val overall = if (stage == Stage.DONE) 1f
            else done + (weights[stage] ?: 0f) * frac.coerceIn(0f, 1f)
            onProgress(PipelineProgress(stage, frac, overall, msg))
        }

        fun checkCancel() = check(!isCancelled()) { "Обработка отменена" }

        // 1. Декодирование
        report(Stage.DECODE, 0f, "Читаем файл")
        var t = System.currentTimeMillis()
        val pcm = AudioDecoder.decode(context, uri)
        stagesMs[Stage.DECODE] = System.currentTimeMillis() - t
        done += (weights[Stage.DECODE] ?: 0f)
        checkCancel()

        // 2. Диаризация — кто и когда говорит
        Log.i(TAG, "stage DIAR start: ${pcm.samples.size} samples, ${pcm.durationMs} ms")
        report(Stage.DIAR, 0f, "Определяем говорящих")
        t = System.currentTimeMillis()
        val turns: List<SpeakerTurn> = runCatching {
            Diarizer(context).diarize(pcm.samples, numSpeakers) { f ->
                report(Stage.DIAR, f, "Определяем говорящих")
            }
        }.getOrElse { e ->
            // Диаризация не должна убивать результат: отдаём одного говорящего.
            Log.w(TAG, "diarization failed, falling back to single speaker", e)
            onProgress(
                PipelineProgress(
                    Stage.DIAR, 1f, done + (weights[Stage.DIAR] ?: 0f),
                    "Диаризация недоступна: ${e.message ?: "ошибка"}",
                ),
            )
            listOf(SpeakerTurn(0, pcm.durationMs.toInt(), "raw_0"))
        }
        stagesMs[Stage.DIAR] = System.currentTimeMillis() - t
        Log.i(TAG, "stage DIAR done: ${turns.size} turns in ${stagesMs[Stage.DIAR]} ms")
        done += (weights[Stage.DIAR] ?: 0f)
        checkCancel()

        // 3. Склейка turn-ов в реплики: подряд идущие куски одного спикера с
        //    короткой паузой — одна реплика, распознавать её надо целиком.
        val utterances = Merge.groupTurns(turns, JOIN_GAP_MS, MAX_UTTERANCE_MS)
        Log.i(TAG, "grouped ${turns.size} turns into ${utterances.size} utterances")

        // 4. Распознавание речи по каждой реплике отдельно
        report(Stage.ASR, 0f, "Распознаём речь (${utterances.size} реплик)")
        t = System.currentTimeMillis()
        val recognized = mutableListOf<Pair<SpeakerTurn, List<AsrSegment>>>()
        val engine = AsrEngine(context)
        try {
            var processedMs = 0L
            val totalMs = utterances.sumOf { (it.endMs - it.startMs).toLong() }.coerceAtLeast(1)
            for ((idx, u) in utterances.withIndex()) {
                checkCancel()
                val slice = slicePcm(pcm.samples, u.startMs, u.endMs)
                // Короткие фрагменты (вдох, скрип, обрывок слога) не распознаём:
                // на них Whisper выдаёт выдуманный текст («Мника», «ПОСИЗА»),
                // который потом неотличим от настоящей короткой реплики.
                if (slice.size < MIN_UTTERANCE_SAMPLES) continue
                val segs = engine.transcribe(slice).filter { seg ->
                    // Отдельно отсекаем короткий выдуманный текст внутри длинного куска.
                    !isLikelyHallucination(seg.text, seg.endMs - seg.startMs)
                }
                if (segs.isNotEmpty()) {
                    val shifted = segs.map { s ->
                        s.copy(startMs = s.startMs + u.startMs, endMs = s.endMs + u.startMs)
                    }
                    recognized += u to shifted
                }
                processedMs += (u.endMs - u.startMs)
                report(
                    Stage.ASR, processedMs.toFloat() / totalMs,
                    "Распознаём речь: ${idx + 1} из ${utterances.size}",
                )
            }
        } finally {
            engine.close()
        }
        stagesMs[Stage.ASR] = System.currentTimeMillis() - t
        Log.i(TAG, "stage ASR done: ${recognized.sumOf { it.second.size }} segments in ${stagesMs[Stage.ASR]} ms")
        done += (weights[Stage.ASR] ?: 0f)
        checkCancel()

        // 5. Сборка реплик: текст уже привязан к говорящему, остаётся нормализовать.
        report(Stage.MERGE, 0.5f, "Собираем реплики")
        t = System.currentTimeMillis()
        var replicas = Merge.buildFromRecognized(recognized)
        stagesMs[Stage.MERGE] = System.currentTimeMillis() - t
        Log.i(TAG, "stage MERGE done: ${replicas.size} replicas")
        done += (weights[Stage.MERGE] ?: 0f)
        checkCancel()

        // 6. Локальная LLM: пунктуация и регистр, без перефразирования
        report(Stage.LLM, 0f, "Обрабатываем текст локальной моделью")
        t = System.currentTimeMillis()
        replicas = LlmPost(context).polish(replicas) { f ->
            report(Stage.LLM, f, "Обрабатываем текст локальной моделью")
        }
        stagesMs[Stage.LLM] = System.currentTimeMillis() - t
        Log.i(TAG, "stage LLM done in ${stagesMs[Stage.LLM]} ms")
        done += (weights[Stage.LLM] ?: 0f)

        report(Stage.DONE, 1f, "Готово")

        return TranscriptResult(
            replicas = replicas,
            durationMs = pcm.durationMs,
            processingMs = System.currentTimeMillis() - started,
            stagesMs = stagesMs,
            speakerCount = replicas.map { it.speakerId }.distinct().size,
        )
    }

    /**
     * Признак галлюцинации Whisper на коротком звуке. Модель обучена выдавать
     * «что-то» даже из шума, поэтому из 100–300 мс тишины появляются странные
     * слова. Порог сознательно мягкий: настоящая короткая реплика («Да», «Нет»,
     * «Хорошо») тоже занимает мало времени, и резать её нельзя.
     */
    private fun isLikelyHallucination(text: String, durationMs: Int): Boolean {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        // Слишком длинное «слово» на очень коротком звуке — склейка случайных
        // токенов, речи такой длительности там физически не помещается.
        return durationMs < SUSPICIOUS_MS &&
            words.any { it.trim('.', ',', '!', '?', '-').length > 12 }
    }

    /** Вырезает отрезок PCM по миллисекундам с проверкой границ. */
    private fun slicePcm(samples: FloatArray, startMs: Int, endMs: Int): FloatArray {
        // 16 кГц = 16 сэмплов на миллисекунду.
        val from = (startMs.toLong() * 16).toInt().coerceIn(0, samples.size)
        val to = (endMs.toLong() * 16).toInt().coerceIn(from, samples.size)
        return samples.copyOfRange(from, to)
    }
}