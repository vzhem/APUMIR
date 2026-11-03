package com.vladimir.messenger.service

import com.vladimir.messenger.data.directpoll.DirectPollRepository
import com.vladimir.messenger.data.directpoll.DirectPollWire

/**
 * Мост между службой ядра и опросами личных чатов (р252).
 *
 * Репозиторий берётся из службы в момент первого обращения ([bind]): поле в
 * `CoreServerService.kt` стоило бы две строки god-файла, потолок которого можно
 * только опускать.
 *
 * Опрос — служебный конверт `APUPOLL1`, как реакция или «удалить у всех»:
 * он разбирается ДО сохранения текста, иначе служебная строка выросла бы в
 * переписке. У моста три входа, и все они обязаны быть: основной приём,
 * запасной путь через релей (`routeIncomingEnvelope`) и действия теневого
 * устройства (`mirror action`).
 *
 * Файл отдельный, потому что `CoreServerService.kt` — god-файл: потолок можно
 * только опускать, а три вызова по одной строке ничего не стоят.
 */
internal object DirectPollRouting {

    /**
     * Посредник для одного разбора: держит службу, из которой лениво достаёт
     * репозиторий опросов.
     */
    fun bind(service: CoreServerService, repository: DirectPollRepository): Mediator =
        Mediator(service, repository)

    class Mediator internal constructor(
        // Служба нужна только для журнала: своего тега посредник не заводит.
        private val service: CoreServerService,
        private val repository: DirectPollRepository,
    ) {
        /** Входящий конверт (основной путь, зеркало, релей). */
        suspend fun incoming(senderId: String, text: String): Boolean =
            DirectPollRouting.incoming(repository, senderId, text)

        /** Действие, сделанное на партнёрском устройстве той же личности. */
        suspend fun mirrorAction(peerId: String, text: String): Boolean =
            DirectPollRouting.mirrorAction(repository, peerId, text)

        /** Квитанция доставки: сбой квитанции не отменяет уже применённый разбор. */
        fun acknowledge(messageId: String, senderId: String) =
            acknowledgePollPacket(messageId, senderId)
    }

    /**
     * Входящий конверт. [senderId] — отправитель; опрос сам найдёт свой чат по
     * нему, поэтому chatId здесь не нужен.
     */
    suspend fun incoming(
        repository: DirectPollRepository,
        senderId: String,
        text: String,
    ): Boolean {
        if (!DirectPollWire.isPollPacket(text)) return false
        if (senderId.isBlank()) return true
        return routePacket("CoreServerService", "directPoll") {
            repository.routeIncoming(senderId, text)
        }
    }

    /** Действие, сделанное на партнёрском устройстве той же личности. */
    suspend fun mirrorAction(
        repository: DirectPollRepository,
        peerId: String,
        text: String,
    ): Boolean {
        if (!DirectPollWire.isPollPacket(text)) return false
        return routePacket("CoreServerService", "directPoll.mirror") {
            repository.applyMirrorOutgoing(peerId, text)
        }
    }

}

/**
 * Квитанция доставки служебного конверта опроса. Ошибка квитанции не должна
 * отменять уже применённый разбор, поэтому она только пишется в журнал.
 */
internal fun acknowledgePollPacket(messageId: String, senderId: String) {
    try {
        com.vladimir.messenger.data.RustBridge.sendDeliveryAck(messageId, senderId)
    } catch (e: Exception) {
        android.util.Log.w("CoreServerService", "Direct poll packet ACK failed: " + e.message)
    }
}
