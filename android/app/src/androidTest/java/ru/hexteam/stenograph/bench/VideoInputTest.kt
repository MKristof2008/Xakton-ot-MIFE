package ru.hexteam.stenograph.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import ru.hexteam.stenograph.engine.AudioPrep
import ru.hexteam.stenograph.engine.EnginesFactory
import ru.hexteam.stenograph.engine.WavReader
import java.io.File

/**
 * Проверка «видео → текст»: приложение должно принимать видеофайл и
 * расшифровывать его звуковую дорожку, ничего не отправляя в сеть.
 */
@RunWith(AndroidJUnit4::class)
class VideoInputTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun videoToTranscript(): Unit = runBlocking {
        val video = File(context.cacheDir, "test_video.mp4")
        if (!video.isFile || video.length() == 0L) {
            testContext.assets.open("test_video.mp4").use { input ->
                video.outputStream().use { out -> input.copyTo(out) }
            }
        }
        Log.i("VIDEO", "видео: ${video.length() / 1024 / 1024} МБ")

        val wav = AudioPrep.prepare(context, video, keep = true)
        check(wav != null) { "не удалось извлечь звуковую дорожку из видео" }
        val durationMs = WavReader.durationMs(wav!!)
        Log.i("VIDEO", "извлечено WAV: ${wav.length() / 1024 / 1024} МБ, $durationMs мс")
        check(durationMs > 1000) { "дорожка пустая: $durationMs мс" }

        val pipeline = EnginesFactory.create(context)
        val started = System.currentTimeMillis()
        val result = pipeline.run(video.absolutePath, useLlm = true)
        val elapsed = System.currentTimeMillis() - started

        Log.i("VIDEO", "RESULT реплик=${result.lines.size} время=${elapsed}мс")
        result.lines.take(3).forEach {
            Log.i("VIDEO", "  ${it.speakerId} ${it.startMs}-${it.endMs} ${it.text}")
        }
        check(result.lines.isNotEmpty()) { "видео не расшифровано" }
    }
}
