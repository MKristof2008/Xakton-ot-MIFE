package ru.hexteam.stenograph.engine

import android.content.Context
import java.io.File

/**
 * Модели на устройстве. Требование ТЗ: к моменту проверки модели уже лежат
 * на телефоне и приложение работает без сети с первого запуска.
 *
 * Схема: модели упакованы в APK (assets/models/...) и при первом запуске
 * копируются в filesDir. Дальше читаются только из filesDir — так они не
 * зависят от жизненного цикла assets и читаются нативным кодом по обычному пути.
 *
 * Если модели в APK не упакованы (dev-сборка без больших файлов), они
 * ожидаются в filesDir/models — туда их кладут через `adb push`.
 */
object ModelStore {

    const val ASR_DIR = "asr"
    const val DIAR_DIR = "diar"
    const val LLM_DIR = "llm"

    /** whisper.cpp: одна модель в GGML-формате. */
    const val WHISPER_GGML = "ggml-tiny.bin"

    /** Диаризация: сегментация pyannote-3.0 + эмбеддинги 3D-Speaker. */
    const val SEGMENTATION = "segmentation-3.0.onnx"
    const val EMBEDDING = "3dspeaker_eres2net.onnx"

    private fun root(context: Context): File = File(context.filesDir, "models")

    fun asrDir(context: Context): File = File(root(context), ASR_DIR).apply { mkdirs() }
    fun diarDir(context: Context): File = File(root(context), DIAR_DIR).apply { mkdirs() }
    fun llmDir(context: Context): File = File(root(context), LLM_DIR).apply { mkdirs() }

    fun segmentation(context: Context): File = File(diarDir(context), SEGMENTATION)
    fun embedding(context: Context): File = File(diarDir(context), EMBEDDING)

    /** Готова ли диаризация: оба файла на месте и непустые. */
    fun diarizationReady(context: Context): Boolean =
        segmentation(context).let { it.isFile && it.length() > 0 } &&
            embedding(context).let { it.isFile && it.length() > 0 }

    fun llmModel(context: Context, name: String): File = File(llmDir(context), name)

    /** Готова ли локальная LLM. */
    fun llmReady(context: Context, name: String): Boolean =
        llmModel(context, name).let { it.isFile && it.length() > 0 }

    /**
     * Копирует модели из assets в filesDir. Идемпотентна: уже скопированные
     * файлы не перезаписываются.
     *
     * @return true, если после вызова модели диаризации на месте.
     */
    fun installDiarization(context: Context, onProgress: (String) -> Unit = {}): Boolean {
        copyFromAssets(
            context,
            listOf(
                "models/$DIAR_DIR/$SEGMENTATION" to segmentation(context),
                "models/$DIAR_DIR/$EMBEDDING" to embedding(context),
            ),
            onProgress,
        )
        return diarizationReady(context)
    }

    /**
     * Копирует модель локальной LLM из assets. Файл большой (сотни МБ),
     * поэтому копирование делается один раз и только если модели ещё нет.
     */
    fun installLlm(context: Context, name: String, onProgress: (String) -> Unit = {}): Boolean {
        copyFromAssets(context, listOf("models/$LLM_DIR/$name" to llmModel(context, name)), onProgress)
        return llmReady(context, name)
    }

    /**
     * Копирование assets → filesDir.
     *
     * Пишем через временный `.part`: если процесс убьют посреди копирования
     * (а на 470 МБ это реально), обрезанный файл не будет принят за готовую
     * модель — при следующем запуске копирование повторится.
     */
    private fun copyFromAssets(
        context: Context,
        targets: List<Pair<String, File>>,
        onProgress: (String) -> Unit,
    ) {
        for ((assetPath, target) in targets) {
            if (target.isFile && target.length() > 0) continue
            val available = runCatching { context.assets.open(assetPath).close() }.isSuccess
            if (!available) continue
            onProgress(target.name)
            val tmp = File(target.parentFile, "${target.name}.part")
            runCatching {
                context.assets.open(assetPath).use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }.onFailure { tmp.delete() }
        }
    }

    /** Что реально лежит на устройстве — для диагностики в логах и на экране. */
    fun describe(context: Context): String =
        listOf(asrDir(context), diarDir(context), llmDir(context)).joinToString("\n") { dir ->
            val files = dir.listFiles()
                ?.joinToString { "${it.name} (${it.length() / 1024 / 1024} МБ)" }
                ?: "—"
            "${dir.name}: $files"
        }
}
