package com.vladimir.messenger.service

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.gif.GifLibrary
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.schedule.DeferredInboxStore
import com.vladimir.messenger.util.ChatPreviews
import com.vladimir.messenger.util.InlineImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/**
 * The service may observe arrivals continuously; the local recovery worker can
 * also drain a bounded batch without starting the service or the Rust engine.
 * Both use the same processor/gate and the same privacy, mute, SOS and quiet
 * paths. Scheduling a worker is not proof that Android displayed a notification.
 */
internal class IncomingMessageNotifications(
    private val context: Context,
    private val messages: MessageDao,
    private val notifications: NotificationHelper,
) {
    private fun processor(recipientId: String) = IncomingNotificationProcessor(
        recipientId = recipientId,
        load = messages::getMessageById,
        complete = { message ->
            messages.completePendingNotification(
                message.id, message.chatId, message.senderId, message.recipientId,
                message.content, message.timestamp, message.topicId, message.channel,
            ) == 1
        },
        isSuppressed = { message ->
            DeferredInboxStore.owns(context, message.id) ||
                DeferredInboxStore.isCancelled(context, message.senderId, message.id) ||
                (message.channel != "GROUP" && com.vladimir.messenger.data.chatclear.ChatClearRegistry
                    .isCleared(context, message.senderId, message.id))
        },
        isActive = { message ->
            notifications.isMessageNotificationActive(message.id, IncomingNotificationSnapshot.of(recipientId, message))
        },
        alert = { message, kind -> show(message, kind, IncomingNotificationSnapshot.of(recipientId, message)) },
        cancel = { message ->
            notifications.cancelMessageNotification(message.id, IncomingNotificationSnapshot.of(recipientId, message))
        },
        current = { IncomingNotificationIdentity.isCurrent(context, recipientId) },
    )

    suspend fun observe(recipientId: String) {
        val processor = processor(recipientId)
        messages.observePendingNotifications(recipientId).collect { batch ->
            var retry = false
            for (message in batch) {
                try {
                    if (processor.process(message.id) == IncomingNotificationProcessor.Result.RETRY) retry = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("IncomingNotifications", "Notification processing failed: ${error.javaClass.simpleName}")
                    retry = true
                }
            }
            // The outer service loop waits before resubscribing; an unchanged
            // failed row would otherwise never trigger another Room emission.
            if (retry) throw IllegalStateException("Pending notification needs retry")
        }
    }

    /** Used by the background worker; cursor persistence is the worker's responsibility. */
    suspend fun drainBatch(recipientId: String, cursor: IncomingNotificationSweep.Cursor): IncomingNotificationSweep.Step? {
        val processor = processor(recipientId)
        val sweep = IncomingNotificationSweep(
            maxPendingRowId = { messages.maxPendingNotificationRowId(recipientId) },
            page = { after, through, limit -> messages.pendingNotificationPage(recipientId, after, through, limit) },
            process = processor::process,
            current = { IncomingNotificationIdentity.isCurrent(context, recipientId) },
            recordFailure = { error ->
                Log.w("IncomingNotifications", "Notification recovery failed: ${error.javaClass.simpleName}")
            },
        )
        return sweep.run(cursor)
    }

    private suspend fun show(message: MessageEntity, kind: IncomingAlert, snapshot: String): IncomingNotificationPost {
        val topic = message.topicId?.takeIf { it.isNotBlank() }
        if (kind == IncomingAlert.SOS) {
            // SOS deliberately bypasses ordinary mute settings, as before.
            return notifications.showSosNotification(message.chatId, message.senderId, topic, messageId = message.id, snapshot = snapshot)
        }
        val text = if (GifLibrary.isGifRef(message.content)) {
            "🖼 Гифка"
        } else {
            ChatPreviews.human(InlineImage.stripImage(message.content).ifBlank { "Фото" })
                ?.take(200) ?: "Фото"
        }
        // NotificationHelper is the common final privacy/mute gate for app,
        // section, chat/group and topic. Quiet messages use its silent path.
        return notifications.showMessageNotification(
            message.chatId, message.senderId, text, true, topic,
            quiet = kind == IncomingAlert.QUIET, messageId = message.id, snapshot = snapshot,
        )
    }
}
