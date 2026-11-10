package com.vladimir.messenger.data.repository

import android.util.Log
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.textcolor.TextColorWire
import com.vladimir.messenger.domain.model.MessageChannel
import com.vladimir.messenger.domain.model.MessageStatus
import kotlinx.coroutines.CancellationException

/** Room-backed, bounded fair scan of the phone-owned outgoing queue. */
internal class QueuedOutboxPump(
    private val messages: MessageDao,
    private val chats: ChatDao,
    private val send: suspend (String, String, String, String) -> Boolean,
) {
    private val scan = FairOutboxScan<MessageEntity>()

    suspend fun pump(pageSize: Int): OutboxProgress = scan.run(
        pageSize = pageSize,
        countAtStart = { messages.queuedOfflineCount() },
        load = { position, size ->
            val page = if (position == null) messages.getQueuedOfflineMessages(size)
                else messages.getQueuedOfflineMessagesAfter(position.timestamp, position.id, size)
            page.map { row ->
                OutboxCandidate(OutboxPosition(row.timestamp, row.id), row.chatId, row)
            }
        },
        attempt = { row ->
            try {
                val chat = chats.getChatById(row.chatId)
                val peer = chat?.contactId.orEmpty()
                if (peer.isBlank()) {
                    false
                } else {
                    val delivered = send(
                        row.id, row.chatId, peer,
                        TextColorWire.wrap(row.content, row.textColor, row.senderNameColor),
                    )
                    if (delivered) {
                        // An ACK may have arrived while send was in progress.
                        messages.updateOutgoingRetryResult(row.id, MessageStatus.SENT.name, MessageChannel.LOCAL.name)
                    }
                    delivered
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("QueuedOutboxPump", "Queued message retry failed", error)
                false
            }
        },
    )
}
