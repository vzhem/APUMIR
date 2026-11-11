package com.vladimir.messenger.data.push

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests only the Kotlin adapter with fake FFI and HTTP, not a real provider. */
class PushWakeCryptoTest {
    private val owner = "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394"
    private val capability = "e684e7708138f2e247613a7784c120d6154816a7bd1d88a54c53f155d8e9617a"
    private val signature = "51d08645a428e7615aaf8cec4d606f9c2a0b65f98a3703844cd0a10156ba10c67971f74b6ee86a90374c37343bd714b82f18560c0b8c389e8297ae1ef178af03"
    private val epoch = "09".repeat(16)
    private val device = "56".repeat(16)
    private val token = "fcm-test-token-abcdefghijklmnop"
    private val local = byteArrayOf(2, 4, 6)
    private val peer = byteArrayOf(1, 3, 5)
    private val secret = ByteArray(32) { 4 }
    private val route = "4237b0e96e22eadbb5093d4cada086a38530389a898b64aa8fc14e8afd780419"

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun state(local: ByteArray = this.local, peer: ByteArray = this.peer, epoch: String = this.epoch) =
        PushRegistrationState.fresh(PushEpochProtocol.bindingHash(local), PushEpochProtocol.bindingHash(peer), 1000, epoch, device)!!
    private fun endpoint() = PushEndpoint.approved("https://wake.example.workers.dev", setOf("wake.example.workers.dev"))!!

    @Test fun matchingSenderAndReceiverProduceTheWorkerRouteAndWipeFfiBuffers() {
        val borrowed = mutableListOf<ByteArray>()
        val output = mutableListOf<ByteArray>()
        val crypto = PushWakeCrypto(derive = { a, b, s, e, _ ->
            borrowed.addAll(listOf(a, b, s, e))
            assertEquals(epoch, e.joinToString("") { "%02x".format(it.toInt() and 255) })
            (bytes(owner) + bytes(capability)).also(output::add)
        }, sign = { _, _, _, _, _, _, _ -> error("not used") }, recordFailure = { error("unexpected failure") })
        val sender = crypto.derive(peer, local, secret, epoch, true)!!
        val receiver = crypto.derive(local, peer, secret, epoch, false)!!
        assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
        assertTrue(output.all { array -> array.all { it == 0.toByte() } })
        assertArrayEquals(byteArrayOf(2, 4, 6), local)
        assertArrayEquals(ByteArray(32) { 4 }, secret)
        assertEquals(route, sender.routeHex())
        assertEquals(route, receiver.routeHex())
        assertNull(receiver.wakeRequest())
        var json = ""
        val http = PushHttpClient(endpoint(), PushPost { _, path, body ->
            assertEquals("/v1/wake", path)
            json = body.toString(Charsets.US_ASCII)
            202
        }, { error("unexpected network failure") })
        assertEquals(PushHttpClient.WakeResult.ACCEPTED, http.wake(sender.wakeRequest()!!))
        assertEquals("{\"owner\":\"$owner\",\"capability\":\"$capability\"}", json)
        assertFalse(sender.toString().contains(capability))
        sender.close()
        receiver.close()
        assertNull(sender.wakeRequest())
        assertNull(receiver.routeHex())
    }

    @Test fun receiverSignsOnlyReservedMatchingStateAndHttpUsesTheSameVector() {
        val borrowed = mutableListOf<ByteArray>()
        lateinit var returned: ByteArray
        var calls = 0
        val crypto = PushWakeCrypto(derive = { _, _, _, _, _ -> bytes(owner) + bytes(capability) },
            sign = { binding, cap, dev, sequence, issued, input, revoke ->
                calls++
                assertArrayEquals(local, binding)
                assertArrayEquals(bytes(capability), cap)
                assertArrayEquals(bytes(device), dev)
                assertEquals(17uL, sequence)
                assertEquals(1_700_000_000uL, issued)
                assertEquals(token, input.toString(Charsets.UTF_8))
                assertFalse(revoke)
                borrowed.addAll(listOf(binding, cap, dev, input))
                (bytes(owner) + bytes(signature)).also { returned = it }
            }, recordFailure = { error("unexpected failure") })
        val material = crypto.derive(local, peer, secret, epoch, false)!!
        var reserved = state()
        repeat(17) { reserved = reserved.reserve()!! }
        val request = material.registrationRequest(local, reserved, 1_700_000_000, token)!!
        assertEquals(1, calls)
        assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
        assertTrue(returned.all { it == 0.toByte() })
        var json = ""
        lateinit var bodyCopy: ByteArray
        val http = PushHttpClient(endpoint(), PushPost { _, path, body ->
            assertEquals("/v1/registration", path)
            json = body.toString(Charsets.US_ASCII)
            bodyCopy = body
            204
        }, { error("unexpected network failure") })
        assertEquals(PushHttpClient.RegistrationResult.STORED, http.register(request))
        assertEquals("{\"action\":\"register\",\"owner\":\"$owner\",\"capability\":\"$capability\",\"device\":\"$device\",\"sequence\":17,\"issuedAt\":1700000000,\"token\":\"$token\",\"signature\":\"$signature\"}", json)
        assertTrue(bodyCopy.all { it == 0.toByte() })
        assertFalse(request.toString().contains(token))
        assertFalse(request.toString().contains(capability))
        assertEquals("6a0ef65def260fcd5f7c7c40fe98f300af25163312506ad67b43bceb5d6fa023", PushWakeCrypto.tokenFingerprint(token))
        material.close()
        assertNull(material.registrationRequest(local, reserved, 1_700_000_000, token))
    }

    @Test fun mismatchedEpochBindingsDirectionAndUnreservedStatesNeverSign() {
        var signs = 0
        val crypto = PushWakeCrypto(derive = { _, _, _, _, _ -> bytes(owner) + bytes(capability) },
            sign = { _, _, _, _, _, _, _ -> signs++; bytes(owner) + bytes(signature) }, recordFailure = { error("unexpected failure") })
        val receiver = crypto.derive(local, peer, secret, epoch, false)!!
        val sender = crypto.derive(local, peer, secret, epoch, true)!!
        val initial = state()
        val reserved = initial.reserve()!!
        assertNull(receiver.registrationRequest(local, initial, 1000, token))
        assertNull(receiver.registrationRequest(byteArrayOf(9), reserved, 1000, token))
        assertNull(receiver.registrationRequest(local, state(peer = byteArrayOf(7)).reserve()!!, 1000, token))
        assertNull(receiver.registrationRequest(local, state(epoch = "0a".repeat(16)).reserve()!!, 1000, token))
        assertNull(receiver.registrationRequest(local, reserved, 0, token))
        assertNull(receiver.registrationRequest(local, reserved, 1000, "short"))
        assertNull(sender.registrationRequest(local, reserved, 1000, token))
        assertEquals(0, signs)
        receiver.close()
        sender.close()
    }

    @Test fun revocationRequiresItsOwnReservationAndEmptyToken() {
        var signs = 0
        val crypto = PushWakeCrypto(derive = { _, _, _, _, _ -> bytes(owner) + bytes(capability) },
            sign = { _, _, _, sequence, _, input, revoke ->
                signs++
                assertEquals(2uL, sequence)
                assertTrue(revoke)
                assertEquals(0, input.size)
                bytes(owner) + bytes(signature)
            }, recordFailure = { error("unexpected failure") })
        val material = crypto.derive(local, peer, secret, epoch, false)!!
        val registered = state().reserve()!!.registered(1, PushWakeCrypto.tokenFingerprint(token)!!, 1001)!!
        val revoking = registered.beginRevocation()!!
        assertNull(material.registrationRequest(local, revoking, 1002, ""))
        val reserved = revoking.reserve()!!
        assertNull(material.registrationRequest(local, reserved, 1002, token))
        val request = material.registrationRequest(local, reserved, 1002, "")
        assertNotNull(request)
        assertTrue(request!!.toJsonBytes().toString(Charsets.US_ASCII).contains("\"action\":\"revoke\""))
        assertTrue(request.toJsonBytes().toString(Charsets.US_ASCII).contains("\"token\":\"\""))
        assertEquals(1, signs)
        material.close()
    }

    @Test fun invalidInputAndMalformedFfiOutputFailClosedWithoutLeaks() {
        var calls = 0
        var lastOutput = byteArrayOf()
        val crypto = PushWakeCrypto(derive = { _, _, _, _, _ ->
            calls++
            ByteArray(64).also { lastOutput = it }
        }, sign = { _, _, _, _, _, _, _ -> error("not used") }, recordFailure = { error("unexpected failure") })
        assertNull(crypto.derive(local, local, secret, epoch, true))
        assertNull(crypto.derive(local, peer, ByteArray(32), epoch, true))
        assertNull(crypto.derive(local, peer, secret, "00".repeat(16), true))
        assertNull(crypto.derive(local, peer, secret, "0A".repeat(16), true))
        assertNull(crypto.derive(ByteArray(513), peer, secret, epoch, true))
        assertEquals(0, calls)
        assertNull(crypto.derive(local, peer, secret, epoch, true))
        assertEquals(1, calls)
        assertTrue(lastOutput.all { it == 0.toByte() })
        assertNull(PushWakeCrypto.tokenFingerprint("too-short"))
        assertNull(PushWakeCrypto.tokenFingerprint("fcm-token-with-newline-abcdefgh\n"))
    }

    @Test fun failureAndCancellationClearBorrowedArraysWithoutLoggingInputs() {
        val borrowed = mutableListOf<ByteArray>()
        var failures = 0
        val failing = PushWakeCrypto(derive = { a, b, s, e, _ ->
            borrowed.addAll(listOf(a, b, s, e))
            throw IOException("sensitive")
        }, sign = { _, _, _, _, _, _, _ -> error("not used") }, recordFailure = { failures++ })
        assertNull(failing.derive(local, peer, secret, epoch, false))
        assertEquals(1, failures)
        assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
        val signBorrowed = mutableListOf<ByteArray>()
        val cancel = PushWakeCrypto(derive = { _, _, _, _, _ -> bytes(owner) + bytes(capability) },
            sign = { b, c, d, _, _, t, _ ->
                signBorrowed.addAll(listOf(b, c, d, t))
                throw CancellationException("stop")
            }, recordFailure = { failures++ })
        val material = cancel.derive(local, peer, secret, epoch, false)!!
        val error = runCatching { material.registrationRequest(local, state().reserve()!!, 1000, token) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, failures)
        assertTrue(signBorrowed.all { array -> array.all { it == 0.toByte() } })
        material.close()
    }

    @Test fun modifiedDerivationInputsAreRejectedAndTemporaryBuffersAreWiped() {
        repeat(4) { modified ->
            val borrowed = mutableListOf<ByteArray>()
            lateinit var result: ByteArray
            var failures = 0
            val crypto = PushWakeCrypto(derive = { a, b, s, e, _ ->
                borrowed.addAll(listOf(a, b, s, e))
                borrowed[modified][0] = (borrowed[modified][0].toInt() xor 0x40).toByte()
                (bytes(owner) + bytes(capability)).also { result = it }
            }, sign = { _, _, _, _, _, _, _ -> error("not used") }, recordFailure = { failures++ })
            assertNull(crypto.derive(local, peer, secret, epoch, false))
            assertEquals(1, failures)
            assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
            assertTrue(result.all { it == 0.toByte() })
            assertArrayEquals(byteArrayOf(2, 4, 6), local)
            assertArrayEquals(byteArrayOf(1, 3, 5), peer)
            assertArrayEquals(ByteArray(32) { 4 }, secret)
        }
    }

    @Test fun modifiedSigningInputsNeverProduceARegistration() {
        repeat(4) { modified ->
            val borrowed = mutableListOf<ByteArray>()
            lateinit var result: ByteArray
            var failures = 0
            val crypto = PushWakeCrypto(derive = { _, _, _, _, _ -> bytes(owner) + bytes(capability) },
                sign = { binding, cap, dev, _, _, input, _ ->
                    borrowed.addAll(listOf(binding, cap, dev, input))
                    borrowed[modified][0] = (borrowed[modified][0].toInt() xor 0x40).toByte()
                    (bytes(owner) + bytes(signature)).also { result = it }
                }, recordFailure = { failures++ })
            val material = crypto.derive(local, peer, secret, epoch, false)!!
            try {
                assertNull(material.registrationRequest(local, state().reserve()!!, 1000, token))
                assertEquals(1, failures)
                assertTrue(borrowed.all { array -> array.all { it == 0.toByte() } })
                assertTrue(result.all { it == 0.toByte() })
            } finally { material.close() }
        }
    }

    @Test fun badSignerOutputCannotProduceARequest() {
        var returned = byteArrayOf()
        var kind = 0
        val crypto = PushWakeCrypto(derive = { _, _, _, _, _ -> bytes(owner) + bytes(capability) },
            sign = { _, _, _, _, _, _, _ ->
                when (kind++) {
                    0 -> ByteArray(5)
                    1 -> ByteArray(32) { 1 } + bytes(signature)
                    else -> bytes(owner) + ByteArray(64)
                }.also { returned = it }
            }, recordFailure = { error("unexpected failure") })
        val material = crypto.derive(local, peer, secret, epoch, false)!!
        repeat(3) {
            assertNull(material.registrationRequest(local, state().reserve()!!, 1000, token))
            assertTrue(returned.all { it == 0.toByte() })
        }
        material.close()
    }
}
