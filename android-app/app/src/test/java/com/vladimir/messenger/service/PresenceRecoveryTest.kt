package com.vladimir.messenger.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceRecoveryTest {
    private class Store {
        @Volatile var now = 1_000_000L
        var resetContactCount = 0
        var resetChatCount = 0
        var resetRouterCount = 0
        var failResetChat = false
        var failContactWrites = 0
        var failChatWrites = 0
        var resetGate: suspend () -> Unit = {}
        var offlineGate: suspend () -> Unit = {}
        val contacts = mutableMapOf<String, Pair<Boolean, Long>>()
        val chats = mutableMapOf<String, Boolean>()
        val router = mutableMapOf<String, Long>()
        val errors = mutableListOf<String>()
        val recovery = PresenceRecovery(
            resetContacts = {
                resetContactCount++
                resetGate()
                contacts.replaceAll { _, old -> false to old.second }
            },
            resetChats = {
                resetChatCount++
                if (failResetChat) { failResetChat = false; error("reset chat") }
                chats.replaceAll { _, _ -> false }
            },
            onReset = { resetRouterCount++; router.clear() },
            updateContact = { id, online, seen ->
                if (!online) offlineGate()
                if (failContactWrites > 0) { failContactWrites--; error("contact write") }
                contacts[id] = online to seen
            },
            updateChats = { peer, id, online ->
                if (failChatWrites > 0) { failChatWrites--; error("chat write") }
                chats[peer] = online
                chats[id] = online
            },
            onOnline = { peer, seen -> router[peer] = seen },
            onOffline = { peer, _ -> router.remove(peer) },
            recordFailure = { phase, _ -> errors += phase },
            nowMs = { now },
        )
    }

    private val peer = "pk_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val contact = "old-contact-primary-key"

    @Test
    fun `reset and discovery are serialized and reset is idempotent`() = runBlocking {
        val s = Store()
        s.contacts[contact] = true to (s.now - 10_000)
        s.chats[peer] = true
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        s.resetGate = { entered.complete(Unit); release.await() }
        val reset = async(Dispatchers.Default) { s.recovery.initialize() }
        entered.await()
        val seen = async(Dispatchers.Default) { s.recovery.observe(peer, contact, s.now) }
        release.complete(Unit)
        reset.await()
        assertNotNull(seen.await())
        s.recovery.initialize()
        assertEquals(1, s.resetContactCount)
        assertEquals(1, s.resetChatCount)
        assertEquals(1, s.resetRouterCount)
        assertEquals(true to s.now, s.contacts[contact])
        assertEquals(true, s.chats[peer])
        assertEquals(true, s.chats[contact])
        assertEquals(s.now, s.router[peer])
    }

    @Test
    fun `a failed partial reset is retried before any online write`() = runBlocking {
        val s = Store()
        s.failResetChat = true
        try {
            s.recovery.initialize()
            throw AssertionError("reset should fail")
        } catch (expected: IllegalStateException) {
            assertEquals("reset chat", expected.message)
        }
        assertNotNull(s.recovery.observe(peer, contact, s.now))
        assertEquals(2, s.resetContactCount)
        assertEquals(2, s.resetChatCount)
        assertEquals(1, s.resetRouterCount)
        assertEquals(true to s.now, s.contacts[contact])
    }

    @Test
    fun `partial online writes are retried by sweep without another heartbeat`() = runBlocking {
        val s = Store()
        s.failContactWrites = 1
        s.failChatWrites = 1
        s.recovery.observe(peer, contact, s.now)
        assertNull(s.contacts[contact])
        assertTrue(s.errors.contains("contact presence"))
        assertTrue(s.errors.contains("chat presence"))
        s.now += 1_000
        s.recovery.sweep()
        assertEquals(true to (s.now - 1_000), s.contacts[contact])
        assertEquals(true, s.chats[peer])
    }

    @Test
    fun `sweep racing new observation cannot overwrite it offline`() = runBlocking {
        val s = Store()
        s.recovery.observe(peer, contact, s.now)
        s.now += PresenceRecovery.TTL_MS + 1
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        s.offlineGate = { entered.complete(Unit); release.await() }
        val sweep = async(Dispatchers.Default) { s.recovery.sweep() }
        entered.await()
        s.now += 1
        val seen = async(Dispatchers.Default) { s.recovery.observe(peer, contact, s.now) }
        release.complete(Unit)
        sweep.await()
        assertNotNull(seen.await())
        assertEquals(true to s.now, s.contacts[contact])
        assertEquals(true, s.chats[peer])
        assertEquals(s.now, s.router[peer])
    }

    @Test
    fun `stale missing and implausibly future events cannot turn a contact online`() = runBlocking {
        val s = Store()
        assertNull(s.recovery.observe(peer, contact, null))
        assertNull(s.recovery.observe(peer, contact, s.now - PresenceRecovery.TTL_MS - 1))
        assertNull(s.recovery.observe(peer, contact, s.now + PresenceRecovery.MAX_CLOCK_SKEW_MS + 1))
        assertNull(s.recovery.observe(peer, contact, 0))
        assertTrue(s.contacts.isEmpty())
        assertTrue(s.chats.isEmpty())
        assertTrue(s.router.isEmpty())
        val accepted = s.recovery.observe(peer, contact, s.now + 10_000)
        assertEquals(s.now, accepted!!.seenAtMs)
        assertEquals(s.now, s.contacts[contact]?.second) // future skew never extends TTL
    }

    @Test
    fun `retained event expires from original observation rather than delivery time`() = runBlocking {
        val s = Store()
        val original = s.now - 150_000
        s.recovery.observe(peer, contact, original)
        s.now += 50_001
        s.recovery.sweep()
        assertEquals(false to original, s.contacts[contact])
        assertEquals(false, s.chats[peer])
        assertEquals(false, s.chats[contact])
        assertFalse(s.router.containsKey(peer))
    }

    @Test
    fun `light touches do not postpone heavy updates forever`() = runBlocking {
        val s = Store()
        assertFalse(s.recovery.observe(peer, contact, s.now)!!.lightTouch)
        repeat(5) {
            s.now += 5_000
            assertTrue(s.recovery.observe(peer, contact, s.now)!!.lightTouch)
        }
        s.now += 5_000
        assertFalse(s.recovery.observe(peer, contact, s.now)!!.lightTouch)
        assertEquals(s.now, s.contacts[contact]?.second)
    }

    @Test
    fun `lost peer cannot be resurrected by the same retained heartbeat`() = runBlocking {
        val s = Store()
        val seen = s.now
        s.recovery.observe(peer, contact, seen)
        s.recovery.lost(peer)
        assertNull(s.recovery.observe(peer, contact, seen))
        assertEquals(false to seen, s.contacts[contact])
        s.now += 1
        assertTrue(s.recovery.observe(peer, contact, s.now)!!.becameOnline)
        assertEquals(true to s.now, s.contacts[contact])
    }

    @Test
    fun `a newly added contact can use an already observed peer`() = runBlocking {
        val s = Store()
        assertFalse(s.recovery.observe(peer, null, s.now)!!.becameOnline)
        s.now += 1
        assertTrue(s.recovery.observe(peer, contact, s.now)!!.becameOnline)
        assertEquals(true to s.now, s.contacts[contact])
    }

    @Test
    fun `cancelling a partial reset unlocks it and the next event retries`() = runBlocking {
        val s = Store()
        val entered = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        s.resetGate = { entered.complete(Unit); blocked.await() }
        val job = launch(Dispatchers.Default) { s.recovery.initialize() }
        entered.await()
        job.cancelAndJoin()
        s.resetGate = {}
        assertNotNull(s.recovery.observe(peer, contact, s.now))
        assertEquals(2, s.resetContactCount)
        assertEquals(true to s.now, s.contacts[contact])
    }
}
