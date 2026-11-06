package ru.hexteam.stenograph.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.hexteam.stenograph.core.Merge
import ru.hexteam.stenograph.ui.AppViewModel
import ru.hexteam.stenograph.ui.player.AudioPlayer
import ru.hexteam.stenograph.ui.theme.speakerColor

/**
 * Экран 3: расшифровка. Спецификация §3.3.
 * Легенда говорящих (чипы-фильтры), лента реплик с цветом говорящего,
 * поиск по тексту, переименование говорящих (только отображение),
 * экспорт: JSON в файл (SAF), JSON/TXT/SRT через «Поделиться».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(state: AppViewModel.UiState, vm: AppViewModel) {
    val context = LocalContext.current
    var showExport by remember { mutableStateOf(false) }
    var renamingSpeaker by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Плеер: переход к реплике по тапу, подсветка текущей
    val scope = rememberCoroutineScope()
    val player = remember { AudioPlayer(context, scope) }
    val playerState by player.state.collectAsState()

    DisposableEffect(state.audioUri) {
        state.audioUri?.let { player.load(it) }
        onDispose { player.release() }
    }

    // Сохранение JSON в файл через SAF — основной путь сдачи результата.
    val saveJson = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(vm.exportJson().toByteArray(Charsets.UTF_8))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        // Метрики: длительность, реплики, говорящие, RTF
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(state.audioName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "RTF %.2f".format(state.rtf),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "Длительность ${Merge.timecode(state.audioDurationMs)} · " +
                "реплик ${state.lines.size} · говорящих ${state.speakers.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Легенда говорящих: чип — фильтр ленты
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.speakerFilter == null,
                onClick = { vm.setSpeakerFilter(null) },
                label = { Text("Все") },
            )
            state.speakers.forEach { speaker ->
                FilterChip(
                    selected = state.speakerFilter == speaker,
                    onClick = {
                        vm.setSpeakerFilter(if (state.speakerFilter == speaker) null else speaker)
                    },
                    label = { Text(vm.labelFor(speaker)) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(speakerColor(speaker)),
                        )
                    },
                )
            }
        }

        // Поиск по тексту
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { vm.setSearchQuery(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Поиск по тексту") },
            singleLine = true,
        )

        // Панель плеера
        if (playerState.isReady) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { player.playPause() }) {
                    Text(if (playerState.isPlaying) "Пауза" else "Слушать")
                }
                Slider(
                    value = playerState.positionMs.toFloat()
                        .coerceIn(0f, playerState.durationMs.toFloat().coerceAtLeast(1f)),
                    valueRange = 0f..playerState.durationMs.toFloat().coerceAtLeast(1f),
                    onValueChange = { player.seekTo(it.toLong()) },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = Merge.timecode(playerState.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Лента реплик
        if (state.visibleLines.isEmpty()) {
            Text(
                text = if (state.hasResult) "Ничего не найдено"
                else "Речь не распознана: в записи нет сегментов.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.visibleLines) { line ->
                    val isCurrent = playerState.positionMs in line.startMs until line.endMs
                    TranscriptCard(
                        speakerLabel = vm.labelFor(line.speakerId),
                        speakerId = line.speakerId,
                        startMs = line.startMs,
                        text = line.text,
                        highlighted = isCurrent,
                        onClick = { player.seekTo(line.startMs) },
                        onLongClick = {
                            renamingSpeaker = line.speakerId
                            renameText = vm.labelFor(line.speakerId)
                        },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.reset() },
                shape = RoundedCornerShape(13.dp),
            ) {
                Text("Новая запись")
            }
            Button(
                onClick = { showExport = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(13.dp),
                enabled = state.hasResult,
            ) {
                Text("Экспорт")
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // Переименование говорящего — только для отображения, в экспорте speaker_N
    renamingSpeaker?.let { speaker ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renamingSpeaker = null },
            title = { Text("Переименовать говорящего") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Отображаемое имя") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameSpeaker(speaker, renameText)
                    renamingSpeaker = null
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { renamingSpeaker = null }) { Text("Отмена") }
            },
        )
    }

    // Экспорт: нижний лист, спецификация §3.4
    if (showExport) {
        ModalBottomSheet(onDismissRequest = { showExport = false }) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Экспорт", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        saveJson.launch("transcript.json")
                        showExport = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("JSON в файл (по схеме ТЗ)") }
                OutlinedButton(
                    onClick = {
                        shareText(context, vm.exportJson(), "transcript.json")
                        showExport = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Поделиться JSON") }
                OutlinedButton(
                    onClick = {
                        shareText(context, vm.exportText(), "transcript.txt")
                        showExport = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Поделиться текстом") }
                OutlinedButton(
                    onClick = {
                        shareText(context, vm.exportSrt(), "transcript.srt")
                        showExport = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Поделиться SRT") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptCard(
    speakerLabel: String,
    speakerId: String,
    startMs: Long,
    text: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val color = speakerColor(speakerId)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            // Цветная полоса говорящего
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = speakerLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = Merge.timecode(startMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Открывает системное «Поделиться» — результат уходит в файл или мессенджер. */
private fun shareText(context: Context, text: String, fileName: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (fileName.endsWith(".json")) "application/json" else "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
    }
    context.startActivity(Intent.createChooser(intent, "Экспорт расшифровки"))
}
