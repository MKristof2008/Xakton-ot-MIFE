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

    /** Границы предложений. Нужны, чтобы оборвать генерацию ровно на своём тексте. */
    private val SENTENCE_END = Regex("[.!?…]+(?=\\s|$)")

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
     * null: модель не установлена или вернула пустоту.
     */
    fun translate(context: Context, source: String): String? {
        val text = source.trim()
        if (text.isEmpty() || !isAvailable(context)) return null

        val dir = directionOf(text)
        // Кавычки вокруг текста — единственный ограничитель, который эта модель
        // понимает: без них она считает вход началом документа и продолжает
        // переводить «дальше», подмешивая чужие слова.
        val instruction =
            if (dir == Direction.RU_EN) "Переведи на английский текст в кавычках:"
            else "Переведи на русский текст в кавычках:"
        val marker = if (dir == Direction.RU_EN) "English:" else "Русский:"
        // Короткие фрагменты («Хорошо.», «Да.») модель переводит наугад: контекста
        // в них нет, и она подставляет постороннюю фразу. Лучше не переводить,
        // чем показать «Hello, I am a new user of this website».
        if (text.length < 12) return null
        val prompt = "$instruction\n«$text»\n$marker"

        // Сколько предложений в оригинале — столько ждём и в переводе.
        // Оборвать раньше нельзя: в реплике может быть два коротких предложения.
        val wanted = SENTENCE_END.findAll(text).count().coerceAtLeast(1)

        val t0 = System.currentTimeMillis()
        val raw = runCatching {
            LocalModel.generate(
                context = context,
                prompt = prompt,
                // Потолок по длине, а не ранний обрыв: обрыв на первом
                // предложении ловил постороннюю фразу, которую модель
                // выдавала перед правильным переводом.
                maxNewChars = text.length * 2 + 96,
            )
        }.getOrElse {
            Log.w(TAG, "translate failed: ${it.message}")
            return null
        }
        val ms = System.currentTimeMillis() - t0
        val clean = cleanup(raw, text, wanted)
        Log.i(TAG, "${dir.label} $ms ms OUT=${clean.replace("\n", " ")}")
        return clean.takeIf { it.isNotBlank() }
    }

    /**
     * Из ответа модели надо оставить ровно столько предложений, сколько было
     * в оригинале: дальше идут посторонние фразы, которые модель досочинила.
     */
    private fun cleanup(raw: String, source: String, sentenceCount: Int): String {
        var s = raw.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() } ?: return ""
        s = s
            .removePrefix("Translation:")
            .removePrefix("Перевод:")
            .removePrefix("EN:")
            .removePrefix("RU:")
            .trim()
            .trim('"', '«', '»', '“', '”')
            .trim()

        // Обрезаем по последнему концу предложения, если модель выдала больше
        // предложений, чем в оригинале.
        val ends = SENTENCE_END.findAll(s).toList()
        if (ends.size > sentenceCount) {
            s = s.substring(0, ends[sentenceCount - 1].range.last + 1).trim()
        }

        // Хвост из кавычек и знаков, затем один знак — как в оригинале.
        s = s.replace(Regex("""[\s.!?…"«»“”]+$"""), "").trim()
        val end = source.trimEnd().lastOrNull()
        if (end in listOf('.', '!', '?', '…')) s += end
        return s.takeIf { !it.equals(source.trim(), ignoreCase = true) } ?: ""
    }
}