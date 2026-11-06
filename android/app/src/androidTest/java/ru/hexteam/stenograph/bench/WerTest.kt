package ru.hexteam.stenograph.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import ru.hexteam.stenograph.engine.AudioPrep
import ru.hexteam.stenograph.engine.WhisperAsrEngine
import java.io.File

/**
 * WER распознавания на реальном устройстве.
 *
 * Эталон — файл в assets тестового APK. Используется только здесь, для
 * замера; в рантайм приложения не попадает (запрет ТЗ №4 — никаких
 * эталонов в APK и никакой подстановки по имени файла).
 *
 * Запуск:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=ru.hexteam.stenograph.bench.WerTest
 */
@RunWith(AndroidJUnit4::class)
class WerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    /** Тестовый набор: 20 записей VoxForge RU с текстовыми эталонами. */
    private fun cases(): List<Pair<String, String>> {
        val manifest = testContext.assets.open("wer/manifest.json")
            .bufferedReader().readText()
        val arr = org.json.JSONArray(manifest)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            o.getString("file") to o.getString("text")
        }
    }

    private fun materialize(assetName: String): File {
        val target = File(context.cacheDir, assetName)
        if (!target.isFile || target.length() == 0L) {
            testContext.assets.open("wer/$assetName").use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return target
    }

    /**
     * Средний WER по набору — именно это число идёт в отчёт.
     * Считается на устройстве, той же моделью, что попадёт в APK.
     */
    @Test
    fun wer_onDevice(): Unit = runBlocking {
        // Модель выбирается аргументом, чтобы сравнить кандидатов одной сборкой:
        //   -Pandroid.testInstrumentationRunnerArguments.model=ggml-tiny.bin
        val modelName = InstrumentationRegistry.getArguments()
            .getString("model") ?: "ggml-base.bin"
        val engine = WhisperAsrEngine(context, modelAsset = "models/$modelName")
        check(engine.isReady()) { "Модель ASR не найдена: models/$modelName" }

        val all = cases()
        var sum = 0.0
        var totalAudioMs = 0L
        var totalElapsedMs = 0L
        var totalRefWords = 0
        var totalErrors = 0.0

        for ((name, reference) in all) {
            val file = materialize(name)
            val started = System.currentTimeMillis()
            val segments = engine.transcribe(file.absolutePath)
            val elapsed = System.currentTimeMillis() - started

            val hypothesis = segments.joinToString(" ") { it.text }
            val wer = WordErrorRate.compute(reference, hypothesis)
            val refWords = WordErrorRate.normalize(reference).split(' ').count { it.isNotBlank() }

            sum += wer
            totalRefWords += refWords
            totalErrors += wer * refWords
            totalAudioMs += AudioPrep.prepare(context, file, keep = false)
                ?.let { ru.hexteam.stenograph.engine.WavReader.durationMs(it) } ?: 0L
            totalElapsedMs += elapsed

            Log.i("WER", "case=$name wer=${"%.4f".format(wer)} words=$refWords ms=$elapsed")
            Log.i("WER", "  REF: ${WordErrorRate.normalize(reference)}")
            Log.i("WER", "  HYP: ${WordErrorRate.normalize(hypothesis)}")
        }

        val mean = if (all.isEmpty()) 0.0 else sum / all.size
        val weighted = if (totalRefWords == 0) 0.0 else totalErrors / totalRefWords
        val rtf = if (totalAudioMs > 0) totalElapsedMs.toDouble() / totalAudioMs else 0.0

        Log.i(
            "WER",
            "RESULT cases=${all.size} wer_mean=${"%.4f".format(mean)} " +
                "wer_weighted=${"%.4f".format(weighted)} words=$totalRefWords " +
                "audio_ms=$totalAudioMs elapsed_ms=$totalElapsedMs rtf=${"%.3f".format(rtf)}",
        )
    }
}

/**
 * WER = (замены + вставки + удаления) / число слов эталона.
 *
 * Нормализация обязательна и должна совпадать с той, что использует
 * эксперт: регистр, пунктуация и ё/е не должны считаться ошибками
 * распознавания — это не ошибки ASR, а разница оформления.
 */
object WordErrorRate {

    fun compute(reference: String, hypothesis: String): Double {
        val ref = normalize(reference).split(' ').filter { it.isNotBlank() }
        val hyp = normalize(hypothesis).split(' ').filter { it.isNotBlank() }
        if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0

        // Расстояние Левенштейна по словам: классическая формула WER.
        var prev = IntArray(hyp.size + 1) { it }
        var cur = IntArray(hyp.size + 1)
        for (i in 1..ref.size) {
            cur[0] = i
            for (j in 1..hyp.size) {
                val cost = if (ref[i - 1] == hyp[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[hyp.size].toDouble() / ref.size
    }

    fun normalize(text: String): String =
        text.lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
