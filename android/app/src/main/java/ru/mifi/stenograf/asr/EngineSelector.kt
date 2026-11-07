package ru.mifi.stenograf.asr

/**
 * Какой движок ASR использовать.
 *
 * По умолчанию — русская модель (zipformer-ru): знает разговорную лексику и мат,
 * быстрее Whisper, не уходит в английские петли, обучена только на русском.
 * Whisper small остаётся запасным путём и эталоном для сравнения.
 */
object EngineSelector {

    @Volatile
    var preferred: AsrEngine.EngineKind = AsrEngine.EngineKind.RU_TRANSDUCER

    /**
     * Движок с учётом того, что реально есть на устройстве: если русской модели
     * нет (не распаковалась), но есть Whisper — работаем на Whisper, не падаем.
     */
    fun resolve(available: List<AsrEngine.EngineKind>): AsrEngine.EngineKind =
        if (preferred in available) preferred else available.firstOrNull() ?: preferred
}
