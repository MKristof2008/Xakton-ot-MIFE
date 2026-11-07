package ru.mifi.stenograf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.mifi.stenograf.settings.SettingsStore

/**
 * Всплывающее меню по кнопке справа сверху: история, настройки, о приложении.
 *
 * Всё «служебное» живёт здесь, чтобы главный экран оставался про одно действие —
 * выбрать запись и получить расшифровку. Ничего не отвлекает.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    state: AppState,
    settings: SettingsStore,
    onDismiss: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var section by remember { mutableStateOf(MenuSection.ROOT) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (section) {
                        MenuSection.ROOT -> "Меню"
                        MenuSection.SETTINGS -> "Настройки"
                        MenuSection.ABOUT -> "О приложении"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickableNoRipple {
                            if (section == MenuSection.ROOT) onDismiss() else section = MenuSection.ROOT
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (section == MenuSection.ROOT) Icons.Filled.Close
                        else Icons.Filled.Settings,
                        contentDescription = "Закрыть",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            when (section) {
                MenuSection.ROOT -> RootSection(
                    historyCount = state.history.size,
                    onHistory = {
                        onDismiss()
                        onOpenHistory()
                    },
                    onSettings = { section = MenuSection.SETTINGS },
                    onAbout = { section = MenuSection.ABOUT },
                )
                MenuSection.SETTINGS -> SettingsSection(settings, state)
                MenuSection.ABOUT -> AboutSection()
            }
        }
    }
}

private enum class MenuSection { ROOT, SETTINGS, ABOUT }

@Composable
private fun RootSection(
    historyCount: Int,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    MenuRow(
        icon = Icons.Filled.History,
        title = "История расшифровок",
        subtitle = if (historyCount == 0) "пока пусто" else "$historyCount записей",
        onClick = onHistory,
    )
    MenuRow(
        icon = Icons.Filled.Settings,
        title = "Настройки",
        subtitle = "звук, хранение, текст",
        onClick = onSettings,
    )
    MenuRow(
        icon = Icons.Filled.Info,
        title = "О приложении",
        subtitle = "модели и метрики",
        onClick = onAbout,
    )
}

@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsSection(settings: SettingsStore, state: AppState) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // SharedPreferences не умеет уведомлять Compose: геттер возвращает новое
    // значение, но рекомпозиции нет, и переключатель «залипал» до перезахода
    // в меню. Держим локальную копию в Compose-состоянии и пишем в prefs
    // при каждом изменении — тогда и анимация Switch, и выбор языка видны сразу.
    var haptics by remember { mutableStateOf(settings.haptics) }
    var keepAudio by remember { mutableStateOf(settings.keepAudio) }
    var historyLimit by remember { mutableStateOf(settings.historyLimit) }
    var language by remember { mutableStateOf(settings.language) }
    var punctuation by remember { mutableStateOf(settings.punctuation) }
    var showTimecodes by remember { mutableStateOf(settings.showTimecodes) }

    SectionLabel("Звук и отклик")
    ToggleRow(
        title = "Вибрация при нажатиях",
        subtitle = "короткий отклик на кнопки и готовность",
        checked = haptics,
    ) { on ->
        haptics = on
        settings.haptics = on
        if (on) Haptics.tap(context)
    }

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    )

    SectionLabel("Хранение")
    ToggleRow(
        title = "Хранить аудио в истории",
        subtitle = "без этого запись нельзя будет прослушать. ~1.9 МБ на минуту",
        checked = keepAudio,
    ) {
        keepAudio = it
        settings.keepAudio = it
    }

    OptionRow(
        title = "Сколько записей держать",
        options = SettingsStore.HISTORY_LIMITS.map { it.toString() },
        selectedIndex = SettingsStore.HISTORY_LIMITS.indexOf(historyLimit).coerceAtLeast(0),
    ) { idx ->
        historyLimit = SettingsStore.HISTORY_LIMITS[idx]
        settings.historyLimit = historyLimit
    }

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    )

    SectionLabel("Распознавание")
    OptionRow(
        title = "Язык записи",
        options = ru.mifi.stenograf.asr.RecognitionLanguage.entries.map { it.label },
        selectedIndex = ru.mifi.stenograf.asr.RecognitionLanguage.entries.indexOf(language)
            .coerceAtLeast(0),
    ) { idx ->
        language = ru.mifi.stenograf.asr.RecognitionLanguage.entries[idx]
        settings.language = language
    }
    Text(
        "Авто определяет язык по началу записи: русский идёт на zipformer-ru, " +
            "остальные — на многоязычный Whisper. Всё на устройстве.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp),
    )

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    )

    SectionLabel("Текст")
    ToggleRow(
        title = "Пунктуация и регистр",
        subtitle = "расставлять точки, запятые и заглавные буквы",
        checked = punctuation,
    ) {
        punctuation = it
        settings.punctuation = it
    }

    ToggleRow(
        title = "Показывать таймкоды",
        subtitle = "по умолчанию на экране результата",
        checked = showTimecodes,
    ) {
        showTimecodes = it
        settings.showTimecodes = it
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun OptionRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { idx, label ->
                val selected = idx == selectedIndex
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickableNoRipple { onSelect(idx) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutSection() {
    val rows = listOf(
        "Версия" to "0.5.0",
        "Распознавание" to "zipformer-ru + Whisper base (мультиязычная)",
        "Диаризация" to "pyannote segmentation-3.0 + 3D-Speaker",
        "Рантайм" to "sherpa-onnx 2.0.1",
        "Сеть" to "не используется — нет разрешения INTERNET",
        "Точность (WER)" to "0.046 на чистой записи",
        "Скорость (RTF)" to "0.46 — вдвое быстрее реального времени",
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        rows.forEach { (k, v) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    k,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(130.dp),
                )
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Все вычисления выполняются на устройстве. Аудио не покидает телефон.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}