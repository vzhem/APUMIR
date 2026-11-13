package com.vladimir.messenger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.chatclear.ChatClearDelivery
import com.vladimir.messenger.data.directpoll.DirectPollRepository
import com.vladimir.messenger.data.local.AppDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real legacy ACK route and app-private files, not network authentication. */
@RunWith(AndroidJUnit4::class)
class DeletionOutboxRoutingInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun entry(chat: String, peer: String, kind: String = DeletionOutbox.KIND_DIRECT) =
        DeletionOutbox.Entry("same-id", kind, chat, peer, "", 100L, 100L, 1, emptyList())

    private fun repository(db: AppDatabase, outbox: DeletionOutbox): MessageDeletionRepository =
        MessageDeletionRepository(
            db.messageDao(), db.chatDao(), db.messageReactionDao(), outbox,
            ChatClearDelivery(context, db.chatDao(), db), db.groupDao(),
            DirectPollRepository(db.directPollDao(), db.chatDao()),
            // The tombstone writer needs the app context; the test uses the real one.
            context,
        )

    @Test fun legacyAckRouteRemovesOnlyTheNamedPeerRequest() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = File(context.cacheDir, "deletion-route-${UUID.randomUUID()}")
        try {
            val queue = DeletionOutbox(root)
            val first = entry("chat-a", "pk_a")
            val second = entry("chat-b", "pk_b")
            val group = entry("group", "", DeletionOutbox.KIND_GROUP)
            listOf(first, second, group).forEach(queue::add)
            val route = repository(db, queue)
            assertFalse(route.routeIncoming("pk_a", "ordinary text", authenticated = true))
            assertTrue(route.routeIncoming("pk_unknown", "APUDEL1|ack|same-id", authenticated = true))
            assertEquals(listOf(first, second, group), DeletionOutbox(root).all())
            assertTrue(route.routeIncoming("pk_a", "APUDEL1|ack|same-id", authenticated = true))
            assertEquals(listOf(second, group), DeletionOutbox(root).all())
            assertTrue(route.routeIncoming("pk_b", "APUDEL1|ack|same-id", authenticated = true))
            assertEquals(listOf(group), DeletionOutbox(root).all())
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun legacyAckRouteLeavesAmbiguousRequestsForRetry() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = File(context.cacheDir, "deletion-route-${UUID.randomUUID()}")
        try {
            val queue = DeletionOutbox(root)
            val first = entry("old-chat", "pk_peer")
            val second = entry("restored-chat", "pk_peer")
            queue.add(first)
            queue.add(second)
            assertTrue(repository(db, queue).routeIncoming("pk_peer", "APUDEL1|ack|same-id", authenticated = true))
            assertEquals(listOf(first, second), DeletionOutbox(root).all())
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }
}
