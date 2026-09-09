package com.vladimir.messenger.data.swarm

// =============================================================================
// POSTMANIFEST.KT — подписанный манифест поста канала (рой, этап 1)
// =============================================================================
// Пост = торрент. Манифест перечисляет текст поста и все куски его фото с
// хэшами и подписан ключом автора (Ed25519 личности). Получатель, у которого
// есть проверенный манифест, принимает текст и куски от КОГО УГОДНО, сверяя
// хэш; значит, досылать посты может любой участник, а не только владелец.
//
// Формат подписи (docs/CHANNEL_SWARM_DESIGN.md §4):
//   sig = Ed25519(seed, "apu-post-v1" ‖ groupId ‖ topicId ‖ messageId ‖ authorId
//                       ‖ sentAtMs ‖ sha256(text) ‖ части)
// Поля склеиваются через '\n' - ни в одном из них перевода строки нет (id и
// хэши), поэтому склейка однозначна. Формат не меняется, когда подпись
// переедет в ядро (этап 3).
//
// Ed25519 здесь - чистая Java-библиотека net.i2p.crypto:eddsa (RFC 8032): из
// того же 32-байтового семени она даёт тот же открытый ключ и те же подписи,
// что ed25519-dalek в ядре. Проверяется тестом на векторе RFC 8032.
// =============================================================================

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Один кусок фото в манифесте: id сообщения-куска и первые 16 байт sha256 его текста. */
data class ManifestPart(val messageId: String, val sha16: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is ManifestPart && other.messageId == messageId && other.sha16.contentEquals(sha16)

    override fun hashCode(): Int = messageId.hashCode() * 31 + sha16.contentHashCode()
}

/**
 * Манифест поста. [signature] пустая у ещё не подписанного; [signerPublicKey] -
 * открытый ключ, которым манифест подписан (32 байта Ed25519), ездит вместе с
 * манифестом, чтобы получатель мог проверить подпись, даже если привязку
 * автора он ещё не видел (кто ВПРАВЕ подписывать - решает получатель по
 * карточке канала, см. [PostManifests]).
 */
data class PostManifest(
    val groupId: String,
    val topicId: String,
    val messageId: String,
    val authorId: String,
    val sentAtMs: Long,
    /** sha256 текста поста (32 байта). */
    val textSha: ByteArray,
    val parts: List<ManifestPart>,
    /**
     * Номер правки: 0 у исходного поста, +1 на каждую правку текста. Получатель
     * заменяет манифест только более новым - старый, присланный повторно,
     * не откатит текст назад.
     */
    val revision: Int = 0,
    val signerPublicKey: ByteArray = ByteArray(0),
    val signature: ByteArray = ByteArray(0),
) {
    val isSigned: Boolean get() = signature.size == Ed25519.SIGNATURE_BYTES && signerPublicKey.size == Ed25519.PUBLIC_KEY_BYTES

    /** Байты, которые подписываются. */
    fun signedBytes(): ByteArray {
        val sb = StringBuilder()
        sb.append(DOMAIN).append('\n')
        sb.append(groupId).append('\n')
        sb.append(topicId).append('\n')
        sb.append(messageId).append('\n')
        sb.append(authorId).append('\n')
        sb.append(sentAtMs).append('\n')
        sb.append(revision).append('\n')
        sb.append(hex(textSha)).append('\n')
        sb.append(partsWire())
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /** Части одной строкой: `id:sha16b64,…` (без разделителей конверта). */
    fun partsWire(): String = parts.joinToString(",") { it.messageId + ":" + b64(it.sha16) }

    /** Подписать семенем личности. */
    fun signed(seed: ByteArray): PostManifest {
        val keyPair = Ed25519.keyPairFromSeed(seed)
        return copy(
            signerPublicKey = keyPair.publicKey,
            signature = Ed25519.sign(keyPair.privateKey, signedBytes()),
        )
    }

    /** Подпись сходится с [signerPublicKey]. Это ещё не значит, что ключ - автора: см. [PostManifests]. */
    fun verifySignature(): Boolean =
        isSigned && Ed25519.verify(signerPublicKey, signedBytes(), signature)

    /** Ожидаемый sha16 куска с таким id или null, если кусок не из этого поста. */
    fun expectedSha16(partMessageId: String): ByteArray? =
        parts.firstOrNull { it.messageId == partMessageId }?.sha16

    /** Сходится ли текст куска с манифестом. */
    fun matchesPart(partMessageId: String, partText: String): Boolean {
        val expected = expectedSha16(partMessageId) ?: return false
        return sha256(partText).copyOf(SHA16_BYTES).contentEquals(expected)
    }

    /** Сходится ли текст поста с манифестом. */
    fun matchesText(text: String): Boolean = sha256(text).contentEquals(textSha)

    override fun equals(other: Any?): Boolean =
        other is PostManifest &&
            other.groupId == groupId && other.topicId == topicId && other.messageId == messageId &&
            other.authorId == authorId && other.sentAtMs == sentAtMs && other.revision == revision &&
            other.textSha.contentEquals(textSha) && other.parts == parts &&
            other.signerPublicKey.contentEquals(signerPublicKey) && other.signature.contentEquals(signature)

    override fun hashCode(): Int = messageId.hashCode()

    companion object {
        const val DOMAIN = "apu-post-v1"
        const val SHA16_BYTES = 16
        /** Больше кусков в одном посте не бывает: 6 фото × 3 куска. */
        const val MAX_PARTS = 24

        /** Собрать неподписанный манифест из текста поста и текстов кусков (по порядку). */
        fun build(
            groupId: String,
            topicId: String,
            messageId: String,
            authorId: String,
            sentAtMs: Long,
            text: String,
            parts: List<Pair<String, String>>,
            revision: Int = 0,
        ): PostManifest = PostManifest(
            groupId = groupId,
            topicId = topicId,
            messageId = messageId,
            authorId = authorId,
            sentAtMs = sentAtMs,
            textSha = sha256(text),
            parts = parts.take(MAX_PARTS).map { (id, partText) ->
                ManifestPart(id, sha256(partText).copyOf(SHA16_BYTES))
            },
            revision = revision,
        )

        /** Разобрать строку частей из конверта; null - строка битая. */
        fun parseParts(wire: String): List<ManifestPart>? {
            if (wire.isBlank()) return emptyList()
            val cells = wire.split(',')
            if (cells.size > MAX_PARTS) return null
            val out = ArrayList<ManifestPart>(cells.size)
            for (cell in cells) {
                val colon = cell.indexOf(':')
                if (colon <= 0 || colon == cell.length - 1) return null
                val id = cell.substring(0, colon)
                val sha = unb64(cell.substring(colon + 1)) ?: return null
                if (sha.size != SHA16_BYTES) return null
                out.add(ManifestPart(id, sha))
            }
            return out
        }

        fun sha256(text: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        fun unhex(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            return try {
                ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            } catch (_: NumberFormatException) {
                null
            }
        }

        fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun unb64(text: String): ByteArray? = try {
            Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Тонкая обёртка над библиотекой eddsa: семя → пара ключей, подпись, проверка. */
object Ed25519 {
    const val SEED_BYTES = 32
    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64

    private val spec = EdDSANamedCurveTable.ED_25519_CURVE_SPEC

    class KeyPair(val privateKey: EdDSAPrivateKey, val publicKey: ByteArray)

    fun keyPairFromSeed(seed: ByteArray): KeyPair {
        require(seed.size == SEED_BYTES) { "seed must be 32 bytes" }
        val privateSpec = EdDSAPrivateKeySpec(seed, spec)
        val privateKey = EdDSAPrivateKey(privateSpec)
        return KeyPair(privateKey, privateKey.abyte)
    }

    fun sign(privateKey: EdDSAPrivateKey, message: ByteArray): ByteArray {
        val engine = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))
        engine.initSign(privateKey)
        return engine.signOneShot(message)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_BYTES || signature.size != SIGNATURE_BYTES) return false
        return try {
            val engine = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))
            engine.initVerify(EdDSAPublicKey(EdDSAPublicKeySpec(publicKey, spec)))
            engine.verifyOneShot(message, signature)
        } catch (_: Exception) {
            false
        }
    }
}
