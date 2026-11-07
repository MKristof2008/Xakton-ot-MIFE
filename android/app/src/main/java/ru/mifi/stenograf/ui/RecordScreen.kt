package ru.mifi.stenograf.ui

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.mifi.stenograf.audio.VoiceRecorder
import ru.mifi.stenograf.output.Replica
import kotlin.math.abs
import kotlin.math.sin

/**
 * Запись голоса из приложения — как голосовое в мессенджере:
 * одно большое кольцо-кнопка, вокруг живой индикатор уровня, таймер.
 */
@Composable
fun RecordScreen(
    state: AppState,
    onRecorded: (java.io.File, Int?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var level by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var recordFile by remember { mutableStateOf<java.io.File?>(null) }

    val recorder = remember { VoiceRecorder(context) }
    val recordScope = rememberCoroutineScope()

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) error = "Без доступа к микрофону запись невозможна"
    }

    // Запрос разрешения при входе на экран.
    LaunchedEffect(Unit) {
        if (!VoiceRecorder.hasPermission(context)) permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun start() {
        val file = java.io.File(context.filesDir, "voice_${System.currentTimeMillis()}.wav")
        error = null
        recording = true
        // Запись идёт в IO-корутине; таймер и уровень обновляются из колбэка.
        recordFile = file
        recordScope.launch {
            runCatching {
                recorder.recordTo(file) { ms ->
                    elapsedMs = ms
                    level = recorder.amplitude()
                }
            }.onFailure { e ->
                error = e.message
            }
            recording = false
        }
    }

    fun stop() {
        recorder.stop()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (recording) "Идёт запись" else "Записать голос",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (recording) "Нажмите, чтобы остановить" else "Говорите — текст появится сам",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))

        RecordButton(
            recording = recording,
            level = level,
            onClick = {
                if (recording) {
                    // Файл читаем ПОСЛЕ того, как корутина записи завершится:
                    // заголовок WAV дописывается только в самом конце, а пока
                    // запись идёт, первые 44 байта — нули. Поэтому сначала
                    // stop(), потом дожидаемся flush(), потом отдаём файл.
                    stop()
                    recordFile?.let { file ->
                        recordScope.launch {
                            // Ждём, пока recordTo вернётся и допишет RIFF-заголовок.
                            withTimeoutOrNull(3000) { recorder.awaitFinished() }
                            val ready = java.io.File(context.filesDir, "latest_voice.wav")
                            runCatching { file.copyTo(ready, overwrite = true) }
                                .onSuccess { onRecorded(ready, 1) }
                                .onFailure { error = "Не удалось сохранить запись: ${it.message}" }
                        }
                    }
                } else {
                    start()
                }
            },
        )

        Spacer(Modifier.height(28.dp))
        Text(
            Replica.timecode(elapsedMs.toInt()),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = if (recording) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (recording) "запись" else "готово к записи",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        error?.let {
            Spacer(Modifier.height(20.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(36.dp))
        OutlinedButton(
            onClick = { state.backToImport() },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Назад") }
    }
}

/**
 * Кнопка записи: пульсирующее кольцо по уровню сигнала, внутри — микрофон
 * или стоп. Кольцо реагирует на громкость, поэтому видно, что микрофон живой.
 */
@Composable
private fun RecordButton(recording: Boolean, level: Float, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "ring")
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "breathe",
    )
    val smoothLevel by animateFloatAsState(level, tween(120), label = "level")

    val ringColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            // 3 концентрических кольца: внешние реагируют на громкость сильнее.
            for (i in 0..2) {
                val base = size.minDimension * (0.30f + i * 0.10f)
                val pulse = if (recording) smoothLevel * size.minDimension * 0.05f else breathe * 6.dp.toPx()
                drawCircle(
                    color = ringColor.copy(alpha = (0.22f - i * 0.06f)),
                    radius = base + pulse,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                )
            }
        }
        Button(
            onClick = onClick,
            modifier = Modifier.size(128.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (recording) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            if (recording) {
                Icon(Icons.Filled.Stop, contentDescription = "Остановить", modifier = Modifier.size(44.dp))
            } else {
                Icon(Icons.Filled.Mic, contentDescription = "Записать", modifier = Modifier.size(48.dp))
            }
        }
    }
}