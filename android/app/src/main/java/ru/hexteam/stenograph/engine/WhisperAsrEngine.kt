package ru.hexteam.stenograph.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.hexteam.stenograph.core.AsrSegment
import java.io.File

/**
 * Настоящая ASR на устройстве: whisper.cpp через JNI.
 *
 * Входной аудиофайл любого формата сначала конвертируется в WAV 16 кГц / моно
 * (AudioConverter, MediaCodec — системный, без сторонних библиотек),
 * затем распознаётся локально. Сети нет по ТЗ.
 */
class WhisperAsrEngine(
    context: Context,
    private val language: String = "ru",
) : AsrEngine {

    private val appContext = context.applicationContext
    private val bridge = WhisperBridge(appContext)

    override fun isReady(): Boolean = runCatching { bridge.modelAvailable() }.getOrDefault(false)

    override suspend fun transcribe(audioPath: String): List<AsrSegment> =
        withContext(Dispatchers.Default) {
            if (!bridge.load()) {
                throw IllegalStateException(
                    "Модель Whisper не найдена. Положите ${WhisperBridge.DEFAULT_MODEL_ASSET} " +
                        "в assets или файл ggml-small.bin во внутреннее хранилище."
                )
            }
            // keep = true: тот же WAV понадобится диаризации, второй раз
            // декодировать не нужно (см. AudioPrep).
            val wav = AudioPrep.prepare(appContext, File(audioPath), keep = true)
                ?: throw IllegalStateException("Не удалось декодировать аудио в WAV 16 кГц")
            bridge.transcribe(wav.absolutePath, language).map { (start, end, text) ->
                AsrSegment(startMs = start, endMs = end, text = text)
            }
        }
}
