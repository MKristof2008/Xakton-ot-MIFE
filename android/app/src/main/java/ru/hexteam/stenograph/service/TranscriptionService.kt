package ru.hexteam.stenograph.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ru.hexteam.stenograph.MainActivity

/**
 * Foreground service: держит процесс живым, пока идёт обработка аудио.
 *
 * Не выполняет вычисления сам — [ru.hexteam.stenograph.ui.AppViewModel]
 * запускает pipeline в viewModelScope; сервис только не даёт системе убить
 * приложение при сворачивании и показывает уведомление с этапом.
 *
 * Тип `dataSync` разрешён без дополнительных разрешений с Android 10.
 */
class TranscriptionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val stage = intent.getStringExtra(EXTRA_STAGE) ?: "Обработка записи"
                startForegroundWithNotification(stage)
            }
            ACTION_UPDATE -> {
                val stage = intent.getStringExtra(EXTRA_STAGE) ?: return START_STICKY
                updateNotification(stage)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startForegroundWithNotification(stage: String) {
        createChannel()
        val notification = buildNotification(stage)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(stage: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(stage))
    }

    private fun buildNotification(stage: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Нейро-стенографист")
            .setContentText(stage)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Обработка записей",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Прогресс распознавания аудио" }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "transcription"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ru.hexteam.stenograph.action.START"
        const val ACTION_UPDATE = "ru.hexteam.stenograph.action.UPDATE"
        const val ACTION_STOP = "ru.hexteam.stenograph.action.STOP"
        const val EXTRA_STAGE = "stage"

        fun start(context: Context, stage: String) {
            val intent = Intent(context, TranscriptionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STAGE, stage)
            }
            context.startForegroundService(intent)
        }

        fun update(context: Context, stage: String) {
            val intent = Intent(context, TranscriptionService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_STAGE, stage)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TranscriptionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
