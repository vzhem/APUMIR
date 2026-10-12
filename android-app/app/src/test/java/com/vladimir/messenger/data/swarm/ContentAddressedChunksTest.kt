package com.vladimir.messenger.data.swarm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentAddressedChunksTest {
    private fun assembler(
        maxEntries: Int = 8,
        maxPendingBytes: Long = 1024,
        ttlMs: Long = 100,
        expiryHorizonMs: Long = 1_000,
        maxEntriesPerOwner: Int = maxEntries,
        maxPendingBytesPerOwner: Long = maxPendingBytes,
        maxOwners: Int = maxEntries,
    ) = ContentAddressedChunks.Assembler(
        maxContentBytes = 512,
        maxPendingEntries = maxEntries,
        maxPendingBytes = maxPendingBytes,
        pendingTtlMs = ttlMs,
        maxExpiryHorizonMs = expiryHorizonMs,
        maxPendingEntriesPerOwner = maxEntriesPerOwner,
        maxPendingBytesPerOwner = maxPendingBytesPerOwner,
        maxPendingOwners = maxOwners,
    )

    @Test
    fun outOfOrderChunksReassembleOnlyToTheCommittedContent() {
        val content = ByteArray(10) { (it * 17).toByte() }
        val manifest = ContentAddressedChunks.manifest(content, chunkSizeBytes = 4, expiresAtMs = 900)
        val chunks = ContentAddressedChunks.split(content, manifest)
        val receiver = assembler()

        assertNull(receiver.receive("alice", "story", manifest, 2, 3, chunks[2].id, chunks[2].bytes, 100))
        assertNull(receiver.receive("alice", "story", manifest, 2, 3, chunks[2].id, chunks[2].bytes, 101))
        assertNull(receiver.receive("alice", "story", manifest, 0, 3, chunks[0].id, chunks[0].bytes, 102))
        assertArrayEquals(content, receiver.receive("alice", "story", manifest, 1, 3, chunks[1].id, chunks[1].bytes, 103))
        assertEquals(0, receiver.pendingCount(104))
        assertEquals(0L, receiver.pendingBytes(104))
    }

    @Test
    fun manifestAndChunkHashesRejectTamperingAndDiscardPartialState() {
        val content = "abcdefgh".toByteArray()
        val manifest = ContentAddressedChunks.manifest(content, 4, 900)
        val chunks = ContentAddressedChunks.split(content, manifest)
        val receiver = assembler()
        assertNull(receiver.receive("alice", "story", manifest, 0, 2, chunks[0].id, chunks[0].bytes, 100))
        assertEquals(1, receiver.pendingCount(101))

        val altered = "WXYZ".toByteArray()
        assertNull(receiver.receive(
            "alice", "story", manifest, 1, 2, chunks[1].id, altered, 102,
        ))
        assertEquals(0, receiver.pendingCount(103))
        assertEquals(0L, receiver.pendingBytes(103))

        val wrongManifest = manifest.copy(contentId = "0".repeat(64))
        assertNull(receiver.receive("alice", "other", wrongManifest, 0, 2, chunks[0].id, chunks[0].bytes, 104))
        assertNull(receiver.receive("alice", "other", wrongManifest, 1, 2, chunks[1].id, chunks[1].bytes, 105))
        assertEquals(0, receiver.pendingCount(106))
    }

    @Test
    fun totalLengthExpiryAndChunkDimensionsAreBoundToManifest() {
        val content = "abcdef".toByteArray()
        val manifest = ContentAddressedChunks.manifest(content, 3, 500)
        val chunks = ContentAddressedChunks.split(content, manifest)
        val receiver = assembler(expiryHorizonMs = 200)

        assertNull(receiver.receive("alice", "wrong-total", manifest, 0, 3, chunks[0].id, chunks[0].bytes, 100))
        assertNull(receiver.receive("alice", "expired", manifest.copy(expiresAtMs = 100), 0, 2, chunks[0].id, chunks[0].bytes, 100))
        assertNull(receiver.receive("alice", "too-far", manifest.copy(expiresAtMs = 301), 0, 2, chunks[0].id, chunks[0].bytes, 100))
        assertNull(receiver.receive("alice", "short", manifest, 0, 2, chunks[0].id, byteArrayOf(1), 100))
        assertEquals(0, receiver.pendingCount(101))
    }

    @Test
    fun perOwnerAndGlobalBudgetsAreChargedBeforeBuffering() {
        val content = "abcdefgh".toByteArray()
        val manifest = ContentAddressedChunks.manifest(content, 4, 900)
        val chunks = ContentAddressedChunks.split(content, manifest)
        val receiver = assembler(
            maxEntries = 2,
            maxPendingBytes = 16,
            maxEntriesPerOwner = 1,
            maxPendingBytesPerOwner = 8,
            maxOwners = 1,
        )

        assertNull(receiver.receive("alice", "one", manifest, 0, 2, chunks[0].id, chunks[0].bytes, 100))
        assertNull(receiver.receive("alice", "two", manifest, 0, 2, chunks[0].id, chunks[0].bytes, 101))
        assertNull(receiver.receive("bob", "three", manifest, 0, 2, chunks[0].id, chunks[0].bytes, 102))
        assertNull(receiver.receive("bob", "one", manifest, 0, 2, chunks[0].id, chunks[0].bytes, 103))
        assertEquals(1, receiver.pendingCount(104))
        assertEquals(8L, receiver.pendingBytes(104))
        assertEquals(1, receiver.pendingCount("alice", 104))
        assertEquals(0, receiver.pendingCount("bob", 104))

        assertArrayEquals(content, receiver.receive("alice", "one", manifest, 1, 2, chunks[1].id, chunks[1].bytes, 105))
        assertEquals(0, receiver.pendingCount(106))
        assertEquals(0L, receiver.pendingBytes(106))
    }

    @Test
    fun expiredPartialEntriesReleaseTheirBudget() {
        val content = "abcdefgh".toByteArray()
        val manifest = ContentAddressedChunks.manifest(content, 4, 900)
        val chunks = ContentAddressedChunks.split(content, manifest)
        val receiver = assembler(ttlMs = 10)

        assertNull(receiver.receive("alice", "one", manifest, 0, 2, chunks[0].id, chunks[0].bytes, 100))
        assertEquals(1, receiver.pendingCount(110))
        assertEquals(0, receiver.pendingCount(111))
        assertEquals(0L, receiver.pendingBytes(111))
    }

    @Test
    fun conflictingManifestForSameKeyDiscardsOnlyThatOwnersPartialEntry() {
        val content = "abcdefgh".toByteArray()
        val manifest = ContentAddressedChunks.manifest(content, 4, 900)
        val chunks = ContentAddressedChunks.split(content, manifest)
        val receiver = assembler()
        assertNull(receiver.receive("alice", "same", manifest, 0, 2, chunks[0].id, chunks[0].bytes, 100))
        assertNull(receiver.receive("alice", "same", manifest.copy(expiresAtMs = 901), 1, 2, chunks[1].id, chunks[1].bytes, 101))
        assertEquals(0, receiver.pendingCount(102))
        assertEquals(0L, receiver.pendingBytes(102))
    }

    @Test
    fun splitAndBase64ParsingAreStrictAndContentAddressed() {
        val content = byteArrayOf(0, 1, 2, -1)
        val manifest = ContentAddressedChunks.manifest(content, 2, 100)
        assertEquals(ContentAddressedChunks.sha256Hex(content), manifest.contentId)
        assertEquals(2, manifest.totalChunks)
        assertTrue(ContentAddressedChunks.isSha256Hex(manifest.contentId))
        assertFalse(ContentAddressedChunks.isSha256Hex(manifest.contentId.uppercase()))
        assertArrayEquals(content, ContentAddressedChunks.decodeCanonicalBase64(ContentAddressedChunks.encodeBase64(content)))
        assertNull(ContentAddressedChunks.decodeCanonicalBase64("AA==\n"))
        assertNull(ContentAddressedChunks.decodeCanonicalBase64("A"))
        try {
            ContentAddressedChunks.split(content.reversedArray(), manifest)
            throw AssertionError("split must reject content not matching the id")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun maximumChunkSizeCapacityCheckDoesNotOverflow() {
        val content = byteArrayOf(1)
        val manifest = ContentAddressedChunks.manifest(
            content,
            chunkSizeBytes = ContentAddressedChunks.MAX_CHUNK_BYTES,
            expiresAtMs = 900,
        )

        assertEquals(1, manifest.totalChunks)
        assertArrayEquals(content, ContentAddressedChunks.split(content, manifest).single().bytes)
    }

    @Test
    fun assemblerRejectsMalformedAndOversizedManifestsBeforeBuffering() {
        val content = byteArrayOf(7)
        val manifest = ContentAddressedChunks.manifest(content, chunkSizeBytes = 1, expiresAtMs = 900)
        val invalid = listOf(
            manifest.copy(contentId = "g".repeat(ContentAddressedChunks.SHA256_HEX_LENGTH)),
            manifest.copy(sizeBytes = 513),
            manifest.copy(chunkSizeBytes = ContentAddressedChunks.MAX_CHUNK_BYTES + 1),
            manifest.copy(sizeBytes = 0),
        )
        val receiver = assembler()

        invalid.forEachIndexed { index, badManifest ->
            assertNull(
                receiver.receive(
                    "alice", "invalid-$index", badManifest, 0, 1,
                    ContentAddressedChunks.sha256Hex(content), content, 100,
                ),
            )
        }
        assertEquals(0, receiver.pendingCount(101))
        assertEquals(0L, receiver.pendingBytes(101))
    }
}
