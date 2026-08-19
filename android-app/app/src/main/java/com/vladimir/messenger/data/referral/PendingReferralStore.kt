package com.vladimir.messenger.data.referral

import android.content.Context
import android.util.Base64
import com.vladimir.messenger.util.ReferralInviteLink
import com.vladimir.messenger.util.VerifiedReferralInviteLink

/**
 * Device-local pending referral attribution.
 *
 * The public signed token is retained across onboarding, but never trusted from storage:
 * every read re-runs Rust binding/signature/expiry verification. Application backup and
 * device transfer are globally disabled, preventing restored-install attribution.
 */
object PendingReferralStore {
    private const val PREFS_NAME = "apu_pending_referral"
    private const val TOKEN_KEY = "verified_token_v1"
    private const val RECEIVED_AT_KEY = "received_at_ms_v1"
    private const val MAX_ENCODED_TOKEN_CHARS = 700

    data class Pending(
        val inviterNodeId: String,
        val token: ByteArray,
        val receivedAtMs: Long,
    )

    @Synchronized
    fun saveVerified(
        context: Context,
        token: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): Pending? = saveVerifiedIn(context, PREFS_NAME, token, nowMs)

    @Synchronized
    internal fun saveVerifiedIn(
        context: Context,
        prefsName: String,
        token: ByteArray,
        nowMs: Long,
    ): Pending? {
        requireAllowedStore(prefsName)
        val verified = VerifiedReferralInviteLink.verifyToken(token, nowMs) ?: return null
        val encoded = Base64.encodeToString(verified.token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        if (encoded.isEmpty() || encoded.length > MAX_ENCODED_TOKEN_CHARS) return null
        val saved = context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(TOKEN_KEY, encoded)
            .putLong(RECEIVED_AT_KEY, nowMs)
            .commit()
        if (!saved) return null
        return Pending(verified.inviterNodeId, verified.token.copyOf(), nowMs)
    }

    @Synchronized
    fun loadVerified(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): Pending? = loadVerifiedIn(context, PREFS_NAME, nowMs)

    @Synchronized
    internal fun loadVerifiedIn(
        context: Context,
        prefsName: String,
        nowMs: Long,
    ): Pending? {
        requireAllowedStore(prefsName)
        val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val encoded = prefs.getString(TOKEN_KEY, null) ?: return null
        val receivedAt = prefs.getLong(RECEIVED_AT_KEY, -1L)
        if (receivedAt < 0L || receivedAt > nowMs || encoded.length > MAX_ENCODED_TOKEN_CHARS) {
            clearIn(context, prefsName)
            return null
        }
        val token = try {
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: IllegalArgumentException) {
            clearIn(context, prefsName)
            return null
        }
        if (token.isEmpty() || token.size > ReferralInviteLink.MAX_TOKEN_BYTES) {
            token.fill(0)
            clearIn(context, prefsName)
            return null
        }
        val verified = VerifiedReferralInviteLink.verifyToken(token, nowMs)
        token.fill(0)
        if (verified == null) {
            clearIn(context, prefsName)
            return null
        }
        return Pending(verified.inviterNodeId, verified.token.copyOf(), receivedAt)
    }

    @Synchronized
    fun clear(context: Context): Boolean = clearIn(context, PREFS_NAME)

    @Synchronized
    internal fun clearIn(context: Context, prefsName: String): Boolean {
        requireAllowedStore(prefsName)
        return context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun requireAllowedStore(prefsName: String) {
        require(prefsName == PREFS_NAME || prefsName.startsWith("apu_pending_referral_test_")) {
            "Unexpected pending referral store"
        }
    }
}
