package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushDrainTrustGateTest {
    private val sender = "pk_" + "1".repeat(32)
    private val recipient = "pk_" + "2".repeat(32)

    /**
     * Mimics a real pin/identity store: every read returns fresh copies, never
     * the same arrays, so the gate's wipe cannot mutate the "database".
     */
    private class Source(
        var sender: String = "pk_" + "1".repeat(32),
        var recipient: String = "pk_" + "2".repeat(32),
        var local: Byte = 3,
        var peer: Byte = 4,
        var key: Byte = 5,
    ) {
        var reads = 0
        var throws = false
        var missing = false

        fun read(): PushDrainTrustGate.Snapshot? {
            reads++
            if (throws) throw IllegalStateException("store unavailable")
            if (missing) return null
            return PushDrainTrustGate.Snapshot(
                sender, recipient, ByteArray(48) { local }, ByteArray(48) { peer }, ByteArray(32) { key },
            )
        }
    }

    private fun gateWith(source: Source): PushDrainTrustGate = PushDrainTrustGate { source.read() }

    @Test fun anUnchangedSnapshotStaysStableAcrossRepeatedChecks() {
        val source = Source()
        val gate = gateWith(source)
        val pass = gate.start()!!
        repeat(5) { assertTrue(pass.stable()) }
        assertTrue(source.reads >= 5)
        pass.close()
        // Closing wipes the baseline: every later check fails closed.
        assertFalse(pass.stable())
    }

    @Test fun anyChangedPinIdentityOrSecretInvalidatesThePass() {
        val pin = Source()
        gateWith(pin).start()!!.let { pass ->
            pin.peer = 9
            assertFalse(pass.stable())
        }
        val identity = Source()
        gateWith(identity).start()!!.let { pass ->
            identity.sender = "pk_" + "3".repeat(32)
            assertFalse(pass.stable())
        }
        val key = Source()
        gateWith(key).start()!!.let { pass ->
            key.key = 7
            assertFalse(pass.stable())
        }
    }

    @Test fun aChangeIsStickyEvenIfTheStoreReturnsTheOldValueAgain() {
        val source = Source()
        val pass = gateWith(source).start()!!
        source.local = 8
        assertFalse(pass.stable())
        // ABA: the old value comes back. The pass must still stay dead.
        source.local = 3
        assertFalse(pass.stable())
    }

    @Test fun anUnreadableOrMissingSnapshotFailsClosed() {
        val throwing = Source()
        val pass = gateWith(throwing).start()!!
        throwing.throws = true
        assertFalse(pass.stable())

        val missing = Source()
        val missingPass = gateWith(missing).start()!!
        missing.missing = true
        assertFalse(missingPass.stable())
    }

    @Test fun structurallyInvalidBaselinesNeverStartAPass() {
        assertNull(PushDrainTrustGate { null }.start())
        // Sender and recipient must be distinct, well-formed nodes.
        assertNull(gateWith(Source(sender = "pk_" + "1".repeat(32), recipient = "pk_" + "1".repeat(32))).start())
        assertNull(gateWith(Source(sender = "not-a-node")).start())
        // Identical local and peer bindings cannot distinguish direction.
        assertNull(gateWith(Source(peer = 3)).start())
        // All-zero or wrongly sized local secret.
        assertNull(gateWith(Source(key = 0)).start())
    }

    @Test fun aThrowingOrCancelledReaderNeverStartsAPass() {
        assertNull(PushDrainTrustGate { throw IllegalStateException("unavailable") }.start())
        var cancelled = false
        try {
            PushDrainTrustGate { throw CancellationException("cancelled") }.start()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test fun theGateWipesOnlyItsOwnCopies() {
        val source = Source()
        val gate = gateWith(source)
        // Invalid baseline so the gate wipes what it was handed, not the source.
        val invalid = PushDrainTrustGate.Snapshot(sender, sender, ByteArray(48) { 3 }, ByteArray(48) { 4 }, ByteArray(32) { 5 })
        assertNull(PushDrainTrustGate { invalid }.start())
        assertArrayEquals(ByteArray(48) { 0 }, invalid.localBinding)
        assertArrayEquals(ByteArray(32) { 0 }, invalid.localSecret)

        // A valid pass reads fresh copies; the "stored" values stay intact.
        val pass = gate.start()!!
        assertTrue(pass.stable())
        pass.close()
        assertEquals(3.toByte(), source.read()!!.localBinding[0])
    }
}
