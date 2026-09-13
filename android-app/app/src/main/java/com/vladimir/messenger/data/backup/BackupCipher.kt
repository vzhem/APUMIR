package com.vladimir.messenger.data.backup

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование файла резервной копии паролем: поток любой длины, порциями.
 *
 * Зачем порциями: одна AES-GCM-запись на весь файл заставила бы держать его в
 * памяти целиком при расшифровке (Android отдаёт открытый текст GCM только после
 * проверки метки), а копия с полученными файлами может весить гигабайты.
 * Поэтому файл режется на порции по [CHUNK_PLAIN_BYTES], каждая закрыта своей
 * меткой, а порядок и конец потока защищены номером порции и флагом «последняя»
 * (конструкция STREAM): порцию нельзя выкинуть, переставить или обрезать файл
 * так, чтобы это осталось незамеченным.
 *
 * Формат файла:
 * ```
 * заголовок: "APUBAK"(6) | версия u8 = 1 | итерации PBKDF2 u32 | соль(16) | префикс nonce(8)
 * порции:    флаг u8 (0 = будут ещё, 1 = последняя) | длина u32 | AES-256-GCM(порция)
 * ```
 * nonce порции = префикс(8) || номер порции u32 (с нуля); AAD = заголовок || флаг.
 * Последняя порция может быть пустой (одна метка). Ключ - PBKDF2-HMAC-SHA256 от
 * пароля с солью SHA-256(соль || домен). Число итераций записано в заголовке,
 * чтобы его можно было поднимать, не меняя формат.
 *
 * Чистая JVM: проверяется unit-тестами, Android-классов тут нет.
 */
object BackupCipher {
    const val MAGIC = "APUBAK"
    const val VERSION = 1
    const val DEFAULT_ITERATIONS = 210_000
    const val MIN_PASSWORD_LENGTH = 8

    /** Порция открытого текста; порции шифртекста длиннее на метку. */
    const val CHUNK_PLAIN_BYTES = 1 shl 20

    private const val SALT_BYTES = 16
    private const val PREFIX_BYTES = 8
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val HEADER_BYTES = 6 + 1 + 4 + SALT_BYTES + PREFIX_BYTES
    private const val MAX_ITERATIONS = 5_000_000
    private const val MAX_COUNTER = 0xFFFF_FFFFL
    private const val DOMAIN = "apu-profile-backup-v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Пароль не подошёл или файл испорчен - различать намеренно нельзя. */
    class WrongPasswordException : IOException("backup password does not match or the file is damaged")

    /** Файл не наш ([newer] = false) или записан более новой версией формата ([newer] = true). */
    class UnsupportedFormatException(message: String, val newer: Boolean = false) : IOException(message)

    private val random = SecureRandom()

    /**
     * Обернуть поток вывода шифрованием. Заголовок пишется сразу; закрытие
     * возвращённого потока дописывает последнюю порцию и закрывает [out].
     */
    fun encrypt(
        out: OutputStream,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        chunkBytes: Int = CHUNK_PLAIN_BYTES,
    ): OutputStream {
        require(password.size >= MIN_PASSWORD_LENGTH) { "backup password too short" }
        require(iterations in 1..MAX_ITERATIONS) { "bad kdf iterations" }
        require(chunkBytes in 1..CHUNK_PLAIN_BYTES) { "bad chunk size" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(PREFIX_BYTES).also(random::nextBytes)
        val header = header(iterations, salt, prefix)
        val key = deriveKey(password, salt, iterations)
        out.write(header)
        return EncryptingStream(out, key, header, prefix, chunkBytes)
    }

    /**
     * Обернуть поток ввода расшифровкой. Заголовок читается и проверяется сразу
     * (формат), пароль - на первой порции: [WrongPasswordException] прилетит из
     * первого `read`. Обрезанный файл даёт [EOFException] в конце.
     */
    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val header = ByteArray(HEADER_BYTES)
        val complete = try {
            readFully(input, header)
        } catch (_: EOFException) {
            false
        }
        // Короткий файл - не «обрезанная копия», а просто не наш файл.
        if (!complete) throw UnsupportedFormatException("file is too short to be an APU backup")
        val buf = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.length).also { buf.get(it) }
        if (String(magic, Charsets.US_ASCII) != MAGIC) throw UnsupportedFormatException("not an APU backup file")
        val version = buf.get().toInt() and 0xFF
        if (version != VERSION) throw UnsupportedFormatException("backup format $version is newer than this app", newer = true)
        val iterations = buf.int
        if (iterations !in 1..MAX_ITERATIONS) throw UnsupportedFormatException("bad kdf parameters")
        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val prefix = ByteArray(PREFIX_BYTES).also { buf.get(it) }
        val key = deriveKey(password, salt, iterations)
        return DecryptingStream(input, key, header, prefix)
    }

    private fun header(iterations: Int, salt: ByteArray, prefix: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC.toByteArray(Charsets.US_ASCII))
            .put(VERSION.toByte())
            .putInt(iterations)
            .put(salt)
            .put(prefix)
            .array()

    private fun nonce(prefix: ByteArray, counter: Long): ByteArray =
        ByteBuffer.allocate(NONCE_BYTES).put(prefix).putInt(counter.toInt()).array()

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val fullSalt = MessageDigest.getInstance("SHA-256")
            .digest(salt + DOMAIN.toByteArray(Charsets.UTF_8))
        val spec = PBEKeySpec(password, fullSalt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** true = буфер заполнен целиком; false = поток кончился, не дав ни байта. Частичный хвост = обрыв. */
    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) {
                if (total == 0) return false
                throw EOFException("backup file is truncated")
            }
            total += n
        }
        return true
    }

    private class EncryptingStream(
        private val out: OutputStream,
        private val key: ByteArray,
        private val header: ByteArray,
        private val prefix: ByteArray,
        chunkBytes: Int,
    ) : OutputStream() {
        private val buffer = ByteArray(chunkBytes)
        private var filled = 0
        private var counter = 0L
        private var closed = false

        override fun write(b: Int) {
            if (closed) throw IOException("stream closed")
            if (filled == buffer.size) writeChunk(final = false)
            buffer[filled++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("stream closed")
            var offset = off
            var remaining = len
            while (remaining > 0) {
                if (filled == buffer.size) writeChunk(final = false)
                val n = minOf(remaining, buffer.size - filled)
                System.arraycopy(b, offset, buffer, filled, n)
                filled += n
                offset += n
                remaining -= n
            }
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            if (closed) return
            try {
                writeChunk(final = true)
                out.flush()
            } finally {
                closed = true
                key.fill(0)
                out.close()
            }
        }

        private fun writeChunk(final: Boolean) {
            if (counter > MAX_COUNTER) throw IOException("backup is too large")
            val flag: Byte = if (final) 1 else 0
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce(prefix, counter)),
            )
            cipher.updateAAD(header)
            cipher.updateAAD(byteArrayOf(flag))
            val ciphertext = cipher.doFinal(buffer, 0, filled)
            out.write(ByteBuffer.allocate(5).put(flag).putInt(ciphertext.size).array())
            out.write(ciphertext)
            filled = 0
            counter++
        }
    }

    private class DecryptingStream(
        private val input: InputStream,
        private val key: ByteArray,
        private val header: ByteArray,
        private val prefix: ByteArray,
    ) : InputStream() {
        private var plain = ByteArray(0)
        private var pos = 0
        private var counter = 0L
        private var finished = false
        private var firstChunk = true

        override fun read(): Int {
            if (!ensure()) return -1
            return plain[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!ensure()) return -1
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() {
            key.fill(0)
            input.close()
        }

        /** true = в буфере есть байты; false = поток честно кончился (после последней порции). */
        private fun ensure(): Boolean {
            while (pos >= plain.size) {
                if (finished) return false
                readChunk()
            }
            return true
        }

        private fun readChunk() {
            val head = ByteArray(5)
            if (!readFully(input, head)) throw EOFException("backup file is truncated: final chunk missing")
            val flag = head[0].toInt()
            if (flag != 0 && flag != 1) throw IOException("backup chunk header is damaged")
            val length = ByteBuffer.wrap(head, 1, 4).int
            if (length < TAG_BYTES || length > CHUNK_PLAIN_BYTES + TAG_BYTES) {
                throw IOException("backup chunk length is damaged")
            }
            val ciphertext = ByteArray(length)
            if (!readFully(input, ciphertext)) throw EOFException("backup file is truncated")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce(prefix, counter)),
            )
            cipher.updateAAD(header)
            cipher.updateAAD(byteArrayOf(flag.toByte()))
            plain = try {
                cipher.doFinal(ciphertext)
            } catch (e: GeneralSecurityException) {
                // Первая порция не открылась = почти наверняка пароль; дальше - порча файла.
                if (firstChunk) throw WrongPasswordException()
                throw IOException("backup file is damaged", e)
            }
            pos = 0
            counter++
            firstChunk = false
            if (flag == 1) {
                finished = true
                if (input.read() != -1) throw IOException("unexpected data after the end of backup")
            }
        }
    }
}
