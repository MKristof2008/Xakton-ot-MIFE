package ru.hexteam.stenograph.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.hexteam.stenograph.core.SpeakerSegment
import java.io.File

/**
 * Диаризация на устройстве: sherpa-onnx.
 *
 * Состав (всё офлайн, CPU):
 *  - сегментация речи — pyannote segmentation-3.0 (ONNX);
 *  - эмбеддинги говорящего — 3D-Speaker ERes2Net (ONNX);
 *  - кластеризация — агломеративная внутри sherpa-onnx, число спикеров
 *    определяется автоматически по порогу или задаётся пользователем.
 *
 * Почему sherpa-onnx, а не отдельный pyannote-порт: один рантайм даёт готовую
 * связку сегментация + эмбеддинги + кластеризация с корректной склейкой
 * соседних фрагментов, и он же используется для ASR — в APK один движок.
 *
 * Вход: WAV 16 кГц / моно (его пишет [AudioConverter]).
 */
class SherpaDiarizationEngine(
    context: Context,
    private val threshold: Float = DEFAULT_THRESHOLD,
) : DiarizationEngine {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "Diarization"

        /**
         * Порог кластеризации: выше — меньше говорящих, ниже — больше.
         *
         * 0.9, а не 0.5 по умолчанию из примеров sherpa-onnx: подобрано
         * замером DER на диалоге двух человек (SM-S938B):
         *   0.70–0.80 → 3 говорящих, DER 0.167
         *   0.85–0.95 → 2 говорящих, DER 0.082
         * Значение взято из середины устойчивого плато, чтобы небольшие
         * отличия в записи не переключали число говорящих.
         * Пересматривать при смене модели эмбеддингов — только по замеру.
         */
        const val DEFAULT_THRESHOLD = 0.9f

        /** Минимальная длина фрагмента речи/паузы, чтобы не дробить реплику. */
        private const val MIN_ON = 0.2f
        private const val MIN_OFF = 0.5f
    }

    override fun isReady(): Boolean = ModelStore.diarizationReady(appContext)

    override suspend fun diarize(
        audioPath: String,
        expectedSpeakers: Int?,
    ): List<SpeakerSegment> = withContext(Dispatchers.Default) {
        check(isReady()) {
            "Модели диаризации не найдены. Ожидались ${ModelStore.SEGMENTATION} и " +
                "${ModelStore.EMBEDDING} в ${ModelStore.diarDir(appContext).absolutePath}"
        }

        // WAV уже подготовлен ASR-этапом; если нет — готовим сами.
        val wav = AudioPrep.prepare(appContext, File(audioPath), keep = true)
            ?: throw IllegalStateException("Не удалось декодировать аудио в WAV 16 кГц")

        val (samples, sampleRate) = WavReader.read(wav)
        if (samples.isEmpty()) return@withContext emptyList()
        check(sampleRate == 16_000) { "Диаризация ждёт 16 кГц, получено $sampleRate" }

        val engine = createEngine(expectedSpeakers)
        try {
            val started = System.currentTimeMillis()
            // Лямбда передаётся явно: `arg` — последний параметр со значением по
            // умолчанию, и trailing-lambda привязалась бы к нему, а не к callback.
            val callback: (Int, Int, Long) -> Int = { done, total, _ ->
                if (done % 20 == 0) Log.d(TAG, "диаризация: $done/$total")
                0 // 0 = продолжать; ненулевое значение прервало бы обработку
            }
            val raw = engine.processWithCallback(samples, callback, 0L)
            val segments = SpeakerPostProcess.apply(
                raw.map { s ->
                    SpeakerSegment(
                        startMs = (s.start * 1000).toLong(),
                        endMs = (s.end * 1000).toLong(),
                        speaker = "speaker_${s.speaker + 1}",
                    )
                },
            )

            Log.i(
                TAG,
                "диаризация: ${segments.size} интервалов, " +
                    "${segments.map { it.speaker }.distinct().size} говорящих, " +
                    "${System.currentTimeMillis() - started} мс на " +
                    "${samples.size * 1000L / sampleRate} мс аудио",
            )
            segments
        } finally {
            engine.release()
        }
    }

    private fun createEngine(expectedSpeakers: Int?): OfflineSpeakerDiarization {
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = ModelStore.segmentation(appContext).absolutePath,
                ),
                numThreads = threadsForSegmentation(),
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = ModelStore.embedding(appContext).absolutePath,
                numThreads = threadsForEmbedding(),
            ),
            clustering = FastClusteringConfig(
                // -1 — определить число говорящих автоматически по порогу.
                numClusters = expectedSpeakers ?: -1,
                threshold = threshold,
            ),
            minDurationOn = MIN_ON,
            minDurationOff = MIN_OFF,
        )
        return OfflineSpeakerDiarization(config = config)
    }

    /**
     * Эмбеддинги считаются по каждому фрагменту — выигрыш от потоков есть,
     * но на слабых устройствах больше 4 потоков упираются в память.
     */
    private fun threadsForEmbedding(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Сегментация идёт целыми окнами: 2 потоков достаточно. */
    private fun threadsForSegmentation(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 2)
}
