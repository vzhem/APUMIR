package com.vladimir.messenger.service

import android.content.Context

/**
 * Обычное письмо от собеседника, не ставшее служебным пакетом.
 *
 * Этим же поводом объявляем ему свою эпоху — не чаще раза в пять минут на
 * собеседника (ограничение живёт в `PushEpochPublish`). Раньше анонс уходил
 * только вслед за НАШИМ сообщением, поэтому телефон, который в переписке
 * только слушает, оставался небудимым: собеседнику нечем было его адресовать,
 * и каждое пробуждение заканчивалось `no_peer_epoch`.
 *
 * Всегда возвращает false: вызывающий обязан продолжить обычную обработку —
 * это письмо ещё должно попасть в переписку.
 *
 * Отдельный файл, потому что `CoreServerService` упёрся в потолок строк.
 */
internal fun announceUnroutedIncoming(context: Context, senderId: String): Boolean {
    com.vladimir.messenger.data.push.PushEpochPublish
        .noteCorrespondentIfEnabled(context, senderId)
    return false
}

/**
 * Сосед показался в сети — сообщаем ему свою эпоху, не дожидаясь письма.
 *
 * Владелец 2026-11-18: «server67 сложно достучаться». Причина была замкнутой:
 * эпоха уходила только по факту переписки, значит телефон, который замёрз ДО
 * получения нашего письма, никогда не узнавал нашу эпоху — и разбудить его было
 * нечем. Теперь достаточно любого свидетельства, что он в сети (presence-кадр,
 * сообщение, квитанция), и того же — когда мы приняли ЕГО эпоху: в ответ уходит
 * наша, так что одного направления хватает, чтобы круг разорвался.
 *
 * Ограничитель внутри `PushEpochPublish`: не чаще раза в пять минут на
 * собеседника, поэтому «показался в сети» не превращается в поток кадров.
 */
internal fun announceOwnEpoch(context: Context, peerId: String) {
    // Хранитель (`CUSTD…`) и транзитные метки (`relay|…`) — не собеседники:
    // эпоха адресована конкретному узлу, а таким «показался в сети» значит
    // только то, что через них прошёл чужой кадр.
    if (peerId.length < 16 || peerId.startsWith("CUSTD") || peerId.startsWith("relay")) return
    com.vladimir.messenger.data.push.PushEpochPublish
        .noteCorrespondentIfEnabled(context, peerId, passive = true)
}

/**
 * Входящий пакет участника нашей группы или канала (не общесетевой gossip).
 *
 * Подписчик канала и молчащий участник группы сами могут ничего не писать:
 * без ответного анона эпохи автору канала и согруппникам нечем их разбудить.
 * Вызывается только после проверки, что `groupId` — наша локальная группа/канал
 * и пакет относится к ней, а не к `dir`/`nick`/`avat`/`who`/`up*`/`fp*`.
 */
internal fun announceGroupCorrespondent(context: Context, senderId: String) {
    if (!com.vladimir.messenger.util.NodeIds.isNodeId(senderId)) return
    com.vladimir.messenger.data.push.PushEpochPublish
        .noteCorrespondentIfEnabled(context, senderId, passive = true)
}
