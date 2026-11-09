package ru.mifi.stenograf.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.mifi.stenograf.pipeline.Stage

/**
 * Экран прогресса. Круговой индикатор с процентом внутри, список этапов
 * с состоянием и живая волна — чтобы ожидание не выглядело зависанием.
 */
@Composable
fun ProcessingScreen(state: AppState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val p = state.progress
    val animated by animateFloatAsState(
        targetValue = p.overallFraction.coerceIn(0f, 1f),
        animationSpec = tween(450),
        label = "progress",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            "Обработка записи",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            state.fileName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            ProgressRing(animated)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                p.stage.label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                p.message.ifBlank { "Работаем на устройстве" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        WaveBarsGradient(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            barCount = 26,
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Stage.entries.filter { it != Stage.DONE }.forEach { stage ->
                StageRow(stage, current = p.stage, passed = stage.ordinal < p.stage.ordinal)
            }
        }

        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = {
                Haptics.warn(context)
                state.cancel()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        ) { Text("Отменить", style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ProgressRing(progress: Float) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceVariant

    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(190.dp)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(primary, secondary, primary)),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // «43 %» компактнее, чем «43 процентов», и не выдаёт
            // неверное склонение на 1, 2 и 44.
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "${(progress * 100).toInt()}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StageRow(stage: Stage, current: Stage, passed: Boolean) {
    val active = stage == current
    val color = when {
        passed -> MaterialTheme.colorScheme.secondary
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (passed || active) 0.16f else 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            if (passed) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            stage.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (passed || active) MaterialTheme.colorScheme.onBackground
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}