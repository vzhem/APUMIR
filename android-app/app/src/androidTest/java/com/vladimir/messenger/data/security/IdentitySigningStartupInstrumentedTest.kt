package com.vladimir.messenger.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentitySigningStartupInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun existingLegacyIdentityGetsStableRealSigningSidecar() {
        val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("identity_created", false))
        val nodeIdBefore = prefs.getString("node_id", null)
        assertNotNull(nodeIdBefore)

        assertEquals(IdentitySigningKeyStore.Mode.READY, IdentitySigningKeyStore.mode(context))
        val first = IdentitySigningKeyStore.installIntoCore(context, nodeIdBefore!!)!!
        val second = IdentitySigningKeyStore.installIntoCore(context, nodeIdBefore)!!

        assertEquals("legacy+ed25519-sidecar-v1", first.mode)
        assertTrue(first.publicKeyHex.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(first.keyId.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(first.publicKeyHex, second.publicKeyHex)
        assertEquals(first.keyId, second.keyId)

        val expectedKeyId = MessageDigest.getInstance("SHA-256")
            .digest(first.publicKeyHex.hexToBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(expectedKeyId, first.keyId)
        assertEquals(nodeIdBefore, prefs.getString("node_id", null))
    }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
