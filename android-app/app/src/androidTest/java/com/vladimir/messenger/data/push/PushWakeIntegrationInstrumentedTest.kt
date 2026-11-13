package com.vladimir.messenger.data.push

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.security.SealedWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import org.junit.Assert.assertNull
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
 * FFI, sealed-envelope crypto, Keystore, SQLite and an independently pinned
 * Room binding, but fake HTTP. This is not a Firebase, real router, live identity
 * replacement, or physical-phone test.
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
        val wakeDb = File(context.noBackupFilesDir, "push-integration-inbox-$suffix.sqlite")
        val registrationAlias = "apu-push-integration-registration-$suffix"
        val epochAlias = "apu-push-integration-epoch-$suffix"
        val random = object : SecureRandom() {
            private var calls = 0
            override fun nextBytes(bytes: ByteArray) { bytes.fill(if (calls++ == 0) 9 else 0x56) }
        }
        val journal = PushRegistrationStore(context, registrationDb, registrationAlias, random = random)
        val epochs = PushEpochStore(context, epochDb, epochAlias)
        val inbox = PushWakeInbox(context, wakeDb)
        val aliceId = "pk_" + "1".repeat(32)
        val bobId = "pk_" + "2".repeat(32)
        val epoch = "09".repeat(16)
        val device = "56".repeat(16)
        val owner = "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394"
        val capability = "e684e7708138f2e247613a7784c120d6154816a7bd1d88a54c53f155d8e9617a"
        val signature = "51d08645a428e7615aaf8cec4d606f9c2a0b65f98a3703844cd0a10156ba10c67971f74b6ee86a90374c37343bd714b82f18560c0b8c389e8297ae1ef178af03"
        val token = "fcm-test-token-abcdefghijklmnop"
        val expectedRoute = "4237b0e96e22eadbb5093d4cada086a38530389a898b64aa8fc14e8afd780419"
        val pinDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            installIdentitySigningSeed(1.toUByte(), aliceId, ByteArray(32) { 1 })
            val aliceIdentity = createIdentitySigningBinding(100)
            val alice = createFileExchangeBinding(aliceIdentity, ByteArray(32) { 3 }, 200)
            val changedAlice = createFileExchangeBinding(aliceIdentity, ByteArray(32) { 5 }, 200)
            installIdentitySigningSeed(1.toUByte(), bobId, ByteArray(32) { 2 })
            val bob = createFileExchangeBinding(createIdentitySigningBinding(100), ByteArray(32) { 4 }, 200)
            val pins = FileExchangePeerStore(pinDb.fileExchangePeerDao())
            val otherPins = FileExchangePeerStore(pinDb.fileExchangePeerDao())
            val pinnedAlice = runBlocking {
                withTimeout(20_000) {
                    // A caller may mutate its packet while the pin gate is
                    // busy: insertion must use the earlier independent copy.
                    val held = CompletableDeferred<Unit>()
                    val unlock = CompletableDeferred<Unit>()
                    val blocker = async(Dispatchers.IO) {
                        pins.withPinnedBinding(aliceId) { held.complete(Unit); unlock.await() }
                    }
                    try {
                        held.await()
                        val mutable = alice.copyOf()
                        val inserting = async(start = CoroutineStart.UNDISPATCHED) { otherPins.pinFirstSeen(mutable) }
                        mutable.fill(0)
                        unlock.complete(Unit)
                        blocker.await()
                        assertTrue(inserting.await().newlyPinned)
                        assertArrayEquals(alice, pins.bindingFor(aliceId)!!)
                    } finally { unlock.complete(Unit) }
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val reading = async(Dispatchers.IO) {
                        pins.withPinnedBinding(aliceId) { existing ->
                            assertNotNull(existing)
                            assertArrayEquals(alice, existing!!)
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                    try {
                        entered.await()
                        val started = CompletableDeferred<Unit>()
                        val removing = async(Dispatchers.IO) {
                            started.complete(Unit)
                            otherPins.forgetPin(aliceId)
                        }
                        started.await()
                        assertNull(withTimeoutOrNull(100) { removing.await() })
                        release.complete(Unit)
                        reading.await()
                        assertTrue(removing.await())
                        assertNull(pins.bindingFor(aliceId))
                        assertTrue(pins.pinFirstSeen(alice).newlyPinned)
                        pins.bindingFor(aliceId)!!
                    } finally { release.complete(Unit) }
                }
            }
            val pinnedBob = runBlocking {
                pins.pinFirstSeen(bob)
                pins.bindingFor(bobId)!!
            }
            val participants = PushRegistrationStore.Participants(bobId, aliceId, bob, pinnedAlice)
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
            val receiver = crypto.derive(bob, pinnedAlice, ByteArray(32) { 4 }, attempt.epochHex, false)!!
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
                assertEquals(PushHttpClient.RegistrationResult.STORED, runBlocking { http.register(request) })
                assertEquals("{\"action\":\"register\",\"owner\":\"$owner\",\"capability\":\"$capability\",\"device\":\"$device\",\"sequence\":17,\"issuedAt\":1700000000,\"token\":\"$token\",\"signature\":\"$signature\"}", registrationBody)
            } finally { receiver.close() }
            val confirmed = (journal.registered(
                participants, attempt.sequence, PushWakeCrypto.tokenFingerprint(token)!!, 1_700_000_000_001, expectedRoute,
            ) as PushRegistrationStore.Result.Ready).state
            // Real restricted Rust signing and UniFFI against the same vectors as
            // the Worker; still no HTTP, scheduler or real message commit here.
            val mailboxReceiver = crypto.derive(bob, pinnedAlice, ByteArray(32) { 4 }, confirmed.epochHex, false)!!
            try {
                val pullSignature = "8283a87b9ae172188e4c7dd879bb456d0474eba22acf0f8f3b83d56b5f87f22533506e7a5eb3256263b65d3581c6c1d3f2502f4416347aab80212ba486b2d00f"
                val resumedSignature = "5df1c4eaa4fb6b02de113ddfcdaba5f7cd5841538e23c160986babaf8c0e86239aa9d5d2f108edf4d16fd7e751f811d285613e419d5afcebeed6ec0c56dd3c0f"
                val ackSignature = "9e5945387e6560401b96bf3ddedf5914f34f7665e0cd517b797498785535d9dae0972f64a26c661bf5578137616ca994408ae6fd23982422e1bf1a400e96e40e"
                val first = PushMailboxReceipt("11".repeat(16), "22".repeat(16))
                val second = PushMailboxReceipt("33".repeat(16), "44".repeat(16))
                val pull = mailboxReceiver.mailboxPull(bob, pinnedAlice, confirmed, 1_700_000_000, "-")!!
                assertEquals("{\"owner\":\"$owner\",\"capability\":\"$capability\",\"device\":\"$device\",\"issuedAt\":1700000000,\"after\":\"-\",\"signature\":\"$pullSignature\"}", pull.toJsonBytes().toString(Charsets.US_ASCII))
                val resumed = mailboxReceiver.mailboxPull(bob, pinnedAlice, confirmed, 1_700_000_000, second.id)!!
                assertEquals("{\"owner\":\"$owner\",\"capability\":\"$capability\",\"device\":\"$device\",\"issuedAt\":1700000000,\"after\":\"${second.id}\",\"signature\":\"$resumedSignature\"}", resumed.toJsonBytes().toString(Charsets.US_ASCII))
                val ack = mailboxReceiver.mailboxAck(bob, pinnedAlice, confirmed, 1_700_000_000, listOf(first, second))!!
                assertEquals("{\"owner\":\"$owner\",\"capability\":\"$capability\",\"device\":\"$device\",\"issuedAt\":1700000000,\"items\":[{\"id\":\"${first.id}\",\"receipt\":\"${first.receipt}\"},{\"id\":\"${second.id}\",\"receipt\":\"${second.receipt}\"}],\"signature\":\"$ackSignature\"}", ack.toJsonBytes().toString(Charsets.US_ASCII))
                assertNull(mailboxReceiver.mailboxPull(bob, changedAlice, confirmed, 1_700_000_000, "-"))
                assertNull(mailboxReceiver.mailboxAck(bob, pinnedAlice, confirmed, 1_700_000_000, listOf(second, first)))
            } finally { mailboxReceiver.close() }
            val indexed = journal.findRoute(expectedRoute) as PushRegistrationStore.RouteResult.Found
            assertEquals(bobId, indexed.localId)
            assertEquals(aliceId, indexed.peerId)
            assertEquals(attempt.generation, indexed.generation)
            // Exercise provider-shaped data through the real local index and
            // durable inbox, then rederive before admission. The scheduler is a
            // counter, not WorkManager or FCM; identity/pin ABA is not covered.
            var scheduled = 0
            val intake = PushWakeIntake(
                lookup = { candidate ->
                    when (journal.findRoute(candidate)) {
                        is PushRegistrationStore.RouteResult.Found -> PushWakeIntake.Hint.FOUND
                        PushRegistrationStore.RouteResult.Missing -> PushWakeIntake.Hint.MISSING
                        PushRegistrationStore.RouteResult.Unavailable -> PushWakeIntake.Hint.UNAVAILABLE
                    }
                },
                enqueueRoute = inbox::enqueueRoute,
                enqueueRecovery = inbox::enqueueRecovery,
                pending = inbox::pending,
                schedule = { scheduled++; true },
                recordFailure = { error("unexpected fake scheduler failure") },
            )
            val receivedAt = 1_700_000_000_010L
            assertEquals(PushWakeIntake.Result.IGNORED,
                intake.onData(mapOf("v" to "1", "route" to "ab".repeat(32)), receivedAt))
            assertEquals(PushWakeIntake.Result.QUEUED,
                intake.onData(mapOf("v" to "1", "route" to expectedRoute), receivedAt))
            assertEquals(1, scheduled)
            inbox.close()
            val claim = (inbox.claim(receivedAt) as PushWakeInbox.ClaimResult.Ready).claim
            val wakeData = PushWakePayload.parse(mapOf("v" to "1", "route" to claim.route!!))!!
            var borrowed: PushWakeInspector.LocalIdentity? = null
            val inspector = PushWakeInspector(
                journal = journal,
                pins = pins,
                localIdentity = { PushWakeInspector.LocalIdentity(bobId, bob, ByteArray(32) { 4 }).also { borrowed = it } },
                recoveryAllowed = { PushWakeDrain.Inspection.DROP },
                crypto = crypto,
                recordFailure = { error("unexpected inspector failure") },
            )
            runBlocking {
                assertEquals(PushWakeDrain.Inspection.ALLOW, inspector.inspect(claim.route))
                assertTrue(borrowed!!.secret.all { it == 0.toByte() })
                assertFalse(borrowed.toString().contains(bobId))
                assertEquals(PushWakeDrain.Inspection.DROP, inspector.inspect(null))
                assertEquals(PushWakeDrain.Inspection.DROP, inspector.inspect("ab".repeat(32)))
                assertTrue(pins.forgetPin(aliceId))
                assertEquals(PushWakeDrain.Inspection.DROP, inspector.inspect(claim.route))
                assertTrue(pins.pinFirstSeen(changedAlice).newlyPinned)
                assertEquals(PushWakeDrain.Inspection.DROP, inspector.inspect(claim.route))
                assertTrue(pins.forgetPin(aliceId))
                assertTrue(pins.pinFirstSeen(alice).newlyPinned)
            }
            val freshReceiver = crypto.derive(bob, pinnedAlice, ByteArray(32) { 4 }, confirmed.epochHex, false)!!
            val freshRoute = try { freshReceiver.routeHex() } finally { freshReceiver.close() }
            assertTrue(PushWakeAdmission.isConsistent(wakeData, indexed.localId, indexed.peerId,
                indexed.generation, bobId, aliceId, bob, pinnedAlice, confirmed, freshRoute))
            assertFalse(PushWakeAdmission.isConsistent(wakeData, indexed.localId, indexed.peerId,
                indexed.generation, bobId, aliceId, bob, bob, confirmed, freshRoute))
            assertFalse(PushWakeAdmission.isConsistent(wakeData, indexed.localId, indexed.peerId,
                indexed.generation, bobId, aliceId, bob, pinnedAlice, confirmed.beginRevocation(), freshRoute))
            assertEquals(PushWakeInbox.Completion.COMPLETED, inbox.complete(claim, true, receivedAt))
            val plaintext = confirmed.announcement()!!
            // Exercise the actual sealer/FFI using the independently pinned
            // Room binding, without touching the application's real router.
            val pushSealer = PushEpochEnvelope(recordFailure = { error("unexpected test envelope failure") })
            assertNull(pushSealer.seal(confirmed, bob, bob, ByteArray(32) { 4 }))
            val wire = pushSealer.seal(confirmed, bob, pinnedAlice, ByteArray(32) { 4 })!!
            // The strict mailbox codec must accept the real app envelope, not
            // just the synthetic Base64 fixtures used by the Worker tests.
            assertTrue(PushMailboxProtocol.validEnvelope(wire))
            val mailboxId = "11".repeat(16)
            val mailboxReceipt = "22".repeat(16)
            val mailboxPage = ("{\"items\":[{\"id\":\"$mailboxId\",\"envelope\":\"$wire\"," +
                "\"expiresAt\":1700604800000,\"receipt\":\"$mailboxReceipt\"}],\"next\":\"$mailboxId\"}")
                .toByteArray(Charsets.US_ASCII)
            try {
                val parsed = PushMailboxResponseCodec.page(mailboxPage)!!
                assertEquals(wire, parsed.items.single().envelope)
                assertEquals(mailboxReceipt, parsed.items.single().receipt)
                assertEquals(mailboxId, parsed.next)
            } finally { mailboxPage.fill(0) }
            installIdentitySigningSeed(1.toUByte(), aliceId, ByteArray(32) { 1 })
            var wrongIdentityFailures = 0
            assertNull(PushEpochEnvelope(recordFailure = { wrongIdentityFailures++ })
                .seal(confirmed, bob, pinnedAlice, ByteArray(32) { 4 }))
            assertEquals(1, wrongIdentityFailures)
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
            // The live router must quarantine this only after opening; the
            // opaque wire itself does not reveal its control-packet type.
            assertFalse(PushControlIngress.isReserved(wire))
            assertTrue(PushControlIngress.isReserved(opened))
            val altered = parts.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            // Passing mailbox structure checks is not authentication.
            assertTrue(PushMailboxProtocol.validEnvelope(SealedWire.encode(parts.keyEnvelope, parts.iv, altered)))
            assertNotNull(runCatching {
                openFileKeyEnvelope(parts.keyEnvelope, alice, ByteArray(32) { 3 }, manifest(altered))
            }.exceptionOrNull())
            val announcement = PushEpochProtocol.parse(opened)!!
            // Pin is supplied independently before handling; never use the
            // binding in this same envelope as a newly trusted TOFU pin.
            assertEquals(PushEpochStore.WriteResult.Rejected,
                epochs.accept(aliceId, bobId, announcement, false, openedSender, pinnedBob, alice))
            assertEquals(PushEpochStore.WriteResult.Rejected,
                epochs.accept(aliceId, bobId, announcement, true, openedSender, null, alice))
            assertEquals(PushEpochStore.WriteResult.Rejected,
                epochs.accept(aliceId, bobId, announcement, true, alice, pinnedBob, alice))
            assertEquals(PushEpochStore.WriteResult.Applied,
                epochs.accept(aliceId, bobId, announcement, true, openedSender, pinnedBob, alice))
            val received = (epochs.read(aliceId, bobId, pinnedBob, alice) as PushEpochStore.ReadResult.Found).state
            assertEquals(epoch, received.epochHex)
            val sender = crypto.derive(alice, pinnedBob, ByteArray(32) { 3 }, received.epochHex!!, true)!!
            try {
                assertEquals(route, sender.routeHex())
                val wake = sender.wakeRequest()
                assertNotNull(wake)
                assertEquals("{\"owner\":\"$owner\",\"capability\":\"$capability\"}", wake!!.toJsonBytes().toString(Charsets.US_ASCII))
                assertTrue(wake.isValid())
                // Build only from an actual FFI/AES message for this offered
                // epoch and independently pinned recipient. No persistence or
                // provider traffic is exercised by this device test.
                val messageWire = PushMailboxMessageSealer(recordFailure = { error("unexpected mailbox sealer failure") }).seal(
                    "12345678-1234-1234-1234-123456789abc", aliceId, bobId, 1_700_000_000_000,
                    "Device test", alice, pinnedBob, ByteArray(32) { 3 },
                ) { true }!!
                val cutoff = 1_700_604_800_000L
                val outsideId = PushMailboxProtocol.putId(cutoff, messageWire)!!
                val put = sender.mailboxPut(alice, pinnedBob, received, outsideId, cutoff, messageWire) { true }!!
                assertTrue(put.isValid())
                assertNull(sender.mailboxPut(alice, pinnedBob, received, outsideId, cutoff + 1, messageWire) { true })
                // The earlier wire was Bob -> Alice, not Alice -> Bob.
                val backwardsId = PushMailboxProtocol.putId(cutoff, wire)!!
                assertNull(sender.mailboxPut(alice, pinnedBob, received, backwardsId, cutoff, wire) { true })
            } finally { sender.close() }
            assertTrue(journal.beginRevocation(participants) is PushRegistrationStore.Result.Ready)
            assertEquals(PushWakeIntake.Result.IGNORED,
                intake.onData(mapOf("v" to "1", "route" to expectedRoute), receivedAt + 1))
            assertEquals(PushWakeDrain.Inspection.DROP, runBlocking { inspector.inspect(expectedRoute) })
        } finally {
            clearIdentitySigningSeed()
            pinDb.close()
            journal.close()
            epochs.close()
            inbox.close()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(registrationAlias)
                deleteEntry(epochAlias)
            }
            listOf(registrationDb, epochDb, wakeDb).forEach { file ->
                context.deleteDatabase(file.absolutePath)
                File(file.absolutePath + "-wal").delete()
                File(file.absolutePath + "-shm").delete()
                File(file.absolutePath + "-journal").delete()
            }
        }
    }

    @Test fun mailboxRoundTripAuthenticatesRealFfiAndPreviouslyPinnedParticipants() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).getBoolean("identity_created", false))
        assertFalse(RustBridge.isRunning())
        assertEquals("legacy-only", identitySigningMode())
        val pinDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val senderId = "pk_" + "2".repeat(32)
        val recipientId = "pk_" + "1".repeat(32)
        val messageId = "01234567-89ab-4cde-8f01-23456789abcd"
        val body = "Проверка сохранённого идентификатора 😀"
        try {
            installIdentitySigningSeed(1.toUByte(), senderId, ByteArray(32) { 2 })
            val senderIdentity = createIdentitySigningBinding(100)
            val senderBinding = createFileExchangeBinding(senderIdentity, ByteArray(32) { 4 }, 200)
            val changedSender = createFileExchangeBinding(senderIdentity, ByteArray(32) { 5 }, 200)
            installIdentitySigningSeed(1.toUByte(), recipientId, ByteArray(32) { 1 })
            val recipientBinding = createFileExchangeBinding(createIdentitySigningBinding(100), ByteArray(32) { 3 }, 200)
            val pins = FileExchangePeerStore(pinDb.fileExchangePeerDao())
            assertNull(runBlocking { pins.bindingFor(senderId) })
            runBlocking { assertTrue(pins.pinFirstSeen(senderBinding).newlyPinned) }
            val previouslyPinned = runBlocking { pins.bindingFor(senderId)!! }
            runBlocking { assertTrue(pins.pinFirstSeen(recipientBinding).newlyPinned) }
            val recipientPin = runBlocking { pins.bindingFor(recipientId)!! }

            installIdentitySigningSeed(1.toUByte(), senderId, ByteArray(32) { 2 })
            var sealFailures = 0
            val sealer = PushMailboxMessageSealer(recordFailure = { sealFailures++ })
            val wire = sealer.seal(messageId, senderId, recipientId, 1_700_000_000_000L, body,
                senderBinding, recipientPin, ByteArray(32) { 4 }) { true }!!
            assertEquals(0, sealFailures)
            assertTrue(PushMailboxProtocol.validEnvelope(wire))
            val cutoff = 1_700_604_800_000L
            val outsideId = PushMailboxProtocol.putId(cutoff, wire)!!
            assertTrue(PushMailboxPutRequest("ab".repeat(32), "cd".repeat(32), outsideId, cutoff, wire).isValid())
            assertFalse(PushMailboxPutRequest("ab".repeat(32), "cd".repeat(32), outsideId, cutoff + 1, wire).isValid())
            val fakeKey = ByteArray(204) { 1 }.also { it[1] = 0; it[2] = 1 }
            val vectorWire = SealedWire.encode(fakeKey, ByteArray(12) { 1 }, ByteArray(32) { 1 })
            assertEquals("bbdfc6257694ab5da289e92b67093da1", PushMailboxProtocol.putId(cutoff, vectorWire))
            fakeKey.fill(0)

            // A malicious authenticated sender can still place false metadata
            // inside a valid envelope. Construct one explicitly to test that
            // the receiver checks the plaintext against its independent pin.
            val wrongSender = PushMailboxMessageCodec.encode(messageId, "pk_" + "3".repeat(32), recipientId, 1_700_000_000_000L, body)!!
            val testKey = ByteArray(32) { 7 }
            val testNonce = ByteArray(12) { (it + 1).toByte() }
            val bytes = wrongSender.toByteArray(Charsets.UTF_8)
            val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(testKey, "AES"), GCMParameterSpec(128, testNonce))
                doFinal(bytes)
            }
            val wrapped = createFileKeyEnvelope(senderBinding, recipientBinding, ByteArray(32) { 4 }, manifest(ciphertext), testKey)
            val mismatchedWire = SealedWire.encode(wrapped, testNonce, ciphertext)
            testKey.fill(0); testNonce.fill(0); bytes.fill(0); ciphertext.fill(0); wrapped.fill(0)
            installIdentitySigningSeed(1.toUByte(), recipientId, ByteArray(32) { 1 })
            assertNull(sealer.seal(messageId, senderId, recipientId, 1_700_000_000_000L, body,
                senderBinding, recipientPin, ByteArray(32) { 4 }) { true })
            assertEquals(1, sealFailures) // the installed sender identity no longer matches
            var failures = 0
            val opener = PushMailboxMessageOpener(recordFailure = { failures++ })
            val result = opener.open(wire, recipientBinding, ByteArray(32) { 3 }, previouslyPinned, senderId, recipientId) { true }!!
            assertEquals(messageId, result.id)
            assertEquals(senderId, result.sender)
            assertEquals(recipientId, result.recipient)
            assertEquals(body, result.content)
            assertNull(opener.open(wire, recipientBinding, ByteArray(32) { 3 }, changedSender, senderId, recipientId) { true })
            assertNull(opener.open(wire, recipientBinding, ByteArray(32) { 3 }, byteArrayOf(), senderId, recipientId) { true })
            assertNull(opener.open(mismatchedWire, recipientBinding, ByteArray(32) { 3 }, previouslyPinned, senderId, recipientId) { true })
            assertEquals(0, failures)
            val altered = SealedWire.decode(wire)!!
            altered.ciphertext[0] = (altered.ciphertext[0].toInt() xor 1).toByte()
            val tamperedWire = SealedWire.encode(altered.keyEnvelope, altered.iv, altered.ciphertext)
            assertNull(opener.open(tamperedWire, recipientBinding, ByteArray(32) { 3 }, previouslyPinned, senderId, recipientId) { true })
            assertEquals(1, failures)
            installIdentitySigningSeed(1.toUByte(), senderId, ByteArray(32) { 2 })
            assertNull(opener.open(wire, recipientBinding, ByteArray(32) { 3 }, previouslyPinned, senderId, recipientId) { true })
            assertEquals(2, failures)
        } finally {
            clearIdentitySigningSeed()
            pinDb.close()
        }
    }

    // Same manifest as MessageSealer: the signed key envelope commits to the ciphertext.
    private fun manifest(ciphertext: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(ciphertext)
        return ("apu-msg-v1|" + digest.joinToString("") { "%02x".format(it.toInt() and 255) }).toByteArray(Charsets.UTF_8)
    }
}
