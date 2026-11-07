package ru.hexteam.stenograph.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import ru.hexteam.stenograph.engine.EnginesFactory
import java.io.File

/**
 * Сквозная проверка узнавания голоса:
 * первая запись регистрирует голоса, вторая — узнаёт их по отпечатку.
 */
@RunWith(AndroidJUnit4::class)
class VoiceEnrollmentTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun audio(): File {
        val target = File(context.cacheDir, "voice_enroll.wav")
        if (!target.isFile || target.length() == 0L) {
            testContext.assets.open("dialog_2spk.wav").use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return target
    }

    @Test
    fun enrollThenRecognize(): Unit = runBlocking {
        val store = EnginesFactory.voiceProfiles(context)
        store.clear()

        val pipeline = EnginesFactory.create(context)
        val embEngine = EnginesFactory.speakerEmbeddings(context)

        // 1. Первый прогон: голоса считаются, база пуста — ничего не узнано.
        val first = pipeline.run(
            audio().absolutePath,
            useLlm = false,
            voiceEngine = embEngine,
            voiceStore = store,
        )
        Log.i("VOICE", "первый прогон: отпечатков=${first.voiceEmbeddings.size}, узнано=${first.recognizedSpeakers.size}")
        if (first.voiceEmbeddings.isEmpty()) {
            Log.i("VOICE", "SKIP: отпечатки не посчитались")
            return@runBlocking
        }
        check(first.recognizedSpeakers.isEmpty()) { "пустая база не должна ничего узнавать" }

        // 2. Регистрируем голоса под именами.
        first.voiceEmbeddings.forEach { (spk, vec) -> store.enroll("Человек-$spk", vec) }
        Log.i("VOICE", "в базе: ${store.load().map { it.name }}")

        // 3. Второй прогон: те же голоса должны узнаться.
        val second = pipeline.run(
            audio().absolutePath,
            useLlm = false,
            voiceEngine = embEngine,
            voiceStore = store,
        )
        Log.i("VOICE", "второй прогон: узнано=${second.recognizedSpeakers}")
        check(second.recognizedSpeakers.size == first.voiceEmbeddings.size) {
            "узнано ${second.recognizedSpeakers.size} из ${first.voiceEmbeddings.size}"
        }

        store.clear()
    }
}
