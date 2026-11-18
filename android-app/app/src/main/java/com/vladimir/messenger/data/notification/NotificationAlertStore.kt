package com.vladimir.messenger.data.notification

import android.content.Context
import com.vladimir.messenger.R

/**
 * Звук и вибрация уведомлений — по разделам и для всего приложения.
 *
 * Владелец 2026-11-17: «в настройках не нашёл функцию со звуком и вибрацией.
 * Звуки и вибрации нужно тоже поставить во всех местах, чтобы можно было
 * настроить личные сообщения, группы, каналы… и так же общее на приложение».
 *
 * Почему это не одна галочка. На Android 8+ звук и вибрация принадлежат каналу
 * уведомлений, а настройки существующего канала код изменить не может. Поэтому
 * «без звука» — это отдельный канал: см. [NotificationAlertChannels], где на
 * каждый раздел заводится четыре профиля (звук+вибрация, только звук, только
 * вибрация, тихо). Здесь хранится только выбор человека, канал вычисляется из
 * него в момент показа.
 */
enum class NotificationAlertScope(
    val key: String,
    val titleRes: Int,
) {
    /** Всё приложение: выключает звук и вибрацию у всех разделов сразу. */
    APP("app", R.string.notification_alert_app),
    PERSONAL_CHATS("personal", R.string.notification_scope_personal),
    GROUPS("groups", R.string.notification_scope_groups),
    CHANNELS("channels", R.string.notification_scope_channels),
}

/** Что делать с входящим сообщением: звук и (или) вибрация. */
data class NotificationAlertProfile(
    val sound: Boolean,
    val vibrate: Boolean,
) {
    companion object {
        val BOTH = NotificationAlertProfile(sound = true, vibrate = true)
        val SILENT = NotificationAlertProfile(sound = false, vibrate = false)
    }
}

/**
 * Хранилище выбора «звук/вибрация». Локальное и без синхронизации: это
 * настройка конкретного телефона, как и паузы уведомлений.
 */
object NotificationAlertStore {

    private const val PREFERENCES_NAME = "notification_alerts"
    private const val SOUND_SUFFIX = "_sound"
    private const val VIBRATE_SUFFIX = "_vibrate"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun soundEnabled(context: Context, scope: NotificationAlertScope): Boolean =
        prefs(context).getBoolean(scope.key + SOUND_SUFFIX, true)

    fun vibrationEnabled(context: Context, scope: NotificationAlertScope): Boolean =
        prefs(context).getBoolean(scope.key + VIBRATE_SUFFIX, true)

    fun setSoundEnabled(context: Context, scope: NotificationAlertScope, enabled: Boolean) {
        prefs(context).edit().putBoolean(scope.key + SOUND_SUFFIX, enabled).apply()
    }

    fun setVibrationEnabled(context: Context, scope: NotificationAlertScope, enabled: Boolean) {
        prefs(context).edit().putBoolean(scope.key + VIBRATE_SUFFIX, enabled).apply()
    }

    /**
     * Итог для раздела: выбор раздела И общий выключатель приложения.
     *
     * Общий выключатель только запрещает, но никогда не включает: если человек
     * выключил звук для всего приложения, раздел не может вернуть его обратно.
     */
    fun effective(context: Context, scope: NotificationAlertScope): NotificationAlertProfile {
        val sound = soundEnabled(context, scope) && soundEnabled(context, NotificationAlertScope.APP)
        val vibrate =
            vibrationEnabled(context, scope) && vibrationEnabled(context, NotificationAlertScope.APP)
        return NotificationAlertProfile(sound, vibrate)
    }

    /** Профиль для конкретного входящего: тихое сообщение всегда молчит. */
    fun profileFor(
        context: Context,
        section: NotificationAlertScope,
        quiet: Boolean,
    ): NotificationAlertProfile =
        if (quiet) NotificationAlertProfile.SILENT else effective(context, section)

    /** Только уведомления о новых сообщениях: остальные настройки не трогаем. */
    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
