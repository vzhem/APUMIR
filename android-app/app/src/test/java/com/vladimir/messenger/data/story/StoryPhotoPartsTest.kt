package com.vladimir.messenger.data.story

import org.junit.Assert.*
import org.junit.Test

class StoryPhotoPartsTest {
    @Test fun reorderedAndRepeatedPartsAreAssembled() {
        val parts = StoryPhotoParts()
        assertNull(parts.receive("alice", "s", 1, 2, "BBBB", 0))
        assertNull(parts.receive("alice", "s", 1, 2, "BBBB", 1))
        assertEquals("AAAABBBB", parts.receive("alice", "s", 0, 2, "AAAA", 2))
    }
    @Test fun sendersNeverShareParts() {
        val parts = StoryPhotoParts()
        assertNull(parts.receive("alice", "s", 0, 2, "AAAA", 0))
        assertNull(parts.receive("bob", "s", 1, 2, "BBBB", 0))
        assertEquals("AAAACCCC", parts.receive("alice", "s", 1, 2, "CCCC", 1))
    }
    @Test fun invalidAndExpiredPartsCannotComplete() {
        val parts = StoryPhotoParts()
        assertNull(parts.receive("a", "s", 0, 100000, "A", 0))
        assertNull(parts.receive("a", "s", -1, 2, "A", 0))
        assertNull(parts.receive("a", "s", 0, 2, "A".repeat(2401), 0))
        assertNull(parts.receive("a", "s", 0, 2, "bad!", 0))
        assertNull(parts.receive("a", "s", 0, 2, "AAAA", 0))
        assertNull(parts.receive("a", "s", 1, 2, "BBBB", 300001))
    }
    @Test fun fullPhotoRoundTrip() {
        val photo = "ABCD".repeat(40000)
        val chunks = photo.chunked(StoryPhotoParts.CHUNK)
        val parts = StoryPhotoParts()
        var result: String? = null
        chunks.indices.reversed().forEach { i ->
            result = parts.receive("a", "s", i, chunks.size, chunks[i], 0)
        }
        assertEquals(photo, result)
    }
    @Test fun conflictingDuplicateInvalidatesAssembly() {
        val parts = StoryPhotoParts()
        assertNull(parts.receive("a", "s", 0, 2, "AAAA", 0))
        assertNull(parts.receive("a", "s", 0, 2, "CCCC", 1))
        assertNull(parts.receive("a", "s", 1, 2, "BBBB", 2))
    }
}
