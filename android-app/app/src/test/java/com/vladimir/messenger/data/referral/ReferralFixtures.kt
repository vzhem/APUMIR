package com.vladimir.messenger.data.referral

/**
 * Синтетические конверты rust-core для host-тестов.
 *
 * Форма байтов настоящая (см. ReferralReceipt), подписи нет: подпись проверяет
 * ядро на телефоне, а здесь проверяются разбор формата и правила зачисления.
 */
internal object ReferralFixtures {

    val INVITEE: String = "pk_" + "11".repeat(32)
    val INVITER: String = "pk_" + "22".repeat(32)
    val STRANGER: String = "pk_" + "33".repeat(32)

    const val NOW: Long = 1_800_000_000_000L
    const val WEEK_MS: Long = 7L * 24 * 60 * 60 * 1000

    fun u16(value: Int): ByteArray =
        byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    fun i64(value: Long): ByteArray {
        val out = ByteArray(8)
        for (index in 0 until 8) {
            out[index] = ((value shr (8 * (7 - index))) and 0xFF).toByte()
        }
        return out
    }

    fun binding(nodeId: String = INVITEE, createdAtMs: Long = NOW - 120_000): ByteArray {
        val legacy = nodeId.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(ReferralReceipt.FORMAT_V1.toByte()) + u16(legacy.size) + legacy +
            ByteArray(ReferralReceipt.PUBLIC_KEY_BYTES) + i64(createdAtMs) +
            ByteArray(ReferralReceipt.SIGNATURE_BYTES)
    }

    fun token(
        inviterNodeId: String = INVITER,
        inviterIdentityCreatedAtMs: Long = NOW - WEEK_MS,
        createdAtMs: Long = NOW - 60_000,
        expiresAtMs: Long = NOW + WEEK_MS,
    ): ByteArray {
        val inner = binding(inviterNodeId, inviterIdentityCreatedAtMs)
        return byteArrayOf(ReferralReceipt.FORMAT_V1.toByte()) + u16(inner.size) + inner +
            ByteArray(ReferralReceipt.NONCE_BYTES) + i64(createdAtMs) + i64(expiresAtMs) +
            ByteArray(ReferralReceipt.SIGNATURE_BYTES)
    }

    /** Подписанный конверт второй версии в том виде, в каком он идёт по проводу. */
    fun signedEnvelope(
        inviteeNodeId: String = INVITEE,
        inviterNodeId: String = INVITER,
        qualifiedAtMs: Long = NOW,
        tokenBytes: ByteArray = token(inviterNodeId),
        bindingBytes: ByteArray = binding(inviteeNodeId),
    ): String = ReferralWire.buildSignedAttribution(
        inviteeNodeId = inviteeNodeId,
        inviterNodeId = inviterNodeId,
        qualifiedAtMs = qualifiedAtMs,
        token = tokenBytes,
        binding = bindingBytes,
    )!!
}
