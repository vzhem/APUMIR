package com.vladimir.messenger.data.file

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferSourceInspectorTest {
    @Test
    fun streamingHashAndMetadataAreCanonical() {
        val bytes = "hello".toByteArray()
        val result = FileTransferSourceInspector.inspect(
            providerDisplayName = " report.pdf ",
            providerMediaType = "Application/PDF",
            declaredSize = bytes.size.toLong(),
        ) { bytes.inputStream() }

        assertEquals("report.pdf", result.displayName)
        assertEquals("application/pdf", result.mediaType)
        assertEquals(5L, result.sizeBytes)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", result.sha256)
    }

    @Test
    fun emptyFileIsSupported() {
        val result = FileTransferSourceInspector.inspect(null, null, 0) {
            byteArrayOf().inputStream()
        }
        assertEquals("file", result.displayName)
        assertEquals(FileTransferSourceInspector.DEFAULT_MEDIA_TYPE, result.mediaType)
        assertEquals(0L, result.sizeBytes)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", result.sha256)
    }

    @Test
    fun unsafeProviderNameIsSanitizedAndUtf8Bounded() {
        assertEquals(".._secret_name.txt", FileTransferSourceInspector.safeDisplayName("../secret\\name.txt"))
        assertEquals("file", FileTransferSourceInspector.safeDisplayName(".."))
        val unicode = FileTransferSourceInspector.safeDisplayName("😀".repeat(100))
        assertTrue(unicode.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(unicode.isNotEmpty())
        assertTrue(!Character.isHighSurrogate(unicode.last()))
    }

    @Test
    fun invalidMediaTypeFallsBackWithoutTrustingProvider() {
        for (invalid in listOf(null, "", "not-a-type", "text/plain with-space", "текст/plain")) {
            assertEquals(
                FileTransferSourceInspector.DEFAULT_MEDIA_TYPE,
                FileTransferSourceInspector.safeMediaType(invalid),
            )
        }
        assertEquals("image/png", FileTransferSourceInspector.safeMediaType(" IMAGE/PNG "))
    }

    @Test
    fun declaredSizeMismatchAndNegativeLengthAreRejected() {
        expectFailure {
            FileTransferSourceInspector.inspect("a", "text/plain", 4) {
                "hello".byteInputStream()
            }
        }
        expectFailure {
            FileTransferSourceInspector.inspect("negative", null, -1L) {
                byteArrayOf().inputStream()
            }
        }
    }

    @Test
    fun readsWithFixedMemoryBound() {
        val stream = RepeatingInputStream(FileTransferSourceInspector.HASH_BUFFER_BYTES.toLong() * 3 + 7)
        val result = FileTransferSourceInspector.inspect("bounded.bin", null, null) { stream }
        assertEquals(FileTransferSourceInspector.HASH_BUFFER_BYTES.toLong() * 3 + 7, result.sizeBytes)
        assertTrue(stream.maxRequested <= FileTransferSourceInspector.HASH_BUFFER_BYTES)
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected operation to fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private class RepeatingInputStream(private val total: Long) : InputStream() {
        private var emitted = 0L
        var maxRequested = 0
            private set

        override fun read(): Int = if (emitted++ < total) 0x5a else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (emitted >= total) return -1
            maxRequested = maxOf(maxRequested, length)
            val count = minOf(length.toLong(), total - emitted).toInt()
            buffer.fill(0x5a.toByte(), offset, offset + count)
            emitted += count
            return count
        }
    }
}
