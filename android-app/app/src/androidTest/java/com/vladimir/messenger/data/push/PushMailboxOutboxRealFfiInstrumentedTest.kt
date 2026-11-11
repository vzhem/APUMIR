package com.vladimir.messenger.data.push

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.local.AppDatabase
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.clearIdentitySigningSeed
import uniffi.p2p_core.createFileExchangeBinding
import uniffi.p2p_core.createIdentitySigningBinding
import uniffi.p2p_core.identitySigningMode
import uniffi.p2p_core.installIdentitySigningSeed

/**
 * Disposable identities, real Rust envelope/derivation and Android storage,
 * independently pinned through a test Room DB; HTTP and the epoch announcement
 * are test inputs. This does not exercise the real router, FCM or lifecycle.
 */
@RunWith(AndroidJUnit4::class)
class PushMailboxOutboxRealFfiInstrumentedTest {
    @Test fun realFfiPersistsAndReusesTheWireBeforeAnAuthenticatedRoundTrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).getBoolean("identity_created", false))
        assertFalse(RustBridge.isRunning())
        assertEquals("legacy-only", identitySigningMode())
        val suffix = UUID.randomUUID().toString()
        val dbFile = File(context.noBackupFilesDir, "push-outbox-ffi-test-$suffix.sqlite")
        val alias = "apu-push-outbox-ffi-test-$suffix"
        val pinDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val senderId = "pk_" + "2".repeat(32)
        val recipientId = "pk_" + "1".repeat(32)
        val id = "01234567-89ab-4cde-8f01-23456789abcd"
        val sentAt = 1_700_000_000_000L
        val cutoff = sentAt + 86_400_000L
        val body = "Проверка надёжного повтора 😀"
        val localSecret = ByteArray(32) { 4 }
        val recipientSecret = ByteArray(32) { 3 }
        var failures = 0
        var first: PushMailboxOutbox? = null
        var reopened: PushMailboxOutbox? = null
        try {
            installIdentitySigningSeed(1.toUByte(), senderId, ByteArray(32) { 2 })
            val sender = createFileExchangeBinding(createIdentitySigningBinding(100), localSecret, 200)
            installIdentitySigningSeed(1.toUByte(), recipientId, ByteArray(32) { 1 })
            val recipient = createFileExchangeBinding(createIdentitySigningBinding(100), recipientSecret, 200)
            val pins = FileExchangePeerStore(pinDb.fileExchangePeerDao())
            assertTrue(pins.pinFirstSeen(sender).newlyPinned)
            assertTrue(pins.pinFirstSeen(recipient).newlyPinned)
            val pinnedRecipient = pins.bindingFor(recipientId)!!
            val pinnedSender = pins.bindingFor(senderId)!!
            val packet = PushEpochProtocol.parse(PushEpochProtocol.encodeOffer(
                1, PushEpochProtocol.bindingHash(pinnedRecipient), PushEpochProtocol.bindingHash(sender), "ab".repeat(16),
            ))!!
            val epoch = (PushEpochProtocol.accept(packet, true, pinnedRecipient, pinnedRecipient, sender, null)
                as PushEpochProtocol.Verdict.Applied).state
            val draft = PushMailboxOutbox.Draft(id, senderId, recipientId, sentAt, body, sender, pinnedRecipient,
                localSecret, epoch, cutoff)
            installIdentitySigningSeed(1.toUByte(), senderId, ByteArray(32) { 2 })
            fun outbox() = PushMailboxOutbox(
                context, PushMailboxMessageSealer(recordFailure = { failures++ }), { failures++ },
                databaseFile = dbFile, keyAlias = alias, clock = { sentAt },
            )
            first = outbox()
            val saved = (first.prepare(draft) { true } as PushMailboxOutbox.Result.Ready)
            assertTrue(saved.created)
            assertEquals(PushMailboxProtocol.putId(cutoff, saved.entry.envelope), saved.entry.id)
            first.close()
            reopened = outbox()
            val retry = (reopened.prepare(draft) { true } as PushMailboxOutbox.Result.Ready)
            assertFalse(retry.created)
            assertEquals(saved.entry.id, retry.entry.id)
            assertEquals(saved.entry.envelope, retry.entry.envelope)
            assertEquals(saved.entry.expiresAt, retry.entry.expiresAt)

            val crypto = PushWakeCrypto(recordFailure = { failures++ })
            val material = crypto.derive(sender, pinnedRecipient, localSecret, epoch.epochHex!!, true)!!
            try {
                val request = material.mailboxPut(sender, pinnedRecipient, epoch,
                    retry.entry.id, retry.entry.expiresAt, retry.entry.envelope) { true }
                assertNotNull(request)
                val http = PushMailboxHttpClient(
                    PushEndpoint.approved("https://wake.example.workers.dev", setOf("wake.example.workers.dev"))!!,
                    PushMailboxPost { _, path, _ ->
                        assertEquals("/v1/mailbox/put", path)
                        PushMailboxHttpResponse(201, "{\"state\":\"stored\",\"expiresAt\":$cutoff,\"wakeQueued\":true}".toByteArray())
                    },
                    { failures++ },
                )
                assertTrue(http.put(request!!) is PushMailboxHttpClient.PutResult.Stored)
                assertFalse((reopened.prepare(draft) { true } as PushMailboxOutbox.Result.Ready).created)
            } finally { material.close() }

            installIdentitySigningSeed(1.toUByte(), recipientId, ByteArray(32) { 1 })
            val opened = PushMailboxMessageOpener(recordFailure = { failures++ }).open(
                retry.entry.envelope, recipient, recipientSecret, pinnedSender, senderId, recipientId,
            ) { true }!!
            assertEquals(id, opened.id)
            assertEquals(body, opened.content)
            assertEquals(sentAt, opened.sentAtMs)
            assertEquals(0, failures)
        } finally {
            clearIdentitySigningSeed()
            first?.close()
            reopened?.close()
            pinDb.close()
            localSecret.fill(0)
            recipientSecret.fill(0)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            context.deleteDatabase(dbFile.absolutePath)
            listOf("-wal", "-shm", "-journal").forEach { File(dbFile.absolutePath + it).delete() }
        }
    }
}
