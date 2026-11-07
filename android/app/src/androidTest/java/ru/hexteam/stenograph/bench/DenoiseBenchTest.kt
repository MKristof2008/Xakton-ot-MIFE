package ru.hexteam.stenograph.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import ru.hexteam.stenograph.engine.NoiseReducer
import ru.hexteam.stenograph.engine.WhisperAsrEngine
import ru.hexteam.stenograph.engine.WavReader
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Замер шумоподавления: WER на чистой записи и на той же записи с шумом,
 * до и после [NoiseReducer].
 *
 * Смысл: шумодав обязан помогать на шуме и НЕ портить чистую речь.
 * Если на чистой записи WER растёт — фильтр слишком агрессивный, и его
 * нельзя включать по умолчанию.
 */
@RunWith(AndroidJUnit4::class)
class DenoiseBenchTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun cases(): List<Pair<String, String>> {
        val arr = org.json.JSONArray(
            testContext.assets.open("wer/manifest.json").bufferedReader().readText(),
        )
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            o.getString("file") to o.getString("text")
        }
    }

    private fun materialize(name: String): File {
        val target = File(context.cacheDir, "denoise_$name")
        if (!target.isFile || target.length() == 0L) {
            testContext.assets.open("wer/$name").use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return target
    }

    /** Белый шум заданного уровня (SNR относительно RMS речи). */
    private fun addNoise(samples: ShortArray, snrDb: Double, seed: Long = 42L): ShortArray {
        var energy = 0.0
        for (s in samples) energy += s.toDouble() * s
        val rms = kotlin.math.sqrt(energy / samples.size)
        val noiseRms = rms / kotlin.math.exp(snrDb / 20.0 * kotlin.math.ln(10.0))
        val rnd = java.util.Random(seed)
        return ShortArray(samples.size) { i ->
            val n = rnd.nextGaussian() * noiseRms
            (samples[i] + n).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    private fun writeWav(samples: ShortArray, rate: Int, file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val dataSize = samples.size * 2
            raf.setLength(0)
            raf.writeBytes("RIFF")
            raf.write(le32(36 + dataSize))
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.write(le32(16))
            raf.write(le32(1).copyOf(2))
            raf.write(le32(1).copyOf(2))
            raf.write(le32(rate))
            raf.write(le32(rate * 2))
            raf.write(le32(2).copyOf(2))
            raf.write(le32(16).copyOf(2))
            raf.writeBytes("data")
            raf.write(le32(dataSize))
            val buf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { buf.putShort(it) }
            raf.write(buf.array())
        }
    }

    private fun le32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun toShorts(file: File): Pair<ShortArray, Int> {
        val (floats, rate) = WavReader.read(file)
        return ShortArray(floats.size) { (floats[it] * 32767f).toInt().toShort() } to rate
    }

    @Test
    fun denoiseEffect(): Unit = runBlocking {
        val engine = WhisperAsrEngine(context)
        check(engine.isReady()) { "нет модели ASR" }

        val all = cases()
        var cleanBefore = 0.0
        var cleanAfter = 0.0
        var noisyBefore = 0.0
        var noisyAfter = 0.0
        var n = 0

        for ((name, reference) in all.take(8)) {
            val file = materialize(name)
            val (samples, rate) = toShorts(file)

            // 1. Чистая запись
            val wClean = File(context.cacheDir, "clean.wav").also { writeWav(samples, rate, it) }
            val ref1 = engine.transcribe(wClean.absolutePath).joinToString(" ") { it.text }

            val den = NoiseReducer.reduce(samples, rate)
            val wCleanDen = File(context.cacheDir, "clean_den.wav").also { writeWav(den, rate, it) }
            val hyp1 = engine.transcribe(wCleanDen.absolutePath).joinToString(" ") { it.text }

            // 2. Та же запись с шумом 10 дБ
            val noisy = addNoise(samples, 10.0)
            val wNoisy = File(context.cacheDir, "noisy.wav").also { writeWav(noisy, rate, it) }
            val ref2 = engine.transcribe(wNoisy.absolutePath).joinToString(" ") { it.text }

            val noisyDen = NoiseReducer.reduce(noisy, rate)
            val wNoisyDen = File(context.cacheDir, "noisy_den.wav").also { writeWav(noisyDen, rate, it) }
            val hyp2 = engine.transcribe(wNoisyDen.absolutePath).joinToString(" ") { it.text }

            cleanBefore += WordErrorRate.compute(reference, ref1)
            cleanAfter += WordErrorRate.compute(reference, hyp1)
            noisyBefore += WordErrorRate.compute(reference, ref2)
            noisyAfter += WordErrorRate.compute(reference, hyp2)
            n++

            Log.i(
                "DENOISE",
                "$name clean ${"%.3f".format(WordErrorRate.compute(reference, ref1))} -> " +
                    "${"%.3f".format(WordErrorRate.compute(reference, hyp1))} | " +
                    "noise10 ${"%.3f".format(WordErrorRate.compute(reference, ref2))} -> " +
                    "${"%.3f".format(WordErrorRate.compute(reference, hyp2))}",
            )
        }

        Log.i(
            "DENOISE",
            "RESULT n=$n clean_wer ${"%.4f".format(cleanBefore / n)} -> ${"%.4f".format(cleanAfter / n)} | " +
                "noisy_wer ${"%.4f".format(noisyBefore / n)} -> ${"%.4f".format(noisyAfter / n)}",
        )
    }
}