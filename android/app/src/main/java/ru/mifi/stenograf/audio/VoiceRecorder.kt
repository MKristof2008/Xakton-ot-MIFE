package ru.mifi.stenograf.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Запись голоса прямо в приложении — как голосовое сообщение в мессенджере:
 * нажал, говоришь, нажал — готово.
 *
 * Пишем не в m4a через MediaRecorder, а сразу в WAV 16 кГц моно. Причина:
 * дальше всё равно нужен PCM 16 кГц, а декодирование сжатого файла — лишний
 * этап и лишние потери. Для записи в 10 минут WAV занимает ~19 МБ, это приемлемо.
 */
class VoiceRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private var record: AudioRecord? = null
    @Volatile private var running = false

    /** Длительность последней записи в мс (по числу записанных сэмплов). */
    @Volatile var durationMs: Long = 0L
        private set

    /**
     * Пишет звук в [target] (WAV). Вызывать из корутины ввода-вывода.
     * Останавливается вызовом [stop] из другого потока.
     */
    suspend fun recordTo(target: File, onTick: (elapsedMs: Long) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            val bufSize = maxOf(minBuf, SAMPLE_RATE / 2 * 2) // ~0.5 c запасом
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufSize * 2,
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Микрофон занят другим приложением"
            }

            record = recorder
            running = true
            durationMs = 0L

            target.parentFile?.mkdirs()
            val raf = RandomAccessFile(target, "rw")
            try {
                raf.setLength(0)
                // Заголовок пишем нулевой длины и правим в конце — иначе не знаем размер.
                raf.write(ByteArray(44))
                recorder.startRecording()

                val buf = ShortArray(bufSize)
                var totalSamples = 0L
                var lastTick = 0L
                while (running && isActive) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read <= 0) continue
                    val bytes = ByteArray(read * 2)
                    for (i in 0 until read) {
                        val v = buf[i].toInt()
                        bytes[2 * i] = (v and 0xFF).toByte()
                        bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    raf.write(bytes)
                    totalSamples += read
                    durationMs = totalSamples * 1000 / SAMPLE_RATE
                    if (durationMs - lastTick >= 200) {
                        lastTick = durationMs
                        onTick(durationMs)
                    }
                }
                recorder.stop()
                writeWavHeader(raf, totalSamples)
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                record = null
                running = false
                raf.close()
            }
        }

    /** Останавливает запись; текущий файл дописывается корректно. */
    fun stop() {
        running = false
    }

    /** Уровень сигнала для индикатора (0..1): считается по последнему буферу. */
    fun amplitude(): Float = lastAmplitude

    @Volatile private var lastAmplitude = 0f

    /** Обновляет уровень — вызывается из цикла записи через колбэк onTick. */
    fun updateAmplitude(samples: ShortArray) {
        var peak = 0
        for (s in samples) {
            val a = kotlin.math.abs(s.toInt())
            if (a > peak) peak = a
        }
        lastAmplitude = (peak / 32768f).coerceIn(0f, 1f)
    }

    /** Дописывает корректный RIFF-заголовок в начало файла. */
    private fun writeWavHeader(raf: RandomAccessFile, samples: Long) {
        val dataLen = (samples * 2).toInt()
        val byteRate = SAMPLE_RATE * 2
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataLen)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)                       // PCM
        header.putShort(1)                       // моно
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(2)                       // block align
        header.putShort(16)                      // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataLen)
        raf.seek(0)
        raf.write(header.array())
    }
}