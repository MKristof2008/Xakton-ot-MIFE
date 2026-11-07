package ru.hexteam.stenograph.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.hexteam.stenograph.core.Merge
import ru.hexteam.stenograph.core.PipelineStage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "аудио"
            vm.onAudioPicked(uri, name)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Нейро-стенографист") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // Импорт аудио
            OutlinedButton(
                onClick = { picker.launch(arrayOf("audio/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy,
            ) {
                Text(if (state.audioUri == null) "Выбрать аудиофайл" else "Выбрать другой файл")
            }
            if (state.audioName.isNotEmpty()) {
                Text(
                    text = "Файл: ${state.audioName}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Настройки
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Восстановление пунктуации", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.useLlm,
                    onCheckedChange = { vm.setUseLlm(it) },
                    enabled = !state.isBusy,
                )
            }

            // Запуск
            Button(
                onClick = { vm.process() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.audioUri != null && !state.isBusy,
            ) {
                Text(if (state.isBusy) "Обработка…" else "Распознать")
            }

            // Прогресс
            if (state.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(state.stage.title, style = MaterialTheme.typography.bodySmall)
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
            }

            // Результат
            if (state.hasResult) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Реплик: ${state.lines.size}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "RTF %.2f".format(state.rtf),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { shareText(context, state.json, "transcript.json") }) {
                        Text("Экспорт JSON")
                    }
                    OutlinedButton(onClick = {
                        val text = vm.plainText()
                        shareText(context, text, "transcript.txt")
                    }) {
                        Text("Экспорт TXT")
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.lines) { line ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "${Merge.timecode(line.startMs)} " +
                                        Merge.humanize(line.speakerId),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(line.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            } else if (state.stage == PipelineStage.ASR && !state.isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                }
            }

            if (state.stage == PipelineStage.DONE && !state.hasResult) {
                Text(
                    text = "Речь не распознана: в записи нет сегментов.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state.stage == PipelineStage.DONE) {
                Text(
                    text = "Готово. Результат можно экспортировать.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Открывает системное «Поделиться» — так результат уходит в файл или мессенджер. */
private fun shareText(context: android.content.Context, text: String, fileName: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (fileName.endsWith(".json")) "application/json" else "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
    }
    context.startActivity(Intent.createChooser(intent, "Экспорт расшифровки"))
}
