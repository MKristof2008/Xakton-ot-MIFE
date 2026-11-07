package ru.mifi.stenograf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mifi.stenograf.llm.Grammar
import ru.mifi.stenograf.llm.TextQuality

/**
 * Текст, который видит пользователь. Главное правило: расшифровка содержит
 * ЕГО слова — ничего не выкидывается и не переписывается по смыслу.
 */
class TextQualityTest {

    @Test
    fun `повтор длинных слов схлопывается`() {
        assertEquals("ну давай давай уже", TextQuality.collapseRepeats("ну давай давай давай давай уже"))
    }

    @Test
    fun `короткие слова не считаются петлёй`() {
        // «Да. Да. Да.» — нормальная речь, её нельзя вычищать.
        assertEquals("Да. Да. Да.", TextQuality.collapseRepeats("Да. Да. Да."))
    }

    @Test
    fun `слоговая петля через дефис обрезается`() {
        assertEquals("подписание в мини-", TextQuality.collapseRepeats("подписание в мини-ни-ни-ни-ни"))
    }

    @Test
    fun `растянутые буквы сжимаются`() {
        assertEquals("очень хорошо", TextQuality.collapseRepeats("оооочень хорошо"))
    }

    @Test
    fun `обычные слова с двойными буквами не портятся`() {
        val out = TextQuality.collapseRepeats("сумма долга выросла")
        assertEquals("сумма долга выросла", out)
    }

    @Test
    fun `настоящая речь не считается вырожденной`() {
        assertFalse(TextQuality.isDegenerate("Добрый день, коллеги!"))
        assertFalse(TextQuality.isDegenerate("Мы успеваем выпустить версию в пятницу"))
        assertFalse(TextQuality.isDegenerate("Да! Да! Да!"))
        // Дефис в русском — норма, а не признак петли.
        assertFalse(TextQuality.isDegenerate("надо по-нормальному, а не по-старому"))
    }

    @Test
    fun `петля декодера считается вырожденной`() {
        assertTrue(TextQuality.isDegenerate("ни-ни-ни-ни-ни-ни"))
    }

    @Test
    fun `мат не вырезается и не заменяется`() {
        val raw = "блин, опять этот отчёт не готов"
        val out = Grammar.polish(raw)
        // Сравниваем без регистра: первое слово становится заглавным,
        // само слово при этом не меняется и не ценизурируется.
        assertTrue(out, out.lowercase().contains("блин"))
        assertTrue(out, out.contains("опять этот отчёт не готов"))
    }

    @Test
    fun `слова не теряются при расстановке пунктуации`() {
        val raw = "я думаю мы успеваем выпустить версию в пятницу вечером"
        val out = Grammar.polish(raw)
        for (w in raw.split(" ")) assertTrue("потеряно слово: $w", out.contains(w))
    }

    @Test
    fun `регистр и точка ставятся`() {
        assertEquals("Привет.", Grammar.polish("привет"))
    }

    @Test
    fun `мусор из одиночных латинских букв убирается`() {
        assertFalse(Grammar.polish("P P.").any { it in 'A'..'Z' || it in 'a'..'z' })
    }

    @Test
    fun `английский текст не чистится русскими правилами`() {
        // В английском «I» — местоимение, а не мусор декодера.
        val out = Grammar.polish("i think we should ship it friday", russian = false)
        assertEquals("I think we should ship it friday.", out)
    }

    @Test
    fun `английский не получает запятых перед союзами`() {
        val out = Grammar.polish("we shipped it but the tests failed", russian = false)
        assertEquals("We shipped it but the tests failed.", out)
    }
}
