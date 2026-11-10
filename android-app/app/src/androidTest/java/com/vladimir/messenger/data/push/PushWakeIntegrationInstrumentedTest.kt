package com.vladimir.messenger.data.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.security.SealedWire
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.clearIdentitySigningSeed
import uniffi.p2p_core.createFileExchangeBinding
import uniffi.p2p_core.createFileKeyEnvelope
import uniffi.p2p_core.createIdentitySigningBinding
import uniffi.p2p_core.identitySigningMode
import uniffi.p2p_core.installIdentitySigningSeed
import uniffi.p2p_core.openFileKeyEnvelope

/**
 * Two disposable identities in an otherwise empty emulator process; real Rust
 * FFI, sealed-envelope crypto, Keystore and SQLite, but fake HTTP and a manually
 * supplied prior pin. This is not a Firebase, real router, or physical-phone test.
 */
@RunWith(AndroidJUnit4::class)
class PushWakeIntegrationInstrumentedTest {
    @Test fun realFfiAndDeviceJournalsMatchWorkerVectorsWithoutProviderNetwork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Do not replace an installed identity on a live profile, even in a
        // manually launched debug instrumentation run.
        assertFalse(context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).getBoolean("identity_created", false))
        assertFalse(RustBridge.isRunning())
        assertEquals("legacy-only", identitySigningMode())
        val suffix = UUID.randomUUID().toString()
        val registrationDb = File(context.noBackupFilesDir, "push-integration-registration-$suffix.sqlite")
        val epochDb = File(context.noBackupFilesDir, "push-integration-epoch-$suffix.sqlite")
        val registrationAlias = "apu-push-integration-registration-$suffix"
        val epochAlias = "apu-push-integration-epoch-$suffix"
        val random = object : SecureRandom() {
            private var calls = 0
            override fun nextBytes(bytes: ByteArray) { bytes.fill(if (calls++ == 0) 9 else 0x56) }
        }
        val journal = PushRegistrationStore(context, registrationDb, registrationAlias, random = random)
        val epochs = PushEpochStore(context, epochDb, epochAlias)
        val aliceId = "pk_" + "1".repeat(32)
        val bobId = "pk_" + "2".repeat(32)
        val epoch = "09".repeat(16)
        val device = "56".repeat(16)
        val owner = "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394"
        val capability = "e684e7708138f2e247613a7784c120d6154816a7bd1d88a54c53f155d8e9617a"
        val signature = "51d08645a428e7615aaf8cec4d606f9c2a0b65f98a3703844cd0a10156ba10c67971f74b6ee86a90374c37343bd714b82f18560c0b8c389e8297ae1ef178af03"
        val token = "fcm-test-token-abcdefghijklmnop"
        val expectedRoute = "4237b0e96e22eadbb5093d4cada086a38530389a898b64aa8fc14e8afd780419"
        try {
            installIdentitySigningSeed(1.toUByte(), aliceId, ByteArray(32) { 1 })
            val alice = createFileExchangeBinding(createIdentitySigningBinding(100), ByteArray(32) { 3 }, 200)
            installIdentitySigningSeed(1.toUByte(), bobId, ByteArray(32) { 2 })
            val bob = createFileExchangeBinding(createIdentitySigningBinding(100), ByteArray(32) { 4 }, 200)
            val participants = PushRegistrationStore.Participants(bobId, aliceId, bob, alice)
            var reserved: PushRegistrationState.State? = null
            repeat(17) {
                reserved = (journal.reserve(participants, 1_700_000_000_000) as PushRegistrationStore.Result.Ready).state
            }
            val attempt = reserved!!
            assertEquals(epoch, attempt.epochHex)
            assertEquals(device, attempt.deviceHex)
            assertEquals(17L, attempt.sequence)
            val crypto = PushWakeCrypto(recordFailure = { error("unexpected test FFI failure") })
            var route: String? = null
            val receiver = crypto.derive(bob, alice, ByteArray(32) { 4 }, attempt.epochHex, false)!!
            try {
                route = receiver.routeHex()
                assertEquals(expectedRoute, route)
                val request = receiver.registrationRequest(bob, attempt, 1_700_000_000, token)!!
                var registrationBody = ""
                val http = PushHttpClient(
                    PushEndpoint.approved("https://wake.example.workers.dev", setOf("wake.example.workers.dev"))!!,
                    PushPost { _, path, body ->
                        assertEquals("/v1/registration", path)
                        registrationBody = body.toString(Charsets.US_ASCII)
                        204
                    }, { error("unexpected fake HTTP failure") },
                )
                assertEquals(PushHttpClient.RegistrationResult.STORED, http.register(request))
                assertEquals("{\"action\":\"register\",\"owner\":\"$owner\",\"capability\":\"$capability\",\"device\":\"$device\",\"sequence\":17,\"issuedAt\":1700000000,\"token\":\"$token\",\"signature\":\"$signature\"}", registrationBody)
            } finally { receiver.close() }
            val confirmed = (journal.registered(
                participants, attempt.sequence, PushWakeCrypto.tokenFingerprint(token)!!, 1_700_000_000_001, expectedRoute,
            ) as PushRegistrationStore.Result.Ready).state
            val indexed = journal.findRoute(expectedRoute) as PushRegistrationStore.RouteResult.Found
            assertEquals(bobId, indexed.localId)
            assertEquals(aliceId, indexed.peerId)
            assertEquals(attempt.generation, indexed.generation)
            val plaintext = confirmed.announcement()!!
            // Match the existing sealed-message format, but deliberately do not
            // mutate the real FileExchangePeerStore or app's message router.
            val messageKey = ByteArray(32) { 7 }
            val iv = ByteArray(12) { 8 }
            val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(messageKey, "AES"), GCMParameterSpec(128, iv))
                doFinal(plaintext.toByteArray(Charsets.UTF_8))
            }
            val envelope = createFileKeyEnvelope(bob, alice, ByteArray(32) { 4 }, manifest(encrypted), messageKey)
            messageKey.fill(0)
            val wire = SealedWire.encode(envelope, iv, encrypted)
            installIdentitySigningSeed(1.toUByte(), aliceId, ByteArray(32) { 1 })
            val parts = SealedWire.decode(wire)!!
            val openedSender = SealedWire.senderBinding(wire)!!
            assertArrayEquals(bob, openedSender)
            val openedKey = openFileKeyEnvelope(parts.keyEnvelope, alice, ByteArray(32) { 3 }, manifest(parts.ciphertext))
            val opened = try {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(openedKey, "AES"), GCMParameterSpec(128, parts.iv))
                    doFinal(parts.ciphertext).toString(Charsets.UTF_8)
                }
            } finally { openedKey.fill(0) }
            assertEquals(plaintext, opened)
            val altered = encrypted.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertNotNull(runCatching {
                openFileKeyEnvelope(parts.keyEnvelope, alice, ByteArray(32) { 3 }, manifest(altered))
            }.exceptionOrNull())
            val announcement = PushEpochProtocol.parse(opened)!!
            // Pin is supplied independently before handling; never use the
            // binding in this same envelope as a newly trusted TOFU pin.
            assertEquals(PushEpochStore.WriteResult.Rejected,
                epochs.accept(aliceId, bobId, announcement, false, openedSender, bob, alice))
            assertEquals(PushEpochStore.WriteResult.Rejected,
                epochs.accept(aliceId, bobId, announcement, true, openedSender, null, alice))
            assertEquals(PushEpochStore.WriteResult.Rejected,
                epochs.accept(aliceId, bobId, announcement, true, alice, bob, alice))
            assertEquals(PushEpochStore.WriteResult.Applied,
                epochs.accept(aliceId, bobId, announcement, true, openedSender, bob, alice))
            val received = (epochs.read(aliceId, bobId, bob, alice) as PushEpochStore.ReadResult.Found).state
            assertEquals(epoch, received.epochHex)
            val sender = crypto.derive(alice, bob, ByteArray(32) { 3 }, received.epochHex!!, true)!!
            try {
                assertEquals(route, sender.routeHex())
                val wake = sender.wakeRequest()
                assertNotNull(wake)
                assertEquals("{\"owner\":\"$owner\",\"capability\":\"$capability\"}", wake!!.toJsonBytes().toString(Charsets.US_ASCII))
                assertTrue(wake.isValid())
            } finally { sender.close() }
        } finally {
            clearIdentitySigningSeed()
            journal.close()
            epochs.close()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(registrationAlias)
                deleteEntry(epochAlias)
            }
            listOf(registrationDb, epochDb).forEach { file ->
                context.deleteDatabase(file.absolutePath)
                File(file.absolutePath + "-wal").delete()
                File(file.absolutePath + "-shm").delete()
                File(file.absolutePath + "-journal").delete()
            }
        }
    }

    // Same manifest as MessageSealer: the signed key envelope commits to the ciphertext.
    private fun manifest(ciphertext: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(ciphertext)
        return ("apu-msg-v1|" + digest.joinToString("") { "%02x".format(it.toInt() and 255) }).toByteArray(Charsets.UTF_8)
    }
}
