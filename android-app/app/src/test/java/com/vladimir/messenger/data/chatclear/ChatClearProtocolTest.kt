package com.vladimir.messenger.data.chatclear

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class ChatClearProtocolTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun offlineRequestRemainsUntilTheExpectedPeersAuthenticatedAck() = runTest {
        val outbox = ChatClearOutbox(temporary.root.resolve("outbox"))
        val batch = ChatClearWire.Batch(UUID.randomUUID(), listOf("old", "old-file"))
        outbox.enqueue(listOf(ChatClearOutbox.Entry("owner", "friend", "chat", batch)))
        val applied = mutableSetOf<String>()
        val replies = mutableListOf<String>()
        val receiver = ChatClearProtocol(
            ChatClearOutbox(temporary.root.resolve("receiver")),
            { "receiver" },
            { _, request -> applied.addAll(request.ids); true },
            { _, response -> replies.add(response) },
        )
        val sender = ChatClearProtocol(outbox, { "owner" }, { _, _ -> false }, { _, _ -> })
        val packet = ChatClearWire.encode(outbox.pending().single().batch)
        assertTrue(receiver.receive("owner", true, packet))
        assertTrue(receiver.receive("owner", true, packet)) // retry is safe to apply twice
        assertEquals(setOf("old", "old-file"), applied)
        assertEquals(1, outbox.pending().size)
        sender.receive("attacker", true, replies.first())
        sender.receive("friend", false, replies.first())
        assertEquals(1, outbox.pending().size)
        sender.receive("friend", true, replies.first())
        assertTrue(outbox.pending().isEmpty())
    }
}
