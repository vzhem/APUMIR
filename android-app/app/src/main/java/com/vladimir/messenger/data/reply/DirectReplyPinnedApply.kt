package com.vladimir.messenger.data.reply

import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Compare the signer of an already-opened wire with an existing durable pin.
 * The pin gate covers the database update, but never the subsequent network
 * ACK or mirror send. It does not serialize identity replacement, DB restore,
 * tombstones or work in another process. A TOFU pin is not identity proof.
 */
internal class DirectReplyPinnedApply(
    private val bindingFromWire: (String) -> ByteArray?,
    private val withPin: suspend (String, suspend (ByteArray?) -> Boolean?) -> Boolean?,
    private val apply: suspend (String, DirectReplyWire.Packet) -> Boolean,
) {
    /** null = no matching pin; false = matching pin but no matching row. */
    suspend fun apply(sender: String, packet: DirectReplyWire.Packet, openedWire: String): Boolean? {
        val candidate = bindingFromWire(openedWire)?.takeIf { it.isNotEmpty() } ?: return null
        return withPin(sender) { pin ->
            currentCoroutineContext().ensureActive()
            if (pin == null || !MessageDigest.isEqual(pin, candidate)) null
            else apply(sender, packet)
        }
    }
}
