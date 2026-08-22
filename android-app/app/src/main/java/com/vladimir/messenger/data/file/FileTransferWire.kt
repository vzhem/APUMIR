package com.vladimir.messenger.data.file

import java.util.Base64

/**
 * Maps encoded file packets to/from the phone-owned durable text transport (Rust send path:
 * direct QUIC or the encrypted durable relay custody). One mesh message carries exactly one
 * already-encoded packet fragment, so relay-level per-message-id dedup keeps retransmits cheap.
 *
 * Message IDs are deterministic per (transfer, item, fragment): a re-pushed packet keeps its ID,
 * the mesh rejects duplicates and the receiver stays idempotent.
 */
object FileTransferWire {
    const val PREFIX = "apu-file1|"
    const val MAX_WIRE_CHARS = 48 * 1024
    const val MAX_MESSAGE_ID_BYTES = 128

    fun isFilePacketText(text: String): Boolean =
        text.length <= MAX_WIRE_CHARS && text.startsWith(PREFIX)

    fun encodeEncodedPacket(encodedPacket: ByteArray): String {
        require(encodedPacket.isNotEmpty() && encodedPacket.size <= maxEncodedPacketBytes()) {
            "Invalid encoded packet size"
        }
        val wire = PREFIX + Base64.getEncoder().encodeToString(encodedPacket)
        require(wire.length <= MAX_WIRE_CHARS) { "File packet exceeds wire budget" }
        return wire
    }

    fun decodeToEncodedPacket(text: String): ByteArray {
        require(isFilePacketText(text)) { "Not a file packet message" }
        val encoded = Base64.getDecoder().decode(text.removePrefix(PREFIX))
        require(encoded.isNotEmpty() && encoded.size <= maxEncodedPacketBytes()) {
            "Decoded file packet out of bounds"
        }
        return encoded
    }

    fun offerMessageId(transferIdHex: String, fragmentIndex: Int): String {
        requireValidTransferId(transferIdHex)
        require(fragmentIndex in 0..FileTransferPacketCodec.MAX_FRAGMENTS)
        return "f${transferIdHex}o$fragmentIndex".also(::requireValidMessageId)
    }

    fun chunkMessageId(transferIdHex: String, chunkIndex: Int, fragmentIndex: Int): String {
        requireValidTransferId(transferIdHex)
        require(chunkIndex >= 0)
        require(fragmentIndex in 0..FileTransferPacketCodec.MAX_FRAGMENTS)
        return "f${transferIdHex}c${chunkIndex}f$fragmentIndex".also(::requireValidMessageId)
    }

    fun ackMessageId(transferIdHex: String, contiguousChunks: Int): String {
        requireValidTransferId(transferIdHex)
        require(contiguousChunks >= 0)
        return "f${transferIdHex}a$contiguousChunks".also(::requireValidMessageId)
    }

    fun chatPlaceholderMessageId(transferIdHex: String): String {
        requireValidTransferId(transferIdHex)
        return "file-$transferIdHex".also(::requireValidMessageId)
    }

    // ── File-HELLO: signed exchange-binding handshake (breaks the first-file deadlock) ──

    const val HELLO_PREFIX = "apu-file-hello1|"
    const val MAX_HELLO_BINDING_BYTES = 512

    fun isHelloText(text: String): Boolean =
        text.length <= 4 * 1024 && text.startsWith(HELLO_PREFIX)

    fun encodeHelloBinding(binding: ByteArray): String {
        require(binding.size in 1..MAX_HELLO_BINDING_BYTES) { "Invalid hello binding size" }
        return HELLO_PREFIX + Base64.getEncoder().encodeToString(binding)
    }

    fun decodeHelloBinding(text: String): ByteArray {
        require(isHelloText(text)) { "Not a file hello message" }
        val binding = Base64.getDecoder().decode(text.removePrefix(HELLO_PREFIX))
        require(binding.size in 1..MAX_HELLO_BINDING_BYTES) { "Decoded hello binding out of bounds" }
        return binding
    }

    /** Deterministic per-direction pair ID so relay dedup keeps repeated handshakes cheap. */
    fun helloMessageId(myNodeId: String, recipientNodeId: String): String {
        require(myNodeId.isNotEmpty() && recipientNodeId.isNotEmpty())
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((myNodeId + "|" + recipientNodeId).toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(32)
        return "fh$digest".also(::requireValidMessageId)
    }

    fun transferIdHexFromPacket(packet: FileTransferPacketCodec.Packet): String =
        packet.transferId.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun requireValidTransferId(transferIdHex: String) {
        require(transferIdHex.matches(Regex("^[0-9a-f]{32}$"))) { "Invalid transfer ID" }
    }

    private fun requireValidMessageId(messageId: String) {
        require(messageId.isNotEmpty() && messageId.toByteArray(Charsets.US_ASCII).size <= MAX_MESSAGE_ID_BYTES) {
            "File message ID too long"
        }
        require(!messageId.contains('|')) { "File message ID must be delimiter-safe" }
    }

    private fun maxEncodedPacketBytes(): Int =
        FileTransferPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES + 64 + FileTransferPacketCodec.AUTH_DIGEST_BYTES
}
