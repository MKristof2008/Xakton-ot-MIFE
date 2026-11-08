package ru.mifi.stenograf.llm

import android.content.Context
import android.util.Log

/**
 * Перевод реплики на другой язык той же локальной моделью.
 *
 * Направление определяется по самому тексту: кириллица → английский,
 * латиница → русский. Переводим по одной реплике и только по действию
 * пользователя: прогонять весь транскрипт сразу — это минуты счёта и нагрев
 * корпуса, а нужно обычно две-три непонятные фразы.
 */
object Translator {

    private const val TAG = "StenografTranslate"

    /** Доля кириллицы среди букв, выше которой текст считается русским. */
    private const val CYRILLIC_SHARE = 0.3f

    enum class Direction(val label: String) { RU_EN("RU → EN"), EN_RU("EN → RU") }

    fun isAvailable(context: Context): Boolean = LocalModel.isAvailable(context)

    fun directionOf(text: String): Direction {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return Direction.EN_RU
        val cyr = text.count { it in 'Ѐ'..'ӿ' }
        return if (cyr.toFloat() / letters >= CYRILLIC_SHARE) Direction.RU_EN else Direction.EN_RU
    }

    /**
     * Блокирующий перевод — вызывать из фонового потока.
     * null: модель не установлена, ответ пустой или повторил оригинал.
     */
    fun translate(context: Context, source: String): String? {
        val text = source.trim()
        if (text.isEmpty() || !isAvailable(context)) return null

        val dir = directionOf(text)
        // Промт минимальный: на телефоне каждый токен инструкции — это секунды.
        // Подсказка языка в конце ("English:") переключает модель в режим
        // продолжения перевода — она не начинает с «Here is the translation».
        val prompt = if (dir == Direction.RU_EN) "Переведи на английский язык:\n$text\nEnglish:"
        else "Переведи на русский язык:\n$text\nРусский:"

        val t0 = System.currentTimeMillis()
        val raw = runCatching { LocalModel.generate(context, prompt, text.length + 96) }
            .getOrElse {
                Log.w(TAG, "translate failed: ${it.message}")
                return null
            }
        val ms = System.currentTimeMillis() - t0
        val clean = cleanup(raw, text)
        Log.i(TAG, "${dir.label} $ms ms: ${clean.take(70)}")
        return clean.takeIf { it.isNotBlank() }
    }

    /**
     * Маленькая модель любит дописать служебную строку («Translation:»,
     * «Перевод:») и повторить оригинал. Берём первую содержательную строку.
     */
    private fun cleanup(raw: String, source: String): String {
        val line = raw.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        val stripped = line
            .removePrefix("Translation:")
            .removePrefix("Перевод:")
            .removePrefix("EN:")
            .removePrefix("RU:")
            .trim()
        return stripped.takeIf { !it.equals(source.trim(), ignoreCase = true) } ?: ""
    }
}