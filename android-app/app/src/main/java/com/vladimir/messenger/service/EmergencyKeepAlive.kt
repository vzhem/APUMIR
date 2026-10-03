package com.vladimir.messenger.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

/**
 * Раунд 141: аварийный каркас доставки уведомлений (просьба владельца:
 * «уведомления должны пробиваться в любом случае, хоть через спящее,
 * хоть через убитое приложение; если убили - приложение должно
 * запуститься через 5 минут в аварийном режиме хотя бы на самых
 * минималках и сразу сообщить»).
 *
 * Как работает: системный будильник звонит раз в 5 минут, приёмник
 * поднимает CoreServerService (это и есть «минимальный режим» - MQTT
 * связь и опрос событий). Письмо доложится, уведомление показывается
 * сразу; тап по нему открывает приложение полностью. Цепочка
 * самоподдерживающаяся: сервис при КАЖДОМ старте перевзводит будильник,
 * будильник стартует сервис - даже если процесс убили, через <=5 минут
 * он оживёт сам. Переживает перезагрузку телефона (BOOT_COMPLETED).
 *
 * Будильник - точный (setExactAndAllowWhileIdle), звенит даже в Doze;
 * без права на точный - неточный запасной (в окне обслуживания Doze).
 */
class EmergencyKeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Короткий карман бодрости (20 секунд): дать MQTT-связи и помпам
        // докрутиться, пока система не усыпила процесс обратно.
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "APU:EmergencyPump")
                .acquire(20_000L)
        }
        val service = Intent(context, CoreServerService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        }
    }

    companion object {
        private const val ACTION_PUMP = "com.vladimir.messenger.EMERGENCY_PUMP"
        private const val REQUEST_CODE = 1001

        /** Интервал аварийного пробуждения: 5 минут (просьба владельца). */
        private const val PUMP_INTERVAL_MS = 5 * 60_000L

        /**
         * Взвести следующий аварийный будильник. Дёшево и идемпотентно
         * (FLAG_UPDATE_CURRENT) - зовётся при каждом старте сервиса.
         */
        fun scheduleNext(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, EmergencyKeepAliveReceiver::class.java).setAction(ACTION_PUMP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = System.currentTimeMillis() + PUMP_INTERVAL_MS
            val exactAllowed = Build.VERSION.SDK_INT < 31 ||
                runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
            try {
                if (exactAllowed) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    // Точный запрещён (настройка телефона): неточный в Doze
                    // зазвонит в окне обслуживания - медленнее, но надёжно.
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } catch (_: Exception) {
                runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            }
        }
    }
}
