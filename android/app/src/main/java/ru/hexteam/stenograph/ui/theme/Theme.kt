package ru.hexteam.stenograph.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
    primary = Accent,
    secondary = Accent2,
    tertiary = Success,
    background = BgDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceHighDark,
    outline = BorderDark,
    onPrimary = BgDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    secondaryContainer = SurfaceHighDark,
    onSecondaryContainer = TextSecondary,
)

private val LightScheme = lightColorScheme(
    primary = Accent,
    secondary = Accent2,
    tertiary = Success,
    background = BgLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceHighLight,
    outline = BorderLight,
    onPrimary = SurfaceLight,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
    onSurfaceVariant = TextSecondaryLight,
    secondaryContainer = SurfaceHighLight,
    onSecondaryContainer = TextSecondaryLight,
)

/**
 * Тема приложения. По спецификации тёмная — основная, но системный выбор
 * пользователя уважаем: светлая тема тоже доступна.
 */
@Composable
fun StenographTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
