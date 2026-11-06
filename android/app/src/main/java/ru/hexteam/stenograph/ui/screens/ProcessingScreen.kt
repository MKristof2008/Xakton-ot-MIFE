package ru.hexteam.stenograph.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.hexteam.stenograph.core.PipelineStage
import ru.hexteam.stenograph.ui.AppViewModel
import ru.hexteam.stenograph.ui.theme.Success
import ru.hexteam.stenograph.ui.theme.TextMuted

/**
 * Экран 2: обработка. Спецификация §3.2.
 * Этапы со статусами: пройден (галочка), идёт (спиннер), впереди (номер).
 * «Отменить» прерывает обработку и возвращает на главный.
 */
@Composable
fun ProcessingScreen(state: AppViewModel.UiState, vm: AppViewModel) {
    val stages = listOf(
        PipelineStage.ASR,
        PipelineStage.DIARIZATION,
        PipelineStage.MERGE,
        PipelineStage.LLM,
    )
    val currentIndex = stages.indexOf(state.stage)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(state.audioName, style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator(modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (state.stage == PipelineStage.ERROR) "Ошибка обработки"
            else state.stage.title,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Полностью офлайн: запись никуда не уходит",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stages.forEachIndexed { index, stage ->
                StageRow(
                    title = stage.title,
                    number = index + 1,
                    done = state.stage == PipelineStage.DONE || index < currentIndex,
                    active = index == currentIndex && state.isBusy,
                )
            }
        }

        state.error?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = { vm.retry() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
            ) {
                Text("Повторить")
            }
        }

        OutlinedButton(
            onClick = { vm.cancel() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(13.dp),
        ) {
            Text("Отменить")
        }
    }
}

@Composable
private fun StageRow(title: String, number: Int, done: Boolean, active: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            done -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Готово",
                tint = Success,
            )
            active -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else -> Text(
                text = "$number",
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
