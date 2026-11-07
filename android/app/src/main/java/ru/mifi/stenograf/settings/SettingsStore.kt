package ru.mifi.stenograf.settings

import android.content.Context
import android.content.SharedPreferences
import ru.mifi.stenograf.asr.RecognitionLanguage

/**
 * Пользовательские настройки. Хранятся в SharedPreferences — переживают
 * перезапуск, но не уезжают с устройства (сети у приложения нет вовсе).
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("stenograf", Context.MODE_PRIVATE)

    companion object {
        /** Сколько реплик показывать сразу без подгрузки — влияет на плавность. */
        const val KEY_HAPTICS = "haptics"
        const val KEY_KEEP_AUDIO = "keep_audio"
        const val KEY_PUNCTUATION = "punctuation"
        const val KEY_SHOW_TIMECODES = "show_timecodes"
        const val KEY_HISTORY_LIMIT = "history_limit"
        const val KEY_LANGUAGE = "recognition_language"

        val HISTORY_LIMITS = listOf(10, 30, 50)
    }

    /** Тактильная отдача. */
    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(v) = prefs.edit().putBoolean(KEY_HAPTICS, v).apply()

    /**
     * Хранить копию аудио в истории. Если выключить — расшифровки останутся,
     * но прослушать их будет нельзя. Экономит место: минута записи ≈ 1.9 МБ.
     */
    var keepAudio: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AUDIO, true)
        set(v) = prefs.edit().putBoolean(KEY_KEEP_AUDIO, v).apply()

    /** Восстанавливать пунктуацию и регистр правилами. */
    var punctuation: Boolean
        get() = prefs.getBoolean(KEY_PUNCTUATION, true)
        set(v) = prefs.edit().putBoolean(KEY_PUNCTUATION, v).apply()

    /** Показывать таймкоды в результате по умолчанию. */
    var showTimecodes: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TIMECODES, true)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_TIMECODES, v).apply()

    /** Сколько записей держать в истории. */
    var historyLimit: Int
        get() = prefs.getInt(KEY_HISTORY_LIMIT, 30)
        set(v) = prefs.edit().putInt(KEY_HISTORY_LIMIT, v).apply()

    /**
     * Язык распознавания. По умолчанию авто: язык определяется по записи,
     * русский идёт на zipformer-ru, остальные — на многоязычный Whisper.
     */
    var language: RecognitionLanguage
        get() = RecognitionLanguage.fromKey(prefs.getString(KEY_LANGUAGE, null))
        set(v) = prefs.edit().putString(KEY_LANGUAGE, v.key).apply()
}