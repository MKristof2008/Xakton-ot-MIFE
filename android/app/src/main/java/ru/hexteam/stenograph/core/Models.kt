package ru.hexteam.stenograph.core

/**
 * Сегмент распознавания речи. Контракт `transcribe(path)`.
 * Таймкоды — в миллисекундах, как требует ТЗ.
 */
data class AsrSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Интервал речи одного говорящего. Контракт `diarize(path)`.
 * `speaker` — сырой идентификатор вида `speaker_1` или `SPEAKER_00`.
 */
data class SpeakerSegment(
    val startMs: Long,
    val endMs: Long,
    val speaker: String,
)

/** Одна реплика после сшивки: кто, когда и что сказал. */
data class TranscriptLine(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** Шаг обработки — для отображения прогресса. */
enum class PipelineStage(val title: String) {
    IDLE("Ожидание"),
    ASR("Распознавание речи"),
    DIARIZATION("Определение говорящих"),
    MERGE("Сшивка реплик"),
    LLM("Восстановление пунктуации"),
    DONE("Готово"),
    ERROR("Ошибка"),
}
