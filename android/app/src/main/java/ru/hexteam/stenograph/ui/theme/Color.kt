package ru.hexteam.stenograph.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Палитра по docs/design/UI_SPEC.md.
 * Тёмная тема — основная: расшифровку читают подолгу.
 */

// Подложка и поверхности
val BgDark = Color(0xFF0B0E14)
val SurfaceDark = Color(0xFF141A24)
val SurfaceHighDark = Color(0xFF1C2430)
val BorderDark = Color(0xFF26303F)

// Акценты
val Accent = Color(0xFF6C8EFF)
val Accent2 = Color(0xFFA78BFA)
val Success = Color(0xFF34D399)

// Текст
val TextPrimary = Color(0xFFE8EDF5)
val TextSecondary = Color(0xFF8B98AC)
val TextMuted = Color(0xFF5A6779)

// Светлая тема (запасной вариант, те же акценты)
val BgLight = Color(0xFFF5F7FB)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceHighLight = Color(0xFFEEF1F7)
val BorderLight = Color(0xFFD7DEE9)
val TextPrimaryLight = Color(0xFF141A24)
val TextSecondaryLight = Color(0xFF5A6779)

/**
 * Цвета говорящих из спецификации. Индекс = номер спикера минус один,
 * дальше по кругу. Цвет — это говорящий, а не украшение.
 */
val SpeakerColors = listOf(
    Color(0xFF6C8EFF),
    Color(0xFF34D399),
    Color(0xFFF59E0B),
    Color(0xFFF472B6),
    Color(0xFFA78BFA),
    Color(0xFF22D3EE),
)

/** Цвет для идентификатора вида `speaker_N`; неизвестный — приглушённый. */
fun speakerColor(speakerId: String): Color {
    val digits = speakerId.takeLastWhile { it.isDigit() }
    val index = digits.toIntOrNull()?.minus(1) ?: return TextMuted
    return SpeakerColors[index.mod(SpeakerColors.size)]
}
