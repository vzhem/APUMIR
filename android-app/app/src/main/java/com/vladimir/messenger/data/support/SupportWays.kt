package com.vladimir.messenger.data.support

// =============================================================================
// SUPPORTWAYS.KT — «Поддержать разработчика»: способы перевода и напоминание
// =============================================================================
// Раунд 219, ЧЕРНОВИК (владелец: «внедряй как черновик... личные данные нигде
// не фигурировали, например сотовый телефон... понятно, просто, безопасно»).
//
// БЕЗОПАСНОСТЬ ЛИЧНЫХ ДАННЫХ: в приложении и в репозитории нет НИ ОДНОГО
// реального реквизита. Список способов отдаёт наш сервис (маршрут /support
// воркера), а сами реквизиты владелец кладёт в KV воркера руками, когда
// решит их опубликовать. Приложение лишь кэширует последний ответ локально
// (закрытая папка приложения). Экран виден только владельцу телефона.
// =============================================================================

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vladimir.messenger.MainActivity
import com.vladimir.messenger.R
import com.vladimir.messenger.data.group.GroupInviteLinks
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Один способ перевода, как его отдал сервис (реквизиты НЕ в коде). */
data class SupportWay(
    val title: String,
    val details: String,
    /** Что положить в буфер по кнопке «Скопировать» (номер/адрес). */
    val copyText: String,
)

object SupportWays {

    private const val TAG = "SupportWays"
    private const val PREFS = "p2p_prefs"
    private const val KEY_CACHE = "support_ways_cache"
    private const val KEY_FETCHED_AT = "support_ways_fetched_at"

    /** Обновляем не чаще, чем авто-чек обновлений (р195: 12ч+джиттер) — хватит и 6ч. */
    private const val CACHE_TTL_MS = 6L * 3600_000L
    private const val TIMEOUT_MS = 15_000

    private fun url(): String =
        "https://" + GroupInviteLinks.WEB_HOST + "/support"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Разобрать ответ сервиса: {"ways":[{"title","details","copy"}]}. */
    fun parse(body: String): List<SupportWay> = runCatching {
        val arr = JSONObject(body).optJSONArray("ways") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = item.optString("title", "").trim()
            val details = item.optString("details", "").trim()
            if (title.isEmpty() || details.isEmpty()) null
            else SupportWay(title, details, item.optString("copy", "").trim())
        }
    }.getOrDefault(emptyList())

    /** Последняя удачно полученная копия (без сети). */
    fun cached(context: Context): List<SupportWay> =
        prefs(context).getString(KEY_CACHE, null)?.let { parse(it) } ?: emptyList()

    /**
     * Свежий список: сеть, при неудаче - кэш, при полном отсутствии - пусто
     * (экран покажет черновое «список настраивается»).
     */
    suspend fun load(context: Context, force: Boolean = false): List<SupportWay> =
        withContext(Dispatchers.IO) {
            val prefs = prefs(context)
            val age = System.currentTimeMillis() - prefs.getLong(KEY_FETCHED_AT, 0L)
            if (!force && age < CACHE_TTL_MS) return@withContext cached(context)

            val body = runCatching {
                val conn = (URL(url()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                }
                try {
                    if (conn.responseCode != 200) null
                    else conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Log.d(TAG, "support ways fetch failed: ${it.message}") }.getOrNull()

            val ways = body?.let { parse(it) } ?: emptyList()
            if (body != null) {
                prefs.edit()
                    .putString(KEY_CACHE, body)
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    .apply()
            }
            if (ways.isNotEmpty()) ways else cached(context)
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ежемесячное напоминание. APU деньги НЕ списывает и платёжных данных НЕ
// хранит - это обычное локальное уведомление по расписанию WorkManager.
// ─────────────────────────────────────────────────────────────────────────────

object SupportReminder {

    private const val TAG = "SupportReminder"
    private const val PREFS = "p2p_prefs"
    private const val KEY_ENABLED = "support_remind_enabled"
    private const val KEY_PERIOD_MONTHS = "support_remind_period_months"
    private const val KEY_DAY = "support_remind_day"
    private const val WORK_NAME = "support_reminder"

    /** Напоминание: надо/не надо, периодичность и день (1..28). */
    data class State(
        val enabled: Boolean = false,
        val periodMonths: Int = 1,
        val day: Int = 15,
    ) {
        val humanLine: String
            get() = if (!enabled) "Напоминание выключено" else
                (if (periodMonths == 1) "Раз в месяц" else "Раз в $periodMonths месяца") +
                    " ${day}-го числа"
    }

    fun state(context: Context): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            periodMonths = prefs.getInt(KEY_PERIOD_MONTHS, 1).coerceIn(1, 3),
            day = prefs.getInt(KEY_DAY, 15).coerceIn(1, 28),
        )
    }

    /** Сохранить выбор человека и немедленно перестроить расписание. */
    fun save(context: Context, state: State) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, state.enabled)
            .putInt(KEY_PERIOD_MONTHS, state.periodMonths.coerceIn(1, 3))
            .putInt(KEY_DAY, state.day.coerceIn(1, 28))
            .apply()
        if (state.enabled) schedule(context) else cancel(context)
    }

    /**
     * Периодическая задача WorkManager: переживает перезапуск телефона сам,
     * без приёмников загрузки. Первый показ - в выбранный день месяца.
     */
    private fun schedule(context: Context) {
        val state = state(context)
        val first = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12) // дневной час: не ночью и не под утро
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_MONTH, state.day)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.MONTH, 1)
        }
        val delayMs = (first.timeInMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val request = PeriodicWorkRequestBuilder<SupportReminderWorker>(
            state.periodMonths * 30L, TimeUnit.DAYS,
        )
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        Log.i(TAG, "support reminder scheduled: first in ${delayMs / 86_400_000L}d, " +
            "every ${state.periodMonths * 30}d")
    }

    private fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }

    /** «Показать пример напоминания» - сразу, вне расписания. */
    fun sendTestNotification(context: Context) {
        notifySupport(context, test = true)
    }
}

/** Локальное уведомление-напоминание (свой тихий канал, тап - в этот экран). */
object SupportReminderNotifier {

    const val CHANNEL_ID = "apu_support"
    const val EXTRA_OPEN_SUPPORT = "extra_open_support"
    private const val NOTIFICATION_ID = 42001

    fun ensureChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Поддержка разработчика",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Ежемесячное напоминание поддержать APU"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifySupport(context: Context, test: Boolean = false) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_SUPPORT, true)
        }
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (test) {
            "Это пример: так выглядит ежемесячное напоминание. " +
                "Перевод добровольный: сколько и когда - решаете вы."
        } else {
            "Если APU полезен - можно поддержать переводом: сколько и когда, решаете вы. " +
                "Нажмите, чтобы открыть способы."
        }
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Поддержать APU")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w("SupportReminder", "notify failed: ${it.message}") }
    }
}

/**
 * Обычный Worker без Hilt-фабрики (как AutoBackupWorker): зависимостей нет -
 * только контекст, prefs и уведомление.
 */
class SupportReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!SupportReminder.state(app).enabled) {
            Log.i("SupportReminder", "reminder disabled - nothing to show")
            return Result.success()
        }
        notifySupport(app)
        return Result.success()
    }
}
