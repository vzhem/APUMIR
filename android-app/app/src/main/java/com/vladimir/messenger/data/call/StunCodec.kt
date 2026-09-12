package com.vladimir.messenger.data.call

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * Минимальный STUN (RFC 5389): Binding Request и разбор Binding Success с
 * XOR-MAPPED-ADDRESS (или устаревшим MAPPED-ADDRESS). Ровно столько, сколько
 * нужно, чтобы узнать свой внешний адрес:порт за NAT для пробивания UDP
 * (CallUdpChannel). Чистая JVM, проверяется unit-тестами побайтно.
 *
 * Внешний ресурс (публичные STUN-серверы) — вспомогательный: если он
 * недоступен, кандидатами остаются свои адреса (Wi-Fi IPv4, глобальный IPv6),
 * а голос при неудаче пробивания идёт мостом через брокер.
 */
object StunCodec {
    const val MAGIC_COOKIE = 0x2112A442
    const val TYPE_BINDING_REQUEST = 0x0001
    const val TYPE_BINDING_SUCCESS = 0x0101
    const val ATTR_MAPPED_ADDRESS = 0x0001
    const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    const val HEADER_BYTES = 20
    const val TX_ID_BYTES = 12

    fun bindingRequest(txId: ByteArray): ByteArray {
        require(txId.size == TX_ID_BYTES) { "STUN transaction id must be 12 bytes" }
        val buf = ByteBuffer.allocate(HEADER_BYTES)
        buf.putShort(TYPE_BINDING_REQUEST.toShort())
        buf.putShort(0)
        buf.putInt(MAGIC_COOKIE)
        buf.put(txId)
        return buf.array()
    }

    /** Похоже на STUN: два старших бита нули и magic cookie на месте. Наш провод начинается с 0x02/0xAC — не спутать. */
    fun isStun(bytes: ByteArray, length: Int): Boolean =
        length >= HEADER_BYTES && (bytes[0].toInt() and 0xC0) == 0 &&
            ByteBuffer.wrap(bytes, 4, 4).int == MAGIC_COOKIE

    /** @return отражённый адрес из Binding Success с нашим txId, иначе null. */
    fun parseBindingResponse(bytes: ByteArray, length: Int, txId: ByteArray): InetSocketAddress? {
        if (!isStun(bytes, length) || txId.size != TX_ID_BYTES) return null
        val buf = ByteBuffer.wrap(bytes, 0, length)
        val type = buf.short.toInt() and 0xFFFF
        if (type != TYPE_BINDING_SUCCESS) return null
        val messageLength = buf.short.toInt() and 0xFFFF
        buf.int // magic cookie, уже проверен
        val tx = ByteArray(TX_ID_BYTES)
        buf.get(tx)
        if (!tx.contentEquals(txId)) return null
        val end = HEADER_BYTES + messageLength
        if (end > length) return null
        var mapped: InetSocketAddress? = null
        var xorMapped: InetSocketAddress? = null
        while (buf.position() + 4 <= end) {
            val attrType = buf.short.toInt() and 0xFFFF
            val attrLength = buf.short.toInt() and 0xFFFF
            val attrStart = buf.position()
            if (attrStart + attrLength > end) return null
            when (attrType) {
                ATTR_XOR_MAPPED_ADDRESS -> xorMapped = parseAddress(bytes, attrStart, attrLength, xor = true, txId = txId)
                ATTR_MAPPED_ADDRESS -> mapped = parseAddress(bytes, attrStart, attrLength, xor = false, txId = txId)
            }
            // Атрибуты выровнены по 4 байта.
            val next = attrStart + ((attrLength + 3) and 3.inv())
            if (next > end) break
            buf.position(next)
        }
        return xorMapped ?: mapped
    }

    private fun parseAddress(bytes: ByteArray, offset: Int, length: Int, xor: Boolean, txId: ByteArray): InetSocketAddress? {
        if (length < 8) return null
        val family = bytes[offset + 1].toInt() and 0xFF
        var port = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        if (xor) port = port xor (MAGIC_COOKIE ushr 16)
        val addressLength = when (family) {
            0x01 -> 4
            0x02 -> 16
            else -> return null
        }
        if (length < 4 + addressLength) return null
        val address = bytes.copyOfRange(offset + 4, offset + 4 + addressLength)
        if (xor) {
            val cookie = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array()
            for (i in 0 until 4) address[i] = (address[i].toInt() xor cookie[i].toInt()).toByte()
            if (addressLength == 16) {
                for (i in 0 until TX_ID_BYTES) address[4 + i] = (address[4 + i].toInt() xor txId[i].toInt()).toByte()
            }
        }
        return try {
            InetSocketAddress(InetAddress.getByAddress(address), port)
        } catch (e: Exception) {
            null
        }
    }
}
