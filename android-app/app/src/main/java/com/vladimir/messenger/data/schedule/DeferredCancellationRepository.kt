package com.vladimir.messenger.data.schedule

import androidx.room.withTransaction
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.service.NotificationHelper
import com.vladimir.messenger.util.ChatPreviews
import com.vladimir.messenger.util.InlineImage
import javax.inject.Inject
import javax.inject.Singleton

/** Local cleanup only. Never removes another author's rows, comments or a whole channel topic. */
@Singleton
class DeferredCancellationRepository @Inject constructor(
    private val db: AppDatabase,
    private val notifications: NotificationHelper,
) {
    suspend fun deleteVisible(sender: String, messageId: String, ownCopy: Boolean = false) {
        db.withTransaction {
            val messages = db.messageDao()
            val head = messages.getMessageById(messageId)
            val rows = (listOfNotNull(head) + (1..InlineImage.MAX_TEXT_PARTS).mapNotNull {
                messages.getMessageById("${messageId}_part$it")
            }).filter { if (ownCopy) it.isFromMe else !it.isFromMe && it.senderId == sender }
            for (row in rows) {
                messages.deleteById(row.id)
                db.messageReactionDao().deleteForMessage(row.id)
            }
            for (chatId in rows.map { it.chatId }.distinct()) {
                val last = messages.getLatest(chatId)
                val preview = ChatPreviews.human(last?.content).orEmpty().take(80)
                val group = db.groupDao().getGroupById(chatId)
                if (group != null) {
                    db.groupDao().updateGroupLastMessage(chatId, preview, last?.timestamp ?: 0L)
                    if (head != null && rows.any { it.id == messageId }) {
                        val topicId = head.topicId.orEmpty()
                        val latest = messages.getTopicMessages(chatId, topicId).lastOrNull { !InlineImage.isPart(it.content) }
                        db.groupDao().refreshDeferredTopicAfterDelete(topicId,
                            ChatPreviews.human(latest?.content).orEmpty().take(80), latest?.timestamp ?: 0L)
                    }
                } else {
                    db.chatDao().updateLastMessage(chatId, preview, last?.timestamp ?: 0L)
                }
            }
        }
        notifications.cancelMessageNotification(messageId)
    }
}
