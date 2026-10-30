package com.vladimir.messenger.service

import com.vladimir.messenger.data.heartgame.HeartGameNetwork
import com.vladimir.messenger.data.rank.PeerRankRouter

/**
 * Разбор двух служебных конвертов, которые едут в одной обёртке «печатает…»:
 * счёта игры в сердечки (`APUGAME1`) и ранга собеседника (`APURANK1`).
 *
 * Зачем отдельная функция. Раньше на всех четырёх путях приёма стояло одно
 * выражение `heartGameNetwork.routeIncoming(...) || peerRankRouter.routeIncoming(...)`.
 * Двойная черта в таком `||` означала «если первый разбор упал или соврал -
 * второй уже не спросят»: сбой сердечек (например, недоступный ключ подписи)
 * съедал конверт ранга, и знак VIP у имени не появлялся. Теперь каждый конверт
 * разбирается под своей защитой: падение одного не уносит второй.
 *
 * Файл отдельный, а не код в `CoreServerService`: тот уже за потолком god-файла,
 * и новый код туда не пишется (правило 8 в `AGENTS.md`).
 *
 * @return true, если текст оказался одним из этих конвертов (тогда в переписку
 *         он попасть не должен).
 */
internal suspend fun routeRankOrHeartPacket(
    heartGameNetwork: HeartGameNetwork,
    peerRankRouter: PeerRankRouter,
    senderId: String,
    text: String,
): Boolean {
    if (routePacket("CoreServerService", "heartGame") {
            heartGameNetwork.routeIncoming(senderId, text)
        }
    ) return true
    return routePacket("CoreServerService", "rank") {
        peerRankRouter.routeIncoming(senderId, text)
    }
}

/**
 * Ответ на служебный конверт — квитанция доставки. Сбой квитанции не должен
 * отменять уже применённый разбор, поэтому ошибка только пишется в журнал.
 */
internal fun acknowledgeRankPacket(messageId: String, senderId: String, why: String) {
    try {
        com.vladimir.messenger.data.RustBridge.sendDeliveryAck(messageId, senderId)
    } catch (e: Exception) {
        android.util.Log.w("CoreServerService", "$why ACK failed: " + e.message)
    }
}
