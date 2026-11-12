package com.vladimir.messenger.data.reply

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.mirror.MirrorReplyTrust
import com.vladimir.messenger.data.security.MessageSealer
import com.vladimir.messenger.data.security.SealedWire
import com.vladimir.messenger.service.canAcknowledgeCfDuplicate
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.clearIdentitySigningSeed
import uniffi.p2p_core.createFileExchangeBinding
import uniffi.p2p_core.createFileKeyEnvelope
import uniffi.p2p_core.createIdentitySigningBinding
import uniffi.p2p_core.fileExchangeBindingNodeId
import uniffi.p2p_core.identitySigningMode
import uniffi.p2p_core.installIdentitySigningSeed
import uniffi.p2p_core.openFileKeyEnvelope
import uniffi.p2p_core.verifyFileExchangeBinding

/** Real disposable Rust signatures/envelopes and Room pins; routing and network remain fakes. */
@RunWith(AndroidJUnit4::class)
class DirectReplyRealFfiInstrumentedTest {
    @Test fun selfClaimedIdCannotPassThePinnedReplyOrLegacyMirrorBinding() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).getBoolean("identity_created", false))
        assertFalse(RustBridge.isRunning())
        assertEquals("legacy-only", identitySigningMode())
        val senderId = "pk_" + "2".repeat(32)
        val recipientId = "pk_" + "1".repeat(32)
        val recipientSecret = ByteArray(32) { 3 }
        val senderSecret = ByteArray(32) { 4 }
        val attackerSecret = ByteArray(32) { 5 }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            fun binding(id: String, seed: Byte, exchange: ByteArray): ByteArray {
                installIdentitySigningSeed(1.toUByte(), id, ByteArray(32) { seed })
                return createFileExchangeBinding(createIdentitySigningBinding(100), exchange, 200)
            }
            val sender = binding(senderId, 2, senderSecret)
            val impostor = binding(senderId, 9, attackerSecret)
            val recipient = binding(recipientId, 1, recipientSecret)
            val fakeSelf = binding(recipientId, 8, attackerSecret)
            assertTrue(verifyFileExchangeBinding(sender))
            assertTrue(verifyFileExchangeBinding(impostor))
            assertEquals(senderId, fileExchangeBindingNodeId(sender))
            assertEquals(senderId, fileExchangeBindingNodeId(impostor))
            assertFalse(sender.contentEquals(impostor))

            val pins = FileExchangePeerStore(db.fileExchangePeerDao())
            assertTrue(pins.pinFirstSeen(sender).newlyPinned)
            db.chatDao().insertChat(ChatEntity(id = "chat", contactId = senderId, contactName = "Sender"))
            db.messageDao().insertMessage(MessageEntity(id = "parent", chatId = "chat", senderId = senderId,
                content = "body", timestamp = 1L, isFromMe = false, channel = "LOCAL"))
            val text = DirectReplyWire.build("parent", "target", "Alice", "quoted")
            val id = DirectReplyWire.transportMessageId("parent")
            var forwards = 0
            var acknowledgements = 0
            val pinned = DirectReplyPinnedApply(SealedWire::senderBinding,
                { peer, action -> pins.withPinnedBinding(peer, action) },
                { peer, packet -> db.messageDao().applyDirectReplyFromPeer(peer, packet.messageId, packet.replyToId, packet.replyAuthor, packet.replyText) > 0 })
            val receiver = DirectReplyIngress({ _, _ -> false }, { _, _ -> error("unexpected mirror") }, pinned::apply,
                { _, _, _, _ -> forwards++ }, { _, _ -> acknowledgements++ }, { _, _ -> error("unexpected storage failure") })

            fun seal(id: String, seed: Byte, from: ByteArray, secret: ByteArray): String {
                installIdentitySigningSeed(1.toUByte(), id, ByteArray(32) { seed })
                val key = ByteArray(32) { 7 }
                val iv = ByteArray(12) { (it + 1).toByte() }
                val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                    doFinal(text.toByteArray(Charsets.UTF_8))
                }
                val wrapped = createFileKeyEnvelope(from, recipient, secret, SealedWire.manifest(ciphertext), key)
                key.fill(0)
                return SealedWire.encode(wrapped, iv, ciphertext)
            }
            fun open(wire: String): String {
                installIdentitySigningSeed(1.toUByte(), recipientId, ByteArray(32) { 1 })
                val parts = SealedWire.decode(wire)!!
                val key = openFileKeyEnvelope(parts.keyEnvelope, recipient, recipientSecret, SealedWire.manifest(parts.ciphertext))
                return try {
                    Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, parts.iv))
                        doFinal(parts.ciphertext).toString(Charsets.UTF_8)
                    }
                } finally { key.fill(0) }
            }

            val forgedWire = seal(senderId, 9, impostor, attackerSecret)
            val forgedText = open(forgedWire)
            assertEquals(text, forgedText)
            // This is a valid signature over a self-claimed ID, not proof that
            // the signing key belongs to the contact.
            assertTrue(MessageSealer.openedByExpectedSender(forgedWire, senderId))
            // CF duplicate compatibility checks the claimed node, not the stored pin.
            assertTrue(canAcknowledgeCfDuplicate(false, senderId, senderId, true) { MessageSealer.openedByExpectedSender(forgedWire, senderId) })
            assertEquals(DirectReplyResult.REJECTED, receiver.network(senderId, "chat", id, forgedText,
                MessageSealer.openedByExpectedSender(forgedWire, senderId), forgedWire))
            assertNull(db.messageDao().getMessageById("parent")!!.replyToId)
            assertEquals(0, forwards)
            assertEquals(0, acknowledgements)

            val realWire = seal(senderId, 2, sender, senderSecret)
            val realText = open(realWire)
            assertTrue(MessageSealer.openedByExpectedSender(realWire, senderId))
            assertTrue(canAcknowledgeCfDuplicate(false, senderId, senderId, true) { MessageSealer.openedByExpectedSender(realWire, senderId) })
            assertEquals(DirectReplyResult.APPLIED, receiver.network(senderId, "chat", id, realText,
                MessageSealer.openedByExpectedSender(realWire, senderId), realWire))
            assertEquals("target", db.messageDao().getMessageById("parent")!!.replyToId)
            assertEquals(1, forwards)
            assertEquals(1, acknowledgements)

            val fakeSelfWire = seal(recipientId, 8, fakeSelf, attackerSecret)
            assertEquals(text, open(fakeSelfWire))
            assertFalse(canAcknowledgeCfDuplicate(false, senderId, senderId, true) { MessageSealer.openedByExpectedSender(fakeSelfWire, senderId) })
            assertTrue(MessageSealer.openedByExpectedSender(fakeSelfWire, recipientId))
            assertFalse(MirrorReplyTrust.verified(true, null, fakeSelfWire, { recipient }, { SealedWire.senderBinding(fakeSelfWire) }))
            val realSelfWire = seal(recipientId, 1, recipient, recipientSecret)
            assertEquals(text, open(realSelfWire))
            assertTrue(MirrorReplyTrust.verified(true, null, realSelfWire, { recipient }, { SealedWire.senderBinding(realSelfWire) }))

            val parts = SealedWire.decode(realWire)!!
            val changed = parts.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertTrue(runCatching { open(SealedWire.encode(parts.keyEnvelope, parts.iv, changed)) }.isFailure)
        } finally {
            clearIdentitySigningSeed()
            db.close()
            senderSecret.fill(0)
            attackerSecret.fill(0)
            recipientSecret.fill(0)
        }
    }
}
