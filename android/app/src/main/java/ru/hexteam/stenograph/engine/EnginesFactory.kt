package ru.hexteam.stenograph.engine

import android.content.Context
import ru.hexteam.stenograph.core.Pipeline

/**
 * Единственное место, где собирается рабочий пайплайн.
 *
 * Зачем отдельный объект: UI не должен решать, какой движок подставить.
 * Раньше это решение было размазано по ViewModel, и диаризация с LLM
 * оставались заглушками даже в release-сборке — формально это нарушало
 * запрет ТЗ «подменять работу модели заранее подготовленной расшифровкой».
 *
 * Здесь подключаются только настоящие движки. Заглушки вынесены в тестовый
 * набор (app/src/test), поэтому в APK их физически нет.
 */
object EnginesFactory {

    /**
     * Рабочий пайплайн для проверки экспертами: все этапы настоящие.
     *
     * @throws IllegalStateException если модели не найдены — лучше явная
     *         ошибка на экране, чем тихая подмена результата заглушкой.
     */
    fun create(
        context: Context,
        expectedSpeakers: Int? = null,
        useLlm: Boolean = true,
        /** Имя модели ASR в assets; по умолчанию — выбранная замером. */
        asrModel: String = WhisperBridge.DEFAULT_MODEL_ASSET,
    ): Pipeline {
        ModelStore.installDiarization(context)
        LocalLlmEngine(context).install()

        return Pipeline(
            asr = WhisperAsrEngine(context, modelAsset = asrModel),
            diarization = SherpaDiarizationEngine(context),
            llm = LocalLlmEngine(context),
        )
    }

    /** Отчёт о готовности моделей — для экрана и логов. */
    fun readiness(context: Context): String = buildString {
        val asr = WhisperAsrEngine(context)
        val diar = SherpaDiarizationEngine(context)
        val llm = LocalLlmEngine(context)
        appendLine("ASR (whisper.cpp): ${if (asr.isReady()) "готов" else "нет модели"}")
        appendLine("Диаризация (sherpa-onnx): ${if (diar.isReady()) "готов" else "нет моделей"}")
        appendLine("LLM (локальная): ${if (llm.isReady()) "готов" else "нет модели"}")
        append(ModelStore.describe(context))
    }
}
