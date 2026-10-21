package com.vladimir.messenger.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vladimir.messenger.MessengerApplication
import com.vladimir.messenger.R

/**
 * Foreground-service идущего звонка (CALLS_BOOTSTRAP.md, 8.5).
 *
 * Сам звук живёт в CallAudioEngine (которым владеет CallManager) — этот сервис
 * нужен системе как держатель типа "microphone": без него Android с Android 12+
 * глушит микрофон, когда приложение свёрнуто. Стартует CallManager на CONNECTING,
 * гасится на завершении. RECORD_AUDIO должен быть выдан ДО старта (UI/менеджер).
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, MessengerApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("APU")
            .setContentText("Идёт звонок")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        // Демонстрация экрана: Android 14+ требует, чтобы foreground-service с типом
        // mediaProjection уже работал ДО создания проекции, поэтому тип заявляем
        // заранее по extra, когда человек нажал «Экран» и идёт за разрешением системы.
        val types = if (intent?.getBooleanExtra(EXTRA_SCREEN, false) == true) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                types,
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    companion object {
        private const val TAG = "CallService"
        private const val NOTIFICATION_ID = 7002

        fun start(context: Context) {
            val intent = Intent(context, CallService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        private const val EXTRA_SCREEN = "screen"

        /** Поднять сервис с типом mediaProjection до запроса разрешения на захват экрана. */
        fun startForScreenShare(context: Context) {
            val intent = Intent(context, CallService::class.java)
                .putExtra(EXTRA_SCREEN, true)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
