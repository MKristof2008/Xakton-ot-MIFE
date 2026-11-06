package ru.hexteam.stenograph.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Чтение WAV (16 бит PCM) в FloatArray — формат входа нативных движков.
 *
 * Нужен, потому что [AudioConverter] отдаёт файл, а sherpa-onnx принимает
 * массив сэмплов в [-1, 1]. Читаем потоково, не поднимая в память байты и
 * сэмплы одновременно: на длинных записях разница в пике ОЗУ заметная.
 */
object WavReader {

    /** @return сэмплы в [-1, 1] и частоту дискретизации. */
    fun read(file: File): Pair<FloatArray, Int> {
        require(file.exists()) { "Нет файла: ${file.absolutePath}" }

        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") {
                "Не WAV: ${file.name}"
            }

            var channels = 1
            var bits = 16
            var rate = 16_000
            var dataOffset = -1L
            var dataSize = 0

            while (raf.filePointer + 8 <= raf.length()) {
                val id = ByteArray(4).also { raf.readFully(it) }
                val size = ByteBuffer.wrap(ByteArray(4).also { raf.readFully(it) })
                    .order(ByteOrder.LITTLE_ENDIAN).int
                val chunk = String(id)

                when (chunk) {
                    "fmt " -> {
                        val fmt = ByteArray(size)
                        raf.readFully(fmt)
                        val buf = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        buf.short // audioFormat
                        channels = buf.short.toInt().coerceAtLeast(1)
                        rate = buf.int
                        buf.int // byteRate
                        buf.short // blockAlign
                        bits = buf.short.toInt().coerceAtLeast(8)
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = size
                        break
                    }
                    else -> raf.seek(raf.filePointer + size + (size and 1))
                }
            }

            require(dataOffset >= 0) { "В WAV нет блока data: ${file.name}" }
            require(bits == 16) { "Ожидался 16-битный PCM, получено $bits бит" }

            val frames = minOf(dataSize, (raf.length() - dataOffset).toInt()) / 2 / channels
            val out = FloatArray(frames)
            raf.seek(dataOffset)

            val buf = ByteArray(8192)
            var frame = 0
            while (frame < frames) {
                val want = minOf(buf.size, (frames - frame) * 2 * channels)
                raf.readFully(buf, 0, want)
                var i = 0
                while (i + 1 < want && frame < frames) {
                    var sum = 0
                    for (c in 0 until channels) {
                        val lo = buf[i].toInt() and 0xFF
                        val hi = buf[i + 1].toInt()
                        sum += (hi shl 8) or lo
                        i += 2
                    }
                    out[frame++] = (sum.toFloat() / channels) / 32_768f
                }
            }
            return out to rate
        }
    }

    /** Длительность WAV в миллисекундах — по размеру блока data. */
    fun durationMs(file: File, sampleRate: Int = 16_000): Long {
        if (!file.exists() || file.length() <= 44) return 0L
        // 16 бит, моно: два байта на сэмпл.
        val samples = (file.length() - 44) / 2
        return samples * 1000 / sampleRate
    }
}
