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
class Diarizer(
    context: Context,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
) {

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
                numThreads = threads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = emb.absolutePath,
                numThreads = threads,
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
            // processWithCallback НЕ используем: JNI-обвязка sherpa-onnx ищет в
            // переданном объекте метод invoke(IIJ)Ljava/lang/Integer;, а Kotlin-лямбда
            // компилируется в invoke(Object,Object,Object)Object -> NoSuchMethodError,
            // JNI abort и аварийное завершение процесса. См. tombstone 2026-11-07.
            onProgress(0.05f)
            val raw = sd.process(samples)
            onProgress(1f)
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