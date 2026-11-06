package ru.mifi.stenograf

import android.net.Uri
import android.os.Bundle
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
import ru.mifi.stenograf.ui.ProcessingScreen
import ru.mifi.stenograf.ui.ResultScreen
import ru.mifi.stenograf.ui.Screen
import ru.mifi.stenograf.ui.StenografTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StenografTheme {
                Scaffold { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        App()
                    }
                }
            }
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val state = remember { AppState(context) }

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
        )
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