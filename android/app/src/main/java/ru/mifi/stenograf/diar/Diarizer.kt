package ru.mifi.stenograf.diar

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
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
        /**
         * Порог агломеративной кластеризации: выше — меньше говорящих.
         * 0.5 на тестовой записи давал 3 кластера вместо 2 (лишний на одном
         * тембре). 0.75 склеивает переусердствовавшее разделение, но всё ещё
         * различает двух разных людей. Значение подобрано замером DER.
         */
        const val DEFAULT_THRESHOLD = 0.75f

        /**
         * Косинусная близость центроидов, выше которой два кластера — один голос.
         * Замерено на живой записи двух человек: у одного голоса 0.70–0.78,
         * у разных людей 0.31–0.48. Берём 0.65 — посередине, с запасом в обе
         * стороны: голоса, разведённые по разным кластерам, не склеятся.
         */
        private const val MERGE_SIMILARITY = 0.65

        /** Кластер короче этого — не участник, а шум: 3 с речи. */
        private const val MIN_CLUSTER_SEC = 3.0

        /** Фрагменты короче 0.5 с не дают устойчивого эмбеддинга. */
        private const val MIN_SEGMENT_SEC = 0.5f
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
            onProgress(0.7f)
            val turns = raw.map { s ->
                SpeakerTurn(
                    startMs = (s.start * 1000).toInt(),
                    endMs = (s.end * 1000).toInt(),
                    speakerRaw = "raw_${s.speaker}",
                )
            }.filter { it.endMs > it.startMs }

            // Постобработка: склеиваем кластеры одного голоса.
            // Основная кластеризация работает по отдельным окнам и на коротких
            // репликах ошибается — один человек разъезжается на 2–5 кластеров.
            // Схожесть центроидов решает это точно: у одного голоса она 0.70–0.78,
            // у разных людей 0.31–0.48.
            val refined = if (numSpeakers != null) turns else mergeSameVoices(turns, samples)
            onProgress(1f)
            return refined
        } finally {
            sd.release()
        }
    }

    /**
     * Склеивает кластеры, у которых усреднённые эмбеддинги почти совпадают.
     *
     * Зачем: sherpa-onnx кластеризует посегментно, и на коротких репликах
     * («Да», «Ну») эмбеддинг шумный — тот же голос попадает в новый кластер.
     * В результате на записи двух человек появляется третий-пятый «спикер»
     * с одной-двумя репликами. Здесь мы считаем центроид каждого кластера
     * (взвешенный по длине речи — короткие реплики дают шумный вектор)
     * и сливаем пары с косинусной близостью выше [MERGE_SIMILARITY].
     *
     * Совсем маленькие кластеры (меньше [MIN_CLUSTER_SEC] речи) сливаются
     * с ближайшим крупным всегда: столько речи даёт случайный шум, а не
     * отдельный участник. Если он всё-таки отдельный — он останется в тексте
     * привязанным к тому голосу, на который больше всего похож.
     */
    private fun mergeSameVoices(turns: List<SpeakerTurn>, samples: FloatArray): List<SpeakerTurn> {
        if (turns.size < 2) return turns

        val ex = runCatching {
            SpeakerEmbeddingExtractor(
                assetManager = appContext.assets,
                config = SpeakerEmbeddingExtractorConfig(
                    model = embeddingPath(),
                    numThreads = threads,
                ),
            )
        }.getOrNull() ?: return turns

        try {
            val bySpeaker = LinkedHashMap<String, MutableList<Pair<FloatArray, Float>>>()
            for (t in turns) {
                val seconds = (t.endMs - t.startMs) / 1000f
                if (seconds < MIN_SEGMENT_SEC) continue
                val vec = runCatching { embed(ex, samples, t.startMs, t.endMs) }.getOrNull() ?: continue
                bySpeaker.getOrPut(t.speakerRaw) { mutableListOf() }.add(vec to seconds)
            }
            if (bySpeaker.size < 2) return turns

            val centroids = bySpeaker.mapValues { (_, items) -> weightedCentroid(items) }
            val speech = bySpeaker.mapValues { (_, items) -> items.sumOf { it.second.toDouble() } }

            // union-find: сливаем кластеры одного голоса
            val parent = centroids.keys.associateWith { it }.toMutableMap()
            fun find(x: String): String {
                var c = x
                while (parent.getValue(c) != c) c = parent.getValue(c)
                return c
            }
            val keys = centroids.keys.toList()
            for (i in keys.indices) {
                for (j in i + 1 until keys.size) {
                    val a = centroids.getValue(keys[i])
                    val b = centroids.getValue(keys[j])
                    if (cosine(a, b) >= MERGE_SIMILARITY) parent[find(keys[j])] = find(keys[i])
                }
            }

            // Мелкие кластеры — в ближайший крупный
            val groups = keys.groupBy { find(it) }
            val big = groups.values.filter { g -> g.sumOf { speech.getValue(it) } >= MIN_CLUSTER_SEC }
            if (big.isNotEmpty()) {
                for (g in groups.values) {
                    if (g.sumOf { speech.getValue(it) } >= MIN_CLUSTER_SEC) continue
                    val rep = centroids.getValue(g.first())
                    val best = big.maxByOrNull { grp ->
                        grp.maxOf { k -> cosine(rep, centroids.getValue(k)) }
                    } ?: continue
                    parent[find(g.first())] = find(best.first())
                }
            }

            return turns.map { t -> t.copy(speakerRaw = "raw_" + find(t.speakerRaw)) }
        } finally {
            runCatching { ex.release() }
        }
    }

    private fun embeddingPath(): String =
        File(ModelStore.diarDir(appContext), ModelStore.EMBEDDING).absolutePath

    private fun embed(
        ex: SpeakerEmbeddingExtractor,
        samples: FloatArray,
        startMs: Int,
        endMs: Int,
    ): FloatArray {
        val from = (startMs.toLong() * 16).toInt().coerceIn(0, samples.size)
        val to = (endMs.toLong() * 16).toInt().coerceIn(from, samples.size)
        val chunk = samples.copyOfRange(from, to)
        val stream = ex.createStream()
        stream.acceptWaveform(chunk, 16_000)
        stream.inputFinished()
        require(ex.isReady(stream)) { "короткий фрагмент" }
        val vec = ex.compute(stream)
        stream.release()
        return vec
    }

    /** Центроид, взвешенный по длине речи: короткие реплики дают шумный вектор. */
    private fun weightedCentroid(items: List<Pair<FloatArray, Float>>): FloatArray {
        val dim = items.first().first.size
        val sum = DoubleArray(dim)
        var total = 0.0
        for ((vec, w) in items) {
            total += w
            for (i in 0 until dim) sum[i] += vec[i] * w
        }
        val norm = kotlin.math.sqrt(sum.sumOf { it * it }).takeIf { it > 0 } ?: 1.0
        return FloatArray(dim) { (sum[it] / norm).toFloat() }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val den = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (den <= 0.0) 0.0 else dot / den
    }
}