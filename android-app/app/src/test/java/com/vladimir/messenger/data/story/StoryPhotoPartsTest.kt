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
    fun photoPartsDeliveredInWavesHoursApartStillAssemble() {
        val image = ByteArray(9_001) { (it * 17).toByte() }
        val packets = contentParts(image)
        assertTrue(packets.size >= 2)
        val parts = StoryPhotoParts()
        // Первая волна: relay отдаёт часть пакетов сразу после публикации.
        packets.dropLast(1).forEach { assertNull(receive(parts, it)) }
        // Остаток доходит, когда телефон получателя проснулся, - через часы.
        // Прежнее пятиминутное окно сборки к этому времени уже обнуляло части, и
        // фото у друга не появлялось никогда.
        assertEquals(
            ContentAddressedChunks.encodeBase64(image),
            receive(parts, packets.last(), receiveAt = now + 6 * 60 * 60 * 1_000L),
        )
    }

    @Test
    fun partialPhotoNeverAssemblesIntoATruncatedPhoto() {
        // 3 * CHUNK_BYTES байт кодируются ровно в четыре части по CHUNK символов.
        val packets = contentParts(ByteArray(StoryPhotoParts.CHUNK_BYTES * 3) { (it * 5).toByte() })
        assertEquals(4, packets.size)
        val parts = StoryPhotoParts()
        // Без последней части получатель не должен получить «обрезанную» подпись
        // истории: сборка отдаёт фото только целиком.
        packets.dropLast(1).forEach { assertNull(receive(parts, it)) }
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
        assertEquals(3, chunks.size)
        assertNull(parts.receiveLegacy(author, "old-story", 1, chunks.size, chunks[1], now))
        assertNull(parts.receiveLegacy(author, "old-story", 0, chunks.size, chunks[0], now + 1))
        assertEquals(photo, parts.receiveLegacy(author, "old-story", 2, chunks.size, chunks[2], now + 2))

        assertNull(parts.receiveLegacy(author, "bad", 0, 2, "@@@@", now))
        assertNull(parts.receiveLegacy(author, "bad", 0, 2, "A".repeat(StoryPhotoParts.CHUNK + 1), now))
        assertNull(parts.receiveLegacy(author, "bad", 0, 2, "AAAA", now))
        assertNull(parts.receiveLegacy(author, "bad", 1, 2, "BBBB", now + StoryPhotoParts.PENDING_TTL_MS + 1))
    }

    @Test fun reorderedAndRepeatedLegacyPartsAreAssembled() {
        val parts = StoryPhotoParts()
        assertNull(parts.receive("alice", "s", 1, 2, "BBBB", 0))
        assertNull(parts.receive("alice", "s", 1, 2, "BBBB", 1))
        assertEquals("AAAABBBB", parts.receive("alice", "s", 0, 2, "AAAA", 2))
    }

    @Test fun legacySendersNeverShareParts() {
        val parts = StoryPhotoParts()
        assertNull(parts.receive("alice", "s", 0, 2, "AAAA", 0))
        assertNull(parts.receive("bob", "s", 1, 2, "BBBB", 0))
        assertEquals("AAAACCCC", parts.receive("alice", "s", 1, 2, "CCCC", 1))
    }

    @Test fun legacyInvalidAndExpiredPartsCannotComplete() {
        val parts = StoryPhotoParts()
        assertNull(parts.receive("a", "s", 0, 100000, "A", 0))
        assertNull(parts.receive("a", "s", -1, 2, "A", 0))
        assertNull(parts.receive("a", "s", 0, 2, "A".repeat(2401), 0))
        assertNull(parts.receive("a", "s", 0, 2, "bad!", 0))
        assertNull(parts.receive("a", "s", 0, 2, "AAAA", 0))
        // Части, разнесённые дальше срока хранения недособранной истории, не склеиваются.
        assertNull(parts.receive("a", "s", 1, 2, "BBBB", StoryPhotoParts.PENDING_TTL_MS + 1))
    }

    @Test fun legacyFullPhotoRoundTrip() {
        val photo = "ABCD".repeat(40000)
        val chunks = photo.chunked(StoryPhotoParts.CHUNK)
        val parts = StoryPhotoParts()
        var result: String? = null
        chunks.indices.reversed().forEach { i ->
            result = parts.receive("a", "s", i, chunks.size, chunks[i], 0)
        }
        assertEquals(photo, result)
    }

    @Test fun legacyConflictingDuplicateInvalidatesAssembly() {
        val parts = StoryPhotoParts()
        assertNull(parts.receive("a", "s", 0, 2, "AAAA", 0))
        assertNull(parts.receive("a", "s", 0, 2, "CCCC", 1))
        assertNull(parts.receive("a", "s", 1, 2, "BBBB", 2))
    }
}
