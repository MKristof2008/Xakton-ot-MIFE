package ru.mifi.stenograf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Стартовый экран: заголовок-плашка, импорт аудио, выбор числа говорящих,
 * статус моделей. Ни одного сетевого действия — офлайн проверяется по манифесту.
 */
@Composable
fun ImportScreen(
    state: AppState,
    onPickFile: (Uri, String, Int?) -> Unit,
    onRecord: () -> Unit = {},
    onHistory: () -> Unit = {},
    onMenu: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var speakersAuto by remember { mutableStateOf(true) }
    var speakersCount by remember { mutableStateOf(2) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onPickFile(
                uri,
                uri.lastPathSegment?.substringAfterLast('/') ?: "аудио",
                if (speakersAuto) null else speakersCount,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { HeroHeader() }
            // Меню справа сверху: история, настройки, о приложении.
            // Всё «служебное» убрано сюда, чтобы главный экран был про одно действие.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickableNoRipple {
                        Haptics.tap(context)
                        onMenu()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Меню",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        ImportCard(
            onPick = {
                Haptics.confirm(context)
                picker.launch(arrayOf("audio/*", "video/*"))
            },
            onRecord = onRecord,
        )

        SpeakersCard(
            context = context,
            auto = speakersAuto,
            count = speakersCount,
            onAutoChange = { speakersAuto = it },
            onCountChange = { speakersCount = it },
        )

        ModelsCard(state)


        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun HeroHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(BrandGradient)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Стенографист",
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                )
                Text(
                    "звук → текст → кто говорил",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

    }
}

@Composable
private fun ImportCard(onPick: () -> Unit, onRecord: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Две крупные плитки вместо кнопок: зона нажатия — вся карточка,
    // а не узкая полоска. На демо это первое, куда попадает палец.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ActionTile(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Upload,
            title = "Файл",
            subtitle = "mp3, wav, видео",
            gradient = BrandGradient,
            onClick = {
                Haptics.confirm(context)
                onPick()
            },
        )
        ActionTile(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Mic,
            title = "Голос",
            subtitle = "записать сейчас",
            gradient = AccentGradient,
            onClick = {
                Haptics.confirm(context)
                onRecord()
            },
        )
    }
}

/**
 * Крупная интерактивная плитка: иконка в градиентном круге, название,
 * подпись. Вся площадь кликабельна, есть визуальный отклик на нажатие.
 */
@Composable
private fun ActionTile(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    gradient: List<Color>,
    onClick: () -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = androidx.compose.animation.core.spring(stiffness = 900f),
        label = "tileScale",
    )

    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
            )
            .padding(vertical = 20.dp, horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(gradient)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpeakersCard(
    context: android.content.Context,
    auto: Boolean,
    count: Int,
    onAutoChange: (Boolean) -> Unit,
    onCountChange: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Сколько человек в записи", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("Определить", auto) {
                    Haptics.tap(context); onAutoChange(true)
                }
                ChoiceChip("Известно", !auto) {
                    Haptics.tap(context); onAutoChange(false)
                }
            }
            if (!auto) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in 1..5) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(
                                    if (count == n) speakerColor("speaker_$n")
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickableNoRipple {
                                    Haptics.tap(context); onCountChange(n)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$n",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (count == n) Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = null,
    )
}

@Composable
private fun ModelsCard(state: AppState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Модели на устройстве", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.modelsReady) "Whisper small · диаризация · пунктуация"
                        else "Готовим при первом запуске",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    text = if (state.modelsReady) "готово" else "распаковка",
                    ok = state.modelsReady,
                )
            }
            if (!state.modelsReady) {
                PulseDots(color = MaterialTheme.colorScheme.secondary)
                Text(
                    state.modelsMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun StatusPill(text: String, ok: Boolean) {
    val tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
    }
}