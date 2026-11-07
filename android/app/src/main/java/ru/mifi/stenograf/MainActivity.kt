package ru.mifi.stenograf

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.mifi.stenograf.output.HexJson
import ru.mifi.stenograf.ui.AppState
import ru.mifi.stenograf.ui.ImportScreen
import ru.mifi.stenograf.ui.HistoryScreen
import ru.mifi.stenograf.ui.ProcessingScreen
import ru.mifi.stenograf.ui.RecordScreen
import ru.mifi.stenograf.ui.ResultScreen
import ru.mifi.stenograf.ui.Screen
import ru.mifi.stenograf.ui.StenografTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Отладочный запуск из adb: не требует тапов по файловому менеджеру.
        //   adb shell am start -n ru.mifi.stenograf/.MainActivity         //     -d content://media/external/audio/media/<id> --ei speakers 2 --ez autorun true
        val debugUri = intent?.data
        val autorun = intent?.getBooleanExtra("autorun", false) == true
        val debugSpeakers = if (intent?.hasExtra("speakers") == true) intent.getIntExtra("speakers", 0) else null
        if (debugUri != null && autorun) Log.i("Stenograf", "authorun: uri=$debugUri speakers=$debugSpeakers")
        setContent {
            StenografTheme {
                Scaffold { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        App(autorunUri = if (autorun) debugUri else null, autorunSpeakers = debugSpeakers)
                    }
                }
            }
        }
    }
}

@Composable
private fun App(autorunUri: Uri? = null, autorunSpeakers: Int? = null) {
    val context = LocalContext.current
    val state = remember { AppState(context) }

    // Автозапуск обработки при отладочном intent — ускоряет прогоны с ПК.
    // remember(uri) обязателен: без ключа повторная доставка intent-а
    // (новый тап, возврат в приложение) запускала ВТОРОЙ прогон параллельно,
    // и два пайплайна дрались за память и модели.
    if (autorunUri != null) {
        remember(autorunUri) {
            state.start(autorunUri, autorunUri.lastPathSegment ?: "debug", autorunSpeakers)
            true
        }
    }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val json = HexJson.toJson(state.replicas())
            val errors = HexJson.validate(json)
            val payload = if (errors.isEmpty()) json else buildString {
                appendLine("/* ВНИМАНИЕ: схема нарушения: ${errors.joinToString("; ")} */")
                append(json)
            }
            context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
        }
    }

    val exportText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val text = HexJson.toText(state.replicas())
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
    }

    when (state.screen) {
        Screen.IMPORT -> ImportScreen(
            state = state,
            onPickFile = { uri: Uri, name: String, speakers: Int? -> state.start(uri, name, speakers) },
            onRecord = { state.goRecord() },
            onHistory = { state.openHistory() },
        )
        Screen.RECORD -> RecordScreen(
            state = state,
            onRecorded = { file, speakers -> state.startRecorded(file, speakers) },
        )
        Screen.HISTORY -> HistoryScreen(state)
        Screen.PROCESSING -> ProcessingScreen(state)
        Screen.RESULT -> ResultScreen(
            state = state,
            onExportJson = { exportJson.launch("transcript.json") },
            onExportText = { exportText.launch("transcript.txt") },
        )
        Screen.ERROR -> {
            Box(Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    "Ошибка: ${state.error ?: "неизвестная"}\n\nВернитесь и попробуйте другой файл.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}