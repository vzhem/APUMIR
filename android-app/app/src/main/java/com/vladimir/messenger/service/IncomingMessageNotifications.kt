package com.vladimir.messenger.service

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.gif.GifLibrary
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.schedule.DeferredInboxStore
import com.vladimir.messenger.util.ChatPreviews
import com.vladimir.messenger.util.InlineImage
import com.vladimir.messenger.util.QuietMessage
import com.vladimir.messenger.util.SosMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

internal enum class IncomingAlert { NORMAL, QUIET, SOS }

/** Claims local work once, including suppressed rows, before invoking any side effect. */
internal class IncomingNotificationProcessor(
    private val claim: suspend (String) -> Boolean,
    private val isDeferred: (String) -> Boolean,
    private val alert: suspend (MessageEntity, IncomingAlert) -> Unit,
) {
    suspend fun process(message: MessageEntity) {
        if (!claim(message.id)) return
        // Deferred reveal owns its separate notification, including after completion.
        if (isDeferred(message.id) || InlineImage.isPart(message.content) ||
            ChatPreviews.isServiceEnvelope(message.content)
        ) return
        val kind = when {
            SosMessage.isSos(message.content) -> IncomingAlert.SOS
            QuietMessage.isQuiet(message.content) -> IncomingAlert.QUIET
            else -> IncomingAlert.NORMAL
        }
        alert(message, kind)
    }
}

/**
 * A bounded Room queue is filled only by actual local arrivals, not by history
 * imports. Claiming before posting avoids duplicate alerts after restart; a
 * process death between that claim and Android's notification call can still
 * lose an alert. Delivery itself and sender timestamps are not changed.
 */
internal class IncomingMessageNotifications(
    private val context: Context,
    private val messages: MessageDao,
    private val notifications: NotificationHelper,
) {
    suspend fun observe(recipientId: String) {
        val processor = IncomingNotificationProcessor(
            claim = { messages.claimPendingNotification(it) == 1 },
            isDeferred = { DeferredInboxStore.owns(context, it) },
            alert = { message, kind -> show(message, kind) },
        )
        messages.observePendingNotifications(recipientId).collect { batch ->
            for (message in batch) {
                try {
                    processor.process(message)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("IncomingNotifications", "Notification processing failed", error)
                }
            }
        }
    }

    private suspend fun show(message: MessageEntity, kind: IncomingAlert) {
        val topic = message.topicId?.takeIf { it.isNotBlank() }
        if (kind == IncomingAlert.SOS) {
            // SOS deliberately bypasses ordinary mute settings, as before.
            notifications.showSosNotification(message.chatId, message.senderId, topic, messageId = message.id)
            return
        }
        val text = if (GifLibrary.isGifRef(message.content)) {
            "🖼 Гифка"
        } else {
            ChatPreviews.human(InlineImage.stripImage(message.content).ifBlank { "Фото" })
                ?.take(200) ?: "Фото"
        }
        // NotificationHelper is the common final privacy/mute gate for app,
        // section, chat/group and topic. Quiet messages use its silent path.
        notifications.showMessageNotification(
            message.chatId, message.senderId, text, true, topic,
            quiet = kind == IncomingAlert.QUIET, messageId = message.id,
        )
    }
}
