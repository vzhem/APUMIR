package com.vladimir.messenger.data.file

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Versioned bounded transport fragmentation for encrypted file offers/chunks. */
object FileTransferPacketCodec {
    // 9 KiB wire frames: lossy/filtered networks pass small MQTT messages and silently stall
    // large ones (observed on the 2026-08-21 acceptance: ~33KB base64 fragments never arrived
    // while every small envelope flowed). 32 frames still cover the 256KiB+tag reassembly cap.
    const val VERSION: Byte = 1
    const val TRANSFER_ID_BYTES = 16
    const val MAX_FRAGMENT_PAYLOAD_BYTES = 9 * 1024
    const val MAX_FRAGMENTS = 512
    const val AUTH_DIGEST_BYTES = 16
    private const val HEADER_BYTES = 1 + 1 + TRANSFER_ID_BYTES + 4 + 2 + 2 + 2
    private const val MAX_REASSEMBLED_BYTES = 4 * 1024 * 1024 + 16
    private val DOMAIN = "apu-file-packet-v1\u0000".toByteArray(Charsets.US_ASCII)

    enum class Type(val wire: Byte) {
        OFFER(1),
        CHUNK(2),
        ACK(3),
        CANCEL(4);

        companion object {
            fun fromWire(value: Byte): Type = entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("Unknown file packet type")
        }
    }

    data class Packet(
        val type: Type,
        val transferId: ByteArray,
        val itemIndex: Int,
        val fragmentIndex: Int,
        val fragmentCount: Int,
        val payload: ByteArray,
    )

    fun fragment(
        type: Type,
        transferId: ByteArray,
        itemIndex: Int,
        payload: ByteArray,
    ): List<ByteArray> {
        validateTransferId(transferId)
        require(itemIndex >= 0)
        require(payload.isNotEmpty() && payload.size <= MAX_REASSEMBLED_BYTES)
        val count = (payload.size + MAX_FRAGMENT_PAYLOAD_BYTES - 1) / MAX_FRAGMENT_PAYLOAD_BYTES
        require(count in 1..MAX_FRAGMENTS)
        return List(count) { fragmentIndex ->
            val start = fragmentIndex * MAX_FRAGMENT_PAYLOAD_BYTES
            val end = minOf(start + MAX_FRAGMENT_PAYLOAD_BYTES, payload.size)
            encode(
                Packet(
                    type,
                    transferId.copyOf(),
                    itemIndex,
                    fragmentIndex,
                    count,
                    payload.copyOfRange(start, end),
                )
            )
        }
    }

    fun encode(packet: Packet): ByteArray {
        validate(packet)
        val unsigned = ByteBuffer.allocate(HEADER_BYTES + packet.payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VERSION)
            .put(packet.type.wire)
            .put(packet.transferId)
            .putInt(packet.itemIndex)
            .putShort(packet.fragmentIndex.toShort())
            .putShort(packet.fragmentCount.toShort())
            .putShort(packet.payload.size.toShort())
            .put(packet.payload)
            .array()
        val digest = packetDigest(unsigned)
        return unsigned + digest
    }

    fun decode(bytes: ByteArray): Packet {
        require(bytes.size in (HEADER_BYTES + 1 + AUTH_DIGEST_BYTES)..
            (HEADER_BYTES + MAX_FRAGMENT_PAYLOAD_BYTES + AUTH_DIGEST_BYTES))
        val unsignedSize = bytes.size - AUTH_DIGEST_BYTES
        val unsigned = bytes.copyOfRange(0, unsignedSize)
        val suppliedDigest = bytes.copyOfRange(unsignedSize, bytes.size)
        require(MessageDigest.isEqual(packetDigest(unsigned), suppliedDigest)) {
            "File packet digest mismatch"
        }
        val input = ByteBuffer.wrap(unsigned).order(ByteOrder.BIG_ENDIAN)
        require(input.get() == VERSION) { "Unsupported file packet version" }
        val type = Type.fromWire(input.get())
        val transferId = ByteArray(TRANSFER_ID_BYTES).also(input::get)
        val itemIndex = input.int
        val fragmentIndex = input.short.toInt() and 0xffff
        val fragmentCount = input.short.toInt() and 0xffff
        val payloadSize = input.short.toInt() and 0xffff
        require(payloadSize == input.remaining()) { "File packet payload length mismatch" }
        val payload = ByteArray(payloadSize).also(input::get)
        return Packet(type, transferId, itemIndex, fragmentIndex, fragmentCount, payload)
            .also(::validate)
    }

    fun reassemble(encodedFragments: List<ByteArray>): ByteArray {
        require(encodedFragments.isNotEmpty() && encodedFragments.size <= MAX_FRAGMENTS)
        val packets = encodedFragments.map(::decode)
        val first = packets.first()
        require(packets.size == first.fragmentCount)
        require(packets.all {
            it.type == first.type &&
                it.itemIndex == first.itemIndex &&
                it.fragmentCount == first.fragmentCount &&
                it.transferId.contentEquals(first.transferId)
        }) { "Mixed file packet fragments" }
        val byIndex = packets.associateBy { it.fragmentIndex }
        require(byIndex.size == packets.size && byIndex.keys == (0 until first.fragmentCount).toSet()) {
            "Missing or duplicate file packet fragment"
        }
        val total = packets.sumOf { it.payload.size }
        require(total in 1..MAX_REASSEMBLED_BYTES)
        return ByteArray(total).also { output ->
            var offset = 0
            for (index in 0 until first.fragmentCount) {
                val payload = byIndex.getValue(index).payload
                payload.copyInto(output, offset)
                offset += payload.size
            }
        }
    }

    private fun validate(packet: Packet) {
        validateTransferId(packet.transferId)
        require(packet.itemIndex >= 0)
        require(packet.fragmentCount in 1..MAX_FRAGMENTS)
        require(packet.fragmentIndex in 0 until packet.fragmentCount)
        require(packet.payload.size in 1..MAX_FRAGMENT_PAYLOAD_BYTES)
    }

    private fun validateTransferId(value: ByteArray) {
        require(value.size == TRANSFER_ID_BYTES && value.any { it != 0.toByte() })
    }

    private fun packetDigest(unsigned: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256")
        .apply { update(DOMAIN); update(unsigned) }
        .digest()
        .copyOf(AUTH_DIGEST_BYTES)
}
