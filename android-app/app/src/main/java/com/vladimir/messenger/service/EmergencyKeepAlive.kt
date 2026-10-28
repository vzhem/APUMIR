package com.vladimir.messenger.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.vladimir.messenger.data.diagnostics.OperationalCounter
import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics

/**
 * Пробуждение доставки после того, как система или человек закрыли приложение.
 *
 * Задача владельца (2026-10-28): «сделать как в Телеграмме — если убило
 * приложение, оно понимает и просыпается». У Telegram письма будит сервер
 * системным push-сообщением. Здесь сервера нет, поэтому «толчок» даёт наше
 * собственное расписание, и оно знает о своём состоянии:
 *
 *  • служба жива — тихий сторож раз в [DeliveryWakePolicy.WATCHDOG_MS]
 *    поднимает её, если её убили без `onDestroy` (без этого «убили» означало
 *    «молчим, пока человек сам не откроет приложение»);
 *  • служба уходит сама (`onDestroy`) или человек смахнул приложение из
 *    списка задач (`onTaskRemoved`) — приложение ЗНАЕТ это и назначает
 *    быстрый подъём через [DeliveryWakePolicy.FAST_WAKE_MS];
 *  • подняться не удалось — лестница [DeliveryWakePolicy.RETRY_LADDER_MS].
 *
 * Решение (интервал, точность) считает [DeliveryWakePolicy]; здесь только
 * AlarmManager и проверка «а жива ли служба на самом деле». Проверка важна:
 * будильник может сработать в момент, когда служба уже поднялась сама, и
 * второй экземпляр поверх живого не нужен.
 *
 * Отдельно про точность. `setExactAndAllowWhileIdle` — самый дорогой по
 * батарее механизм Android, поэтому он включается только когда телефон
 * действительно разрешает приложению работу в фоне (см. [DeliveryWakePolicy.canBeExact]).
 * Если разрешений нет, будильник будет неточным, и экран «Уведомления
 * приходят вовремя» честно скажет, чего не хватает.
 */
class EmergencyKeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (!isOwnAction(action)) {
            Log.w(TAG, "emergency receiver ignored unexpected action=" + (action ?: "null"))
            return
        }
        OperationalDiagnostics.tracker.count(OperationalCounter.DELIVERY_WAKE_ALARM)
        // Короткий карман бодрости: дать связи и помпам докрутиться, пока
        // система не усыпила процесс обратно.
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "APU:DeliveryWake").acquire(20_000L)
        }
        if (!DeliveryAssurance.isEnabled(context)) {
            EmergencyKeepAlive.cancel(context)
            return
        }
        val status = DeliveryAssurance.status(context)
        val now = System.currentTimeMillis()
        if (!DeliveryWakePolicy.shouldWakeNow(status.conditions, status.lastAliveMs, now)) {
            // Служба жива (метка свежая) — поднимать второй экземпляр незачем.
            // Но следующий сторож обязателен: будильник одноразовый.
            Log.i(TAG, "wake skipped: service is alive, next watchdog scheduled")
            EmergencyKeepAlive.scheduleWatchdog(context)
            return
        }
        DeliveryAssurance.noteWake(context, now)
        OperationalDiagnostics.tracker.count(OperationalCounter.DELIVERY_WAKE_START)
        if (DeliveryAssurance.startService(context)) {
            // Успешный старт сам перевзведёт сторож из onStartCommand.
            Log.i(TAG, "service woken after " + describeDelay(status.lastAliveMs, now))
            OperationalDiagnostics.recordWake(status.lastAliveMs, now)
        } else {
            // Android 12+ запрещает старт foreground-сервиса из фона без
            // исключения из оптимизации батареи. Отказ виден человеку на
            // экране «Уведомления приходят вовремя» и уходит в лестницу.
            OperationalDiagnostics.tracker.count(OperationalCounter.FOREGROUND_START_REFUSED)
            EmergencyKeepAlive.onServiceRefused(context)
        }
    }

    private fun describeDelay(lastAliveMs: Long, nowMs: Long): String {
        if (lastAliveMs <= 0L) return "first start"
        val minutes = ((nowMs - lastAliveMs) / 60_000L).coerceAtLeast(0L)
        return if (minutes >= 1) "after ${minutes} min without the service" else "within a minute"
    }

    companion object {
        private const val TAG = "EmergencyKeepAlive"

        /**
         * Какие действия принимает приёмник.
         *
         * Приёмник объявлен `exported="true"`: на части прошивок только так
         * доходит BOOT_COMPLETED. Значит, постороннее приложение тоже может
         * прислать Intent — поэтому allowlist, а не «любое действие».
         * `MY_PACKAGE_REPLACED` нужен после обновления APK: служба не
         * переживает замену пакета, и без этого письма не приходили бы до
         * первого открытия приложения.
         *
         * `LOCKED_BOOT_COMPLETED` здесь нет намеренно (и его нет в манифесте):
         * до первой разблокировки телефон закрывает личность и настройки —
         * расшифровывать письма нечем, а чтение этих настроек до разблокировки
         * упало бы. Доставка начинается с BOOT_COMPLETED.
         */
        // Имена действий записаны строками, а не через Intent.ACTION_*: так
        // allowlist проверяется JVM-тестом без Android и не зависит от того,
        // подставил ли компилятор значение системной константы.
        private const val ACTION_BOOT = "android.intent.action.BOOT_COMPLETED"
        private const val ACTION_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

        fun isOwnAction(action: String?): Boolean =
            action == EmergencyKeepAlive.ACTION_PUMP ||
                action == ACTION_BOOT ||
                action == ACTION_PACKAGE_REPLACED
    }
}

/** Планирование пробуждений. Отдельно от приёмника — проверяется JVM-тестом. */
object EmergencyKeepAlive {

    private const val TAG = "EmergencyKeepAlive"
    const val ACTION_PUMP = "com.vladimir.messenger.EMERGENCY_PUMP"
    private const val REQUEST_CODE = 1001

    private const val PREFS = "emergency_keep_alive"
    private const val KEY_REFUSALS = "consecutive_refusals"

    /**
     * Служба стартовала и работает: отметить жизнь и взвести тихий сторож.
     * Зовётся из [CoreServerService.onStartCommand] после успешного
     * `startForeground` — но только когда это ручной/системный запуск, а не
     * пробуждение по будильнику (см. [scheduleWatchdog]).
     */
    fun onServiceStarted(context: Context) {
        DeliveryAssurance.markAlive(context)
        refusals(context, 0)
        scheduleWatchdog(context)
    }

    /**
     * Служба уходит (onDestroy). Если это не осознанный перезапуск, приложение
     * знает, что его «убили», и назначает быстрый подъём: именно этого не
     * хватало — раньше следующая попытка была через час.
     */
    fun onServiceStopped(context: Context, intentional: Boolean) {
        if (!DeliveryAssurance.isEnabled(context)) {
            cancel(context)
            return
        }
        if (intentional) {
            // Служба остановилась сама и знает причину. Частый подъём здесь
            // вреден (причина не исчезнет за минуты, вышел бы цикл), но и
            // совсем не будить нельзя: человек не должен остаться без писем
            // до первого открытия приложения.
            applyPlan(context, DeliveryWakePolicy.afterPlannedStop(DeliveryAssurance.conditions(context)))
            return
        }
        applyPlan(context, DeliveryWakePolicy.serviceGone(DeliveryAssurance.conditions(context)))
    }

    /** Человек смахнул приложение из списка задач: поднимаемся быстро. */
    fun onTaskRemoved(context: Context) {
        if (!DeliveryAssurance.isEnabled(context)) return
        applyPlan(context, DeliveryWakePolicy.taskRemoved(DeliveryAssurance.conditions(context)))
    }

    /**
     * Поднять службу не удалось (отказ `startForeground`, таймаут foreground,
     * запрет старта из фона): следующая попытка по лестнице 1 → 5 → 15 → 30.
     * Здесь настойчивость оправдана — письма ждут.
     */
    fun onServiceRefused(context: Context) {
        if (!DeliveryAssurance.isEnabled(context)) {
            cancel(context)
            return
        }
        val misses = refusals(context, -1) + 1
        refusals(context, misses)
        val plan = DeliveryWakePolicy.afterRefusal(DeliveryAssurance.conditions(context), misses)
        Log.i(TAG, "reschedule after refusal #$misses: " + describe(plan))
        applyPlan(context, plan)
    }

    /**
     * Взвести тихий сторож, пока служба жива. Публично, потому что приёмник
     * перевзводит его после каждого срабатывания: будильник одноразовый.
     */
    fun scheduleWatchdog(context: Context) {
        applyPlan(context, DeliveryWakePolicy.whileAlive(DeliveryAssurance.conditions(context)))
    }

    /** Снять будильник (человек выключил доставку — не будить зря). */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
        refusals(context, 0)
    }

    private fun applyPlan(context: Context, plan: DeliveryWakePolicy.Plan) {
        val delay = plan.delayMs
        if (delay == null) {
            // Политика сказала «не будить»: снимаем прежний будильник, иначе
            // выключенная доставка продолжала бы поднимать службу.
            cancel(context)
            Log.i(TAG, "no wake scheduled: ${plan.reason}")
            return
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        val at = System.currentTimeMillis() + delay
        try {
            if (plan.exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            // Точный будильник могли отобрать в настройках уже после проверки.
            // Неточный всё равно разбудит в окне обслуживания Doze — это
            // честная деградация, а не отказ.
            Log.w(TAG, "exact alarm failed, falling back to inexact: " + e.message)
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
        Log.i(TAG, "wake scheduled: " + describe(plan))
    }

    private fun describe(plan: DeliveryWakePolicy.Plan): String {
        val minutes = (plan.delayMs ?: 0L) / 60_000L
        val mode = if (plan.exact) "exact" else "inexact"
        return "in $minutes min ($mode), reason=${plan.reason}"
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, EmergencyKeepAliveReceiver::class.java).setAction(ACTION_PUMP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Счётчик подряд идущих отказов. [delta] < 0 — «прочитать и ничего не
     * менять», иначе записать новое значение и вернуть его.
     */
    private fun refusals(context: Context, delta: Int): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getInt(KEY_REFUSALS, 0)
        if (delta < 0) return current
        if (current != delta) prefs.edit().putInt(KEY_REFUSALS, delta).apply()
        return delta
    }

    /** Сколько подряд идущих отказов запомнено (для диагностики и тестов). */
    fun refusalsForDiagnostics(context: Context): Int = refusals(context, -1)

    /** Есть ли уже право на точные будильники — для диагностики. */
    fun exactAlarmsUsable(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || DeliveryAssurance.exactAlarmsAllowed(context)
}
