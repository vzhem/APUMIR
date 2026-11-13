package com.vladimir.messenger.data.push

/**
 * Decides what a provider signal may do. This is deliberately the narrowest
 * possible slice and performs no I/O: it validates provider *shape*, then
 * separates "this looks like our data-only signal" from everything else.
 *
 * A provider signal is never a message, never a delivery receipt and never
 * authorization. FCM payloads are not authenticated: anyone with the
 * registration token (for example the service operator, a compromised console,
 * or a leaked token) can post this shape. Therefore a signal may only request
 * that the app look in the encrypted mailbox; it may never carry text, a
 * sender, a chat id, a pin, a route, or a number of unread messages.
 *
 * Expiry and message identity come from the mailbox response, which the client
 * authenticates with the pinned peer. Nothing in this class may be used to
 * decide that a message "arrived".
 */
internal object PushSignalPolicy {
    /** Exact field set of a supported data-only signal. */
    private val SIGNAL_FIELDS = setOf("v")
    /** Notification blocks Google could render without the app running. */
    private val FORBIDDEN_BLOCKS = setOf("notification", "android.notification", "webpush")

    sealed interface Decision {
        /** A shape-valid v1 signal: schedule a bounded mailbox check. */
        object LookInMailbox : Decision
        /** Structurally fine, but this app version does not act on it. */
        object Ignore : Decision
        /** Not our protocol. Never render, never schedule, never ACK. */
        object Reject : Decision
        /** Provider (re)delivered messages while the app was away. */
        object CheckMailboxAfterGap : Decision
    }

    /**
     * [data] is the provider map. Extra keys are rejected rather than ignored,
     * so a future field cannot silently change meaning on an older client.
     * A payload carrying an automatic notification block is rejected even if
     * the data is otherwise valid: such a payload would prove nothing about
     * the mailbox and could show unauthenticated text.
     */
    fun inspect(data: Map<String, String>?, hasNotificationBlock: Boolean): Decision {
        if (hasNotificationBlock) return Decision.Reject
        if (data == null) return Decision.Reject
        if (data.keys != SIGNAL_FIELDS) return Decision.Reject
        if (data["v"] != SUPPORTED_VERSION) return Decision.Ignore
        return Decision.LookInMailbox
    }

    /**
     * `onDeletedMessages` means the provider dropped signals while the device
     * was unreachable. The only honest reaction is a full mailbox check; it is
     * not evidence that tokens are valid, that the app is reachable or that
     * anything was delivered.
     */
    fun afterGap(): Decision = Decision.CheckMailboxAfterGap

    /** A provider payload may not carry any of these, even as an extension. */
    fun carriesForbiddenBlock(keys: Set<String>): Boolean =
        keys.any { it.lowercase() in FORBIDDEN_BLOCKS }

    private const val SUPPORTED_VERSION = "1"
}
