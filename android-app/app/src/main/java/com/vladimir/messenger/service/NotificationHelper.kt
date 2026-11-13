package com.vladimir.messenger.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vladimir.messenger.MainActivity
import com.vladimir.messenger.MessengerApplication
import com.vladimir.messenger.R
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.notification.FlashlightMessageKind
import com.vladimir.messenger.data.notification.NotificationMuteScope
import com.vladimir.messenger.data.notification.NotificationMuteStore
import com.vladimir.messenger.data.repository.ContactRepository
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

/**
 * Helper для показа push-уведомлений о новых сообщениях.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactRepository: ContactRepository,
    private val chatDao: ChatDao,
    private val groupDao: GroupDao,
    private val notificationMuteStore: NotificationMuteStore,
    private val flashlightBlinker: FlashlightBlinker,
) {
    companion object {
        private const val SOS_NOTIFICATION_BASE_ID = 700_000
        const val EXTRA_CHAT_ID = "extra_chat_id"

        /**
         * Тема (пост канала / тема группы), где написано сообщение. null -
         * личный чат. По нему тап открывает ТО МЕСТО, где сообщение, а не
         * только список чатов.
         */
        const val EXTRA_TOPIC_ID = "extra_topic_id"
    }

    /**
     * Показать уведомление о новом входящем сообщении.
     * 
     * @param chatId ID чата (для deep link)
     * @param senderId ID отправителя
     * @param messageText Текст сообщения
     * @param isIncoming true если входящее, false если исходящее (не показываем)
     */
    internal suspend fun showMessageNotification(
        chatId: String,
        senderId: String,
        rawText: String,
        isIncoming: Boolean,
        topicId: String?,
        /** Тихое сообщение (префикс в тексте): уведомление без звука и вибрации. */
        quiet: Boolean = false,
        messageId: String? = null,
        snapshot: String? = null,
    ): IncomingNotificationPost {
        if (messageId != null && com.vladimir.messenger.data.schedule.DeferredInboxStore.isCancelled(context, senderId, messageId)) return IncomingNotificationPost.SUPPRESSED
        val messageText = com.vladimir.messenger.util.QuietMessage.strip(rawText)
        val silent = quiet || com.vladimir.messenger.util.QuietMessage.isQuiet(rawText)
        android.util.Log.d("NotificationHelper", "showMessageNotification called: chatId=$chatId, senderId=${senderId.take(16)}, isIncoming=$isIncoming")
        if (!isIncoming) {
            android.util.Log.d("NotificationHelper", "Skipping notification (outgoing message)")
            return IncomingNotificationPost.SUPPRESSED
        }

        // Последний страж настройки «Кто может приглашать в группы и каналы»:
        // карточка-приглашение от запрещённого отправителя не должна звенеть
        // ни одним путём, даже если сообщение как-то попало в базу раньше.
        run {
            val mode = com.vladimir.messenger.data.profile.InvitePrivacyStore.value(context)
            if (mode != com.vladimir.messenger.data.profile.InvitePrivacy.ALL) {
                val isCard = com.vladimir.messenger.util.GroupInviteCardSender.parseCard(rawText) != null ||
                    com.vladimir.messenger.util.GroupInviteCardSender.parseMultiCard(rawText) != null
                if (isCard) {
                    val isContact = runCatching { contactRepository.getContactById(senderId) }
                        .getOrNull() != null
                    if (!com.vladimir.messenger.data.profile.InvitePrivacyStore.allows(mode, isContact)) {
                        android.util.Log.i("NotificationHelper", "invite notification suppressed by privacy setting")
                        return IncomingNotificationPost.SUPPRESSED
                    }
                }
            }
        }

        val nowMs = System.currentTimeMillis()
        val group = groupDao.getGroupById(chatId)
        if (isMuted(chatId, topicId, group, nowMs)) {
            android.util.Log.i("NotificationHelper", "Message notification suppressed by mute settings")
            return IncomingNotificationPost.SUPPRESSED
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!canPost(notificationManager, MessengerApplication.CHANNEL_ID)) return IncomingNotificationPost.BLOCKED

        // Получить имя и аватар отправителя
        val contact = contactRepository.getContactById(senderId)

        // Групповое сообщение: в заголовке нужна группа, а не «pk_40d2401a».
        // Отправитель в группе обычно не в контактах, поэтому имя берём из
        // списка участников, и только потом - из идентификатора узла.
        val senderName = contact?.displayName
            ?: groupDao.getMember(chatId, senderId)?.displayName?.takeIf { it.isNotBlank() }
            ?: senderId.take(8)
        val title = group?.title ?: senderName
        // Карточку показываем понятным текстом, а не транспортной ссылкой
        // с адресом сервиса, slug и идентификаторами. Оба вида уведомления
        // (свёрнутый и развёрнутый) используют один и тот же preview.
        val preview = com.vladimir.messenger.util.MessageNotificationText.format(messageText)
        val body = if (group != null) "$senderName: $preview" else preview
        
        // Создать intent для открытия чата
        val intent = Intent(context, MainActivity::class.java).apply {
            // Extras do not participate in PendingIntent identity: include the
            // full chat tag in the action so hash collisions cannot retarget taps.
            action = "com.vladimir.messenger.notification." + LocalMessageNotifications.chatTag(chatId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_ID, chatId)
            if (!topicId.isNullOrBlank()) putExtra(EXTRA_TOPIC_ID, topicId)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            chatId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Создать notification
        val notification = NotificationCompat.Builder(context, MessengerApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)  // Нужна иконка
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setSilent(silent)
            .setAutoCancel(true)
            .addExtras(android.os.Bundle().apply {
                putString(LocalMessageNotifications.EXTRA_MESSAGE, messageId)
                putString(LocalMessageNotifications.EXTRA_CHAT, chatId)
                putString(LocalMessageNotifications.EXTRA_TOPIC, topicId)
                putString(LocalMessageNotifications.EXTRA_SNAPSHOT, snapshot)
            })
            .setContentIntent(pendingIntent)
            .build()

        // Tags group by the entire chat, not 1,000 colliding integer buckets.
        try {
            notificationManager.notify(LocalMessageNotifications.chatTag(chatId), LocalMessageNotifications.MESSAGE_ID, notification)
        } catch (_: SecurityException) {
            return IncomingNotificationPost.BLOCKED
        }
        // Permission may have changed while the notification was being built.
        if (!canPost(notificationManager, MessengerApplication.CHANNEL_ID)) return IncomingNotificationPost.BLOCKED
        flashlightBlinker.blinkForMessage(
            kind = FlashlightMessageKind.classify(
                hasGroup = group != null,
                isChannel = group?.isChannel == true,
            ),
            silent = silent,
            muted = false,
        )
        return IncomingNotificationPost.POSTED
    }

    /**
     * Тревожное сообщение SOS из группы: активно оповещаем, несмотря ни на
     * какие паузы звука (группа без звука, тихие часы и прочее SOS не
     * касаются - сигнал чрезвычайный). Отдельный канал с сигналом
     * будильника, длинная вибрация и попытка полноэкранного показа:
     * уведомление должно разбудить и спящий телефон.
     */
    internal suspend fun showSosNotification(
        chatId: String,
        senderId: String,
        topicId: String?,
        messageId: String? = null,
        snapshot: String? = null,
    ): IncomingNotificationPost {
        if (messageId != null && com.vladimir.messenger.data.schedule.DeferredInboxStore.isCancelled(context, senderId, messageId)) return IncomingNotificationPost.SUPPRESSED
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!canPost(notificationManager, MessengerApplication.CHANNEL_SOS_ID)) return IncomingNotificationPost.BLOCKED
        val group = groupDao.getGroupById(chatId)
        val contact = contactRepository.getContactById(senderId)
        val senderName = contact?.displayName
            ?: groupDao.getMember(chatId, senderId)?.displayName?.takeIf { it.isNotBlank() }
            ?: senderId.take(8)
        val groupTitle = group?.title?.takeIf { it.isNotBlank() }
        val title = if (groupTitle != null) "🆘 SOS — $groupTitle" else "🆘 SOS"
        val body = "$senderName: ${com.vladimir.messenger.util.SosMessage.DEFAULT_TEXT}"

        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.vladimir.messenger.notification." + LocalMessageNotifications.sosTag(chatId, messageId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_ID, chatId)
            if (!topicId.isNullOrBlank()) putExtra(EXTRA_TOPIC_ID, topicId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (SOS_NOTIFICATION_BASE_ID + chatId.hashCode()).mod(10_000),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, MessengerApplication.CHANNEL_SOS_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            // Полноэкранный показ будит спящий телефон; где система его не
            // разрешает (Android 14+ без отдельного разрешения), уведомление
            // просто всплывёт поверх экрана - тихо оно не останется.
            .setFullScreenIntent(pendingIntent, true)
            .setVibrate(longArrayOf(0, 700, 250, 700, 250, 700))
            .addExtras(android.os.Bundle().apply {
                putString(LocalMessageNotifications.EXTRA_MESSAGE, messageId)
                putString(LocalMessageNotifications.EXTRA_CHAT, chatId)
                putString(LocalMessageNotifications.EXTRA_TOPIC, topicId)
                putString(LocalMessageNotifications.EXTRA_SNAPSHOT, snapshot)
            })
            .setContentIntent(pendingIntent)
            .build()

        // Independent tags keep distinct SOS messages from replacing each other.
        try {
            notificationManager.notify(LocalMessageNotifications.sosTag(chatId, messageId), LocalMessageNotifications.SOS_ID, notification)
        } catch (_: SecurityException) {
            return IncomingNotificationPost.BLOCKED
        }
        if (!canPost(notificationManager, MessengerApplication.CHANNEL_SOS_ID)) return IncomingNotificationPost.BLOCKED
        flashlightBlinker.blinkForSos(isGroup = group != null && !group.isChannel)
        return IncomingNotificationPost.POSTED
    }

    /**
     * Проверка в последней точке перед публикацией: сюда попадают и события
     * ядра, и зеркальные сообщения. Поэтому глобальная пауза, раздел, чат,
     * группа/канал и отдельная тема одинаково работают для всех путей доставки.
     */
    private suspend fun isMuted(
        chatId: String,
        topicId: String?,
        group: com.vladimir.messenger.data.local.entity.GroupEntity?,
        nowMs: Long,
    ): Boolean {
        if (notificationMuteStore.isMuted(NotificationMuteScope.APP, nowMs)) return true

        if (group != null) {
            val section = if (group.isChannel) {
                NotificationMuteScope.CHANNELS
            } else {
                NotificationMuteScope.GROUPS
            }
            if (notificationMuteStore.isMuted(section, nowMs)) return true
            if (group.mutedUntilMs > nowMs) return true
            if (!topicId.isNullOrBlank()) {
                if (notificationMuteStore.isMuted(NotificationMuteScope.TOPICS, nowMs)) return true
                if (notificationMuteStore.isTopicMuted(chatId, topicId, nowMs)) return true
            }
            return false
        }

        if (notificationMuteStore.isMuted(NotificationMuteScope.PERSONAL_CHATS, nowMs)) return true
        return chatDao.getChatById(chatId)?.mutedUntilMs?.let { it > nowMs } == true
    }

    /** Android can silently drop notify when the app or channel is blocked. */
    internal fun canPost(manager: NotificationManager, channelId: String): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val channel = manager.getNotificationChannel(channelId) ?: return false
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val group = channel.group?.let(manager::getNotificationChannelGroup)
            if (group?.isBlocked == true) return false
        }
        return true
    }

    /** A matching visible post can repair a crash without sounding again. */
    internal fun isMessageNotificationActive(messageId: String, snapshot: String): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.activeNotifications.any {
            it.notification.extras.getString(LocalMessageNotifications.EXTRA_MESSAGE) == messageId &&
                it.notification.extras.getString(LocalMessageNotifications.EXTRA_SNAPSHOT) == snapshot
        }
    }

    /** A snapshot filter prevents a stale retry from dismissing a newer post. */
    fun cancelMessageNotification(messageId: String, snapshot: String? = null) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications.filter {
            it.notification.extras.getString(LocalMessageNotifications.EXTRA_MESSAGE) == messageId &&
                (snapshot == null || it.notification.extras.getString(LocalMessageNotifications.EXTRA_SNAPSHOT) == snapshot)
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    /** Clear this chat only; legacy posts without a chat extra cannot be safely attributed. */
    fun cancelChatNotifications(chatId: String) = LocalMessageNotifications.cancelChat(context, chatId)
}
