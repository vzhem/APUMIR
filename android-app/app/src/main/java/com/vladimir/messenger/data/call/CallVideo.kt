package com.vladimir.messenger.data.call

/**
 * Видеокадры звонка: JPEG по тому же сокету, что голос.
 * Старые сборки пакет `media` не знают и отбрасывают — у них остаётся аудио.
 *
 * seq на проводе — u32. Голос считает от 0; видео ставит старший бит,
 * чтобы nonce AES-GCM не совпал с голосовым кадром.
 */
object CallVideo {
    const val SEQ_BASE = 0x80000000L
    const val MAX_JPEG_BYTES = 3_500
    const val WIDTH = 240
    const val HEIGHT = 320

    fun wireSeq(index: Long): Long = SEQ_BASE + (index and 0x7fff_ffffL)

    fun isVideoSeq(seq: Long): Boolean = seq >= SEQ_BASE

    /** [rotationDegrees one byte][jpeg]. */
    fun pack(rotationDegrees: Int, jpeg: ByteArray): ByteArray {
        val out = ByteArray(1 + jpeg.size)
        out[0] = ((rotationDegrees % 360 + 360) % 360 / 90).toByte()
        System.arraycopy(jpeg, 0, out, 1, jpeg.size)
        return out
    }

    fun unpack(payload: ByteArray): Pair<Int, ByteArray>? {
        if (payload.size < 2) return null
        val rotation = (payload[0].toInt() and 0x03) * 90
        return rotation to payload.copyOfRange(1, payload.size)
    }
}
