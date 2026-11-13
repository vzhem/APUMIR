package com.vladimir.messenger.service

import com.vladimir.messenger.domain.model.Contact
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresenceAudienceObserverTest {
    @Test
    fun `initial seed and routing changes refresh but online dots do not`() = runBlocking {
        val a = Contact(id = "legacy", fingerprint = "pk_aaaaaaa", displayName = "A")
        val state = MutableStateFlow(listOf(a))
        val refreshed = Channel<Int>(Channel.UNLIMITED)
        var calls = 0
        val job = PresenceAudienceObserver.start(this, state, refresh = { refreshed.send(++calls) }, recordFailure = { throw it })
        assertEquals(1, withTimeout(2_000) { refreshed.receive() })
        state.value = listOf(a.copy(isOnline = true, lastSeenAtMs = 42))
        assertNull(withTimeoutOrNull(100) { refreshed.receive() })
        state.value = listOf(a.copy(fingerprint = "pk_bbbbbbb"))
        assertEquals(2, withTimeout(2_000) { refreshed.receive() })
        state.value = emptyList()
        assertEquals(3, withTimeout(2_000) { refreshed.receive() })
        job.cancelAndJoin()
    }

    @Test
    fun `failed audience refresh retries even when contacts did not change`() = runBlocking {
        val state = MutableStateFlow(emptyList<Contact>())
        val refreshed = Channel<Int>(Channel.UNLIMITED)
        var calls = 0
        var failures = 0
        val job = PresenceAudienceObserver.start(this, state, refresh = {
            calls++
            if (calls == 1) error("db busy")
            refreshed.send(calls)
        }, recordFailure = { failures++ }, retryMs = 1)
        assertEquals(2, withTimeout(2_000) { refreshed.receive() })
        assertEquals(1, failures)
        job.cancelAndJoin()
    }
}
