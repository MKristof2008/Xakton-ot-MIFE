package ru.mifi.stenograf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.mifi.stenograf.pipeline.Stage

/** Экран прогресса: этапы, общий процент, отмена. Без него обработка выглядит «зависшей». */
@Composable
fun ProcessingScreen(state: AppState) {
    val p = state.progress
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Обработка", style = MaterialTheme.typography.headlineSmall)
        Text(state.fileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        p.stage.label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(p.overallFraction * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(
                    progress = { p.overallFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (p.message.isNotEmpty()) {
                    Text(
                        p.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stage.entries.filter { it != Stage.DONE }.forEach { stage ->
                val passed = stage.ordinal < p.stage.ordinal
                val current = stage == p.stage
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            passed -> "✓"
                            current -> "•"
                            else -> "○"
                        },
                        modifier = Modifier.size(20.dp),
                        color = when {
                            passed -> MaterialTheme.colorScheme.primary
                            current -> MaterialTheme.colorScheme.onBackground
                            else -> MaterialTheme.colorScheme.secondary
                        },
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stage.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (current || passed) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Обработка идёт на устройстве. Сеть не используется — можно включить авиарежим.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        OutlinedButton(onClick = { state.cancel() }) { Text("Отменить") }
    }
}