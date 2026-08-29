package com.vladimir.messenger.data.referral

import java.util.Base64

/**
 * Проводной конверт реферальной атрибуции.
 *
 * Приглашённый отправляет пригласившему один короткий пакет «я пришёл по твоей
 * ссылке». Конверт живёт поверх того же транспорта 1:1, что и APUGRP1/APULAN1,
 * и разбирается тем же приёмником (CoreServerService.handleEvent) ДО
 * авто-создания контакта, поэтому в историю чата обычным текстом не попадает.
 *
 * Подписи в первом шаге нет, и это осознанно: зачисляется не тот, кто назван в
 * конверте, а фактический отправитель пакета (senderId транспорта), поэтому
 * подделкой можно раздуть только собственный счётчик. Подписанный receipt из
 * MASTER_PLAN 2.5.2A — следующий шаг вместе с правилом «только новая identity».
 *
 * Разбор строгий: любое отклонение даёт null, пакет молча отбрасывается.
 */
object ReferralWire {

    const val PREFIX = "APUREF1"
    const val KIND_ATTRIBUTION = "attr"
    const val VERSION = "1"
    const val NONCE_BYTES = 16

    /** Конверт короткий намеренно: два узла, метка времени и nonce, ничего больше. */
    const val MAX_ENVELOPE_CHARS = 256

    private const val FIELD_COUNT = 7
    private val nodeIdPattern = Regex("^pk_[0-9a-f]{32}(?:[0-9a-f]{32})?$")
    private val noncePattern = Regex("^[A-Za-z0-9_-]+$")

    data class Attribution(
        val inviteeNodeId: String,
        val inviterNodeId: String,
        val createdAtMs: Long,
        /** base64url без дополнения, ровно [NONCE_BYTES] байт. */
        val nonce: String,
    )

    /**
     * Собрать конверт. Возвращает null на любом недопустимом входе: вызывающий
     * код не должен уметь отправить кривую атрибуцию.
     */
    fun buildAttribution(
        inviteeNodeId: String,
        inviterNodeId: String,
        createdAtMs: Long,
        nonce: ByteArray,
    ): String? {
        if (nonce.size != NONCE_BYTES) return null
        if (createdAtMs <= 0L) return null
        val invitee = canonicalNodeId(inviteeNodeId) ?: return null
        val inviter = canonicalNodeId(inviterNodeId) ?: return null
        if (invitee == inviter) return null
        val encodedNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
        val envelope = listOf(
            PREFIX,
            KIND_ATTRIBUTION,
            VERSION,
            invitee,
            inviter,
            createdAtMs.toString(),
            encodedNonce,
        ).joinToString("|")
        return envelope.takeIf { it.length <= MAX_ENVELOPE_CHARS }
    }

    /** Дешёвая проверка префикса для приёмника: вызывать до тяжёлого разбора. */
    fun isReferralPacket(text: String?): Boolean {
        val value = text ?: return false
        return value.length <= MAX_ENVELOPE_CHARS && value.startsWith("$PREFIX|")
    }

    fun parse(text: String?): Attribution? {
        val value = text ?: return null
        if (value.length > MAX_ENVELOPE_CHARS) return null
        if (!value.startsWith("$PREFIX|")) return null

        val fields = value.split('|')
        if (fields.size != FIELD_COUNT) return null
        if (fields[1] != KIND_ATTRIBUTION) return null
        if (fields[2] != VERSION) return null

        val invitee = canonicalNodeId(fields[3]) ?: return null
        val inviter = canonicalNodeId(fields[4]) ?: return null
        if (invitee == inviter) return null

        val createdAt = fields[5].toLongOrNull() ?: return null
        if (createdAt <= 0L) return null

        val nonce = fields[6]
        if (nonce.isEmpty() || !noncePattern.matches(nonce)) return null
        val decoded = try {
            Base64.getUrlDecoder().decode(nonce)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.size != NONCE_BYTES) return null

        return Attribution(
            inviteeNodeId = invitee,
            inviterNodeId = inviter,
            createdAtMs = createdAt,
            nonce = nonce,
        )
    }

    /**
     * Идентификатор узла в каноническом виде: `pk_` плюс 32 или 64 hex-цифры.
     * Регистр приводится к нижнему, чтобы один и тот же узел не засчитывался
     * дважды из-за разных источников ссылки.
     */
    fun canonicalNodeId(value: String?): String? {
        val text = value?.trim()?.lowercase() ?: return null
        return text.takeIf { nodeIdPattern.matches(it) }
    }
}
