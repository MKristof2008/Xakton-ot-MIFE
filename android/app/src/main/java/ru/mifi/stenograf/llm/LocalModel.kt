package ru.mifi.stenograf.llm

import android.content.Context
import ai.onnxruntime.genai.SimpleGenAI
import ru.mifi.stenograf.model.ModelStore
import java.io.File

/**
 * Одна локальная LLM на всё приложение.
 *
 * Раньше каждый вызов `LlmPost(context)` поднимал свой SimpleGenAI и ни разу
 * его не закрывал: модель на 800 МБ оставалась в памяти после каждого прогона.
 * Теперь движок один на процесс, а пунктуатор и переводчик ходят в него по
 * очереди — ONNX Runtime GenAI не умеет генерировать из двух потоков сразу.
 */
object LocalModel {

    /** Порядок предпочтения: Qwen2.5-0.5B легче для телефона, Phi-3 — запасной. */
    private val SUBDIRS = listOf("qwen2.5-0.5b", "phi3-mini")

    private var engine: SimpleGenAI? = null
    private val lock = Any()

    /** Имя каталога с моделью или null, если модели нет. */
    fun subdir(context: Context): String? = SUBDIRS.firstOrNull { name ->
        val d = File(ModelStore.llmDir(context), name)
        d.isDirectory && d.listFiles()?.any { it.name.endsWith(".onnx") } == true
    }

    fun isAvailable(context: Context): Boolean = subdir(context) != null

    private fun engine(context: Context): SimpleGenAI {
        engine?.let { return it }
        val name = subdir(context) ?: error("Локальная модель не установлена")
        val dir = File(ModelStore.llmDir(context), name)
        val path = if (File(dir, "genai_config.json").exists()) dir.absolutePath
        else dir.listFiles()?.firstOrNull { it.name.endsWith(".onnx") }?.absolutePath
            ?: error("В ${dir.absolutePath} нет модели ONNX Runtime GenAI")
        return SimpleGenAI(path).also { engine = it }
    }

    /**
     * Блокирующая генерация; вызывать из фонового потока.
     *
     * `SimpleGenAI.generate` возвращает и промт, и ответ одним потоком, поэтому
     * текст собираем из listener — он отдаёт только сгенерированные токены.
     *
     * @param maxNewChars ожидаемая длина ответа: от неё считается max_length.
     */
    fun generate(context: Context, prompt: String, maxNewChars: Int): String =
        synchronized(lock) {
            val e = engine(context)
            val params = e.createGeneratorParams()
            params.setSearchOption("do_sample", false)
            // max_length — лимит ВСЕЙ последовательности (промт + ответ).
            // Меньше длины промта он обрывает генерацию сразу, больше — модель
            // уходит в болтовню и греет телефон.
            params.setSearchOption("max_length", (prompt.length / 2 + maxNewChars).toDouble())
            val sb = StringBuilder()
            e.generate(params, prompt) { token -> sb.append(token) }
            sb.toString()
                .substringBefore("<|im_end|>")
                .substringBefore("<|endoftext|>")
                .trim()
        }

    @Synchronized
    fun close() {
        runCatching { engine?.close() }
        engine = null
    }
}