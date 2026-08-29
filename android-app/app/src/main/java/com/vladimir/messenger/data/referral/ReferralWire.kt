package com.vladimir.messenger.data.referral

import java.util.Base64

/**
 * Проводной конверт реферальной атрибуции.
 *
 * Приглашённый отправляет пригласившему один пакет «я пришёл по твоей ссылке».
 * Конверт живёт поверх того же транспорта 1:1, что и APUGRP1/APULAN1, и
 * разбирается тем же приёмником (CoreServerService.handleEvent) ДО
 * авто-создания контакта, поэтому в историю чата обычным текстом не попадает.
 *
 * Версия 2 — подписанная. Внутри лежат два уже существующих криптографических
 * объекта rust-core, новых примитивов не потребовалось:
 * - подписанный токен приглашения (`create_referral_invite_token`): кто
 *   пригласил и когда ссылка была создана;
 * - подписанная привязка identity (`create_identity_signing_binding`): узел
 *   приглашённого и момент создания его identity.
 * Обе подписи проверяются на пригласившем теми же функциями ядра
 * (`verify_referral_invite_token`, `verify_identity_binding`), поэтому
 * приписать себе чужое приглашение или выдать старую identity за новую нельзя.
 *
 * Версия 1 (без подписи) распознаётся и поглощается, но больше не зачисляется:
 * иначе любой узел мог бы накрутить счётчик чужим идентификатором.
 *
 * Разбор строгий: любое отклонение даёт null, пакет молча отбрасывается.
 */
object ReferralWire {

    const val PREFIX = "APUREF1"
    const val KIND_ATTRIBUTION = "attr"
    const val VERSION_SIGNED = "2"
    const val VERSION_UNSIGNED = "1"

    /**
     * Конверт второй версии несёт токен (до 512 байт) и привязку identity
     * (142 байта для короткого узла), поэтому предел поднят с 256 до 2048.
     * Транспорт ограничивает конверт 16 КБ (GroupWire.MAX_ENVELOPE_BYTES).
     */
    const val MAX_ENVELOPE_CHARS = 2_048

    /** Параметр ссылки-приглашения, в котором передаётся подписанный токен. */
    const val LINK_TOKEN_PARAMETER = "r"

    private const val SIGNED_FIELD_COUNT = 8
    private const val UNSIGNED_FIELD_COUNT = 7
    private const val NONCE_BYTES = 16
    private val nodeIdPattern = Regex("^pk_[0-9a-f]{32}(?:[0-9a-f]{32})?$")
    private val base64UrlPattern = Regex("^[A-Za-z0-9_-]+$")

    sealed class Packet {
        /** Подписанная атрибуция: единственная, которая может поднять счётчик. */
        data class SignedAttribution(
            val inviteeNodeId: String,
            val inviterNodeId: String,
            val qualifiedAtMs: Long,
            /** base64url подписанного токена приглашения, выданного пригласившим. */
            val tokenB64: String,
            /** base64url подписанной привязки identity приглашённого. */
            val bindingB64: String,
        ) : Packet()

        /**
         * Прежняя атрибуция без подписи. Разбирается только для того, чтобы
         * приёмник поглотил её, а не положил в чат как текст.
         */
        data class UnsignedAttribution(
            val inviteeNodeId: String,
            val inviterNodeId: String,
            val createdAtMs: Long,
        ) : Packet()
    }

    fun buildSignedAttribution(
        inviteeNodeId: String,
        inviterNodeId: String,
        qualifiedAtMs: Long,
        token: ByteArray,
        binding: ByteArray,
    ): String? {
        if (qualifiedAtMs <= 0L) return null
        if (token.isEmpty() || token.size > ReferralReceipt.MAX_TOKEN_BYTES) return null
        if (binding.isEmpty() || binding.size > ReferralReceipt.MAX_BINDING_BYTES) return null
        val invitee = canonicalNodeId(inviteeNodeId) ?: return null
        val inviter = canonicalNodeId(inviterNodeId) ?: return null
        if (invitee == inviter) return null
        val envelope = listOf(
            PREFIX,
            KIND_ATTRIBUTION,
            VERSION_SIGNED,
            invitee,
            inviter,
            qualifiedAtMs.toString(),
            encode(token),
            encode(binding),
        ).joinToString("|")
        return envelope.takeIf { it.length <= MAX_ENVELOPE_CHARS }
    }

    /** Дешёвая проверка префикса для приёмника: вызывать до тяжёлого разбора. */
    fun isReferralPacket(text: String?): Boolean {
        val value = text ?: return false
        return value.length <= MAX_ENVELOPE_CHARS && value.startsWith("$PREFIX|")
    }

    fun parse(text: String?): Packet? {
        val value = text ?: return null
        if (value.length > MAX_ENVELOPE_CHARS) return null
        if (!value.startsWith("$PREFIX|")) return null

        val fields = value.split('|')
        if (fields.size < 2 || fields[1] != KIND_ATTRIBUTION) return null
        return when (fields[2]) {
            VERSION_SIGNED -> parseSigned(fields)
            VERSION_UNSIGNED -> parseUnsigned(fields)
            else -> null
        }
    }

    private fun parseSigned(fields: List<String>): Packet.SignedAttribution? {
        if (fields.size != SIGNED_FIELD_COUNT) return null
        val invitee = canonicalNodeId(fields[3]) ?: return null
        val inviter = canonicalNodeId(fields[4]) ?: return null
        if (invitee == inviter) return null
        val qualifiedAt = fields[5].toLongOrNull() ?: return null
        if (qualifiedAt <= 0L) return null
        val tokenB64 = fields[6]
        val bindingB64 = fields[7]
        if (!isBoundedBase64(tokenB64, ReferralReceipt.MAX_TOKEN_BYTES)) return null
        if (!isBoundedBase64(bindingB64, ReferralReceipt.MAX_BINDING_BYTES)) return null
        return Packet.SignedAttribution(invitee, inviter, qualifiedAt, tokenB64, bindingB64)
    }

    private fun parseUnsigned(fields: List<String>): Packet.UnsignedAttribution? {
        if (fields.size != UNSIGNED_FIELD_COUNT) return null
        val invitee = canonicalNodeId(fields[3]) ?: return null
        val inviter = canonicalNodeId(fields[4]) ?: return null
        if (invitee == inviter) return null
        val createdAt = fields[5].toLongOrNull() ?: return null
        if (createdAt <= 0L) return null
        val nonce = fields[6]
        if (nonce.isEmpty() || !base64UrlPattern.matches(nonce)) return null
        val decoded = decode(nonce) ?: return null
        if (decoded.size != NONCE_BYTES) return null
        return Packet.UnsignedAttribution(invitee, inviter, createdAt)
    }

    /**
     * Идентификатор узла в каноническом виде: `pk_` плюс 32 или 64 hex-цифры.
     * На телефонах владельца встречается короткая форма (32), поэтому принимаем
     * обе. Регистр приводится к нижнему, чтобы один узел не засчитывался дважды.
     */
    fun canonicalNodeId(value: String?): String? {
        val text = value?.trim()?.lowercase() ?: return null
        return text.takeIf { nodeIdPattern.matches(it) }
    }

    fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(encoded: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun isBoundedBase64(encoded: String, maxBytes: Int): Boolean {
        if (encoded.isEmpty() || !base64UrlPattern.matches(encoded)) return false
        // Достаточно грубой оценки длины, точный размер проверит разбор формата.
        val approximateBytes = encoded.length * 3L / 4L
        return approximateBytes <= maxBytes
    }
}
