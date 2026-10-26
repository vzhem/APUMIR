package com.vladimir.messenger.data.mirror

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing bootstrap must let two devices open the same mirror frames. */
class MirrorFrameCipherTest {

    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun roundTripUsesThePairingRoomKey() {
        val wire = MirrorFrameCipher.seal(key, "history row and profile state")

        assertTrue(wire.orEmpty().startsWith("APUMIRROR2:"))
        assertEquals("history row and profile state", MirrorFrameCipher.open(key, wire!!))
    }

    @Test
    fun wrongKeyAndTamperingAreRejected() {
        val wire = MirrorFrameCipher.seal(key, "private mirror frame")!!
        val otherKey = key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tampered = wire.dropLast(1) + if (wire.last() == 'A') 'B' else 'A'

        assertNull(MirrorFrameCipher.open(otherKey, wire))
        assertNull(MirrorFrameCipher.open(key, tampered))
        assertNotEquals(wire, MirrorFrameCipher.seal(key, "private mirror frame"))
    }

    @Test
    fun legacyWireIsNotMistakenForSharedCiphertext() {
        assertNull(MirrorFrameCipher.open(key, "APUTYP1|legacy"))
        assertNull(MirrorFrameCipher.seal(ByteArray(31), "text"))
    }
}
