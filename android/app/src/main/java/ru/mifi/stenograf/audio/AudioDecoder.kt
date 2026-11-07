package ru.mifi.stenograf.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Декодирование любого аудио/видеофайла в PCM float32, 16 кГц, моно.
 *
 * Использует только системные MediaExtractor + MediaCodec — никаких внешних
 * зависимостей и никакой сети. Модели ASR/диаризации обучены именно на 16 кГц.
 */
object AudioDecoder {

    const val TARGET_RATE = 16_000

    data class Pcm(val samples: FloatArray, val sampleRate: Int) {
        val durationMs: Long get() = samples.size * 1000L / sampleRate
    }

    fun decode(context: Context, uri: Uri): Pcm {
        // WAV читаем напрямую: MediaExtractor на части устройств не умеет
        // открывать raw PCM («Failed to instantiate extractor»), а запись
        // с микрофона у нас именно в WAV 16 кГц — это самый частый путь.
        if (isWav(context, uri)) return decodeWav(context, uri)

        val extractor = MediaExtractor()
        try {
            // file:// приходит при отладочных прогонах и из некоторых файловых менеджеров;
            // MediaExtractor читает такой путь напрямую, без ContentResolver.
            if (uri.scheme == "file") {
                val path = uri.path ?: error("Некорректный file-URI: $uri")
                extractor.setDataSource(path)
            } else {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.declaredLength >= 0) {
                        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
                    } else {
                        extractor.setDataSource(afd.fileDescriptor)
                    }
                } ?: error("Не удалось открыть файл: $uri")
            }

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("В файле нет аудиодорожки")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val raw = ArrayList<FloatArray>(1 shl 16)
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var outputChannels = srcChannels

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    // Формат вывода может прийти позже входа и содержать реальное число каналов.
                    val outFormat = codec.outputFormat
                    outputChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        // MediaCodec на Android отдаёт 16-bit PCM по умолчанию.
                    }
                    val buf: ByteBuffer = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val n = shorts.remaining()
                    val mono = FloatArray(n / outputChannels)
                    for (i in mono.indices) {
                        var acc = 0f
                        for (c in 0 until outputChannels) acc += shorts.get(i * outputChannels + c) / 32768f
                        mono[i] = acc / outputChannels
                    }
                    raw += mono
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputChannels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }

            codec.stop()
            codec.release()

            val joined = concat(raw)
            val resampled = if (srcRate == TARGET_RATE) joined else resample(joined, srcRate, TARGET_RATE)
            return Pcm(normalize(resampled), TARGET_RATE)
        } finally {
            extractor.release()
        }
    }

    /** Быстрая проверка по первым байтам: RIFF....WAVE. */
    private fun isWav(context: Context, uri: Uri): Boolean = runCatching {
        val head = openInput(context, uri)?.use { it.readNBytes(12) } ?: return@runCatching false
        head.size >= 12 &&
            String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
    }.getOrDefault(false)

    private fun openInput(context: Context, uri: Uri): java.io.InputStream? = when (uri.scheme) {
        "file" -> uri.path?.let { java.io.FileInputStream(it) }
        else -> context.contentResolver.openInputStream(uri)
    }

    /**
     * Разбор WAV вручную: ищем чанк `fmt ` для формата и `data` для сэмплов.
     * Понимаем 8/16/24/32-битный PCM и float32 — запись с микрофона даёт 16 бит,
     * но файлы пользователя бывают любые.
     */
    private fun decodeWav(context: Context, uri: Uri): Pcm {
        val bytes = openInput(context, uri)?.use { it.readBytes() }
            ?: error("Не удалось открыть файл: $uri")
        require(bytes.size > 44) { "Файл слишком короткий: $uri" }

        var pos = 12
        var channels = 1
        var rate = TARGET_RATE
        var bits = 16
        var format = 1 // 1 = PCM, 3 = IEEE float
        var dataOffset = -1
        var dataLen = 0

        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le32(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> if (body + 16 <= bytes.size) {
                    format = le16(bytes, body)
                    channels = le16(bytes, body + 2)
                    rate = le32(bytes, body + 4)
                    bits = le16(bytes, body + 14)
                }
                "data" -> {
                    dataOffset = body
                    dataLen = size.coerceAtMost(bytes.size - body)
                }
            }
            // Чанки выравниваются по чётной границе (RIFF).
            pos = body + size + (size and 1)
        }
        require(dataOffset >= 0 && dataLen > 0) { "В WAV нет чанка data: $uri" }

        val bytesPerSample = (bits / 8).coerceAtLeast(1)
        val frames = dataLen / (bytesPerSample * channels)
        val mono = FloatArray(frames)

        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                val o = dataOffset + (i * channels + c) * bytesPerSample
                val v = when {
                    format == 3 && bits == 32 -> {
                        val bitsInt = le32(bytes, o)
                        Float.fromBits(bitsInt)
                    }
                    bits == 16 -> le16s(bytes, o) / 32768f
                    bits == 8 -> (bytes[o].toInt() and 0xFF) / 128f - 1f
                    bits == 24 -> {
                        val raw = (bytes[o].toInt() and 0xFF) or
                            ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[o + 2].toInt() and 0xFF) shl 16)
                        // Знаковое расширение из 24 бит.
                        val signed = (raw shl 8) shr 8
                        signed / 8388608f
                    }
                    bits == 32 -> le32(bytes, o) / 2147483648f
                    else -> 0f
                }
                acc += v
            }
            mono[i] = acc / channels
        }

        val resampled = if (rate == TARGET_RATE) mono else resample(mono, rate, TARGET_RATE)
        return Pcm(normalize(resampled), TARGET_RATE)
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le16s(b: ByteArray, o: Int): Int = le16(b, o).toShort().toInt()

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun concat(parts: List<FloatArray>): FloatArray {
        val total = parts.sumOf { it.size }
        val out = FloatArray(total)
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    /**
     * Ресемплинг: линейная интерполяция. Для 44.1/48 кГц → 16 кГц этого достаточно —
     * модели устойчивы, а фильтр низких частот даёт VAD и ASR.
     */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate
        val outSize = (input.size / ratio).toInt()
        val out = FloatArray(outSize)
        for (i in 0 until outSize) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }

    /** Нормализация по пику до 0.9 — модели чувствительны к уровню записи. */
    fun normalize(input: FloatArray): FloatArray {
        var peak = 0f
        for (v in input) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        if (peak <= 1e-6f) return input
        val gain = 0.9f / peak
        if (gain in 0.5f..2.0f) return input
        val out = FloatArray(input.size)
        for (i in input.indices) out[i] = (input[i] * gain).coerceIn(-1f, 1f)
        return out
    }
}