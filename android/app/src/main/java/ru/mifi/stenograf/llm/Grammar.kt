package ru.mifi.stenograf.llm

/**
 * Восстановление грамотности без модели: то, что можно сделать правилами,
 * детерминированно и бесплатно.
 *
 * Зачем. Даже при отсутствии локальной LLM текст расшифровки должен читаться
 * как речь, а не как поток BPE-кусков. Все правила проверены на русском и
 * не меняют ни одного слова — только регистр, пробелы и знаки.
 */
object Grammar {

    /** С какого места реплики начинаем искать границу предложения (символов). */
    private const val MIN_SPLIT_CHARS = 45

    /** Сокращения, после которых точка не заканчивает предложение. */
    private val ABBREVIATIONS = setOf(
        "т", "е", "д", "п", "др", "г", "гг", "руб", "млн", "млрд", "тыс",
        "ул", "стр", "рис", "табл", "см", "напр", "им",
    )

    /** Союзы и вводные слова, перед которыми в русском почти всегда ставится запятая. */
    private val COMMA_BEFORE = listOf(
        "но", "а", "однако", "зато", "что", "чтобы", "потому", "поэтому",
        "который", "которая", "которые", "которого", "если", "когда", "хотя",
        "пока", "кроме", "также", "кстати", "например", "конечно", "кажется",
        "наверное", "во-первых", "во-вторых",
    )

    /** Вводные слова, которые выделяются запятыми с двух сторон. */
    private val PARENTHETICAL = listOf(
        "конечно", "кажется", "наверное", "кстати", "например", "во-первых",
        "во-вторых", "к сожалению", "к счастью", "пожалуй", "в общем",
    )

    /**
     * Обрабатывает текст одной реплики: чистка пробелов, пунктуация, регистр.
     * Слова не добавляются и не удаляются (кроме явного мусора из одиночных
     * латинских символов — артефакта декодера на тишине).
     */
    fun polish(text: String): String = polish(text, russian = true)

    /**
     * То же с явным указанием языка (для тестов и вызовов из пайплайна).
     *
     * Английский нельзя прогонять через русские правила: во-первых, в нём
     * одиночная латинская буква — это местоимение «I», а не мусор декодера;
     * во-вторых, запятые перед «а» и «что» там не ставятся вовсе. Поэтому
     * для не-русского текста остаются только безопасные шаги: пробелы,
     * регистр и финальная точка.
     */
    fun polish(text: String, russian: Boolean): String {
        var s = text.trim()
        if (s.isEmpty()) return s

        // Петли декодера режутся на любом языке: это деградация модели, а не речь.
        s = TextQuality.collapseRepeats(s)
        if (!russian) {
            s = normalizeSpaces(s)
            s = capitalizeSentences(s)
            s = ensureFinalPunctuation(s)
            return s
        }
        // Дальше — только русские правила.
        // Петли режем ДО пунктуации: иначе «ни-ни-ни» превращается
        // в «ни-ни-ни» с запятыми и выглядит ещё хуже.
        s = dropDecoderNoise(s)
        s = normalizeSpaces(s)
        s = restoreSentenceBoundaries(s)
        s = insertCommas(s)
        s = capitalizeSentences(s)
        s = ensureFinalPunctuation(s)
        return s
    }

    /**
     * Артефакты декодера на тишине: одиночные латинские буквы и повторяющиеся
     * односимвольные куски («P P.», «А а а»). Настоящей речи там нет.
     */
    private fun dropDecoderNoise(s: String): String {
        val tokens = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val meaningful = tokens.filterNot { t ->
            val clear = t.trim('.', ',', '!', '?', '-', '…')
            // Один латинский символ — почти всегда мусор от модели.
            clear.length == 1 && clear[0] in 'a'..'z' || clear.length == 1 && clear[0] in 'A'..'Z'
        }
        return meaningful.joinToString(" ")
    }

    private fun normalizeSpaces(s: String): String =
        // Сначала любые пробельные символы (включая переводы строк из токенов
        // Whisper) -> один пробел. Иначе реплика приходит с переносом строки.
        s.replace(Regex("\\s+"), " ")
            .replace(Regex(" ([.,!?;:…])"), "$1")
            .trim()

    /**
     * Расставляет точки там, где модель их потеряла: пауза в речи Whisper
     * передаёт заглавной буквой следующего слова. Дополнительно разбиваем
     * чрезмерно длинные реплики на предложения по союзам.
     */
    private fun restoreSentenceBoundaries(s: String): String {
        var out = s
        // Двойные пробелы и следы токенизации уже убраны; остаётся длинная строка
        // без точек — ставим их перед «потому что», «а», «но» в середине фразы.
        if (!out.contains('.')) {
            for (word in listOf("потому что", "но", "а", "однако", "поэтому")) {
                val idx = out.indexOf(" $word ", startIndex = 20)
                if (idx > 0 && idx < out.length - 10) {
                    out = out.substring(0, idx) + ", " + out.substring(idx + 1)
                }
            }
            // Очень длинную реплику без единой точки читать невозможно:
            // zipformer вообще не ставит знаков. Делим её на предложения
            // по союзам — грубо, но текст становится читаемым.
            out = splitLongRun(out)
        }
        return out
    }

    /** Союзы и частицы, с которых естественно начинать новое предложение. */
    private val SENTENCE_START = listOf(
        "а", "но", "и вот", "вот", "тогда", "потом", "потому что", "поэтому",
        "слушай", "смотри", "давай", "короче",
    )

    /**
     * Делит длинную реплику без знаков на предложения по союзам.
     *
     * Порог [MIN_SPLIT_CHARS] выбран так, чтобы не трогать короткие реплики:
     * в них одна мысль, дробление только мешает. Если делить нечего —
     * возвращаем текст как есть, лучше без точек, чем с точкой посреди слова.
     */
    private fun splitLongRun(s: String): String {
        val words = s.split(' ').filter { it.isNotEmpty() }
        if (words.size < 12) return s

        val out = StringBuilder()
        var sinceSplit = 0
        for ((i, w) in words.withIndex()) {
            if (i > 0) {
                val lower = w.lowercase()
                val canSplit = sinceSplit >= MIN_SPLIT_CHARS && i < words.size - 3 &&
                    SENTENCE_START.any { lower == it || lower.startsWith("$it ") }
                if (canSplit) {
                    out.append(". ")
                    sinceSplit = 0
                } else {
                    out.append(' ')
                }
            }
            out.append(w)
            sinceSplit += w.length + 1
        }
        return out.toString()
    }

    /** Запятые перед союзами и вокруг вводных слов. */
    private fun insertCommas(s: String): String {
        var out = s
        for (w in PARENTHETICAL) {
            // Вводное слово выделяется запятыми только В СЕРЕДИНЕ предложения.
            // В начале запятая перед ним не нужна: «Мне кажется, этим стоит
            // заняться» — а не «Мне, кажется, этим стоит заняться».
            val re = Regex(
                "(?<=[\\p{L}\\d,.!?])\\s+${Regex.escape(w)}\\s+(?!,)",
                RegexOption.IGNORE_CASE,
            )
            out = re.replace(out) { m ->
                // Вводное слово обособляем только в середине предложения.
                // В начале реплики не трогаем вообще: любая вставка там
                // склеивала соседние слова («Мне кажется» -> «Мнекажется»),
                // а «Мне кажется, этим стоит заняться» и так верно.
                val prefix = out.substring(0, m.range.first)
                val lastChar = prefix.trimEnd().lastOrNull()
                val atStart = prefix.isBlank() ||
                    (lastChar != null && lastChar in ".!?…")
                if (atStart || out.getOrNull(m.range.first - 1) == ',') m.value
                else ", ${m.value.trim()}, "
            }
        }
        for (w in COMMA_BEFORE) {
            // Запятая перед союзом, если это не начало предложения и запятой ещё нет
            val re = Regex("(?<=[\\p{L}\\d])\\s+${Regex.escape(w)}\\s+", RegexOption.IGNORE_CASE)
            out = re.replace(out) { m ->
                if (out.getOrNull(m.range.first - 1) == ',') m.value else ", ${m.value.trim()} "
            }
        }
        // Двойные запятые и запятая перед точкой — убираем
        out = out.replace(Regex(",\\s*,"), ",")
            .replace(Regex(",\\s*([.!?…])"), "$1")
            .replace(Regex("\\s+,"), ",")
            .replace(Regex(",\\s*"), ", ")
        return out.trim()
    }

    private fun capitalizeSentences(s: String): String {
        val sb = StringBuilder(s.length)
        var capitalizeNext = true
        for (i in s.indices) {
            val c = s[i]
            if (capitalizeNext && c.isLetter()) {
                sb.append(c.uppercaseChar())
                capitalizeNext = false
            } else {
                sb.append(c)
                if (c in ".!?…") {
                    // Точка после сокращения не начинает новое предложение.
                    val prevWord = s.substring(0, i).trimEnd('.').substringAfterLast(' ').lowercase()
                    capitalizeNext = prevWord !in ABBREVIATIONS
                }
            }
        }
        return sb.toString()
    }

    private fun ensureFinalPunctuation(s: String): String {
        val t = s.trimEnd()
        if (t.isEmpty()) return t
        return if (t.last() in ".!?…") t else "$t."
    }

    /**
     * Склеивает две соседние реплики одного говорящего в одну строку.
     * Первая заканчивается точкой — значит, это разные предложения; иначе
     * склеиваем через пробел, чтобы не рвать фразу на стыке кусков.
     */
    fun join(a: String, b: String): String {
        val left = a.trimEnd()
        val right = b.trimStart()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val glue = if (left.last() in ".!?…") " " else " "
        val joined = left + glue + right.replaceFirstChar { it.lowercaseChar() }
        return joined
    }
}