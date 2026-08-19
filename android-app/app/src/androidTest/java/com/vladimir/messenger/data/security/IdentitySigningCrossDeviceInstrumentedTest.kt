package com.vladimir.messenger.data.security

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.identitySigningBindingMatchesInstalled
import uniffi.p2p_core.verifyIdentitySigningBinding

@RunWith(AndroidJUnit4::class)
class IdentitySigningCrossDeviceInstrumentedTest {
    @Test
    fun verifiesForeignBindingRejectsTamperAndDoesNotClaimLocalMatch() {
        val encoded = InstrumentationRegistry.getArguments().getString("foreign_binding")
        require(!encoded.isNullOrBlank()) { "foreign_binding argument is required" }
        require(encoded.length <= 512) { "foreign binding is unbounded" }
        val binding = Base64.decode(encoded, Base64.NO_WRAP)

        assertTrue(verifyIdentitySigningBinding(binding))
        assertFalse(identitySigningBindingMatchesInstalled(binding))

        val tampered = binding.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        assertFalse(verifyIdentitySigningBinding(tampered))
        assertFalse(identitySigningBindingMatchesInstalled(tampered))
    }
}
