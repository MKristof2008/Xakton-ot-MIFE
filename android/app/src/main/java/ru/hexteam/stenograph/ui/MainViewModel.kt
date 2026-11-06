package ru.hexteam.stenograph.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hexteam.stenograph.BuildConfig
import ru.hexteam.stenograph.core.Pipeline
import ru.hexteam.stenograph.core.PipelineStage
import ru.hexteam.stenograph.core.TranscriptJson
import ru.hexteam.stenograph.core.TranscriptLine
import ru.hexteam.stenograph.engine.Stubs
import java.io.File

/**
 * Состояние экрана и запуск обработки.
 *
 * В debug-сборке используются заглушки движков: приложение полностью работает
 * без моделей и без сети — это позволяет проверять интерфейс и экспорт
 * параллельно с разработкой ASR, диаризации и LLM.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val audioUri: Uri? = null,
        val audioName: String = "",
        val stage: PipelineStage = PipelineStage.IDLE,
        val lines: List<TranscriptLine> = emptyList(),
        val json: String = "",
        val error: String? = null,
        val expectedSpeakers: Int? = null,
        val useLlm: Boolean = true,
        val usingStubs: Boolean = BuildConfig.DEBUG,
        val rtf: Double = 0.0,
    ) {
        val isBusy: Boolean
            get() = stage != PipelineStage.IDLE &&
                stage != PipelineStage.DONE &&
                stage != PipelineStage.ERROR

        val hasResult: Boolean get() = lines.isNotEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val pipeline = Pipeline(
        asr = Stubs.StubAsr(),
        diarization = Stubs.StubDiarization(),
        llm = Stubs.StubLlm(),
    )

    fun onAudioPicked(uri: Uri, displayName: String) {
        _state.update {
            it.copy(
                audioUri = uri,
                audioName = displayName,
                error = null,
                lines = emptyList(),
                json = "",
                stage = PipelineStage.IDLE,
            )
        }
    }

    fun setExpectedSpeakers(count: Int?) {
        _state.update { it.copy(expectedSpeakers = count) }
    }

    fun setUseLlm(enabled: Boolean) {
        _state.update { it.copy(useLlm = enabled) }
    }

    fun process() {
        val uri = _state.value.audioUri ?: run {
            _state.update { it.copy(error = "Сначала выберите аудиофайл") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(stage = PipelineStage.ASR, error = null) }
            try {
                val path = copyToCache(uri)
                val result = withContext(Dispatchers.Default) {
                    pipeline.run(
                        audioPath = path,
                        expectedSpeakers = _state.value.expectedSpeakers,
                        useLlm = _state.value.useLlm,
                        onStage = { stage -> _state.update { it.copy(stage = stage) } },
                    )
                }
                _state.update {
                    it.copy(
                        lines = result.lines,
                        json = result.json,
                        rtf = result.rtf,
                        stage = PipelineStage.DONE,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(stage = PipelineStage.ERROR, error = e.message ?: "Не удалось обработать запись")
                }
            }
        }
    }

    fun reset() {
        _state.update { UiState(usingStubs = BuildConfig.DEBUG) }
    }

    /** Копирует выбранный файл во внутреннее хранилище: движки работают с путём, не с Uri. */
    private suspend fun copyToCache(uri: Uri): String = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val target = File(context.cacheDir, "input_audio")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Не удалось открыть выбранный файл")
        target.absolutePath
    }

    /** Текст для показа в интерфейсе. */
    fun plainText(): String = TranscriptJson.toPlainText(_state.value.lines)
}
