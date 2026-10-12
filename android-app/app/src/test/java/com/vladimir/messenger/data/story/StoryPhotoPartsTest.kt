package com.vladimir.messenger.data.story

import com.vladimir.messenger.data.swarm.ContentAddressedChunks
import org.junit.Assert.*
import org.junit.Test

class StoryPhotoPartsTest {
    private data class Packet(
        val storyId: String,
        val contentId: String,
        val sizeBytes: Int,
        val chunkSizeBytes: Int,
        val expiresAtMs: Long,
        val index: Int,
        val total: Int,
        val chunkId: String,
        val data: String,
    )

    private val author = "alice"
    private val now = 10_000L

    private fun contentParts(content: ByteArray, storyId: String = "story-v2", time: Long = now): List<Packet> {
        val photoB64 = ContentAddressedChunks.encodeBase64(content)
        val encodedBytes = photoB64.toByteArray(Charsets.US_ASCII)
        val expiry = time + StoryPhotoParts.MAX_EXPIRY_HORIZON_MS
        val manifest = ContentAddressedChunks.manifest(encodedBytes, StoryPhotoParts.CHUNK_BYTES, expiry)
        return ContentAddressedChunks.split(encodedBytes, manifest).map { chunk ->
            Packet(
                storyId = storyId,
                contentId = manifest.contentId,
                sizeBytes = manifest.sizeBytes,
                chunkSizeBytes = manifest.chunkSizeBytes,
                expiresAtMs = manifest.expiresAtMs,
                index = chunk.index,
                total = manifest.totalChunks,
                chunkId = chunk.id,
                data = String(chunk.bytes, Charsets.US_ASCII),
            )
        }
    }

    private fun receive(
        parts: StoryPhotoParts,
        packet: Packet,
        sender: String = author,
        receiveAt: Long = now,
    ): String? = parts.receiveContentAddressed(
        author = sender,
        storyId = packet.storyId,
        index = packet.index,
        total = packet.total,
        contentId = packet.contentId,
        contentSizeBytes = packet.sizeBytes,
        chunkSizeBytes = packet.chunkSizeBytes,
        expiresAtMs = packet.expiresAtMs,
        chunkId = packet.chunkId,
        data = packet.data,
        now = receiveAt,
    )

    @Test
    fun outOfOrderPiecesAssembleTheCanonicalPhoto() {
        val image = ByteArray(6_001) { (it * 13).toByte() }
        val packets = contentParts(image)
        val parts = StoryPhotoParts()
        var assembled: String? = null
        packets.indices.reversed().forEach { index -> assembled = receive(parts, packets[index]) ?: assembled }
        assertEquals(ContentAddressedChunks.encodeBase64(image), assembled)
    }

    @Test
    fun totalChunkAndWholeContentHashesRejectTampering() {
        val packets = contentParts(ByteArray(5_000) { it.toByte() })
        val parts = StoryPhotoParts()
        assertNull(receive(parts, packets[0]))
        assertNull(receive(parts, packets[0]))
        assertNull(receive(parts, packets[1].copy(data = "QQ==")))
        assertNull(receive(parts, packets[1]))
        assertNull(receive(parts, packets.last()))
    }

    @Test
    fun dimensionsExpiryAndIndexAreBoundToTheManifest() {
        val packet = contentParts("content-addressed story photo".toByteArray()).single()
        val parts = StoryPhotoParts()
        assertNull(receive(parts, packet.copy(total = 2)))
        assertNull(receive(parts, packet.copy(contentId = "0" + packet.contentId)))
        assertNull(receive(parts, packet.copy(expiresAtMs = now)))
        assertNull(receive(parts, packet.copy(chunkSizeBytes = packet.chunkSizeBytes - 1)))
        assertNull(receive(parts, packet.copy(chunkId = "0" + packet.chunkId)))
        assertNull(receive(parts, packet.copy(sizeBytes = 999_999)))
        assertNull(receive(parts, packet.copy(index = -1)))
    }

    @Test
    fun partialAssembliesExpireAndDoNotCrossSenderBoundaries() {
        val packets = contentParts(ByteArray(StoryPhotoParts.CHUNK_BYTES * 2) { it.toByte() })
        val parts = StoryPhotoParts()
        assertNull(receive(parts, packets[0]))
        assertNull(receive(parts, packets[1].copy(storyId = "other-story")))
        assertNull(receive(parts, packets[1], sender = "bob"))
        assertNull(receive(parts, packets[1], receiveAt = now + StoryPhotoParts.PENDING_TTL_MS + 1))
    }

    @Test
    fun legacyPacketsAreReadWithStrictSizeAndBase64Checks() {
        val photo = ContentAddressedChunks.encodeBase64(ByteArray(4_001) { (it * 3).toByte() })
        val chunks = photo.chunked(StoryPhotoParts.CHUNK)
        val parts = StoryPhotoParts()
        assertNull(parts.receiveLegacy(author, "old-story", 1, chunks.size, chunks[1], now))
        assertEquals(photo, parts.receiveLegacy(author, "old-story", 0, chunks.size, chunks[0], now + 1))

        assertNull(parts.receiveLegacy(author, "bad", 0, 2, "@@@@", now))
        assertNull(parts.receiveLegacy(author, "bad", 0, 2, "A".repeat(StoryPhotoParts.CHUNK + 1), now))
        assertNull(parts.receiveLegacy(author, "bad", 0, 2, "AAAA", now))
        assertNull(parts.receiveLegacy(author, "bad", 1, 2, "BBBB", now + StoryPhotoParts.PENDING_TTL_MS + 1))
    }
}
