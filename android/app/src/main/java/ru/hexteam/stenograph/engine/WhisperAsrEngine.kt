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
    /** Модель в assets. Выбор по замеру WER, не по умолчанию библиотеки. */
    private val modelAsset: String = WhisperBridge.DEFAULT_MODEL_ASSET,
) : AsrEngine {

    private val appContext = context.applicationContext
    private val bridge = WhisperBridge(appContext)

    override fun isReady(): Boolean =
        runCatching { bridge.modelAvailable(modelAsset) }.getOrDefault(false)

    /** Выгружает модель из памяти: следующему этапу нужно её место. */
    override fun release() = bridge.release()

    override suspend fun transcribe(audioPath: String): List<AsrSegment> =
        withContext(Dispatchers.Default) {
            if (!bridge.load(modelAsset)) {
                throw IllegalStateException(
                    "Модель Whisper не найдена. Положите $modelAsset " +
                        "в assets или файл ggml-base.bin во внутреннее хранилище."
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
