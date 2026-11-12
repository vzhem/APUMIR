package com.vladimir.messenger.data.repository

import android.util.Log
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.textcolor.TextColorWire
import com.vladimir.messenger.domain.model.MessageChannel
import com.vladimir.messenger.domain.model.MessageStatus
import kotlinx.coroutines.CancellationException

/** Room-backed, bounded fair scan of PENDING and phone-owned QUEUED_OFFLINE messages. */
internal class QueuedOutboxPump(
    private val messages: MessageDao,
    private val chats: ChatDao,
    private val send: suspend (String, String, String, String) -> Boolean,
) {
    private val scan = FairOutboxScan<MessageEntity>()

    suspend fun pump(pageSize: Int): OutboxProgress = scan.run(
        pageSize = pageSize,
        countAtStart = { messages.countPendingOutgoing() },
        load = { position, size ->
            val page = if (position == null) messages.getRetryableOutgoingMessages(size)
                else messages.getRetryableOutgoingMessagesAfter(position.timestamp, position.id, size)
            page.map { row ->
                OutboxCandidate(OutboxPosition(row.timestamp, row.id), row.chatId, row)
            }
        },
        attempt = { snapshot ->
            try {
                // Pages can wait behind slow sends; an ACK, clear, or edit may
                // have changed this row since it was selected.
                val row = messages.getMessageById(snapshot.id)
                if (row == null || !row.isFromMe ||
                    row.status !in setOf(MessageStatus.PENDING.name, MessageStatus.QUEUED_OFFLINE.name)
                ) {
                    null
                } else {
                    val peer = OutboxRecipient.forMessage(row, chats.getChatById(row.chatId)?.contactId)
                    if (!peer.startsWith("pk_")) {
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
