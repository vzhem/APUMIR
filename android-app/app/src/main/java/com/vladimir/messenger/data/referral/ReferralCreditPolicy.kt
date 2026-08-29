package com.vladimir.messenger.data.referral

/**
 * Подтверждённый receipt: значения, прочитанные из подписанных конвертов после
 * проверки подписей ядром. Все поля аутентичны, кроме [qualifiedAtMs] — оно
 * пришло в открытом конверте, поэтому ограничивается окном времени.
 */
data class VerifiedReceipt(
    /** Узел приглашённого из подписанной привязки identity. */
    val inviteeNodeId: String,
    /** Когда приглашённый создал свою identity. */
    val inviteeIdentityCreatedAtMs: Long,
    /** Узел пригласившего из подписанного токена. */
    val inviterNodeId: String,
    /** Когда пригласивший создал ссылку. */
    val tokenCreatedAtMs: Long,
    val tokenExpiresAtMs: Long,
    /** Когда приглашённый заявил о квалификации (из открытой части конверта). */
    val qualifiedAtMs: Long,
)

/**
 * Правило зачисления приглашения — полная версия MASTER_PLAN 2.5.2A.
 *
 * Логика чистая (без Android, без криптографии), поэтому полностью покрыта
 * host-тестами и выполняется в гейте на шаге unit-тестов. Подписи проверяет
 * [ReferralReceiptVerifier] до вызова [decide].
 *
 * Что здесь закрыто из плана:
 * - токен валиден, подписан, не истёк и привязан к прямому пригласившему;
 * - identity приглашённого СОЗДАНА НЕ РАНЬШЕ ссылки, то есть засчитываются
 *   только новички (решение владельца от 2026-08-29: «потом нужно сделать чтобы
 *   только новеньких»);
 * - самоприглашение, повторный пакет, повторная установка и клон той же
 *   identity не двигают счётчик (идемпотентность по узлу);
 * - чужой идентификатор в конверте ничего не даёт: зачисляется узел из
 *   подписанной привязки, совпадающий с фактическим отправителем.
 *
 * Что осталось за пределами этого шага и требует отдельных раундов:
 * подтверждение D7-активности для высоких ступеней, антифрод против
 * device-farm/эмуляторов, отдельная подпись именно под receipt (нужен новый
 * экспорт из rust-core) и ослеплённые receipt в публичный реестр.
 */
object ReferralCreditPolicy {

    /** Окно жизни атрибуции: как MAX_REFERRAL_LIFETIME_MS в rust-core. */
    const val MAX_ATTRIBUTION_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** Допустимый перекос часов: как MAX_REFERRAL_CLOCK_SKEW_MS в rust-core. */
    const val CLOCK_SKEW_MS = 5L * 60 * 1000

    /**
     * Насколько identity может быть «старше» ссылки и всё ещё считаться новой.
     * Запас нужен только из-за перекоса часов между телефонами: сутки с запасом
     * покрывают ручную установку времени, а установка годичной давности всё
     * равно отсекается.
     */
    const val NEW_IDENTITY_SKEW_MS = 24L * 60 * 60 * 1000

    sealed class Decision {
        data class Credit(val inviteeNodeId: String) : Decision()
        data class Reject(val reason: String) : Decision()
    }

    fun decide(
        receipt: VerifiedReceipt,
        transportSenderId: String,
        ownNodeId: String?,
        alreadyCredited: Set<String>,
        nowMs: Long,
    ): Decision {
        val own = ReferralWire.canonicalNodeId(ownNodeId)
            ?: return Decision.Reject("own node id unavailable")
        val sender = ReferralWire.canonicalNodeId(transportSenderId)
            ?: return Decision.Reject("sender is not a node id")

        if (!receipt.inviterNodeId.equals(own, ignoreCase = true)) {
            return Decision.Reject("token is addressed to another node")
        }
        // Узел в подписанной привязке обязан совпадать с тем, кто реально
        // прислал пакет: иначе чужой receipt можно было бы переслать от себя.
        if (!receipt.inviteeNodeId.equals(sender, ignoreCase = true)) {
            return Decision.Reject("receipt does not match the transport sender")
        }
        if (sender.equals(own, ignoreCase = true)) {
            return Decision.Reject("self referral")
        }
        if (alreadyCredited.any { it.equals(sender, ignoreCase = true) }) {
            return Decision.Reject("already credited")
        }

        val age = nowMs - receipt.qualifiedAtMs
        if (age < -CLOCK_SKEW_MS) return Decision.Reject("qualified in the future")
        if (age > MAX_ATTRIBUTION_AGE_MS) return Decision.Reject("attribution expired")
        if (nowMs > receipt.tokenExpiresAtMs + CLOCK_SKEW_MS) {
            return Decision.Reject("invite token expired")
        }
        if (receipt.inviteeIdentityCreatedAtMs > nowMs + CLOCK_SKEW_MS) {
            return Decision.Reject("invitee identity created in the future")
        }
        if (receipt.inviteeIdentityCreatedAtMs < receipt.tokenCreatedAtMs - NEW_IDENTITY_SKEW_MS) {
            return Decision.Reject("invitee identity is not new")
        }

        return Decision.Credit(sender)
    }
}
