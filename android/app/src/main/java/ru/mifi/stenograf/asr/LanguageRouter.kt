package ru.mifi.stenograf.asr

/**
 * Чем и на каком языке распознавать запись.
 *
 * Правила простые и проверяемые:
 *  - русский звук → zipformer-ru: он точнее Whisper на русском и втрое быстрее;
 *  - любой другой язык → Whisper multilingual: других моделей для него в APK нет;
 *  - «Авто» — язык определяется один раз по началу записи и дальше не меняется.
 *
 * Почему язык определяется один раз, а не на каждой реплике: переключение
 * посреди записи даёт текст, где половина строк в другой раскладке, а WER
 * считается по всей записи целиком.
 */
object LanguageRouter {

    /** Сколько звука отдаём на определение языка. Язык записи не меняется, больше не нужно. */
    private const val DETECT_MS = 20_000

    /**
     * Ниже этого среднего уровня сигнала запись считаем тишиной.
     * 0.005 ≈ −46 dBFS: тише — фоновый шум, а не речь. Детектор языка
     * на таком фрагменте выдаёт случайный код.
     */
    private const val SILENCE_RMS = 0.005

    /** Языки, которые мы реально поддерживаем в интерфейсе и ожидаем услышать. */
    private val KNOWN_LANGUAGES = setOf(
        "ru", "en", "de", "fr", "es", "it", "pt", "zh", "ja", "ko", "uk", "kk", "tr", "pl",
    )

    private fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return kotlin.math.sqrt(sum / samples.size)
    }

    data class Plan(
        val engine: AsrEngine.EngineKind,
        /** ISO-код для Whisper; пустая строка — автоопределение самой моделью. */
        val language: String,
        /** Что показать в интерфейсе; null — язык явно выбран, определять нечего. */
        val detectedLabel: String? = null,
    )

    /**
     * @param choice выбор пользователя или [RecognitionLanguage.AUTO]
     * @param samples PCM 16 кГц моно (используется только в авторежиме)
     * @param available движки, для которых на устройстве есть веса
     * @param detect определение языка по фрагменту; возвращает ISO-код или null
     */
    fun plan(
        choice: RecognitionLanguage,
        samples: FloatArray,
        hasRussianModel: Boolean,
        hasWhisper: Boolean,
        detect: (FloatArray) -> String?,
    ): Plan {
        fun russian(label: String? = null) = when {
            hasRussianModel -> Plan(AsrEngine.EngineKind.RU_TRANSDUCER, "ru", label)
            hasWhisper -> Plan(AsrEngine.EngineKind.WHISPER_SMALL, "ru", label)
            else -> Plan(AsrEngine.EngineKind.RU_TRANSDUCER, "ru", label)
        }

        fun whisper(code: String, label: String? = null) = when {
            hasWhisper -> Plan(AsrEngine.EngineKind.WHISPER_SMALL, code, label)
            // Многоязычной модели нет — английский распознать нечем, честно
            // падаем на русскую и помечаем это в интерфейсе.
            else -> russian("нет модели EN")
        }

        return when (choice) {
            RecognitionLanguage.RU -> russian()
            RecognitionLanguage.EN -> whisper("en", "English")
            RecognitionLanguage.AUTO -> {
                // Определять нечем — ведём себя как до появления английского.
                if (!hasWhisper || samples.isEmpty()) return russian()
                // На тишине и в шуме детектор выдаёт случайный язык (наблюдали
                // «nn» — нюнорск на записи без речи). Проверяем, есть ли вообще
                // звук: если тихо, язык определять не по чему — остаёмся на русском.
                if (rms(samples) < SILENCE_RMS) return russian()
                val head = samples.copyOfRange(0, minOf(samples.size, DETECT_MS * 16))
                val code = detect(head)
                when {
                    code.isNullOrBlank() -> russian()
                    code.startsWith("ru") -> russian(displayName(code))
                    // Языки, для которых у нас нет ожиданий, чаще всего означают
                    // ошибку детектора на коротком/шумном фрагменте. Русский —
                    // безопасный дефолт: движение к нему не портит русский текст.
                    code !in KNOWN_LANGUAGES -> russian()
                    else -> whisper(code, displayName(code))
                }
            }
        }
    }

    /** Человеческое имя языка для интерфейса; неизвестный код показываем как есть. */
    fun displayName(code: String): String = when (code.lowercase().substringBefore('-')) {
        "ru" -> "Русский"
        "en" -> "English"
        "de" -> "Deutsch"
        "fr" -> "Français"
        "es" -> "Español"
        "zh" -> "中文"
        "uk" -> "Українська"
        else -> code.uppercase()
    }
}