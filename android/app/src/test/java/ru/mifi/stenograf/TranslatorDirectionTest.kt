package ru.mifi.stenograf

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mifi.stenograf.llm.Translator

class TranslatorDirectionTest {

    @Test
    fun `кириллица переводится на английский`() {
        assertEquals(
            Translator.Direction.RU_EN,
            Translator.directionOf("Добрый день, коллеги. Начнём с обсуждения релиза."),
        )
    }

    @Test
    fun `латиница переводится на русский`() {
        assertEquals(
            Translator.Direction.EN_RU,
            Translator.directionOf("We should ship the release on Friday evening."),
        )
    }

    @Test
    fun `смешанный текст с терминами остаётся русским`() {
        // Названия продуктов латиницей не должны переворачивать направление.
        assertEquals(
            Translator.Direction.RU_EN,
            Translator.directionOf("Мы выкатываем Android и iOS в пятницу вечером"),
        )
    }

    @Test
    fun `цифры и знаки без букв не роняют определение`() {
        assertEquals(Translator.Direction.EN_RU, Translator.directionOf("12:30 --- 42 %"))
        assertEquals(Translator.Direction.EN_RU, Translator.directionOf(""))
    }

    @Test
    fun `одно английское слово среди русских считается русским текстом`() {
        // «deadline» — типичная вставка в русскую речь.
        assertEquals(
            Translator.Direction.RU_EN,
            Translator.directionOf("Дедлайн по релизу в пятницу, успеваем"),
        )
    }
}