package com.vladimir.messenger.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Потолки чтения файлов (аудит 2026-10-26, п. 4.5).
 *
 * Проверяем именно поведение на границе: файл ровно в лимит читается, на байт
 * больше — нет; разросшийся поток обрывается на лету, а не после того, как
 * окажется в памяти; сумма файла совпадает с эталоном.
 */
class BoundedFilesTest {

    @Test
    fun fileExactlyAtTheLimitIsReadAndOneByteMoreIsRefused() {
        val file = File.createTempFile("bounded", ".bin")
        try {
            file.writeBytes(ByteArray(1024) { (it % 251).toByte() })

            assertEquals(1024, file.readBytesAtMost(1024).size)

            val error = assertThrows(FileTooLargeException::class.java) {
                file.readBytesAtMost(1023)
            }
            assertEquals(1023L, error.limitBytes)
            assertEquals(1024L, error.actualBytes)
        } finally {
            file.delete()
        }
    }

    @Test
    fun streamThatGrowsPastTheLimitIsCutBeforeItFitsInMemory() {
        // Поток «бесконечный»: если лимит не работает, тест не зависнет, а
        // упадёт по исчерпанию памяти — поэтому объём стрима ограничен 1 МБ.
        val stream = object : java.io.InputStream() {
            private var produced = 0
            override fun read(): Int {
                if (produced >= 1024 * 1024) return -1
                produced++
                return 7
            }
        }
        val error = assertThrows(FileTooLargeException::class.java) {
            stream.readBytesAtMost(4096)
        }
        // Прочитано чуть больше лимита, но точно не весь поток.
        assertTrue(error.actualBytes <= 4096L + 8192L)
    }

    @Test
    fun fileDigestMatchesTheDigestOfItsBytes() {
        val file = File.createTempFile("digest", ".bin")
        try {
            val bytes = ByteArray(300_000) { (it * 31).toByte() }
            file.writeBytes(bytes)

            val expected = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

            assertEquals(expected, file.sha256Hex())
            assertEquals(64, file.sha256Hex().length)
        } finally {
            file.delete()
        }
    }

    @Test
    fun copyStopsAtTheLimitAndReportsIt() {
        val source = ByteArrayInputStream(ByteArray(10_000) { 1 })
        val out = ByteArrayOutputStream()

        assertTrue(copyWithLimit(source, out, 10_000))
        assertEquals(10_000, out.size())

        val tooLong = ByteArrayInputStream(ByteArray(10_001) { 1 })
        val cut = ByteArrayOutputStream()
        assertFalse(copyWithLimit(tooLong, cut, 10_000))
        assertTrue("записано не больше лимита", cut.size() <= 10_000)
    }

    @Test
    fun copyKeepsBytesIdentical() {
        val payload = ByteArray(70_000) { (it % 253).toByte() }
        val out = ByteArrayOutputStream()

        assertTrue(copyWithLimit(ByteArrayInputStream(payload), out, 1_000_000))

        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun readingAMissingFileStillFailsLoudly() {
        val missing = File(Files.createTempDirectory("bounded").toFile(), "absent.bin")
        val error = assertThrows(IOException::class.java) { missing.readBytesAtMost(16) }
        assertFalse(error is FileTooLargeException)
    }
}
