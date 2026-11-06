package ru.mifi.stenograf.model

import android.content.Context
import java.io.File

/**
 * Модели лежат в assets (идут в APK) и при первом запуске копируются в filesDir.
 * Так они гарантированно на устройстве к моменту проверки — без сети.
 *
 * Если модели не упакованы в APK (dev-сборка), ModelStore ищет их в
 * filesDir/models — туда их можно положить через `adb push`.
 */
object ModelStore {

    const val ASR = "asr"
    const val DIAR = "diar"
    const val LLM = "llm"

    const val WHISPER_ENCODER = "whisper-base-encoder.int8.onnx"
    const val WHISPER_DECODER = "whisper-base-decoder.int8.onnx"
    const val WHISPER_TOKENS = "whisper-base-tokens.txt"

    const val SEGMENTATION = "segmentation-3.0.onnx"
    const val EMBEDDING = "3dspeaker_eres2net.onnx"

    private fun root(context: Context) = File(context.filesDir, "models")

    fun asrDir(context: Context): File = File(root(context), ASR).apply { mkdirs() }
    fun diarDir(context: Context): File = File(root(context), DIAR).apply { mkdirs() }
    fun llmDir(context: Context): File = File(root(context), LLM).apply { mkdirs() }

    /** true, если основные модели ASR и диаризации на месте. */
    fun isReady(context: Context): Boolean =
        File(asrDir(context), WHISPER_ENCODER).exists() &&
            File(asrDir(context), WHISPER_DECODER).exists() &&
            File(asrDir(context), WHISPER_TOKENS).exists() &&
            File(diarDir(context), SEGMENTATION).exists() &&
            File(diarDir(context), EMBEDDING).exists()

    /**
     * Копирует модели из assets в filesDir. Возвращает false, если в assets их нет
     * (тогда ожидаем, что их залили через adb push).
     */
    fun installFromAssets(context: Context, onProgress: (String) -> Unit = {}): Boolean {
        val assets = context.assets
        val map = mapOf(
            "models/$ASR/$WHISPER_ENCODER" to File(asrDir(context), WHISPER_ENCODER),
            "models/$ASR/$WHISPER_DECODER" to File(asrDir(context), WHISPER_DECODER),
            "models/$ASR/$WHISPER_TOKENS" to File(asrDir(context), WHISPER_TOKENS),
            "models/$DIAR/$SEGMENTATION" to File(diarDir(context), SEGMENTATION),
            "models/$DIAR/$EMBEDDING" to File(diarDir(context), EMBEDDING),
        )
        var copiedAny = false
        for ((assetPath, target) in map) {
            if (target.exists() && target.length() > 0) continue
            val available = runCatching { assets.open(assetPath).use { true } }.getOrDefault(false)
            if (!available) continue
            onProgress(target.name)
            assets.open(assetPath).use { input ->
                target.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
            }
            copiedAny = true
        }
        return copiedAny || isReady(context)
    }

    fun describe(context: Context): String {
        val dirs = listOf(asrDir(context), diarDir(context), llmDir(context))
        return dirs.joinToString("\n") { d ->
            val files = d.listFiles()?.joinToString { "${it.name} (${it.length() / 1024 / 1024} МБ)" } ?: "—"
            "${d.name}: $files"
        }
    }
}