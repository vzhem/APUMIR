package com.vladimir.messenger.util

import uniffi.p2p_core.verifiedReferralInviterNodeId
import uniffi.p2p_core.verifyReferralInviteToken

/** Security boundary between an untrusted HTTPS link and referral attribution. */
object VerifiedReferralInviteLink {
    data class Verified(
        val inviterNodeId: String,
        val token: ByteArray,
        val original: String,
    )

    fun verify(raw: String?, nowMs: Long = System.currentTimeMillis()): Verified? {
        val parsed = ReferralInviteLink.parse(raw) ?: return null
        return verifyToken(parsed.token, nowMs)?.copy(original = parsed.original)
    }

    fun verifyToken(token: ByteArray, nowMs: Long = System.currentTimeMillis()): Verified? {
        if (token.isEmpty() || token.size > ReferralInviteLink.MAX_TOKEN_BYTES) return null
        val ownedToken = token.copyOf()
        return try {
            if (!verifyReferralInviteToken(ownedToken, nowMs)) return null
            val inviterNodeId = verifiedReferralInviterNodeId(ownedToken, nowMs)
            if (!isCanonicalLegacyNodeId(inviterNodeId)) return null
            Verified(inviterNodeId, ownedToken, original = "")
        } catch (_: Exception) {
            null
        }
    }

    private fun isCanonicalLegacyNodeId(value: String): Boolean {
        val suffix = value.removePrefix("pk_")
        return value.startsWith("pk_") &&
            (suffix.length == 32 || suffix.length == 64) &&
            suffix.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
