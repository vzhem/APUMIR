package com.vladimir.messenger.data.rank

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Уведомление экранов о том, что ранг собеседника изменился.
 *
 * Знак VIP у имени читается из базы при построении списка чатов и шапки чата.
 * Без этого потока значок появлялся бы только после перезахода на экран: ранг
 * приезжает служебным конвертом в любой момент, пока человек уже смотрит список.
 * Постоянного опроса нет — считаем изменения, как в [ReferralRankStore].
 */
object PeerRankStore {

    private val _changes = MutableStateFlow(0)

    /** Счётчик изменений: экраны перечитывают знак VIP, когда он растёт. */
    val changes: StateFlow<Int> = _changes

    /** Сообщить экранам, что ранг собеседника мог измениться. */
    fun notifyChanged() {
        _changes.value = _changes.value + 1
    }
}
