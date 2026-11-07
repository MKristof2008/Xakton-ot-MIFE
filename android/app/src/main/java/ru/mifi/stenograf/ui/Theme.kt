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

/**
 * Палитра. Основная тема — тёмная: приложение про аудио и текст, тёмный фон
 * меньше утомляет на длинных расшифровках и лучше смотрится на демо.
 */
private val Blue = Color(0xFF4C7DFF)
private val Cyan = Color(0xFF38E1C4)
private val Violet = Color(0xFF8B5CF6)
private val Coral = Color(0xFFFF6B6B)

private val Ink = Color(0xFF0A0D14)
private val InkSurface = Color(0xFF141A26)
private val InkSurfaceHigh = Color(0xFF1C2433)
private val OnInk = Color(0xFFEDF1F8)
private val Muted = Color(0xFF8B97AC)

private val LightPaper = Color(0xFFF5F7FB)
private val LightSurface = Color(0xFFFFFFFF)

/** Цвета говорящих: различимы между собой, в том числе при дальтонизме. */
val SpeakerColors = listOf(
    Color(0xFF4C7DFF), // синий
    Color(0xFF2FBF71), // зелёный
    Color(0xFFE8A317), // янтарный
    Color(0xFFB06AE8), // фиолетовый
    Color(0xFF00A6B8), // бирюзовый
    Color(0xFFE85D75), // коралловый
)

fun speakerColor(speakerId: String): Color {
    val n = speakerId.substringAfterLast('_').toIntOrNull() ?: 1
    return SpeakerColors[(n - 1).coerceAtLeast(0) % SpeakerColors.size]
}

/** Фирменный градиент: им залиты акцентные кнопки и заголовки-плашки. */
val BrandGradient = listOf(Color(0xFF4C7DFF), Color(0xFF7B4CFF))
val AccentGradient = listOf(Color(0xFF38E1C4), Color(0xFF4C7DFF))

private val Dark = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E2C4D),
    onPrimaryContainer = Color(0xFFD6E2FF),
    secondary = Cyan,
    onSecondary = Ink,
    tertiary = Violet,
    background = Ink,
    onBackground = OnInk,
    surface = InkSurface,
    onSurface = OnInk,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = Muted,
    outline = Color(0xFF2A3446),
    error = Coral,
)

private val Light = lightColorScheme(
    primary = Color(0xFF2F5BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE6FF),
    onPrimaryContainer = Color(0xFF0B1B3D),
    secondary = Color(0xFF0E8F7C),
    onSecondary = Color.White,
    tertiary = Color(0xFF6D3FD4),
    background = LightPaper,
    onBackground = Color(0xFF121722),
    surface = LightSurface,
    onSurface = Color(0xFF121722),
    surfaceVariant = Color(0xFFE9EDF6),
    onSurfaceVariant = Color(0xFF5A6478),
    outline = Color(0xFFD3DAE8),
    error = Color(0xFFD93F3F),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

@Composable
fun StenografTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}