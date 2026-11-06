package ru.mifi.stenograf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.mifi.stenograf.output.Replica
import ru.mifi.stenograf.pipeline.TranscriptResult

/** Результат: реплики с таймкодами и спикерами + экспорт. */
@Composable
fun ResultScreen(
    state: AppState,
    onExportJson: () -> Unit,
    onExportText: () -> Unit,
) {
    val result = state.result ?: return
    var showTimecodes by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        SummaryCard(result)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Таймкоды", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = showTimecodes, onCheckedChange = { showTimecodes = it })
        }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(result.replicas) { rep ->
                ReplicaCard(rep, showTimecodes)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onExportJson, modifier = Modifier.weight(1f)) { Text("Экспорт JSON") }
            OutlinedButton(onClick = onExportText) { Text("TXT") }
            OutlinedButton(onClick = { state.backToImport() }) { Text("Новая") }
        }
    }
}

@Composable
private fun SummaryCard(result: TranscriptResult) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Расшифровка готова", style = MaterialTheme.typography.titleMedium)
            Text(
                "Реплик: ${result.replicas.size} · говорящих: ${result.speakerCount} · " +
                    "длительность: ${Replica.timecode(result.durationMs.toInt())}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            // RTF на экране — на защите это цифра, которую спросят.
            Text(
                "Время обработки %.1f с · RTF %.2f".format(result.processingMs / 1000f, result.rtf),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun ReplicaCard(rep: Replica, showTimecodes: Boolean) {
    val color = speakerColor(rep.speakerId)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 36.dp)
                    .background(color, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.size(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rep.speakerId.replace("speaker_", "Спикер "),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (showTimecodes) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "${Replica.timecode(rep.startMs)}–${Replica.timecode(rep.endMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Text(rep.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}