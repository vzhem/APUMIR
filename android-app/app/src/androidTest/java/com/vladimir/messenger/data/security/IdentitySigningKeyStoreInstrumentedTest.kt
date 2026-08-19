package com.vladimir.messenger.data.security

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Ignore("Accepted pre-wiring; default alias is now production state. Use an isolated namespace for future reruns.")
class IdentitySigningKeyStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanBefore() = cleanup()

    @After
    fun cleanAfter() = cleanup()

    @Test
    fun deviceBoundRoundTripZeroizationTamperAndMissingKey() {
        assertEquals(IdentitySigningKeyStore.Mode.ABSENT, IdentitySigningKeyStore.mode(context))

        lateinit var firstBorrowedArray: ByteArray
        val firstDigest = IdentitySigningKeyStore.withSeed(context) { seed ->
            firstBorrowedArray = seed
            assertEquals(IdentitySigningSeedEnvelope.SEED_BYTES, seed.size)
            assertTrue(seed.any { it.toInt() != 0 })
            MessageDigest.getInstance("SHA-256").digest(seed)
        }
        assertTrue(firstBorrowedArray.all { it.toInt() == 0 })
        assertEquals(IdentitySigningKeyStore.Mode.READY, IdentitySigningKeyStore.mode(context))

        lateinit var secondBorrowedArray: ByteArray
        val secondDigest = IdentitySigningKeyStore.withSeed(context) { seed ->
            secondBorrowedArray = seed
            MessageDigest.getInstance("SHA-256").digest(seed)
        }
        assertArrayEquals(firstDigest, secondDigest)
        assertTrue(secondBorrowedArray.all { it.toInt() == 0 })

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val original = prefs.getString(PREF_WRAPPED_SEED, null)!!
        val tamperedBytes = Base64.decode(original, Base64.NO_WRAP).also {
            it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte()
        }
        val tampered = Base64.encodeToString(tamperedBytes, Base64.NO_WRAP)
        assertTrue(prefs.edit().putString(PREF_WRAPPED_SEED, tampered).commit())

        assertEquals(IdentitySigningKeyStore.Mode.UNAVAILABLE, IdentitySigningKeyStore.mode(context))
        assertThrows(IdentitySigningKeyStore.SigningSeedUnavailableException::class.java) {
            IdentitySigningKeyStore.withSeed(context) { error("must not expose tampered seed") }
        }
        assertEquals(tampered, prefs.getString(PREF_WRAPPED_SEED, null))

        // A restored wrapped blob without this installation's Keystore alias
        // must not be silently replaced or rotated.
        assertTrue(prefs.edit().putString(PREF_WRAPPED_SEED, original).commit())
        keyStore().deleteEntry(WRAP_ALIAS)
        assertEquals(IdentitySigningKeyStore.Mode.UNAVAILABLE, IdentitySigningKeyStore.mode(context))
        assertThrows(IdentitySigningKeyStore.SigningSeedUnavailableException::class.java) {
            IdentitySigningKeyStore.withSeed(context) { error("must not rotate missing key") }
        }
        assertEquals(original, prefs.getString(PREF_WRAPPED_SEED, null))
    }

    private fun cleanup() {
        context.deleteSharedPreferences(PREFS_NAME)
        val keyStore = keyStore()
        if (keyStore.containsAlias(WRAP_ALIAS)) keyStore.deleteEntry(WRAP_ALIAS)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val WRAP_ALIAS = "apu_identity_signing_wrap_v1"
        private const val PREFS_NAME = "apu_identity_signing"
        private const val PREF_WRAPPED_SEED = "wrapped_seed_v1"
    }
}
