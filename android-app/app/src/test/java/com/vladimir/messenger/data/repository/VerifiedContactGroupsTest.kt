package com.vladimir.messenger.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedContactGroupsTest {
    @Test
    fun `same name cannot join independent verified devices or unverified contacts`() {
        val anchors = mapOf("a1" to "A", "a2" to "A", "b1" to "B", "b2" to "B")
        val groups = VerifiedContactGroups.partition(listOf("a1", "b1", "unknown", "b2", "a2")) { a, b ->
            anchors[a] != null && anchors[a] == anchors[b]
        }
        assertEquals(listOf(listOf("a1", "a2"), listOf("b1", "b2")), groups)
        assertTrue(groups.none { "unknown" in it })
    }

    @Test
    fun `incomplete or non-transitive proof never merges an unverified pair`() {
        val proved = setOf(setOf("a", "b"), setOf("a", "c"))
        val groups = VerifiedContactGroups.partition(listOf("a", "b", "c", "a")) { a, b ->
            setOf(a, b) in proved
        }
        assertEquals(listOf(listOf("a", "b")), groups)
    }

    @Test
    fun `matching names with no identity proof produce no merge`() {
        assertTrue(VerifiedContactGroups.partition(listOf("a", "b")) { _, _ -> false }.isEmpty())
    }
}
