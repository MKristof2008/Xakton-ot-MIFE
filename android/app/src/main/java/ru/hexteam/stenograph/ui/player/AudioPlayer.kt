package ru.hexteam.stenograph.ui.player

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Простое воспроизведение выбранной записи: play/pause, переход к миллисекунде,
 * текущая позиция потоком для подсветки реплики.
 *
 * Живёт в пределах экрана результата; [release] вызывается из DisposableEffect.
 */
class AudioPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    data class PlayerState(
        val isReady: Boolean = false,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
    )

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /** Подготовить источник. Повторный вызов заменяет источник. */
    fun load(uri: Uri) {
        releasePlayer()
        runCatching {
            MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()
                player = this
                _state.value = PlayerState(isReady = true, durationMs = duration.toLong())
                setOnCompletionListener {
                    _state.value = _state.value.copy(isPlaying = false, positionMs = 0L)
                }
            }
        }
    }

    fun playPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            stopTicker()
            _state.value = _state.value.copy(isPlaying = false)
        } else {
            p.start()
            _state.value = _state.value.copy(isPlaying = true)
            startTicker()
        }
    }

    /** Переход к позиции (тап по реплике → её start_ms) и воспроизведение. */
    fun seekTo(ms: Long) {
        val p = player ?: return
        p.seekTo(ms.coerceIn(0L, _state.value.durationMs).toInt())
        _state.value = _state.value.copy(positionMs = ms)
        if (!p.isPlaying) {
            p.start()
            _state.value = _state.value.copy(isPlaying = true)
            startTicker()
        }
    }

    fun release() {
        stopTicker()
        releasePlayer()
        _state.value = PlayerState()
    }

    private fun releasePlayer() {
        player?.runCatching { if (isPlaying) stop(); reset(); release() }
        player = null
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive) {
                player?.let {
                    _state.value = _state.value.copy(positionMs = it.currentPosition.toLong())
                }
                delay(200)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }
}
