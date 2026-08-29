package com.vladimir.messenger.data.referral

/**
 * Читатель двух криптографических конвертов rust-core.
 *
 * Форматы взяты один в один из ядра, ничего нового не придумано:
 * - привязка identity, `SignedIdentityBindingV1::to_bytes`
 *   (`rust-core/src/crypto/signing_identity.rs`):
 *   `[version=1][legacy_len:u16][legacy][pubkey 32][created_at i64][signature 64]`;
 * - токен приглашения, `IdentityBoundReferralInviteV1::to_bytes`:
 *   `[version=1][binding_len:u16][binding][nonce 16][created i64][expires i64][signature 64]`.
 *
 * Здесь только ЧТЕНИЕ полей. Подлинность проверяет само ядро
 * (`verifyIdentitySigningBinding`, `verifyReferralInviteToken`), а подпись
 * покрывает и идентификатор узла, и метки времени, поэтому прочитанные значения
 * нельзя подделать, не имея приватного ключа владельца.
 *
 * Класс намеренно без Android-зависимостей: разбор покрыт host-тестами и
 * выполняется в гейте.
 */
object ReferralReceipt {

    const val FORMAT_V1 = 1
    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64
    const val NONCE_BYTES = 16
    const val TIME_BYTES = 8

    /** Предел из `SignedIdentityBindingV1::from_bytes`: узел не длиннее 67 байт. */
    const val MAX_LEGACY_NODE_ID_BYTES = 67

    /** `IdentitySigningKeyStore.MAX_REFERRAL_TOKEN_BYTES` и предел ссылки. */
    const val MAX_TOKEN_BYTES = 512
    const val MAX_BINDING_BYTES = 256

    private const val BINDING_FIXED_BYTES = 1 + 2 + PUBLIC_KEY_BYTES + TIME_BYTES + SIGNATURE_BYTES
    private const val TOKEN_TAIL_BYTES = NONCE_BYTES + TIME_BYTES + TIME_BYTES + SIGNATURE_BYTES

    data class IdentityBinding(
        val nodeId: String,
        val createdAtMs: Long,
    )

    data class InviteToken(
        val inviterNodeId: String,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        /** Когда была создана identity пригласившего (вложена в токен). */
        val inviterIdentityCreatedAtMs: Long,
    )

    fun parseIdentityBinding(bytes: ByteArray): IdentityBinding? {
        if (bytes.size < BINDING_FIXED_BYTES) return null
        if (bytes[0].toInt() != FORMAT_V1) return null
        val legacyLength = u16(bytes, 1)
        if (legacyLength <= 0 || legacyLength > MAX_LEGACY_NODE_ID_BYTES) return null
        if (bytes.size != 1 + 2 + legacyLength + PUBLIC_KEY_BYTES + TIME_BYTES + SIGNATURE_BYTES) return null

        // Ядро требует hex строго в нижнем регистре (is_legacy_routing_node_id),
        // поэтому здесь никакой нормализации: что подписано, то и читаем.
        val rawNodeId = asciiField(bytes, 3, legacyLength) ?: return null
        val nodeId = rawNodeId.takeIf { it == ReferralWire.canonicalNodeId(it) } ?: return null
        val publicKeyEnd = 3 + legacyLength + PUBLIC_KEY_BYTES
        return IdentityBinding(nodeId, i64(bytes, publicKeyEnd))
    }

    fun parseInviteToken(bytes: ByteArray): InviteToken? {
        if (bytes.size < 1 + 2 + TOKEN_TAIL_BYTES) return null
        if (bytes[0].toInt() != FORMAT_V1) return null
        val bindingLength = u16(bytes, 1)
        val bindingEnd = 3 + bindingLength
        if (bindingLength <= 0 || bytes.size != bindingEnd + TOKEN_TAIL_BYTES) return null

        val binding = parseIdentityBinding(bytes.copyOfRange(3, bindingEnd)) ?: return null
        val nonceEnd = bindingEnd + NONCE_BYTES
        val createdEnd = nonceEnd + TIME_BYTES
        return InviteToken(
            inviterNodeId = binding.nodeId,
            createdAtMs = i64(bytes, nonceEnd),
            expiresAtMs = i64(bytes, createdEnd),
            inviterIdentityCreatedAtMs = binding.createdAtMs,
        )
    }

    private fun asciiField(bytes: ByteArray, offset: Int, length: Int): String? {
        val builder = StringBuilder(length)
        for (index in offset until offset + length) {
            val byte = bytes[index].toInt()
            if (byte < 0x20 || byte > 0x7E) return null
            builder.append(byte.toChar())
        }
        return builder.toString()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun i64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in offset until offset + TIME_BYTES) {
            value = (value shl 8) or (bytes[index].toLong() and 0xFF)
        }
        return value
    }
}
