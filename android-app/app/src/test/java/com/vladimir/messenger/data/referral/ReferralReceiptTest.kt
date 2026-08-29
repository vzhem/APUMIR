package com.vladimir.messenger.data.referral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор конвертов rust-core.
 *
 * Подписи здесь подделать нельзя, поэтому байты собираются синтетически
 * ([ReferralFixtures]) — проверяется чтение полей и отказ на любом отклонении
 * от формата. Подлинность на телефоне проверяет ядро (ReferralReceiptVerifier).
 */
class ReferralReceiptTest {

    private val nodeId = ReferralFixtures.INVITEE
    private val createdAt = ReferralFixtures.NOW

    @Test
    fun identityBindingIsReadFieldByField() {
        val parsed = ReferralReceipt.parseIdentityBinding(ReferralFixtures.binding(createdAtMs = createdAt))
        assertNotNull(parsed)
        assertEquals(nodeId, parsed!!.nodeId)
        assertEquals(createdAt, parsed.createdAtMs)
    }

    @Test
    fun longNodeIdFormIsAccepted() {
        val long = "pk_" + "abcdef0123456789".repeat(4)
        val parsed = ReferralReceipt.parseIdentityBinding(ReferralFixtures.binding(long))
        assertEquals(long, parsed?.nodeId)
        assertEquals(long.length, ReferralReceipt.MAX_LEGACY_NODE_ID_BYTES)
    }

    @Test
    fun malformedBindingsAreRejected() {
        val good = ReferralFixtures.binding(createdAtMs = createdAt)
        assertNull(ReferralReceipt.parseIdentityBinding(ByteArray(0)))
        assertNull(ReferralReceipt.parseIdentityBinding(good.copyOfRange(0, good.size - 1)))
        assertNull(ReferralReceipt.parseIdentityBinding(good + byteArrayOf(0)))
        // Другая версия конверта
        assertNull(ReferralReceipt.parseIdentityBinding(byteArrayOf(2) + good.copyOfRange(1, good.size)))
        // Длина узла в заголовке не совпадает с фактической
        val lied = good.copyOf()
        lied[2] = (lied[2] + 4).toByte()
        assertNull(ReferralReceipt.parseIdentityBinding(lied))
        // Узел не канонический
        assertNull(ReferralReceipt.parseIdentityBinding(ReferralFixtures.binding("not-a-node-id-at-all-not-at-all!!")))
        // Верхний регистр ядро не подписывает — читать нечего
        assertNull(ReferralReceipt.parseIdentityBinding(ReferralFixtures.binding(nodeId.uppercase())))
        // Непечатаемый байт внутри узла
        val binary = good.copyOf()
        binary[5] = 0x01
        assertNull(ReferralReceipt.parseIdentityBinding(binary))
        // Заявлена длина узла больше предела ядра
        val oversized = good.copyOf()
        oversized[1] = 1
        oversized[2] = 0x60
        assertNull(ReferralReceipt.parseIdentityBinding(oversized))
    }

    @Test
    fun inviteTokenCarriesInviterAndBothTimestamps() {
        val expires = createdAt + ReferralFixtures.WEEK_MS
        val parsed = ReferralReceipt.parseInviteToken(
            ReferralFixtures.token(
                inviterNodeId = ReferralFixtures.INVITER,
                inviterIdentityCreatedAtMs = createdAt - 10_000,
                createdAtMs = createdAt,
                expiresAtMs = expires,
            ),
        )
        assertNotNull(parsed)
        assertEquals(ReferralFixtures.INVITER, parsed!!.inviterNodeId)
        assertEquals(createdAt, parsed.createdAtMs)
        assertEquals(expires, parsed.expiresAtMs)
        assertEquals(createdAt - 10_000, parsed.inviterIdentityCreatedAtMs)
    }

    @Test
    fun malformedTokensAreRejected() {
        val good = ReferralFixtures.token()
        assertNull(ReferralReceipt.parseInviteToken(ByteArray(0)))
        assertNull(ReferralReceipt.parseInviteToken(good.copyOfRange(0, good.size - 1)))
        assertNull(ReferralReceipt.parseInviteToken(good + byteArrayOf(0)))
        assertNull(ReferralReceipt.parseInviteToken(byteArrayOf(9) + good.copyOfRange(1, good.size)))
        // Вложенная привязка повреждена
        assertNull(ReferralReceipt.parseInviteToken(byteArrayOf(1, 2, 3) + ByteArray(120)))
        // Длина вложенной привязки не совпадает с телом
        val lied = good.copyOf()
        lied[2] = (lied[2] + 1).toByte()
        assertNull(ReferralReceipt.parseInviteToken(lied))
    }

    @Test
    fun syntheticEnvelopeStaysInsideTheTransportBounds() {
        val tokenBytes = ReferralFixtures.token()
        val bindingBytes = ReferralFixtures.binding()
        assertTrue(tokenBytes.size <= ReferralReceipt.MAX_TOKEN_BYTES)
        assertTrue(bindingBytes.size <= ReferralReceipt.MAX_BINDING_BYTES)
        val envelope = ReferralFixtures.signedEnvelope()
        assertTrue(envelope.length <= ReferralWire.MAX_ENVELOPE_CHARS)
    }
}
