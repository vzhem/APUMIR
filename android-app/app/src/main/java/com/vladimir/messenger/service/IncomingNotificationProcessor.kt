package com.vladimir.messenger.service

import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.util.ChatPreviews
import com.vladimir.messenger.util.InlineImage
import com.vladimir.messenger.util.QuietMessage
import com.vladimir.messenger.util.SosMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class IncomingAlert { NORMAL, QUIET, SOS }

/** A post can be accepted by Android, deliberately suppressed, or blocked by system settings. */
internal enum class IncomingNotificationPost { POSTED, SUPPRESSED, BLOCKED }

/**
 * The service observer and the local recovery worker share this gate. A pending
 * row is re-read inside it, posted first, and only then completed by an
 * optimistic snapshot update. An active notification with the SAME profile
 * and snapshot repairs a crash after Android accepted notify but before Room
 * was updated. Android accepting a post does not prove that a user saw it.
 *
 * This is at-least-once, not an atomic transaction with Android: if a user or
 * the OS dismisses a notification in that crash window it may be posted again.
 * The process-local gate cannot serialize another process, profile restore or
 * deletion. A row can also change between read and notify; a failed snapshot
 * update cancels only the matching post and leaves the changed row for retry.
 */
internal class IncomingNotificationProcessor(
    private val recipientId: String,
    private val load: suspend (String) -> MessageEntity?,
    private val complete: suspend (MessageEntity) -> Boolean,
    private val isSuppressed: (MessageEntity) -> Boolean,
    private val isActive: (MessageEntity) -> Boolean,
    private val alert: suspend (MessageEntity, IncomingAlert) -> IncomingNotificationPost,
    private val cancel: (MessageEntity) -> Unit,
    private val current: () -> Boolean,
) {
    enum class Result { DONE, RETRY, BLOCKED }

    suspend fun process(id: String): Result = IncomingNotificationGate.run {
        currentCoroutineContext().ensureActive()
        if (recipientId.isBlank() || !current()) return@run Result.RETRY
        val message = load(id) ?: return@run Result.DONE
        currentCoroutineContext().ensureActive()
        if (!current()) return@run Result.RETRY
        if (message.id != id || message.isFromMe || !message.notificationPending ||
            (message.recipientId.isNotEmpty() && message.recipientId != recipientId)
        ) return@run Result.DONE

        val suppressed = isSuppressed(message) || InlineImage.isPart(message.content) ||
            ChatPreviews.isServiceEnvelope(message.content)
        if (!suppressed) {
            val alreadyActive = isActive(message)
            currentCoroutineContext().ensureActive()
            if (!current()) return@run Result.RETRY
            if (!alreadyActive) {
                val kind = when {
                    SosMessage.isSos(message.content) -> IncomingAlert.SOS
                    QuietMessage.isQuiet(message.content) -> IncomingAlert.QUIET
                    else -> IncomingAlert.NORMAL
                }
                if (alert(message, kind) == IncomingNotificationPost.BLOCKED) {
                    // A system permission/channel may become available later.
                    // No flag is cleared and the periodic job will revisit it.
                    return@run Result.BLOCKED
                }
            }
        }
        // A cancelled or failed alert leaves the durable flag set for recovery.
        currentCoroutineContext().ensureActive()
        if (!current() || !complete(message)) {
            if (!suppressed) cancel(message)
            return@run Result.RETRY
        }
        Result.DONE
    }
}
