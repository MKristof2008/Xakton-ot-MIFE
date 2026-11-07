package ru.mifi.stenograf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import ru.mifi.stenograf.audio.AudioPlayback
import ru.mifi.stenograf.output.Replica
import ru.mifi.stenograf.pipeline.Stage
import ru.mifi.stenograf.pipeline.TranscriptResult

/**
 * Результат расшифровки: сводка с метриками, лента реплик с цветом говорящего
 * и панель экспорта. Таймкоды можно скрыть — для чтения текста они мешают.
 */
@Composable
fun ResultScreen(
    state: AppState,
    onExportJson: () -> Unit,
    onExportText: () -> Unit,
) {
    val result = state.result ?: return
    var showTimecodes by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(result) { Haptics.success(context) }

    // Поиск по тексту расшифровки: регистр не важен.
    val shown = remember(query, result.replicas) {
        if (query.isBlank()) result.replicas
        else result.replicas.filter { it.text.contains(query, ignoreCase = true) }
    }

    // Плеер: живой, если у результата есть файл аудио (запись или история).
    val playback = remember { AudioPlayback() }
    val audioPath = state.recordedPath ?: state.audioUri?.takeIf { it.scheme == "file" }?.path
    LaunchedEffect(audioPath) {
        if (audioPath != null) playback.prepare(audioPath) else playback.stop()
    }
    // Освобождаем MediaPlayer при уходе с экрана. Без этого системный плеер
    // писал в лог «MediaPlayer finalized without being released», а нативные
    // ресурсы висели до сборки мусора — на длинных записях это лишняя память.
    DisposableEffect(Unit) {
        onDispose { playback.release() }
    }
    // Тикер позиции, пока играет.
    LaunchedEffect(playback.isPlaying) {
        while (playback.isPlaying) {
            playback.syncPosition()
            kotlinx.coroutines.delay(120)
        }
    }
    // Активная реплика: та, внутри которой сейчас играет звук.
    val activeIndex = if (audioPath == null) -1
    else shown.indexOfFirst { playback.positionMs in it.startMs..it.endMs }

    Column(modifier = Modifier.fillMaxSize().brandGlow()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SummaryHeader(result) }
            if (audioPath != null) {
                item {
                    PlayerBar(
                        playback = playback,
                        title = state.viewingTitle.ifBlank { state.fileName },
                    )
                }
            }
            item { MetricsRow(result) }
            item {
                SearchField(query = query, onChange = { query = it })
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (query.isBlank()) "Расшифровка" else "Найдено: ${shown.size}",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "таймкоды",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = showTimecodes,
                        onCheckedChange = { showTimecodes = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
            itemsIndexed(shown, key = { _, r -> "${r.startMs}-${r.speakerId}" }) { index, rep ->
                // Каскад: каждая реплика появляется с задержкой по индексу.
                // На демо это читается как «система думает и выдаёт результат».
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(rep.startMs) {
                    kotlinx.coroutines.delay((index.coerceAtMost(12)) * 55L)
                    visible = true
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = visible,
                    enter = androidx.compose.animation.fadeIn(tween(220)) +
                        androidx.compose.animation.slideInHorizontally(tween(220)) { it / 6 },
                ) {
                ReplicaRow(
                    rep = rep,
                    showTimecodes = showTimecodes,
                    isActive = index == activeIndex,
                    onClick = if (audioPath != null) {
                        { playback.playFrom(rep.startMs) }
                    } else {
                        null
                    },
                )
                }
            }
        }

        ExportBar(onExportJson, onExportText, onNew = { state.backToImport() })
    }
}

@Composable
private fun SummaryHeader(result: TranscriptResult) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Готово",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            "${result.replicas.size} реплик · ${result.speakerCount} " +
                if (result.speakerCount == 1) "говорящий" else "говорящих",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Плитки метрик: длительность, время обработки, RTF. RTF спросят на защите. */
@Composable
private fun MetricsRow(result: TranscriptResult) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricTile(
            value = Replica.timecode(result.durationMs.toInt()),
            label = "длина",
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            value = "${(result.processingMs / 1000f).toInt()} с",
            label = "обработка",
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            value = "%.2f".format(result.rtf),
            label = "RTF",
            modifier = Modifier.weight(1f),
            highlight = result.rtf <= 1f,
        )
    }
}

@Composable
private fun MetricTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                color = if (highlight) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * Одна реплика: цветная полоса слева — говорящий, над текстом — метка
 * с номером и временем. Полоса даёт мгновенное узнавание «кто это» при чтении.
 */
@Composable
private fun ReplicaRow(
    rep: Replica,
    showTimecodes: Boolean,
    isActive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val color = speakerColor(rep.speakerId)
    val bg = if (isActive) color.copy(alpha = 0.16f)
    else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    val speakerNumber = rep.speakerId.substringAfterLast('_')

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(if (rep.text.length > 90) 68.dp else 44.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .then(if (onClick != null) Modifier.clickableNoRipple(onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Спикер $speakerNumber",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
                if (showTimecodes) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${Replica.timecode(rep.startMs)}–${Replica.timecode(rep.endMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(rep.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ExportBar(onJson: () -> Unit, onText: () -> Unit, onNew: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                ),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onJson,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Экспорт JSON по ТЗ", style = MaterialTheme.typography.labelLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onText,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("TXT", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = onNew,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Новая", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
/**
 * Плеер записи: play/pause, полоса прогресса, текущее время.
 * Полоса кликабельна — можно перескочить в любое место, не листая реплики.
 */
@Composable
private fun PlayerBar(playback: AudioPlayback, title: String) {
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val brandGradient = BrandGradient
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickableNoRipple { playback.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Пауза" else "Слушать",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        "${Replica.timecode(playback.positionMs)} / ${Replica.timecode(playback.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // Полоса прогресса: клик по ней перескакивает в это место записи.
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .pointerInput(playback.durationMs) {
                        detectTapGestures { offset ->
                            if (playback.durationMs > 0) {
                                val frac = (offset.x / size.width).coerceIn(0f, 1f)
                                playback.playFrom((frac * playback.durationMs).toInt())
                            }
                        }
                    },
            ) {
                val cy = size.height / 2f
                drawLine(
                    color = surfaceVariant,
                    start = Offset(0f, cy),
                    end = Offset(size.width, cy),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                val frac = if (playback.durationMs > 0) {
                    (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
                } else 0f
                if (frac > 0f) {
                    drawLine(
                        brush = Brush.horizontalGradient(brandGradient),
                        start = Offset(0f, cy),
                        end = Offset(size.width * frac, cy),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                drawCircle(
                    color = onSurface,
                    radius = 5.dp.toPx(),
                    center = Offset(size.width * frac, cy),
                )
            }
        }
    }
}



/** Строка поиска по расшифровке. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Поиск по тексту", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Очистить",
                    modifier = Modifier.size(18.dp).clickableNoRipple { onChange("") },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
    )
}
