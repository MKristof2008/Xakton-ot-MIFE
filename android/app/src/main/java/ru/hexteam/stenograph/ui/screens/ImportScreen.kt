package ru.hexteam.stenograph.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.hexteam.stenograph.ui.AppViewModel
import java.io.File

/**
 * Экран 1: импорт аудио. Спецификация §3.1.
 * «Выбрать файл» через Storage Access Framework (без разрешений на хранилище)
 * и «Записать с микрофона» (разрешение RECORD_AUDIO, файл во внутренней памяти).
 */
@Composable
fun ImportScreen(state: AppViewModel.UiState, vm: AppViewModel) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordFile by remember { mutableStateOf<File?>(null) }
    var micError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "аудио"
            vm.onAudioPicked(uri, name)
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            micError = "Без разрешения на микрофон запись невозможна"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recording?.runCatching { stop(); release() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(
                text = "Нейро-стенографист",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Локально, без интернета",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.usingStubs) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    text = "DEBUG FAKE: движки-заглушки, реальные модели не подключены. " +
                        "Интерфейс и экспорт работают полностью.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Зона выбранного файла
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (state.audioUri == null) {
                    Text(
                        text = "Аудиофайл не выбран",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "mp3, wav, m4a, ogg, aac",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(state.audioName, style = MaterialTheme.typography.bodyLarge)
                }
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
        }
        micError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Восстановление пунктуации", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = state.useLlm, onCheckedChange = { vm.setUseLlm(it) })
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { picker.launch(arrayOf("audio/*")) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(13.dp),
        ) {
            Text(if (state.audioUri == null) "Выбрать файл" else "Выбрать другой файл")
        }

        OutlinedButton(
            onClick = {
                micError = null
                if (recording != null) {
                    // Остановить запись и передать файл в обработку
                    recording?.runCatching { stop(); release() }
                    recording = null
                    recordFile?.let { file ->
                        vm.onAudioPicked(Uri.fromFile(file), file.name)
                    }
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        return@OutlinedButton
                    }
                    val file = File(context.cacheDir, "recording_${System.currentTimeMillis()}.m4a")
                    val recorder = MediaRecorder(context).apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setOutputFile(file.absolutePath)
                        prepare()
                        start()
                    }
                    recording = recorder
                    recordFile = file
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(13.dp),
        ) {
            Text(if (recording != null) "Остановить запись" else "Записать с микрофона")
        }

        Button(
            onClick = { vm.process() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(13.dp),
            enabled = state.audioUri != null,
        ) {
            Text("Обработать")
        }
    }
}
