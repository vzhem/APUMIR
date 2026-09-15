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
 *
 * Несколько хранителей (этап 8). Файл, который лежит у одного телефона,
 * пропадает вместе с ним; поэтому отправитель, пока сам в сети, раздаёт
 * копии до [TARGET_HOLDERS] хранителям (по одному за раз, следующего - когда
 * предыдущий подтвердил всё; список - в `custodianNodeId` через запятую,
 * [FileCustodyPdu.holders]). Получатель, увидев предложения от нескольких,
 * шлёт каждому инвентарь недостающего ([FileTransferPacketCodec.Type.CUSTODY_WANT]):
 * «есть N подряд, пришли вот эти» - и хранители отдают разные куски, а не
 * один и тот же. Хранитель без инвентаря (старая версия получателя или пакет
 * потерялся) шлёт по-старому, от подтверждённого префикса.
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
    /**
     * Можно ли отдать эту передачу на хранение. Файлы группы (рой, этап 9)
     * на хранение не идут: у каждого просителя своя копия, и на большой
     * группе хранители утонули бы в них; проситель просто спросит другого
     * сида. По умолчанию - можно всё (личные файлы).
     */
    private val custodyAllowed: suspend (FileTransferEntity) -> Boolean = { true },
) {
    /** Ход одного плеча: кому шлём, что подтверждено, докуда дослали. */
    private class Leg(val peer: String, val startedAtMs: Long) {
        @Volatile var acked: Long = 0L
        @Volatile var lastAckAtMs: Long = 0L
        @Volatile var lastPumpAtMs: Long = 0L
        @Volatile var sentUpTo: Long = 0L
        @Volatile var pumps: Int = 0
        /** Инвентарь получателя (этап 8): какие куски он просит именно у меня; null - шлём от префикса. */
        @Volatile var wanted: LongArray? = null
        /** Номер последнего инвентаря - старый, пришедший позже, игнорируем. */
        @Volatile var wantSeq: Long = -1L
        /** Конец последнего окна «от префикса» и когда оно ушло: инвентарь сразу следом не должен просить это заново. */
        @Volatile var plainWindowEnd: Long = 0L
        @Volatile var plainWindowAtMs: Long = 0L
    }

    private class Window(val sent: Int, val end: Long, val remainingWanted: LongArray? = null)

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
        // Ждущие получателя и уже отданные одному хранителю (им можно добавить
        // второго, пока отправитель в сети).
        val waiting = transferDao.getWaitingRecipient() + transferDao.getCustodied(now)
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
            if (!custodyAllowed(transfer)) continue
            // Уже лежит у хранителей в нужном числе - хватит (прямые повторы
            // делает обычный передатчик, см. resumeStaleCustodied). Меньше -
            // пока сами в сети, раздаём ещё по одной копии за раз.
            val holders = FileCustodyPdu.holders(transfer.custodianNodeId)
            if (holders.size >= TARGET_HOLDERS) continue
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
                .firstOrNull { it != transfer.peerNodeId && it != me && it !in blocked && it !in holders }
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
                        val holders = FileCustodyPdu.holders(transfer.custodianNodeId) + from
                        transferDao.setCustodian(transferIdHex, FileCustodyPdu.joinHolders(holders), now)
                        if (transfer.state == "WAITING_RECIPIENT") {
                            advance(transfer, "CUSTODIED")
                        }
                        attempts.remove(transferIdHex)
                        // Отказы прошлых кандидатов остаются в силе (их не
                        // спрашиваем повторно для следующей копии).
                        Log.i(TAG, "custody: $transferIdHex fully held by ${from.takeLast(8)} (${holders.distinct().size} holder(s))")
                    } else if (transfer.state in setOf("WAITING_RECIPIENT", "CUSTODIED") && contiguous >= leg.sentUpTo) {
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

    /**
     * Инвентарь получателя (этап 8): что именно он хочет от меня. Пустой
     * список - «пока ничего»: у него есть другие хранители на эти куски.
     */
    suspend fun onRecipientWant(transferIdHex: String, from: String, contiguous: Long, seq: Long, ranges: List<LongRange>) {
        mutex.withLock {
            val row = transferDao.getTransfer(transferIdHex) ?: return
            if (row.direction != "CUSTODY" || row.peerNodeId != from) return
            if (row.completedChunks < row.chunkCount) return
            val now = nowMs()
            val leg = forwards.getOrPut(transferIdHex) { Leg(from, now) }
            if (seq <= leg.wantSeq) return
            leg.wantSeq = seq
            leg.lastAckAtMs = now
            if (contiguous > leg.acked) leg.acked = minOf(contiguous, row.chunkCount)
            var wanted = FileCustodyPdu.expandRanges(ranges, row.chunkCount, MAX_WANTED_PER_INVENTORY)
            if (leg.plainWindowEnd > 0L && now - leg.plainWindowAtMs < IN_FLIGHT_GRACE_MS) {
                // Только что ушло окно от префикса - получатель составлял
                // инвентарь, ещё не видя его. Эти куски в пути; если и правда
                // потеряются, следующий инвентарь попросит их снова.
                wanted = wanted.filter { it >= leg.plainWindowEnd }.toLongArray()
            }
            leg.wanted = wanted
            Log.i(TAG, "custody: ${from.takeLast(8)} wants ${wanted.size} chunk(s) of $transferIdHex from me (has $contiguous)")
            // Инвентарь - явная просьба: первую порцию по нему шлём сразу
            // (получатель шлёт инвентарь не чаще раза в 10 с), дальше порции
            // отмеряют его подтверждения (onRecipientAck).
            if (wanted.isNotEmpty() && isOnline(from)) pushForward(row, leg, now)
        }
    }

    /** Отправитель сообщил, что получатель всё подтвердил (этап 8): копия больше не нужна. */
    suspend fun onOriginRelease(transferIdHex: String, from: String) {
        mutex.withLock {
            val row = transferDao.getTransfer(transferIdHex) ?: return
            if (row.direction != "CUSTODY" || row.originNodeId != from) return
            Log.i(TAG, "custody: origin ${from.takeLast(8)} released $transferIdHex, deleting copy")
            release(transferIdHex)
        }
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
                    } else if (leg.wanted != null) {
                        // Раздача по инвентарю (этап 8): префикс получателя
                        // двигают и другие хранители, поэтому следующую порцию
                        // отмеряем по его подтверждениям с небольшим шагом.
                        val wanted = leg.wanted ?: LongArray(0)
                        if (wanted.isNotEmpty() && isOnline(from) &&
                            (leg.lastPumpAtMs == 0L || now - leg.lastPumpAtMs >= WANT_PACE_MS || contiguous >= leg.sentUpTo)
                        ) {
                            // Порцию отмеряет подтверждение на наш кусок:
                            // получатель шлёт его тому, от кого кусок пришёл.
                            pushForward(row, leg, now)
                        }
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
            sendWindow(row.peerNodeId, row, offer, leg.acked, offerOnly = leg.lastAckAtMs == 0L, wanted = leg.wanted)
        }
        val window = result.getOrNull()
        if (window == null) {
            Log.i(TAG, "custody: ${row.peerNodeId.takeLast(8)} not reachable directly: ${result.exceptionOrNull()?.message}")
            return 0
        }
        leg.sentUpTo = window.end
        if (leg.wanted != null) {
            // Отправленное по инвентарю больше не шлём сами: если потерялось,
            // следующий инвентарь получателя попросит снова.
            leg.wanted = window.remainingWanted ?: LongArray(0)
        } else if (window.end > leg.acked) {
            leg.plainWindowEnd = window.end
            leg.plainWindowAtMs = now
        }
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
        val wanted = leg.wanted
        if (wanted != null && wanted.isEmpty()) {
            // Получатель просил пока ничего не слать (эти куски он берёт у
            // других). Напоминаем о себе одним предложением: если тот
            // хранитель пропал, получатель ответит новым инвентарём.
            return now - leg.lastPumpAtMs >= REPUMP_INTERVAL_MS
        }
        if (leg.acked >= leg.sentUpTo && leg.acked < chunkCount && leg.lastAckAtMs != 0L) return true
        val silent = leg.lastAckAtMs == 0L && leg.pumps >= SILENT_PUMPS_BEFORE_BACKOFF
        // Отвечал, но давно замолчал (получатель старой версии переключился
        // на другого хранителя и подтверждает только ему): не лить окно
        // каждые полминуты - редкий повтор, пока отправитель не отпустит.
        val stalled = leg.lastAckAtMs != 0L && now - leg.lastAckAtMs >= STALL_TIMEOUT_MS
        val interval = if (silent || stalled) SILENT_REPUMP_INTERVAL_MS else REPUMP_INTERVAL_MS
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
        /** Этап 8: конкретные куски по инвентарю получателя; null - окно от [acked]. */
        wanted: LongArray? = null,
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
        val perWindow = windowChunks(transfer.chunkSize)
        if (wanted != null) {
            // Инвентарь получателя: только просимое, не больше окна за раз.
            // Конец окна для таймера - последний отправленный номер + 1.
            val pending = wanted.filter { it >= acked && it < transfer.chunkCount }
            var last = acked
            var taken = 0
            for (chunkIndex in pending) {
                if (taken == perWindow) break
                sent += sendChunk(to, id, transferId, chunkIndex)
                last = maxOf(last, chunkIndex + 1)
                taken++
            }
            return Window(sent, last, pending.drop(taken).toLongArray())
        }
        val windowEnd = minOf(transfer.chunkCount, acked + perWindow.toLong())
        for (chunkIndex in acked until windowEnd) {
            sent += sendChunk(to, id, transferId, chunkIndex)
        }
        return Window(sent, windowEnd)
    }

    private fun sendChunk(to: String, id: String, transferId: ByteArray, chunkIndex: Long): Int {
        val ciphertext = chunkStore.readEncryptedChunk(id, chunkIndex)
            ?: throw IllegalStateException("Chunk $chunkIndex missing for $id")
        var sent = 0
        try {
            for (fragment in FileTransferPacketCodec.fragment(FileTransferPacketCodec.Type.CUSTODY_CHUNK, transferId, chunkIndex, ciphertext)) {
                check(directTransport(to, FileTransferWire.encodeEncodedPacket(fragment))) { "direct send failed" }
                sent++
            }
        } finally {
            ciphertext.fill(0)
        }
        return sent
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

        /** Сколько кусков помещается в одно окно прямого канала (та же арифметика, что у прямой передачи). */
        fun windowChunks(chunkSize: Int): Int {
            val fragmentsPerChunk = maxOf(
                1,
                (chunkSize + FileTransferChunkStore.AEAD_TAG_BYTES +
                    FileTransferPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES - 1) /
                    FileTransferPacketCodec.MAX_FRAGMENT_PAYLOAD_BYTES,
            )
            val budget = if (chunkSize >= FileTransferSender.LARGE_CHUNK_BYTES) {
                FileTransferSender.MAX_INFLIGHT_MESSAGES_LARGE
            } else {
                FileTransferSender.MAX_INFLIGHT_MESSAGES
            }
            return maxOf(1, budget / fragmentsPerChunk)
        }

        private val FINISHED_STATES = setOf("COMPLETE", "CANCELLED", "FAILED", "EXPIRED")
        /** Сколько копий раздать хранителям, пока отправитель в сети (этап 8). */
        const val TARGET_HOLDERS = 2
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
        /** Больше кусков из одного инвентаря не запоминаем: следующий инвентарь принесёт остальное. */
        const val MAX_WANTED_PER_INVENTORY = 4096
        /**
         * Раздача по инвентарю: порцию отмеряет подтверждение получателя на
         * НАШ кусок (он шлёт его тому, от кого кусок пришёл), это и есть
         * обратная связь; малый шаг лишь гасит залпы подтверждений.
         */
        const val WANT_PACE_MS = 250L
        /** Окно от префикса считается «в пути» столько: инвентарь за это время не просит его заново. */
        const val IN_FLIGHT_GRACE_MS = 15_000L
    }
}
