package ru.hexteam.stenograph.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import ru.hexteam.stenograph.core.SpeakerSegment
import ru.hexteam.stenograph.engine.ModelStore
import ru.hexteam.stenograph.engine.SherpaDiarizationEngine
import java.io.File

/**
 * Подбор порога кластеризации на реальной записи.
 *
 * Порог — единственный параметр, который решает, сколько говорящих найдёт
 * движок. Подбирать его «на глаз» бессмысленно: нужен DER на эталоне.
 * Тест прогоняет набор порогов и печатает DER в logcat, чтобы выбрать
 * значение по цифрам (docs/benchmarks/measurements.csv).
 *
 * Эталон читается из assets тестового APK и используется ТОЛЬКО здесь:
 * в рантайм приложения он не попадает (запрет ТЗ №4).
 */
@RunWith(AndroidJUnit4::class)
class ThresholdSweepTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private val thresholds = listOf(0.30f, 0.40f, 0.45f, 0.50f, 0.55f, 0.60f, 0.70f)

    private fun audio(): File {
        val target = File(context.cacheDir, "bench_input.wav")
        if (!target.isFile || target.length() == 0L) {
            testContext.assets.open("dialog_2spk.wav").use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return target
    }

    /** Эталон: строки «speaker_N start_ms end_ms». */
    private fun reference(): List<SpeakerSegment> =
        testContext.assets.open("dialog_2spk.ref.txt").bufferedReader().readLines()
            .mapNotNull { line ->
                val p = line.trim().split(Regex("\\s+"))
                if (p.size < 3) return@mapNotNull null
                SpeakerSegment(p[1].toLong(), p[2].toLong(), p[0])
            }

    /**
     * Прогон одного порога. Порог передаётся через аргумент инструментации:
     * `-Pandroid.testInstrumentationRunnerArguments.threshold=0.6`.
     * Так один замер — один запуск, без пересчёта модели в памяти.
     */
    @Test
    fun measureThreshold() = runBlocking {
        val file = audio()
        val ref = reference()
        // Модели копируются из assets при первом запуске (та же схема, что в приложении).
        ModelStore.installDiarization(context)

        val args = InstrumentationRegistry.getArguments()
        val t = args.getString("threshold")?.toFloatOrNull()
            ?: SherpaDiarizationEngine.DEFAULT_THRESHOLD
        // Если задано число говорящих — кластеризация идёт по нему, а не по порогу.
        val nSpeakers = args.getString("speakers")?.toIntOrNull()

        val engine = SherpaDiarizationEngine(context, threshold = t)
        val started = System.currentTimeMillis()
        val hyp = engine.diarize(file.absolutePath, expectedSpeakers = nSpeakers)
        val elapsed = System.currentTimeMillis() - started

        val speakers = hyp.map { it.speaker }.distinct().size
        val der = Der.compute(ref, hyp, collarMs = 250L)
        Log.i(
            "SWEEP",
            "RESULT threshold=$t speakers=$nSpeakers found=$speakers segments=${hyp.size} " +
                "der=${"%.4f".format(der)} elapsed_ms=$elapsed",
        )
        Log.i("SWEEP", "  эталон: ${ref.size} интервалов, ${ref.map { it.speaker }.distinct().size} говорящих")
        Log.i("SWEEP", "  разбор: ${Der.explain(ref, hyp, collarMs = 250L)}")
        hyp.forEach { Log.i("SWEEP", "  H ${it.startMs}-${it.endMs} ${it.speaker}") }
    }

    /** Массовый прогон: нужен один раз при подборе, дальше — [measureThreshold]. */
    @Test
    fun sweepThresholds() = runBlocking {
        val file = audio()
        val ref = reference()
        ModelStore.installDiarization(context)
        for (t in thresholds) {
            val hyp = SherpaDiarizationEngine(context, threshold = t)
                .diarize(file.absolutePath, expectedSpeakers = null)
            Log.i(
                "SWEEP",
                "RESULT threshold=$t speakers=${hyp.map { it.speaker }.distinct().size} " +
                    "der=${"%.4f".format(Der.compute(ref, hyp))}",
            )
        }
    }
}

/**
 * DER (Diarization Error Rate) — как в NIST md-eval, упрощённо:
 * (пропуск + ложная речь + ошибка говорящего) / длительность эталонной речи.
 *
 * Допуск (collar) вокруг границ обязателен: расхождение в 100–200 мс на
 * границе реплики — не ошибка диаризации, а разница в разметке. Без него
 * метрика занижает качество и не соответствует тому, как считают эксперты.
 */
object Der {

    /** Разложение ошибки по категориям — в миллисекундах. */
    data class Breakdown(
        val refSpeechMs: Long,
        val missedMs: Long,
        val falseAlarmMs: Long,
        val confusionMs: Long,
    ) {
        val der: Double
            get() = if (refSpeechMs <= 0) 0.0
            else (missedMs + falseAlarmMs + confusionMs).toDouble() / refSpeechMs
    }

    /**
     * DER по кадрам с оптимальным сопоставлением меток говорящих.
     *
     * Сопоставление обязательно: номера говорящих в эталоне и в гипотезе
     * назначаются независимо, поэтому `speaker_1` гипотезы может быть
     * `speaker_2` эталона. Без выравнивания даже идеальная диаризация даёт
     * DER около 1.0 — именно эту ошибку метрика и давала раньше.
     *
     * Выравнивание — жадное по максимальному перекрытию: при малом числе
     * говорящих (2–6 на записях ТЗ) даёт тот же результат, что и оптимум.
     *
     * @param collarMs допуск у границ эталонных реплик: разница разметки
     *        в 100–250 мс — не ошибка диаризации.
     */
    fun compute(
        ref: List<SpeakerSegment>,
        hyp: List<SpeakerSegment>,
        collarMs: Long = 250L,
        stepMs: Long = 10L,
    ): Double = breakdown(ref, hyp, collarMs, stepMs).der

    fun breakdown(
        ref: List<SpeakerSegment>,
        hyp: List<SpeakerSegment>,
        collarMs: Long = 250L,
        stepMs: Long = 10L,
    ): Breakdown {
        if (ref.isEmpty()) return Breakdown(0, 0, 0, 0)
        val end = maxOf(ref.maxOf { it.endMs }, hyp.maxOfOrNull { it.endMs } ?: 0L)
        if (end <= 0) return Breakdown(0, 0, 0, 0)

        // 1. Матрица совместной встречаемости «эталон × гипотеза».
        val pairs = linkedMapOf<Pair<String, String>, Long>()
        val refSpeakers = linkedSetOf<String>()
        val hypSpeakers = linkedSetOf<String>()
        var refSpeech = 0L
        var missed = 0L
        var falseAlarm = 0L
        var t = 0L
        while (t < end) {
            val r = speakerAt(ref, t)
            val h = speakerAt(hyp, t)
            if (r != null) refSpeakers += r
            if (h != null) hypSpeakers += h

            if (r != null) {
                refSpeech += stepMs
                if (h == null) missed += stepMs
                else pairs[r to h] = (pairs[r to h] ?: 0L) + stepMs
            } else if (h != null) {
                falseAlarm += stepMs
            }
            t += stepMs
        }

        // 2. Жадное выравнивание меток по перекрытию.
        val mapping = mutableMapOf<String, String>()
        val usedHyp = mutableSetOf<String>()
        for ((pair, ms) in pairs.entries.sortedByDescending { it.value }) {
            val (r, h) = pair
            if (r in mapping || h in usedHyp) continue
            mapping[r] = h
            usedHyp += h
        }

        // 3. Ошибка говорящего — кадры, где размеченный говорящий не совпал
        //    с выровненным. Кадры у границ эталона не штрафуем.
        var confusion = 0L
        t = 0L
        while (t < end) {
            val r = speakerAt(ref, t)
            val h = speakerAt(hyp, t)
            val nearBoundary = ref.any {
                kotlin.math.abs(it.startMs - t) <= collarMs ||
                    kotlin.math.abs(it.endMs - t) <= collarMs
            }
            if (r != null && h != null && !nearBoundary && mapping[r] != h) confusion += stepMs
            t += stepMs
        }
        return Breakdown(refSpeech, missed, falseAlarm, confusion)
    }

    /** Разложение ошибки по типам: видно, что именно ломается. */
    fun explain(
        ref: List<SpeakerSegment>,
        hyp: List<SpeakerSegment>,
        collarMs: Long = 250L,
    ): String {
        val b = breakdown(ref, hyp, collarMs)
        fun pct(v: Long) =
            if (b.refSpeechMs > 0) "%.1f%%".format(100.0 * v / b.refSpeechMs) else "—"
        return "речь=${b.refSpeechMs}мс пропуск=${pct(b.missedMs)} " +
            "ложная=${pct(b.falseAlarmMs)} путаница=${pct(b.confusionMs)}"
    }

    private fun speakerAt(segments: List<SpeakerSegment>, t: Long): String? =
        segments.firstOrNull { t >= it.startMs && t < it.endMs }?.speaker
}
