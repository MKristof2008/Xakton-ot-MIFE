package ru.mifi.stenograf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Палитра: спокойная, тёмная основа, высокий контраст текста. */
private val Blue = Color(0xFF2F6BFF)
private val BlueDark = Color(0xFF8FB0FF)
private val Ink = Color(0xFF11151C)
private val Paper = Color(0xFFF6F7F9)
private val Coral = Color(0xFFD9453A)

/** Цвета спикеров — различимы и в тёмной, и в светлой теме. */
val SpeakerColors = listOf(
    Color(0xFF2F6BFF), // синий
    Color(0xFF16914F), // зелёный
    Color(0xFFB45309), // охра
    Color(0xFF8B3FD1), // фиолетовый
    Color(0xFF0E7490), // бирюзовый
    Color(0xFFB02A6E), // бордо
)

fun speakerColor(speakerId: String): Color {
    val n = speakerId.substringAfterLast('_').toIntOrNull() ?: 1
    return SpeakerColors[(n - 1).coerceAtLeast(0) % SpeakerColors.size]
}

private val Light = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = Color(0xFF4B5563),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    error = Coral,
)

private val Dark = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF08122A),
    secondary = Color(0xFFB6BDC9),
    background = Color(0xFF0B0F15),
    onBackground = Color(0xFFE8EBF0),
    surface = Color(0xFF141A22),
    onSurface = Color(0xFFE8EBF0),
    error = Color(0xFFFF8A80),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun StenografTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}