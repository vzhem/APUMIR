package com.vladimir.messenger.service

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * «Уведомления приходят вовремя»: состояние разрешений, отметка «служба жива»
 * и переходы в системные настройки.
 *
 * Решение «когда будить» живёт в [DeliveryWakePolicy] (чистая арифметика,
 * покрыта тестом). Здесь — только Android: спросить систему о разрешениях,
 * записать метку жизни, открыть нужный экран настроек, поднять службу.
 *
 * Почему это отдельный объект, а не код в `CoreServerService`: тот уже за
 * потолком god-файла (AGENTS.md п. 8), и новый код туда не пишется. Заодно
 * экран настроек и сторожевой работник читают одно и то же состояние, а не
 * каждый своё.
 */
object DeliveryAssurance {

    private const val TAG = "DeliveryAssurance"

    /** Настройки доставки: не секрет и не история, обычный SharedPreferences. */
    private const val PREFS = "apu_delivery_assurance"
    private const val KEY_WAKE_ENABLED = "wake_enabled"
    private const val KEY_LAST_ALIVE_MS = "last_alive_ms"
    private const val KEY_LAST_WAKE_MS = "last_wake_ms"
    private const val KEY_WAKES = "wakes_count"

    /** Меченое «служба жива» пишем не чаще раза в пять минут: это диск. */
    private const val HEARTBEAT_EVERY_MS = 5 * 60_000L

    /** Пункт, который человек может разрешить. */
    enum class Item { NOTIFICATIONS, BATTERY, EXACT_ALARMS, BACKGROUND }

    /** Что показать на экране «Уведомления приходят вовремя». */
    data class Status(
        val enabled: Boolean,
        val notificationsAllowed: Boolean,
        val batteryUnrestricted: Boolean,
        val exactAlarmsAllowed: Boolean,
        val lastAliveMs: Long,
        val wakes: Int,
    ) {
        val conditions: DeliveryWakePolicy.Conditions
            get() = DeliveryWakePolicy.Conditions(
                enabled = enabled,
                batteryUnrestricted = batteryUnrestricted,
                exactAlarmsAllowed = exactAlarmsAllowed,
                notificationsAllowed = notificationsAllowed,
            )

        /**
         * Что мешает доставке вовремя — по порядку важности.
         *
         * «Работа в фоне» (автозапуск) нельзя проверить из кода: на части
         * прошивок это отдельный переключатель в системных настройках
         * приложения. Поэтому он показывается подсказкой, а не пунктом
         * «не разрешено» - иначе экран вечно выглядел бы сломанным.
         */
        val blocking: List<Item>
            get() = buildList {
                if (!notificationsAllowed) add(Item.NOTIFICATIONS)
                if (enabled && !batteryUnrestricted) add(Item.BATTERY)
                if (enabled && !exactAlarmsAllowed) add(Item.EXACT_ALARMS)
            }

        /** Всё ли готово, чтобы письмо будило телефон без задержки в час. */
        val onTime: Boolean
            get() = enabled && notificationsAllowed && batteryUnrestricted && exactAlarmsAllowed
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAKE_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE_ENABLED, enabled).apply()
        if (enabled) {
            // Сразу привести расписание в соответствие с новым решением:
            // выключили - будильник снимается, включили - взводится заново по
            // текущим разрешениям. Метку жизни здесь НЕ ставим: службы может
            // не быть, и «жива» на 45 минут вперёд было бы враньём.
            EmergencyKeepAlive.scheduleWatchdog(context)
        } else {
            EmergencyKeepAlive.cancel(context)
        }
    }

    /** Полное состояние для экрана настроек и сторожа. */
    fun status(context: Context): Status = Status(
        enabled = isEnabled(context),
        notificationsAllowed = notificationsAllowed(context),
        batteryUnrestricted = batteryUnrestricted(context),
        exactAlarmsAllowed = exactAlarmsAllowed(context),
        lastAliveMs = lastAliveMs(context),
        wakes = prefs(context).getInt(KEY_WAKES, 0),
    )

    /** Условия для политики: читаются без обращения к диску сверх prefs. */
    fun conditions(context: Context): DeliveryWakePolicy.Conditions = status(context).conditions

    fun notificationsAllowed(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return if (Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun batteryUnrestricted(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    fun exactAlarmsAllowed(context: Context): Boolean {
        // До Android 12 точные будильники разрешены всем; на 12+ их может
        // отобрать настройка телефона, и тогда setExact бросает исключение.
        if (Build.VERSION.SDK_INT < 31) return true
        return runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        }.getOrDefault(false)
    }

    /**
     * Отметить, что служба жива. Пишется не чаще раза в
     * [HEARTBEAT_EVERY_MS]: сторож читает метку, а не сам факт вызова.
     */
    fun markAlive(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        val last = prefs.getLong(KEY_LAST_ALIVE_MS, 0L)
        if (last > 0L && nowMs - last in 0 until HEARTBEAT_EVERY_MS) return
        prefs.edit().putLong(KEY_LAST_ALIVE_MS, nowMs).apply()
    }

    fun lastAliveMs(context: Context): Long = prefs(context).getLong(KEY_LAST_ALIVE_MS, 0L)

    /** Записать попытку пробуждения: число видно в настройках и диагностике. */
    fun noteWake(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        prefs.edit()
            .putLong(KEY_LAST_WAKE_MS, nowMs)
            .putInt(KEY_WAKES, (prefs.getInt(KEY_WAKES, 0) + 1).coerceAtMost(1_000_000))
            .apply()
    }

    /**
     * Поднять службу доставки. Возвращает false, если система запретила старт
     * из фона (Android 12+); причину пишет в журнал вызывающий.
     */
    fun startService(context: Context): Boolean = try {
        val intent = Intent(context, CoreServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "service start refused (sdk=" + Build.VERSION.SDK_INT + "): " + e.message)
        false
    }

    // ---------------------------------------------------------------- экраны

    /**
     * Открыть системный экран для пункта [item]. Все переходы обёрнуты:
     * на части прошивок «правильного» экрана нет, и падение активности из-за
     * отсутствующей настройки выглядело бы как поломка приложения.
     */
    fun openSystemSettings(context: Context, item: Item): Boolean = runCatching {
        val intent = when (item) {
            Item.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            Item.BATTERY ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:" + context.packageName))
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
            Item.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= 31) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:" + context.packageName))
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:" + context.packageName))
                }
            // Автозапуск: единого системного экрана нет, ведём туда, где на
            // большинстве прошивок живут «батарея» и «запуск в фоне».
            Item.BACKGROUND -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + context.packageName))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.onFailure { error ->
        Log.w(TAG, "system settings for $item unavailable: " + error.message)
    }.getOrDefault(false)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
