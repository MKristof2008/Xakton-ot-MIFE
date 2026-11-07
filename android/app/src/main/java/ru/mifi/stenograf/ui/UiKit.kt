package ru.mifi.stenograf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Клик без волны-ряби: для круглых «пилюль» и карточек с собственным состоянием. */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

/** Мягкая подсветка-градиент позади контента; используется на экране результата. */
fun Modifier.brandGlow(alpha: Float = 0.16f): Modifier = composed {
    drawBehind {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    androidx.compose.ui.graphics.Color(0xFF4C7DFF).copy(alpha = alpha),
                    androidx.compose.ui.graphics.Color.Transparent,
                ),
                center = Offset(size.width * 0.85f, -size.height * 0.05f),
                radius = size.width * 0.9f,
            ),
        )
    }
}

val CardShape = RoundedCornerShape(22.dp)
val PillShape = RoundedCornerShape(50)

fun Modifier.tintBackground(
    color: androidx.compose.ui.graphics.Color,
    shape: Shape = PillShape,
): Modifier = clip(shape).background(color)
