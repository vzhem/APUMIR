package com.vladimir.messenger.data.push

import androidx.room.withTransaction
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.domain.model.MessageChannel
import com.vladimir.messenger.domain.model.MessageStatus
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Experimental Room commit boundary for an ALREADY authenticated mailbox body.
 * [PushMailboxIncoming] is responsible for opening against a prior pin. This
 * class never creates a contact/chat, sends an ACK or displays a notification.
 * It inserts the row, unread count, preview and pending-alert flag atomically.
 *
 * The suppression store lives outside Room. Its writers must persist a
 * tombstone before idempotent Room cleanup. Ordinary single-message deletion
 * does not yet satisfy that contract, so this class must NOT be connected to a
 * live push runtime. The caller must also hold a reviewed identity/restore gate
 * across this transaction and revalidate trust before any later network ACK.
 * [current] is only a non-blocking snapshot check and cannot prevent ABA.
 *
 * A conflicting, unsupported, suppressed, stale, or ambiguous item is RETRY,
 * not proof of delivery. Even a durable tombstone is not ACKed here. Existing
 * legacy rows with an empty recipient are deliberately ambiguous. Timestamp is
 * not part of duplicate matching because some native paths use arrival time.
 */
internal class PushMailboxRoomStore(
    private val database: AppDatabase,
    // Fast, non-suspending, fail-closed lookup; never calls Room or takes a
    // lifecycle lock. It must include all applicable cancellation stores.
    private val suppressed: (sender: String, messageId: String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun commit(
        message: PushMailboxMessageCodec.Message,
        pinnedPeer: ByteArray,
        current: () -> Boolean,
    ): PushMailboxDrain.Commit {
        if (!PushMailboxTextPolicy.accepts(message.content) || pinnedPeer.size !in 1..512 ||
            PushMailboxMessageCodec.encode(message.id, message.sender, message.recipient, message.sentAtMs, message.content) == null
        ) return PushMailboxDrain.Commit.RETRY
        val now = clock()
        if (!PushMailboxProtocol.validTimestamp(now)) return PushMailboxDrain.Commit.RETRY
        // A peer's clock cannot place a future-dated message above every later
        // preview indefinitely. Some ordinary paths already use arrival time.
        val shownTime = minOf(message.sentAtMs, now)
        val pin = pinnedPeer.copyOf()
        fun guard() {
            if (!current() || !MessageDigest.isEqual(pin, pinnedPeer) || suppressed(message.sender, message.id)) {
                throw Stale()
            }
        }
        return try {
            currentCoroutineContext().ensureActive()
            database.withTransaction {
                guard()
                val savedPin = database.fileExchangePeerDao().get(message.sender)
                if (!matchesPin(savedPin?.bindingBase64, savedPin?.bindingSha256, pin)) return@withTransaction PushMailboxDrain.Commit.RETRY
                val chats = database.chatDao()
                if (chats.countChatsForContact(message.sender) != 1) return@withTransaction PushMailboxDrain.Commit.RETRY
                val chat = chats.getChatByContactId(message.sender) ?: return@withTransaction PushMailboxDrain.Commit.RETRY
                if (chat.id.isBlank() || database.groupDao().getGroupById(chat.id) != null ||
                    database.fileTransferDao().hasMessageTransfer(message.id) ||
                    database.directPollDao().hasMessagePoll(message.id)
                ) return@withTransaction PushMailboxDrain.Commit.RETRY
                val messages = database.messageDao()
                val existing = messages.getMessageById(message.id)
                if (existing != null) {
                    guard()
                    return@withTransaction if (samePlainMessage(existing, chat.id, message)) {
                        PushMailboxDrain.Commit.ALREADY_COMMITTED
                    } else PushMailboxDrain.Commit.RETRY
                }
                val row = MessageEntity(
                    id = message.id, chatId = chat.id, senderId = message.sender,
                    recipientId = message.recipient, content = message.content, timestamp = shownTime,
                    status = MessageStatus.DELIVERED.name, isFromMe = false,
                    channel = MessageChannel.STORE_FORWARD.name, notificationPending = true,
                )
                if (messages.insertMessageIgnore(row) == -1L) return@withTransaction PushMailboxDrain.Commit.RETRY
                guard()
                check(chats.recordMailboxIncoming(chat.id, message.sender, message.content, shownTime) == 1)
                guard()
                PushMailboxDrain.Commit.COMMITTED
            }.also { currentCoroutineContext().ensureActive() }
        } catch (_: Stale) {
            // A stale snapshot or suppression is an expected rollback, not success.
            currentCoroutineContext().ensureActive()
            PushMailboxDrain.Commit.RETRY
        } finally {
            pin.fill(0)
        }
    }

    private fun matchesPin(encoded: String?, hash: String?, pin: ByteArray): Boolean {
        if (encoded == null || encoded.length !in 1..700 || hash == null) return false
        val decoded = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
            // A corrupted saved pin is an ordinary fail-closed rejection.
            return false
        }
        return try {
            val expectedHash = MessageDigest.getInstance("SHA-256").digest(pin)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            hash == expectedHash && Base64.getEncoder().encodeToString(decoded) == encoded && MessageDigest.isEqual(decoded, pin)
        } finally { decoded.fill(0) }
    }

    private fun samePlainMessage(row: MessageEntity, chatId: String, message: PushMailboxMessageCodec.Message): Boolean =
        !row.isFromMe && row.chatId == chatId && row.senderId == message.sender &&
            row.recipientId == message.recipient && row.content == message.content &&
            row.status == MessageStatus.DELIVERED.name && row.channel in PERSONAL_CHANNELS &&
            row.topicId.isNullOrEmpty() && row.replyToId.isNullOrEmpty() &&
            row.replyAuthor.isEmpty() && row.replyText.isEmpty() && row.editedAtMs == 0L &&
            row.textColor.isEmpty() && row.senderNameColor.isEmpty()

    // A new or malformed channel is not automatically proof of a personal row.
    private companion object {
        val PERSONAL_CHANNELS = setOf("UNKNOWN", "MQTT", "CF", "LOCAL", "STORE_FORWARD")
    }

    private class Stale : Exception()
}
