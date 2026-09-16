package com.vladimir.messenger.data.file

import android.util.Log
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Раздача ОБЩЕЙ копии файла группы нескольким просителям сразу (K2,
 * v11.70.25, `docs/CORE_ROADMAP.md`).
 *
 * До K2 сид готовил каждому просителю свою передачу: свой манифест, свой
 * ключ, заново зашифрованные куски - и куски двух сидов были несовместимы,
 * поэтому файл шёл только от одного. Теперь у файла группы один манифест с
 * меткой группы вместо получателя (`grp_<id>`, ядро
 * `create_group_file_manifest`), один ключ и одни куски: их держит автор
 * (строка `OUTGOING`/`SEEDING`, зашифровано один раз) и каждый, кто файл уже
 * получил (его входящая `COMPLETE`: куски и ключ у него остались от приёма).
 * Любой из них - сид, и все сиды отдают байт в байт одно и то же, так что
 * проситель качает разные куски у разных сидов и сливает в один файл.
 *
 * Здесь - сторона сида. На каждого просителя своё «плечо» ([Leg]): сначала
 * уходит только предложение (манифест + конверт с ключом файла, запечатанный
 * ДЛЯ ЭТОГО просителя, + моя подписанная привязка); куски - после первого
 * подтверждения, окном от подтверждённого префикса или по инвентарю
 * просителя ([onWant]): «у меня N подряд, пришли вот эти» - так несколько
 * сидов шлют разные полосы, а не одно и то же. Всё по прямому каналу
 * (LAN/QUIC, [directTransport]); гонять мегабайты через ретранслятор нельзя.
 * Подтверждения и инвентарь приходят через FileTransferReceiver
 * ([FileTransferReceiver.GroupSeedHooks]).
 *
 * Плечо без единого подтверждения дольше [OFFER_TIMEOUT_MS] или замолчавшее
 * на [STALL_TIMEOUT_MS] убирается: проситель, если ему ещё надо, попросит
 * снова (`fwant`). Больше [MAX_LEGS] плеч одновременно не держим - рой ставит
 * лишних просителей в очередь.
 */
class GroupFileSeeder(
    private val transferDao: FileTransferDao,
    private val chunkStore: FileTransferChunkStore,
    /** Прямой канал: (узел, текст) -> true, если ушло напрямую. */
    private val directTransport: (String, String) -> Boolean,
    private val ownBindingProvider: () -> ByteArray?,
    /** Конверт с ключом общей копии для просителя (OutgoingFilePreparationService.wrapGroupKey). */
    private val wrapKey: suspend (transferIdHex: String, requester: String) -> ByteArray,
    /**
     * Можно ли отдавать файл этой строки этому узлу (участник группы, не
     * забанен). Спрашивается, когда плечо поднимается не по просьбе роя (он
     * проверяет сам), а по подтверждению или инвентарю после перезапуска.
     */
    private val mayServe: suspend (row: FileTransferEntity, requester: String) -> Boolean = { _, _ -> true },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Ход раздачи одному просителю. */
    private class Leg(val transferIdHex: String, val requester: String, val startedAtMs: Long) {
        @Volatile var envelope: ByteArray? = null
        @Volatile var acked: Long = 0L
        @Volatile var lastAckAtMs: Long = 0L
        @Volatile var lastPumpAtMs: Long = 0L
        @Volatile var sentUpTo: Long = 0L
        @Volatile var pumps: Int = 0
        /** Инвентарь просителя: какие куски он ждёт именно от меня; null - от префикса. */
        @Volatile var wanted: LongArray? = null
        @Volatile var wantSeq: Long = -1L
        /** Конец последнего окна «от префикса» и когда оно ушло. */
        @Volatile var plainWindowEnd: Long = 0L
        @Volatile var plainWindowAtMs: Long = 0L
    }

    private class Window(val sent: Int, val end: Long, val remainingWanted: LongArray? = null)

    data class Summary(val legsPumped: Int, val packets: Int, val dropped: Int)

    private val mutex = Mutex()
    /** "transferId|requester" -> плечо. */
    private val legs = ConcurrentHashMap<String, Leg>()
    /** transferId -> просители, подтвердившие весь файл (для карточки автора; только память). */
    private val served = ConcurrentHashMap<String, MutableSet<String>>()
    /** Сообщить рою: просителю отдан весь файл (он сам теперь сид). */
    @Volatile var onServed: (transferIdHex: String, requester: String) -> Unit = { _, _ -> }
    @Volatile private var lastSweepAt = 0L

    /** Скольким просителям отдан весь файл этой копии. */
    fun servedCount(transferIdHex: String): Int = served[transferIdHex]?.size ?: 0

    fun activeLegs(): Int = legs.size

    /**
     * Годится ли строка как источник общей копии: манифест с меткой группы,
     * ключ и все куски на месте. Автор - `SEEDING`, получивший - `COMPLETE`.
     */
    suspend fun canSeed(transfer: FileTransferEntity, keyReady: (String) -> Boolean): Boolean {
        val seedRow = (transfer.direction == "OUTGOING" && transfer.state == "SEEDING") ||
            (transfer.direction == "INCOMING" && transfer.state == "COMPLETE")
        if (!seedRow || transfer.expiresAtMs <= nowMs()) return false
        val manifest = runCatching { chunkStore.readManifest(transfer.transferId) }.getOrNull() ?: return false
        if (!isGroupManifest(manifest)) return false
        if (!keyReady(transfer.transferId)) return false
        return runCatching { chunkStore.storedChunkIndices(transfer.transferId).size.toLong() == transfer.chunkCount }
            .getOrDefault(false)
    }

    /** Итог попытки предложить общую копию. */
    enum class OfferResult {
        /** Предложение ушло по прямому каналу; куски пойдут по подтверждению. */
        SENT,
        /** Ключ обмена просителя не закреплён - конверт не запечатать (рой просит HELLO и повторяет). */
        NO_KEY,
        /** Прямой канал к просителю не поднялся (он не в сети): пусть попросит снова. */
        UNREACHABLE,
        /** Все плечи заняты: просьба ждёт очереди. */
        BUSY,
    }

    /**
     * Предложить просителю общую копию. Только предложение: куски пойдут
     * после его подтверждения.
     */
    suspend fun offer(transfer: FileTransferEntity, requester: String): OfferResult = mutex.withLock {
        val id = transfer.transferId
        val key = legKey(id, requester)
        val now = nowMs()
        val existing = legs[key]
        if (existing != null) {
            // Повторная просьба того же: напомнить предложением, окно - по подтверждению.
            return@withLock if (pushLeg(transfer, existing, now) > 0) OfferResult.SENT else OfferResult.UNREACHABLE
        }
        if (legs.size >= MAX_LEGS) return@withLock OfferResult.BUSY
        val leg = Leg(id, requester, now)
        val wrapped = runCatching { wrapKey(id, requester) }
        val envelope = wrapped.getOrNull()
        if (envelope == null) {
            Log.w(TAG, "seed: cannot wrap key of $id for ${requester.takeLast(8)}: ${wrapped.exceptionOrNull()?.message}")
            return@withLock OfferResult.NO_KEY
        }
        leg.envelope = envelope
        legs[key] = leg
        val sent = pushLeg(transfer, leg, now)
        if (sent == 0) {
            legs.remove(key)
            return@withLock OfferResult.UNREACHABLE
        }
        Log.i(TAG, "seed: offered $id (${transfer.totalBytes} B) to ${requester.takeLast(8)}; legs=${legs.size}")
        OfferResult.SENT
    }

    /** Подтверждение просителя: двигает окно, закрывает плечо по полному файлу. */
    suspend fun onAck(transferIdHex: String, from: String, contiguous: Long) {
        mutex.withLock {
            val row = transferDao.getTransfer(transferIdHex) ?: return
            val now = nowMs()
            val key = legKey(transferIdHex, from)
            if (contiguous >= row.chunkCount) {
                val leg = legs.remove(key)
                val first = served.getOrPut(transferIdHex) { ConcurrentHashMap.newKeySet() }.add(from)
                if (leg != null || first) {
                    Log.i(TAG, "seed: $transferIdHex fully delivered to ${from.takeLast(8)} (served=${servedCount(transferIdHex)})")
                }
                if (first) runCatching { onServed(transferIdHex, from) }
                return
            }
            // Плечо могло пропасть при перезапуске: проситель подтверждает по
            // моему прежнему предложению - продолжаем с его префикса.
            val leg = legs[key] ?: (if (legs.size < MAX_LEGS) revive(row, from, now) else null) ?: return
            leg.lastAckAtMs = now
            if (contiguous > leg.acked) leg.acked = contiguous
            val wanted = leg.wanted
            if (wanted != null) {
                if (wanted.isNotEmpty() && (leg.lastPumpAtMs == 0L || now - leg.lastPumpAtMs >= WANT_PACE_MS || contiguous >= leg.sentUpTo)) {
                    pushLeg(row, leg, now)
                }
            } else if (contiguous >= leg.sentUpTo) {
                pushLeg(row, leg, now)
            }
        }
    }

    /** Инвентарь просителя: что именно слать ему (пусто - пока ничего, эти куски у других сидов). */
    suspend fun onWant(transferIdHex: String, from: String, contiguous: Long, seq: Long, ranges: List<LongRange>) {
        mutex.withLock {
            val row = transferDao.getTransfer(transferIdHex) ?: return
            val now = nowMs()
            val key = legKey(transferIdHex, from)
            val leg = legs[key] ?: (if (legs.size < MAX_LEGS) revive(row, from, now) else null) ?: return
            if (seq <= leg.wantSeq) return
            leg.wantSeq = seq
            leg.lastAckAtMs = now
            if (contiguous > leg.acked) leg.acked = minOf(contiguous, row.chunkCount)
            var wanted = FileCustodyPdu.expandRanges(ranges, row.chunkCount, MAX_WANTED_PER_INVENTORY)
            if (leg.plainWindowEnd > 0L && now - leg.plainWindowAtMs < IN_FLIGHT_GRACE_MS) {
                wanted = wanted.filter { it >= leg.plainWindowEnd }.toLongArray()
            }
            leg.wanted = wanted
            Log.i(TAG, "seed: ${from.takeLast(8)} wants ${wanted.size} chunk(s) of $transferIdHex from me (has $contiguous)")
            if (wanted.isNotEmpty()) pushLeg(row, leg, now)
        }
    }

    /** Проситель отказался (файл уже собран у него из других полос). */
    suspend fun onCancel(transferIdHex: String, from: String) {
        mutex.withLock {
            if (legs.remove(legKey(transferIdHex, from)) != null) {
                Log.i(TAG, "seed: ${from.takeLast(8)} declined $transferIdHex; leg closed")
            }
        }
    }

    /**
     * Раз в цикл насоса: повторить предложение молчащим плечам, убрать
     * безответные и замолчавшие, стереть истёкшие общие копии автора.
     */
    suspend fun pump(): Summary = mutex.withLock {
        val now = nowMs()
        var pumped = 0
        var packets = 0
        var dropped = 0
        for ((key, leg) in legs.entries.toList()) {
            val row = transferDao.getTransfer(leg.transferIdHex)
            if (row == null || row.expiresAtMs <= now) {
                legs.remove(key); dropped++; continue
            }
            val silentSince = if (leg.lastAckAtMs == 0L) leg.startedAtMs else leg.lastAckAtMs
            val limit = if (leg.lastAckAtMs == 0L) OFFER_TIMEOUT_MS else STALL_TIMEOUT_MS
            if (now - silentSince >= limit) {
                Log.i(TAG, "seed: ${leg.requester.takeLast(8)} silent for ${leg.transferIdHex}; leg dropped")
                legs.remove(key); dropped++; continue
            }
            if (!due(leg, row.chunkCount, now)) continue
            val sent = pushLeg(row, leg, now)
            if (sent > 0) {
                pumped++
                packets += sent
            }
        }
        if (now - lastSweepAt >= SWEEP_INTERVAL_MS) {
            lastSweepAt = now
            for (row in runCatching { transferDao.getSeeding() }.getOrDefault(emptyList())) {
                if (row.expiresAtMs > now) continue
                runCatching { chunkStore.deleteTransfer(row.transferId) }
                runCatching { transferDao.deleteTransfer(row.transferId) }
                served.remove(row.transferId)
                Log.i(TAG, "seed: expired group copy ${row.transferId} removed")
            }
        }
        Summary(pumped, packets, dropped)
    }

    /** Убрать все плечи и память об этой копии (группу покинули, копию стёрли). */
    suspend fun forget(transferIdHex: String) {
        mutex.withLock {
            legs.keys.removeAll { it.startsWith("$transferIdHex|") }
            served.remove(transferIdHex)
        }
    }

    // ── Внутреннее ───────────────────────────────────────────────────────────

    private fun legKey(transferIdHex: String, requester: String) = "$transferIdHex|$requester"

    /** Плечо для просителя, о котором мы забыли (перезапуск): конверт нужен заново. */
    private suspend fun revive(row: FileTransferEntity, requester: String, now: Long): Leg? {
        val manifest = runCatching { chunkStore.readManifest(row.transferId) }.getOrNull() ?: return null
        if (!isGroupManifest(manifest)) return null
        if (!runCatching { mayServe(row, requester) }.getOrDefault(false)) {
            Log.w(TAG, "seed: ${requester.takeLast(8)} may not receive ${row.transferId}; ignored")
            return null
        }
        val envelope = runCatching { wrapKey(row.transferId, requester) }.getOrNull() ?: return null
        val leg = Leg(row.transferId, requester, now)
        leg.envelope = envelope
        leg.lastAckAtMs = now
        legs[legKey(row.transferId, requester)] = leg
        return leg
    }

    private fun due(leg: Leg, chunkCount: Long, now: Long): Boolean {
        if (leg.lastPumpAtMs == 0L) return true
        val wanted = leg.wanted
        if (wanted != null && wanted.isEmpty()) return now - leg.lastPumpAtMs >= REPUMP_INTERVAL_MS
        if (leg.acked >= leg.sentUpTo && leg.acked < chunkCount && leg.lastAckAtMs != 0L) return true
        val silent = leg.lastAckAtMs == 0L && leg.pumps >= SILENT_PUMPS_BEFORE_BACKOFF
        val interval = if (silent) SILENT_REPUMP_INTERVAL_MS else REPUMP_INTERVAL_MS
        return now - leg.lastPumpAtMs >= interval
    }

    /** Предложение (+ окно, если рукопожатие состоялось) просителю. @return фрагментов ушло, 0 - канала нет. */
    private fun pushLeg(row: FileTransferEntity, leg: Leg, now: Long): Int {
        leg.lastPumpAtMs = now
        leg.pumps++
        val result = runCatching {
            val manifest = chunkStore.readManifest(row.transferId) ?: error("manifest missing")
            val envelope = leg.envelope ?: error("envelope missing")
            val binding = ownBindingProvider() ?: error("own binding missing")
            val offer = FileOfferPdu.encode(manifest, envelope, binding)
            sendWindow(leg.requester, row, offer, leg.acked, offerOnly = leg.lastAckAtMs == 0L, wanted = leg.wanted)
        }
        val window = result.getOrNull()
        if (window == null) {
            Log.i(TAG, "seed: ${leg.requester.takeLast(8)} not reachable directly for ${row.transferId}: ${result.exceptionOrNull()?.message}")
            return 0
        }
        leg.sentUpTo = window.end
        if (leg.wanted != null) {
            leg.wanted = window.remainingWanted ?: LongArray(0)
        } else if (window.end > leg.acked) {
            leg.plainWindowEnd = window.end
            leg.plainWindowAtMs = now
        }
        return window.sent
    }

    private fun sendWindow(
        to: String,
        transfer: FileTransferEntity,
        offer: ByteArray,
        acked: Long,
        offerOnly: Boolean,
        wanted: LongArray?,
    ): Window {
        val id = transfer.transferId
        val transferId = hexToBytes(id)
        var sent = 0
        for (fragment in FileTransferPacketCodec.fragment(FileTransferPacketCodec.Type.OFFER, transferId, 0L, offer)) {
            check(directTransport(to, FileTransferWire.encodeEncodedPacket(fragment))) { "direct send failed" }
            sent++
        }
        if (offerOnly) return Window(sent, acked)
        if (acked >= transfer.chunkCount) return Window(sent, transfer.chunkCount)
        val perWindow = FileCustodySender.windowChunks(transfer.chunkSize)
        if (wanted != null) {
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
            for (fragment in FileTransferPacketCodec.fragment(FileTransferPacketCodec.Type.CHUNK, transferId, chunkIndex, ciphertext)) {
                check(directTransport(to, FileTransferWire.encodeEncodedPacket(fragment))) { "direct send failed" }
                sent++
            }
        } finally {
            ciphertext.fill(0)
        }
        return sent
    }

    private fun hexToBytes(transferIdHex: String): ByteArray =
        ByteArray(16) { index -> transferIdHex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    companion object {
        private const val TAG = "GroupFileSeeder"

        /**
         * Метка группы в манифесте (K2): в канонических байтах после домена,
         * версии и id передачи идут отправитель и получатель с длиной в двух
         * байтах; получатель общей копии начинается с `grp_`. Разбор без
         * ядра, чтобы сидер оставался проверяемым JVM-тестом.
         */
        fun isGroupManifest(manifest: ByteArray): Boolean {
            val domain = "apu-file-manifest-v1\u0000".toByteArray(Charsets.US_ASCII)
            if (manifest.size < domain.size + 1 + 16 + 2) return false
            for (i in domain.indices) if (manifest[i] != domain[i]) return false
            var offset = domain.size + 1 + 16
            val senderLength = ((manifest[offset].toInt() and 0xff) shl 8) or (manifest[offset + 1].toInt() and 0xff)
            offset += 2 + senderLength
            if (manifest.size < offset + 2) return false
            val recipientLength = ((manifest[offset].toInt() and 0xff) shl 8) or (manifest[offset + 1].toInt() and 0xff)
            offset += 2
            if (recipientLength < 5 || manifest.size < offset + recipientLength) return false
            val prefix = FileTransferChatRouting.GROUP_SCOPE_PREFIX.toByteArray(Charsets.US_ASCII)
            for (i in prefix.indices) if (manifest[offset + i] != prefix[i]) return false
            return true
        }

        /** Просителей одновременно: у каждого своё окно, канал делится между ними. */
        const val MAX_LEGS = 6
        /** Предложение без единого подтверждения дольше этого - проситель не принимает (или старой версии). */
        const val OFFER_TIMEOUT_MS = 2 * 60_000L
        /** Подтверждал, но замолчал: докачал у других или ушёл. */
        const val STALL_TIMEOUT_MS = 10 * 60_000L
        const val REPUMP_INTERVAL_MS = FileTransferSender.REPUMP_INTERVAL_MS
        const val SILENT_PUMPS_BEFORE_BACKOFF = 3
        const val SILENT_REPUMP_INTERVAL_MS = 5 * 60_000L
        const val SWEEP_INTERVAL_MS = 10 * 60_000L
        const val MAX_WANTED_PER_INVENTORY = FileCustodySender.MAX_WANTED_PER_INVENTORY
        const val WANT_PACE_MS = FileCustodySender.WANT_PACE_MS
        const val IN_FLIGHT_GRACE_MS = FileCustodySender.IN_FLIGHT_GRACE_MS
    }
}
