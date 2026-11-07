package ru.mifi.stenograf.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin

/**
 * Живой фон: медленно плывущие цветные пятна и мелкая сетка.
 *
 * Это первое, что видит человек на демо, и это задаёт ощущение «продукт, а не
 * лабораторная». Рисуется на Canvas: ни картинок, ни гифок, вес нулевой.
 * Движение намеренно медленное (период 12–20 с), чтобы не рябило в глазах
 * и не жрало батарею.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing)),
        label = "auroraTime",
    )

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawAurora(t)
        }
        content()
    }
}

private fun DrawScope.drawAurora(t: Float) {
    val w = size.width
    val h = size.height
    val twoPi = (2 * Math.PI).toFloat()

    // Три пятна, каждое со своей фазой и орбитой. Цвета — фирменные,
    // прозрачность низкая: фон должен читаться, а не спорить с текстом.
    // Яркость поднята: на тёмном фоне полупрозрачные пятна «съедались»,
    // и фон выглядел просто чёрным. Теперь видно, но текст остаётся читаемым.
    // Пятна заметные: фон должен читаться как «живой», а не как чёрный экран.
    // Прозрачность 0.5–0.8 при радиальном градиенте даёт мягкое свечение.
    val blobs = listOf(
        Triple(Color(0xFF4C7DFF), 0.0f, 0.80f),
        Triple(Color(0xFF8B5CF6), 0.35f, 0.70f),
        Triple(Color(0xFF38E1C4), 0.7f, 0.50f),
    )

    for ((color, phase, alpha) in blobs) {
        val angle = t * twoPi + phase * twoPi
        val cx = w * (0.5f + 0.38f * cos(angle))
        val cy = h * (0.28f + 0.22f * sin(angle * 1.3f + phase))
        val radius = w * (0.55f + 0.08f * sin(angle * 0.7f))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                center = Offset(cx, cy),
                radius = radius,
            ),
            radius = radius,
            center = Offset(cx, cy),
        )
    }

}
