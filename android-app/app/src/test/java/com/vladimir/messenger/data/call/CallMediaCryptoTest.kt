package com.vladimir.messenger.data.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMediaCryptoTest {

    private val key = ByteArray(16) { (it * 7 + 1).toByte() }
    private val pcm = ByteArray(640) { (it % 253).toByte() }

    @Test
    fun roundTripRestoresPlaintext() {
        val crypto = CallMediaCrypto(key)
        val sealed = crypto.encrypt(5L, pcm)
        assertTrue(sealed.size > pcm.size) // tag 16 байт дописан
        assertArrayEquals(pcm, crypto.decrypt(5L, sealed))
    }

    @Test
    fun wrongKeyCannotOpen() {
        val sealed = CallMediaCrypto(key).encrypt(1L, pcm)
        val other = CallMediaCrypto(ByteArray(16) { (it * 3 + 2).toByte() })
        assertNull(other.decrypt(1L, sealed))
    }

    @Test
    fun wrongSeqCannotOpen() {
        // nonce привязан к seq (и AAD тоже): кадр, подсаженный под чужим номером, не открывается.
        val crypto = CallMediaCrypto(key)
        crypto.encrypt(1L, pcm).also { sealed ->
            assertNull(crypto.decrypt(2L, sealed))
        }
    }

    @Test
    fun tamperedByteCannotOpen() {
        val crypto = CallMediaCrypto(key)
        val sealed = crypto.encrypt(7L, pcm)
        sealed[sealed.size / 2] = (sealed[sealed.size / 2].toInt() xor 0x01).toByte()
        assertNull(crypto.decrypt(7L, sealed))
    }

    @Test
    fun truncatedFrameRejectedWithoutThrowing() {
        val crypto = CallMediaCrypto(key)
        assertNull(crypto.decrypt(3L, ByteArray(8)))
        assertNull(crypto.decrypt(-1L, ByteArray(64)))
    }
}
