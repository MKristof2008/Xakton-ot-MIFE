package ru.hexteam.stenograph.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.hexteam.stenograph.core.SpeakerSegment
import java.io.File
import java.io.RandomAccessFile

/**
 * Отпечаток голоса говорящего (speaker embedding).
 *
 * Зачем: диаризация отвечает «сколько людей и кто когда говорил», но не
 * отвечает «это тот же человек, что был в прошлой записи». Эмбеддинг —
 * вектор, который зависит только от тембра и манеры речи, поэтому два
 * фрагмента одного человека дают близкие векторы, а разных — далёкие.
 *
 * Модель та же, что у диаризации (3D-Speaker ERes2Net), — второй раз её
 * в APK не кладём. Считаем по сегментам каждого говорящего и усредняем:
 * один короткий сегмент шумит, среднее по нескольким устойчивее.
 */
class SpeakerEmbeddingEngine(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private var extractor: SpeakerEmbeddingExtractor? = null

    companion object {
        private const val TAG = "SpeakerEmbedding"

        /** Меньше этого фрагмент не даёт надёжного отпечатка (мс). */
        const val MIN_SEGMENT_MS = 500L

        /** Больше этого не нужно: ухудшается не точность, а только время. */
        const val MAX_SEGMENT_MS = 15_000L

        private const val SAMPLE_RATE = 16_000
    }

    fun isReady(): Boolean = ModelStore.embedding(appContext).let { it.isFile && it.length() > 0 }

    /**
     * Считает отпечаток для каждого говорящего.
     *
     * @return «speaker_N» → единичный вектор; говорящие, для которых не
     *         нашлось достаточно речи, в результат не попадают.
     */
    suspend fun embed(
        audioPath: String,
        speakers: List<SpeakerSegment>,
    ): Map<String, FloatArray> = withContext(Dispatchers.Default) {
        if (speakers.isEmpty() || !isReady()) return@withContext emptyMap()

        val wav = AudioPrep.prepare(appContext, File(audioPath), keep = true) ?: return@withContext emptyMap()
        val (samples, rate) = WavReader.read(wav)
        if (samples.isEmpty()) return@withContext emptyMap()

        val ex = ensureExtractor() ?: return@withContext emptyMap()
        val result = mutableMapOf<String, FloatArray>()

        for ((speaker, segments) in speakers.groupBy { it.speaker }) {
            val vectors = segments.mapNotNull { seg ->
                val audio = cut(samples, rate, seg.startMs, seg.endMs) ?: return@mapNotNull null
                val emb = runCatching { compute(ex, audio, rate) }.getOrNull()
                emb
            }
            if (vectors.isEmpty()) continue
            val mean = mean(vectors)
            val norm = norm(mean)
            if (norm > 0f) {
                result[speaker] = FloatArray(mean.size) { mean[it] / norm }
                Log.i(TAG, "$speaker: ${vectors.size} фрагм., dim=${mean.size}")
            }
        }
        result
    }

    private fun ensureExtractor(): SpeakerEmbeddingExtractor? {
        extractor?.let { return it }
        val model = ModelStore.embedding(appContext)
        if (!model.isFile) {
            Log.w(TAG, "нет модели эмбеддингов: ${model.absolutePath}")
            return null
        }
        return runCatching {
            SpeakerEmbeddingExtractor(
                config = SpeakerEmbeddingExtractorConfig(
                    model = model.absolutePath,
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 2),
                ),
            )
        }.onFailure { Log.e(TAG, "не удалось загрузить модель: ${it.message}") }
            .getOrNull()
            ?.also { extractor = it }
    }

    private fun compute(
        ex: SpeakerEmbeddingExtractor,
        audio: FloatArray,
        rate: Int,
    ): FloatArray? {
        val stream = ex.createStream()
        return try {
            stream.acceptWaveform(audio, rate)
            stream.inputFinished()
            if (!ex.isReady(stream)) return null
            ex.compute(stream)
        } finally {
            stream.release()
        }
    }

    /** Вырезка куска записи по таймкодам; слишком длинные — обрезаем. */
    private fun cut(samples: FloatArray, rate: Int, startMs: Long, endMs: Long): FloatArray? {
        val from = (startMs * rate / 1000).toInt().coerceIn(0, samples.size)
        val to = (minOf(endMs, startMs + MAX_SEGMENT_MS) * rate / 1000)
            .toInt().coerceIn(from, samples.size)
        if (to - from < MIN_SEGMENT_MS * rate / 1000) return null
        return samples.copyOfRange(from, to)
    }

    private fun mean(vectors: List<FloatArray>): FloatArray {
        val out = FloatArray(vectors.first().size)
        for (v in vectors) {
            for (i in out.indices) out[i] += v[i]
        }
        val n = vectors.size.toFloat()
        for (i in out.indices) out[i] = out[i] / n
        return out
    }

    private fun norm(v: FloatArray): Float =
        kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()

    override fun close() {
        extractor?.release()
        extractor = null
    }
}