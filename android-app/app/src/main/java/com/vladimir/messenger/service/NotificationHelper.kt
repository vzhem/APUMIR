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
        if (!isIncoming) return  // Не показываем уведомления для исходящих

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Получить имя и аватар отправителя
        val contact = contactRepository.getContactById(senderId)
        val senderName = contact?.displayName ?: senderId.take(8)
        
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
            .setContentTitle(senderName)
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
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
