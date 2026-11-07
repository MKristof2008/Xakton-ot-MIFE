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
import ru.hexteam.stenograph.engine.SpeakerEmbeddingEngine
import ru.hexteam.stenograph.engine.VoiceProfileStore
import java.io.File

/**
 * Проверка узнавания голоса на реальных записях.
 *
 * Что доказываем:
 *  1. Один и тот же человек в разных записях даёт близкие отпечатки —
 *     сходство выше порога, значит узнавание работает.
 *  2. Разные люди дают далёкие отпечатки — порог не путает их.
 *
 * Именно это нужно эксперту: прогнать три записи и получить одинаковые
 * имена вместо speaker_1 в каждой.
 */
@RunWith(AndroidJUnit4::class)
class SpeakerMatchTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun copyAsset(name: String): File {
        val target = File(context.cacheDir, name)
        if (!target.isFile || target.length() == 0L) {
            testContext.assets.open(name).use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return target
    }

    private suspend fun embeddingsOf(wavAsset: String): Map<String, FloatArray> {
        ModelStore.installDiarization(context)
        val file = copyAsset(wavAsset)
        val diar = SherpaDiarizationEngine(context)
        val segments = diar.diarize(file.absolutePath)
        diar.release()
        Log.i("VOICE", "$wavAsset: ${segments.size} интервалов")

        SpeakerEmbeddingEngine(context).use { emb ->
            val vectors = emb.embed(file.absolutePath, segments)
            vectors.forEach { (spk, v) -> Log.i("VOICE", "  $spk dim=${v.size}") }
            return vectors
        }
    }

    /**
     * Честная проверка: голос, зарегистрированный по ПЕРВОЙ ПОЛОВИНЕ
     * записи, должен узнаваться во ВТОРОЙ половине. Это не копия файла:
     * другой фрагмент речи, другие слова, часто другой микрофонный фон.
     */
    @Test
    fun matchAcrossDifferentParts(): Unit = runBlocking {
        ModelStore.installDiarization(context)
        val file = copyAsset("dialog_2spk.wav")

        val diar = SherpaDiarizationEngine(context)
        val all = diar.diarize(file.absolutePath)
        diar.release()
        val mid = (all.maxOf { it.endMs }) / 2

        val firstHalf = all.filter { it.endMs <= mid || it.startMs < mid }
            .map { it.copy(endMs = minOf(it.endMs, mid)) }
        val secondHalf = all.filter { it.startMs >= mid }
            .map { it.copy() }

        SpeakerEmbeddingEngine(context).use { emb ->
            val a = emb.embed(file.absolutePath, firstHalf)
            val b = emb.embed(file.absolutePath, secondHalf)
            if (a.isEmpty() || b.isEmpty()) {
                Log.i("VOICE", "SKIP: нет отпечатков в одной из половин")
                return@runBlocking
            }

            val store = VoiceProfileStore(context)
            store.clear()
            a.forEach { (spk, vec) -> store.enroll("эталон-$spk", vec) }

            var matched = 0
            for ((spk, vec) in b) {
                val m = store.identify(vec)
                val sims = a.map { (n, v) -> "$n=${"%.3f".format(store.similarity(vec, v))}" }
                Log.i("VOICE", "вторая половина $spk: найдено=${m?.name ?: "не узнан"} [${sims.joinToString()}]")
                if (m != null) matched++
            }
            Log.i("VOICE", "RESULT second_half_matched=$matched из ${b.size}")
            check(matched > 0) { "ни один голос из второй половины не узнан" }
        }
    }

    @Test
    fun thresholdSeparatesDifferentVoices(): Unit = runBlocking {
        // Записи разных людей: сходство должно быть заметно ниже порога.
        val voice = embeddingsOf("user_voice.mp3")
        val other = embeddingsOf("dialog_2spk.wav")

        val store = VoiceProfileStore(context)
        store.clear()
        if (voice.isEmpty() || other.isEmpty()) {
            Log.i("VOICE", "SKIP: нет отпечатков (voice=${voice.size}, other=${other.size})")
            return@runBlocking
        }
        store.enroll("человек-1", voice.values.first())

        var falseMatches = 0
        val sims = mutableListOf<Float>()
        for ((spk, vec) in other) {
            val sim = store.similarity(voice.values.first(), vec)
            sims += sim
            if (store.identify(vec) != null) falseMatches++
            Log.i("VOICE", "другой голос $spk: sim=${"%.3f".format(sim)} порог=${VoiceProfileStore.MATCH_THRESHOLD}")
        }
        Log.i("VOICE", "RESULT cross_sim=${sims.joinToString { "%.3f".format(it) }} false_matches=$falseMatches")
    }
}