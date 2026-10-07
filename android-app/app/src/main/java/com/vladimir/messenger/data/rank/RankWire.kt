package com.vladimir.messenger.data.rank

// =============================================================================
// RANKWIRE.KT — проводной конверт ранга: «я элита» для собеседников
// =============================================================================
// Решение владельца от 2026-10-07: знак VIP должен стоять не только у своего
// звания, но и у имён собеседников («Да, сделай передачу ранга»).
//
// Как это устроено. Ранг собеседника нельзя узнать самому: он растёт от ЕГО
// приглашённых друзей, а не от чужих наблюдений. Поэтому узел сам сообщает свой
// ранг тем, с кем переписывается, — коротким служебным конвертом. Конверт
// разбирается и поглощается приёмником ДО того, как текст попадёт в историю
// чата, — тем же приёмом, что APUREF1 (реферальная атрибуция) и APUGRP1
// (групповые пакеты). Собеседник видит только золотой знак VIP у имени, а не
// служебную строку в переписке.
//
// Что конверт доказывает и чего не доказывает. Транспорт 1:1 уже
// аутентифицирован: пакет пришёл от того контакта, чьим узлом он подписан на
// уровне сессии. Поэтому мы проверяем главное — что заявленный в конверте узел
// совпадает с отправителем; чужое имя так не подставить. При этом число
// приглашений узел сообщает о себе сам, и проверяемого доказательства «у меня
// ровно столько» в конверте нет: полный список приглашённых — это чужие
// идентификаторы, и пересылать его каждому собеседнику нельзя (приватность).
// Знак VIP — знак признания, а не пропуск к возможностям: возможности
// собеседника от чужого знака не зависят. Границу прямо описал в
// docs/AI_HANDOFF.md.
//
// Формат: APURANK1|1|<node_id>|<qualified>|<updated_at_ms>
// Разбор строгий: любое отклонение даёт null, пакет молча поглощается.
// =============================================================================

object RankWire {

    const val PREFIX = "APURANK1"
    const val VERSION = "1"
    const val SEPARATOR = "|"

    /**
     * Верхняя граница, которую принимаем от собеседника. Совпадает с потолком
     * счётчика в приложении (ReferralRankStore.MAX_SUPPORTED_COUNT): конверт с
     * числом выше — не ранг, а попытка нарисовать себе несуществующую ступень.
     */
    const val MAX_COUNT = 1_000

    /**
     * Насколько «из будущего» может быть метка времени. Часы на телефонах
     * разъезжаются, поэтому небольшой запас допускаем, а совсем невозможную
     * дату (сбитые часы или подделка) отбрасываем.
     */
    private const val FUTURE_TOLERANCE_MS = 24L * 60 * 60 * 1000

    // Форму узла проверяем тем же правилом, что и весь остальной транспорт
    // (`NodeIds.isNodeId`: `pk_` и 7..128 букв/цифр). Строгий hex здесь был бы
    // строже самого транспорта и молча терял бы ранги живых людей с узлами
    // другой длины - цена ошибки выше цены терпимости.
    private const val NODE_PREFIX = "pk_"
    private const val NODE_MIN_BODY = 7
    private const val NODE_MAX_BODY = 128

    /** Разобранный ранг собеседника. */
    data class PeerRank(
        val nodeId: String,
        val qualified: Int,
        val updatedAtMs: Long,
    )

    fun isRankPacket(text: String): Boolean =
        text.startsWith("$PREFIX$SEPARATOR")

    /**
     * Собрать конверт о своём ранге. null — если узел или число не годятся:
     * такой пакет не должен уходить в сеть вообще.
     */
    fun build(nodeId: String, qualified: Int, updatedAtMs: Long): String? {
        val canonical = canonicalNodeId(nodeId) ?: return null
        if (qualified < 0 || updatedAtMs <= 0) return null
        val count = qualified.coerceAtMost(MAX_COUNT)
        return listOf(PREFIX, VERSION, canonical, count.toString(), updatedAtMs.toString())
            .joinToString(SEPARATOR)
    }

    /** Разобрать конверт собеседника. Строго; при любом сомнении — null. */
    fun parse(text: String, nowMs: Long = System.currentTimeMillis()): PeerRank? {
        val parts = text.split(SEPARATOR)
        if (parts.size != 5) return null
        if (parts[0] != PREFIX || parts[1] != VERSION) return null
        val nodeId = canonicalNodeId(parts[2]) ?: return null
        val qualified = parts[3].toIntOrNull() ?: return null
        if (qualified < 0 || qualified > MAX_COUNT) return null
        val updatedAtMs = parts[4].toLongOrNull() ?: return null
        if (updatedAtMs <= 0 || updatedAtMs > nowMs + FUTURE_TOLERANCE_MS) return null
        return PeerRank(nodeId, qualified, updatedAtMs)
    }

    /** Канонический вид узла: нижний регистр и проверка формы, как в транспорте. */
    fun canonicalNodeId(value: String?): String? {
        val text = value?.trim()?.lowercase() ?: return null
        if (!text.startsWith(NODE_PREFIX)) return null
        val body = text.length - NODE_PREFIX.length
        if (body < NODE_MIN_BODY || body > NODE_MAX_BODY) return null
        if (!text.all { it in 'a'..'z' || it in '0'..'9' }) return null
        return text
    }
}
