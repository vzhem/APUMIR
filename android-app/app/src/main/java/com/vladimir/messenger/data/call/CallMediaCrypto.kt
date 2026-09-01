package com.vladimir.messenger.data.call

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Криптография голосовых кадров звонка (CALLS_BOOTSTRAP.md, 8.2): AES-128-GCM,
 * ключ на направление приезжает в offer/accept внутри E2E-текста.
 *
 * nonce = 8 байт big-endian seq + 4 нулевых байта: seq уникален и растёт, поэтому
 * пара (ключ, nonce) никогда не повторяется; tag = 128 бит дописан в конец.
 * AAD («APUCALL1|seq») привязывает кадр к проводу и номеру — подсаженный чужой
 * кадр или кадр с другим номером не расшифруется.
 *
 * Чистый JVM (javax.crypto), без Android — unit-тестируется в обычной JVM.
 */
class CallMediaCrypto(key16: ByteArray) {

    init {
        require(key16.size == 16) { "Call media key must be 16 bytes" }
    }

    private val key = SecretKeySpec(key16, "AES")

    fun encrypt(seq: Long, plain: ByteArray): ByteArray {
        require(seq >= 0) { "Negative seq" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(seq)))
        cipher.updateAAD(aad(seq))
        return cipher.doFinal(plain)
    }

    /** @return расшифрованный PCM или null (подмена/потеря — кадр молча отбрасывается). */
    fun decrypt(seq: Long, ciphertext: ByteArray): ByteArray? {
        if (seq < 0 || ciphertext.size < TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(seq)))
            cipher.updateAAD(aad(seq))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    private fun nonce(seq: Long): ByteArray =
        ByteBuffer.allocate(12).putLong(seq).putInt(0).array()

    private fun aad(seq: Long): ByteArray =
        ("APUCALL1|" + seq).toByteArray(Charsets.US_ASCII)

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val TAG_BYTES = 16
    }
}
