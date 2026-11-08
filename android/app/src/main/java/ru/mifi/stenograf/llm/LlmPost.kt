package ru.mifi.stenograf.llm

import android.content.Context
import android.util.Log
import ru.mifi.stenograf.output.Replica

/**
 * Постобработка текста на локальной LLM (ONNX Runtime GenAI, модель в filesDir).
 *
 * Ограничения, которые держим жёстко:
 *  - модель только расставляет пунктуацию и регистр;
 *  - ответ проверяется: при расхождении длины > 30 % или потере слов — откат;
 *  - если модель не установлена или упала — работает детерминированный пунктуатор,
 *    пайплайн не падает (транскрипт важнее).
 *
 * Движок один на приложение ([LocalModel]): загрузка 800 МБ и ~3 секунды,
 * держать по копии на каждый экран нельзя.
 */
class LlmPost(private val context: Context) {

    companion object {
        private const val TAG = "StenografLLM"
    }

    fun isAvailable(): Boolean = LocalModel.isAvailable(context)

    /**
     * Прогоняет реплики через локальную LLM. Синхронная, вызывается из фонового
     * потока пайплайна — поэтому внутри блокирующая генерация.
     */
    fun polish(replicas: List<Replica>, onProgress: (Float) -> Unit = {}): List<Replica> {
        if (replicas.isEmpty()) return replicas
        if (!isAvailable()) {
            onProgress(1f)
            return replicas.map { it.copy(text = rulePolish(it.text)) }
        }

        val out = ArrayList<Replica>(replicas.size)
        Log.i(TAG, "LLM model: ${LocalModel.subdir(context)}, replicas=${replicas.size}")
        for ((i, rep) in replicas.withIndex()) {
            onProgress(i.toFloat() / replicas.size)
            val t0 = System.currentTimeMillis()
            val result = runCatching { askModel(rep.text) }
            val fixed = result.getOrNull()
            val ms = System.currentTimeMillis() - t0
            val ok = fixed != null && acceptable(fixed, rep.text)
            Log.i(
                TAG,
                "LLM replica $i: ${ms} ms, model=${if (ok) "yes" else "no"}," +
                    " error=${result.exceptionOrNull()?.message ?: "-"}, out=${(fixed ?: "").take(60)}",
            )
            val safe = fixed?.takeIf { acceptable(it, rep.text) } ?: rulePolish(rep.text)
            out += rep.copy(text = safe)
        }
        onProgress(1f)
        return out
    }

    private fun askModel(text: String): String {
        // Промт короткий: длинная инструкция сама стоит секунды генерации.
        val prompt = "Пунктуация и заглавные буквы, без изменений слов:\n" + text + "\nОтвет:\n"
        // Qwen любит дописать «Вот вариант: …» после готового текста.
        // Первая строка — сам ответ, остальное отбрасываем.
        return LocalModel.generate(context, prompt, text.length / 2 + 96)
            .substringBefore('\n')
            .trim()
    }

    /** Проверка ответа модели: не потерял ли слова и не напридумывал ли своего. */
    private fun acceptable(candidate: String, original: String): Boolean {
        val c = candidate.trim()
        if (c.isEmpty()) return false
        if (c.length < original.length * 0.7 || c.length > original.length * 1.3) return false
        val orig = words(original)
        val cand = words(c)
        if (cand.isEmpty()) return false
        // Все слова оригинала должны присутствовать в ответе.
        val pool = cand.toMutableList()
        for (w in orig) {
            val idx = pool.indexOf(w)
            if (idx < 0) return false
            pool.removeAt(idx)
        }
        return true
    }

    private fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Детерминированное восстановление регистра и знаков — без модели (Grammar.kt). */
    fun rulePolish(text: String): String = Grammar.polish(text)

    fun close() = Unit
}