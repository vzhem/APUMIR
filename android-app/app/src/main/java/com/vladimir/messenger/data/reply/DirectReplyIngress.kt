package com.vladimir.messenger.data.reply

import com.vladimir.messenger.util.NodeIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A recognised sidecar must not be mistaken for an ordinary chat message. */
internal enum class DirectReplyResult { NOT_PACKET, REJECTED, UNAPPLIED, APPLIED }

/**
 * Direct-reply metadata is authoritative only for a verified sender and a
 * matching incoming personal-chat row. The caller establishes sender binding
 * after opening the network envelope, and [applyNetwork] compares it with the
 * existing pin while the row is updated. Mirrors require a verified paired
 * frame and its source attestation. Remote chat IDs are deliberately not used:
 * local chat IDs differ between devices. A TOFU pin is not proof of a person's
 * identity, nor of the authenticity of the previously stored message body.
 *
 * An early or conflicting sidecar is not acknowledged here. There is no
 * durable pending-reply inbox yet, so withholding an ACK is not a promise of
 * redelivery. The clear check is a preflight, not an atomic identity or
 * network barrier.
 */
internal class DirectReplyIngress(
    private val isCleared: (String, String) -> Boolean,
    private val apply: suspend (String, DirectReplyWire.Packet) -> Boolean,
    // null means the network signer does not match a pre-existing pin.
    private val applyNetwork: suspend (String, DirectReplyWire.Packet, String) -> Boolean?,
    private val forwardVerified: (String, String, String, String) -> Unit,
    private val acknowledge: (String, String) -> Unit,
    private val recordFailure: (String, Exception) -> Unit,
) {
    suspend fun network(
        sender: String,
        chat: String,
        transportId: String,
        text: String,
        authenticated: Boolean,
        openedWire: String,
    ): DirectReplyResult = receive(sender, chat, transportId, text, authenticated, openedWire, fromMirror = false)

    suspend fun mirror(
        sender: String,
        transportId: String,
        text: String,
        authenticatedSource: Boolean,
    ): DirectReplyResult = receive(sender, "", transportId, text, authenticatedSource, "", fromMirror = true)

    private suspend fun receive(
        sender: String,
        chat: String,
        transportId: String,
        text: String,
        authenticated: Boolean,
        openedWire: String,
        fromMirror: Boolean,
    ): DirectReplyResult {
        currentCoroutineContext().ensureActive()
        if (!DirectReplyWire.isPacket(text)) return DirectReplyResult.NOT_PACKET
        if (!authenticated || !NodeIds.isNodeId(sender)) return DirectReplyResult.REJECTED
        val packet = DirectReplyWire.parse(text) ?: return DirectReplyResult.REJECTED
        val parent = packet.messageId
        if ('\u0000' in parent || '|' in parent || parent.toByteArray(Charsets.UTF_8).size > 128 ||
            DirectReplyWire.transportMessageId(parent) != transportId
        ) return DirectReplyResult.REJECTED
        val applied = try {
            if (isCleared(sender, parent)) return DirectReplyResult.REJECTED
            currentCoroutineContext().ensureActive()
            if (fromMirror) apply(sender, packet) else applyNetwork(sender, packet, openedWire)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure("apply", error)
            return DirectReplyResult.UNAPPLIED
        }
        currentCoroutineContext().ensureActive()
        if (applied == null) return DirectReplyResult.REJECTED
        if (!fromMirror && applied) {
            // Do not forward an unapplied sidecar: false can mean a conflicting
            // quote, not just a missing parent. A paired device with an empty
            // quote could otherwise accept the locally rejected version.
            // Early sidecars still need a durable inbox and mirror catch-up.
            sideEffect("forward") { forwardVerified(sender, chat, transportId, text) }
            currentCoroutineContext().ensureActive()
            sideEffect("ack") { acknowledge(transportId, sender) }
        }
        return if (applied) DirectReplyResult.APPLIED else DirectReplyResult.UNAPPLIED
    }

    private fun sideEffect(stage: String, action: () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure(stage, error)
        }
    }
}
