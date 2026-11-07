package ru.mifi.stenograf.audio

import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Плеер записи прямо на экране расшифровки.
 *
 * Зачем свой, а не системный: нужно два действия, которых у системного плеера
 * нет — проиграть запись с конкретного таймкода реплики (тап по реплике)
 * и подсветить, какая реплика звучит сейчас.
 */
class AudioPlayback {

    private var player: MediaPlayer? = null

    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0)
        private set
    var durationMs by mutableStateOf(0)
        private set
    var path by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Готовит файл. Повторный вызов с тем же путём ничего не делает. */
    fun prepare(newPath: String) {
        if (path == newPath && player != null) return
        release()
        path = newPath
        runCatching {
            val mp = MediaPlayer()
            mp.setDataSource(newPath)
            mp.setOnPreparedListener {
                durationMs = it.duration.coerceAtLeast(0)
            }
            mp.setOnCompletionListener {
                isPlaying = false
                positionMs = durationMs
            }
            mp.setOnErrorListener { _, what, extra ->
                error = "Ошибка воспроизведения ($what/$extra)"
                isPlaying = false
                true
            }
            mp.prepare()
            durationMs = mp.duration.coerceAtLeast(0)
            player = mp
        }.onFailure { e ->
            error = "Не удалось открыть запись: ${e.message}"
            player = null
        }
    }

    /** Начать воспроизведение с миллисекунды [fromMs]. */
    fun play(fromMs: Int = positionMs) {
        val mp = player ?: return
        runCatching {
            mp.seekTo(fromMs.coerceIn(0, durationMs))
            positionMs = fromMs
            mp.start()
            isPlaying = true
            error = null
        }
    }

    /** Переключить: играет — пауза, стоит — играть. */
    fun toggle(fromMs: Int = positionMs) {
        if (isPlaying) pause() else play(fromMs)
    }

    fun pause() {
        runCatching {
            player?.takeIf { it.isPlaying }?.pause()
            positionMs = player?.currentPosition ?: positionMs
        }
        isPlaying = false
    }

    /** Проиграть фрагмент реплики: переход по таймкоду (требование UX из ТЗ). */
    fun playFrom(ms: Int) {
        play(ms)
    }

    /** Обновляет позицию; вызывать по таймеру с экрана. */
    fun syncPosition() {
        val mp = player ?: return
        if (isPlaying) positionMs = runCatching { mp.currentPosition }.getOrDefault(positionMs)
    }

    fun stop() {
        runCatching { player?.pause(); player?.seekTo(0) }
        isPlaying = false
        positionMs = 0
    }

    fun release() {
        runCatching { player?.release() }
        player = null
        isPlaying = false
        durationMs = 0
        positionMs = 0
    }
}