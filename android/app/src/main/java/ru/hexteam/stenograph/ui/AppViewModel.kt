package ru.hexteam.stenograph.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hexteam.stenograph.BuildConfig
import ru.hexteam.stenograph.core.Merge
import ru.hexteam.stenograph.core.Pipeline
import ru.hexteam.stenograph.core.PipelineStage
import ru.hexteam.stenograph.core.TranscriptLine
import ru.hexteam.stenograph.engine.Stubs
import ru.hexteam.stenograph.export.Export
import java.io.File

/**
 * Состояние и логика трёх экранов: импорт → обработка → результат.
 *
 * Отдельно от [MainViewModel]: тот обслуживает первый одноэкранный макет,
 * этот — навигационный интерфейс по docs/design/UI_SPEC.md.
 * Движки те же, что и в MainViewModel: в debug — заглушки, реальные
 * подключит ML-модуль через те же интерфейсы.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    /** Экраны навигации. */
    enum class Screen { IMPORT, PROCESSING, RESULT }

    data class UiState(
        val screen: Screen = Screen.IMPORT,
        val audioUri: Uri? = null,
        val audioName: String = "",
        val audioDurationMs: Long = 0L,
        val stage: PipelineStage = PipelineStage.IDLE,
        val lines: List<TranscriptLine> = emptyList(),
        val error: String? = null,
        val expectedSpeakers: Int? = null,
        val useLlm: Boolean = true,
        val usingStubs: Boolean = BuildConfig.DEBUG,
        val rtf: Double = 0.0,
        val processingMs: Long = 0L,
        /** Отображаемые имена говорящих. В экспорт идут исходные speaker_N. */
        val speakerLabels: Map<String, String> = emptyMap(),
        /** Фильтр по говорящему на экране результата; null — все. */
        val speakerFilter: String? = null,
        val searchQuery: String = "",
    ) {
        val isBusy: Boolean
            get() = stage != PipelineStage.IDLE &&
                stage != PipelineStage.DONE &&
                stage != PipelineStage.ERROR

        val hasResult: Boolean get() = lines.isNotEmpty()

        val speakers: List<String>
            get() = lines.map { it.speakerId }.distinct()

        /** Реплики с учётом фильтра говорящего и поиска. */
        val visibleLines: List<TranscriptLine>
            get() = lines
                .filter { speakerFilter == null || it.speakerId == speakerFilter }
                .filter {
                    searchQuery.isBlank() ||
                        it.text.contains(searchQuery.trim(), ignoreCase = true)
                }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val pipeline = Pipeline(
        asr = Stubs.StubAsr(),
        diarization = Stubs.StubDiarization(),
        llm = Stubs.StubLlm(),
    )

    private var processJob: Job? = null

    fun onAudioPicked(uri: Uri, displayName: String) {
        _state.update {
            it.copy(
                audioUri = uri,
                audioName = displayName,
                error = null,
                lines = emptyList(),
                stage = PipelineStage.IDLE,
                speakerLabels = emptyMap(),
                speakerFilter = null,
                searchQuery = "",
            )
        }
    }

    fun setExpectedSpeakers(count: Int?) {
        _state.update { it.copy(expectedSpeakers = count) }
    }

    fun setUseLlm(enabled: Boolean) {
        _state.update { it.copy(useLlm = enabled) }
    }

    /** Запуск обработки и переход на экран прогресса. */
    fun process() {
        val uri = _state.value.audioUri ?: run {
            _state.update { it.copy(error = "Сначала выберите аудиофайл") }
            return
        }

        processJob?.cancel()
        processJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    screen = Screen.PROCESSING,
                    stage = PipelineStage.ASR,
                    error = null,
                )
            }
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
                        rtf = result.rtf,
                        processingMs = result.processingMs,
                        audioDurationMs = result.durationMs,
                        stage = PipelineStage.DONE,
                        screen = Screen.RESULT,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _state.update {
                    it.copy(
                        stage = PipelineStage.ERROR,
                        screen = Screen.IMPORT,
                        error = "Не хватило памяти для обработки. Попробуйте запись короче.",
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        stage = PipelineStage.ERROR,
                        error = e.message ?: "Не удалось обработать запись",
                    )
                }
            }
        }
    }

    /** Отмена обработки и возврат на главный. */
    fun cancel() {
        processJob?.cancel()
        processJob = null
        _state.update {
            it.copy(screen = Screen.IMPORT, stage = PipelineStage.IDLE)
        }
    }

    /** Повторный запуск после ошибки. */
    fun retry() {
        _state.update { it.copy(error = null) }
        process()
    }

    fun renameSpeaker(speakerId: String, label: String) {
        _state.update {
            val labels = it.speakerLabels.toMutableMap()
            if (label.isBlank()) labels.remove(speakerId) else labels[speakerId] = label.trim()
            it.copy(speakerLabels = labels)
        }
    }

    fun setSpeakerFilter(speakerId: String?) {
        _state.update { it.copy(speakerFilter = speakerId) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    /** Сброс на главный экран для новой записи. */
    fun reset() {
        processJob?.cancel()
        _state.update { UiState(usingStubs = BuildConfig.DEBUG) }
    }

    /** JSON строго по схеме ТЗ. Переименования в экспорт не попадают. */
    fun exportJson(): String = Export.toJson(_state.value.lines)

    /** Читаемый текст с таймкодами. */
    fun exportText(): String = Export.toTxt(_state.value.lines, _state.value.speakerLabels)

    /** Субтитры SRT. */
    fun exportSrt(): String = Export.toSrt(_state.value.lines, _state.value.speakerLabels)

    /** Отображаемое имя говорящего: переименованное или «Спикер N». */
    fun labelFor(speakerId: String): String =
        _state.value.speakerLabels[speakerId] ?: Merge.humanize(speakerId)

    /** Копирует выбранный файл во внутреннее хранилище: движки работают с путём, не с Uri. */
    private suspend fun copyToCache(uri: Uri): String = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val target = File(context.cacheDir, "input_audio")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Не удалось открыть выбранный файл")
        target.absolutePath
    }
}
