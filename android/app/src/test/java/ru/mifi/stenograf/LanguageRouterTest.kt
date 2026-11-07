package ru.mifi.stenograf

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mifi.stenograf.asr.AsrEngine
import ru.mifi.stenograf.asr.LanguageRouter
import ru.mifi.stenograf.asr.RecognitionLanguage

/**
 * Выбор движка и языка. Модели не нужны: проверяется только логика решения,
 * а определение языка подменяется лямбдой.
 */
class LanguageRouterTest {

    private val both = listOf(AsrEngine.EngineKind.RU_TRANSDUCER, AsrEngine.EngineKind.WHISPER_SMALL)
    private val ruOnly = listOf(AsrEngine.EngineKind.RU_TRANSDUCER)

    /** Полсекунды тишины: содержимое для логики не важно, важна длина. */
    private val samples = FloatArray(16_000 / 2)

    private fun plan(
        choice: RecognitionLanguage,
        available: List<AsrEngine.EngineKind> = both,
        detected: String? = null,
    ) = LanguageRouter.plan(
        choice = choice,
        samples = samples,
        hasRussianModel = AsrEngine.EngineKind.RU_TRANSDUCER in available,
        hasWhisper = AsrEngine.EngineKind.WHISPER_SMALL in available,
        detect = { detected },
    )

    @Test
    fun `русский идёт на zipformer`() {
        val p = plan(RecognitionLanguage.RU)
        assertEquals(AsrEngine.EngineKind.RU_TRANSDUCER, p.engine)
        assertEquals("ru", p.language)
    }

    @Test
    fun `английский идёт на whisper`() {
        val p = plan(RecognitionLanguage.EN)
        assertEquals(AsrEngine.EngineKind.WHISPER_SMALL, p.engine)
        assertEquals("en", p.language)
    }

    @Test
    fun `авто с русским определением оставляет zipformer`() {
        val p = plan(RecognitionLanguage.AUTO, detected = "ru")
        assertEquals(AsrEngine.EngineKind.RU_TRANSDUCER, p.engine)
        assertEquals("Русский", p.detectedLabel)
    }

    @Test
    fun `авто с английским определением переключает на whisper`() {
        val p = plan(RecognitionLanguage.AUTO, detected = "en")
        assertEquals(AsrEngine.EngineKind.WHISPER_SMALL, p.engine)
        assertEquals("en", p.language)
        assertEquals("English", p.detectedLabel)
    }

    @Test
    fun `авто без определения остаётся на русском`() {
        val p = plan(RecognitionLanguage.AUTO, detected = null)
        assertEquals(AsrEngine.EngineKind.RU_TRANSDUCER, p.engine)
    }

    @Test
    fun `без модели whisper английский не уходит в zipformer молча`() {
        // Модели нет: движок остаётся русским, но пользователь видит, что EN не поддержан.
        val p = plan(RecognitionLanguage.EN, available = ruOnly)
        assertEquals(AsrEngine.EngineKind.RU_TRANSDUCER, p.engine)
        assertEquals("нет модели EN", p.detectedLabel)
    }

    @Test
    fun `авто без модели whisper не вызывает детектор`() {
        var called = false
        val p = LanguageRouter.plan(
            choice = RecognitionLanguage.AUTO,
            samples = samples,
            hasRussianModel = true,
            hasWhisper = false,
            detect = { called = true; "en" },
        )
        assertEquals(false, called)
        assertEquals(AsrEngine.EngineKind.RU_TRANSDUCER, p.engine)
    }
}