package ru.hexteam.stenograph.engine

import ru.hexteam.stenograph.core.AsrSegment
import ru.hexteam.stenograph.core.SpeakerSegment

/**
 * Заглушки движков.
 *
 * Зачем нужны: по ТЗ «пока модуль не готов — работаем на заглушках».
 * Позволяют собрать и проверить весь путь «аудио → расшифровка → экспорт»
 * до появления настоящих моделей, а также на устройстве без достаточной памяти.
 *
 * Заглушки НЕ подменяют работу модели на защите: они включаются только
 * в debug-сборке и явно помечаются в интерфейсе.
 */
object Stubs {

    /** Правдоподобная расшифровка короткого диалога — для демонстрации интерфейса. */
    private val DEMO_DIALOG = listOf(
        Triple(0L, 2600L, "добрый день коллеги начнём совещание"),
        Triple(2600L, 6100L, "давайте обсудим сроки по проекту стенографиста"),
        Triple(6100L, 9800L, "я предлагаю сдать базовый уровень к пятнице"),
        Triple(9800L, 13400L, "согласен тогда я подготовлю тестовые записи"),
        Triple(13400L, 16800L, "хорошо зафиксируем это как решение"),
    )

    private val DEMO_SPEAKERS = listOf(
        Triple(0L, 6100L, "speaker_1"),
        Triple(6100L, 13400L, "speaker_2"),
        Triple(13400L, 16800L, "speaker_1"),
    )

    class StubAsr : AsrEngine {
        override suspend fun transcribe(audioPath: String): List<AsrSegment> =
            DEMO_DIALOG.map { (start, end, text) ->
                AsrSegment(startMs = start, endMs = end, text = text)
            }

        override fun isReady(): Boolean = true
    }

    class StubDiarization : DiarizationEngine {
        override suspend fun diarize(
            audioPath: String,
            expectedSpeakers: Int?,
        ): List<SpeakerSegment> =
            DEMO_SPEAKERS.map { (start, end, speaker) ->
                SpeakerSegment(startMs = start, endMs = end, speaker = speaker)
            }

        override fun isReady(): Boolean = true
    }

    class StubLlm : LlmEngine {
        /** Простейшая «пунктуация»: заглавная буква в начале и точка в конце. */
        override suspend fun clean(text: String): String =
            text.lines().joinToString("\n") { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) line
                else trimmed.replaceFirstChar { it.uppercase() } + "."
            }

        override fun isReady(): Boolean = true
    }
}
