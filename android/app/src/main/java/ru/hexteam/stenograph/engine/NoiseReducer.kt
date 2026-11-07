package ru.hexteam.stenograph.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Лёгкое офлайн-шумоподавление для речи.
 *
 * Метод: спектральное вычитание с оценкой шума по первым тихим кадрам,
 * защитным floor и overlap-add. Никаких моделей и сетевых вызовов: работает
 * до ASR прямо на устройстве.
 *
 * Защита от артефактов:
 * - шум оценивается только по кадрам с низкой энергией;
 * - амплитуда не обнуляется, минимум 15% спектра сохраняется;
 * - если запись уже чистая, коэффициент ослабления близок к 1 и сигнал
 *   практически не меняется.
 */
object NoiseReducer {
    private const val FRAME = 512
    private const val HOP = FRAME / 2
    private const val NOISE_FRAMES = 20
    private const val FLOOR = 0.15f
    private const val REDUCTION = 1.25f

    fun reduce(samples: ShortArray, sampleRate: Int): ShortArray {
        if (samples.size < FRAME * 3 || sampleRate <= 0) return samples

        val frameCount = (samples.size - FRAME) / HOP + 1
        val energies = DoubleArray(frameCount)
        val window = FloatArray(FRAME) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (FRAME - 1))).toFloat()
        }
        val real = FloatArray(FRAME)
        val imag = FloatArray(FRAME)
        val magnitudes = Array(frameCount) { FloatArray(FRAME / 2 + 1) }

        for (frame in 0 until frameCount) {
            val offset = frame * HOP
            var energy = 0.0
            for (i in 0 until FRAME) {
                val value = samples[offset + i].toFloat() / 32768f
                energy += value * value
                real[i] = value * window[i]
                imag[i] = 0f
            }
            energies[frame] = energy / FRAME
            fft(real, imag, inverse = false)
            for (bin in magnitudes[frame].indices) {
                magnitudes[frame][bin] = hypot(real[bin], imag[bin])
            }
        }

        val quiet = energies.indices.sortedBy { energies[it] }
            .take(NOISE_FRAMES.coerceAtMost(frameCount))
        val quietMedian = quiet.map { energies[it] }.average()
        val speechMedian = energies.sorted().drop(frameCount / 4)
            .take((frameCount / 2).coerceAtLeast(1)).average()

        // Если тихие кадры и «речевые» почти не отличаются, устойчивого
        // фонового шума нет: возвращаем исходный сигнал побитово. Это важно
        // для чистых записей — шумодав не должен ухудшать WER.
        if (quietMedian <= 1e-10 || speechMedian / quietMedian < 3.0) return samples

        val noise = FloatArray(FRAME / 2 + 1)
        for (frame in quiet) {
            for (bin in noise.indices) noise[bin] += magnitudes[frame][bin]
        }
        val q = quiet.size.coerceAtLeast(1).toFloat()
        for (bin in noise.indices) noise[bin] = noise[bin] / q

        val out = FloatArray(samples.size)
        val weights = FloatArray(samples.size)
        for (frame in 0 until frameCount) {
            val offset = frame * HOP
            for (bin in 0..FRAME / 2) {
                val current = magnitudes[frame][bin]
                val clean = max(FLOOR * current, current - REDUCTION * noise[bin])
                val gain = if (current > 1e-6f) clean / current else 1f
                real[bin] *= gain
                imag[bin] *= gain
                if (bin > 0 && bin < FRAME / 2) {
                    real[FRAME - bin] *= gain
                    imag[FRAME - bin] *= gain
                }
            }
            fft(real, imag, inverse = true)
            for (i in 0 until FRAME) {
                val pos = offset + i
                if (pos < out.size) {
                    out[pos] += real[i] * window[i]
                    weights[pos] += window[i] * window[i]
                }
            }
        }

        return ShortArray(samples.size) { i ->
            val value = if (weights[i] > 1e-4f) out[i] / weights[i] else samples[i] / 32768f
            (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    private fun fft(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imag[i]; imag[i] = imag[j]; imag[j] = im
            }
        }
        var length = 2
        while (length <= n) {
            val angle = 2.0 * PI / length * if (inverse) 1 else -1
            val wr0 = cos(angle).toFloat()
            val wi0 = sin(angle).toFloat()
            for (start in 0 until n step length) {
                var wr = 1f
                var wi = 0f
                for (i in 0 until length / 2) {
                    val even = start + i
                    val odd = even + length / 2
                    val tr = wr * real[odd] - wi * imag[odd]
                    val ti = wr * imag[odd] + wi * real[odd]
                    real[odd] = real[even] - tr
                    imag[odd] = imag[even] - ti
                    real[even] += tr
                    imag[even] += ti
                    val next = wr * wr0 - wi * wi0
                    wi = wr * wi0 + wi * wr0
                    wr = next
                }
            }
            length = length shl 1
        }
        if (inverse) {
            val nf = n.toFloat()
            for (i in real.indices) { real[i] = real[i] / nf; imag[i] = imag[i] / nf }
        }
    }
}
