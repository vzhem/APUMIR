package com.vladimir.messenger.data.reply

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.mirror.MirrorFrameCipher
import com.vladimir.messenger.data.mirror.MirrorOutgoingFrame
import com.vladimir.messenger.data.mirror.MirrorReplyTrust
import com.vladimir.messenger.data.mirror.MirrorRoomToken
import com.vladimir.messenger.data.mirror.MirrorRow
import com.vladimir.messenger.data.repository.AutomaticOutboxSender
import com.vladimir.messenger.data.repository.OutgoingReplySidecar
import com.vladimir.messenger.data.repository.QueuedOutboxPump
import com.vladimir.messenger.data.repository.roomReplyAwareOutboxSender
import com.vladimir.messenger.data.repository.MirrorOutgoingDispatcher
import com.vladimir.messenger.data.repository.MirrorOutgoingStore
import com.vladimir.messenger.data.repository.MirrorStage
import com.vladimir.messenger.data.repository.outgoingTextRow
import com.vladimir.messenger.data.security.MessageSealer
import com.vladimir.messenger.data.security.SealedWire
import com.vladimir.messenger.service.canAcknowledgeCfDuplicate
import java.util.UUID
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
import org.json.JSONObject
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
        val prefsPrefix = "mirror-frame-ffi-${UUID.randomUUID()}-"
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences(prefsPrefix + name, mode)
        }
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

            fun seal(id: String, seed: Byte, from: ByteArray, secret: ByteArray, plaintext: String = text): String {
                installIdentitySigningSeed(1.toUByte(), id, ByteArray(32) { seed })
                val key = ByteArray(32) { 7 }
                val iv = ByteArray(12) { (it + 1).toByte() }
                val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                    doFinal(plaintext.toByteArray(Charsets.UTF_8))
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

            // Production Room retry and reply ingress, joined by real Rust
            // signatures/envelopes and AES. The network is deliberately fake:
            // the first sidecar arrives before its body and must not be ACKed.
            val sourceDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                sourceDb.chatDao().insertChat(ChatEntity(id = "outgoing-chat", contactId = recipientId, contactName = "Receiver"))
                sourceDb.messageDao().insertMessage(MessageEntity(
                    id = "retry-parent", chatId = "outgoing-chat", senderId = "self", content = "answer",
                    timestamp = 42L, isFromMe = true, status = "QUEUED_OFFLINE", recipientId = recipientId,
                    replyToId = "target", replyAuthor = "Alice", replyText = "quoted",
                ))
                var bodies = 0
                val results = mutableListOf<DirectReplyResult>()
                val guard = AutomaticOutboxSender(
                    chatContact = { sourceDb.chatDao().getChatById(it)?.contactId },
                    isCleared = { _, _ -> false },
                    transport = { parent, _, _, _ ->
                        bodies++
                        if (bodies == 1) false else {
                            db.messageDao().insertMessage(MessageEntity(
                                id = parent, chatId = "chat", senderId = senderId, content = "answer",
                                timestamp = 42L, isFromMe = false, status = "DELIVERED", channel = "LOCAL",
                            ))
                            true
                        }
                    },
                    recordFailure = { throw AssertionError("unexpected parent guard failure", it) },
                )
                val sidecar = OutgoingReplySidecar(
                    sourceDb.messageDao()::getMessageById, guard::preflight,
                    { wireId, _, _, payload ->
                        val wire = seal(senderId, 2, sender, senderSecret, payload)
                        val opened = open(wire)
                        val result = receiver.network(senderId, "chat", wireId, opened,
                            MessageSealer.openedByExpectedSender(wire, senderId), wire)
                        results += result
                        result == DirectReplyResult.APPLIED
                    },
                    { throw AssertionError("unexpected retry-sidecar failure", it) },
                )
                val retry = roomReplyAwareOutboxSender(
                    sourceDb.messageDao(), sourceDb.chatDao(), sourceDb.groupDao(), guard, guard, sidecar,
                )
                val pump = QueuedOutboxPump(sourceDb.messageDao(), sourceDb.chatDao(), retry::send)
                assertEquals(0, pump.pump(20).sent)
                assertEquals(listOf(DirectReplyResult.UNAPPLIED), results)
                assertEquals(1, acknowledgements)
                assertNull(db.messageDao().getMessageById("retry-parent"))
                assertEquals(1, pump.pump(20).sent)
                assertEquals(listOf(DirectReplyResult.UNAPPLIED, DirectReplyResult.APPLIED), results)
                assertEquals("target", db.messageDao().getMessageById("retry-parent")!!.replyToId)
                assertEquals(2, forwards)
                assertEquals(2, acknowledgements)
            } finally {
                sourceDb.close()
            }

            val fakeSelfWire = seal(recipientId, 8, fakeSelf, attackerSecret)
            assertEquals(text, open(fakeSelfWire))
            assertFalse(canAcknowledgeCfDuplicate(false, senderId, senderId, true) { MessageSealer.openedByExpectedSender(fakeSelfWire, senderId) })
            assertTrue(MessageSealer.openedByExpectedSender(fakeSelfWire, recipientId))
            assertFalse(MirrorReplyTrust.verified(true, null, fakeSelfWire, { recipient }, { SealedWire.senderBinding(fakeSelfWire) }))
            val realSelfWire = seal(recipientId, 1, recipient, recipientSecret)
            assertEquals(text, open(realSelfWire))
            assertTrue(MirrorReplyTrust.verified(true, null, realSelfWire, { recipient }, { SealedWire.senderBinding(realSelfWire) }))

            // Model the legacy mirror fallback with real FFI envelopes. An
            // attacker can seal a valid outgoing row to our public binding and
            // claim our node ID; opening it alone does not establish pairing.
            // This invokes the production adapter, trust, dispatcher and Room
            // store. The channel and network are not run; its open operation
            // is modeled with the same FFI and AES-GCM primitives above.
            assertTrue(isolatedContext.getSharedPreferences("apu_file_exchange", Context.MODE_PRIVATE).edit()
                .putString("signed_public_binding_v1", Base64.encodeToString(recipient, Base64.NO_WRAP)).commit())
            val outgoing = MirrorRow(id = "paired-outgoing", chatId = "shadow-chat", contactName = "Sender",
                senderId = "self", content = "answer", timestamp = 42L, mine = true, recipientId = senderId,
                status = "PENDING", replyToId = "target", replyAuthor = "Alice", replyText = "quoted")
            val outgoingJson = outgoing.toJson().toString()
            val store = MirrorOutgoingStore(db) { _, _ -> false }
            var attempts = 0
            val dispatcher = MirrorOutgoingDispatcher(
                isCleared = { _, _ -> false },
                localChat = { assertEquals(senderId, it); "chat" },
                initial = { frame, chat ->
                    val row = outgoingTextRow(frame.id, chat, frame.recipientId, frame.content, frame.timestamp,
                        DirectReplyWire.Target(frame.replyToId, frame.replyAuthor, frame.replyText),
                        frame.textColor, frame.senderNameColor)
                    assertEquals(MirrorStage.Inserted, store.stageNew(row, frame.recipientId, frame.content))
                    attempts++
                },
                manual = { _, _ -> error("unexpected retry") },
                recordFailure = { throw AssertionError("unexpected dispatch failure", it) },
            )
            suspend fun routeOutgoing(wire: String) {
                val body = MirrorOutgoingFrame.open(isolatedContext, recipientId, wire) { value ->
                    val key = MirrorRoomToken.loadSyncKey(isolatedContext)
                    val plaintext = key?.let { MirrorFrameCipher.open(it, value) } ?: open(value)
                    JSONObject(plaintext)
                } ?: return
                dispatcher.dispatch(MirrorRow.fromJson(body))
            }
            val forgedOutgoing = seal(recipientId, 8, fakeSelf, attackerSecret, outgoingJson)
            assertEquals(outgoingJson, open(forgedOutgoing))
            assertTrue(MessageSealer.openedByExpectedSender(forgedOutgoing, recipientId))
            routeOutgoing(forgedOutgoing)
            assertNull(db.messageDao().getMessageById(outgoing.id))
            assertEquals(0, attempts)
            val realOutgoing = seal(recipientId, 1, recipient, recipientSecret, outgoingJson)
            routeOutgoing(realOutgoing)
            assertEquals(1, attempts)
            assertEquals("target", db.messageDao().getMessageById(outgoing.id)!!.replyToId)
            val relabelledEcho = outgoing.copy(id = "relabeled-echo", senderId = "", contactName = "", status = "SENT")
            routeOutgoing(seal(recipientId, 1, recipient, recipientSecret, relabelledEcho.toJson().toString()))
            assertEquals(1, attempts)
            assertNull(db.messageDao().getMessageById(relabelledEcho.id))

            // The modern paired route uses a different device binding and the
            // shared authenticated room key instead of the legacy self-key.
            val roomKey = ByteArray(32) { (it + 11).toByte() }
            assertTrue(isolatedContext.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).edit()
                .putString("mirror_sync_key_v1", Base64.encodeToString(roomKey,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)).commit())
            val paired = outgoing.copy(id = "paired-room-key")
            routeOutgoing(MirrorFrameCipher.seal(roomKey, paired.toJson().toString())!!)
            assertEquals(2, attempts)
            assertEquals("target", db.messageDao().getMessageById(paired.id)!!.replyToId)
            roomKey.fill(0)

            val parts = SealedWire.decode(realWire)!!
            val changed = parts.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertTrue(runCatching { open(SealedWire.encode(parts.keyEnvelope, parts.iv, changed)) }.isFailure)
        } finally {
            clearIdentitySigningSeed()
            db.close()
            context.deleteSharedPreferences(prefsPrefix + "apu_file_exchange")
            context.deleteSharedPreferences(prefsPrefix + "p2p_prefs")
            senderSecret.fill(0)
            attackerSecret.fill(0)
            recipientSecret.fill(0)
        }
    }
}
