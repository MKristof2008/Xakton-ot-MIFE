package ru.mifi.neurosteng

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * JNI-мост к whisper.cpp.
 *
 * Модель ggml-base.bin лежит в assets и при первом запуске распаковывается
 * во внутренний каталог: whisper.cpp требует обычный путь к файлу, а assets
 * внутри APK — это не файловая система.
 */
class WhisperBridge(private val context: Context) {

    private var handle: Long = 0L

    /** Загружает модель. Возвращает false, если модель не найдена или битая. */
    fun load(modelAsset: String = "models/ggml-base.bin"): Boolean {
        val modelFile = ensureModelUnpacked(modelAsset) ?: return false
        handle = nativeInit(modelFile.absolutePath)
        return handle != 0L
    }

    fun isLoaded(): Boolean = handle != 0L

    /**
     * Распознаёт WAV (16 кГц, моно, 16 бит) и возвращает сегменты в формате ТЗ.
     *
     * @return список сегментов; speaker_id пока всегда speaker_1 — диаризация
     *         на устройстве не реализована (см. android/README.md).
     */
    fun transcribe(wavPath: String, language: String = "ru"): List<HexJson.Segment> {
        if (handle == 0L) return emptyList()
        val raw = nativeTranscribe(handle, wavPath, language) ?: return emptyList()
        // Формат строки: start_ms\tend_ms\ttext
        return raw.mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val start = parts[0].toIntOrNull() ?: return@mapNotNull null
            val end = parts[1].toIntOrNull() ?: return@mapNotNull null
            val text = parts[2].trim()
            if (text.isEmpty() || end <= start) return@mapNotNull null
            HexJson.Segment("speaker_1", start, end, text)
        }
    }

    fun release() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

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
        init {
            System.loadLibrary("whisper_jni")
        }
    }
}