package com.vladimir.messenger.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryRevokeTest {
    private fun story(
        id: String,
        authorId: String = "alice",
        mine: Boolean = false,
    ) = StoryStore.Story(id, authorId, "A", "hi", 0, 1_000L, mine, null)

    @Test fun remainingDropsOnlyThatAuthorsStory() {
        val items = listOf(
            story("s1", "alice"),
            story("s1", "bob"),
            story("s2", "alice"),
        )
        val left = StoryRevoke.remaining(items, "alice", "s1")
        assertEquals(listOf("bob:s1", "alice:s2"), left.map { "${it.authorId}:${it.id}" })
    }

    @Test fun tombstoneCoversTheStoryLifetime() {
        val until = StoryRevoke.tombstoneUntilMs(1_000L, lifetimeMs = 24L * 60 * 60 * 1000)
        assertTrue(StoryRevoke.isActive(until, 1_000L))
        assertTrue(StoryRevoke.isActive(until, 1_000L + 23 * 60 * 60 * 1000))
        assertFalse(StoryRevoke.isActive(until, until))
    }

    @Test fun tombstoneKeyIsPerAuthor() {
        assertTrue(StoryRevoke.key("alice", "s1") != StoryRevoke.key("bob", "s1"))
    }
}
