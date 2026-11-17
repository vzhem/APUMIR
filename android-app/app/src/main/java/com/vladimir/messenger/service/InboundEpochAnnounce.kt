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
