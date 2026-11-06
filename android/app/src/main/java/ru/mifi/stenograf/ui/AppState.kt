package ru.mifi.stenograf.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mifi.stenograf.model.ModelStore
import ru.mifi.stenograf.output.Replica
import ru.mifi.stenograf.pipeline.PipelineProgress
import ru.mifi.stenograf.pipeline.TranscriptPipeline
import ru.mifi.stenograf.pipeline.TranscriptResult

/** Экран приложения. */
enum class Screen { IMPORT, PROCESSING, RESULT, ERROR }

class AppState(private val context: Context) : ViewModel() {

    var screen by mutableStateOf(Screen.IMPORT)
        private set
    var progress by mutableStateOf(PipelineProgress(ru.mifi.stenograf.pipeline.Stage.DECODE, 0f, 0f))
        private set
    var result by mutableStateOf<TranscriptResult?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var audioUri by mutableStateOf<Uri?>(null)
        private set
    var fileName by mutableStateOf("")
        private set
    var modelsReady by mutableStateOf(ModelStore.isReady(context))
        private set
    var modelsMessage by mutableStateOf(ModelStore.describe(context))
        private set

    private var job: Job? = null
    @Volatile private var cancelled = false

    fun installModels() {
        viewModelScope.launch {
            modelsMessage = "Распаковываем модели…"
            val ok = withContext(Dispatchers.IO) {
                ModelStore.installFromAssets(context) { name ->
                    modelsMessage = "Распаковываем: $name"
                }
            }
            modelsReady = ok && ModelStore.isReady(context)
            modelsMessage = if (modelsReady) {
                "Модели готовы (офлайн).\n" + ModelStore.describe(context)
            } else {
                "Модели не найдены в APK. Положите их в файлы приложения через adb:\n" +
                    "adb push <файл> /sdcard/Android/data/ru.mifi.stenograf/files/models/…\n\n" +
                    ModelStore.describe(context)
            }
        }
    }

    fun start(uri: Uri, displayName: String, speakers: Int?) {
        audioUri = uri
        fileName = displayName
        screen = Screen.PROCESSING
        error = null
        cancelled = false
        progress = PipelineProgress(ru.mifi.stenograf.pipeline.Stage.DECODE, 0f, 0f, "Старт")

        job = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    TranscriptPipeline(context).run(
                        uri = uri,
                        numSpeakers = speakers,
                        onProgress = { p -> progress = p },
                        isCancelled = { cancelled },
                    )
                }
                result = res
                screen = Screen.RESULT
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                screen = Screen.ERROR
            }
        }
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
        screen = Screen.IMPORT
    }

    fun backToImport() {
        screen = Screen.IMPORT
        result = null
        error = null
    }

    fun replicas(): List<Replica> = result?.replicas ?: emptyList()
}