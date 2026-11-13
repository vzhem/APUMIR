package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.mirror.MirrorRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorOutgoingScopesTest {
    private fun row(peer: String) = MirrorRow(
        id = "message", chatId = "legacy-chat", contactName = "Friend", senderId = "self",
        content = "text", timestamp = 42L, mine = true, recipientId = peer, status = "SENT",
    )

    @Test fun canonicalAndRawTombstonesStopEchoBeforeChatResolution() {
        val alias = "https://invite/?node=pk_friend&x=1"
        val checks = mutableListOf<Pair<String, String>>()
        assertFalse(mirrorEchoMayResolve(row(alias)) { peer, id ->
            checks += peer to id
            peer == "pk_friend"
        })
        assertEquals(listOf(alias to "message", "pk_friend" to "message"), checks)
        checks.clear()
        assertFalse(mirrorEchoMayResolve(row(alias)) { peer, id ->
            checks += peer to id
            peer == alias
        })
        assertEquals(listOf(alias to "message"), checks)
        assertTrue(mirrorEchoMayResolve(row(alias)) { _, _ -> false })
    }

    @Test fun malformedPeersFailBeforeLookupAndBlankLegacyEchoUsesOnlyExistingChat() {
        var lookups = 0
        val lookup: (String, String) -> Boolean = { _, _ -> lookups++; false }
        for (peer in listOf("pk_", "pk_friend\u0000other", "pk_friend|other", "not-a-peer", "pk_" + "я".repeat(64))) {
            assertFalse(mirrorEchoMayResolve(row(peer), lookup))
        }
        assertEquals(0, lookups)
        assertTrue(mirrorEchoMayResolve(row(""), lookup))
        assertEquals(0, lookups)
    }

    @Test fun identityOrStorageFailuresAreNotTreatedAsPermission() {
        assertTrue(runCatching {
            mirrorEchoMayResolve(row("pk_friend")) { _, _ -> error("identity unavailable") }
        }.isFailure)
    }
}
