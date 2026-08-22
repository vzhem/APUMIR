package com.vladimir.messenger.data.file

/**
 * Resolves the transport chat scope to the recipient phone's local chat ID.
 *
 * Chat IDs are device-local UUIDs, so a sender's chat ID must never be persisted as-is when the
 * recipient already has its own chat for that contact. Direct file QUIC frames deliberately carry
 * [DIRECT_TRANSPORT_SCOPE] instead of pretending that a remote chat UUID is portable.
 */
internal object FileTransferChatRouting {
    const val DIRECT_TRANSPORT_SCOPE = "direct"

    /**
     * Returns the recipient's local chat when known. A non-direct transport scope is retained only
     * for the legacy path; the direct sentinel is never allowed to become a Room chat ID.
     */
    fun resolve(transportChatId: String, localChatId: String?): String? {
        localChatId?.takeIf { it.isNotBlank() }?.let { return it }
        return transportChatId.takeIf {
            it.isNotBlank() && it != DIRECT_TRANSPORT_SCOPE
        }
    }
}
