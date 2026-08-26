package com.vladimir.messenger.data.group

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class DeliveryReport(
    val attempted: Int,
    val delivered: Int,
    val failed: List<String>,
) {
    val isFullSuccess: Boolean get() = failed.isEmpty()
    val deliveredRatio: Double get() = if (attempted == 0) 1.0 else delivered.toDouble() / attempted
}

/**
 * Как групповой конверт доходит до участников.
 *
 * Это единственное место, где группа касается транспорта. Сейчас реализация
 * одна — [PerMemberFanoutDelivery] поверх существующей отправки 1:1. Когда в
 * Rust-ядре появится групповой gossip-фанаут, добавится вторая реализация
 * этого же интерфейса, и ни репозиторий, ни UI меняться не будут.
 */
interface GroupDelivery {
    val name: String

    suspend fun deliver(envelope: String, recipients: List<String>): DeliveryReport
}

/**
 * Отправка каждому участнику отдельно тем же транспортом, что и личные чаты.
 *
 * Ограничение [maxConcurrent] нужно по двум причинам: не забить очередь ядра
 * при большом списке участников и не держать в памяти сразу весь веер копий.
 */
class PerMemberFanoutDelivery(
    override val name: String = "per-member-fanout",
    private val maxConcurrent: Int = 8,
    private val send: suspend (recipientId: String, envelope: String) -> Boolean,
) : GroupDelivery {

    override suspend fun deliver(envelope: String, recipients: List<String>): DeliveryReport =
        coroutineScope {
            val targets = recipients.filter { it.isNotBlank() }.distinct()
            if (targets.isEmpty()) return@coroutineScope DeliveryReport(0, 0, emptyList())

            val width = maxConcurrent.coerceAtLeast(1)
            val failed = ArrayList<String>()
            var delivered = 0

            targets.chunked(width).forEach { batch ->
                val results = batch.map { id ->
                    async { id to runCatching { send(id, envelope) }.getOrDefault(false) }
                }.awaitAll()
                results.forEach { (id, ok) ->
                    if (ok) delivered++ else failed.add(id)
                }
            }

            DeliveryReport(targets.size, delivered, failed)
        }
}
