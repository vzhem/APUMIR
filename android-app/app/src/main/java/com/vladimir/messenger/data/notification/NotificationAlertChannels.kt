package com.vladimir.messenger.data.notification

import android.app.NotificationChannel
import android.content.Context
import android.app.NotificationManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings

/**
 * Каналы уведомлений о сообщениях: по одному на каждый раздел и профиль.
 *
 * На Android 8+ звук и вибрация принадлежат каналу, менять их из кода после
 * создания нельзя. Поэтому «звук/вибрация по разделам» — это не флаг у
 * уведомления, а отдельный канал: 4 профиля × 3 раздела. Человек выбирает
 * профиль в настройках приложения, а имя канала в системных настройках говорит
 * то же самое словами.
 *
 * Общий канал `apu_messages_v2` (v11.74.532) остаётся в системе: удалять чужой
 * выбор нельзя, но новые уведомления идут уже в каналы разделов.
 */
object NotificationAlertChannels {

    /** Профиль канала: что он делает с уведомлением. */
    enum class Profile(val key: String, val sound: Boolean, val vibrate: Boolean, val nameRes: Int) {
        BOTH("sv", true, true, com.vladimir.messenger.R.string.notification_channel_both),
        SOUND("s", true, false, com.vladimir.messenger.R.string.notification_channel_sound),
        VIBRATE("v", false, true, com.vladimir.messenger.R.string.notification_channel_vibrate),
        SILENT("n", false, false, com.vladimir.messenger.R.string.notification_channel_silent),
    }

    /** Раздел, к которому относится сообщение. */
    enum class Section(
        val key: String,
        val nameRes: Int,
        val scope: NotificationAlertScope,
    ) {
        PERSONAL(
            "personal",
            com.vladimir.messenger.R.string.notification_scope_personal,
            NotificationAlertScope.PERSONAL_CHATS,
        ),
        GROUPS(
            "groups",
            com.vladimir.messenger.R.string.notification_scope_groups,
            NotificationAlertScope.GROUPS,
        ),
        CHANNELS(
            "channels",
            com.vladimir.messenger.R.string.notification_scope_channels,
            NotificationAlertScope.CHANNELS,
        ),
    }

    fun channelId(section: Section, profile: Profile): String =
        "apu_msg_${section.key}_${profile.key}"

    /** Профиль канала по выбору человека (тихое сообщение всегда молчит). */
    fun profileFor(alert: NotificationAlertProfile): Profile = when {
        alert.sound && alert.vibrate -> Profile.BOTH
        alert.sound -> Profile.SOUND
        alert.vibrate -> Profile.VIBRATE
        else -> Profile.SILENT
    }

    /** Создать (или обновить имя) все каналы разделов. */
    fun ensure(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        for (section in Section.entries) {
            for (profile in Profile.entries) {
                manager.createNotificationChannel(channel(context, section, profile))
            }
        }
    }

    private fun channel(context: Context, section: Section, profile: Profile): NotificationChannel {
        val silent = profile == Profile.SILENT
        val channel = NotificationChannel(
            channelId(section, profile),
            "${context.getString(section.nameRes)} — ${context.getString(profile.nameRes)}",
            if (silent) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH,
        )
        channel.description = context.getString(com.vladimir.messenger.R.string.notification_channel_description)
        channel.setShowBadge(true)
        channel.enableLights(!silent)
        if (profile.sound) {
            channel.setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        } else {
            channel.setSound(null, null)
        }
        channel.enableVibration(profile.vibrate)
        if (profile.vibrate) {
            channel.vibrationPattern = longArrayOf(0, 250, 200, 250)
        }
        return channel
    }
}
