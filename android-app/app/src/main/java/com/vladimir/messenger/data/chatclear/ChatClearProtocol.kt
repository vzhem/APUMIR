package com.vladimir.messenger.data.chatclear

/** Transport-independent dispatcher. The caller must verify a sealed peer identity before setting authenticated. */
internal class ChatClearProtocol(
    private val acknowledge: suspend (peer: String, authenticated: Boolean, operation: java.util.UUID) -> Unit,
    apply: ChatClearReceiver.Apply,
    sendAck: suspend (String, String) -> Unit,
) {
    private val receiver = ChatClearReceiver(apply, sendAck)

    suspend fun receive(peer: String, authenticated: Boolean, text: String): Boolean {
        if (!ChatClearWire.isControl(text)) return false
        val ack = ChatClearWire.decodeAck(text)
        if (ack != null) {
            acknowledge(peer, authenticated, ack)
            return true
        }
        return receiver.receive(peer, authenticated, text)
    }
}
