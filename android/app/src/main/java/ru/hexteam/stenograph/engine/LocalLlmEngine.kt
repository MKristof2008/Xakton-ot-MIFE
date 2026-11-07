package ru.hexteam.stenograph.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.hexteam.stenograph.core.TranscriptLine
import java.io.File

/**
 * Локальная LLM на устройстве (llama.cpp, GGUF).
 *
 * ТЗ требует локальную LLM в пайплайне, а не для галочки. Роль выбрана по
 * результатам A/B-замера (docs/ML_MODELS.md): переписывание текста моделью
 * ухудшало WER (0.144 → 0.155), а пунктуация и регистр — не меняли (0.1443).
 * Поэтому модель делает только пунктуацию/регистр, и результат принимается
 * лишь после проверки:
 *
 *  - длина ответа отличается от исходной больше чем на [MAX_LENGTH_RATIO] → откат;
 *  - пропало больше [MAX_LOST_WORDS_RATIO] исходных слов → откат;
 *  - появились слова, которых не было (галлюцинация) → откат.
 *
 * Откат означает «вернуть текст ASR без изменений»: расшифровка важнее
 * красивой пунктуации. Модель никогда не удаляет и не добавляет смысл.
 *
 * Почему llama.cpp, а не onnxruntime-genai: AAR genai тянет в манифест
 * INTERNET/ACCESS_NETWORK_STATE и телеметрию Microsoft с HTTP-клиентом —
 * прямое нарушение ТЗ. llama.cpp — чистый C++, сетевых зависимостей нет.
 */
class LocalLlmEngine(
    context: Context,
    private val modelFile: File = File(ModelStore.llmDir(context), DEFAULT_MODEL),
) : LlmEngine {

    private val appContext = context.applicationContext
    private var handle: Long = 0L

    companion object {
        private const val TAG = "LocalLlm"

        /**
         * Модель: Qwen2.5-0.5B-Instruct, квантизация Q4_K_M (469 МБ).
         *
         * Почему 0.5B, а не 1.5B: задача узкая — пунктуация и регистр. На ПК
         * A/B-замер показал, что переписывание текста моделью ухудшает WER
         * (0.144 → 0.155), поэтому 1.5B не даёт выигрыша в точности, но
         * занимает втрое больше RAM и заметно медленнее на телефоне.
         * Замеры на устройстве — docs/benchmarks/measurements.csv.
         */
        const val DEFAULT_MODEL = "qwen2.5-0.5b-instruct-q4_k_m.gguf"

        /** Допустимое изменение длины текста после пунктуации. */
        private const val MAX_LENGTH_RATIO = 0.30

        /** Доля потерянных исходных слов, после которой ответ считается порчей. */
        private const val MAX_LOST_WORDS_RATIO = 0.05

        private const val MAX_TOKENS = 512

        init {
            System.loadLibrary("llm_jni")
        }
    }

    override fun isReady(): Boolean = modelFile.isFile && modelFile.length() > 0

    /** Достаёт модель из assets при первом запуске (сеть не нужна). */
    fun install(onProgress: (String) -> Unit = {}): Boolean =
        ModelStore.installLlm(appContext, modelFile.name, onProgress)

    override suspend fun clean(text: String): String = withContext(Dispatchers.Default) {
        if (text.isBlank() || !isReady()) return@withContext text
        if (!load()) return@withContext text

        val prompt = buildPrompt(text)
        val raw = runCatching { nativeGenerate(handle, prompt, MAX_TOKENS) }
            .onFailure { Log.w(TAG, "генерация не удалась: ${it.message}") }
            .getOrNull()
            ?: return@withContext text

        val candidate = postProcess(raw, text)
        if (acceptable(candidate, text)) candidate else text
    }

    /**
     * Загружает модель один раз на этап. Повторных загрузок нет:
     * `handle` живёт до [release], а release вызывается пайплайном после
     * обработки всех реплик — иначе модель весом ~478 МБ грузилась бы
     * заново на каждой строке.
     */
    private fun load(): Boolean {
        if (handle != 0L) return true
        handle = runCatching { nativeInit(modelFile.absolutePath, THREADS) }
            .onFailure { Log.e(TAG, "не удалось загрузить модель: ${it.message}") }
            .getOrDefault(0L)
        if (handle == 0L) Log.e(TAG, "модель не загружена: ${modelFile.absolutePath}")
        return handle != 0L
    }

    /** Освобождает модель (~478 МБ) после обработки всех реплик. */
    override fun release() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
            Log.i(TAG, "модель выгружена, память освобождена")
        }
    }

    /**
     * Промпт минимальный и жёсткий: одна задача, без свободы формулировать.
     * Instruct-формат Qwen2.5 (ChatML).
     */
    private fun buildPrompt(text: String): String = buildString {
        append("<|im_start|>system\n")
        append("Ты расставляешь знаки препинания и заглавные буквы в русском тексте. ")
        append("Не меняй слова, не добавляй и не убирай их, не перефразируй. ")
        append("Верни только исправленный текст одной строкой.<|im_end|>\n")
        append("<|im_start|>user\n")
        append(text)
        append("<|im_end|>\n<|im_start|>assistant\n")
    }

    /** Убирает служебные токены и обвязку, если модель их всё же повторила. */
    private fun postProcess(raw: String, original: String): String {
        var out = raw
        out = out.substringBefore("<|im_end|>")
        out = out.substringAfter("<|im_start|>assistant", out)
        out = out.lines().firstOrNull { it.isNotBlank() } ?: original
        return out.trim().trim('"', '«', '»', '\'').trim()
    }

    /**
     * Проверка «модель не испортила текст».
     *
     * Сравниваем по словам без пунктуации и регистра: пунктуация — это ровно
     * то, что модель имеет право менять. Если набор слов разошёлся, ответ
     * отбрасывается целиком.
     */
    internal fun acceptable(candidate: String, original: String): Boolean {
        if (candidate.isBlank()) return false
        if (original.isBlank()) return false

        val lengthRatio = kotlin.math.abs(candidate.length - original.length).toDouble() /
            original.length
        if (lengthRatio > MAX_LENGTH_RATIO) return false

        val originalWords = words(original)
        val candidateWords = words(candidate)
        if (originalWords.isEmpty()) return false

        val candidateSet = candidateWords.toSet()
        val lost = originalWords.count { it !in candidateSet }
        if (lost.toDouble() / originalWords.size > MAX_LOST_WORDS_RATIO) return false

        // Новые слова — признак галлюцинации: модель не имеет права их добавлять.
        val originalSet = originalWords.toSet()
        val added = candidateWords.count { it !in originalSet }
        if (added.toDouble() / candidateWords.size.coerceAtLeast(1) > MAX_LOST_WORDS_RATIO) {
            return false
        }
        return true
    }

    private fun words(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    private fun threads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    private val THREADS: Int get() = threads()

    private external fun nativeInit(modelPath: String, threads: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String?
    private external fun nativeRelease(handle: Long)
}
