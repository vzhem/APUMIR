package com.vladimir.messenger.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryVideoWireTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val media = StoryVideoMedia("a".repeat(64), 1024, 60_000, 720, 1280, true)
    private val story = StoryStore.Story(id, "alice", "Alice", "caption", 0, 1000, false, video = media)
    private fun matches(author: String = "alice", name: String = StoryVideoWire.fileName(id),
        sha: String = media.sha256, bytes: Long = 1024, now: Long = 1000) =
        StoryVideoWire.matches(story, author, name, sha, bytes, now)

    @Test fun stableFileIdentity() {
        assertEquals(id, StoryVideoWire.storyId(StoryVideoWire.fileName(id)))
        assertTrue(matches())
    }
    @Test fun rejectPathsAndUnrelatedNames() {
        listOf("../$id.mp4", "$id.mp4", "apu-story-video-../$id.mp4", "apu-story-video-$id.mp4.exe").forEach {
            assertNull(StoryVideoWire.storyId(it))
        }
    }
    @Test fun senderMustBeStoryAuthor() { assertFalse(matches(author = "mallory")) }
    @Test fun bytesAndHashMustMatch() {
        assertFalse(matches(bytes = 1025))
        assertFalse(matches(sha = "b".repeat(64)))
    }
    @Test fun exactExpiryAndFutureAreRejected() {
        assertTrue(matches(now = 1000 + StoryStore.LIFETIME_MS - 1))
        assertFalse(matches(now = 1000 + StoryStore.LIFETIME_MS))
        assertFalse(matches(now = 999))
    }
    @Test fun photoOnlyCannotAuthorizeAVideo() {
        assertFalse(StoryVideoWire.matches(story.copy(video = null), "alice", StoryVideoWire.fileName(id), media.sha256, 1024, 1000))
    }
    @Test fun captionCannotEraseVideoDescriptor() {
        val merged = StoryStore.mergeIncoming(story, story.copy(video = null, text = "caption packet"))
        assertEquals(media, merged.video)
    }
    @Test fun differentStoryCannotClaimSameHash() {
        assertFalse(matches(name = StoryVideoWire.fileName("00000000-0000-0000-0000-000000000000")))
    }
}
