package com.vladimir.messenger.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * У друга история живёт в StoryStore: пакет без фото не должен затирать снимок,
 * который уже собрали. Иначе автор видит свою копию с фото, а друг — одну подпись.
 */
class StoryStoreMergeTest {
    private fun story(
        id: String = "story-1",
        authorId: String = "alice",
        authorName: String = "Alice",
        text: String = "caption",
        bg: Int = 1,
        createdAtMs: Long = 1_000L,
        mine: Boolean = false,
        photoB64: String? = null,
    ) = StoryStore.Story(id, authorId, authorName, text, bg, createdAtMs, mine, photoB64)

    @Test
    fun captionPacketDoesNotEraseAnAlreadyAssembledPhoto() {
        val withPhoto = story(photoB64 = "PHOTO", text = "first")
        val captionOnly = story(photoB64 = null, text = "later", bg = 2, authorName = "A.")
        val merged = StoryStore.mergeIncoming(withPhoto, captionOnly)
        assertEquals("PHOTO", merged.photoB64)
        assertEquals("later", merged.text)
        assertEquals(2, merged.bg)
        assertEquals("A.", merged.authorName)
        assertEquals(1_000L, merged.createdAtMs)
        assertEquals("alice", merged.authorId)
    }

    @Test
    fun photoPacketUpgradesACaptionOnlyStory() {
        val captionOnly = story(photoB64 = null)
        val withPhoto = story(photoB64 = "PHOTO")
        assertEquals("PHOTO", StoryStore.mergeIncoming(captionOnly, withPhoto).photoB64)
    }

    @Test
    fun missingExistingKeepsTheIncomingStory() {
        val incoming = story(photoB64 = "PHOTO")
        assertSame(incoming, StoryStore.mergeIncoming(null, incoming))
    }

    @Test
    fun differentIdsAreNotMerged() {
        val existing = story(id = "a", photoB64 = "PHOTO")
        val incoming = story(id = "b", photoB64 = null, text = "other")
        val merged = StoryStore.mergeIncoming(existing, incoming)
        assertSame(incoming, merged)
        assertNull(merged.photoB64)
    }

    @Test
    fun ownStoryStaysMineWhenARemoteCopyArrives() {
        val mine = story(mine = true, photoB64 = "PHOTO")
        val echo = story(mine = false, photoB64 = null, authorName = "")
        val merged = StoryStore.mergeIncoming(mine, echo)
        assertTrue(merged.mine)
        assertEquals("PHOTO", merged.photoB64)
        assertEquals("Alice", merged.authorName)
    }
}
