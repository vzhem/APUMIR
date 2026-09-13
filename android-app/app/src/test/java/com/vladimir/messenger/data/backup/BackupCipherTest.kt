package com.vladimir.messenger.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Шифр файла резервной копии. Итерации PBKDF2 в тестах маленькие: важна не
 * стойкость, а формат, порядок порций и честные ошибки.
 */
class BackupCipherTest {

    private val password = "correct horse battery".toCharArray()

    private fun seal(plain: ByteArray, chunk: Int = 1024, iterations: Int = 10): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCipher.encrypt(out, password, iterations = iterations, chunkBytes = chunk).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, pw: CharArray = password): ByteArray =
        BackupCipher.decrypt(ByteArrayInputStream(sealed), pw).use { it.readBytes() }

    @Test
    fun roundTripAcrossChunkBoundaries() {
        // Пустой, меньше порции, ровно порция, несколько порций с хвостом.
        for (size in listOf(0, 1, 1023, 1024, 1025, 5000)) {
            val plain = ByteArray(size) { (it * 31 + 7).toByte() }
            assertArrayEquals("size $size", plain, open(seal(plain)))
        }
    }

    @Test
    fun headerIsRecognisable() {
        val sealed = seal(ByteArray(10))
        assertEquals("APUBAK", String(sealed.copyOfRange(0, 6), Charsets.US_ASCII))
        assertEquals(BackupCipher.VERSION, sealed[6].toInt())
    }

    @Test
    fun wrongPasswordIsReportedOnFirstRead() {
        val sealed = seal("secret data".toByteArray())
        assertThrows(BackupCipher.WrongPasswordException::class.java) {
            open(sealed, "wrong password!!".toCharArray())
        }
    }

    @Test
    fun truncatedFileIsDetected() {
        val sealed = seal(ByteArray(3000) { it.toByte() })
        // Отрезаем последнюю (финальную) порцию целиком: поток без флага «последняя».
        val cut = sealed.copyOf(sealed.size - (3000 - 2048) - 16 - 5)
        assertThrows(EOFException::class.java) { open(cut) }
        // Обрыв посреди порции.
        assertThrows(EOFException::class.java) { open(sealed.copyOf(sealed.size - 3)) }
    }

    @Test
    fun droppedMiddleChunkIsDetected() {
        val plain = ByteArray(3 * 1024) { it.toByte() }
        val sealed = seal(plain)
        val header = 6 + 1 + 4 + 16 + 8
        val chunkLen = 5 + 1024 + 16
        // Выкидываем вторую порцию: третья придёт под счётчиком 1 и не расшифруется.
        val tampered = sealed.copyOfRange(0, header + chunkLen) +
            sealed.copyOfRange(header + 2 * chunkLen, sealed.size)
        assertThrows(IOException::class.java) { open(tampered) }
    }

    @Test
    fun flippedByteIsDetected() {
        val sealed = seal(ByteArray(100) { 1 })
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { open(tampered) }
    }

    @Test
    fun trailingGarbageIsRejected() {
        val sealed = seal(ByteArray(10))
        assertThrows(IOException::class.java) { open(sealed + byteArrayOf(0)) }
    }

    @Test
    fun newerVersionIsRefusedAsNewer() {
        val sealed = seal(ByteArray(10)).also { it[6] = 2 }
        val error = assertThrows(BackupCipher.UnsupportedFormatException::class.java) { open(sealed) }
        assertTrue(error.newer)
    }

    @Test
    fun foreignFileIsRefusedAsNotOurs() {
        val error = assertThrows(BackupCipher.UnsupportedFormatException::class.java) {
            open("PK\u0003\u0004 this is a zip, not a backup, long enough header".toByteArray())
        }
        assertTrue(!error.newer)
    }

    @Test
    fun sameInputEncryptsDifferently() {
        val plain = "same".toByteArray()
        assertNotEquals(seal(plain).toList(), seal(plain).toList())
    }

    @Test
    fun shortPasswordIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCipher.encrypt(ByteArrayOutputStream(), "short".toCharArray(), iterations = 10)
        }
    }
}
