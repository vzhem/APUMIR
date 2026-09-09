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

    /**
     * @param groupId идентификатор группы; транспорт использует его как chatId,
     *   поэтому одна и та же отправка 1:1 остаётся пригодной для групп.
     */
    suspend fun deliver(
        groupId: String,
        envelope: String,
        recipients: List<String>,
    ): DeliveryReport
}

/**
 * Отправка каждому участнику отдельно тем же транспортом, что и личные чаты.
 *
 * Ограничение ширины ([maxConcurrent] или [concurrency]) нужно по двум
 * причинам: не забить очередь ядра при большом списке участников и не держать
 * в памяти сразу весь веер копий. [gate] - общий на телефон бюджет пакетов
 * (см. `data/swarm/SwarmBudget`): перед каждой отправкой веер ждёт жетон,
 * поэтому пост с шестью фото в группу на двести человек уходит в разрешённом
 * темпе, а не залпом.
 */
class PerMemberFanoutDelivery(
    override val name: String = "per-member-fanout",
    private val maxConcurrent: Int = 8,
    private val send: suspend (groupId: String, recipientId: String, envelope: String) -> Boolean,
    /**
     * Порядок обхода получателей: свои, проверенные, стабильные, остальные;
     * внутри яруса - лучшие по рейтингу первыми.
     *
     * По умолчанию порядок не меняется - так веер остаётся проверяемым
     * обычным JVM-тестом, без Android и без накопленной статистики.
     */
    private val order: suspend (List<String>) -> List<String> = { it },
    /** Ширина веера на момент отправки; по умолчанию - постоянная [maxConcurrent]. */
    private val concurrency: () -> Int = { maxConcurrent },
    /** Ожидание жетона перед каждой отправкой; по умолчанию - без ожидания. */
    private val gate: suspend () -> Unit = {},
) : GroupDelivery {

    override suspend fun deliver(
        groupId: String,
        envelope: String,
        recipients: List<String>,
    ): DeliveryReport =
        coroutineScope {
            // Сначала свои и надёжные: при обрыве связи на середине веера
            // данные успеют уйти хотя бы тем, кто раздаст дальше.
            val targets = order(recipients.filter { it.isNotBlank() }.distinct())
            if (targets.isEmpty()) return@coroutineScope DeliveryReport(0, 0, emptyList())

            val width = concurrency().coerceAtLeast(1)
            val failed = ArrayList<String>()
            var delivered = 0

            targets.chunked(width).forEach { batch ->
                val results = batch.map { id ->
                    async {
                        id to runCatching {
                            gate()
                            send(groupId, id, envelope)
                        }.getOrDefault(false)
                    }
                }.awaitAll()
                results.forEach { (id, ok) ->
                    if (ok) delivered++ else failed.add(id)
                }
            }

            DeliveryReport(targets.size, delivered, failed)
        }
}
