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

    /**
     * Размер модели ASR. `small` даёт заметно меньше WER на русском, но
     * занимает на 210 МБ больше и считает примерно в 2.5 раза дольше.
     * Переключение — одно место: см. PREFERRED_ASR.
     */
    const val PREFERRED_ASR = "small"

    // Whisper оставляем только как запасной/эталон: base удалён, он хуже small
    // по всем замерам и занимал 160 МБ в APK.
    val ASR_VARIANTS = listOf("small")

    /**
     * Русская модель Vosk (Apache-2.0), портированная в sherpa-onnx.
     * Обучена только на русском — знает разговорную лексику и мат, не уходит
     * в английские петли, как Whisper. Основной движок по умолчанию.
     */
    data class ModelFiles(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
    ) {
        fun files(dir: File): List<File> =
            listOf(encoder, decoder, joiner, tokens).map { File(dir, it) }
    }

    val RU_MODEL = ModelFiles(
        encoder = "ru-zipformer-encoder.int8.onnx",
        decoder = "ru-zipformer-decoder.onnx",
        joiner = "ru-zipformer-joiner.int8.onnx",
        tokens = "ru-zipformer-tokens.txt",
    )

    fun whisperFiles(dir: File, variant: String): List<File> =
        listOf(encoderName(variant), decoderName(variant), tokensName(variant)).map { File(dir, it) }

    fun encoderName(variant: String) = "whisper-$variant-encoder.int8.onnx"
    fun decoderName(variant: String) = "whisper-$variant-decoder.int8.onnx"
    fun tokensName(variant: String) = "whisper-$variant-tokens.txt"

    /** Первый доступный вариант из [ASR_VARIANTS], начиная с предпочтительного. */
    fun activeAsrVariant(context: Context): String {
        val dir = asrDir(context)
        val order = listOf(PREFERRED_ASR) + ASR_VARIANTS.filter { it != PREFERRED_ASR }
        return order.firstOrNull { v ->
            File(dir, encoderName(v)).exists() && File(dir, decoderName(v)).exists()
        } ?: PREFERRED_ASR
    }

    const val SEGMENTATION = "segmentation-3.0.onnx"
    const val EMBEDDING = "3dspeaker_eres2net.onnx"

    private fun root(context: Context) = File(context.filesDir, "models")

    fun asrDir(context: Context): File = File(root(context), ASR).apply { mkdirs() }
    fun diarDir(context: Context): File = File(root(context), DIAR).apply { mkdirs() }
    fun llmDir(context: Context): File = File(root(context), LLM).apply { mkdirs() }

    /** true, если основные модели ASR и диаризации на месте. */
    fun isReady(context: Context): Boolean {
        val asr = asrDir(context)
        // Достаточно любой рабочей ASR-модели: русской или Whisper.
        val asrOk = RU_MODEL.files(asr).all { it.exists() } ||
            whisperFiles(asr, PREFERRED_ASR).all { it.exists() }
        return asrOk &&
            File(diarDir(context), SEGMENTATION).exists() &&
            File(diarDir(context), EMBEDDING).exists()
    }

    /**
     * Копирует модели из assets в filesDir. Возвращает false, если в assets их нет
     * (тогда ожидаем, что их залили через adb push).
     */
    fun installFromAssets(context: Context, onProgress: (String) -> Unit = {}): Boolean {
        val assets = context.assets
        val files = mutableListOf<Pair<String, File>>()
        for (v in ASR_VARIANTS) {
            files += "models/$ASR/${encoderName(v)}" to File(asrDir(context), encoderName(v))
            files += "models/$ASR/${decoderName(v)}" to File(asrDir(context), decoderName(v))
            files += "models/$ASR/${tokensName(v)}" to File(asrDir(context), tokensName(v))
        }
        for (name in listOf(RU_MODEL.encoder, RU_MODEL.decoder, RU_MODEL.joiner, RU_MODEL.tokens)) {
            files += "models/$ASR/$name" to File(asrDir(context), name)
        }
        files += "models/$DIAR/$SEGMENTATION" to File(diarDir(context), SEGMENTATION)
        files += "models/$DIAR/$EMBEDDING" to File(diarDir(context), EMBEDDING)
        val map = files.toMap()
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