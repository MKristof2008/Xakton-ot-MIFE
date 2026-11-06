package ru.hexteam.stenograph.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * JNI-мост к whisper.cpp (нативная ASR на устройстве, без сети).
 *
 * Модель GGML лежит в assets и при первом запуске распаковывается в filesDir:
 * whisper.cpp требует обычный путь к файлу, а assets внутри APK — не ФС.
 * Если модели нет ни в assets, ни в filesDir — isReady() == false и пайплайн
 * продолжает работать на заглушке (debug) либо честно сообщает об ошибке.
 */
class WhisperBridge(private val context: Context) {

    private var handle: Long = 0L

    /** Загружает модель. Возвращает false, если модель не найдена или битая. */
    fun load(modelAsset: String = DEFAULT_MODEL_ASSET): Boolean {
        if (handle != 0L) return true
        val modelFile = ensureModelUnpacked(modelAsset) ?: return false
        handle = nativeInit(modelFile.absolutePath)
        return handle != 0L
    }

    fun isLoaded(): Boolean = handle != 0L

    /**
     * Распознаёт WAV (16 кГц, моно, 16 бит) и возвращает сырые сегменты ASR.
     * Диаризация — отдельный движок, здесь спикер не определяется.
     */
    fun transcribe(wavPath: String, language: String = "ru"): List<Triple<Long, Long, String>> {
        if (handle == 0L) return emptyList()
        val raw = nativeTranscribe(handle, wavPath, language) ?: return emptyList()
        // Формат строки: start_ms\tend_ms\ttext
        return raw.mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val start = parts[0].toLongOrNull() ?: return@mapNotNull null
            val end = parts[1].toLongOrNull() ?: return@mapNotNull null
            val text = parts[2].trim()
            if (text.isEmpty() || end <= start) return@mapNotNull null
            Triple(start, end, text)
        }
    }

    fun release() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    /** Модель уже на устройстве (assets распакованы или положена вручную). */
    fun modelAvailable(): Boolean =
        File(context.filesDir, DEFAULT_MODEL_ASSET.substringAfterLast('/')).let {
            it.exists() && it.length() > 0
        } || runCatching {
            context.assets.open(DEFAULT_MODEL_ASSET).close(); true
        }.getOrDefault(false)

    /** Достаёт модель из assets в filesDir один раз. */
    private fun ensureModelUnpacked(assetPath: String): File? {
        val target = File(context.filesDir, assetPath.substringAfterLast('/'))
        if (target.exists() && target.length() > 0) return target
        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target
        } catch (e: Exception) {
            null
        }
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(handle: Long, wavPath: String, language: String): Array<String>?
    private external fun nativeRelease(handle: Long)

    companion object {
        const val DEFAULT_MODEL_ASSET = "models/ggml-tiny.bin"

        init {
            System.loadLibrary("whisper_jni")
        }
    }
}
