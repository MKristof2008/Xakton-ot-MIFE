package ru.mifi.stenograf.asr

/**
 * Язык распознавания, выбранный пользователем.
 *
 * Зачем нужен выбор, если модель умеет определять язык сама: угадывание
 * ошибается на коротких записях (одна фраза из трёх слов), а цена ошибки —
 * весь текст не в той раскладке. Авто — режим по умолчанию: язык берётся
 * по первому окну записи, дальше он уже не меняется.
 *
 * [code] — то, что уходит в Whisper. Пустая строка означает автоопределение
 * самой моделью внутри sherpa-onnx.
 */
enum class RecognitionLanguage(val key: String, val label: String, val code: String) {
    AUTO("auto", "Авто", ""),
    RU("ru", "Русский", "ru"),
    EN("en", "English", "en"),
    ;

    companion object {
        /** Настройка из SharedPreferences; неизвестное значение — авто. */
        fun fromKey(key: String?): RecognitionLanguage =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}