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
import com.vladimir.messenger.data.local.dao.GroupDao
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
    private val groupDao: GroupDao,
) {
    companion object {
        private const val MESSAGE_NOTIFICATION_BASE_ID = 2000
        const val EXTRA_CHAT_ID = "extra_chat_id"
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
        messageText: String,
        isIncoming: Boolean,
    ) {
        android.util.Log.d("NotificationHelper", "showMessageNotification called: chatId=$chatId, senderId=${senderId.take(16)}, isIncoming=$isIncoming")
        if (!isIncoming) {
            android.util.Log.d("NotificationHelper", "Skipping notification (outgoing message)")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Получить имя и аватар отправителя
        val contact = contactRepository.getContactById(senderId)

        // Групповое сообщение: в заголовке нужна группа, а не «pk_40d2401a».
        // Отправитель в группе обычно не в контактах, поэтому имя берём из
        // списка участников, и только потом - из идентификатора узла.
        val group = groupDao.getGroupById(chatId)
        val senderName = contact?.displayName
            ?: groupDao.getMember(chatId, senderId)?.displayName?.takeIf { it.isNotBlank() }
            ?: senderId.take(8)
        val title = group?.title ?: senderName
        val body = if (group != null) "$senderName: $messageText" else messageText
        
        // Создать intent для открытия чата
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_ID, chatId)
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        // Показать notification (ID на основе chatId чтобы группировать)
        val notificationId = MESSAGE_NOTIFICATION_BASE_ID + chatId.hashCode().mod(1000)
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Отменить все уведомления для чата (когда пользователь открыл чат).
     */
    fun cancelChatNotifications(chatId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = MESSAGE_NOTIFICATION_BASE_ID + chatId.hashCode().mod(1000)
        notificationManager.cancel(notificationId)
    }
}
