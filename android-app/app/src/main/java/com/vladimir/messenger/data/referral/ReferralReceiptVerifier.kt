package com.vladimir.messenger.data.referral

import uniffi.p2p_core.verifyIdentitySigningBinding
import uniffi.p2p_core.verifyReferralInviteToken
import uniffi.p2p_core.verifiedReferralInviterNodeId

/**
 * Проверка подписанной атрибуции приглашения.
 *
 * Порядок намеренный: сначала дешёвые проверки размера и формы, потом
 * криптография ядра, и только потом чтение полей. Обе подписи проверяются тем
 * же rust-core, который их создал, поэтому ни идентификатор узла, ни метки
 * времени подделать нельзя.
 *
 * Возвращает null на любой неудаче: вызывающий код трактует это как «не
 * зачислено», а не как ошибку транспорта.
 */
object ReferralReceiptVerifier {

    fun verify(packet: ReferralWire.Packet.SignedAttribution, nowMs: Long): VerifiedReceipt? {
        val token = ReferralWire.decode(packet.tokenB64) ?: return null
        val binding = ReferralWire.decode(packet.bindingB64) ?: return null
        if (token.isEmpty() || token.size > ReferralReceipt.MAX_TOKEN_BYTES) return null
        if (binding.isEmpty() || binding.size > ReferralReceipt.MAX_BINDING_BYTES) return null

        return try {
            // Токен подписан пригласившим: подтверждает, кто и когда создал ссылку.
            if (!verifyReferralInviteToken(token, nowMs)) return null
            // Привязка подписана приглашённым: подтверждает его узел и дату identity.
            if (!verifyIdentitySigningBinding(binding)) return null

            val tokenInviter = verifiedReferralInviterNodeId(token, nowMs)
            val tokenView = ReferralReceipt.parseInviteToken(token) ?: return null
            val bindingView = ReferralReceipt.parseIdentityBinding(binding) ?: return null

            // Ядро и наш читатель формата обязаны увидеть один и тот же токен.
            if (!tokenInviter.equals(tokenView.inviterNodeId, ignoreCase = true)) return null
            if (!tokenView.inviterNodeId.equals(packet.inviterNodeId, ignoreCase = true)) return null
            if (!bindingView.nodeId.equals(packet.inviteeNodeId, ignoreCase = true)) return null

            VerifiedReceipt(
                inviteeNodeId = bindingView.nodeId,
                inviteeIdentityCreatedAtMs = bindingView.createdAtMs,
                inviterNodeId = tokenView.inviterNodeId,
                tokenCreatedAtMs = tokenView.createdAtMs,
                tokenExpiresAtMs = tokenView.expiresAtMs,
                qualifiedAtMs = packet.qualifiedAtMs,
            )
        } catch (error: Exception) {
            null
        }
    }
}
