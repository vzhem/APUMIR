package com.vladimir.messenger.data.push

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.security.SealedWire
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
 * Disposable identities and a test Room DB, with real Rust FFI, AES-GCM and
 * the experimental pinned Room commit boundary, but fake pull/ACK and a test
 * suppression callback. This is not the live router, lifecycle lock, Firebase,
 * server, notification display or a physical-phone delivery test.
 */
@RunWith(AndroidJUnit4::class)
class PushMailboxDrainInstrumentedTest {
    @Test fun realRoomCommitPrecedesAckAndReplayedEnvelopeDeduplicatesAfterReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).getBoolean("identity_created", false))
        assertFalse(RustBridge.isRunning())
        assertEquals("legacy-only", identitySigningMode())
        val dbName = "push-drain-test-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        val senderId = "pk_" + "2".repeat(32)
        val recipientId = "pk_" + "1".repeat(32)
        val messageId = "01234567-89ab-4cde-8f01-23456789abcd"
        val content = "Сохранить ровно один раз 😀"
        val sentAt = 1_700_000_000_000L
        var inserted = 0
        var openedFailures = 0
        var ackCalls = 0
        try {
            installIdentitySigningSeed(1.toUByte(), senderId, ByteArray(32) { 2 })
            val sender = createFileExchangeBinding(createIdentitySigningBinding(100), ByteArray(32) { 4 }, 200)
            val changedSender = createFileExchangeBinding(createIdentitySigningBinding(100), ByteArray(32) { 5 }, 200)
            db.chatDao().insertChat(ChatEntity(id = "test-direct-chat", contactId = senderId, contactName = "Friend"))
            installIdentitySigningSeed(1.toUByte(), recipientId, ByteArray(32) { 1 })
            val recipient = createFileExchangeBinding(createIdentitySigningBinding(100), ByteArray(32) { 3 }, 200)
            val initialPins = FileExchangePeerStore(db.fileExchangePeerDao())
            assertTrue(initialPins.pinFirstSeen(sender).newlyPinned)
            assertTrue(initialPins.pinFirstSeen(recipient).newlyPinned)
            val recipientPin = initialPins.bindingFor(recipientId)!!
            installIdentitySigningSeed(1.toUByte(), senderId, ByteArray(32) { 2 })
            val wire = PushMailboxMessageSealer(recordFailure = { error("real FFI sealing failed") }).seal(
                messageId, senderId, recipientId, sentAt, content, sender, recipientPin, ByteArray(32) { 4 },
            ) { true }!!
            val changed = SealedWire.decode(wire)!!
            changed.ciphertext[0] = (changed.ciphertext[0].toInt() xor 1).toByte()
            val tampered = SealedWire.encode(changed.keyEnvelope, changed.iv, changed.ciphertext)
            val goodItem = PushMailboxItem("11".repeat(16), wire, sentAt, "aa".repeat(16))
            val badItem = PushMailboxItem("22".repeat(16), tampered, sentAt, "bb".repeat(16))
            installIdentitySigningSeed(1.toUByte(), recipientId, ByteArray(32) { 1 })

            fun drainFor(database: AppDatabase, acceptAck: Boolean): PushMailboxDrain {
                val pins = FileExchangePeerStore(database.fileExchangePeerDao())
                val incoming = PushMailboxIncoming(
                    pins, PushMailboxMessageOpener(recordFailure = { openedFailures++ }),
                    PushMailboxRoomStore(database, suppressed = { _, _ -> false }),
                )
                return PushMailboxDrain(
                    pull = { cursor ->
                        if (cursor == "-") PushMailboxHttpClient.PullResult.Page(listOf(goodItem, badItem), badItem.id)
                        else PushMailboxHttpClient.PullResult.Page(emptyList(), null)
                    },
                    // The suppression and lifecycle callbacks remain test-only.
                    commit = { envelope ->
                        incoming.commit(envelope, senderId, recipientId, recipient, ByteArray(32) { 3 }) { true }
                            .also { if (it == PushMailboxDrain.Commit.COMMITTED) inserted++ }
                    },
                    ack = { receipts ->
                        ackCalls++
                        assertEquals(listOf(goodItem.id), receipts.map { it.id })
                        val row = database.messageDao().getMessageById(messageId)
                        assertNotNull(row)
                        assertEquals(content, row!!.content)
                        assertTrue(row.notificationPending)
                        if (acceptAck) PushMailboxHttpClient.AckResult.ACCEPTED else PushMailboxHttpClient.AckResult.UNAVAILABLE
                    },
                    current = { true }, recordFailure = { error("unexpected drain failure") },
                )
            }

            // Simulate a lost response after the Room transaction; no cursor is
            // advanced on this failure, so the same ciphertext is seen again.
            assertEquals(PushMailboxDrain.Result.Retry, drainFor(db, false).run())
            assertEquals(1, inserted)
            assertEquals(1, ackCalls)
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
            assertNotNull(db.messageDao().getMessageById(messageId))
            assertNotNull(FileExchangePeerStore(db.fileExchangePeerDao()).bindingFor(senderId))
            assertEquals(PushMailboxDrain.Result.Unprocessed, drainFor(db, true).run())
            assertEquals(1, inserted)
            assertEquals(2, ackCalls)
            assertEquals(2, openedFailures) // corrupted envelope was never acknowledged
            assertTrue(db.messageDao().getMessageById(messageId)!!.notificationPending)
            assertEquals(1, db.chatDao().getChatById("test-direct-chat")!!.unreadCount)
            assertEquals(content, db.chatDao().getChatById("test-direct-chat")!!.lastMessage)

            // The actual pin gate and opener, not a caller-claimed sender, decide
            // whether an already committed copy can be treated as a duplicate.
            val pins = FileExchangePeerStore(db.fileExchangePeerDao())
            val incoming = PushMailboxIncoming(
                pins, PushMailboxMessageOpener(recordFailure = { openedFailures++ }),
                PushMailboxRoomStore(db, suppressed = { _, _ -> false }),
            )
            suspend fun replay() = incoming.commit(wire, senderId, recipientId, recipient, ByteArray(32) { 3 }) { true }
            assertTrue(pins.forgetPin(senderId))
            assertEquals(PushMailboxDrain.Commit.RETRY, replay())
            assertTrue(pins.pinFirstSeen(changedSender).newlyPinned)
            assertEquals(PushMailboxDrain.Commit.RETRY, replay())
            assertTrue(pins.forgetPin(senderId))
            assertTrue(pins.pinFirstSeen(sender).newlyPinned)
            assertEquals(PushMailboxDrain.Commit.ALREADY_COMMITTED, replay())
            assertEquals(1, db.chatDao().getChatById("test-direct-chat")!!.unreadCount)
        } finally {
            clearIdentitySigningSeed()
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
