package ru.hexteam.stenograph.engine

import ru.hexteam.stenograph.core.AsrSegment
import ru.hexteam.stenograph.core.SpeakerSegment

/**
 * Распознавание речи. Контракт `transcribe(path)`.
 *
 * Реализации:
 *  - [WhisperAsrEngine] — настоящая модель на устройстве;
 *  - тестовые фейки объявляются в самих тестах, в APK не попадают.
 */
interface AsrEngine {
    /** @param audioPath путь к аудиофайлу в локальном хранилище */
    suspend fun transcribe(audioPath: String): List<AsrSegment>

    /** Готов ли движок к работе (модель загружена). */
    fun isReady(): Boolean

    /**
     * Освобождает память модели. Вызывается пайплайном сразу после этапа:
     * whisper (~190 МБ), диаризация (~40 МБ) и LLM (~478 МБ) вместе
     * не помещаются, и Android убивает процесс на середине обработки.
     */
    fun release() {}
}

/**
 * Определение говорящих. Контракт `diarize(path)`.
 */
interface DiarizationEngine {
    /** @param expectedSpeakers ожидаемое число говорящих; null — определить автоматически */
    suspend fun diarize(
        audioPath: String,
        expectedSpeakers: Int? = null,
    ): List<SpeakerSegment>

    fun isReady(): Boolean

    /** Освобождает память после этапа — см. [AsrEngine.release]. */
    fun release() {}
}

/**
 * Локальная LLM: восстановление пунктуации и регистра, исправление ошибок ASR.
 * Контракт `clean_segments(segments)`.
 */
interface LlmEngine {
    /** Возвращает текст с восстановленной пунктуацией. Смысл не меняется. */
    suspend fun clean(text: String): String

    fun isReady(): Boolean

    /** Освобождает память после этапа — см. [AsrEngine.release]. */
    fun release() {}
}
