package com.vladimir.messenger.data.chatclear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClearLookupTest {
    @Test fun blankIdentityFailsClosedBeforeStorageCanBeSkipped() {
        var lookups = 0
        val lookup = ChatClearLookup(owner = { "" }, contains = { _, _, _ -> lookups++; false })
        assertTrue(runCatching { lookup.isCleared("pk_peer", "message") }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, lookups)
    }

    @Test fun scopedStorageResultIsUsedAndFailuresAreNotTurnedIntoAbsence() {
        val calls = mutableListOf<Triple<String, String, String>>()
        val lookup = ChatClearLookup(
            owner = { "owner" },
            contains = { owner, peer, id ->
                calls += Triple(owner, peer, id)
                if (id == "broken") error("storage unavailable")
                peer == "pk_peer" && id == "cleared"
            },
        )
        assertTrue(lookup.isCleared("pk_peer", "cleared"))
        assertFalse(lookup.isCleared("pk_other", "cleared"))
        assertTrue(runCatching { lookup.isCleared("pk_peer", "broken") }.exceptionOrNull() is IllegalStateException)
        assertEquals(Triple("owner", "pk_peer", "cleared"), calls.first())
    }

    @Test fun emptyScopeNeverCreatesStorageOrRequiresIdentity() {
        val lookup = ChatClearLookup(owner = { error("unexpected identity read") }, contains = { _, _, _ -> error("unexpected storage read") })
        assertFalse(lookup.isCleared("", "message"))
        assertFalse(lookup.isCleared("pk_peer", ""))
    }
}
