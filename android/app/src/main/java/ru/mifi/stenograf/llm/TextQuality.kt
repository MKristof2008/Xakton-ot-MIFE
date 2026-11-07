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
        // 1. Одно и то же слово много раз подряд: «он он он он он».
        //    Считаем ДЛИНУ серии, а не разнообразие: «Да! Да! Да!» и
        //    «ну ну ну» — живая речь, там серия короткая и слова короткие.
        var longestRun = 1
        var runNow = 1
        for (i in 1 until words.size) {
            if (words[i] == words[i - 1]) {
                runNow++
                if (runNow > longestRun) longestRun = runNow
            } else {
                runNow = 1
            }
        }
        if (longestRun >= 4) return true
        // 3. Дефисные обрывки: «ни-ни-ни», «ла-ла-ла». Само наличие дефиса
        //    ничего не значит — в русском это нормальное написание, и настоящая
        //    речь вида «надо по-нормальному, а не по-старому» не должна пропадать.
        //    Признак петли — короткие слоги через дефис с малым числом повторов.
        // Признак петли — само строение слова: обрывок вида «ни-ни-ни-ни».
        // Такие слова collapseRepeats превращает в «ни-» (обрезанный хвост),
        // и вот это уже точно мусор, а не речь.
        val brokenByLoop = words.count { w ->
            val parts = w.split('-')
            (parts.size >= 3 && parts.drop(1).distinct().size == 1 && parts[1].length <= 4) ||
                (parts.size >= 2 && parts.last().isEmpty())
        }
        if (words.size >= 1 && brokenByLoop > 0) return true
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
            // Сравниваем без знаков препинания: «Да.» и «Да» — одно слово.
            // Короткие слова («да», «ну», «и») — нормальная речь, «Да. Да. Да.»
            // не петля, поэтому схлопываем только длинные повторы.
            val a = raw[i].trim('.', ',', '!', '?', '-', '…')
            val b = if (i > 0) raw[i - 1].trim('.', ',', '!', '?', '-', '…') else ""
            if (i > 0 && a.equals(b, ignoreCase = true) && a.length > 2) {
                run++
                if (run > REPEAT_LIMIT) continue
            } else {
                run = 1
            }
            out += raw[i]
        }
        val joined = out.joinToString(" ")

        // Слоговая петля: «мини-ни-ни-ни» -> «мини», «ла-ла-ла-ла» -> «ла».
        // Первый слог сохраняется как есть, повторы-близнецы отбрасываются.
        val noSyllableLoop = joined.replace(
            Regex("""(\p{L}{1,})-(\p{L}{1,})(?:-\2){2,}"""),
            "$1-",
        )
        // Растянутые буквы: «оооочень» -> «очень». Три и более одинаковых
        // подряд — всегда артефакт декодера: в русском таких слов нет.
        // Двойные согласные («сумма», «долго») не затрагиваются — там всего две.
        val noStretch = noSyllableLoop.replace(Regex("""(\p{L})\1{3,}""")) { m ->
            m.value.take(1)
        }
        // Повтор короткой фразы целиком: «давай давай давай» -> «давай давай».
        // Фраза должна быть не короче 10 символов: «Да. Да. Да.» и «ну ну» —
        // живая речь, а не петля, её вычищать нельзя.
        return noStretch.replace(
            Regex("""\b((?:\p{L}{2,}[\s,]+)+\p{L}{3,})(?:[,\s]+\1)+""", RegexOption.IGNORE_CASE),
            "$1",
        )
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Схлопывает повтор целой фразы («давай давай давай» -> «давай давай»).
     * Делаем вручную, а не регуляркой: нужно отличить петлю от нормальной
     * речи. «Да. Да. Да.» — петли нет (одно короткое слово), «давай давай
     * давай» — тоже живая речь; петля начинается с трёх повторов длинной фразы.
     */
    private fun collapsePhraseLoop(s: String): String {
        val words = s.split(" ").filter { it.isNotEmpty() }
        if (words.size < 6) return s
        for (len in 2..4) {
            var i = 0
            while (i + len * 3 <= words.size) {
                val phrase = words.subList(i, i + len)
                // Петлёй считаем только фразу из 2+ слов общей длиной от 12 символов.
                if (phrase.joinToString(" ").length < 12) { i++; continue }
                var repeats = 1
                while (i + len * (repeats + 1) <= words.size &&
                    words.subList(i + len * repeats, i + len * (repeats + 1))
                        .map { it.lowercase() } == phrase.map { it.lowercase() }
                ) repeats++
                if (repeats >= 3) {
                    val tail = words.subList(i + len * repeats, words.size)
                    return (words.subList(0, i) + phrase + tail).joinToString(" ")
                }
                i++
            }
        }
        return s
    }

    private fun tokens(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}-]+")).filter { it.isNotEmpty() }
}