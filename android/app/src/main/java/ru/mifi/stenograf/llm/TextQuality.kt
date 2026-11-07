package ru.mifi.stenograf.llm

/**
 * Очистка текста от зацикливаний декодера.
 *
 * Whisper (особенно base/quantized) на протяжных звуках и на словах вне словаря
 * уходит в петлю и выдаёт одно и то же: «подписание в мини-ни-ни-ни-ни»,
 * «дыхки, лым, как гей, лы». Это не ошибка распознавания в обычном смысле —
 * это деградация декодера, и её надо резать, иначе реплика выглядит как бред.
 *
 * Все правила работают и по словам, и по слогам через дефис.
 */
object TextQuality {

    /** Сколько раз подряд должна повториться единица, чтобы счесть это петлёй. */
    private const val REPEAT_LIMIT = 2

    /**
     * Признак сломанной реплики: почти нет уникальных слов, много повторов,
     * обрывки через дефис. Такие сегменты не показываем пользователю вообще —
     * лучше пропуск, чем выдуманный текст.
     */
    fun isDegenerate(text: String): Boolean {
        val words = tokens(text)
        if (words.isEmpty()) return true
        // 1. Слишком много повторов одного и того же слова подряд.
        var run = 1
        for (i in 1 until words.size) {
            if (words[i] == words[i - 1]) {
                run++
                if (run > REPEAT_LIMIT) return true
            } else {
                run = 1
            }
        }
        // 2. Малое разнообразие слов при заметной длине: «и он он он как как».
        val distinct = words.toSet().size
        if (words.size >= 5 && distinct * 100 / words.size < 45) return true
        // 3. Обрывки через дефис: «ни-ни-ни», «ла-ла-ла».
        val hyphenated = words.count { it.contains('-') }
        if (words.size >= 3 && hyphenated * 100 / words.size > 60) return true
        return false
    }

    /**
     * Компактит петлю: если слово повторилось больше [REPEAT_LIMIT] раз подряд,
     * оставляем первые [REPEAT_LIMIT] вхождений. Для протяжного «оооочень»
     * и растянутого «нееет» это возвращает нормальный вид.
     */
    fun collapseRepeats(text: String): String {
        val raw = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (raw.isEmpty()) return text
        val out = mutableListOf<String>()
        var run = 1
        for (i in raw.indices) {
            if (i > 0 && raw[i].equals(raw[i - 1], ignoreCase = true)) {
                run++
                if (run > REPEAT_LIMIT) continue
            } else {
                run = 1
            }
            out += raw[i]
        }
        val joined = out.joinToString(" ")

        // Слоговые петли внутри слова: «мини-ни-ни-ни» -> «мини».
        return joined.replace(Regex("""(\b\p{L}{2,})-(\p{L}{1,4})(?:-\2){2,}"""), "$1-")
            // Растянутые гласные: «оооочень» -> «очень» (3+ одинаковых подряд).
            .replace(Regex("""(\p{L})\1{2,}""")) { m -> m.value.take(2) }
            // Повтор фразы из двух слов: «давай давай давай»
            .replace(
                Regex("""\b((?:\p{L}+[\s,]+){1,3}\p{L}+)(?:[,\s]+\1){2,}""", RegexOption.IGNORE_CASE),
                "$1",
            )
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun tokens(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}-]+")).filter { it.isNotEmpty() }
}