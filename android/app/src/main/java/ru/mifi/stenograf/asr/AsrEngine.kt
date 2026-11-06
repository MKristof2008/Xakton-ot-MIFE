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
        /**
         * Длина окна. Whisper обрабатывает окно целиком, поэтому слишком длинное
         * окно замедляет инференс без выигрыша в точности.
         */
        const val WINDOW_SEC = 30f

        /**
         * Перекрытие окон. Раньше было 2 с, но это давало повтор фраз на границах
         * и заметно портило WER (движок не может надёжно склеить два прогона:
         * Whisper на разных окнах пишет одну и ту же фразу по-разному).
         * Ноль — на стыке возможна потеря одного слова; этим и платим за отсутствие
         * дублей. Слово-«перебежчик» видно в отчёте как одна вставка/удаление.
         */
        const val OVERLAP_SEC = 0f

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
                        // Окна перекрываются, поэтому начало нового окна повторяет
                        // хвост предыдущего. Отрезаем только совпавший префикс, а
                        // остаток фразы сохраняем — терять новую речь нельзя.
                        val fresh = trimRepeatedPrefix(seg.third, out)
                        if (fresh.isNotBlank() && eMs > lastEmittedEndMs) {
                            out += AsrSegment(maxOf(sMs, 0), eMs, fresh)
                            lastEmittedEndMs = maxOf(lastEmittedEndMs, eMs)
                        }
                    }
                } else {
                    val sMs = chunkStartMs
                    val eMs = chunkStartMs + chunk.size * 1000 / 16_000
                    val fresh = trimRepeatedPrefix(text, out)
                    if (fresh.isNotBlank() && eMs > lastEmittedEndMs) {
                        out += AsrSegment(sMs, eMs, fresh)
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

    /**
     * Отрезает от начала нового сегмента те слова, которыми уже закончился
     * принятый текст: так уходит повтор из-за перекрытия окон, но новая речь
     * в конце фразы сохраняется. Сравнение по нормализованным словам, потому что
     * Whisper на границе окна пишет ту же фразу с другой пунктуацией.
     */
    private fun trimRepeatedPrefix(text: String, already: List<AsrSegment>): String {
        if (already.isEmpty()) return text
        // Перекрытие — 1–2 секунды речи, это самое большее два последних сегмента.
        val oldWords = words(already.takeLast(2).joinToString(" ") { it.text })
        if (oldWords.isEmpty()) return text

        val raw = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val newNorm = raw.map { normalizeWord(it) }.filter { it.isNotEmpty() }
        if (newNorm.isEmpty()) return text

        val maxN = minOf(oldWords.size, newNorm.size)
        var cutRaw = 0
        for (n in maxN downTo 2) {
            if (oldWords.subList(oldWords.size - n, oldWords.size) == newNorm.subList(0, n)) {
                // Переводим «n нормализованных слов» в число исходных токенов.
                var seen = 0
                var i = 0
                while (i < raw.size && seen < n) {
                    if (normalizeWord(raw[i]).isNotEmpty()) seen++
                    i++
                }
                cutRaw = i
                break
            }
        }
        if (cutRaw == 0) return text
        return raw.drop(cutRaw).joinToString(" ")
    }

    private fun words(s: String): List<String> =
        s.split(Regex("\\s+")).map { normalizeWord(it) }.filter { it.isNotEmpty() }

    private fun normalizeWord(w: String): String =
        w.lowercase().filter { it.isLetterOrDigit() }

    fun close() {
        recognizer?.release()
        recognizer = null
    }
}