package ru.hexteam.stenograph.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Конвертация любого аудио в WAV 16 кГц / моно / 16 бит — формат whisper.cpp.
 *
 * В Python-версии это делает ffmpeg. На Android ffmpeg обычно не тянут,
 * поэтому используем MediaCodec: он есть в системе и умеет mp3, m4a, aac, opus.
 * Ограничение: если файл уже WAV с другой частотой, он тоже пересемплируется
 * через MediaCodec, что не всегда поддерживается — тогда возвращается null.
 */
object AudioConverter {

    private const val TARGET_RATE = 16_000

    fun toWav16kMono(context: Context, uri: Uri): File? {
        return try {
            val extractor = MediaExtractor()
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return null
            decodeToWav(context, extractor)
        } catch (e: Exception) {
            null
        }
    }

    fun toWav16kMono(context: Context, file: File): File? = try {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        decodeToWav(context, extractor)
    } catch (e: Exception) {
        null
    }

    private fun decodeToWav(context: Context, extractor: MediaExtractor): File? { return try {
        var audioTrack = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrack = i
                format = f
                break
            }
        }
        if (audioTrack < 0 || format == null) return null
        extractor.selectTrack(audioTrack)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        // Примитивный растущий буфер вместо ArrayList<Short>: на 95-секундной
        // записи ArrayList держит ~1.5 млн объектов-обёрток (десятки МБ)
        // поверх самих данных, и процесс убивало по памяти.
        var pcm = ShortArray(1 shl 20) // ~2 с при 16 кГц
        var pcmLen = 0
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                val buffer = codec.getOutputBuffer(outIndex)!!
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val count = buffer.remaining() / 2
                if (pcmLen + count > pcm.size) {
                    var grown = pcm.size * 2
                    while (grown < pcmLen + count) grown *= 2
                    pcm = pcm.copyOf(grown)
                }
                buffer.asShortBuffer().get(pcm, pcmLen, count)
                pcmLen += count
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // новый формат игнорируем: работаем с тем, что объявлен в треке
            }
        }

        codec.stop()
        codec.release()

        val mono = downmix(pcm.copyOf(pcmLen), channels)
        val resampled = resample(mono, sourceRate, TARGET_RATE)
        // Предобработка остаётся локальной и не меняет длину/таймкоды записи.
        // Если запись чистая, защитный floor оставляет спектр почти без изменений.
        val denoised = NoiseReducer.reduce(resampled, TARGET_RATE)
        writeWav(denoised, File(context.cacheDir, "input16k.wav"))
        File(context.cacheDir, "input16k.wav")
    } catch (e: Exception) {
        null
    } finally {
        extractor.release()
    } }

    /** Среднее по каналам: сохраняет речь, убирает стерео. */
    private fun downmix(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        val frames = pcm.size / channels
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += pcm[i * channels + c]
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    /** Линейная интерполяция: для речи приемлемо, зависимостей не требует. */
    private fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || input.isEmpty()) return input
        val outLen = (input.size.toLong() * to / from).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val pos = i.toLong() * from / to
            val idx = pos.toInt().coerceIn(0, input.size - 1)
            out[i] = input[idx]
        }
        return out
    }

    private fun writeWav(samples: ShortArray, file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val dataSize = samples.size * 2
            raf.setLength(0)
            raf.writeBytes("RIFF")
            raf.writeIntLe(36 + dataSize)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeIntLe(16)
            raf.writeShortLe(1)                    // PCM
            raf.writeShortLe(1)                    // моно
            raf.writeIntLe(TARGET_RATE)
            raf.writeIntLe(TARGET_RATE * 2)        // байт в секунду
            raf.writeShortLe(2)                    // блок
            raf.writeShortLe(16)                   // бит на сэмпл
            raf.writeBytes("data")
            raf.writeIntLe(dataSize)
            val buffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { buffer.putShort(it) }
            raf.write(buffer.array())
        }
    }

    private fun RandomAccessFile.writeIntLe(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun RandomAccessFile.writeShortLe(value: Int) {
        write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
    }

    /** Длительность WAV в миллисекундах — для отбраковки сегментов за концом. */
    fun durationMs(file: File): Int {
        if (!file.exists() || file.length() < 44) return 0
        val dataBytes = file.length() - 44
        return ((dataBytes / 2) * 1000 / TARGET_RATE).toInt()
    }
}