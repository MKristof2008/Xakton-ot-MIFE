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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Translate
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
/** Сколько первых реплик появляются каскадом. Остальные — сразу. */
private const val CASCADE_LIMIT = 8

@Composable
fun ResultScreen(
    state: AppState,
    onExportJson: () -> Unit,
    onExportText: () -> Unit,
    onExportSrt: () -> Unit = {},
) {
    val result = state.result ?: return
    // Начальное значение — из настроек, а не всегда true: раньше тумблер
    // «Показывать таймкоды по умолчанию» сохранялся, но экран его не читал.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val settings = remember { ru.mifi.stenograf.settings.SettingsStore(appContext) }
    var showTimecodes by remember { mutableStateOf(settings.showTimecodes) }
    var query by remember { mutableStateOf("") }
    // Кого переименовываем: null — диалог закрыт.
    var renaming by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(result) { Haptics.success(context) }

    // Поиск по оригиналу И по переводу: искать «deadline» в русском транскрипте
// с английскими пометками иначе нечем.
    val shown = remember(query, result.replicas, state.translations) {
        if (query.isBlank()) result.replicas
        else result.replicas.filter {
            it.text.contains(query, ignoreCase = true) ||
                state.translations[it.startMs]?.contains(query, ignoreCase = true) == true
        }
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

    // Автопрокрутка к звучащей реплике: при прослушивании длинной записи
    // подсветка уезжала за экран и было непонятно, где сейчас звук.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(activeIndex) {
        // +5 — шапка со сводкой и плеером выше списка. Скроллим только когда
        // реально играет звук: иначе список прыгает при каждом касании.
        if (activeIndex >= 0 && playback.isPlaying) {
            runCatching { listState.animateScrollToItem(activeIndex + 5) }
        }
    }

    // Перевод доступен, только если локальная модель уже на устройстве.
    // Проверка — это чтение каталога, поэтому запоминаем на один экран.
    val canTranslate = remember(result) { ru.mifi.stenograf.llm.Translator.isAvailable(appContext) }

    Column(modifier = Modifier.fillMaxSize().brandGlow()) {
        LazyColumn(
            state = listState,
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
                SpeakingShare(
                    replicas = shown,
                    label = { state.speakerLabel(it) },
                )
            }
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
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "таймкоды",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
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
            // Массовый перевод — отдельной строкой под заголовком: в одной
            // строке с «таймкоды» текст заголовка ломался на две строки.
            if (canTranslate && state.translations.isEmpty() || state.translatingAll) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                if (state.translatingAll) state.stopTranslateAll()
                                else state.translateAll(result.replicas)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            if (state.translatingAll) {
                                // Спиннер вместо иконки: видно, что идёт работа,
                                // и сколько реплик уже готово.
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 1.8.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Translate,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (state.translatingAll) {
                                    "Остановить · ${state.translations.size}/${result.replicas.size}"
                                } else {
                                    "Перевести все реплики"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            // Пустая расшифровка — не «сломалось», а «в записи не нашлось речи».
            // Раньше экран просто показывал заголовок и пустоту, и было
            // непонятно, обработался файл или нет.
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (result.replicas.isEmpty()) {
                            "В записи не найдено речи. Проверьте, что звук не выключен " +
                                "и микрофон не был закрыт; тишина и ровный шум отбрасываются."
                        } else {
                            "По запросу «$query» ничего не найдено."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                    )
                }
            }
            itemsIndexed(shown, key = { _, r -> "${r.startMs}-${r.speakerId}" }) { index, rep ->
                // Каскад только для первых реплик: на записи в 200 реплик
                // задержка в полсекунды на каждую превращала прокрутку в слайд-шоу.
                var visible by remember { mutableStateOf(index >= CASCADE_LIMIT) }
                LaunchedEffect(rep.startMs) {
                    if (index < CASCADE_LIMIT) {
                        kotlinx.coroutines.delay(index * 45L)
                        visible = true
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = visible,
                    enter = androidx.compose.animation.fadeIn(tween(220)) +
                        androidx.compose.animation.slideInVertically(tween(220)) { it / 8 },
                ) {
                ReplicaRow(
                    rep = rep,
                    speakerLabel = state.speakerLabel(rep.speakerId),
                    showTimecodes = showTimecodes,
                    isActive = index == activeIndex,
                    translation = state.translationFor(rep),
                    isTranslating = state.translatingStartMs == rep.startMs,
                    canTranslate = canTranslate,
                    onClick = if (audioPath != null) {
                        { playback.playFrom(rep.startMs) }
                    } else {
                        null
                    },
                    onTranslate = { state.translate(rep) },
                    onRenameSpeaker = { renaming = rep.speakerId },
                )
                }
            }
        }

        ExportBar(onExportJson, onExportText, onExportSrt, onNew = { state.backToImport() })
    }

    // Переименование говорящего: «Спикер 2» → «Иванов».
    val renameTarget = renaming
    if (renameTarget != null) {
        var nameInput by remember(renameTarget) {
            mutableStateOf(state.speakerNames[renameTarget].orEmpty())
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            title = {
                Text("Переименовать: ${state.speakerLabel(renameTarget)}")
            },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it.take(24) },
                    singleLine = true,
                    placeholder = { Text("Имя или роль") },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    state.renameSpeaker(renameTarget, nameInput)
                    renaming = null
                }) { Text("Сохранить") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renaming = null }) { Text("Отмена") }
            },
        )
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

/**
 * Сколько времени говорил каждый участник — по одной полоске на человека.
 * На встречах это первый вопрос после расшифровки: «кто вообще говорил».
 */
@Composable
private fun SpeakingShare(
    replicas: List<Replica>,
    label: (String) -> String,
) {
    if (replicas.isEmpty()) return
    val total = replicas.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }.coerceAtLeast(1)
    val shares = replicas
        .groupBy { it.speakerId }
        .map { (id, list) -> id to list.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) } }
        .sortedByDescending { it.second }
    if (shares.size < 2) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            for ((id, ms) in shares) {
                Box(
                    modifier = Modifier
                        .weight((ms.toFloat() / total).coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .background(speakerColor(id)),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((id, ms) in shares.take(4)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(speakerColor(id)),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${label(id)} ${(ms * 100 / total)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
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
    speakerLabel: String,
    showTimecodes: Boolean,
    isActive: Boolean = false,
    translation: String? = null,
    isTranslating: Boolean = false,
    canTranslate: Boolean = false,
    onClick: (() -> Unit)? = null,
    onTranslate: (() -> Unit)? = null,
    onRenameSpeaker: (() -> Unit)? = null,
) {
    val color = speakerColor(rep.speakerId)
    val bg = if (isActive) color.copy(alpha = 0.16f)
    else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)

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
                    speakerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    modifier = if (onRenameSpeaker != null) {
                        Modifier.clickableNoRipple(onRenameSpeaker)
                    } else Modifier,
                )
                if (showTimecodes) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${Replica.timecode(rep.startMs)}–${Replica.timecode(rep.endMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (canTranslate && onTranslate != null) {
                    TranslateChip(
                        busy = isTranslating,
                        done = translation != null,
                        onClick = onTranslate,
                    )
                }
            }
            Text(rep.text, style = MaterialTheme.typography.bodyLarge)
            // Перевод появляется под оригиналом и отделён тонкой линией:
            // так видно, что это другой язык, а не продолжение реплики.
            androidx.compose.animation.AnimatedVisibility(
                visible = translation != null,
                enter = androidx.compose.animation.fadeIn(tween(180)) +
                    androidx.compose.animation.expandVertically(tween(180)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        translation.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color.copy(alpha = 0.95f),
                    )
                }
            }
        }
    }
}

/** Маленькая кнопка «перевести» в строке реплики: три состояния. */
@Composable
private fun TranslateChip(busy: Boolean, done: Boolean, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = if (done) 0.16f else 0.10f))
            .clickableNoRipple(onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = tint,
            )
        } else {
            Text(
                if (done) "перевод ✓" else "перевести",
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
    }
}

@Composable
private fun ExportBar(
    onJson: () -> Unit,
    onText: () -> Unit,
    onSrt: () -> Unit,
    onNew: () -> Unit,
) {
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
                Text("TXT", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            OutlinedButton(
                onClick = onSrt,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.ClosedCaption, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("SRT", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            OutlinedButton(
                onClick = onNew,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Новая", style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
