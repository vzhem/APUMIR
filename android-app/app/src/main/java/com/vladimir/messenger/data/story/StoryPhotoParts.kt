package com.vladimir.messenger.data.story

import com.vladimir.messenger.data.swarm.ContentAddressedChunks
import java.nio.charset.StandardCharsets

internal class StoryPhotoParts {
    companion object {
        const val CHUNK = 2400
        const val CHUNK_BYTES = CHUNK
        const val MAX_SIZE = 160_000
        const val MAX_PHOTO_BYTES = MAX_SIZE * 3 / 4
        const val MAX_PARTS = 67
        const val MAX_PENDING_SENDERS = 16
        const val MAX_PENDING_PER_SENDER = 2
        /**
         * Сколько держим недособранную историю.
         *
         * Части приходят волнами: relay отдаёт накопленное, когда телефон
         * получателя проснётся, и между волнами легко проходит больше пяти
         * минут. Прежнее окно в 300 000 мс обнуляло сборку, а дошедшие позже
         * части начинали её заново - собрать фото уже не получалось, и у друга
         * оно не появлялось никогда. Окно равно сроку жизни самой истории:
         * дольше хранить нечего, а память и так ограничена числом сборок и их
         * размером.
         */
        const val PENDING_TTL_MS = 24L * 60L * 60L * 1_000L + 300_000L
        const val MAX_CONTENT_CHARS = 256 * 1024
        const val MAX_EXPIRY_HORIZON_MS = 24L * 60L * 60L * 1_000L + 300_000L
        private const val CONTENT_PENDING_ENTRIES = MAX_PENDING_SENDERS
        private const val CONTENT_PENDING_BYTES = MAX_SIZE.toLong() * CONTENT_PENDING_ENTRIES
        private const val LEGACY_PENDING_ENTRIES = MAX_PENDING_SENDERS
        private const val LEGACY_PENDING_CHARS = MAX_SIZE.toLong() * LEGACY_PENDING_ENTRIES
    }

    private val assembler = ContentAddressedChunks.Assembler(
        maxContentBytes = MAX_SIZE,
        maxPendingEntries = CONTENT_PENDING_ENTRIES,
        maxPendingBytes = CONTENT_PENDING_BYTES,
        pendingTtlMs = PENDING_TTL_MS,
        maxExpiryHorizonMs = MAX_EXPIRY_HORIZON_MS,
        maxPendingEntriesPerOwner = MAX_PENDING_PER_SENDER,
        maxPendingBytesPerOwner = MAX_SIZE.toLong() * MAX_PENDING_PER_SENDER,
        maxPendingOwners = MAX_PENDING_SENDERS,
    )

    private data class LegacyKey(val author: String, val storyId: String)
    private data class LegacyPending(val total: Int, val startedAtMs: Long,
        val chunks: MutableMap<Int, String> = HashMap(), var chars: Int = 0)
    private val legacy = LinkedHashMap<LegacyKey, LegacyPending>()
    private var legacyChars = 0L

    @Synchronized
    fun receiveContentAddressed(
        author: String, storyId: String, index: Int, total: Int,
        contentId: String, contentSizeBytes: Int, chunkSizeBytes: Int,
        expiresAtMs: Long, chunkId: String, data: String, now: Long,
    ): String? {
        evictLegacy(now)
        if (author.length !in 1..256 || storyId.length !in 1..100) return null
        val key = assemblyKey(author, storyId)
        if (chunkSizeBytes != CHUNK || total !in 1..MAX_PARTS || index !in 0 until total ||
            contentSizeBytes !in 1..MAX_SIZE) {
            assembler.discard(author, key); return null
        }
        if (data.length !in 1..CHUNK || !isCanonicalBase64(data)) {
            assembler.discard(author, key); return null
        }
        val manifest = ContentAddressedChunks.Manifest(
            contentId = contentId, sizeBytes = contentSizeBytes,
            chunkSizeBytes = chunkSizeBytes, expiresAtMs = expiresAtMs)
        val complete = assembler.receive(
            ownerId = author, key = key, manifest = manifest,
            index = index, totalChunks = total, chunkId = chunkId,
            bytes = data.toByteArray(StandardCharsets.US_ASCII), nowMs = now) ?: return null
        if (complete.size !in 1..MAX_SIZE) { discard(author, storyId); return null }
        val photoB64 = String(complete, StandardCharsets.US_ASCII)
        if (!isCanonicalBase64(photoB64)) { discard(author, storyId); return null }
        val imageBytes = ContentAddressedChunks.decodeCanonicalBase64(photoB64)
            ?: run { discard(author, storyId); return null }
        if (imageBytes.isEmpty() || imageBytes.size > MAX_PHOTO_BYTES) {
            discard(author, storyId); return null
        }
        return photoB64
    }

    @Synchronized
    fun receiveLegacy(author: String, id: String, index: Int, total: Int, data: String, now: Long): String? {
        evictLegacy(now)
        if (author.length !in 1..256 || id.length !in 1..100 || total !in 1..MAX_PARTS ||
            index !in 0 until total || data.isEmpty() || data.length > CHUNK || !isBase64Alphabet(data)) {
            discardLegacy(author, id); return null
        }
        val key = LegacyKey(author, id)
        val existing = legacy[key]
        if (existing != null && existing.total != total) { discardLegacy(author, id); return null }
        if (existing == null) {
            val senderEntries = legacy.keys.count { it.author == author }
            val senderCount = legacy.keys.map { it.author }.toSet().size
            if (senderEntries >= MAX_PENDING_PER_SENDER ||
                (senderEntries == 0 && senderCount >= MAX_PENDING_SENDERS) ||
                legacy.size >= LEGACY_PENDING_ENTRIES) return null
            legacy[key] = LegacyPending(total, now)
        }
        val item = legacy.getValue(key)
        val previous = item.chunks[index]
        if (previous != null) {
            if (previous != data) discardLegacy(author, id)
            return null
        }
        if (item.chars > MAX_SIZE - data.length || legacyChars > LEGACY_PENDING_CHARS - data.length) {
            discardLegacy(author, id); return null
        }
        item.chunks[index] = data
        item.chars += data.length
        legacyChars += data.length
        if (item.chunks.size != total) return null
        val photoB64 = (0 until total).joinToString("") { item.chunks.getValue(it) }
        discardLegacy(author, id)
        if (photoB64.length !in 1..MAX_SIZE || !isCanonicalBase64(photoB64)) return null
        val bytes = ContentAddressedChunks.decodeCanonicalBase64(photoB64) ?: return null
        return photoB64.takeIf { bytes.isNotEmpty() && bytes.size <= MAX_PHOTO_BYTES }
    }

    @Deprecated("Use receiveContentAddressed")
    fun receive(author: String, id: String, index: Int, total: Int, data: String, now: Long): String? =
        receiveLegacy(author, id, index, total, data, now)

    @Synchronized
    fun discardAuthor(author: String) {
        if (author.length !in 1..256) return
        legacy.keys.filter { it.author == author }.forEach { discardLegacy(it.author, it.storyId) }
        assembler.pendingKeys(author, System.currentTimeMillis()).forEach { assembler.discard(author, it) }
    }

    @Synchronized
    fun sweep(now: Long) { evictLegacy(now); assembler.pendingCount(now) }

    @Synchronized
    fun discard(author: String, storyId: String) {
        if (author.length !in 1..256 || storyId.length !in 1..100) return
        assembler.discard(author, assemblyKey(author, storyId))
        discardLegacy(author, storyId)
    }

    private fun assemblyKey(author: String, storyId: String) = "$author.length:$author:$storyId"

    private fun isBase64Alphabet(v: String) = v.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }
    private fun isCanonicalBase64(v: String): Boolean {
        val b = ContentAddressedChunks.decodeCanonicalBase64(v) ?: return false
        return ContentAddressedChunks.encodeBase64(b) == v
    }

    private fun evictLegacy(now: Long) {
        val it = legacy.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val s = e.value.startedAtMs
            if (now < s || now - s > PENDING_TTL_MS) {
                legacyChars = (legacyChars - e.value.chars).coerceAtLeast(0L)
                it.remove()
            }
        }
    }

    private fun discardLegacy(author: String, id: String) {
        val r = legacy.remove(LegacyKey(author, id)) ?: return
        legacyChars = (legacyChars - r.chars).coerceAtLeast(0L)
    }
}
