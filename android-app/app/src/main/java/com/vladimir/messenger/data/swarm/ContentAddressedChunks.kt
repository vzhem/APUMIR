package com.vladimir.messenger.data.swarm

import java.security.MessageDigest
import java.util.Base64

object ContentAddressedChunks {
    const val SHA256_HEX_LENGTH = 64
    const val MAX_CHUNK_BYTES = 1024 * 1024
    const val MAX_CHUNKS = 4096

    private val sha256Pattern = Regex("^[0-9a-f]{$SHA256_HEX_LENGTH}$")

    data class Manifest(val contentId: String, val sizeBytes: Int, val chunkSizeBytes: Int, val expiresAtMs: Long) {
        val totalChunks: Int get() {
            if (sizeBytes <= 0 || chunkSizeBytes <= 0) return 0
            val c = (sizeBytes.toLong() + chunkSizeBytes - 1) / chunkSizeBytes
            return c.takeIf { it in 1..MAX_CHUNKS.toLong() }?.toInt() ?: 0
        }
    }
    data class Chunk(val index: Int, val id: String, val bytes: ByteArray)

    fun manifest(content: ByteArray, chunkSizeBytes: Int, expiresAtMs: Long): Manifest {
        require(content.isNotEmpty() && chunkSizeBytes in 1..MAX_CHUNK_BYTES && expiresAtMs > 0)
        require(chunkSizeBytes * MAX_CHUNKS >= content.size)
        return Manifest(sha256Hex(content), content.size, chunkSizeBytes, expiresAtMs)
    }
    fun split(content: ByteArray, manifest: Manifest): List<Chunk> {
        require(manifest.sizeBytes == content.size && sha256Hex(content) == manifest.contentId)
        return (0 until manifest.totalChunks).map { i ->
            val s = i.toLong() * manifest.chunkSizeBytes
            val e = minOf(content.size.toLong(), s + manifest.chunkSizeBytes).toInt()
            Chunk(i, sha256Hex(content.copyOfRange(s.toInt(), e)), content.copyOfRange(s.toInt(), e))
        }
    }
    fun decodeCanonicalBase64(text: String): ByteArray? = try {
        val d = Base64.getDecoder().decode(text)
        d.takeIf { Base64.getEncoder().encodeToString(d) == text }
    } catch (_: IllegalArgumentException) { null }
    fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder()
        for (b in digest) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        return sb.toString()
    }
    fun isSha256Hex(v: String): Boolean = sha256Pattern.matches(v)


    class Assembler(
        private val maxContentBytes: Int,
        private val maxPendingEntries: Int,
        private val maxPendingBytes: Long,
        private val pendingTtlMs: Long,
        private val maxExpiryHorizonMs: Long,
        private val maxPendingEntriesPerOwner: Int = maxPendingEntries,
        private val maxPendingBytesPerOwner: Long = maxPendingBytes,
        private val maxPendingOwners: Int = maxPendingEntries,
    ) {
        private data class StoredChunk(val id: String, val bytes: ByteArray)
        private data class Pending(val ownerId: String, val manifest: Manifest, val startedAtMs: Long,
            val chunks: MutableMap<Int, StoredChunk> = HashMap())
        private val pending = LinkedHashMap<String, Pending>()
        private val ownerBytes = HashMap<String, Long>()
        private var pendingBytes = 0L
        init {
            require(maxContentBytes > 0)
            require(maxPendingEntries > 0)
            require(maxPendingBytes > 0L)
            require(pendingTtlMs > 0L)
            require(maxExpiryHorizonMs > 0L)
            require(maxPendingEntriesPerOwner > 0)
            require(maxPendingBytesPerOwner > 0L)
            require(maxPendingOwners > 0)
        }
        fun receive(ownerId: String, key: String, manifest: Manifest, index: Int, totalChunks: Int,
                    chunkId: String, bytes: ByteArray, nowMs: Long): ByteArray? {
            evictExpired(nowMs)
            if (ownerId.length !in 1..256 || key.length !in 1..512) return null
            if (!isValidManifest(manifest, maxContentBytes) || !expiryIsAllowed(manifest.expiresAtMs, nowMs) ||
                totalChunks != manifest.totalChunks || index !in 0 until manifest.totalChunks ||
                !isSha256Hex(chunkId)) {
                discardLocked(key, ownerId); return null
            }
            val expectedSize = expectedChunkSize(manifest, index)
            if (expectedSize == null || bytes.size != expectedSize || sha256Hex(bytes) != chunkId) {
                discardLocked(key, ownerId); return null
            }
            var item = pending[key]
            if (item != null && item.ownerId != ownerId) return null
            if (item != null && item.manifest != manifest) {
                discardLocked(key, ownerId); return null
            }
            if (item == null) {
                val charge = manifest.sizeBytes.toLong()
                val bytesForOwner = ownerBytes[ownerId] ?: 0L
                val entriesForOwner = pending.values.count { it.ownerId == ownerId }
                val hasOwnerCapacity = ownerBytes.containsKey(ownerId) || ownerBytes.size < maxPendingOwners
                if (!hasOwnerCapacity || charge > maxPendingBytes || charge > maxPendingBytesPerOwner ||
                    pending.size >= maxPendingEntries || pendingBytes > maxPendingBytes - charge ||
                    entriesForOwner >= maxPendingEntriesPerOwner ||
                    bytesForOwner > maxPendingBytesPerOwner - charge) return null
                item = Pending(ownerId, manifest, nowMs)
                pending[key] = item
                pendingBytes += charge
                ownerBytes[ownerId] = bytesForOwner + charge
            }
            val previous = item.chunks[index]
            if (previous != null) {
                if (previous.id != chunkId || !previous.bytes.contentEquals(bytes)) discardLocked(key, ownerId)
                return null
            }
            item.chunks[index] = StoredChunk(chunkId, bytes.copyOf())
            if (item.chunks.size != manifest.totalChunks) return null
            val complete = ByteArray(manifest.sizeBytes)
            var offset = 0
            for (chunkIndex in 0 until manifest.totalChunks) {
                val part = item.chunks[chunkIndex] ?: run { discardLocked(key, ownerId); return null }
                System.arraycopy(part.bytes, 0, complete, offset, part.bytes.size)
                offset += part.bytes.size
            }
            discardLocked(key, ownerId)
            return complete.takeIf { offset == manifest.sizeBytes && sha256Hex(it) == manifest.contentId }
        }
        fun discard(ownerId: String, key: String) {
            if (ownerId.length in 1..256 && key.length in 1..512) discardLocked(key, ownerId)
        }
        fun pendingCount(nowMs: Long): Int { evictExpired(nowMs); return pending.size }
        fun pendingBytes(nowMs: Long): Long { evictExpired(nowMs); return pendingBytes }
        fun pendingCount(ownerId: String, nowMs: Long): Int { evictExpired(nowMs); return pending.values.count { it.ownerId == ownerId } }
        fun pendingKeys(ownerId: String, nowMs: Long): List<String> { evictExpired(nowMs); return pending.entries.filter { it.value.ownerId == ownerId }.map { it.key } }
        private fun expiryIsAllowed(expiresAtMs: Long, nowMs: Long): Boolean {
            if (nowMs < 0L || expiresAtMs <= nowMs) return false
            return expiresAtMs - nowMs <= maxExpiryHorizonMs
        }
        private fun expectedChunkSize(manifest: Manifest, index: Int): Int? {
            if (index !in 0 until manifest.totalChunks) return null
            val start = index.toLong() * manifest.chunkSizeBytes
            val remaining = manifest.sizeBytes.toLong() - start
            if (remaining <= 0L) return null
            return minOf(manifest.chunkSizeBytes.toLong(), remaining).toInt()
        }
        private fun evictExpired(nowMs: Long) {
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val started = e.value.startedAtMs
                if (nowMs >= started && nowMs - started > pendingTtlMs) {
                    removeLocked(it, e.key, e.value)
                }
            }
        }
        private fun discardLocked(key: String, ownerId: String) {
            val item = pending[key] ?: return
            if (item.ownerId != ownerId) return
            pending.remove(key)
            subtractCharge(item)
        }
        private fun removeLocked(it: MutableIterator<MutableMap.MutableEntry<String, Pending>>, key: String, item: Pending) {
            it.remove(); subtractCharge(item)
        }
        private fun subtractCharge(item: Pending) {
            val charge = item.manifest.sizeBytes.toLong()
            pendingBytes = (pendingBytes - charge).coerceAtLeast(0L)
            val remaining = (ownerBytes[item.ownerId] ?: charge) - charge
            if (remaining <= 0L) ownerBytes.remove(item.ownerId) else ownerBytes[item.ownerId] = remaining
        }
    }
}
