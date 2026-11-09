package ru.mifi.stenograf.llm

import android.content.Context
import ai.onnxruntime.genai.Generator
import ai.onnxruntime.genai.GeneratorParams
import ai.onnxruntime.genai.Model
import ai.onnxruntime.genai.Tokenizer
import ru.mifi.stenograf.model.ModelStore
import java.io.File

/**
 * Одна локальная LLM на всё приложение.
 *
 * Раньше каждый вызов `LlmPost(context)` поднимал свой SimpleGenAI и ни разу
 * его не закрывал: модель на 800 МБ оставалась в памяти после каждого прогона.
 * Теперь движок один на процесс, а пунктуатор и переводчик ходят в него по
 * очереди — ONNX Runtime GenAI не умеет генерировать из двух потоков сразу.
 *
 * Вместо `SimpleGenAI.generate` используется связка Model + Tokenizer + Generator:
 * она генерирует токен за токеном, и цикл можно оборвать на первом законченном
 * предложении. `generate` крутит до общего лимита, и модель успевает дописать
 * после перевода абзацы «от себя».
 */
object LocalModel {

    /** Порядок предпочтения: Qwen2.5-0.5B легче для телефона, Phi-3 — запасной. */
    private val SUBDIRS = listOf("qwen2.5-0.5b", "phi3-mini")

    private var model: Model? = null
    private var tokenizer: Tokenizer? = null
    private val lock = Any()

    /** Имя каталога с моделью или null, если модели нет. */
    fun subdir(context: Context): String? = SUBDIRS.firstOrNull { name ->
        val d = File(ModelStore.llmDir(context), name)
        d.isDirectory && d.listFiles()?.any { it.name.endsWith(".onnx") } == true
    }

    fun isAvailable(context: Context): Boolean = subdir(context) != null

    private fun ensure(context: Context) {
        if (model != null) return
        val name = subdir(context) ?: error("Локальная модель не установлена")
        val dir = File(ModelStore.llmDir(context), name)
        val path = if (File(dir, "genai_config.json").exists()) dir.absolutePath
        else dir.listFiles()?.firstOrNull { it.name.endsWith(".onnx") }?.absolutePath
            ?: error("В ${dir.absolutePath} нет модели ONNX Runtime GenAI")
        val m = Model(path)
        model = m
        tokenizer = Tokenizer(m)
    }

    /**
     * Генерирует продолжение промта. Блокирующий вызов — из фонового потока.
     *
     * @param maxNewChars ожидаемая длина ответа; от неё считается max_length
     *   (лимит ВСЕЙ последовательности, промт + ответ).
     * @param stopAt проверка после каждого токена: true — оборвать генерацию.
     */
    fun generate(
        context: Context,
        prompt: String,
        maxNewChars: Int,
        stopAt: (String) -> Boolean = { false },
    ): String = synchronized(lock) {
        ensure(context)
        val m = model!!
        val tok = tokenizer!!

        val params = GeneratorParams(m)
        // Детерминированно: нужна пунктуация и перевод, а не вариативность.
        params.setSearchOption("do_sample", false)
        params.setSearchOption("max_length", (prompt.length / 2 + maxNewChars).toDouble())

        val sb = StringBuilder()
        Generator(m, params).use { gen ->
            gen.appendTokenSequences(tok.encode(prompt))
            tok.createStream().use { stream ->
                while (!gen.isDone) {
                    gen.generateNextToken()
                    val id = gen.getLastTokenInSequence(0)
                    sb.append(stream.decode(id))
                    // Служебные токены приходят текстом — их тоже ловим.
                    val text = sb.toString()
                    if (text.contains("<|im_end|>") || text.contains("<|endoftext|>")) break
                    if (stopAt(text)) break
                }
            }
        }
        sb.toString()
            .substringBefore("<|im_end|>")
            .substringBefore("<|endoftext|>")
            .trim()
    }

    @Synchronized
    fun close() {
        runCatching { tokenizer?.close() }
        runCatching { model?.close() }
        tokenizer = null
        model = null
    }
}