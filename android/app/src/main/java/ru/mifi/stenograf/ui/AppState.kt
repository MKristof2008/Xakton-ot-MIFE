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
enum class Screen { IMPORT, RECORD, PROCESSING, RESULT, HISTORY, CANCELLED, ERROR }

class AppState(private val context: Context) : ViewModel() {

    /** Те же SharedPreferences, что и в меню: язык выбирается в настройках. */
    private val settings = ru.mifi.stenograf.settings.SettingsStore(context)

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

    private val historyStore = ru.mifi.stenograf.history.HistoryStore(context)
    var history by mutableStateOf<List<ru.mifi.stenograf.history.HistoryStore.Item>>(emptyList())
        private set
    var historyLoading by mutableStateOf(false)
        private set

    /** Открытая из истории запись (если смотрим не только что сделанную). */
    var viewingTitle by mutableStateOf("")
        private set

    init {
        loadHistory()
        // Модели в APK могут обновиться (например, base -> small). Досыпаем
        // недостающие при старте, иначе приложение молча работает на старых.
        if (!modelsReady) installModels()
        else viewModelScope.launch {
            withContext(Dispatchers.IO) { ModelStore.installFromAssets(context) }
            modelsReady = ModelStore.isReady(context)
            modelsMessage = ModelStore.describe(context)
        }
    }

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
        // Повторный старт при уже идущей обработке игнорируем: два пайплайна
        // одновременно съедают всю память и роняют процесс.
        if (job?.isActive == true) return
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
                        language = settings.language,
                        punctuation = settings.punctuation,
                        onProgress = { p -> progress = p },
                        isCancelled = { cancelled },
                    )
                }
                result = res
                // Пишем результат на диск: нужно для замеров WER/DER с ПК и для
                // отладки, когда экран не под рукой. Файл во внутреннем каталоге.
                runCatching {
                    val f = java.io.File(context.filesDir, "last_result.json")
                    f.writeText(ru.mifi.stenograf.output.HexJson.toJson(res.replicas))
                    java.io.File(context.filesDir, "last_meta.txt").writeText(
                        "duration_ms=${res.durationMs}\nprocessing_ms=${res.processingMs}\n" +
                            "rtf=${res.rtf}\nspeakers=${res.speakerCount}\nreplicas=${res.replicas.size}\n" +
                            res.stagesMs.entries.joinToString("\n") { "${it.key}=${it.value}ms" },
                    )
                }
                // Сохраняем в историю: аудио копируется, чтобы его можно было
                // прослушать позже — исходный content:// может стать недоступен.
                withContext(Dispatchers.IO) {
                    historyStore.save(
                        // Имя из content-URI часто выглядит как «video:1000033217».
                        // Для истории это бесполезно: подставляем дату и время.
                        title = friendlyTitle(displayName),
                        audioUri = uri,
                        durationMs = res.durationMs,
                        processingMs = res.processingMs,
                        speakerCount = res.speakerCount,
                        replicas = res.replicas,
                        keepAudio = settings.keepAudio,
                        limit = settings.historyLimit,
                    )
                }
                viewingTitle = ""
                screen = Screen.RESULT
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Отмена корутины — это НЕ ошибка. Раньше она попадала в общий
                // catch и пользователь видел «Ошибка: Обработка отменена».
                throw e
            } catch (e: Exception) {
                // Отмену по кнопке пайплайн сигналит через check(): это
                // IllegalStateException с нашим текстом. Отделяем её от сбоев.
                if (cancelled || e.message == "Обработка отменена") {
                    screen = Screen.CANCELLED
                } else {
                    error = e.message ?: e.toString()
                    screen = Screen.ERROR
                }
            }
        }
    }

    /** Старт обработки записанного в приложении файла. */
    fun startRecorded(file: java.io.File, speakers: Int?) {
        start(android.net.Uri.fromFile(file), file.name, speakers)
    }

    /**
     * Человеческое имя записи: если исходное имя — технический хэш или
     * «video:12345», используем дату и время. Обычные имена оставляем как есть.
     */
    private fun friendlyTitle(raw: String): String {
        val looksTechnical = raw.length > 20 || raw.startsWith("video:") ||
            raw.startsWith("audio:") || raw.all { it.isDigit() || it in ":-_" }
        if (!looksTechnical) return raw
        val now = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("ru"))
            .format(java.util.Date())
        return "Запись $now"
    }

    fun openHistory() {
        screen = Screen.HISTORY
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            historyLoading = true
            history = withContext(Dispatchers.IO) { historyStore.list() }
            historyLoading = false
        }
    }

    /** Открыть запись из истории: показываем сохранённый результат и аудио. */
    fun openFromHistory(item: ru.mifi.stenograf.history.HistoryStore.Item) {
        result = TranscriptResult(
            replicas = item.replicas,
            durationMs = item.durationMs,
            processingMs = item.processingMs,
            stagesMs = emptyMap(),
            speakerCount = item.speakerCount,
        )
        recordedPath = item.audioPath
        viewingTitle = item.title
        audioUri = item.audioPath?.let { android.net.Uri.fromFile(java.io.File(it)) }
        screen = Screen.RESULT
    }

    fun deleteHistoryItem(item: ru.mifi.stenograf.history.HistoryStore.Item) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { historyStore.delete(item.id) }
            loadHistory()
        }
    }

    /** Путь к аудиофайлу во внутреннем каталоге (для плеера). */
    var recordedPath by mutableStateOf<String?>(null)
        private set

    fun goRecord() {
        screen = Screen.RECORD
        error = null
    }

    /**
     * Отмена обработки. Показываем отдельный экран, а не молча выкидываем
     * на старт: пользователь должен понимать, что обработка остановлена,
     * и иметь явную кнопку вернуться. Раньше экран просто «прыгал» на старт.
     */
    fun cancel() {
        cancelled = true
        job?.cancel()
        job = null
        screen = Screen.CANCELLED
    }

    /** Вернуться на старт после отмены. */
    fun backFromCancel() {
        cancelled = false
        result = null
        error = null
        screen = Screen.IMPORT
    }

    /** Запустить обработку того же файла заново после отмены. */
    fun retryLast() {
        val uri = audioUri
        if (uri == null) {
            backFromCancel()
            return
        }
        cancelled = false
        start(uri, fileName, null)
    }

    fun backToImport() {
        screen = Screen.IMPORT
        result = null
        error = null
    }

    fun replicas(): List<Replica> = result?.replicas ?: emptyList()
}