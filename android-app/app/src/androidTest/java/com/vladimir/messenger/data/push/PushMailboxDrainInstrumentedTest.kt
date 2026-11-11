package com.vladimir.messenger.data.push

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.MessageEntity
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
 * Disposable identities and a test Room DB, with real Rust FFI and AES-GCM but
 * fake pull/ACK and a test-only commit callback. This is not the production
 * router, lifecycle lock, Firebase, server or a physical-phone delivery test.
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
                val opener = PushMailboxMessageOpener(recordFailure = { openedFailures++ })
                return PushMailboxDrain(
                    pull = { cursor ->
                        if (cursor == "-") PushMailboxHttpClient.PullResult.Page(listOf(goodItem, badItem), badItem.id)
                        else PushMailboxHttpClient.PullResult.Page(emptyList(), null)
                    },
                    // This callback is intentionally test-only. Production must
                    // additionally coordinate pin, identity, restore, controls,
                    // chat selection, notification policy and normal transport.
                    commit = commit@{ envelope ->
                        val pin = pins.bindingFor(senderId) ?: return@commit PushMailboxDrain.Commit.RETRY
                        val message = opener.open(envelope, recipient, ByteArray(32) { 3 }, pin, senderId, recipientId) { true }
                            ?: return@commit PushMailboxDrain.Commit.RETRY
                        database.withTransaction {
                            val messages = database.messageDao()
                            val prior = messages.getMessageById(message.id)
                            if (prior != null) {
                                if (!prior.isFromMe && prior.senderId == message.sender && prior.recipientId == message.recipient &&
                                    prior.content == message.content && prior.timestamp == message.sentAtMs
                                ) PushMailboxDrain.Commit.ALREADY_COMMITTED else PushMailboxDrain.Commit.RETRY
                            } else {
                                val row = MessageEntity(
                                    id = message.id, chatId = "test-direct-chat", senderId = message.sender,
                                    recipientId = message.recipient, content = message.content, timestamp = message.sentAtMs,
                                    isFromMe = false, status = "DELIVERED", notificationPending = true,
                                )
                                if (messages.insertMessageIgnore(row) == -1L) PushMailboxDrain.Commit.RETRY
                                else { inserted++; PushMailboxDrain.Commit.COMMITTED }
                            }
                        }
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
        } finally {
            clearIdentitySigningSeed()
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
