package ru.hexteam.stenograph.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import ru.hexteam.stenograph.engine.AudioPrep
import ru.hexteam.stenograph.engine.ModelStore
import ru.hexteam.stenograph.engine.SherpaDiarizationEngine
import ru.hexteam.stenograph.engine.WhisperAsrEngine
import ru.hexteam.stenograph.engine.WavReader
import java.io.File

/**
 * Замеры на реальном устройстве: диаризация и RTF.
 *
 * Запуск:
 *   adb push <wav> /sdcard/Download/test.wav
 *   ./gradlew :app:connectedDebugAndroidTest
 *
 * Числа печатаются в logcat с тегом BENCH и в отчёт теста — оттуда они
 * попадают в docs/benchmarks/measurements.csv. Никаких «приблизительных»
 * значений: только то, что реально измерили на телефоне.
 */
@RunWith(AndroidJUnit4::class)
class DeviceBenchTest {

    /** Приложение под тестом: у него лежат модели и кэш. */
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Тестовый APK: только в нём есть assets с записью для замера. */
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    /**
     * Тестовая запись. Лежит в assets тестового APK: тест не имеет доступа
     * к /sdcard/Download (scoped storage), а копировать вручную — лишний шаг.
     * В основной APK этот файл не попадает: androidTest-ассеты отдельные.
     */
    private fun testAudio(): File {
        val target = File(context.cacheDir, "bench_input.wav")
        if (!target.isFile || target.length() == 0L) {
            testContext.assets.open("dialog_2spk.wav").use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return target
    }

    @Test
    fun diarization_onDevice(): Unit = runBlocking {
        val audio = testAudio()
        val engine = SherpaDiarizationEngine(context)

        if (!engine.isReady()) {
            ModelStore.installDiarization(context) { Log.i("BENCH", "копирую $it") }
        }
        check(engine.isReady()) {
            "Модели диаризации не найдены: ${ModelStore.describe(context)}"
        }

        val wav = AudioPrep.prepare(context, audio, keep = true)!!
        val (samples, rate) = WavReader.read(wav)
        val audioMs = samples.size * 1000L / rate

        val started = System.currentTimeMillis()
        val segments = engine.diarize(audio.absolutePath, expectedSpeakers = null)
        val elapsed = System.currentTimeMillis() - started

        val speakers = segments.map { it.speaker }.distinct().size
        val rtf = if (audioMs > 0) elapsed.toDouble() / audioMs else 0.0

        Log.i(
            "BENCH",
            "DIAR segments=${segments.size} speakers=$speakers " +
                "audio_ms=$audioMs elapsed_ms=$elapsed rtf=${"%.3f".format(rtf)}",
        )
        segments.forEach { Log.i("BENCH", "DIAR ${it.startMs}\t${it.endMs}\t${it.speaker}") }

        check(segments.isNotEmpty()) { "Диаризация вернула пустой результат" }
        check(speakers >= 2) { "Ожидались минимум 2 говорящих, получено $speakers" }
    }

    @Test
    fun asr_rtf_onDevice(): Unit = runBlocking {
        val audio = testAudio()
        val wav = AudioPrep.prepare(context, audio, keep = true)!!
        val (samples, rate) = WavReader.read(wav)
        val audioMs = samples.size * 1000L / rate

        val engine = WhisperAsrEngine(context)
        check(engine.isReady()) { "Модель ASR не найдена" }

        val started = System.currentTimeMillis()
        val segments = engine.transcribe(audio.absolutePath)
        val elapsed = System.currentTimeMillis() - started
        val rtf = if (audioMs > 0) elapsed.toDouble() / audioMs else 0.0

        Log.i(
            "BENCH",
            "ASR segments=${segments.size} audio_ms=$audioMs elapsed_ms=$elapsed " +
                "rtf=${"%.3f".format(rtf)}",
        )
        segments.forEach { Log.i("BENCH", "ASR ${it.startMs}\t${it.endMs}\t${it.text}") }

        check(segments.isNotEmpty()) { "ASR вернул пустой результат" }
    }
}
