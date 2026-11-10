package com.vladimir.messenger.data.repository

import android.util.Log
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.mirror.MirrorHub
import com.vladimir.messenger.data.textcolor.TextColorWire
import com.vladimir.messenger.domain.model.MessageStatus
import com.vladimir.messenger.util.ChatPreviews
import kotlinx.coroutines.CancellationException

/**
 * A direct stream success means SENT, not that Room on the other phone has
 * committed the message. Revisit personal SENT rows until the app-level ACK
 * arrives. This is a separate, slower fair scan: a backlog of these rows must
 * not block the primary PENDING/QUEUED_OFFLINE pump. Room remains the durable
 * source, retries keep the original ID, and terminal statuses are never reset.
 */
internal class UnconfirmedOutboxPump(
    private val messages: MessageDao,
    private val chats: ChatDao,
    private val send: suspend (String, String, String, String) -> Boolean,
    private val mirrorIsActive: () -> Boolean = { MirrorHub.routeOutgoing() != null },
) {
    // A single older retry per turn leaves room for fresh sends on the same peer.
    private val scan = FairOutboxScan<MessageEntity>(maxAttemptsPerCycle = 1)

    suspend fun pump(pageSize: Int): OutboxProgress {
        // The active mirror partner owns network sends while it is present.
        if (mirrorIsActive()) return OutboxProgress(0, 0, false)
        return scan.run(
            pageSize = pageSize,
            countAtStart = { messages.countUnconfirmedPersonalSent() },
            load = { position, size ->
                val page = if (position == null) messages.getUnconfirmedPersonalSent(size)
                    else messages.getUnconfirmedPersonalSentAfter(position.timestamp, position.id, size)
                page.map { row -> OutboxCandidate(OutboxPosition(row.timestamp, row.id), row.chatId, row) }
            },
            // Service packets can be numerous in old databases. They must not
            // consume the one network attempt allowed in this sweep turn.
            eligible = { row -> !ChatPreviews.isServicePacket(row.content) && !row.content.startsWith("APUSTORY1|") },
            attempt = { row ->
                try {
                    // An app ACK/read or deletion can race the database scan.
                    // Never resend a row already known to be terminal.
                    val current = messages.getMessageById(row.id)
                    if (current == null || !current.isFromMe || current.status != MessageStatus.SENT.name ||
                        current.channel == "GROUP" || !current.topicId.isNullOrBlank() ||
                        current.id.startsWith("deferred_") || current.content.startsWith("APUGIFREF1|") ||
                        ChatPreviews.isServicePacket(current.content) || current.content.startsWith("APUSTORY1|")
                    ) {
                        true
                    } else {
                        val storedPeer = current.recipientId
                        val peer = if (storedPeer.startsWith("pk_")) storedPeer
                            else chats.getChatById(current.chatId)?.contactId.orEmpty()
                        if (!peer.startsWith("pk_") || mirrorIsActive()) false
                        else send(
                            current.id, current.chatId, peer,
                            TextColorWire.wrap(current.content, current.textColor, current.senderNameColor),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("UnconfirmedOutbox", "Unconfirmed message retry failed: ${error.javaClass.simpleName}")
                    false
                }
            },
        )
    }
}
