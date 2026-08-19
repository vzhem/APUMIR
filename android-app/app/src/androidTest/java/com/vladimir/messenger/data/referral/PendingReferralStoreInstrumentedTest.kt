package com.vladimir.messenger.data.referral

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.createReferralInviteToken

@RunWith(AndroidJUnit4::class)
class PendingReferralStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testPrefs = "apu_pending_referral_test_instrumented_v1"

    @Test
    fun verifiedTokenPersistsAndInvalidOrExpiredStateIsRemoved() {
        context.deleteSharedPreferences(testPrefs)
        try {
            val profile = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            assertTrue(profile.getBoolean("identity_created", false))
            val nodeId = profile.getString("node_id", null)
            assertNotNull(nodeId)
            assertNotNull(IdentitySigningKeyStore.installIntoCore(context, nodeId!!))

            val bindingText = context
                .getSharedPreferences("apu_identity_signing", Context.MODE_PRIVATE)
                .getString("identity_binding_v1", null)
            assertNotNull(bindingText)
            val binding = Base64.decode(bindingText, Base64.NO_WRAP)
            val createdAt = System.currentTimeMillis()
            val expiresAt = createdAt + 24 * 60 * 60 * 1_000L
            val token = createReferralInviteToken(binding, createdAt, expiresAt)

            val saved = PendingReferralStore.saveVerifiedIn(
                context,
                testPrefs,
                token,
                createdAt,
            )
            assertNotNull(saved)
            assertEquals(nodeId, saved!!.inviterNodeId)
            val loaded = PendingReferralStore.loadVerifiedIn(context, testPrefs, createdAt + 1_000)
            assertNotNull(loaded)
            assertEquals(nodeId, loaded!!.inviterNodeId)
            assertArrayEquals(token, loaded.token)

            val encoded = context.getSharedPreferences(testPrefs, Context.MODE_PRIVATE)
                .getString("verified_token_v1", null)!!
            val stored = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            stored[stored.lastIndex] = (stored.last().toInt() xor 1).toByte()
            val tampered = Base64.encodeToString(
                stored,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            context.getSharedPreferences(testPrefs, Context.MODE_PRIVATE)
                .edit()
                .putString("verified_token_v1", tampered)
                .commit()
            assertNull(PendingReferralStore.loadVerifiedIn(context, testPrefs, createdAt + 2_000))
            assertFalse(context.getSharedPreferences(testPrefs, Context.MODE_PRIVATE)
                .contains("verified_token_v1"))

            val shortToken = createReferralInviteToken(binding, createdAt, createdAt + 1_000)
            assertNotNull(PendingReferralStore.saveVerifiedIn(
                context,
                testPrefs,
                shortToken,
                createdAt,
            ))
            assertNull(PendingReferralStore.loadVerifiedIn(context, testPrefs, createdAt + 1_001))
        } finally {
            context.deleteSharedPreferences(testPrefs)
        }
    }
}
