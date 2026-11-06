package ru.mifi.stenograf.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import ru.mifi.stenograf.model.ModelStore
import ru.mifi.stenograf.output.AsrSegment

/**
 * Распознавание речи на устройстве: sherpa-onnx + Whisper (multilingual, русский).
 *
 * Никакой сети: модель лежит в filesDir, рантайм — нативный sherpa-onnx-jni из AAR.
 * Таймкоды считаются от начала файла: длинное аудио режем на окна по 20 с с
 * перекрытием 2 с, к каждому окну прибавляем его оффсет.
 */
class AsrEngine(context: Context, private val threads: Int = defaultThreads()) {

    private val appContext = context.applicationContext
    private var recognizer: OfflineRecognizer? = null

    companion object {
        const val WINDOW_SEC = 20f
        const val OVERLAP_SEC = 2f

        fun defaultThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    }

    private fun ensureLoaded(): OfflineRecognizer =
        recognizer ?: run {
            val dir = ModelStore.asrDir(appContext)
            val cfg = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = dir.resolve(ModelStore.WHISPER_ENCODER).apply { ensure() }.absolutePath,
                        decoder = dir.resolve(ModelStore.WHISPER_DECODER).apply { ensure() }.absolutePath,
                        language = "ru",
                        task = "transcribe",
                        // Таймкоды по токенам нужны для честной разбивки на реплики.
                        enableTokenTimestamps = true,
                        enableSegmentTimestamps = true,
                    ),
                    tokens = dir.resolve(ModelStore.WHISPER_TOKENS).apply { ensure() }.absolutePath,
                    modelType = "whisper",
                    numThreads = threads,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            OfflineRecognizer(config = cfg).also { recognizer = it }
        }

    private fun java.io.File.ensure() {
        require(exists()) { "Нет файла модели: $absolutePath" }
    }

    /**
     * @param samples PCM float32, 16 кГц, моно.
     * @return сегменты с временем от начала записи.
     */
    fun transcribe(samples: FloatArray, onProgress: (Float) -> Unit = {}): List<AsrSegment> {
        val rec = ensureLoaded()
        val windowSize = (WINDOW_SEC * 16_000).toInt()
        val overlap = (OVERLAP_SEC * 16_000).toInt()
        val step = windowSize - overlap
        if (samples.isEmpty()) return emptyList()

        val out = mutableListOf<AsrSegment>()
        var offset = 0
        var lastEmittedEndMs = -1

        while (offset < samples.size) {
            val end = minOf(offset + windowSize, samples.size)
            val chunk = samples.copyOfRange(offset, end)

            val stream = rec.createStream()
            stream.acceptWaveform(chunk, 16_000)
            rec.decode(stream)
            val result = rec.getResult(stream)
            stream.release()

            val chunkStartMs = offset * 1000 / 16_000
            val text = result.text.trim()
            if (text.isNotEmpty()) {
                val ts = result.timestamps
                // timestamps — сикунды в пределах окна (если включены)
                if (ts.isNotEmpty() && ts.size > 1) {
                    for (seg in splitIntoSegments(result.tokens, ts, text)) {
                        val sMs = chunkStartMs + (seg.first * 1000).toInt()
                        val eMs = chunkStartMs + (seg.second * 1000).toInt()
                        if (eMs > lastEmittedEndMs + 200) { // не дублируем перекрытие
                            out += AsrSegment(sMs, eMs, seg.third)
                            lastEmittedEndMs = maxOf(lastEmittedEndMs, eMs)
                        }
                    }
                } else {
                    val sMs = chunkStartMs
                    val eMs = chunkStartMs + chunk.size * 1000 / 16_000
                    if (eMs > lastEmittedEndMs + 200) {
                        out += AsrSegment(sMs, eMs, text)
                        lastEmittedEndMs = eMs
                    }
                }
            }

            onProgress((end.toFloat() / samples.size).coerceIn(0f, 1f))
            if (end >= samples.size) break
            offset += step
        }
        return out.filter { it.text.isNotBlank() && it.endMs > it.startMs }
    }

    /**
     * Группирует токены в сегменты Whisper по паузам: токены с extremely
     * большим разрывом начинают новую фразу. Возвращает (start, end, text).
     */
    private fun splitIntoSegments(
        tokens: Array<String>,
        ts: FloatArray,
        fullText: String,
    ): List<Triple<Float, Float, String>> {
        if (tokens.size != ts.size) {
            val endSec = ts.lastOrNull() ?: fullText.length.toFloat()
            return listOf(Triple(ts.firstOrNull() ?: 0f, endSec, fullText))
        }
        val out = mutableListOf<Triple<Float, Float, String>>()
        var buf = StringBuilder()
        var segStart = ts.firstOrNull() ?: 0f
        var prevEnd = segStart
        for (i in tokens.indices) {
            val t = ts[i]
            if (buf.isNotEmpty() && (t - prevEnd) > 1.0f) {
                out += Triple(segStart, prevEnd, buf.toString().trim())
                buf = StringBuilder()
                segStart = t
            }
            buf.append(tokens[i])
            prevEnd = t
        }
        if (buf.isNotEmpty()) out += Triple(segStart, prevEnd, buf.toString().trim())
        return out.filter { it.third.isNotBlank() }.ifEmpty {
            listOf(Triple(ts.firstOrNull() ?: 0f, ts.lastOrNull() ?: 0f, fullText))
        }
    }

    fun close() {
        recognizer?.release()
        recognizer = null
    }
}