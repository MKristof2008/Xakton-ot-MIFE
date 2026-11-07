package ru.mifi.stenograf.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import ru.mifi.stenograf.model.ModelStore
import ru.mifi.stenograf.output.AsrSegment
import java.io.File

/**
 * Движок распознавания с выбором модели.
 *
 * Почему два движка:
 *  - Whisper multilingual (`small`) — универсальный, хорошо ставит пробелы и
 *    пунктуацию, но русская лексика у него вторична: разговорные слова и мат
 *    он восстанавливает «как услышал в английском» и уходит в петли.
 *  - Zipformer-ru (Vosk model-ru) обучен ТОЛЬКО на русском: знает разговорную
 *    лексику, мат и сленг, не пытается писать по-английски, быстрее в 3 раза.
 *    Минус — не ставит знаки и иногда нормализует текст (цифры словами).
 *
 * Используем Zipformer-ru для звука, Whisper small — как запасной и эталон
 * для сравнения качества на своих записях.
 */
class AsrEngine(
    context: Context,
    private val engine: EngineKind = EngineKind.RU_TRANSDUCER,
    private val threads: Int = defaultThreads(),
) {

    enum class EngineKind(val label: String) {
        RU_TRANSDUCER("zipformer-ru"),
        WHISPER_SMALL("whisper-small"),
    }

    private val appContext = context.applicationContext
    private var recognizer: OfflineRecognizer? = null

    companion object {
        /** BPE-маркер начала слова в словарях sherpa-onnx (U+2581). */
        private const val MARK = '▁'

        /**
         * Zipformer — неавторегрессионный трансдьюсер: ему не нужны окна и
         * перекрытия, он обрабатывает запись целиком. 20 с — запас на память.
         */
        const val TRANSDUCER_WINDOW_SEC = 20f
        const val TRANSDUCER_OVERLAP_SEC = 0f

        /** Whisper обучен на окнах 30 с. */
        const val WHISPER_WINDOW_SEC = 30f
        const val WHISPER_OVERLAP_SEC = 0f

        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

        /** Движок, для которого на устройстве есть веса. */
        fun availableEngines(context: Context): List<EngineKind> {
            val dir = ModelStore.asrDir(context)
            val out = mutableListOf<EngineKind>()
            if (ModelStore.RU_MODEL.files(dir).all { it.exists() }) out += EngineKind.RU_TRANSDUCER
            if (ModelStore.whisperFiles(dir, ModelStore.PREFERRED_ASR).all { it.exists() }) {
                out += EngineKind.WHISPER_SMALL
            }
            return out
        }
    }

    private fun ensureLoaded(): OfflineRecognizer =
        recognizer ?: run {
            val dir = ModelStore.asrDir(appContext)
            val cfg = when (engine) {
                EngineKind.RU_TRANSDUCER -> {
                    val m = ModelStore.RU_MODEL
                    m.files(dir).forEach { require(it.exists()) { "Нет файла модели: ${it.name}" } }
                    OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            transducer = OfflineTransducerModelConfig(
                                encoder = File(dir, m.encoder).absolutePath,
                                decoder = File(dir, m.decoder).absolutePath,
                                joiner = File(dir, m.joiner).absolutePath,
                            ),
                            tokens = File(dir, m.tokens).absolutePath,
                            modelType = "transducer",
                            numThreads = threads,
                            provider = "cpu",
                        ),
                        decodingMethod = "greedy_search",
                    )
                }

                EngineKind.WHISPER_SMALL -> {
                    val variant = ModelStore.PREFERRED_ASR
                    OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(
                                encoder = File(dir, ModelStore.encoderName(variant)).apply {
                                    require(exists()) { "Нет файла модели: $name" }
                                }.absolutePath,
                                decoder = File(dir, ModelStore.decoderName(variant)).apply {
                                    require(exists()) { "Нет файла модели: $name" }
                                }.absolutePath,
                                language = "ru",
                                task = "transcribe",
                                enableTokenTimestamps = true,
                                enableSegmentTimestamps = true,
                            ),
                            tokens = File(dir, ModelStore.tokensName(variant)).absolutePath,
                            modelType = "whisper",
                            numThreads = threads,
                            provider = "cpu",
                        ),
                        decodingMethod = "greedy_search",
                    )
                }
            }
            OfflineRecognizer(config = cfg).also { recognizer = it }
        }

    /**
     * @param samples PCM float32, 16 кГц, моно.
     * @return сегменты с временем от начала записи.
     */
    fun transcribe(samples: FloatArray, onProgress: (Float) -> Unit = {}): List<AsrSegment> {
        if (samples.isEmpty()) return emptyList()
        val rec = ensureLoaded()
        val (windowSec, overlapSec) = when (engine) {
            EngineKind.RU_TRANSDUCER -> TRANSDUCER_WINDOW_SEC to TRANSDUCER_OVERLAP_SEC
            EngineKind.WHISPER_SMALL -> WHISPER_WINDOW_SEC to WHISPER_OVERLAP_SEC
        }
        val windowSize = (windowSec * 16_000).toInt()
        val step = (windowSize - (overlapSec * 16_000).toInt()).coerceAtLeast(16_000)

        val out = mutableListOf<AsrSegment>()
        var offset = 0
        var lastEnd = -1

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
                val pieces: List<Triple<Float, Float, String>> =
                    if (ts.size > 1 && result.tokens.size == ts.size) {
                        splitIntoSegments(result.tokens, ts, text)
                    } else {
                        listOf(Triple(0f, (chunk.size * 1000 / 16_000) / 1000f, text))
                    }
                for (seg in pieces) {
                    val sMs = chunkStartMs + (seg.first * 1000).toInt()
                    val eMs = chunkStartMs + (seg.second * 1000).toInt()
                    if (eMs <= lastEnd + 100) continue
                    val fresh = trimRepeatedPrefix(seg.third, out)
                    if (fresh.isNotBlank()) {
                        out += AsrSegment(maxOf(sMs, 0), eMs, fresh)
                        lastEnd = maxOf(lastEnd, eMs)
                    }
                }
            }

            onProgress((end.toFloat() / samples.size).coerceIn(0f, 1f))
            if (end >= samples.size) break
            offset += step
        }
        return out.filter { it.text.isNotBlank() && it.endMs > it.startMs }
    }

    /** Группирует токены в фразы по паузам > 1 с. */
    private fun splitIntoSegments(
        tokens: Array<String>,
        ts: FloatArray,
        fullText: String,
    ): List<Triple<Float, Float, String>> {
        val out = mutableListOf<Triple<Float, Float, String>>()
        var buf = StringBuilder()
        var segStart = ts.firstOrNull() ?: 0f
        var prevEnd = segStart
        for (i in tokens.indices) {
            val t = ts[i]
            if (buf.isNotEmpty() && (t - prevEnd) > 1.0f) {
                out += Triple(segStart, prevEnd, tidyText(buf.toString()))
                buf = StringBuilder()
                segStart = t
            }
            // BPE-токены: граница слова закодирована символом `▁` В НАЧАЛЕ токена.
            // Он же означает «здесь начинается новое слово». Один `▁` — это
            // пробел перед словом; сам символ в текст не попадает.
            // Раньше он заменялся на пробел ВНУТРИ токена, из-за чего получалось
            // «До б ры й д ень» вместо «Добрый день».
            val raw = tokens[i]
            val startsWord = raw.startsWith(MARK)
            val piece = raw.replace(MARK.toString(), "")
            if (piece.isEmpty()) {
                prevEnd = t
                continue
            }
            if (buf.isNotEmpty() && startsWord && !buf.last().isWhitespace()) {
                buf.append(' ')
            }
            buf.append(piece)
            prevEnd = t
        }
        if (buf.isNotEmpty()) out += Triple(segStart, prevEnd, tidyText(buf.toString()))
        return out.filter { it.third.isNotBlank() }.ifEmpty {
            listOf(Triple(ts.firstOrNull() ?: 0f, ts.lastOrNull() ?: 0f, fullText))
        }
    }

    private fun Char.isPunctuation(): Boolean = this in ".,!?;:…—–)"

    private fun tidyText(raw: String): String {
        var s = raw.replace(Regex("[ \\t]+"), " ").trim()
        if (s.isEmpty()) return s
        s = s.replace(Regex("\\s+([.,!?;:…])"), "$1")
        s = s.replace(Regex("\\(\\s+"), "(").replace(Regex("\\s+\\)"), ")")
        return s
    }

    /** Отрезает повтор слов из перекрытия окон (для Whisper). */
    private fun trimRepeatedPrefix(text: String, already: List<AsrSegment>): String {
        if (already.isEmpty()) return text
        val oldWords = words(already.takeLast(2).joinToString(" ") { it.text })
        if (oldWords.isEmpty()) return text
        val raw = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val newNorm = raw.map { normalizeWord(it) }.filter { it.isNotEmpty() }
        if (newNorm.isEmpty()) return text
        val maxN = minOf(oldWords.size, newNorm.size)
        var cutRaw = 0
        for (n in maxN downTo 2) {
            if (oldWords.subList(oldWords.size - n, oldWords.size) == newNorm.subList(0, n)) {
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
        return if (cutRaw == 0) text else raw.drop(cutRaw).joinToString(" ")
    }

    private fun words(s: String): List<String> =
        s.split(Regex("\\s+")).map { normalizeWord(it) }.filter { it.isNotEmpty() }

    private fun normalizeWord(w: String): String = w.lowercase().filter { it.isLetterOrDigit() }

    fun close() {
        recognizer?.release()
        recognizer = null
    }
}