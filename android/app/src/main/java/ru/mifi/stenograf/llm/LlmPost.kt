package ru.mifi.stenograf.llm

import android.content.Context
import ai.onnxruntime.genai.SimpleGenAI
import ru.mifi.stenograf.model.ModelStore
import ru.mifi.stenograf.output.Replica
import java.io.File

/**
 * Постобработка текста на локальной LLM (ONNX Runtime GenAI, модель GGUF/ONNX в filesDir).
 *
 * Ограничения, которые держим жёстко:
 *  - модель только расставляет пунктуацию и регистр;
 *  - ответ проверяется: при расхождении длины > 30 % или потере слов — откат;
 *  - если модель не установлена или упала — работает детерминированный пунктуатор,
 *    пайплайн не падает (транскрипт важнее).
 */
class LlmPost(private val context: Context) {

    private var engine: SimpleGenAI? = null

    companion object {
        /** Каталог модели относительно filesDir/models/llm. */
        const val MODEL_SUBDIR = "phi3-mini"
    }

    private fun modelDir(): File = File(ModelStore.llmDir(context), MODEL_SUBDIR)

    fun isAvailable(): Boolean {
        val d = modelDir()
        return d.isDirectory && d.listFiles()?.any { it.name.endsWith(".onnx") || it.name.endsWith(".onnx.data") } == true
    }

    /**
     * Прогоняет реплики через локальную LLM. Безопасна для вызова из фонового потока.
     */
    fun polish(replicas: List<Replica>, onProgress: (Float) -> Unit = {}): List<Replica> {
        if (replicas.isEmpty()) return replicas
        if (!isAvailable()) {
            onProgress(1f)
            return replicas.map { it.copy(text = rulePolish(it.text)) }
        }

        val out = ArrayList<Replica>(replicas.size)
        for ((i, rep) in replicas.withIndex()) {
            onProgress(i.toFloat() / replicas.size)
            val fixed = runCatching { askModel(rep.text) }.getOrNull()
            val safe = fixed?.takeIf { acceptable(it, rep.text) } ?: rulePolish(rep.text)
            out += rep.copy(text = safe)
        }
        onProgress(1f)
        return out
    }

    private fun ensureEngine(): SimpleGenAI {
        engine?.let { return it }
        val dir = modelDir()
        require(dir.isDirectory) { "Нет каталога модели: ${dir.absolutePath}" }
        val genaiConfig = File(dir, "genai_config.json")
        val path = if (genaiConfig.exists()) dir.absolutePath else {
            dir.listFiles()?.firstOrNull { it.name.endsWith(".onnx") }?.absolutePath
                ?: error("В ${dir.absolutePath} нет модели ONNX Runtime GenAI")
        }
        val e = SimpleGenAI(path)
        engine = e
        return e
    }

    private fun askModel(text: String): String {
        val e = ensureEngine()
        val params = e.createGeneratorParams()
        // Детерминированно: нам нужна пунктуация, а не вариативность.
        params.setSearchOption("do_sample", false)
        params.setSearchOption("max_length", (text.length / 2 + 96).toDouble())

        val prompt = buildString {
            append("<|user|>")
            append('\n')
            append("Восстанови пунктуацию и заглавные буквы в тексте ниже. ")
            append("Не добавляй и не убирай слова, не перефразируй. ")
            append("Ответь только исправленным текстом без пояснений.")
            append("\n\n")
            append(text)
            append("\n<|end|>\n<|assistant|>\n")
        }
        return e.generate(params, prompt) { }
            .substringAfter("<|assistant|>")
            .substringBefore("<|end|>")
            .substringBefore("\n<|")
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

    fun close() {
        runCatching { engine?.close() }
        engine = null
    }
}