package ru.mifi.stenograf.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

/**
 * Живая эквалайзер-полоса: показывается во время обработки и как украшение
 * на стартовом экране. Сделана на Canvas, без картинок — не тянет APK.
 */
@Composable
fun WaveBars(
    modifier: Modifier = Modifier,
    barCount: Int = 9,
    color: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    animated: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Canvas(modifier = modifier.height(56.dp)) {
        drawBars(barCount, color, if (animated) phase else 1.2f)
    }
}

private fun DrawScope.drawBars(count: Int, color: Color, phase: Float) {
    val gap = size.width / (count * 2f)
    val barW = gap
    val centerY = size.height / 2f
    for (i in 0 until count) {
        // Высота — сумма двух синусов с разным периодом: движение выглядит
        // живым, а не механическим.
        val w = sin(phase + i * 0.7f) * 0.5f + sin(phase * 0.6f + i * 1.3f) * 0.3f
        val h = (0.22f + abs(w) * 0.78f) * size.height
        val x = i * gap * 2f + gap / 2f
        drawRoundRect(
            color = color.copy(alpha = 0.9f - i * 0.04f),
            topLeft = Offset(x, centerY - h / 2f),
            size = Size(barW, h),
            cornerRadius = CornerRadius(barW / 2f, barW / 2f),
        )
    }
}

/** Статичный мини-индикатор спикера: три столбика цвета говорящего. */
@Composable
fun SpeakerDot(level: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 18.dp, height = 18.dp)) {
        val bars = 3
        val gap = size.width / (bars * 2f)
        for (i in 0 until bars) {
            val h = size.height * (0.35f + level * (0.3f + i * 0.2f))
            drawRoundRect(
                color = color,
                topLeft = Offset(i * gap * 2f, (size.height - h) / 2f),
                size = Size(gap, h),
                cornerRadius = CornerRadius(gap / 2f, gap / 2f),
            )
        }
    }
}

/** Ряд из трёх точек-пульсаций: индикатор «идёт работа». */
@Composable
fun PulseDots(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val p by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Row(modifier = modifier) {
        Canvas(modifier = Modifier.size(width = 46.dp, height = 12.dp)) {
            for (i in 0 until 3) {
                val local = ((p + i * 0.22f) % 1f)
                val r = 2.dp.toPx() + (1 - local) * 2.5f.dp.toPx()
                drawCircle(
                    color = color.copy(alpha = 0.35f + (1 - local) * 0.65f),
                    radius = r,
                    center = Offset(6.dp.toPx() + i * 14.dp.toPx(), size.height / 2f),
                )
            }
        }
    }
}