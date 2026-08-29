package com.vladimir.messenger.data.referral

/**
 * Правило зачисления приглашения.
 *
 * Логика намеренно чистая (без Android и без транспорта), поэтому полностью
 * покрыта host-тестами: `ReferralCreditPolicyTest` выполняется в гейте
 * `scripts/groups-build-gate.ps1` на шаге unit-тестов.
 *
 * Решение владельца от 2026-08-29: на первом шаге засчитывается ЛЮБОЙ, кто
 * пришёл по ссылке и начал чат. Ограничение «только новая identity»
 * (MASTER_PLAN 2.5.2A: «Invitee создал новую identity») добавляется вторым
 * шагом сюда же — правило единственное, поэтому ни транспорт, ни хранилище
 * переделывать не придётся.
 */
object ReferralCreditPolicy {

    /** Столько живёт атрибуция: совпадает с MAX_REFERRAL_LIFETIME_MS в rust-core. */
    const val MAX_ATTRIBUTION_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** Допустимый перекос часов, как MAX_REFERRAL_CLOCK_SKEW_MS в rust-core. */
    const val CLOCK_SKEW_MS = 5L * 60 * 1000

    sealed class Decision {
        /** Кого зачислить: фактический отправитель пакета, не имя из конверта. */
        data class Credit(val inviteeNodeId: String) : Decision()
        data class Reject(val reason: String) : Decision()
    }

    fun decide(
        packet: ReferralWire.Attribution,
        transportSenderId: String,
        ownNodeId: String?,
        alreadyCredited: Set<String>,
        nowMs: Long,
    ): Decision {
        val own = ReferralWire.canonicalNodeId(ownNodeId)
            ?: return Decision.Reject("own node id unavailable")
        val sender = ReferralWire.canonicalNodeId(transportSenderId)
            ?: return Decision.Reject("sender is not a node id")

        // Пакет должен быть адресован нам: чужую атрибуцию себе не забираем.
        if (!packet.inviterNodeId.equals(own, ignoreCase = true)) {
            return Decision.Reject("packet is addressed to another node")
        }
        if (sender.equals(own, ignoreCase = true)) {
            return Decision.Reject("self referral")
        }
        // Идемпотентность: один и тот же приглашённый счётчик второй раз не двигает,
        // поэтому повторная доставка пакета и повторная установка не дают накрутки.
        if (alreadyCredited.any { it.equals(sender, ignoreCase = true) }) {
            return Decision.Reject("already credited")
        }

        val age = nowMs - packet.createdAtMs
        if (age < -CLOCK_SKEW_MS) return Decision.Reject("created in the future")
        if (age > MAX_ATTRIBUTION_AGE_MS) return Decision.Reject("attribution expired")

        return Decision.Credit(sender)
    }
}
