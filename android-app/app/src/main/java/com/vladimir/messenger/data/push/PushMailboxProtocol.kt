package com.vladimir.messenger.data.push

// java.util.Base64 is available on API 26, the application's minSdk, and on the JVM.
import java.security.MessageDigest
import java.util.Base64

/**
 * Wire shapes only. Structural checks do not authenticate or decrypt an
 * envelope. Mailbox data, receipts and capabilities must never be logged.
 */
internal object PushMailboxProtocol {
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    const val MAX_ENVELOPE_CHARS = 192 * 1024
    const val MAX_ACK_ITEMS = 16
    const val MAX_PAGE_ITEMS = 4
    const val MAX_RESPONSE_BYTES = MAX_PAGE_ITEMS * MAX_ENVELOPE_CHARS + 4096
    private val HEX_16 = Regex("^[0-9a-f]{32}$")
    private val HEX_32 = Regex("^[0-9a-f]{64}$")
    private val HEX_64 = Regex("^[0-9a-f]{128}$")

    fun validId(value: String): Boolean = HEX_16.matches(value) && value.any { it != '0' }
    fun validRoute(owner: String, capability: String): Boolean =
        HEX_32.matches(owner) && owner.any { it != '0' } &&
            HEX_32.matches(capability) && capability.any { it != '0' }
    fun validSignature(value: String): Boolean = HEX_64.matches(value)
    fun validTimestamp(value: Long): Boolean = value in 1..MAX_SAFE_INTEGER

    /**
     * Outside id is the first 16 bytes of a domain-separated SHA-256 of the
     * immutable expiry and wire. It is NOT an authenticated original message id.
     * The ciphertext must have fresh encryption randomness. Persist all three
     * values before the first put; never extend the cutoff or re-seal a retry.
     * The Worker checks the cutoff on its own clock, including at commit time.
     */
    fun putId(expiresAt: Long, envelope: String): String? {
        if (!validTimestamp(expiresAt) || !validEnvelope(envelope)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("apu-mailbox-put-id-v1\n".toByteArray(Charsets.US_ASCII))
        digest.update(expiresAt.toString().toByteArray(Charsets.US_ASCII))
        digest.update('\n'.code.toByte())
        val wire = envelope.toByteArray(Charsets.US_ASCII)
        val hash = try {
            digest.update(wire)
            digest.update('\n'.code.toByte())
            digest.digest()
        } finally { wire.fill(0) }
        return try {
            hash.take(16).joinToString("") { "%02x".format(it.toInt() and 255) }.takeIf(::validId)
        } finally { hash.fill(0) }
    }

    /** Same bounded, canonical structure accepted by the experimental Worker. */
    fun validEnvelope(value: String): Boolean {
        if (value.length > MAX_ENVELOPE_CHARS || !value.startsWith("APUSEAL1|")) return false
        // Find the two remaining separators without split(): an untrusted
        // delimiter flood must not allocate an array of thousands of fields.
        val keyEnd = value.indexOf('|', "APUSEAL1|".length)
        if (keyEnd < 0) return false
        val ivEnd = value.indexOf('|', keyEnd + 1)
        if (ivEnd < 0 || value.indexOf('|', ivEnd + 1) >= 0) return false
        val key = decodeBase64(value.substring("APUSEAL1|".length, keyEnd), 204, 715) ?: return false
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        return try {
            iv = decodeBase64(value.substring(keyEnd + 1, ivEnd), 12, 12) ?: return false
            ciphertext = decodeBase64(value.substring(ivEnd + 1), 17, 64 * 1024 + 16) ?: return false
            if (key[0] != 1.toByte()) return false
            val bindingSize = ((key[1].toInt() and 255) shl 8) or (key[2].toInt() and 255)
            bindingSize in 1..512 && key.size == bindingSize + 203
        } finally {
            key.fill(0)
            iv?.fill(0)
            ciphertext?.fill(0)
        }
    }

    private fun decodeBase64(value: String, minimum: Int, maximum: Int): ByteArray? {
        if (value.isEmpty() || value.length % 4 != 0 || value.length > ((maximum + 2) / 3) * 4) return null
        return try {
            val decoded = Base64.getDecoder().decode(value)
            if (decoded.size !in minimum..maximum || Base64.getEncoder().encodeToString(decoded) != value) {
                decoded.fill(0)
                null
            } else decoded
        } catch (_: IllegalArgumentException) {
            // Invalid Base64 from a remote endpoint is an ordinary rejection.
            null
        }
    }
}

/** No data class: generated toString/copy would expose capability and ciphertext. */
internal class PushMailboxPutRequest(
    private val owner: String,
    private val capability: String,
    private val id: String,
    internal val expiresAt: Long,
    private val envelope: String,
) {
    internal fun isValid(): Boolean = PushMailboxProtocol.validRoute(owner, capability) &&
        PushMailboxProtocol.validId(id) && PushMailboxProtocol.putId(expiresAt, envelope) == id

    internal fun toJsonBytes(): ByteArray = (
        "{\"owner\":\"$owner\",\"capability\":\"$capability\",\"id\":\"$id\",\"expiresAt\":$expiresAt,\"envelope\":\"$envelope\"}"
        ).toByteArray(Charsets.US_ASCII)

    override fun toString(): String = "PushMailboxPutRequest(redacted)"
}

internal class PushMailboxPullRequest(
    private val owner: String,
    private val capability: String,
    private val device: String,
    private val issuedAt: Long,
    internal val after: String,
    private val signature: String,
) {
    internal fun isValid(): Boolean = PushMailboxProtocol.validRoute(owner, capability) &&
        PushMailboxProtocol.validId(device) && PushMailboxProtocol.validTimestamp(issuedAt) &&
        (after == "-" || PushMailboxProtocol.validId(after)) && PushMailboxProtocol.validSignature(signature)

    internal fun toJsonBytes(): ByteArray = (
        "{\"owner\":\"$owner\",\"capability\":\"$capability\",\"device\":\"$device\",\"issuedAt\":$issuedAt," +
            "\"after\":\"$after\",\"signature\":\"$signature\"}"
        ).toByteArray(Charsets.US_ASCII)

    override fun toString(): String = "PushMailboxPullRequest(redacted)"
}

internal class PushMailboxReceipt(internal val id: String, internal val receipt: String) {
    internal fun isValid(): Boolean = PushMailboxProtocol.validId(id) && PushMailboxProtocol.validId(receipt)
    override fun toString(): String = "PushMailboxReceipt(redacted)"
}

internal class PushMailboxAckRequest(
    private val owner: String,
    private val capability: String,
    private val device: String,
    private val issuedAt: Long,
    receipts: List<PushMailboxReceipt>,
    private val signature: String,
) {
    // Copy only a bounded number: too many items remain invalid, not truncated into a valid ACK.
    private val receipts = receipts.take(PushMailboxProtocol.MAX_ACK_ITEMS + 1)

    internal fun isValid(): Boolean = PushMailboxProtocol.validRoute(owner, capability) &&
        PushMailboxProtocol.validId(device) && PushMailboxProtocol.validTimestamp(issuedAt) &&
        PushMailboxProtocol.validSignature(signature) && receipts.size in 1..PushMailboxProtocol.MAX_ACK_ITEMS &&
        receipts.all { it.isValid() } && receipts.zipWithNext().all { (first, next) -> first.id < next.id }

    internal fun toJsonBytes(): ByteArray {
        val entries = receipts.joinToString(",") { "{\"id\":\"${it.id}\",\"receipt\":\"${it.receipt}\"}" }
        return ("{\"owner\":\"$owner\",\"capability\":\"$capability\",\"device\":\"$device\",\"issuedAt\":$issuedAt," +
            "\"items\":[$entries],\"signature\":\"$signature\"}").toByteArray(Charsets.US_ASCII)
    }

    override fun toString(): String = "PushMailboxAckRequest(redacted)"
}

internal class PushMailboxItem(
    internal val id: String,
    internal val envelope: String,
    internal val expiresAt: Long,
    internal val receipt: String,
) {
    override fun toString(): String = "PushMailboxItem(redacted)"
}
