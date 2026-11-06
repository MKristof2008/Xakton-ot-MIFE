package ru.mifi.stenograf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Стартовый экран: импорт аудио, выбор числа спикеров, статус моделей.
 * Всё локально — экран не содержит ни одного сетевого действия.
 */
@Composable
fun ImportScreen(
    state: AppState,
    onPickFile: (Uri, String, Int?) -> Unit,
) {
    var speakersAuto by remember { mutableStateOf(true) }
    var speakersCount by remember { mutableStateOf(2) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onPickFile(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "аудио", if (speakersAuto) null else speakersCount)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Нейро-стенографист", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Локальная расшифровка диалогов: распознавание речи, определение говорящих и таймкоды. " +
                "Работает полностью офлайн — приложение не запрашивает доступ в интернет.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. Аудио", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Выберите запись из памяти устройства. Поддерживаются mp3, wav, m4a, ogg, flac и видеофайлы.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Button(
                    onClick = { picker.launch(arrayOf("audio/*", "video/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Выбрать аудиофайл") }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("2. Сколько говорят", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = speakersAuto,
                        onClick = { speakersAuto = true },
                        label = { Text("Авто") },
                    )
                    FilterChip(
                        selected = !speakersAuto,
                        onClick = { speakersAuto = false },
                        label = { Text("Указать") },
                    )
                }
                if (!speakersAuto) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (n in 2..5) {
                            FilterChip(
                                selected = speakersCount == n,
                                onClick = { speakersCount = n },
                                label = { Text("$n") },
                            )
                        }
                    }
                }
                Text(
                    "Если число известно — укажите: это заметно повышает точность разделения.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        ModelsCard(state)

        Spacer(Modifier.height(8.dp))
        Text(
            "Результат: расшифровка с таймкодами и экспорт в JSON по формату задания.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun ModelsCard(state: AppState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("3. Модели", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .background(
                            if (state.modelsReady) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        if (state.modelsReady) "готовы" else "не установлены",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                state.modelsMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(min = 0.dp),
            )
            if (!state.modelsReady) {
                OutlinedButton(onClick = { state.installModels() }) { Text("Распаковать модели") }
            }
        }
    }
}