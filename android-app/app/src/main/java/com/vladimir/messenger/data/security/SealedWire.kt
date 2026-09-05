package com.vladimir.messenger.data.security

import java.util.Base64

// java.util.Base64, а не android.util.Base64: доступен с API 26 (наш minSdk)
// и, в отличие от android-версии, работает в JVM-тестах без эмулятора.

/**
 * Текстовая обёртка запечатанного сообщения.
 *
 * Транспорт ядра принимает строку, а конверт двоичный, поэтому три части
 * кодируются base64 и разделяются вертикальной чертой:
 *
 * `APUSEAL1|<конверт ключа>|<вектор>|<шифротекст>`
 *
 * Префикс нужен приёмной стороне, чтобы отличить конверт от обычного текста
 * и от других протоколов APU без попыток расшифровки.
 */
object SealedWire {
    const val PREFIX = "APUSEAL1|"

    /** Потолок строки: 64 КБ полезной нагрузки в base64 плюс заголовки. */
    private const val MAX_WIRE_CHARS = 192 * 1024
    private const val IV_BYTES = 12

    data class Parts(
        val keyEnvelope: ByteArray,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    ) {
        // ByteArray сравнивается по ссылке — переопределяем, иначе тесты и
        // любые сравнения молча врут.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Parts) return false
            return keyEnvelope.contentEquals(other.keyEnvelope) &&
                iv.contentEquals(other.iv) &&
                ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int {
            var result = keyEnvelope.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            result = 31 * result + ciphertext.contentHashCode()
            return result
        }
    }

    fun encode(keyEnvelope: ByteArray, iv: ByteArray, ciphertext: ByteArray): String {
        val encoder = Base64.getEncoder()
        return PREFIX + encoder.encodeToString(keyEnvelope) +
            "|" + encoder.encodeToString(iv) +
            "|" + encoder.encodeToString(ciphertext)
    }

    fun isSealed(text: String): Boolean = text.startsWith(PREFIX)

    /** Разобрать конверт или вернуть null, если это не наш формат. */
    fun decode(text: String): Parts? {
        if (!isSealed(text) || text.length > MAX_WIRE_CHARS) return null
        val body = text.substring(PREFIX.length)
        val fields = body.split('|')
        if (fields.size != 3) return null
        return runCatching {
            val decoder = Base64.getDecoder()
            val keyEnvelope = decoder.decode(fields[0])
            val iv = decoder.decode(fields[1])
            val ciphertext = decoder.decode(fields[2])
            if (keyEnvelope.isEmpty() || ciphertext.isEmpty() || iv.size != IV_BYTES) {
                return null
            }
            Parts(keyEnvelope, iv, ciphertext)
        }.getOrNull()
    }
}
