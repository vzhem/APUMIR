package com.vladimir.messenger.ui.screens.onboarding

import com.vladimir.messenger.data.local.entity.NicknameEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class InviterNicknameLookupTest {
    private fun entry(id: String) = NicknameEntity(id, "friend", 1)

    @Test fun `exclude own and previous device identities`() {
        val entries = listOf(entry("SELF"), entry("old"), entry("friend"))
        val result = InviterNicknameLookup.candidates(entries, "self") { it == "old" }
        assertEquals(listOf("friend"), result.map { it.ownerId })
    }

    @Test fun `duplicate owner is not treated as ambiguous`() {
        val result = InviterNicknameLookup.candidates(listOf(entry("peer"), entry("PEER")), null) { false }
        assertEquals(1, result.size)
    }

    @Test fun `different owners remain ambiguous`() {
        val result = InviterNicknameLookup.candidates(listOf(entry("one"), entry("two")), null) { false }
        assertEquals(2, result.size)
    }
}
