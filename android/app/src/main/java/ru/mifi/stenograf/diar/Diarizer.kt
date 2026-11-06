package ru.mifi.stenograf.diar

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import ru.mifi.stenograf.model.ModelStore
import ru.mifi.stenograf.output.SpeakerTurn
import java.io.File

/**
 * Диаризация на устройстве: pyannote segmentation-3.0 + WeSpeaker/3D-Speaker embeddings
 * + агломеративная кластеризация (всё внутри sherpa-onnx, офлайн).
 */
class Diarizer(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        /** Порог кластеризации: выше — меньше спикеров. Подбирается на тестовых записях. */
        const val DEFAULT_THRESHOLD = 0.5f
    }

    fun diarize(
        samples: FloatArray,
        numSpeakers: Int?,
        threshold: Float = DEFAULT_THRESHOLD,
        onProgress: (Float) -> Unit = {},
    ): List<SpeakerTurn> {
        val dir = ModelStore.diarDir(appContext)
        val seg = File(dir, ModelStore.SEGMENTATION)
        val emb = File(dir, ModelStore.EMBEDDING)
        require(seg.exists()) { "Нет модели сегментации: ${seg.absolutePath}" }
        require(emb.exists()) { "Нет модели эмбеддингов: ${emb.absolutePath}" }

        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = seg.absolutePath),
                numThreads = 1,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = emb.absolutePath,
                numThreads = 1,
            ),
            clustering = FastClusteringConfig(
                numClusters = numSpeakers ?: -1,
                threshold = threshold,
            ),
            minDurationOn = 0.2f,
            minDurationOff = 0.5f,
        )

        val sd = OfflineSpeakerDiarization(config = config)
        try {
            // Лямбда передаётся явно: аргумент arg со значением по умолчанию идёт
            // последним, поэтому trailing-lambda привязалась бы к нему.
            val callback: (Int, Int, Long) -> Int = { done, total, _ ->
                if (total > 0) onProgress(done.toFloat() / total.coerceAtLeast(1))
                0 // 0 = продолжаем; ненулевое значение прервало бы обработку
            }
            val raw = sd.processWithCallback(samples, callback, 0L)
            return raw.map { s ->
                SpeakerTurn(
                    startMs = (s.start * 1000).toInt(),
                    endMs = (s.end * 1000).toInt(),
                    speakerRaw = "raw_${s.speaker}",
                )
            }.filter { it.endMs > it.startMs }
        } finally {
            sd.release()
        }
    }
}