package com.vladimir.messenger.data.receipt

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.file.OutgoingFilePreparationService
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.mirror.MirrorRow
import com.vladimir.messenger.data.referral.ReferralAttributionSender
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.story.StoryVideoDelivery
import com.vladimir.messenger.service.routeOutgoingDeliveryAck
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/SQLite; transport sender, cleanup and mirror are injected. */
@RunWith(AndroidJUnit4::class)
class ScopedDeliveryAckInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "scoped_delivery_ack_test.db"
    private val peer = "pk_" + "a".repeat(32)
    private val other = "pk_" + "b".repeat(32)
    private lateinit var db: AppDatabase

    @Before fun setUp() = runBlocking {
        context.deleteDatabase(name)
        db = open()
        db.chatDao().insertChat(ChatEntity(id = "chat", contactId = peer, contactName = "Peer"))
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(name)
    }

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
    private fun row(id: String, chat: String = "chat", recipient: String = peer) = MessageEntity(
        id = id, chatId = chat, senderId = "self", content = "text", timestamp = 1L,
        isFromMe = true, status = "SENT", channel = "LOCAL", recipientId = recipient,
    )
    private suspend fun update(id: String) = db.messageDao().markOutgoingMessageDelivered(id) > 0
    private suspend fun apply(id: String, sender: String) = applyPeerScopedDeliveryAck(db, id, sender, ::update)

    private fun repository(): ChatRepository {
        val peers = FileExchangePeerStore(db.fileExchangePeerDao())
        val preparation = OutgoingFilePreparationService(context, db.fileTransferDao(), peers)
        val stories = StoryVideoDelivery(context, db.chatDao(), db.fileTransferDao(), preparation, peers)
        return ChatRepository(context, db.chatDao(), db.messageDao(), db.groupDao(), stories,
            ReferralAttributionSender(context), db.contactDao(), db)
    }

    @Test fun onlyMatchingPeerCanCommitReleaseOrForwardAndDuplicateRecoversCleanup() = runBlocking {
        db.messageDao().insertMessage(row("message"))
        val releases = mutableListOf<String>()
        val forwards = mutableListOf<String>()
        val notes = mutableListOf<String?>()
        suspend fun route(sender: String?) = routeOutgoingDeliveryAck(
            "message", sender, db, ::update, { _, who -> notes += who }, releases::add, { forwards += "forward" },
        )
        assertFalse(route(other))
        assertEquals("SENT", db.messageDao().getMessageById("message")!!.status)
        assertTrue(releases.isEmpty())
        assertTrue(forwards.isEmpty())
        assertTrue(route(peer))
        assertFalse(route(peer)) // duplicate repairs a crash between Room and native cleanup
        assertEquals(listOf("message", "message"), releases)
        assertEquals(2, forwards.size)
        db.messageDao().updateMessageStatus("message", "READ")
        assertFalse(route(peer))
        assertEquals("READ", db.messageDao().getMessageById("message")!!.status)
        assertEquals(3, releases.size)
        assertEquals(listOf(other, peer, peer, peer), notes)
        db.close()
        db = open()
        assertEquals("READ", db.messageDao().getMessageById("message")!!.status)
    }

    @Test fun savedAndLegacyRecipientsAreScopedAndGroupsNeverMatch() = runBlocking {
        db.chatDao().insertChat(ChatEntity(id = "moved", contactId = other, contactName = "Other"))
        db.chatDao().insertChat(ChatEntity(id = "group", contactId = peer, contactName = "Group"))
        db.groupDao().insertGroup(GroupEntity(id = "group", title = "Group", ownerId = peer))
        listOf(
            row("saved", "moved", peer),
            row("legacy", recipient = ""),
            row("link", recipient = "p2pmessenger://add?node=$peer&name=Alice"),
            row("invalid", recipient = "not-a-peer"),
            row("nul", recipient = "${peer}\u0000suffix"),
            row("group-row", "group", peer),
            row("group-channel").copy(channel = "GROUP"),
            row("topic").copy(topicId = "topic"),
            row("incoming").copy(isFromMe = false),
            row("orphan", "missing", peer),
        ).forEach { db.messageDao().insertMessage(it) }
        assertEquals(ScopedDeliveryAck.REJECTED, apply("saved", other))
        assertEquals(ScopedDeliveryAck.UPDATED, apply("saved", peer))
        assertEquals(ScopedDeliveryAck.REJECTED, apply("legacy", other))
        assertEquals(ScopedDeliveryAck.UPDATED, apply("legacy", peer))
        assertEquals(ScopedDeliveryAck.UPDATED, apply("link", peer))
        for (id in listOf("invalid", "nul", "group-row", "group-channel", "topic", "incoming", "orphan")) {
            assertEquals(ScopedDeliveryAck.REJECTED, apply(id, peer))
            assertEquals("SENT", db.messageDao().getMessageById(id)!!.status)
        }
        assertEquals(ScopedDeliveryAck.REJECTED, apply("link", "not-a-node"))
    }

    @Test fun missingActiveRowCanReachPartnerWithoutReleasingLocalCustody() = runBlocking {
        val partnerName = "scoped_delivery_ack_partner_test.db"
        context.deleteDatabase(partnerName)
        val partner = Room.databaseBuilder(context, AppDatabase::class.java, partnerName).build()
        try {
            partner.chatDao().insertChat(ChatEntity(id = "chat", contactId = peer, contactName = "Peer"))
            partner.messageDao().insertMessage(row("message"))
            val releases = mutableListOf<String>()
            val forwarded = mutableListOf<String>()
            val noted = mutableListOf<Pair<String, String?>>()
            suspend fun route(sender: String?, id: String = "message") = routeOutgoingDeliveryAck(
                id, sender, db, ::update, { transport, claimed -> noted += transport to claimed }, releases::add, { forwarded += sender.orEmpty() },
            )
            assertFalse(route(""))
            assertFalse(route(peer, "bad\u0000id"))
            assertTrue(forwarded.isEmpty())
            noted.clear()
            assertFalse(route(peer)) // active device lacks the outgoing row
            assertEquals(listOf("message" to peer), noted)
            assertEquals(listOf(peer), forwarded)
            assertTrue(releases.isEmpty())
            assertEquals("SENT", partner.messageDao().getMessageById("message")!!.status)
            suspend fun updatePartner(id: String) = partner.messageDao().markOutgoingMessageDelivered(id) > 0
            assertEquals(ScopedDeliveryAck.REJECTED, applyPeerScopedDeliveryAck(partner, "message", other, ::updatePartner))
            assertEquals(ScopedDeliveryAck.UPDATED, applyPeerScopedDeliveryAck(partner, "message", forwarded.single(), ::updatePartner))
            assertEquals("DELIVERED", partner.messageDao().getMessageById("message")!!.status)

            // Group fanout and scheduled fallback use a transport ID, not the group row ID.
            // The transport observer still sees it even though no local row matches.
            val transportId = UUID.randomUUID().toString()
            assertFalse(route(peer, transportId))
            assertEquals(transportId to peer, noted.last())
            assertTrue(releases.isEmpty())
        } finally {
            partner.close()
            context.deleteDatabase(partnerName)
        }
    }

    @Test fun repositoryFallbackScopesIncomingButNeverTreatsItsOwnMirrorAckAsDelivery() = runBlocking {
        val repo = repository()
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        db.messageDao().insertMessage(row(first))
        db.messageDao().insertMessage(row(second))
        val frame = MirrorRow(UUID.randomUUID().toString(), "chat", "", peer, "ack|$first", 1L,
            mine = false, recipientId = "", status = "RECEIVED")
        assertEquals("" to "", repo.applyMirrorIncoming(frame.copy(senderId = other), notify = false))
        assertEquals("SENT", db.messageDao().getMessageById(first)!!.status)
        // A sent ACK is OUR receipt to a peer, not proof that a peer received our text.
        repo.applyMirrorSent(frame.copy(mine = true, senderId = peer))
        assertEquals("SENT", db.messageDao().getMessageById(first)!!.status)
        assertEquals("" to "", repo.applyMirrorIncoming(frame, notify = false))
        assertEquals("DELIVERED", db.messageDao().getMessageById(first)!!.status)
        assertNull(db.messageDao().getMessageById(frame.id))

        // saveIncomingMessage's last-resort filter needs the normal identity-scoped clear lookup.
        val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        val previous = prefs.getString("node_id", null)
        try {
            prefs.edit().putString("node_id", "pk_" + "c".repeat(32)).commit()
            assertFalse(repo.saveIncomingMessage("chat", other, UUID.randomUUID().toString(), "ack|$second", 1L))
            assertEquals("SENT", db.messageDao().getMessageById(second)!!.status)
            assertFalse(repo.saveIncomingMessage("chat", peer, UUID.randomUUID().toString(), "ack|$second", 1L))
            assertEquals("DELIVERED", db.messageDao().getMessageById(second)!!.status)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove("node_id") else editor.putString("node_id", previous)
            editor.commit()
        }
    }

    @Test fun repositoryLegacyJunkRepairUsesStoredSenderBeforeDeletingRows() = runBlocking {
        val repo = repository()
        val good = UUID.randomUUID().toString()
        val wrong = UUID.randomUUID().toString()
        val goodFrame = UUID.randomUUID().toString()
        val wrongFrame = UUID.randomUUID().toString()
        db.messageDao().insertMessage(row(good))
        db.messageDao().insertMessage(row(wrong))
        db.messageDao().insertMessage(row(goodFrame).copy(senderId = peer, isFromMe = false, content = "ack|$good"))
        db.messageDao().insertMessage(row(wrongFrame).copy(senderId = other, isFromMe = false, content = "ack|$wrong"))
        assertEquals(2, repo.cleanupDeliveryAckJunk())
        assertEquals("DELIVERED", db.messageDao().getMessageById(good)!!.status)
        assertEquals("SENT", db.messageDao().getMessageById(wrong)!!.status)
        assertNull(db.messageDao().getMessageById(goodFrame))
        assertNull(db.messageDao().getMessageById(wrongFrame))
    }

    @Test fun roomTransactionPreventsRetargetingBetweenScopeCheckAndUpdate() = runBlocking {
        // The legacy blank recipient makes the chat contact part of the scope.
        db.messageDao().insertMessage(row("message", recipient = ""))
        val entered = CompletableDeferred<Unit>()
        val retargetStarted = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val ack = async(Dispatchers.Default) {
            applyPeerScopedDeliveryAck(db, "message", peer) { id ->
                entered.complete(Unit)
                proceed.await()
                update(id)
            }
        }
        withTimeout(5_000) { entered.await() }
        val retarget = async(Dispatchers.Default) {
            retargetStarted.complete(Unit)
            db.chatDao().insertChat(ChatEntity(id = "chat", contactId = other, contactName = "Other"))
        }
        withTimeout(5_000) { retargetStarted.await() }
        delay(100)
        assertFalse(retarget.isCompleted)
        proceed.complete(Unit)
        assertEquals(ScopedDeliveryAck.UPDATED, withTimeout(5_000) { ack.await() })
        withTimeout(5_000) { retarget.await() }
        assertEquals("DELIVERED", db.messageDao().getMessageById("message")!!.status)
        assertEquals(ScopedDeliveryAck.REJECTED, apply("message", peer))
        assertEquals(ScopedDeliveryAck.ALREADY_COMMITTED, apply("message", other))
    }

    @Test fun failedOrCancelledTransactionCannotReleaseOrForward() = runBlocking {
        db.messageDao().insertMessage(row("message"))
        val releases = mutableListOf<String>()
        var forwards = 0
        val failure = runCatching {
            routeOutgoingDeliveryAck("message", peer, db, { id ->
                update(id)
                error("rollback")
            }, { _, _ -> }, releases::add, { forwards++ })
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("SENT", db.messageDao().getMessageById("message")!!.status)
        assertTrue(releases.isEmpty())
        val entered = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            routeOutgoingDeliveryAck("message", peer, db, { id ->
                update(id)
                entered.complete(Unit)
                wait.await()
                true
            }, { _, _ -> }, releases::add, { forwards++ })
        }
        withTimeout(5_000) { entered.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals("SENT", db.messageDao().getMessageById("message")!!.status)
        assertTrue(releases.isEmpty())
        assertEquals(0, forwards)
    }

    @Test fun observerAndCleanupFailuresAreIndependentButCancellationStillPropagates() = runBlocking {
        db.messageDao().insertMessage(row("message"))
        var forwards = 0
        assertTrue(routeOutgoingDeliveryAck("message", peer, db, ::update,
            { _, _ -> error("scheduler unavailable") }, { error("native unavailable") }, { forwards++ }))
        assertEquals("DELIVERED", db.messageDao().getMessageById("message")!!.status)
        assertEquals(1, forwards)
        val released = mutableListOf<String>()
        assertFalse(routeOutgoingDeliveryAck("message", peer, db, ::update, { _, _ -> }, released::add, { forwards++ }))
        assertEquals(listOf("message"), released) // retry cleanup after a committed status
        assertEquals(2, forwards)

        db.messageDao().insertMessage(row("cancel-before"))
        val cancelledBefore = runCatching {
            routeOutgoingDeliveryAck("cancel-before", peer, db, ::update,
                { _, _ -> throw CancellationException("cancel") }, released::add, { forwards++ })
        }.exceptionOrNull()
        assertTrue(cancelledBefore is CancellationException)
        assertEquals("SENT", db.messageDao().getMessageById("cancel-before")!!.status)
        db.messageDao().insertMessage(row("cancel-after"))
        val cancelledAfter = runCatching {
            routeOutgoingDeliveryAck("cancel-after", peer, db, ::update,
                { _, _ -> }, { throw CancellationException("cancel") }, { forwards++ })
        }.exceptionOrNull()
        assertTrue(cancelledAfter is CancellationException)
        assertEquals("DELIVERED", db.messageDao().getMessageById("cancel-after")!!.status)
        assertEquals(2, forwards)
        assertFalse(routeOutgoingDeliveryAck("cancel-after", peer, db, ::update, { _, _ -> }, released::add))
        assertEquals(listOf("message", "cancel-after"), released)
    }

    @Test fun legacyMqttEventWithoutSenderRemainsUnscopedForCompatibility() = runBlocking {
        db.messageDao().insertMessage(row("message"))
        val releases = mutableListOf<String>()
        var forwards = 0
        assertTrue(routeOutgoingDeliveryAck("message", null, db, ::update, { _, _ -> }, releases::add, { forwards++ }))
        assertEquals("DELIVERED", db.messageDao().getMessageById("message")!!.status)
        assertEquals(listOf("message"), releases)
        assertEquals(0, forwards)
    }
}
