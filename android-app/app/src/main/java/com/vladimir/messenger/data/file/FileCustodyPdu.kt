package com.vladimir.messenger.data.file

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Тело пакета [FileTransferPacketCodec.Type.CUSTODY_OFFER] - «подержи файл
 * для того, кто сейчас не в сети» (этап 7 роя, хранение у третьего телефона).
 *
 * Тот же самый байтовый пакет ходит по обоим плечам: отправитель → хранитель
 * и хранитель → получатель. Внутри - всё, что нужно получателю, чтобы принять
 * файл так, будто он пришёл напрямую: манифест, конверт с ключом (запечатан
 * ТОЛЬКО для получателя, хранитель его не вскроет) и подписанная привязка
 * отправителя. Хранитель видит имя, размер и число кусков (это есть в
 * манифесте), но не содержимое: куски идут к нему уже зашифрованными ключом
 * файла, а ключа у него нет.
 *
 * Кто есть кто, решают два поля: [Custody.originId] - чей файл,
 * [Custody.recipientId] - кому. Телефон, получивший пакет, сравнивает
 * recipientId с собой: «это мне» - принять как пересланное предложение,
 * «не мне» - взять на хранение (если отправитель - контакт и место есть).
 *
 * Формат (big-endian): ver(1)=1 | len(1) originId | len(1) recipientId |
 * len(2) manifest | len(2) keyEnvelope | len(2) senderBinding. Никаких
 * хвостов: лишние байты - ошибка.
 */
object FileCustodyPdu {
    const val VERSION: Byte = 1
    const val MAX_NODE_ID_BYTES = 128
    private const val HEADER_BYTES = 1 + 1 + 1 + 2 + 2 + 2

    /** Статусы подтверждения [FileTransferPacketCodec.Type.CUSTODY_ACK] (один байт payload). */
    const val ACK_OK: Byte = 1
    /** Хранитель отказал по политике (не контакт, не тот адресат, ключ сменился). */
    const val ACK_REFUSED: Byte = 2
    /** У хранителя нет места под пересылку - отправитель идёт к следующему. */
    const val ACK_FULL: Byte = 3

    data class Custody(
        val originId: String,
        val recipientId: String,
        val manifest: ByteArray,
        val keyEnvelope: ByteArray,
        val senderBinding: ByteArray,
    ) {
        /** Предложение в том виде, в каком его понимает обычный приёмник. */
        fun asOffer(): FileOfferPdu.Offer = FileOfferPdu.Offer(manifest, keyEnvelope, senderBinding)
    }

    fun maxEncodedBytes(): Int =
        HEADER_BYTES + 2 * MAX_NODE_ID_BYTES + FileOfferPdu.MAX_MANIFEST_BYTES +
            FileOfferPdu.MAX_ENVELOPE_BYTES + FileOfferPdu.MAX_BINDING_BYTES

    fun encode(
        originId: String,
        recipientId: String,
        manifest: ByteArray,
        keyEnvelope: ByteArray,
        senderBinding: ByteArray,
    ): ByteArray {
        val origin = nodeIdBytes(originId)
        val recipient = nodeIdBytes(recipientId)
        require(originId != recipientId) { "Custody origin and recipient must differ" }
        require(manifest.size in FileOfferPdu.MIN_MANIFEST_BYTES..FileOfferPdu.MAX_MANIFEST_BYTES) {
            "Invalid manifest size"
        }
        require(keyEnvelope.size in FileOfferPdu.MIN_ENVELOPE_BYTES..FileOfferPdu.MAX_ENVELOPE_BYTES) {
            "Invalid key envelope size"
        }
        require(senderBinding.size in 1..FileOfferPdu.MAX_BINDING_BYTES) { "Invalid sender binding size" }
        return ByteBuffer.allocate(
            HEADER_BYTES + origin.size + recipient.size + manifest.size + keyEnvelope.size + senderBinding.size,
        )
            .order(ByteOrder.BIG_ENDIAN)
            .put(VERSION)
            .put(origin.size.toByte())
            .put(origin)
            .put(recipient.size.toByte())
            .put(recipient)
            .putShort(manifest.size.toShort())
            .put(manifest)
            .putShort(keyEnvelope.size.toShort())
            .put(keyEnvelope)
            .putShort(senderBinding.size.toShort())
            .put(senderBinding)
            .array()
    }

    fun decode(bytes: ByteArray): Custody {
        require(bytes.size in (HEADER_BYTES + 2 + FileOfferPdu.MIN_MANIFEST_BYTES + FileOfferPdu.MIN_ENVELOPE_BYTES + 1)..maxEncodedBytes()) {
            "Invalid custody offer size"
        }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(input.get() == VERSION) { "Unsupported custody offer version" }
        val originId = readNodeId(input)
        val recipientId = readNodeId(input)
        require(originId != recipientId) { "Custody origin and recipient must differ" }
        val manifest = readLengthPrefixed(input, FileOfferPdu.MIN_MANIFEST_BYTES, FileOfferPdu.MAX_MANIFEST_BYTES)
        val keyEnvelope = readLengthPrefixed(input, FileOfferPdu.MIN_ENVELOPE_BYTES, FileOfferPdu.MAX_ENVELOPE_BYTES)
        val senderBinding = readLengthPrefixed(input, 1, FileOfferPdu.MAX_BINDING_BYTES)
        require(!input.hasRemaining()) { "Trailing bytes in custody offer" }
        return Custody(originId, recipientId, manifest, keyEnvelope, senderBinding)
    }

    /** Один байт статуса подтверждения; всё остальное - мусор. */
    fun ackStatus(payload: ByteArray): Byte? {
        if (payload.size != 1) return null
        return payload[0].takeIf { it == ACK_OK || it == ACK_REFUSED || it == ACK_FULL }
    }

    private fun nodeIdBytes(nodeId: String): ByteArray {
        val bytes = nodeId.toByteArray(Charsets.US_ASCII)
        require(
            nodeId.startsWith("pk_") && bytes.size in 4..MAX_NODE_ID_BYTES &&
                nodeId.substring(3).all { it in '0'..'9' || it in 'a'..'f' },
        ) { "Invalid custody node id" }
        return bytes
    }

    private fun readNodeId(input: ByteBuffer): String {
        require(input.hasRemaining()) { "Truncated custody node id" }
        val length = input.get().toInt() and 0xff
        require(length in 4..MAX_NODE_ID_BYTES) { "Custody node id length out of bounds" }
        require(input.remaining() >= length) { "Truncated custody node id" }
        val bytes = ByteArray(length)
        input.get(bytes)
        val nodeId = String(bytes, Charsets.US_ASCII)
        require(nodeId.startsWith("pk_") && nodeId.substring(3).all { it in '0'..'9' || it in 'a'..'f' }) {
            "Malformed custody node id"
        }
        return nodeId
    }

    private fun readLengthPrefixed(input: ByteBuffer, minimum: Int, maximum: Int): ByteArray {
        require(input.remaining() >= 2) { "Truncated custody field length" }
        val length = input.short.toInt() and 0xffff
        require(length in minimum..maximum) { "Custody field length out of bounds" }
        require(input.remaining() >= length) { "Truncated custody field" }
        val bytes = ByteArray(length)
        input.get(bytes)
        return bytes
    }
}
