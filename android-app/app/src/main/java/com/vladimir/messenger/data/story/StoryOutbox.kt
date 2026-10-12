package com.vladimir.messenger.data.story

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Досылка фотоистории.
 *
 * Фото едет десятками отдельных сообщений, и каждая часть обязательна: сборка у
 * получателя отдаёт снимок только целиком. Если потерялась хоть одна часть,
 * друг не увидит ничего - ни фото, ни подписи. Поэтому пакеты не рассылаются
 * один раз: идентификатор сообщения выводится из истории и номера части, и тот
 * же самый пакет повторяется в следующих раундах. Повтор не вредит: сборка
 * принимает одинаковую часть молча, а новая волна доходит, когда телефон друга
 * снова в сети.
 */
object StoryOutbox {
    /** Сколько всего волн рассылки. */
    const val MAX_ROUNDS = 3

    /** Пауза перед второй волной; третья - ещё через столько же. */
    const val ROUND_DELAY_MS = 90_000L

    /** Досылаем только настоящие фотоистории: у текстовой истории один пакет. */
    private const val MAX_RESEND_PACKETS = 120

    /** Сколько историй держим в досылке одновременно, старые вытесняются. */
    private const val MAX_STORIES = 4

    /** Пакет истории: тот же [transportId] во всех раундах. */
    class Packet(val transportId: String, val content: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val jobs = LinkedHashMap<String, Job>()
    private val order = ArrayDeque<String>()

    /** Пакеты истории с устойчивыми идентификаторами отправки. */
    fun packets(storyId: String, encoded: List<String>): List<Packet> =
        encoded.mapIndexed { index, content -> Packet(transportId(storyId, index), content) }

    /**
     * Идентификатор сообщения для части истории.
     *
     * Одинаковый во всех раундах (иначе повтор приедет как новое сообщение) и
     * всегда UUID: транспорт и релей работают с UUID-идентификаторами.
     */
    fun transportId(storyId: String, index: Int): String =
        UUID.nameUUIDFromBytes("APU-STORY1:$storyId:$index".toByteArray(Charsets.UTF_8)).toString()

    /** Нужна ли досылка: только для историй с несколькими пакетами. */
    fun canResend(storyId: String, packetCount: Int): Boolean =
        storyId.length in 1..100 && packetCount in 2..MAX_RESEND_PACKETS

    /**
     * Можно ли вообще слать: текстовая история — один пакет, фото — несколько.
     * Первую волну отправляем всегда; без этого текстовые истории молча не уходят.
     */
    fun canDeliver(storyId: String, packetCount: Int, peerCount: Int): Boolean =
        storyId.length in 1..100 && packetCount in 1..MAX_RESEND_PACKETS && peerCount > 0

    /**
     * Разослать пакеты всем контактам и повторить волны позже.
     *
     * [send] возвращает false, если часть не удалось отдать транспорту; тогда
     * следующий раунд повторяет историю целиком (пропущенную часть в одиночку
     * собрать всё равно нельзя). Первая волна уходит всегда, в том числе для
     * однопакетной текстовой истории; повтор — только у фото (несколько пакетов).
     */
    fun start(
        storyId: String,
        packets: List<Packet>,
        peers: List<String>,
        send: suspend (peerId: String, packet: Packet) -> Boolean,
    ) {
        if (!canDeliver(storyId, packets.size, peers.size)) return
        val rounds = if (canResend(storyId, packets.size)) MAX_ROUNDS else 1
        val job = scope.launch {
            deliver(packets, peers, send, { delay(it) }, rounds)
        }
        synchronized(lock) {
            jobs.remove(storyId)?.cancel()
            jobs[storyId] = job
            order.remove(storyId)
            order.addLast(storyId)
            while (order.size > MAX_STORIES) jobs.remove(order.removeFirst())?.cancel()
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (jobs[storyId] === job) jobs.remove(storyId)
                order.remove(storyId)
            }
        }
    }

    /** Отменить досылку: свою историю удалили до конца рассылки. */
    fun cancel(storyId: String) {
        synchronized(lock) {
            jobs.remove(storyId)?.cancel()
            order.remove(storyId)
        }
    }

    /**
     * Волны рассылки. Возврат, как только раунд прошёл без потерь.
     *
     * [pause] вынесен параметром: тест проверяет повторы без реальных пауз.
     */
    internal suspend fun deliver(
        packets: List<Packet>,
        peers: List<String>,
        send: suspend (peerId: String, packet: Packet) -> Boolean,
        pause: suspend (Long) -> Unit,
        rounds: Int = MAX_ROUNDS,
    ) {
        val total = rounds.coerceIn(1, MAX_ROUNDS)
        repeat(total) { round ->
            if (round > 0) pause(ROUND_DELAY_MS * round)
            var missed = 0
            for (peer in peers) {
                for (packet in packets) {
                    val ok = runCatching { send(peer, packet) }.getOrDefault(false)
                    if (!ok) missed++
                }
            }
            if (missed == 0) return
        }
    }
}
