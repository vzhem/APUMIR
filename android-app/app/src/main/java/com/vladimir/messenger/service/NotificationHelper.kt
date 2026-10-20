package com.vladimir.messenger.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.vladimir.messenger.MainActivity
import com.vladimir.messenger.MessengerApplication
import com.vladimir.messenger.R
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.GroupDao
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
) {
    companion object {
        private const val MESSAGE_NOTIFICATION_BASE_ID = 2000
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
    suspend fun showMessageNotification(
        chatId: String,
        senderId: String,
        rawText: String,
        isIncoming: Boolean,
        topicId: String?,
        /** Тихое сообщение (префикс в тексте): уведомление без звука и вибрации. */
        quiet: Boolean = false,
        messageId: String? = null,
    ) {
        if (messageId != null && com.vladimir.messenger.data.schedule.DeferredInboxStore.isCancelled(context, senderId, messageId)) return
        val messageText = com.vladimir.messenger.util.QuietMessage.strip(rawText)
        val silent = quiet || com.vladimir.messenger.util.QuietMessage.isQuiet(rawText)
        android.util.Log.d("NotificationHelper", "showMessageNotification called: chatId=$chatId, senderId=${senderId.take(16)}, isIncoming=$isIncoming")
        if (!isIncoming) {
            android.util.Log.d("NotificationHelper", "Skipping notification (outgoing message)")
            return
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
                        return
                    }
                }
            }
        }

        val nowMs = System.currentTimeMillis()
        val group = groupDao.getGroupById(chatId)
        if (isMuted(chatId, topicId, group, nowMs)) {
            android.util.Log.i("NotificationHelper", "Message notification suppressed by mute settings: ${chatId.take(12)}")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
            .addExtras(android.os.Bundle().apply { putString("apu_message_id", messageId) })
            .setContentIntent(pendingIntent)
            .build()
        
        // Показать notification (ID на основе chatId чтобы группировать)
        val notificationId = MESSAGE_NOTIFICATION_BASE_ID + chatId.hashCode().mod(1000)
        notificationManager.notify(notificationId, notification)
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

    /**
     * Отменить все уведомления для чата (когда пользователь открыл чат).
     */
    /** Do not dismiss a newer, unrelated message notification in the same chat. */
    fun cancelMessageNotification(messageId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications.filter { it.notification.extras.getString("apu_message_id") == messageId }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun cancelChatNotifications(chatId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = MESSAGE_NOTIFICATION_BASE_ID + chatId.hashCode().mod(1000)
        notificationManager.cancel(notificationId)
    }
}
