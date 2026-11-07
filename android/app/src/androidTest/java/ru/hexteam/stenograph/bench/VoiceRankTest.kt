package ru.hexteam.stenograph.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import ru.hexteam.stenograph.core.SpeakerSegment
import ru.hexteam.stenograph.engine.EnginesFactory
import ru.hexteam.stenograph.engine.ModelStore
import ru.hexteam.stenograph.engine.SherpaDiarizationEngine
import java.io.File

/**
 * Проверка «одним голосом по всей базе»: ранжирование кандидатов.
 *
 * Сценарий как у эксперта: в базе 4 сохранённых человека (3 чужих +
 * нужный). Проверяем голос — нужный обязан оказаться первым, а проценты
 * показать, насколько он ближе остальных.
 */
@RunWith(AndroidJUnit4::class)
class VoiceRankTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun file(name: String): File {
        val target = File(context.cacheDir, name)
        if (!target.isFile || target.length() == 0L) {
            testContext.assets.open(name).use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return target
    }

    @Test
    fun rankFindsRightPersonFirst(): Unit = runBlocking {
        ModelStore.installDiarization(context)
        val audio = file("dialog_2spk.wav")
        val diar = SherpaDiarizationEngine(context)
        val segments = diar.diarize(audio.absolutePath)
        diar.release()

        val mid = segments.maxOf { it.endMs } / 2
        val firstHalf = segments.filter { it.startMs < mid }.map { it.copy(endMs = minOf(it.endMs, mid)) }
        val secondHalf = segments.filter { it.startMs >= mid }

        val store = EnginesFactory.voiceProfiles(context)
        store.clear()
        EnginesFactory.speakerEmbeddings(context).use { emb ->
            val enrolled = emb.embed(audio.absolutePath, firstHalf)
            val probe = emb.embed(audio.absolutePath, secondHalf)

            // База: настоящий голос + «чужие» голоса из другой записи
            val other = emb.embed(file("user_voice.mp3").absolutePath,
                listOf(SpeakerSegment(0, 10_000, "speaker_9")))
            enrolled.entries.forEachIndexed { i, (spk, v) ->
                store.enroll("Человек-$spk", v)
            }
            other.forEach { (_, v) -> store.enroll("Чужой-голос", v) }

            Log.i("RANK", "в базе: ${store.load().map { it.name }}")

            for ((spk, v) in probe) {
                val ranked = store.rank(v)
                Log.i("RANK", "проверка $spk:")
                ranked.forEach { m ->
                    Log.i("RANK", "   ${"%.1f".format(m.similarity * 100)} %  ${m.name}")
                }
                check(ranked.isNotEmpty()) { "база пуста" }
                check(ranked.first().name.startsWith("Человек")) {
                    "первым должен быть настоящий голос, а не ${ranked.first().name}"
                }
            }
            Log.i("RANK", "RESULT ok")
        }
        store.clear()
    }
}
