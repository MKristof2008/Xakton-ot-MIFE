package ru.hexteam.stenograph.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import ru.hexteam.stenograph.core.PipelineStage
import ru.hexteam.stenograph.engine.EnginesFactory
import java.io.File

/**
 * Сквозная проверка: аудио → ASR → диаризация → сшивка → LLM → JSON.
 *
 * Проверяет ровно то, что будут проверять эксперты:
 *  - пайплайн проходит все этапы на настоящих моделях (без заглушек);
 *  - JSON соответствует схеме ТЗ: четыре поля, целые числа, порядок по start_ms;
 *  - говорящие назначены диаризацией, а не выдуманы.
 */
@RunWith(AndroidJUnit4::class)
class PipelineE2ETest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun audio(): File {
        val target = File(context.cacheDir, "e2e_input.wav")
        if (!target.isFile || target.length() == 0L) {
            testContext.assets.open("dialog_2spk.wav").use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return target
    }

    /**
     * Реальная запись пользователя (54 с, mp3) — воспроизводит падение
     * на длинной записи: раньше процесс убивало по памяти на середине.
     */
    @Test
    fun userRecording(): Unit = runBlocking {
        val target = File(context.cacheDir, "user_voice.mp3")
        if (!target.isFile || target.length() == 0L) {
            testContext.assets.open("user_voice.mp3").use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        val pipeline = EnginesFactory.create(context)
        val started = System.currentTimeMillis()
        val result = pipeline.run(target.absolutePath, useLlm = true)
        val elapsed = System.currentTimeMillis() - started
        Log.i("E2E", "USER реплик=${result.lines.size} время=${elapsed}мс rtf=${"%.3f".format(result.rtf)}")
        result.lines.take(3).forEach { Log.i("E2E", "USER ${it.speakerId} ${it.startMs}-${it.endMs} ${it.text}") }
        check(result.lines.isNotEmpty()) { "пустая расшифровка на реальной записи" }
    }

    /** Длинная запись (2.5 МБ mp3): проверка, что не убивает по памяти. */
    @Test
    fun longRecording(): Unit = runBlocking {
        val target = File(context.cacheDir, "user_long.mp3")
        if (!target.isFile || target.length() == 0L) {
            testContext.assets.open("user_long.mp3").use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        val pipeline = EnginesFactory.create(context)
        val started = System.currentTimeMillis()
        val result = pipeline.run(target.absolutePath, useLlm = true)
        val elapsed = System.currentTimeMillis() - started
        val peakMb = File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM") }?.filter { it.isDigit() }
            ?.toLongOrNull()?.div(1024)
        Log.i(
            "E2E",
            "LONG реплик=${result.lines.size} время=${elapsed}мс " +
                "rtf=${"%.3f".format(result.rtf)} peak_ram_mb=$peakMb",
        )
        check(result.lines.isNotEmpty()) { "пустая расшифровка на длинной записи" }
    }

    @Test
    fun fullPipeline(): Unit = runBlocking {
        val modelName = InstrumentationRegistry.getArguments()
            .getString("model") ?: "ggml-small-q5_1.bin"
        val pipeline = EnginesFactory.create(context, asrModel = "models/$modelName")
        Log.i("E2E", "модель ASR: $modelName")
        val stages = mutableListOf<PipelineStage>()

        val started = System.currentTimeMillis()
        val result = pipeline.run(
            audioPath = audio().absolutePath,
            expectedSpeakers = null,
            useLlm = true,
            onStage = { stages += it },
        )
        val elapsed = System.currentTimeMillis() - started

        Log.i("E2E", "этапы: ${stages.joinToString(" → ")}")
        Log.i("E2E", "реплик: ${result.lines.size}, время: $elapsed мс, RTF=${"%.3f".format(result.rtf)}")
        result.lines.forEach { Log.i("E2E", "  ${it.speakerId} ${it.startMs}-${it.endMs} ${it.text}") }
        Log.i("E2E", "JSON: ${result.json.take(400)}")

        // 1. Все этапы пройдены, ни один не подменён заглушкой.
        check(stages.contains(PipelineStage.ASR)) { "не было этапа ASR" }
        check(stages.contains(PipelineStage.DIARIZATION)) { "не было этапа диаризации" }
        check(stages.contains(PipelineStage.LLM)) { "не было этапа LLM" }
        check(result.lines.isNotEmpty()) { "пустая расшифровка" }

        // 2. Схема JSON по ТЗ.
        val json = JSONObject(result.json)
        val arr = json.getJSONArray("transcript")
        check(arr.length() == result.lines.size) { "число реплик в JSON не совпало" }

        var prevStart = -1L
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            check(o.length() == 4) { "лишние поля в реплике: ${o.keys().asSequence().toList()}" }
            val speaker = o.getString("speaker_id")
            val start = o.getLong("start_ms")
            val end = o.getLong("end_ms")
            val text = o.getString("text")

            check(speaker.startsWith("speaker_")) { "неверный speaker_id: $speaker" }
            check(end >= start) { "end_ms < start_ms в реплике $i" }
            check(text.isNotBlank()) { "пустой текст в реплике $i" }
            check(start >= prevStart) { "реплики не упорядочены по start_ms" }
            prevStart = start
        }

        // 3. Говорящие назначены диаризацией: на диалоге двух человек
        //    должно быть больше одного говорящего, и ни один не «unknown».
        val speakers = result.lines.map { it.speakerId }.distinct()
        Log.i("E2E", "говорящих: ${speakers.size} — $speakers")
        check(speakers.size >= 2) { "диаризация не разделила говорящих: $speakers" }
        check(speakers.none { it == "speaker_unknown" }) { "есть реплики без говорящего" }
    }
}
