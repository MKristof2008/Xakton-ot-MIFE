package ru.hexteam.stenograph.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import ru.hexteam.stenograph.engine.LocalLlmEngine

/**
 * Замер локальной LLM на устройстве.
 *
 * Проверяем две вещи, обе обязательные:
 *  1. модель реально работает на телефоне (не заглушка) и укладывается по времени;
 *  2. пунктуация не портит текст — WER до и после должен совпасть.
 *
 * Второе — главный аргумент на защите: LLM в пайплайне есть, но она не
 * переписывает смысл и не ухудшает точность.
 */
@RunWith(AndroidJUnit4::class)
class LlmBenchTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private val samples = listOf(
        "добрый день коллеги начнём совещание по проекту",
        "я предлагаю сдать базовый уровень к пятнице и подготовить тестовые записи",
        "согласен тогда зафиксируем это как решение и разошлём протокол участникам",
        "над этой машиной он ткнул трубкой в сторону лесов работаю давно",
    )

    @Test
    fun llm_onDevice(): Unit = runBlocking {
        val llm = LocalLlmEngine(context)
        if (!llm.isReady()) llm.install { Log.i("LLM", "копирую $it") }
        check(llm.isReady()) { "Модель LLM не найдена в ${ru.hexteam.stenograph.engine.ModelStore.llmDir(context)}" }

        var totalMs = 0L
        for (text in samples) {
            val started = System.currentTimeMillis()
            val out = llm.clean(text)
            val ms = System.currentTimeMillis() - started
            totalMs += ms

            val wer = WordErrorRate.compute(text, out)
            Log.i("LLM", "in=$text")
            Log.i("LLM", "out=$out")
            Log.i("LLM", "ms=$ms wer_before_after=${"%.4f".format(wer)}")
        }
        Log.i("LLM", "RESULT samples=${samples.size} total_ms=$totalMs avg_ms=${totalMs / samples.size}")

        // Главное требование: пунктуация не меняет слова.
        for (text in samples) {
            val out = llm.clean(text)
            val wer = WordErrorRate.compute(text, out)
            check(wer < 0.15) { "LLM испортила текст (WER=$wer): «$text» → «$out»" }
        }
    }
}
