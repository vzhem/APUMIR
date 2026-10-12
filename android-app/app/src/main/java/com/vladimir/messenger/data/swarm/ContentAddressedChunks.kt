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
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
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
        fun receive(
            ownerId: String, key: String, manifest: Manifest,
            index: Int, totalChunks: Int, chunkId: String, bytes: ByteArray, nowMs: Long,
        ): ByteArray? = null
        fun discard(ownerId: String, key: String) {}
        fun pendingCount(nowMs: Long): Int = 0
        fun pendingBytes(nowMs: Long): Long = 0
    }
}
