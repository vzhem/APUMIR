package com.vladimir.messenger.service

/**
 * Адресный запрос конверта ранга у собеседника, только что появившегося в сети.
 *
 * Вынесено из `CoreServerService.kt` при добавлении опросов личных чатов
 * (р252): god-файл не должен расти, а весь смысл запроса — одна проверка
 * «ранг ещё неизвестен» и один вызов ограничителя внутри `PeerRankBroadcaster`.
 *
 * @return true — запрос отправлен; false — ранг уже известен или отправить не
 *   удалось (тогда значок VIP появится со следующей недельной рассылкой).
 */
internal suspend fun CoreServerService.requestPeerRank(peerId: String): Boolean =
    if (peerRankStore.qualifiedFor(peerId) == null) {
        peerRankBroadcaster.requestRank(peerId)
    } else {
        false
    }
