package com.vladimir.messenger.data.file

import android.util.Log
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Хранение файла у третьего телефона (этап 7 роя, `SECURE_FILE_TRANSFER.md` §D).
 *
 * Две роли в одном классе, потому что у них одна механика (окно кусков,
 * подтверждения, прямой канал):
 *
 * 1. **Отправитель** ([pumpOrigin]). Своя исходящая передача стоит в
 *    `WAITING_RECIPIENT` дольше [CUSTODY_AFTER_MS] - получатель не в сети.
 *    Выбираем хранителя: контакт, который сейчас в сети, с лучшим рейтингом
 *    ([candidates] уже отсортированы). Отдаём ему `CUSTODY_OFFER` (манифест +
 *    конверт ключа ДЛЯ ПОЛУЧАТЕЛЯ + своя привязка) и зашифрованные куски
 *    `CUSTODY_CHUNK` по прямому каналу (LAN/QUIC, [directTransport]); окно
 *    двигают его `CUSTODY_ACK`. Когда он подтвердил все куски - передача
 *    становится `CUSTODIED`, телефон отправителя может уходить из сети.
 *    Кандидат, который не ответил за [ATTEMPT_TIMEOUT_MS] (старая версия
 *    молча отбрасывает незнакомый тип), отказал или полон - откладывается
 *    на [REJECT_COOLDOWN_MS], берём следующего. Свою копию отправитель не
 *    удаляет: `COMPLETE` ставит только финальное подтверждение самого
 *    получателя (обычный `ACK`, он идёт отправителю через очередь
 *    ретранслятора).
 *
 * 2. **Хранитель** ([pumpForwarding]). Строки `direction = CUSTODY` с полным
 *    набором кусков; как только получатель (`peerNodeId`) показался в сети
 *    ([isOnline]) - отдаём ему тот же `CUSTODY_OFFER` (собран из сохранённого
 *    манифеста, конверта и привязки отправителя) и куски по прямому каналу,
 *    окно двигают `CUSTODY_ACK` получателя. Полное подтверждение - куски и
 *    строка удаляются ([onRecipientAck]). Хранитель ключа файла не имеет и
 *    содержимого не видит; место занимает в пределах ползунка «Место под
 *    пересылку» (проверяет приёмник при записи куска).
 *
 * Прямой канал обязателен для обоих плеч: гонять мегабайты через очередь
 * ретранслятора нельзя (те же правила, что у прямой передачи). Подтверждения
 * маленькие и идут обычным надёжным транспортом (их шлёт приёмник).
 *
 * Рукопожатие: сначала уходит ТОЛЬКО предложение; куски - после первого
 * подтверждения (оно же сообщает, сколько у той стороны уже есть). Так
 * телефон старой версии или отказавший хранитель не получает впустую
 * полмегабайта кусков. Дальше - окно от подтверждённого префикса; новое окно
 * уходит, когда подтверждён конец предыдущего, либо по таймеру
 * [REPUMP_INTERVAL_MS] (потери), а не на каждое подтверждение.
 */
class FileCustodySender(
    private val transferDao: FileTransferDao,
    private val chunkStore: FileTransferChunkStore,
    /** Прямой канал: (узел, текст) -> true, если ушло напрямую. */
    private val directTransport: (String, String) -> Boolean,
    private val ownBindingProvider: () -> ByteArray?,
    private val myNodeId: () -> String?,
    /**
     * Кандидаты в хранители для файла размера totalBytes получателю recipientId:
     * уже в порядке предпочтения (рейтинг), только те, кто сейчас в сети, без
     * самого получателя. Пусто - ждём дальше.
     */
    private val candidates: suspend (recipientId: String, totalBytes: Long) -> List<String>,
    /** Получатель хранимого файла сейчас в сети (по пульсу присутствия). */
    private val isOnline: suspend (nodeId: String) -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Ход одного плеча: кому шлём, что подтверждено, докуда дослали. */
    private class Leg(val peer: String, val startedAtMs: Long) {
        @Volatile var acked: Long = 0L
        @Volatile var lastAckAtMs: Long = 0L
        @Volatile var lastPumpAtMs: Long = 0L
        @Volatile var sentUpTo: Long = 0L
        @Volatile var pumps: Int = 0
    }

    private class Window(val sent: Int, val end: Long)

    data class Summary(val originPumped: Int, val forwarded: Int, val packets: Int)

    private val mutex = Mutex()
    /** Мои исходящие: transferId -> текущий кандидат в хранители. */
    private val attempts = ConcurrentHashMap<String, Leg>()
    /** transferId -> (кандидат -> до какого момента не предлагать). */
    private val rejectedUntil = ConcurrentHashMap<String, MutableMap<String, Long>>()
    /** Чужие на хранении: transferId -> выдача получателю. */
    private val forwards = ConcurrentHashMap<String, Leg>()
    @Volatile private var lastSweepAt = 0L

    /** Кому сейчас предлагаем передачу (журнал, тесты). */
    fun currentCustodian(transferIdHex: String): String? = attempts[transferIdHex]?.peer

    // ── Отправитель ──────────────────────────────────────────────────────────

    suspend fun pumpOrigin(): Summary = mutex.withLock {
        val now = nowMs()
        val me = myNodeId() ?: return Summary(0, 0, 0)
        var pumped = 0
        var packets = 0
        val waiting = transferDao.getWaitingRecipient()
        // Попытки по передачам, которых больше нет (отменены, доставлены,
        // истекли) - забыть; временно ушедшие в TRANSFERRING по пульсу
        // контакта остаются, они вернутся.
        val waitingIds = waiting.mapTo(HashSet<String>()) { it.transferId }
        for (id in attempts.keys.toList()) {
            if (id in waitingIds) continue
            val row = transferDao.getTransfer(id)
            if (row == null || row.direction != "OUTGOING" || row.state in FINISHED_STATES) {
                attempts.remove(id)
                rejectedUntil.remove(id)
            }
        }
        for (id in rejectedUntil.keys.toList()) {
            if (id !in waitingIds && transferDao.getTransfer(id) == null) rejectedUntil.remove(id)
        }
        for (transfer in waiting) {
            if (transfer.expiresAtMs <= now) continue
            if (transfer.chunkCount <= 0L) continue
            // Уже лежит у хранителя: второго не ищем (прямые повторы делает
            // обычный передатчик, см. resumeStaleCustodied).
            if (transfer.custodianNodeId.isNotBlank()) continue
            // От создания, а не от последнего изменения: каждый пульс любого
            // контакта будит ожидающие передачи и обновляет updatedAtMs, так
            // что «две минуты тишины» иначе не наступили бы никогда.
            if (now - transfer.createdAtMs < CUSTODY_AFTER_MS) continue
            val id = transfer.transferId
            val leg = attempts[id]
            if (leg != null) {
                val silentSince = if (leg.lastAckAtMs == 0L) leg.startedAtMs else leg.lastAckAtMs
                val limit = if (leg.lastAckAtMs == 0L) ATTEMPT_TIMEOUT_MS else STALL_TIMEOUT_MS
                if (now - silentSince >= limit) {
                    Log.i(TAG, "custody: ${leg.peer.takeLast(8)} silent for $id, next candidate")
                    reject(id, leg.peer, now, if (leg.lastAckAtMs == 0L) REJECT_COOLDOWN_MS else UNREACHABLE_COOLDOWN_MS)
                    attempts.remove(id)
                } else if (due(leg, transfer.chunkCount, now)) {
                    val sent = pushOrigin(transfer, leg, me, now)
                    if (sent > 0) {
                        pumped++
                        packets += sent
                    }
                }
                continue
            }
            val blocked = rejectedUntil[id].orEmpty().filterValues { it > now }.keys
            val candidate = candidates(transfer.peerNodeId, transfer.totalBytes)
                .firstOrNull { it != transfer.peerNodeId && it != me && it !in blocked }
                ?: continue
            val fresh = Leg(candidate, now)
            attempts[id] = fresh
            Log.i(TAG, "custody: offering $id (${transfer.totalBytes} B) to ${candidate.takeLast(8)}")
            val sent = pushOrigin(transfer, fresh, me, now)
            if (sent > 0) {
                pumped++
                packets += sent
            }
        }
        return Summary(pumped, 0, packets)
    }

    /** Подтверждение хранителя-кандидата моей исходящей передаче. */
    suspend fun onCustodianAck(transferIdHex: String, from: String, contiguous: Long, status: Byte) {
        mutex.withLock {
            val leg = attempts[transferIdHex] ?: return
            if (leg.peer != from) return
            val transfer = transferDao.getTransfer(transferIdHex) ?: return
            if (transfer.direction != "OUTGOING") return
            val now = nowMs()
            when (status) {
                FileCustodyPdu.ACK_OK -> {
                    leg.lastAckAtMs = now
                    if (contiguous > leg.acked) leg.acked = contiguous
                    if (contiguous >= transfer.chunkCount) {
                        transferDao.setCustodian(transferIdHex, from, now)
                        if (transfer.state == "WAITING_RECIPIENT") {
                            advance(transfer, "CUSTODIED")
                        }
                        attempts.remove(transferIdHex)
                        rejectedUntil.remove(transferIdHex)
                        Log.i(TAG, "custody: $transferIdHex fully held by ${from.takeLast(8)}")
                    } else if (transfer.state == "WAITING_RECIPIENT" && contiguous >= leg.sentUpTo) {
                        // Конец окна подтверждён - следующее окно сразу, не
                        // дожидаясь насоса.
                        val me = myNodeId() ?: return
                        pushOrigin(transfer, leg, me, now)
                    }
                }
                FileCustodyPdu.ACK_REFUSED, FileCustodyPdu.ACK_FULL -> {
                    Log.i(
                        TAG,
                        "custody: ${from.takeLast(8)} ${if (status == FileCustodyPdu.ACK_FULL) "is full" else "refused"} " +
                            "for $transferIdHex, next candidate",
                    )
                    reject(transferIdHex, from, now)
                    attempts.remove(transferIdHex)
                }
            }
        }
    }

    /** Окно кандидату; недостижимого напрямую снимаем с попытки. @return фрагментов ушло. */
    private suspend fun pushOrigin(transfer: FileTransferEntity, leg: Leg, me: String, now: Long): Int {
        val id = transfer.transferId
        leg.lastPumpAtMs = now
        leg.pumps++
        val result = runCatching {
            val manifest = chunkStore.readManifest(id) ?: error("manifest missing")
            val envelope = chunkStore.readKeyEnvelope(id) ?: error("key envelope missing")
            val binding = ownBindingProvider() ?: error("own binding missing")
            val offer = FileCustodyPdu.encode(me, transfer.peerNodeId, manifest, envelope, binding)
            sendWindow(leg.peer, transfer, offer, leg.acked, offerOnly = leg.lastAckAtMs == 0L)
        }
        val window = result.getOrNull()
        if (window == null) {
            Log.i(TAG, "custody: ${leg.peer.takeLast(8)} not reachable for $id: ${result.exceptionOrNull()?.message}")
            // Не навсегда: прямой канал мог просто не подняться в этот раз.
            reject(id, leg.peer, now, UNREACHABLE_COOLDOWN_MS)
            attempts.remove(id)
            return 0
        }
        leg.sentUpTo = window.end
        return window.sent
    }

    // ── Хранитель ────────────────────────────────────────────────────────────

    suspend fun pumpForwarding(): Summary = mutex.withLock {
        val now = nowMs()
        var forwarded = 0
        var packets = 0
        val active = transferDao.getActiveCustody(now)
        forwards.keys.retainAll(active.mapTo(HashSet<String>()) { it.transferId })
        for (row in active) {
            if (row.completedChunks < row.chunkCount) continue // ещё собираем от отправителя
            if (!isOnline(row.peerNodeId)) continue
            val id = row.transferId
            val leg = forwards.getOrPut(id) { Leg(row.peerNodeId, now) }
            if (!due(leg, row.chunkCount, now)) continue
            val sent = pushForward(row, leg, now)
            if (sent > 0) {
                forwarded++
                packets += sent
                if (row.state != "FORWARDING") advance(row, "FORWARDING")
            }
        }
        return Summary(0, forwarded, packets)
    }

    /** Подтверждение получателя хранимой у меня передаче. */
    suspend fun onRecipientAck(transferIdHex: String, from: String, contiguous: Long, status: Byte) {
        mutex.withLock {
            val row = transferDao.getTransfer(transferIdHex) ?: return
            if (row.direction != "CUSTODY" || row.peerNodeId != from) return
            val now = nowMs()
            when (status) {
                FileCustodyPdu.ACK_OK -> {
                    val leg = forwards.getOrPut(transferIdHex) { Leg(from, now) }
                    leg.lastAckAtMs = now
                    if (contiguous > leg.acked) leg.acked = contiguous
                    if (contiguous >= row.chunkCount) {
                        Log.i(TAG, "custody: $transferIdHex delivered to ${from.takeLast(8)}, releasing")
                        release(transferIdHex)
                    } else if (contiguous >= leg.sentUpTo && isOnline(from)) {
                        pushForward(row, leg, now)
                    }
                }
                FileCustodyPdu.ACK_REFUSED -> {
                    // Получатель не примет (отправитель не его контакт, ключ
                    // сменился и т.п.) - держать дальше бессмысленно.
                    Log.i(TAG, "custody: ${from.takeLast(8)} refused $transferIdHex, releasing")
                    release(transferIdHex)
                }
                FileCustodyPdu.ACK_FULL -> {
                    // У получателя нет места: подождём, повторим по таймеру.
                    Log.i(TAG, "custody: ${from.takeLast(8)} has no space for $transferIdHex, will retry")
                    val leg = forwards.getOrPut(transferIdHex) { Leg(from, now) }
                    leg.lastPumpAtMs = now
                }
            }
        }
    }

    /** Истёкшие и брошенные (нет прогресса [STALE_HOLD_MS]) чужие файлы - удалить. Не чаще раза в 10 минут. */
    suspend fun sweep(force: Boolean = false): Int = mutex.withLock {
        val now = nowMs()
        if (!force && now - lastSweepAt < SWEEP_INTERVAL_MS) return 0
        lastSweepAt = now
        var removed = 0
        for (row in transferDao.getFinishedCustody(now)) {
            release(row.transferId)
            removed++
        }
        for (row in transferDao.getActiveCustody(now)) {
            if (row.completedChunks < row.chunkCount && now - row.updatedAtMs >= STALE_HOLD_MS) {
                Log.i(TAG, "custody: ${row.transferId} abandoned by origin, releasing")
                release(row.transferId)
                removed++
            }
        }
        return removed
    }

    private suspend fun pushForward(row: FileTransferEntity, leg: Leg, now: Long): Int {
        val id = row.transferId
        leg.lastPumpAtMs = now
        leg.pumps++
        if (row.originNodeId.isBlank()) return 0
        val result = runCatching {
            val manifest = chunkStore.readManifest(id) ?: error("manifest missing")
            val envelope = chunkStore.readKeyEnvelope(id) ?: error("key envelope missing")
            val binding = chunkStore.readOriginBinding(id) ?: error("origin binding missing")
            val offer = FileCustodyPdu.encode(row.originNodeId, row.peerNodeId, manifest, envelope, binding)
            sendWindow(row.peerNodeId, row, offer, leg.acked, offerOnly = leg.lastAckAtMs == 0L)
        }
        val window = result.getOrNull()
        if (window == null) {
            Log.i(TAG, "custody: ${row.peerNodeId.takeLast(8)} not reachable directly: ${result.exceptionOrNull()?.message}")
            return 0
        }
        leg.sentUpTo = window.end
        return window.sent
    }

    private suspend fun release(transferIdHex: String) {
        runCatching { chunkStore.deleteTransfer(transferIdHex) }
        transferDao.deleteTransfer(transferIdHex)
        forwards.remove(transferIdHex)
    }

    // ── Общее ────────────────────────────────────────────────────────────────

    /**
     * Пора ли слать окно: первый раз - сразу; конец прошлого окна подтверждён -
     * сразу; иначе по таймеру. Плечо, которое ни разу не ответило за
     * несколько окон (телефон старой версии молча отбрасывает незнакомый
     * тип), переводим на редкий повтор, чтобы не лить впустую.
     */
    private fun due(leg: Leg, chunkCount: Long, now: Long): Boolean {
        if (leg.lastPumpAtMs == 0L) return true
        if (leg.acked >= leg.sentUpTo && leg.acked < chunkCount && leg.lastAckAtMs != 0L) return true
        val interval = if (leg.lastAckAtMs == 0L && leg.pumps >= SILENT_PUMPS_BEFORE_BACKOFF) {
            SILENT_REPUMP_INTERVAL_MS
        } else {
            REPUMP_INTERVAL_MS
        }
        return now - leg.lastPumpAtMs >= interval
    }

    /**
     * Предложение (и, если рукопожатие состоялось, окно кусков начиная с
     * [acked]) - напрямую [to]. Бросает, если прямой канал не дал отправить.
     */
    private fun sendWindow(
        to: String,
        transfer: FileTransferEntity,
        offer: ByteArray,
        acked: Long,
        offerOnly: Boolean,
    ): Window {
        val id = transfer.transferId
        val transferId = hexToBytes(id)
        var sent = 0
        for (fragment in FileTransferPacketCodec.fragment(FileTransferPacketCodec.Type.CUSTODY_OFFER, transferId, 0L, offer)) {
            check(directTransport(to, FileTransferWire.encodeEncodedPacket(fragment))) { "direct send failed" }
            sent++
        }
        if (offerOnly) return Window(sent, acked)
        if (acked >= transfer.chunkCount) return Window(sent, transfer.chunkCount)
        val fragmentsPerChunk = maxOf(
            1,
            (transfer.chunkSize + FileTransferChunkStore.AEAD_TAG_BYTES +
                FileTransferPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES - 1) /
                FileTransferPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES,
        )
        val budget = if (transfer.chunkSize >= FileTransferSender.LARGE_CHUNK_BYTES) {
            FileTransferSender.MAX_INFLIGHT_MESSAGES_LARGE
        } else {
            FileTransferSender.MAX_INFLIGHT_MESSAGES
        }
        val windowChunks = maxOf(1, budget / fragmentsPerChunk)
        val windowEnd = minOf(transfer.chunkCount, acked + windowChunks.toLong())
        for (chunkIndex in acked until windowEnd) {
            val ciphertext = chunkStore.readEncryptedChunk(id, chunkIndex)
                ?: throw IllegalStateException("Chunk $chunkIndex missing for $id")
            try {
                for (fragment in FileTransferPacketCodec.fragment(FileTransferPacketCodec.Type.CUSTODY_CHUNK, transferId, chunkIndex, ciphertext)) {
                    check(directTransport(to, FileTransferWire.encodeEncodedPacket(fragment))) { "direct send failed" }
                    sent++
                }
            } finally {
                ciphertext.fill(0)
            }
        }
        return Window(sent, windowEnd)
    }

    private fun reject(transferIdHex: String, candidate: String, now: Long, cooldown: Long = REJECT_COOLDOWN_MS) {
        val map = rejectedUntil.getOrPut(transferIdHex) { ConcurrentHashMap<String, Long>() }
        map[candidate] = now + cooldown
    }

    private suspend fun advance(transfer: FileTransferEntity, newState: String) {
        val updated = transferDao.advanceProgress(
            transferId = transfer.transferId,
            state = newState,
            completedChunks = transfer.completedChunks,
            transferredBytes = transfer.transferredBytes,
            updatedAtMs = nowMs(),
            errorCode = null,
        )
        if (updated != 1) Log.w(TAG, "custody: state flip to $newState rejected for ${transfer.transferId}")
    }

    private fun hexToBytes(transferIdHex: String): ByteArray =
        ByteArray(16) { index -> transferIdHex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    companion object {
        private const val TAG = "FileCustodySender"
        private val FINISHED_STATES = setOf("COMPLETE", "CANCELLED", "FAILED", "CUSTODIED", "EXPIRED")
        /** Сколько передача ждёт получателя, прежде чем искать хранителя. */
        const val CUSTODY_AFTER_MS = 2 * 60_000L
        /** Кандидат ни разу не ответил - считаем, что не умеет (старая версия) или не хочет. */
        const val ATTEMPT_TIMEOUT_MS = 90_000L
        /** Кандидат отвечал, но замолчал: снимаем попытку, ненадолго откладываем. */
        const val STALL_TIMEOUT_MS = 10 * 60_000L
        /** Отказавшего/молчащего кандидата не трогаем для этой передачи. */
        const val REJECT_COOLDOWN_MS = 6 * 60 * 60_000L
        /** Недостижимого напрямую - на короткое время: канал мог не подняться. */
        const val UNREACHABLE_COOLDOWN_MS = 10 * 60_000L
        const val REPUMP_INTERVAL_MS = FileTransferSender.REPUMP_INTERVAL_MS
        /** Столько окон без единого ответа - дальше повторяем редко. */
        const val SILENT_PUMPS_BEFORE_BACKOFF = 4
        const val SILENT_REPUMP_INTERVAL_MS = 10 * 60_000L
        /** Куски не приходят от отправителя двое суток - хранение брошено. */
        const val STALE_HOLD_MS = 48 * 60 * 60_000L
        const val SWEEP_INTERVAL_MS = 10 * 60_000L
        /** Потолки хранителя: всего чужих файлов и от одного отправителя. */
        const val MAX_CUSTODY_TRANSFERS = 32
        const val MAX_CUSTODY_PER_ORIGIN = 8
        /** Хранимая передача живёт не дольше недели, даже если манифест щедрее. */
        const val CUSTODY_TTL_MS = 7L * 24 * 60 * 60_000L
        /**
         * Файл у хранителя, а итогового подтверждения получателя всё нет:
         * через это время отправитель снова пробует и напрямую (на случай,
         * если хранитель пропал). Получатель, у которого файл уже есть,
         * ответит итоговым подтверждением на первый же повтор.
         */
        const val DIRECT_RETRY_AFTER_MS = 15 * 60_000L
    }
}
