package com.vladimir.messenger.data.file

import java.io.InputStream
import java.security.MessageDigest

/** Streaming metadata/hash preflight. It never retains source bytes or a filesystem path. */
object FileTransferSourceInspector {
    const val MAX_FILE_BYTES = 10L * 1024 * 1024
    const val HASH_BUFFER_BYTES = 64 * 1024
    const val MAX_DISPLAY_NAME_BYTES = 255
    const val MAX_PROVIDER_NAME_CHARS = 4_096
    const val MAX_MEDIA_TYPE_BYTES = 127
    const val DEFAULT_MEDIA_TYPE = "application/octet-stream"

    data class InspectedFile(
        val displayName: String,
        val mediaType: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    fun inspect(
        providerDisplayName: String?,
        providerMediaType: String?,
        declaredSize: Long?,
        openStream: () -> InputStream,
    ): InspectedFile {
        require(declaredSize == null || declaredSize in 0..MAX_FILE_BYTES) {
            "Declared file size is outside the MVP limit"
        }
        val displayName = safeDisplayName(providerDisplayName)
        val mediaType = safeMediaType(providerMediaType)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        try {
            openStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total = Math.addExact(total, read.toLong())
                    require(total <= MAX_FILE_BYTES) { "Selected file exceeds the MVP limit" }
                    digest.update(buffer, 0, read)
                }
            }
        } finally {
            buffer.fill(0)
        }
        require(declaredSize == null || declaredSize == total) {
            "Provider file size changed during inspection"
        }
        return InspectedFile(
            displayName = displayName,
            mediaType = mediaType,
            sizeBytes = total,
            sha256 = digest.digest().toHex(),
        )
    }

    fun safeDisplayName(value: String?): String {
        val bounded = value.orEmpty().take(MAX_PROVIDER_NAME_CHARS)
        val replaced = buildString(bounded.length) {
            bounded.forEach { character ->
                append(
                    if (character.isISOControl() || character == '/' || character == '\\') '_'
                    else character
                )
            }
        }.trim()
        val candidate = replaced.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "file"
        return truncateUtf8(candidate, MAX_DISPLAY_NAME_BYTES)
            .trim()
            .takeUnless { it.isEmpty() || it == "." || it == ".." }
            ?: "file"
    }

    fun safeMediaType(value: String?): String {
        val candidate = value.orEmpty().trim().lowercase()
        return candidate.takeIf {
            it.isNotEmpty() &&
                it.length <= MAX_MEDIA_TYPE_BYTES &&
                '/' in it &&
                it.all { character -> character.code in 0x21..0x7e && character != '\\' }
        } ?: DEFAULT_MEDIA_TYPE
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val output = StringBuilder()
        var bytes = 0
        val iterator = value.codePoints().iterator()
        while (iterator.hasNext()) {
            val text = String(Character.toChars(iterator.nextInt()))
            val width = text.toByteArray(Charsets.UTF_8).size
            if (bytes + width > maxBytes) break
            output.append(text)
            bytes += width
        }
        return output.toString()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
