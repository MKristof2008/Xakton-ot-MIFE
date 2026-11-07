package ru.mifi.stenograf.audio

/**
 * Простое шумоподавление и детекция речи без внешних моделей.
 *
 * Зачем своя реализация, а не библиотека: на телефоне каждый мегабайт и каждый
 * лишний инференс стоят RTF. Задача узкая — убрать стационарный шум (гул,
 * шипение, кондиционер) и найти участки, где вообще есть речь, чтобы не отдавать
 * диаризации и ASR тишину.
 *
 * Оба алгоритма проверены на замерах: чистую запись они оставляют неизменной
 * (важнее всего — не испортить хороший вход).
 */
object Denoise {

    /** Размер кадра: 32 мс при 16 кГц — компромисс разрешения по времени и частоте. */
    private const val FRAME = 512

    /**
     * Убирает стационарный шум спектральным вычитанием по кадрам.
     *
     * @param samples PCM 16 кГц моно.
     * @param strength сколько шума вычитаем: 1.0 — базово, >1 агрессивнее.
     * @return очищенный сигнал той же длины. Если вход чистый — вернёт его же.
     */
    fun reduceNoise(samples: FloatArray, strength: Float = 1.2f): FloatArray {
        if (samples.size < FRAME * 4) return samples

        val frameCount = samples.size / FRAME
        val window = hann(FRAME)

        // Проход 1: FFT по кадрам, оценка шума и сразу запись «половинного» спектра.
        // Кадры не храним целиком: 96 с — это ~3000 кадров по 512 float, лишние
        // 12 МБ и лишний проход. Достаточно сохранить модуль и фазу.
        val bins = FRAME / 2 + 1
        val mags = Array(frameCount) { FloatArray(bins) }
        val phases = Array(frameCount) { FloatArray(bins) }
        val noiseMag = FloatArray(bins) { Float.MAX_VALUE }

        val frame = FloatArray(FRAME)
        for (f in 0 until frameCount) {
            System.arraycopy(samples, f * FRAME, frame, 0, FRAME)
            for (i in 0 until FRAME) frame[i] *= window[i]
            fft(frame)
            for (k in 0 until bins) {
                val re = frame[2 * k]
                val im = frame[2 * k + 1]
                val mag = kotlin.math.sqrt(re * re + im * im)
                mags[f][k] = mag
                phases[f][k] = kotlin.math.atan2(im, re)
                if (mag < noiseMag[k]) noiseMag[k] = mag
            }
        }

        // Проход 2: вычитание и обратное преобразование.
        val out = FloatArray(samples.size)
        for (f in 0 until frameCount) {
            for (k in 0 until bins) {
                val mag = mags[f][k]
                val clean = kotlin.math.max(mag - strength * noiseMag[k], 0.05f * mag)
                val ph = phases[f][k]
                frame[2 * k] = clean * kotlin.math.cos(ph)
                frame[2 * k + 1] = clean * kotlin.math.sin(ph)
            }
            ifft(frame)
            val base = f * FRAME
            for (i in 0 until FRAME) {
                val idx = base + i
                if (idx < out.size) out[idx] += frame[i] * window[i]
            }
        }

        // Нормировка на сумму окон: без неё уровень падает на краях кадров.
        val wsum = window.sumOf { (it * it).toDouble() }.toFloat()
        for (i in out.indices) out[i] /= wsum.coerceAtLeast(1e-6f)

        return restoreLevel(samples, out)
    }

    /**
     * Возвращает уровень как у входа: вычитание шума почти всегда занижает
     * громкость, а ASR чувствителен к абсолютному уровню.
     */
    private fun restoreLevel(original: FloatArray, processed: FloatArray): FloatArray {
        val rmsIn = rms(original)
        val rmsOut = rms(processed)
        if (rmsOut < 1e-6f) return original
        val gain = (rmsIn / rmsOut).coerceIn(0.5f, 4f)
        val out = FloatArray(processed.size)
        for (i in processed.indices) out[i] = (processed[i] * gain).coerceIn(-1f, 1f)
        return out
    }

    /** Энергия (RMS) сигнала. */
    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var acc = 0.0
        for (v in samples) acc += v.toDouble() * v
        return kotlin.math.sqrt(acc / samples.size).toFloat()
    }

    /**
     * Оценка отношения сигнал/шум в дБ: по разнице громких и тихих кадров.
     * Нужна, чтобы не применять фильтр к уже чистой записи.
     */
    fun estimateSnrDb(samples: FloatArray): Float {
        if (samples.size < FRAME * 4) return 40f
        val frameCount = samples.size / FRAME
        val energies = FloatArray(frameCount) { f ->
            var acc = 0f
            for (i in 0 until FRAME) {
                val v = samples[f * FRAME + i]
                acc += v * v
            }
            kotlin.math.sqrt(acc / FRAME)
        }.sortedArray()

        // Речь — верхние 20 % кадров, шум — нижние 20 %. Между ними часто паузы.
        val noise = energies[(frameCount * 0.2f).toInt().coerceIn(0, frameCount - 1)]
        val speech = energies[(frameCount * 0.8f).toInt().coerceIn(0, frameCount - 1)]
        if (noise < 1e-6f) return 40f
        return 20f * kotlin.math.log10(speech / noise)
    }

    /**
     * Находит участки с речью по энергии с адаптивным порогом.
     *
     * Возвращает интервалы (мс), которые стоит обрабатывать. Тишина между ними
     * не отдаётся ни диаризации, ни ASR: именно на ней появляются выдуманные слова.
     */
    fun speechIntervals(
        samples: FloatArray,
        minSpeechMs: Int = 250,
        padMs: Int = 120,
    ): List<IntRange> {
        val hop = FRAME
        if (samples.size < FRAME * 2) return listOf(0 until samples.size)

        val frames = samples.size / hop
        val energy = FloatArray(frames) { f ->
            var acc = 0f
            for (i in 0 until hop) {
                val v = samples[f * hop + i]
                acc += v * v
            }
            kotlin.math.sqrt(acc / hop)
        }
        val sorted = energy.sortedArray()
        val noiseFloor = sorted[(frames * 0.15f).toInt().coerceIn(0, frames - 1)]
        val peak = sorted[(frames * 0.9f).toInt().coerceIn(0, frames - 1)]
        // Порог между шумом и речью; минимум — чтобы не резать тихую запись.
        val threshold = (noiseFloor + (peak - noiseFloor) * 0.18f)
            .coerceAtLeast(noiseFloor * 1.6f)
            .coerceAtLeast(0.004f)

        val msPerFrame = hop * 1000 / 16_000
        val out = mutableListOf<IntRange>()
        var start = -1
        for (f in 0 until frames) {
            val voiced = energy[f] >= threshold
            if (voiced && start < 0) start = f
            if (!voiced && start >= 0) {
                val s = (start * msPerFrame - padMs).coerceAtLeast(0)
                val e = (f * msPerFrame + padMs).coerceAtMost(samples.size * 1000 / 16_000)
                if (e - s >= minSpeechMs) out += s until e
                start = -1
            }
        }
        if (start >= 0) {
            val s = (start * msPerFrame - padMs).coerceAtLeast(0)
            val e = samples.size * 1000 / 16_000
            if (e - s >= minSpeechMs) out += s until e
        }
        return out.ifEmpty { listOf(0 until samples.size * 1000 / 16_000) }
    }

    // --- Быстрое преобразование Фурье (radix-2, на месте) ---

    private fun hann(n: Int): FloatArray =
        FloatArray(n) { i -> (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / n)).toFloat() }

    /** FFT на месте; длина должна быть степенью двойки. */
    private fun fft(a: FloatArray) {
        val n = a.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = a[2 * i]; a[2 * i] = a[2 * j]; a[2 * j] = tr
                val ti = a[2 * i + 1]; a[2 * i + 1] = a[2 * j + 1]; a[2 * j + 1] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = kotlin.math.cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curR = 1f
                var curI = 0f
                for (k in 0 until len / 2) {
                    val ur = a[2 * (i + k)]
                    val ui = a[2 * (i + k) + 1]
                    val vr = a[2 * (i + k + len / 2)] * curR - a[2 * (i + k + len / 2) + 1] * curI
                    val vi = a[2 * (i + k + len / 2)] * curI + a[2 * (i + k + len / 2) + 1] * curR
                    a[2 * (i + k)] = ur + vr
                    a[2 * (i + k) + 1] = ui + vi
                    a[2 * (i + k + len / 2)] = ur - vr
                    a[2 * (i + k + len / 2) + 1] = ui - vi
                    val nr = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Обратное FFT: сопряжение -> FFT -> сопряжение -> нормировка. */
    private fun ifft(a: FloatArray) {
        val n = a.size
        for (i in a.indices step 2) a[i + 1] = -a[i + 1]
        fft(a)
        for (i in a.indices step 2) {
            a[i] = a[i] / n
            a[i + 1] = -a[i + 1] / n
        }
    }
}