package com.vladimir.messenger.data.push

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake FFI: the Rust/Node vectors and real Keystore/FFI need separate tests. */
class PushMailboxAccessSignerTest {
    private val owner = "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394"
    private val cap = "e684e7708138f2e247613a7784c120d6154816a7bd1d88a54c53f155d8e9617a"
    private val device = "56".repeat(16)
    private val epoch = "09".repeat(16)
    private val pullSignature = "8283a87b9ae172188e4c7dd879bb456d0474eba22acf0f8f3b83d56b5f87f22533506e7a5eb3256263b65d3581c6c1d3f2502f4416347aab80212ba486b2d00f"
    private val resumedSignature = "5df1c4eaa4fb6b02de113ddfcdaba5f7cd5841538e23c160986babaf8c0e86239aa9d5d2f108edf4d16fd7e751f811d285613e419d5afcebeed6ec0c56dd3c0f"
    private val ackSignature = "9e5945387e6560401b96bf3ddedf5914f34f7665e0cd517b797498785535d9dae0972f64a26c661bf5578137616ca994408ae6fd23982422e1bf1a400e96e40e"
    private val local = byteArrayOf(2, 4, 6)
    private val peer = byteArrayOf(1, 3, 5)
    private val secret = ByteArray(32) { 4 }
    private val time = 1_700_000_000L
    private val items = listOf(PushMailboxReceipt("11".repeat(16), "22".repeat(16)),
        PushMailboxReceipt("33".repeat(16), "44".repeat(16)))

    private fun bytes(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun state(local: ByteArray = this.local, peer: ByteArray = this.peer, epoch: String = this.epoch) =
        PushRegistrationState.fresh(PushEpochProtocol.bindingHash(local), PushEpochProtocol.bindingHash(peer), 1000, epoch, device)!!
    private fun registered(local: ByteArray = this.local, peer: ByteArray = this.peer, epoch: String = this.epoch) =
        state(local, peer, epoch).reserve()!!.registered(1, "ab".repeat(32), 1001)!!
    private fun crypto(
        signer: (ByteArray, ByteArray, ByteArray, ULong, ByteArray, ByteArray, Boolean) -> ByteArray,
        failure: () -> Unit = { error("unexpected failure") },
    ) = PushWakeCrypto(derive = { _, _, _, _, _ -> bytes(owner) + bytes(cap) },
        sign = { _, _, _, _, _, _, _ -> error("not used") }, signMailbox = signer, recordFailure = failure)

    @Test fun signedPullAndAckMatchWorkerVectorsAndWipeBorrowedBuffers() {
        val borrowed = mutableListOf<ByteArray>()
        val outputs = mutableListOf<ByteArray>()
        var calls = 0
        val material = crypto({ binding, capability, dev, issued, cursor, packed, acknowledge ->
            calls++
            assertArrayEquals(local, binding)
            assertArrayEquals(bytes(cap), capability)
            assertArrayEquals(bytes(device), dev)
            assertEquals(time.toULong(), issued)
            assertEquals(0, cursor.size)
            if (acknowledge) {
                assertArrayEquals(bytes("11".repeat(16) + "22".repeat(16) + "33".repeat(16) + "44".repeat(16)), packed)
            } else assertEquals(0, packed.size)
            borrowed.addAll(listOf(binding, capability, dev, cursor, packed))
            (bytes(owner) + bytes(if (acknowledge) ackSignature else pullSignature)).also(outputs::add)
        }).derive(local, peer, secret, epoch, false)!!
        try {
            val pull = material.mailboxPull(local, peer, registered(), time, "-")!!
            assertEquals("{\"owner\":\"$owner\",\"capability\":\"$cap\",\"device\":\"$device\",\"issuedAt\":$time,\"after\":\"-\",\"signature\":\"$pullSignature\"}", pull.toJsonBytes().toString(Charsets.US_ASCII))
            val ack = material.mailboxAck(local, peer, registered(), time, items)!!
            assertEquals("{\"owner\":\"$owner\",\"capability\":\"$cap\",\"device\":\"$device\",\"issuedAt\":$time,\"items\":[{\"id\":\"${items[0].id}\",\"receipt\":\"${items[0].receipt}\"},{\"id\":\"${items[1].id}\",\"receipt\":\"${items[1].receipt}\"}],\"signature\":\"$ackSignature\"}", ack.toJsonBytes().toString(Charsets.US_ASCII))
            assertFalse(pull.toString().contains(cap))
            assertFalse(ack.toString().contains(items[0].receipt))
            assertEquals(2, calls)
            assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
            assertTrue(outputs.all { array -> array.all { it == 0.toByte() } })
            assertArrayEquals(byteArrayOf(2, 4, 6), local)
            assertArrayEquals(byteArrayOf(1, 3, 5), peer)
        } finally { material.close() }
        assertNull(material.mailboxPull(local, peer, registered(), time, "-"))
    }

    @Test fun onlyCurrentRegisteredReceiverWithMatchingPinAndEpochCanSign() {
        var calls = 0
        val crypto = crypto({ _, _, _, _, _, _, _ -> calls++; bytes(owner) + bytes(pullSignature) })
        val receiver = crypto.derive(local, peer, secret, epoch, false)!!
        val sender = crypto.derive(local, peer, secret, epoch, true)!!
        try {
            assertNull(receiver.mailboxPull(local, peer, state(), time, "-"))
            assertNull(receiver.mailboxPull(local, peer, registered().beginRevocation()!!, time, "-"))
            assertNull(receiver.mailboxPull(byteArrayOf(9), peer, registered(), time, "-"))
            assertNull(receiver.mailboxPull(local, byteArrayOf(8), registered(), time, "-"))
            assertNull(receiver.mailboxPull(local, peer, registered(epoch = "0a".repeat(16)), time, "-"))
            assertNull(receiver.mailboxPull(local, peer, registered(peer = byteArrayOf(8)), time, "-"))
            assertNull(receiver.mailboxPull(local, peer, registered(), 0, "-"))
            assertNull(receiver.mailboxPull(local, peer, registered(), time, "00".repeat(16)))
            assertNull(receiver.mailboxAck(local, peer, registered(), time, items.reversed()))
            assertNull(receiver.mailboxAck(local, peer, registered(), time, listOf(items[0], items[0])))
            assertNull(sender.mailboxPull(local, peer, registered(), time, "-"))
            assertNull(sender.mailboxAck(local, peer, registered(), time, items))
            assertEquals(0, calls)
        } finally { receiver.close(); sender.close() }
    }

    @Test fun cursorAndReceiptsAreSignedAsBinaryAndNotSilentlyResorted() {
        var calls = 0
        val material = crypto({ _, _, _, _, cursor, packed, ack ->
            calls++
            if (ack) {
                assertArrayEquals(bytes("11".repeat(16) + "22".repeat(16) + "33".repeat(16) + "44".repeat(16)), packed)
                assertEquals(0, cursor.size)
            } else {
                assertArrayEquals(bytes("33".repeat(16)), cursor)
                assertEquals(0, packed.size)
            }
            bytes(owner) + bytes(if (ack) ackSignature else resumedSignature)
        }).derive(local, peer, secret, epoch, false)!!
        try {
            val resumed = material.mailboxPull(local, peer, registered(), time, "33".repeat(16))!!
            assertEquals("{\"owner\":\"$owner\",\"capability\":\"$cap\",\"device\":\"$device\",\"issuedAt\":$time,\"after\":\"${"33".repeat(16)}\",\"signature\":\"$resumedSignature\"}", resumed.toJsonBytes().toString(Charsets.US_ASCII))
            assertTrue(material.mailboxAck(local, peer, registered(), time, items) != null)
            assertNull(material.mailboxAck(local, peer, registered(), time, items.reversed()))
            assertEquals(2, calls)
        } finally { material.close() }
    }

    @Test fun alteredFfiInputsAreRejectedAndEveryTemporaryBufferIsWiped() {
        repeat(5) { changed ->
            val borrowed = mutableListOf<ByteArray>()
            lateinit var output: ByteArray
            var failures = 0
            val material = crypto({ binding, capability, dev, _, cursor, packed, _ ->
                borrowed.addAll(listOf(binding, capability, dev, cursor, packed))
                // Ack has no cursor; use a pull for that particular mutation.
                borrowed[changed][0] = (borrowed[changed][0].toInt() xor 0x40).toByte()
                (bytes(owner) + bytes(ackSignature)).also { output = it }
            }, { failures++ }).derive(local, peer, secret, epoch, false)!!
            try {
                val request = if (changed == 3) material.mailboxPull(local, peer, registered(), time, "33".repeat(16))
                    else material.mailboxAck(local, peer, registered(), time, items)
                assertNull(request)
                assertEquals(1, failures)
                assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
                assertTrue(output.all { it == 0.toByte() })
            } finally { material.close() }
        }
    }

    @Test fun changedCallerBindingsDuringFfiAreRejectedWithoutClearingCallerArrays() {
        repeat(2) { changed ->
            val callerLocal = local.copyOf()
            val callerPeer = peer.copyOf()
            val borrowed = mutableListOf<ByteArray>()
            lateinit var output: ByteArray
            val material = crypto({ binding, capability, dev, _, cursor, packed, _ ->
                borrowed.addAll(listOf(binding, capability, dev, cursor, packed))
                val target = if (changed == 0) callerLocal else callerPeer
                target[0] = (target[0].toInt() xor 0x40).toByte()
                (bytes(owner) + bytes(pullSignature)).also { output = it }
            }).derive(callerLocal, callerPeer, secret, epoch, false)!!
            try {
                assertNull(material.mailboxPull(callerLocal, callerPeer, registered(), time, "-"))
                assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
                assertTrue(output.all { it == 0.toByte() })
                // The signer owns only its snapshots, not the supplied arrays.
                assertTrue(callerLocal.any { it != 0.toByte() })
                assertTrue(callerPeer.any { it != 0.toByte() })
            } finally { material.close() }
        }
    }

    @Test fun closedMaterialAndBadSignerOutputCannotCreateAnAccessRequest() {
        var kind = 0
        lateinit var material: PushWakeCrypto.Material
        var output = byteArrayOf()
        material = crypto({ _, _, _, _, _, _, _ ->
            when (kind++) {
                0 -> ByteArray(5)
                1 -> ByteArray(32) { 1 } + bytes(pullSignature)
                2 -> bytes(owner) + ByteArray(64)
                else -> { material.close(); bytes(owner) + bytes(pullSignature) }
            }.also { output = it }
        }).derive(local, peer, secret, epoch, false)!!
        repeat(4) {
            assertNull(material.mailboxPull(local, peer, registered(), time, "-"))
            assertTrue(output.all { it == 0.toByte() })
        }
    }

    @Test fun failuresAndCancellationAreGenericAndClearInputs() {
        val borrowed = mutableListOf<ByteArray>()
        var failures = 0
        var cancel = false
        val material = crypto({ binding, capability, dev, _, cursor, packed, _ ->
            borrowed.addAll(listOf(binding, capability, dev, cursor, packed))
            if (cancel) throw CancellationException("private") else throw IOException("private")
        }, { failures++ }).derive(local, peer, secret, epoch, false)!!
        try {
            assertNull(material.mailboxPull(local, peer, registered(), time, "-"))
            assertEquals(1, failures)
            cancel = true
            assertTrue(runCatching { material.mailboxAck(local, peer, registered(), time, items) }.exceptionOrNull() is CancellationException)
            assertEquals(1, failures)
            assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
        } finally { material.close() }
    }
}
