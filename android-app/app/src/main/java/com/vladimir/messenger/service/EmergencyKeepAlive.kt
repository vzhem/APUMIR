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
 * Аварийный каркас доставки: если система убила процесс, уведомления всё равно
 * должны пробиться (просьба владельца, раунд 141).
 *
 * Что изменилось 2026-10-26 (P1-6 аудита). Раньше сервис при КАЖДОМ старте
 * перевзводил точный будильник «+5 минут» — 288 пробуждений в сутки, всегда,
 * даже когда всё работает. `setExactAndAllowWhileIdle` в Doze — самый дорогой
 * по батарее механизм Android. Теперь два разных сценария:
 *
 *  - сервис жив ([onServiceStarted]) — одна страховка в час НЕТОЧНЫМ
 *    будильником (`setAndAllowWhileIdle`): если процесс убьют без колбэка,
 *    сервис вернётся в течение часа, а не через 288 лишних пробуждений;
 *  - старт не удался ([onServiceRefused]) — вот здесь нужна настойчивость:
 *    точные будильники по лестнице 5 → 15 → 30 → 60 минут, счётчик отказов
 *    в SharedPreferences, чтобы после перезагрузки не начинать лестницу
 *    заново с пяти минут.
 *
 * Приёмник объявлен `exported="true"` (BOOT_COMPLETED доходит не на всех
 * прошивках), поэтому в `onReceive` принимаются только два своих действия —
 * постороннее приложение не поднимет наш foreground-сервис.
 */
class EmergencyKeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != EmergencyKeepAlive.ACTION_PUMP && action != Intent.ACTION_BOOT_COMPLETED) {
            Log.w(TAG, "emergency receiver ignored unexpected action=" + (action ?: "null"))
            return
        }
        // Короткий карман бодрости (20 секунд): дать MQTT-связи и помпам
        // докрутиться, пока система не усыпила процесс обратно.
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "APU:EmergencyPump")
                .acquire(20_000L)
        }
        val service = Intent(context, CoreServerService::class.java)
        val started = try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
            true
        } catch (e: Exception) {
            // Android 12+ запрещает старт foreground-сервиса из фона, Android 15
            // запрещает dataSync из BOOT_COMPLETED. Раньше отказ молча
            // проглатывался: сервис не поднимался, и в логах не оставалось
            // следа — «после перезагрузки ничего не приходит» без причины.
            OperationalDiagnostics.tracker.count(OperationalCounter.FOREGROUND_START_REFUSED)
            Log.w(TAG, "emergency start refused (sdk=" + Build.VERSION.SDK_INT + ")", e)
            false
        }
        if (!started) {
            // Не терять цепочку: следующая попытка по лестнице 5 → 15 → 30 → 60.
            EmergencyKeepAlive.onServiceRefused(context)
        }
        // Успешный старт сам перевзведёт страховку: сервис вызовет
        // EmergencyKeepAlive.onServiceStarted() из onStartCommand.
    }

    companion object {
        private const val TAG = "EmergencyKeepAlive"
    }
}

/** Планирование аварийного будильника. Отдельно от приёмника — тестируется. */
object EmergencyKeepAlive {

    private const val TAG = "EmergencyKeepAlive"
    const val ACTION_PUMP = "com.vladimir.messenger.EMERGENCY_PUMP"
    private const val REQUEST_CODE = 1001

    private const val PREFS = "emergency_keep_alive"
    private const val KEY_REFUSALS = "consecutive_refusals"

    /** Страховка, пока сервис жив: раз в час. Неточная — батарея дороже. */
    const val SAFETY_NET_MS = 60 * 60_000L

    /** Лестница повторов, когда сервис не поднимается. */
    val RETRY_LADDER_MS = longArrayOf(5 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L)

    /**
     * Сервис стартовал и работает: одна страховка в час и сброс лестницы.
     * Зовётся из [CoreServerService.onStartCommand] после успешного
     * `startForeground`.
     */
    fun onServiceStarted(context: Context) {
        refusals(context, 0)
        schedule(context, SAFETY_NET_MS, exact = false)
    }

    /**
     * Сервис поднять не удалось (отказ `startForeground`, таймаут foreground,
     * запрет старта из фона): следующая попытка по лестнице, точным
     * будильником — здесь настойчивость оправдана, письма ждут.
     */
    fun onServiceRefused(context: Context) {
        val misses = refusals(context, -1) + 1
        refusals(context, misses)
        val delay = RETRY_LADDER_MS[(misses - 1).coerceIn(0, RETRY_LADDER_MS.lastIndex)]
        Log.i(TAG, "reschedule after refusal #$misses in ${delay / 60_000} min")
        schedule(context, delay, exact = true)
    }

    /** Снять будильник (пользователь выключил сервис — не будить зря). */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
        refusals(context, 0)
    }

    private fun schedule(context: Context, delayMs: Long, exact: Boolean) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        val at = System.currentTimeMillis() + delayMs
        val exactAllowed = !exact || Build.VERSION.SDK_INT < 31 ||
            runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        try {
            if (exact && exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                // Точный запрещён настройкой телефона (или он и не нужен —
                // страховка): неточный в Doze зазвонит в окне обслуживания.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            Log.w(TAG, "exact alarm failed, falling back to inexact: ${e.message}")
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
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
}
