package com.vladimir.messenger.data.file

import android.util.Log
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferChunkEntity
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.p2p_core.FileTransferManifestFfi

/** TOFU pin boundary so the receiver stays JVM-testable without the native library. */
fun interface FileExchangePinner {
    /** @return true when this binding was pinned for the first time; throws on key change. */
    suspend fun pinFirstSeen(binding: ByteArray, nowMs: Long): Boolean
}

/**
 * Receiver-side ingest for direct file transfers (F3). All packet items arrive as ordinary
 * durable text messages; this class reassembles the bounded fragments, verifies the offer
 * (manifest authenticity via the signed key envelope, recipient identity, expiry), stores
 * encrypted chunks durably, replies with deterministic file-ACKs the sender windows against,
 * and only exposes plaintext after the final whole-file SHA-256 verification.
 *
 * Nothing here trusts packet text for authorization: a wrong sender, a foreign recipient, an
 * expired offer, a geometry mismatch or a tampered fragment fails closed and is dropped.
 */
class FileTransferReceiver(
    private val transferDao: FileTransferDao,
    private val chunkStore: FileTransferChunkStore,
    private val receivedStore: ReceivedFileStore,
    private val pinner: FileExchangePinner,
    private val crypto: FileCryptoGateway,
    private val keyVault: TransferKeyVaultAccess,
    private val identity: LocalExchangeIdentity,
    private val transport: PacketTransport,
    private val ackSink: suspend (transferIdHex: String, contiguousChunks: Long) -> Unit,
    private val notifier: FileChatNotifier,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Хранение чужих файлов и приём через хранителя (этап 7 роя); по умолчанию всё отклоняется. */
    private val custody: CustodyPolicy = CustodyPolicy(),
    /**
     * Куда класть новое предложение (рой, этап 9: файл группы). Участник
     * группы попросил файл у другого участника, и тот шлёт обычную передачу;
     * общего чата у них может не быть, а прямой кадр несёт вместо чата метку
     * `direct`. По умолчанию - «не знаю», и решает транспортный чат.
     */
    private val routeOffer: suspend (senderId: String, fileSha256Hex: String) -> OfferRouting = { _, _ -> OfferRouting.Unknown },
    /** Раздача общей копии файла группы (K2): подтверждения, инвентарь и отказы просителей - сидеру. */
    private val groupSeed: GroupSeedHooks = GroupSeedHooks(),
    /**
     * K3: FCAP от собеседника (он принял наше предложение и понимает
     * APUF-кадры). Вызывается, когда МЫ отправитель: передатчик переводит
     * передачу на бинарный канал с следующего цикла.
     */
    private val onFcap: suspend (transferIdHex: String, from: String, maxFramePayload: Int) -> Unit =
        { _, _, _ -> },
) {
    /**
     * Границы сидера общей копии файла группы (K2, v11.70.25,
     * [GroupFileSeeder]). Строка `OUTGOING`/`SEEDING` - не передача одному
     * получателю, а копия для всех участников: ACK, инвентарь (WANT) и CANCEL
     * по ней приходят от разных узлов и уходят туда, а не в обычный передатчик.
     */
    class GroupSeedHooks(
        val onAck: suspend (transferIdHex: String, from: String, contiguous: Long) -> Unit = { _, _, _ -> },
        val onWant: suspend (transferIdHex: String, from: String, contiguous: Long, seq: Long, ranges: List<LongRange>) -> Unit =
            { _, _, _, _, _ -> },
        val onCancel: suspend (transferIdHex: String, from: String) -> Unit = { _, _ -> },
        /**
         * Я (проситель) принял предложение общей копии от очередного сида:
         * рой может позвать ещё сидов на полосы этого же файла. [seedCount] -
         * сколько сидов уже шлют.
         */
        val onOfferAccepted: suspend (chatId: String, seedId: String, fileSha256Hex: String, seedCount: Int) -> Unit =
            { _, _, _, _ -> },
    )

    /** Ответ на вопрос «куда класть предложение файла с таким хэшем от этого узла». */
    sealed class OfferRouting {
        /** Файл группы: строка передачи ложится в этот чат (id группы). */
        data class Chat(val chatId: String) : OfferRouting()
        /**
         * Такой файл уже идёт от другого сида (или уже получен): второе
         * предложение не нужно. Отправителю уходит CANCEL с меткой [chatId]
         * (этап 10), чтобы он не слал куски в пустоту до конца срока.
         */
        data class Duplicate(val chatId: String) : OfferRouting()
        /** Не файл группы: как обычно, по транспортному чату. */
        object Unknown : OfferRouting()
    }

    /**
     * Границы политики хранения у третьего телефона. Приёмник сам решает
     * только про подлинность и геометрию; «кого пускать», «сколько места»
     * и «в какой чат» приходят снаружи, чтобы JVM-тесты жили без Android.
     */
    class CustodyPolicy(
        /** Берём ли на хранение файл этого отправителя (в приложении - он наш контакт). */
        val acceptsFrom: suspend (originId: String) -> Boolean = { false },
        /** Сколько байт ещё можно записать в хранилище кусков (квота «Место под пересылку»). */
        val headroomBytes: () -> Long = { 0L },
        /** Чат с отправителем пересланного файла на ЭТОМ телефоне; null - отправитель не контакт. */
        val chatIdFor: suspend (originId: String) -> String? = { null },
        /** Подтверждение хранителя моей исходящей передаче (см. [FileCustodySender.onCustodianAck]). */
        val onCustodianAck: suspend (transferIdHex: String, from: String, contiguous: Long, status: Byte) -> Unit =
            { _, _, _, _ -> },
        /** Подтверждение получателя хранимой у меня передаче (см. [FileCustodySender.onRecipientAck]). */
        val onRecipientAck: suspend (transferIdHex: String, from: String, contiguous: Long, status: Byte) -> Unit =
            { _, _, _, _ -> },
        /** Инвентарь получателя хранимой у меня передаче (этап 8, см. [FileCustodySender.onRecipientWant]). */
        val onRecipientWant: suspend (transferIdHex: String, from: String, contiguous: Long, seq: Long, ranges: List<LongRange>) -> Unit =
            { _, _, _, _, _ -> },
        /** Отправитель отпускает хранимую у меня копию (этап 8, см. [FileCustodySender.onOriginRelease]). */
        val onOriginRelease: suspend (transferIdHex: String, from: String) -> Unit = { _, _ -> },
    )

    private class PendingItem {
        val fragments: MutableMap<Int, ByteArray> = HashMap()
        var fragmentCount: Int = -1
    }

    private val mutex = Mutex()
    private val pendingItems = LinkedHashMap<String, PendingItem>()
    private val bufferedChunks = LinkedHashMap<String, MutableMap<Long, ByteArray>>()
    /** One bounded progress cursor per active transfer; never one heap entry per file chunk. */
    private val contiguousPrefixes = HashMap<String, Long>()
    /**
     * Передачи через хранителя, по которым отправитель объявился и напрямую:
     * тогда подтверждения нужны обоим - хранителю (чтобы освободил место) и
     * отправителю (его окно двигают только обычные ACK).
     */
    private val directFromOrigin = HashSet<String>()
    /** Номер последнего инвентаря по передаче (этап 8): строго растёт, даже если часы стоят. */
    private val wantSeq = HashMap<String, Long>()
    /** Когда инвентарь уходил в последний раз: не чаще [INVENTORY_INTERVAL_MS], кроме появления нового хранителя. */
    private val inventorySentAt = HashMap<String, Long>()
    /** transferId -> (хранитель -> когда от него последний раз пришёл кусок): кто из хранителей жив. */
    private val holderSeenAt = HashMap<String, MutableMap<String, Long>>()
    /** transferId -> (хранитель -> когда его последний раз просили о кусках): молчащий после просьбы - выбыл. */
    private val holderAskedAt = HashMap<String, MutableMap<String, Long>>()
    /** transferId -> хранитель, говоривший с нами последним: подтверждение идёт ему, остальным - реже. */
    private val lastHolderHeard = HashMap<String, String>()
    /**
     * Файл группы полосами (K2): transferId -> сиды, приславшие предложение с
     * общим манифестом (`grp_`), в порядке появления. Подтверждения и
     * инвентарь недостающего идут им всем; кто прислал кусок последним и
     * когда - в [holderSeenAt]/[lastHolderHeard] (те же карты, что у
     * хранителей: механика дележа одна). Только в памяти: после перезапуска
     * сиды представятся снова (предложение повторяется каждые полминуты).
     */
    private val groupSeeds = HashMap<String, LinkedHashSet<String>>()
    /**
     * Передачи, от которых я отказался (этап 10: файл группы уже идёт от
     * другого сида). Их куски, долетающие следом за предложением, не
     * буферизуются, а сразу отбрасываются. Небольшой список, старое вытесняется.
     */
    private val declined = object : LinkedHashMap<String, Boolean>(32, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > MAX_DECLINED
    }
    private var pendingBytes = 0L

    /** Returns true when the text was a file packet (caller must not store it as chat text). */
    suspend fun onIncomingText(senderId: String, chatId: String, messageId: String, text: String): Boolean {
        if (FileTransferWire.isHelloText(text)) {
            onHelloText(senderId, text)
            return true
        }
        if (!FileTransferWire.isFilePacketText(text)) return false
        mutex.withLock {
            runCatching {
                val packet = FileTransferPacketCodec.decode(FileTransferWire.decodeToEncodedPacket(text))
                val transferIdHex = FileTransferWire.transferIdHexFromPacket(packet)
                collectFragment(senderId, chatId, transferIdHex, packet)
            }.onFailure { error ->
                Log.w(TAG, "Dropped malformed file packet from $senderId: ${error.message}")
            }
        }
        return true
    }

    enum class HelloResult { NOT_HELLO, PINNED_NEW, PINNED_ALREADY, REJECTED }

    /**
     * File-HELLO handshake: a tiny durable message carrying only the sender's signed exchange
     * binding. Verified against the message sender, TOFU-pinned, and the caller auto-replies
     * with its own HELLO on a first-time pin so both sides end up pinned. Breaks the
     * first-file deadlock without weakening the pin (a changed key still throws and rejects).
     */
    suspend fun onHelloText(senderId: String, text: String): HelloResult {
        if (!FileTransferWire.isHelloText(text)) return HelloResult.NOT_HELLO
        mutex.withLock {
            try {
                val binding = FileTransferWire.decodeHelloBinding(text)
                if (!crypto.verifyBinding(binding) || crypto.bindingNodeId(binding) != senderId) {
                    Log.w(TAG, "File HELLO from $senderId failed verification; dropped")
                    return HelloResult.REJECTED
                }
                val newlyPinned = pinner.pinFirstSeen(binding, nowMs())
                Log.i(
                    TAG,
                    "File HELLO from $senderId: ${if (newlyPinned) "pinned (new)" else "already pinned"}",
                )
                return if (newlyPinned) HelloResult.PINNED_NEW else HelloResult.PINNED_ALREADY
            } catch (error: Exception) {
                // Самый частый случай - собеседник переустановил приложение и
                // прислал НОВЫЙ ключ, а у нас закреплён старый. Пин намеренно
                // не сбрасывается сам: так подмену ключа не отличить от
                // переустановки. Лечится пересканированием QR собеседника,
                // поэтому подсказка пишется прямо здесь.
                val changed = error.message?.contains("key changed") == true
                if (changed) {
                    Log.w(
                        TAG,
                        "File HELLO from $senderId REJECTED: exchange key changed " +
                            "(peer reinstalled?). Messages to this peer cannot be sealed " +
                            "until the QR code is scanned again.",
                    )
                } else {
                    Log.w(TAG, "File HELLO from $senderId rejected: ${error.message}")
                }
                return HelloResult.REJECTED
            }
        }
    }

    private suspend fun collectFragment(
        senderId: String,
        chatId: String,
        transferIdHex: String,
        packet: FileTransferPacketCodec.Packet,
    ) {
        val key = "${transferIdHex}|${packet.wireVersion}|${packet.type.wire}|${packet.itemIndex}"
        val pending = pendingItems[key] ?: PendingItem().also {
            pendingItems[key] = it
            evictOldestIfOverBudget()
        }
        check(pending.fragmentCount == -1 || pending.fragmentCount == packet.fragmentCount) {
            "Fragment count conflict for file item"
        }
        val ownedFragment = packet.payload.copyOf()
        val existing = pending.fragments.putIfAbsent(packet.fragmentIndex, ownedFragment)
        if (existing != null) {
            ownedFragment.fill(0)
            check(existing.contentEquals(packet.payload)) { "Conflicting duplicate file fragment" }
            return
        }
        pending.fragmentCount = packet.fragmentCount
        pendingBytes = Math.addExact(pendingBytes, packet.payload.size.toLong())
        evictOldestIfOverBudget()
        if (pendingItems[key] !== pending) return
        if (pending.fragments.size < packet.fragmentCount) return

        // Complete item: re-encode fragments and strictly reassemble.
        val encodedFragments = IntRange(0, packet.fragmentCount - 1).map { fragment ->
            FileTransferPacketCodec.encode(
                FileTransferPacketCodec.Packet(
                    packet.type,
                    hexToBytes(transferIdHex),
                    packet.itemIndex,
                    fragment,
                    packet.fragmentCount,
                    pending.fragments.getValue(fragment),
                    packet.wireVersion,
                )
            )
        }
        releasePending(key, pending)
        val payload = FileTransferPacketCodec.reassemble(encodedFragments)
        try {
            when (packet.type) {
                FileTransferPacketCodec.Type.OFFER -> handleOffer(senderId, chatId, payload)
                FileTransferPacketCodec.Type.CHUNK -> handleChunk(senderId, transferIdHex, packet.itemIndex, payload)
                FileTransferPacketCodec.Type.ACK ->
                    handleAck(senderId, transferIdHex, packet.itemIndex, payload)
                FileTransferPacketCodec.Type.CANCEL -> handleCancel(senderId, transferIdHex, payload)
                FileTransferPacketCodec.Type.CUSTODY_OFFER -> handleCustodyOffer(senderId, payload)
                FileTransferPacketCodec.Type.CUSTODY_CHUNK ->
                    handleCustodyChunk(senderId, transferIdHex, packet.itemIndex, payload)
                FileTransferPacketCodec.Type.CUSTODY_ACK ->
                    handleCustodyAck(senderId, transferIdHex, packet.itemIndex, payload)
                FileTransferPacketCodec.Type.CUSTODY_WANT ->
                    handleCustodyWant(senderId, transferIdHex, packet.itemIndex, payload)
                FileTransferPacketCodec.Type.WANT ->
                    handleGroupWant(senderId, transferIdHex, packet.itemIndex, payload)
                FileTransferPacketCodec.Type.FCAP ->
                    handleFcap(senderId, transferIdHex, payload)
            }
        } finally {
            payload.fill(0)
        }
    }

    /**
     * K3: собеседник (получатель нашего предложения) подтвердил, что
     * понимает бинарные APUF-кадры. Принимаем только от адресата нашей
     * исходящей передачи; чужой FCAP - мусор (как и чужой ACK).
     */
    private suspend fun handleFcap(senderId: String, transferIdHex: String, payload: ByteArray) {
        val transfer = transferDao.getTransfer(transferIdHex)
        if (transfer == null || transfer.direction != "OUTGOING" || transfer.peerNodeId != senderId) {
            Log.w(TAG, "File FCAP for unknown/foreign transfer $transferIdHex from ${senderId.takeLast(8)}; dropped")
            return
        }
        val maxFramePayload = when (payload.size) {
            0 -> FileTransferWire.BINARY_MAX_FRAME_PAYLOAD
            4 -> ((payload[0].toInt() and 0xff) shl 24) or
                ((payload[1].toInt() and 0xff) shl 16) or
                ((payload[2].toInt() and 0xff) shl 8) or
                (payload[3].toInt() and 0xff)
            else -> {
                Log.w(TAG, "Malformed FCAP payload (${payload.size} bytes) from ${senderId.takeLast(8)}; dropped")
                return
            }
        }
        runCatching { onFcap(transferIdHex, senderId, maxFramePayload) }
            .onFailure { Log.w(TAG, "FCAP hook failed for $transferIdHex: ${it.message}") }
        Log.i(TAG, "File transfer $transferIdHex is binary-capable (max frame ${maxFramePayload})")
    }

    /**
     * K3: сообщить отправителю, что приёмник понимает APUF-кадры. Текстовый
     * пакет (маленький, с дедупликацией): старому отправителю FCAP неизвестен
     * и будет молча отброшен — он продолжит слать текстовые фрагменты, и
     * ничего не теряется.
     */
    private suspend fun sendFcap(transferIdHex: String, to: String, chatId: String) {
        runCatching {
            val maxFrame = FileTransferWire.BINARY_MAX_FRAME_PAYLOAD
            val payload = byteArrayOf(
                (maxFrame ushr 24).toByte(),
                (maxFrame ushr 16).toByte(),
                (maxFrame ushr 8).toByte(),
                maxFrame.toByte(),
            )
            val packet = FileTransferPacketCodec.encode(
                FileTransferPacketCodec.Packet(
                    FileTransferPacketCodec.Type.FCAP,
                    hexToBytes(transferIdHex),
                    0L,
                    0,
                    1,
                    payload,
                )
            )
            transport.send(
                FileTransferWire.fcapMessageId(transferIdHex),
                chatId,
                to,
                FileTransferWire.encodeEncodedPacket(packet),
            )
        }.onFailure { error ->
            Log.w(TAG, "File FCAP send failed for $transferIdHex: ${error.message}")
        }
    }

    private fun releasePending(key: String, item: PendingItem) {
        pendingItems.remove(key)
        pendingBytes -= item.fragments.values.sumOf { it.size }
        item.fragments.values.forEach { it.fill(0) }
        item.fragments.clear()
    }

    private fun evictOldestIfOverBudget() {
        while (pendingItems.size > MAX_PENDING_ITEMS || pendingBytes > MAX_PENDING_BYTES) {
            val oldestKey = pendingItems.keys.firstOrNull() ?: break
            releasePending(oldestKey, pendingItems.getValue(oldestKey))
            Log.w(TAG, "Evicted stale file item buffer $oldestKey")
        }
    }

    /**
     * Получатель отказался от моей исходящей передачи (этап 10: файл группы
     * уже пришёл от другого сида). Останавливаем её - помечаем CANCELLED и
     * убираем куски: иначе сид слал бы предложение до конца срока и держал
     * копию на диске. Только от адресата и только для исходящей; чужие и
     * старые (до v11.70.20 CANCEL лишь писался в журнал) - как раньше.
     */
    private suspend fun handleCancel(senderId: String, transferIdHex: String, payload: ByteArray) {
        if (!payload.contentEquals(CANCEL_DECLINED)) {
            Log.i(TAG, "File transfer CANCEL notice for $transferIdHex")
            return
        }
        val transfer = transferDao.getTransfer(transferIdHex)
        if (transfer != null && isSeedRow(transfer, senderId)) {
            // Общая копия файла группы (K2): отказался один проситель, копия
            // остаётся для остальных - сидер лишь перестаёт слать ему.
            groupSeed.onCancel(transferIdHex, senderId)
            return
        }
        if (transfer == null || transfer.direction != "OUTGOING" || transfer.peerNodeId != senderId) {
            Log.w(TAG, "Unauthorized file CANCEL from ${senderId.takeLast(8)} for $transferIdHex; dropped")
            return
        }
        if (transfer.state == "COMPLETE" || transfer.state == "CANCELLED") return
        val updated = advance(transfer, newState = "CANCELLED", errorCode = "DECLINED")
        if (updated == 1) {
            runCatching { chunkStore.deleteTransfer(transferIdHex) }
            Log.i(TAG, "File transfer $transferIdHex declined by ${senderId.takeLast(8)}; cancelled")
        }
    }

    /**
     * Отказаться от уже заведённой входящей передачи (этап 10: тот же файл
     * группы пришёл от другого сида). Отправителю уходит CANCEL; строку и
     * куски убирает вызывающий (FileTransferRouter.declineIncoming).
     */
    suspend fun declineTransfer(transfer: FileTransferEntity) {
        if (transfer.direction != "INCOMING" || transfer.state == "COMPLETE") return
        val seeds = mutex.withLock {
            declined[transfer.transferId] = true
            contiguousPrefixes.remove(transfer.transferId)
            bufferedChunks.remove(transfer.transferId)?.values?.forEach { it.fill(0) }
            groupSeeds.remove(transfer.transferId)?.toList().orEmpty()
        }
        // Общая копия (K2): отказ - каждому сиду, что слал полосы.
        for (seed in seeds) sendCancel(transfer.transferId, transfer.chatId, seed)
        if (transfer.peerNodeId !in seeds) sendCancel(transfer.transferId, transfer.chatId, transfer.peerNodeId)
    }

    /** Отказ от предложения: получателю этот файл уже не нужен (см. [handleCancel]). */
    private suspend fun sendCancel(transferIdHex: String, chatId: String, to: String) {
        runCatching {
            val packet = FileTransferPacketCodec.encode(
                FileTransferPacketCodec.Packet(
                    FileTransferPacketCodec.Type.CANCEL,
                    hexToBytes(transferIdHex),
                    0L,
                    0,
                    1,
                    CANCEL_DECLINED,
                )
            )
            transport.send(
                FileTransferWire.cancelMessageId(transferIdHex),
                chatId,
                to,
                FileTransferWire.encodeEncodedPacket(packet),
            )
        }.onFailure { error ->
            Log.w(TAG, "File CANCEL send failed for $transferIdHex: ${error.message}")
        }
    }

    private suspend fun handleAck(
        senderId: String,
        transferIdHex: String,
        contiguousChunks: Long,
        payload: ByteArray,
    ) {
        if (!payload.contentEquals(byteArrayOf(1))) {
            Log.w(TAG, "Malformed file ACK payload for $transferIdHex; dropped")
            return
        }
        val transfer = transferDao.getTransfer(transferIdHex)
        if (transfer != null && isSeedRow(transfer, senderId)) {
            // Общая копия файла группы (K2): подтверждения приходят от разных
            // просителей, окно каждому ведёт сидер.
            if (contiguousChunks in 0L..transfer.chunkCount) groupSeed.onAck(transferIdHex, senderId, contiguousChunks)
            return
        }
        if (transfer == null || transfer.direction != "OUTGOING" || transfer.peerNodeId != senderId) {
            Log.w(TAG, "Unauthorized file ACK from $senderId for $transferIdHex; dropped")
            return
        }
        if (contiguousChunks !in 0L..transfer.chunkCount) {
            Log.w(TAG, "Out-of-range file ACK $contiguousChunks for $transferIdHex; dropped")
            return
        }
        ackSink(transferIdHex, contiguousChunks)
        // Получатель подтвердил весь файл - хранителям копии больше не нужны
        // (этап 8: получатель старой версии подтверждает только одному из них,
        // остальные иначе держали бы файл до срока). Id детерминирован: повтор
        // итогового ACK повторит и это, сеть отсеет дубли.
        if (contiguousChunks >= transfer.chunkCount) {
            for (holder in FileCustodyPdu.holders(transfer.custodianNodeId)) {
                sendCustodyAck(holder, transferIdHex, contiguousChunks, FileCustodyPdu.ACK_RELEASE, holderTag = holder)
            }
        }
    }

    private suspend fun handleOffer(senderId: String, chatId: String, payload: ByteArray) {
        val offer = FileOfferPdu.decode(payload)
        val manifest = crypto.parseManifest(offer.manifest)
        if (manifest.fileSize > Long.MAX_VALUE.toULong() ||
            manifest.chunkCount > Long.MAX_VALUE.toULong()
        ) {
            Log.w(TAG, "File offer geometry exceeds Android durable range; dropped")
            return
        }
        val now = nowMs()
        // Общий манифест файла группы (K2): отправитель в нём - автор копии,
        // а предложение шлёт любой сид; получатель - метка группы, а не я.
        // Подлинность сида даёт его подписанный ключ обмена (ниже) и конверт
        // ключа, запечатанный именно им; принадлежность к группе - рой
        // (routeOffer): чужому файл не отдадут, а метка сверяется с группой.
        val groupOffer = FileTransferChatRouting.isGroupScope(manifest.recipientNodeId)
        if (!groupOffer && manifest.senderNodeId != senderId) {
            Log.w(TAG, "File offer sender mismatch: ${manifest.senderNodeId} != $senderId")
            return
        }
        val myNodeId = identity.myNodeId()
        if (myNodeId == null || (!groupOffer && manifest.recipientNodeId != myNodeId)) {
            Log.w(TAG, "File offer addressed to another node; dropped")
            return
        }
        if (manifest.expiresAtMs <= now) {
            Log.w(TAG, "File offer expired at ${manifest.expiresAtMs}; dropped")
            return
        }
        if (!crypto.verifyBinding(offer.senderBinding) || crypto.bindingNodeId(offer.senderBinding) != senderId) {
            Log.w(TAG, "File offer sender binding failed verification; dropped")
            return
        }
        try {
            pinner.pinFirstSeen(offer.senderBinding, now)
        } catch (pinError: Exception) {
            Log.w(TAG, "File offer rejected: pinned exchange key changed for $senderId (${pinError.message})")
            return
        }

        val transferIdHex = manifest.transferIdHex
        val existing = transferDao.getTransfer(transferIdHex)
        // Общая копия (K2): один и тот же transferId приходит от нескольких
        // сидов - второй сид не конфликт, а ещё один источник тех же кусков.
        val sameGroupTransfer = groupOffer && existing != null && existing.direction == "INCOMING" &&
            existing.fileSha256 == manifest.fileSha256Hex
        if (existing != null && !sameGroupTransfer && (existing.direction != "INCOMING" || existing.peerNodeId != senderId)) {
            Log.w(TAG, "File offer conflicts with local transfer row; dropped")
            return
        }
        if (existing?.state == "FAILED") {
            Log.w(TAG, "File offer for already failed transfer; dropped")
            return
        }
        if (groupOffer && existing != null && existing.state == "COMPLETE") {
            // Уже всё есть (сид опоздал): ему достаточно итогового подтверждения.
            sendGroupAck(transferIdHex, senderId, existing.chunkCount)
            return
        }
        val knownGroupSeed = groupOffer && groupSeeds[transferIdHex]?.contains(senderId) == true
        // Файл группы (этап 9) ложится в группу, а не в личный чат с сидом;
        // без чата (отправитель не контакт, файла я не просил) предложение
        // отбрасывается: метка транспорта в базу попасть не должна.
        val targetChatId = existing?.chatId ?: when (val route = routeOffer(senderId, manifest.fileSha256Hex)) {
            is OfferRouting.Chat -> route.chatId
            is OfferRouting.Duplicate -> {
                Log.i(TAG, "File offer $transferIdHex from ${senderId.takeLast(8)}: same file already coming; declined")
                declined[transferIdHex] = true
                bufferedChunks.remove(transferIdHex)?.values?.forEach { it.fill(0) }
                sendCancel(transferIdHex, route.chatId, senderId)
                return
            }
            OfferRouting.Unknown -> if (groupOffer) {
                null // общий манифест принимается только по слову роя
            } else {
                chatId.takeIf { it.isNotBlank() && it != FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE }
            }
        }
        if (targetChatId == null) {
            Log.w(TAG, "File offer $transferIdHex from ${senderId.takeLast(8)} has no chat here; dropped")
            return
        }
        if (groupOffer && manifest.recipientNodeId != FileTransferChatRouting.groupScope(targetChatId)) {
            Log.w(TAG, "Group file offer $transferIdHex carries scope of another group; dropped")
            return
        }
        if (groupOffer && existing != null && !knownGroupSeed) {
            // Ещё один сид той же общей копии (K2): тот же transferId, те же
            // куски. Рой подтверждает, что сид - участник или тот, кого я
            // просил («файл идёт» здесь не отказ, а «да, это тот файл»);
            // чужому - ничего.
            if (routeOffer(senderId, manifest.fileSha256Hex) is OfferRouting.Unknown) {
                Log.w(TAG, "Group file offer $transferIdHex from a stranger ${senderId.takeLast(8)}; dropped")
                return
            }
            val seeds = groupSeeds[transferIdHex].orEmpty()
            if (seeds.size >= MAX_GROUP_SEEDS) {
                Log.i(TAG, "Group file offer $transferIdHex from ${senderId.takeLast(8)}: enough seeds already; declined")
                sendCancel(transferIdHex, targetChatId, senderId)
                return
            }
        }
        val transfer = existing ?: insertIncomingTransfer(manifest, senderId, targetChatId, now) ?: return
        if (transfer.custodianNodeId.isNotBlank()) directFromOrigin.add(transferIdHex)
        // Раньше отказывались, теперь берём (первый сид пропал): куски снова нужны.
        declined.remove(transferIdHex)

        var newSeed = false
        if (groupOffer) {
            val seeds = groupSeeds.getOrPut(transferIdHex) { LinkedHashSet<String>() }
            if (senderId !in seeds) {
                seeds.add(senderId)
                newSeed = true
                inventorySentAt.remove(transferIdHex) // новый сид - инвентарь всем сразу
            }
            lastHolderHeard[transferIdHex] = senderId
        }
        Log.i(
            TAG,
            "File offer accepted: $transferIdHex from $senderId " +
                "(${manifest.displayName}, ${manifest.fileSize} B, ${manifest.chunkCount} chunks" +
                (if (groupOffer) "; group seeds=${groupSeeds[transferIdHex]?.size ?: 0}" else "") + ")",
        )
        chunkStore.storeManifest(transferIdHex, offer.manifest)
        if (groupOffer) {
            // Конверт у каждого сида свой (свежий nonce, его подпись): хранится
            // первый, ключ из него уже в хранилище; остальные несут тот же ключ.
            if (chunkStore.readKeyEnvelope(transferIdHex) == null) chunkStore.storeKeyEnvelope(transferIdHex, offer.keyEnvelope)
        } else {
            chunkStore.storeKeyEnvelope(transferIdHex, offer.keyEnvelope)
        }
        if (keyVault.mode(transferIdHex) != FileTransferKeyVault.Mode.READY) {
            openAndImportKey(transferIdHex, offer)
        }
        recoverDurableProgress(transferIdHex)
        if (groupOffer && newSeed) {
            val count = groupSeeds[transferIdHex]?.size ?: 1
            runCatching { groupSeed.onOfferAccepted(targetChatId, senderId, manifest.fileSha256Hex, count) }
                .onFailure { Log.w(TAG, "group offer hook failed: ${it.message}") }
        }

        // Chunks may have arrived before the offer: ingest them now.
        bufferedChunks.remove(transferIdHex)?.let { buffered ->
            for ((chunkIndex, ciphertext) in buffered.toSortedMap()) {
                ingestChunkCiphertext(transferIdHex, chunkIndex, ciphertext)
                ciphertext.fill(0)
            }
        }

        // K3: личным файлам — сказать отправителю про бинарный канал.
        // Файлы группы (K2) по нему не ходят: рою нужна личность сида,
        // которой в APUF-кадре нет, — остаются на текстовых фрагментах.
        if (!groupOffer) {
            sendFcap(transferIdHex, senderId, targetChatId)
        }

        val contiguous = contiguousReceived(transferIdHex)
        sendFileAck(transferIdHex, contiguous)
        val fresh = transferDao.getTransfer(transferIdHex) ?: return
        if (contiguous >= manifest.chunkCount.toLong()) {
            finalizeTransfer(fresh, manifest)
        } else if (fresh.state == "OFFERED") {
            advance(fresh, newState = "TRANSFERRING")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // K3: бинарные диапазоны зашифрованных кусков (APUF, событие
    // "file_chunk_received" из ядра)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Сборка одного куска из APUF-диапазонов. Диапазоны приходят разными
     * стримами и могут задержаться: порядок и дубли не имеют значения,
     * пересечения — брак (отправитель так не шлёт, а «так» — либо шум,
     * либо проба).
     */
    private class BinaryChunkAssembler(val expectedLen: Int) {
        val buffer = ByteArray(expectedLen)
        private val covered = mutableListOf<LongRange>()
        private var coveredBytes = 0L

        val isComplete: Boolean
            get() = coveredBytes == expectedLen.toLong()

        /** @return false — диапазон вне куска или пересекается с принятым. */
        fun addRange(offset: Int, data: ByteArray): Boolean {
            if (data.isEmpty()) return false
            val start = offset.toLong()
            val end = start + data.size.toLong()
            if (end > expectedLen.toLong()) return false
            for (range in covered) {
                // Диапазоны полуоткрытые [start, end): `covered` хранит
                // LongRange (закрытый, endInclusive = end-1), поэтому
                // пересечение проверяем через endInclusive.
                if (start <= range.endInclusive && range.start < end) return false
            }
            data.copyInto(buffer, offset)
            covered += start until end
            coveredBytes += data.size.toLong()
            return true
        }
    }

    private val binaryAssemblers = LinkedHashMap<String, BinaryChunkAssembler>()

    /**
     * K3: бинарный диапазон зашифрованного куска по прямому QUIC-каналу.
     *
     * Отправителя в кадре нет: личный кусок адресован собеседнику
     * передачи (peerNodeId) — это единственная адресация, которую мы
     * здесь проверяем. Подложные диапазоны не соберутся в подлинный
     * шифртекст: AES-GCM тег целого куска проверяется в
     * [ingestChunkCiphertext], и испорченный кусок на диск не ложится.
     */
    suspend fun onBinaryChunk(
        transferIdHex: String,
        chunkIndex: Long,
        chunkOffset: Int,
        ciphertextChunkLen: Int,
        ciphertextRange: ByteArray,
    ) {
        if (chunkIndex < 0L || chunkOffset < 0 || ciphertextChunkLen <= 0 || ciphertextRange.isEmpty()) return
        if (ciphertextChunkLen < FileTransferChunkStore.AEAD_TAG_BYTES + 1) {
            Log.w(TAG, "Binary chunk for $transferIdHex too short (${ciphertextChunkLen}); dropped")
            return
        }
        if (ciphertextRange.size > ciphertextChunkLen - chunkOffset) {
            Log.w(TAG, "Binary range for $transferIdHex exceeds chunk bounds; dropped")
            return
        }
        mutex.withLock {
            if (declined.containsKey(transferIdHex)) return
            val key = "$transferIdHex|$chunkIndex"
            var assembler = binaryAssemblers[key]
            if (assembler == null || assembler.expectedLen != ciphertextChunkLen) {
                assembler?.buffer?.fill(0)
                assembler = BinaryChunkAssembler(ciphertextChunkLen).also {
                    binaryAssemblers[key] = it
                    evictBinaryAssemblers()
                }
            }
            if (!assembler.addRange(chunkOffset, ciphertextRange)) {
                Log.w(TAG, "Binary range overlap for $key at offset $chunkOffset; dropped")
                return
            }
            if (!assembler.isComplete) return
            // Кусок собран целиком: дальше ровно тот же путь, что и для
            // склеенных текстовых фрагментов — аутентификация, диск, ACK.
            val assembled = assembler.buffer
            binaryAssemblers.remove(key)
            evictBinaryAssemblers()
            val transfer = transferDao.getTransfer(transferIdHex)
            if (transfer == null || transfer.direction != "INCOMING") {
                // Оффер ещё не дошёл (или это не наша передача): кусок
                // ждёт оффера в том же буфере, что и текстовые.
                bufferOrDropChunk(transferIdHex, chunkIndex, assembled)
            } else {
                val manifestBytes = chunkStore.readManifest(transferIdHex)
                if (manifestBytes != null && GroupFileSeeder.isGroupManifest(manifestBytes)) {
                    Log.w(TAG, "Binary chunk of group transfer $transferIdHex; group files ride the text path")
                } else {
                    handleChunk(transfer.peerNodeId, transferIdHex, chunkIndex, assembled)
                }
            }
            assembled.fill(0)
        }
    }

    private fun evictBinaryAssemblers() {
        while (binaryAssemblers.size > MAX_BUFFERED_CHUNKS ||
            binaryAssemblers.values.sumOf { it.buffer.size.toLong() } > MAX_BUFFERED_CHUNK_BYTES
        ) {
            val oldestKey = binaryAssemblers.keys.firstOrNull() ?: break
            val assembler = binaryAssemblers.remove(oldestKey)
            assembler?.buffer?.fill(0)
            Log.w(TAG, "Evicted binary chunk assembler $oldestKey")
        }
    }

    private suspend fun handleChunk(
        senderId: String,
        transferIdHex: String,
        chunkIndex: Long,
        ciphertext: ByteArray,
    ) {
        val transfer = transferDao.getTransfer(transferIdHex)
        val manifestBytes = chunkStore.readManifest(transferIdHex)
        if (transfer == null || manifestBytes == null) {
            if (declined.containsKey(transferIdHex)) return
            bufferOrDropChunk(transferIdHex, chunkIndex, ciphertext)
            return
        }
        if (transfer.direction == "CUSTODY") {
            // Обычные куски чужой передаче не адресуются: хранитель принимает
            // только CUSTODY_CHUNK от отправителя.
            Log.w(TAG, "Plain chunk for custody transfer $transferIdHex from ${senderId.takeLast(8)}; dropped")
            return
        }
        if (transfer.direction == "OUTGOING") {
            // Своей исходящей (в том числе общей копии группы, K2) куски не шлют.
            Log.w(TAG, "Plain chunk for own outgoing $transferIdHex from ${senderId.takeLast(8)}; dropped")
            return
        }
        val groupManifest = GroupFileSeeder.isGroupManifest(manifestBytes)
        if (groupManifest && transfer.state == "COMPLETE") {
            // Файл уже собран из полос, а этот сид ещё шлёт: итоговое
            // подтверждение ему лично, чтобы он закрыл плечо (K2).
            sendGroupAck(transferIdHex, senderId, transfer.chunkCount)
            return
        }
        if (groupManifest) {
            // Файл группы полосами (K2): кусок проверяется сразу ключом файла
            // - испорченный или подложный не ложится на диск и не портит
            // сборку, а приславший его сид выбывает из дележа. Сиды известны
            // только в памяти: после перезапуска кусок до повторного
            // предложения принимается от любого, но только подлинный.
            val seedsOfTransfer = groupSeeds[transferIdHex]
            if (!seedsOfTransfer.isNullOrEmpty() && senderId !in seedsOfTransfer) {
                Log.w(TAG, "Group chunk $chunkIndex for $transferIdHex from unexpected ${senderId.takeLast(8)}; dropped")
                return
            }
            if (!verifyGroupChunk(manifestBytes, transferIdHex, chunkIndex, ciphertext)) {
                Log.w(TAG, "Group chunk $chunkIndex for $transferIdHex from ${senderId.takeLast(8)} failed authentication; seed dropped")
                seedsOfTransfer?.remove(senderId)
                return
            }
        }
        // Кусок пришёл напрямую от отправителя, хотя файл идёт и через
        // хранителя: с этого момента подтверждаем обоим (см. sendFileAck).
        if (transfer.direction == "INCOMING" && transfer.custodianNodeId.isNotBlank() &&
            senderId == transfer.peerNodeId
        ) {
            directFromOrigin.add(transferIdHex)
        }
        // Файл группы полосами (K2): кто прислал кусок - тому подтверждение
        // (им он отмеряет окно), остальным сидам - с инвентарём.
        groupSeeds[transferIdHex]?.let { seeds ->
            if (senderId in seeds) {
                holderSeenAt.getOrPut(transferIdHex) { HashMap<String, Long>() }[senderId] = nowMs()
                lastHolderHeard[transferIdHex] = senderId
            }
        }
        ingestChunkCiphertext(transferIdHex, chunkIndex, ciphertext)
    }

    private fun bufferOrDropChunk(transferIdHex: String, chunkIndex: Long, ciphertext: ByteArray) {
        if (chunkIndex < 0L) return
        val buffered = bufferedChunks.getOrPut(transferIdHex) { LinkedHashMap() }
        // collectFragment wipes its reassembled payload after dispatch; retain an owned copy.
        val existing = buffered.put(chunkIndex, ciphertext.copyOf())
        if (existing != null) existing.fill(0)
        while (bufferedChunks.size > MAX_BUFFERED_TRANSFERS ||
            bufferedChunkCount() > MAX_BUFFERED_CHUNKS ||
            bufferedChunkBytes() > MAX_BUFFERED_CHUNK_BYTES
        ) {
            val oldestTransfer = bufferedChunks.keys.firstOrNull() ?: break
            bufferedChunks.remove(oldestTransfer)?.values?.forEach { it.fill(0) }
            Log.w(TAG, "Dropped pre-offer chunk buffer for $oldestTransfer")
        }
    }

    private fun bufferedChunkCount(): Int = bufferedChunks.values.sumOf { it.size }

    private fun bufferedChunkBytes(): Long {
        var total = 0L
        for (chunks in bufferedChunks.values) {
            for (ciphertext in chunks.values) {
                total = Math.addExact(total, ciphertext.size.toLong())
            }
        }
        return total
    }

    private suspend fun ingestChunkCiphertext(
        transferIdHex: String,
        chunkIndex: Long,
        ciphertext: ByteArray,
    ) {
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        if (transfer.state == "COMPLETE") {
            // Отправитель не получил итогового подтверждения и шлёт снова:
            // повторяем его (id детерминирован, сеть отсеет дубли), иначе
            // он будет качать в пустоту до конца срока.
            sendFileAck(transferIdHex, transfer.chunkCount)
            return
        }
        if (transfer.state == "FAILED") return
        val manifestBytes = chunkStore.readManifest(transferIdHex) ?: return
        val manifest = crypto.parseManifest(manifestBytes)
        val chunkCount = manifest.chunkCount.toLong()
        if (chunkIndex !in 0L until chunkCount) {
            Log.w(TAG, "Chunk index $chunkIndex out of range for $transferIdHex")
            return
        }
        if (manifest.expiresAtMs <= nowMs()) {
            Log.w(TAG, "Chunk for expired transfer $transferIdHex dropped")
            return
        }
        val expectedPlaintext = plaintextLengthOf(manifest, chunkIndex)
        if (ciphertext.size != expectedPlaintext + FileTransferChunkStore.AEAD_TAG_BYTES) {
            Log.w(TAG, "Chunk $chunkIndex geometry mismatch for $transferIdHex")
            return
        }
        val stored = try {
            chunkStore.storeEncryptedChunk(transferIdHex, chunkIndex, ciphertext)
        } catch (storeError: Exception) {
            Log.w(TAG, "Chunk $chunkIndex rejected by store for $transferIdHex: ${storeError.message}")
            // Место под пересылку кончилось: передача не падает (отправитель
            // повторит кусок, а владелец может подвинуть ползунок или
            // очистить завершённые), но пузырь в чате должен сказать, чего
            // ждём. Следующий удачный кусок снимает пометку сам (advance
            // без errorCode).
            if (storeError is FileTransferChunkStore.StorageFullException &&
                transfer.errorCode != ERROR_NO_SPACE
            ) {
                advance(transfer, newState = transfer.state, errorCode = ERROR_NO_SPACE)
            }
            return
        }
        val inserted = transferDao.insertChunkIgnore(
            FileTransferChunkEntity(
                transferId = transferIdHex,
                chunkIndex = chunkIndex,
                state = "RECEIVED",
                ciphertextBytes = stored.ciphertextBytes,
                chunkSha256 = stored.sha256,
                updatedAtMs = nowMs(),
            )
        ) != -1L
        val contiguous = advanceContiguousPrefix(transferIdHex)
        if (!inserted) {
            // Duplicate: repeat current ACK. No per-chunk in-memory set is retained.
            sendFileAck(transferIdHex, contiguous)
            return
        }

        val completedChunks = Math.addExact(transfer.completedChunks, 1L)
        val transferredBytes = Math.addExact(
            transfer.transferredBytes,
            plaintextLengthOf(manifest, chunkIndex).toLong(),
        )
        Log.i(
            TAG,
            "File chunk stored: $chunkIndex for $transferIdHex " +
                "($completedChunks/$chunkCount, contiguous=$contiguous)",
        )

        val updated = advance(
            transfer,
            newState = if (completedChunks == chunkCount) "VERIFYING" else "TRANSFERRING",
            completedChunks = completedChunks,
            transferredBytes = transferredBytes,
        )
        if (updated == 0) return

        sendFileAck(transferIdHex, contiguous)
        if (completedChunks == chunkCount) {
            finalizeTransfer(transfer, manifest)
        }
    }

    private suspend fun finalizeTransfer(
        transfer: FileTransferEntity,
        manifest: FileTransferManifestFfi,
    ) {
        val transferIdHex = transfer.transferId
        // Re-fetch: the row may have advanced (e.g. VERIFYING) since the caller loaded it, and
        // the monotonic Room guard must never see regressed progress numbers.
        val fresh = transferDao.getTransfer(transferIdHex) ?: return
        if (fresh.state == "COMPLETE") return
        val manifestBytes = chunkStore.readManifest(transferIdHex) ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        val writer = receivedStore.openWriter(
            transferIdHex,
            manifest.displayName,
            manifest.fileSize.toLong(),
        )
        try {
            keyVault.withExistingKey(transferIdHex) { fileKey ->
                for (chunkIndex in 0L until manifest.chunkCount.toLong()) {
                    val ciphertext = chunkStore.readEncryptedChunk(transferIdHex, chunkIndex)
                        ?: throw IllegalStateException("Missing chunk $chunkIndex at finalize")
                    try {
                        val plaintext = crypto.decryptChunk(manifestBytes, fileKey, chunkIndex, ciphertext)
                        try {
                            digest.update(plaintext)
                            writer.write(plaintext, plaintext.size)
                        } finally {
                            plaintext.fill(0)
                        }
                    } finally {
                        ciphertext.fill(0)
                    }
                }
            }
            if (digest.digest().toHex() != manifest.fileSha256Hex) {
                throw IllegalStateException("Whole-file SHA-256 mismatch")
            }
            writer.commit()
            check(advance(fresh, newState = "COMPLETE") == 1) {
                "Cannot persist verified file completion"
            }
            Log.i(TAG, "File transfer COMPLETE: $transferIdHex (${manifest.displayName})")
            directFromOrigin.remove(transferIdHex)
            wantSeq.remove(transferIdHex)
            inventorySentAt.remove(transferIdHex)
            holderSeenAt.remove(transferIdHex)
            holderAskedAt.remove(transferIdHex)
            lastHolderHeard.remove(transferIdHex)
            val finalSeeds = groupSeeds.remove(transferIdHex)
            notifier.onFileReceived(
                chatId = fresh.chatId,
                senderId = fresh.peerNodeId,
                messageId = FileTransferWire.chatPlaceholderMessageId(transferIdHex),
                displayName = manifest.displayName,
                mediaType = manifest.mediaType,
                totalBytes = manifest.fileSize.toLong(),
                fileSha256 = manifest.fileSha256Hex,
            )
            if (finalSeeds.isNullOrEmpty()) {
                sendFileAck(transferIdHex, manifest.chunkCount.toLong())
            } else {
                // Итоговое подтверждение - каждому сиду общей копии: по нему
                // сидер закрывает полосу просителю (K2).
                for (seed in finalSeeds) sendGroupAck(transferIdHex, seed, manifest.chunkCount.toLong())
            }
        } catch (error: Exception) {
            // abort() is a no-op after a successful commit (Writer guards its finished state).
            writer.abort()
            runCatching { receivedStore.deleteTransfer(transferIdHex) }
            advance(fresh, newState = "FAILED", errorCode = "VERIFY_FAILED")
            Log.w(TAG, "File verification failed for $transferIdHex: ${error.message}")
        }
    }

    private suspend fun openAndImportKey(transferIdHex: String, offer: FileOfferPdu.Offer) {
        val myBinding = identity.myBinding()
            ?: throw IllegalStateException("Local file exchange binding unavailable")
        val fileKey = identity.withSecret { secret ->
            crypto.openKeyEnvelope(offer.keyEnvelope, myBinding, secret, offer.manifest)
        } ?: throw IllegalStateException("Local file exchange secret unavailable")
        try {
            keyVault.importKey(transferIdHex, fileKey)
        } finally {
            fileKey.fill(0)
        }
    }

    private suspend fun recoverDurableProgress(transferIdHex: String) {
        if (contiguousPrefixes.containsKey(transferIdHex)) return
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        val completedChunks = transferDao.countChunks(transferIdHex)
        val transferredBytes = transferDao.receivedPlaintextBytes(transferIdHex)
        if (completedChunks > transfer.completedChunks || transferredBytes > transfer.transferredBytes) {
            advance(
                transfer,
                newState = transfer.state,
                completedChunks = completedChunks,
                transferredBytes = transferredBytes,
            )
        }
        contiguousPrefixes[transferIdHex] = discoverContiguousPrefix(transferIdHex)
    }

    private fun discoverContiguousPrefix(transferIdHex: String): Long {
        var contiguous = 0L
        while (chunkStore.hasEncryptedChunk(transferIdHex, contiguous)) {
            contiguous = Math.addExact(contiguous, 1L)
        }
        return contiguous
    }

    private fun advanceContiguousPrefix(transferIdHex: String): Long {
        var contiguous = contiguousPrefixes[transferIdHex]
            ?: discoverContiguousPrefix(transferIdHex)
        while (chunkStore.hasEncryptedChunk(transferIdHex, contiguous)) {
            contiguous = Math.addExact(contiguous, 1L)
        }
        contiguousPrefixes[transferIdHex] = contiguous
        return contiguous
    }

    private fun contiguousReceived(transferIdHex: String): Long =
        contiguousPrefixes[transferIdHex] ?: discoverContiguousPrefix(transferIdHex).also {
            contiguousPrefixes[transferIdHex] = it
        }

    private fun plaintextLengthOf(manifest: FileTransferManifestFfi, chunkIndex: Long): Int {
        val chunkSize = manifest.chunkSize.toLong()
        val offset = Math.multiplyExact(chunkIndex, chunkSize)
        return minOf(chunkSize, manifest.fileSize.toLong() - offset).toInt()
    }

    private suspend fun sendFileAck(transferIdHex: String, contiguousChunks: Long) {
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        val seeds = groupSeeds[transferIdHex]?.toList().orEmpty()
        if (seeds.isNotEmpty() && transfer.direction == "INCOMING") {
            sendGroupAcks(transfer, seeds, contiguousChunks)
            return
        }
        // Приём через хранителя: окно двигает подтверждение ЕМУ, а
        // отправителю (он, скорее всего, не в сети - потому и хранитель)
        // уходит только итоговое, чтобы не забивать очередь ретранслятора
        // сотней мелких подтверждений.
        val holders = if (transfer.direction == "INCOMING") FileCustodyPdu.holders(transfer.custodianNodeId) else emptyList()
        if (holders.isNotEmpty()) {
            // Этап 8: несколько хранителей - каждому свой список недостающего,
            // чтобы они не слали одно и то же. Инвентарь идёт ПЕРЕД
            // подтверждением: получив подтверждение, хранитель сразу шлёт
            // окно, и оно должно быть уже по инвентарю. С одним хранителем
            // инвентарь не нужен: он и так идёт от подтверждённого префикса.
            val complete = contiguousChunks >= transfer.chunkCount
            var inventoryNow = false
            if (holders.size > 1 && !complete) {
                val now = nowMs()
                val last = inventorySentAt[transferIdHex]
                if (last == null || now - last >= INVENTORY_INTERVAL_MS) {
                    inventorySentAt[transferIdHex] = now
                    inventoryNow = true
                    sendInventory(transfer, holders, contiguousChunks)
                }
            }
            // Подтверждение на каждый кусок - тому, кто его прислал (им он
            // отмеряет следующую порцию); остальным - вместе с инвентарём и
            // по завершении, чтобы не удваивать мелкие пакеты в очереди.
            val from = lastHolderHeard[transferIdHex]
            for (holder in holders) {
                if (complete || inventoryNow || from == null || holder == from) {
                    sendCustodyAck(holder, transferIdHex, contiguousChunks, FileCustodyPdu.ACK_OK, holderTag = holder)
                }
            }
            if (!complete && transferIdHex !in directFromOrigin) return
        }
        runCatching {
            val packet = FileTransferPacketCodec.encode(
                FileTransferPacketCodec.Packet(
                    FileTransferPacketCodec.Type.ACK,
                    hexToBytes(transferIdHex),
                    contiguousChunks,
                    0,
                    1,
                    byteArrayOf(1),
                )
            )
            transport.send(
                FileTransferWire.ackMessageId(transferIdHex, contiguousChunks),
                transfer.chatId,
                transfer.peerNodeId,
                FileTransferWire.encodeEncodedPacket(packet),
            )
        }.onFailure { error ->
            Log.w(TAG, "File ACK send failed for $transferIdHex: ${error.message}")
        }
    }

    // ── Файл группы полосами от нескольких сидов (K2, v11.70.25) ─────────────

    /**
     * Строка, из которой я раздаю общую копию файла группы: моя авторская
     * (`OUTGOING`/`SEEDING`) или полученная целиком (`INCOMING`/`COMPLETE` -
     * приём окончен, подтверждения и инвентарь по ней могут приходить только
     * от просителей). Что именно я отдаю (и отдаю ли), решает сидер: у
     * личной передачи манифест не групповой, и плеча он не откроет.
     */
    private fun isSeedRow(transfer: FileTransferEntity, @Suppress("UNUSED_PARAMETER") from: String): Boolean =
        (transfer.direction == "OUTGOING" && transfer.state == "SEEDING") ||
            (transfer.direction == "INCOMING" && transfer.state == "COMPLETE")

    /** Кусок общей копии подлинный: расшифровывается ключом файла под общим манифестом. */
    private fun verifyGroupChunk(manifestBytes: ByteArray, transferIdHex: String, chunkIndex: Long, ciphertext: ByteArray): Boolean {
        if (keyVault.mode(transferIdHex) != FileTransferKeyVault.Mode.READY) return true // ключ ещё не пришёл: проверит сборка
        return runCatching {
            keyVault.withExistingKey(transferIdHex) { fileKey ->
                crypto.decryptChunk(manifestBytes, fileKey, chunkIndex, ciphertext).fill(0)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Подтверждения сидам общей копии: каждому - своё (id с меткой сида,
     * иначе сеть отсеет второе как дубль), на каждый кусок - тому, кто его
     * прислал; остальным - вместе с инвентарём и по завершении. С двумя и
     * более сидами перед подтверждением уходит инвентарь недостающего
     * ([sendGroupInventory]): недостающее делится полосами, и сиды шлют
     * разные куски. Один сид инвентаря не получает: он идёт от префикса, как
     * обычная передача.
     */
    private suspend fun sendGroupAcks(transfer: FileTransferEntity, seeds: List<String>, contiguousChunks: Long) {
        val transferIdHex = transfer.transferId
        val complete = contiguousChunks >= transfer.chunkCount
        var inventoryNow = false
        if (seeds.size > 1 && !complete) {
            val now = nowMs()
            val last = inventorySentAt[transferIdHex]
            if (last == null || now - last >= INVENTORY_INTERVAL_MS) {
                inventorySentAt[transferIdHex] = now
                inventoryNow = true
                sendGroupInventory(transfer, seeds, contiguousChunks)
            }
        }
        val from = lastHolderHeard[transferIdHex]
        for (seed in seeds) {
            if (complete || inventoryNow || from == null || seed == from) {
                sendGroupAck(transferIdHex, seed, contiguousChunks)
            }
        }
    }

    private suspend fun sendGroupAck(transferIdHex: String, seed: String, contiguousChunks: Long) {
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        runCatching {
            val packet = FileTransferPacketCodec.encode(
                FileTransferPacketCodec.Packet(
                    FileTransferPacketCodec.Type.ACK,
                    hexToBytes(transferIdHex),
                    contiguousChunks,
                    0,
                    1,
                    byteArrayOf(1),
                )
            )
            transport.send(
                FileTransferWire.groupAckMessageId(transferIdHex, seed, contiguousChunks),
                transfer.chatId,
                seed,
                FileTransferWire.encodeEncodedPacket(packet),
            )
        }.onFailure { error ->
            Log.w(TAG, "Group file ACK send failed for $transferIdHex: ${error.message}")
        }
    }

    /**
     * Инвентарь недостающего каждому сиду общей копии - та же арифметика, что
     * у нескольких хранителей ([sendInventory]): полосы по номеру куска между
     * живыми сидами (от кого куски идут или кого ещё не просили), первое окно
     * первого сида не делится.
     */
    private suspend fun sendGroupInventory(transfer: FileTransferEntity, seeds: List<String>, contiguousChunks: Long) {
        val me = identity.myNodeId() ?: return
        val transferIdHex = transfer.transferId
        val have = chunkStore.storedChunkIndices(transferIdHex).toHashSet()
        val missing = ArrayList<Long>()
        var index = contiguousChunks
        while (index < transfer.chunkCount && missing.size < MAX_INVENTORY_CHUNKS) {
            if (index !in have) missing += index
            index++
        }
        if (missing.isEmpty()) return
        val now = nowMs()
        val first = wantSeq[transferIdHex] == null
        val seq = maxOf(now, (wantSeq[transferIdHex] ?: -1L) + 1)
        wantSeq[transferIdHex] = seq
        val seen = holderSeenAt[transferIdHex].orEmpty()
        val asked = holderAskedAt.getOrPut(transferIdHex) { HashMap<String, Long>() }
        val active = seeds.indices.filter { position ->
            val seed = seeds[position]
            val askedAt = asked[seed] ?: return@filter true
            now - maxOf(askedAt, seen[seed] ?: 0L) < HOLDER_STALE_MS
        }
        val reservedEnd = if (first) contiguousChunks + FileCustodySender.windowChunks(transfer.chunkSize) else 0L
        val stripes = FileCustodyPdu.assign(missing.toLongArray(), seeds.size, active, reservedFor = 0, reservedEnd = reservedEnd)
        for ((position, seed) in seeds.withIndex()) {
            val ranges = FileCustodyPdu.compressRanges(stripes[position])
            if (ranges.isNotEmpty()) asked[seed] = now
            runCatching {
                val packet = FileTransferPacketCodec.encode(
                    FileTransferPacketCodec.Packet(
                        FileTransferPacketCodec.Type.WANT,
                        hexToBytes(transferIdHex),
                        contiguousChunks,
                        0,
                        1,
                        FileCustodyPdu.encodeWant(seq, ranges),
                    )
                )
                transport.send(
                    FileTransferWire.groupWantMessageId(transferIdHex, me, seed, seq),
                    transfer.chatId,
                    seed,
                    FileTransferWire.encodeEncodedPacket(packet),
                )
            }.onFailure { error ->
                Log.w(TAG, "Group WANT send failed for $transferIdHex: ${error.message}")
            }
        }
    }

    /** Проситель общей копии прислал инвентарь: что именно слать ему (K2). */
    private suspend fun handleGroupWant(
        senderId: String,
        transferIdHex: String,
        contiguousChunks: Long,
        payload: ByteArray,
    ) {
        val want = runCatching { FileCustodyPdu.decodeWant(payload) }.getOrNull()
        if (want == null) {
            Log.w(TAG, "Malformed group WANT payload for $transferIdHex; dropped")
            return
        }
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        if (!isSeedRow(transfer, senderId)) {
            Log.w(TAG, "Group WANT for $transferIdHex from ${senderId.takeLast(8)} is not for a seeding copy; dropped")
            return
        }
        if (contiguousChunks !in 0L..transfer.chunkCount) return
        groupSeed.onWant(transferIdHex, senderId, contiguousChunks, want.seq, want.ranges)
    }

    // ── Хранение у третьего телефона (этап 7 роя) ──────────────────────────

    private suspend fun handleCustodyOffer(senderId: String, payload: ByteArray) {
        val custodyOffer = FileCustodyPdu.decode(payload)
        val me = identity.myNodeId() ?: return
        if (custodyOffer.recipientId == me) {
            handleForwardedOffer(senderId, custodyOffer)
        } else {
            handleCustodyRequest(senderId, custodyOffer)
        }
    }

    /**
     * Хранитель переслал мне чужой файл: проверяем ровно как прямое
     * предложение, только отправитель - [FileCustodyPdu.Custody.originId], а
     * не тот, от кого пришёл пакет. Конверт с ключом запечатан отправителем
     * для меня, хранитель его вскрыть не мог.
     */
    private suspend fun handleForwardedOffer(custodianId: String, custodyOffer: FileCustodyPdu.Custody) {
        val manifest = crypto.parseManifest(custodyOffer.manifest)
        val transferIdHex = manifest.transferIdHex
        val originId = custodyOffer.originId
        val now = nowMs()
        val me = identity.myNodeId() ?: return
        // Метка хранителя в id: с двумя хранителями одинаковые ответы разным
        // адресатам иначе слились бы для сети в один.
        suspend fun refuse(reason: String) {
            Log.w(TAG, "Forwarded file offer $transferIdHex via ${custodianId.takeLast(8)} refused: $reason")
            sendCustodyAck(custodianId, transferIdHex, 0L, FileCustodyPdu.ACK_REFUSED, holderTag = custodianId)
        }
        if (manifest.fileSize > Long.MAX_VALUE.toULong() || manifest.chunkCount > Long.MAX_VALUE.toULong()) {
            refuse("geometry"); return
        }
        if (manifest.senderNodeId != originId || manifest.recipientNodeId != me) {
            refuse("manifest parties mismatch"); return
        }
        if (manifest.expiresAtMs <= now) {
            refuse("expired"); return
        }
        if (!crypto.verifyBinding(custodyOffer.senderBinding) ||
            crypto.bindingNodeId(custodyOffer.senderBinding) != originId
        ) {
            refuse("origin binding"); return
        }
        val existing = transferDao.getTransfer(transferIdHex)
        if (existing != null && (existing.direction != "INCOMING" || existing.peerNodeId != originId)) {
            refuse("conflicts with local transfer row"); return
        }
        if (existing?.state == "FAILED") {
            refuse("already failed"); return
        }
        if (existing?.state == "COMPLETE") {
            // Уже всё есть: хранителю достаточно знать, что можно удалять.
            sendCustodyAck(custodianId, transferIdHex, existing.chunkCount, FileCustodyPdu.ACK_OK, holderTag = custodianId)
            return
        }
        // Чужой файл от не-контакта не принимаем - и ключ чужака не
        // закрепляем: проверка знакомства раньше закрепления.
        val chatId = existing?.chatId ?: custody.chatIdFor(originId)
        if (chatId == null) {
            refuse("origin is not a contact"); return
        }
        try {
            pinner.pinFirstSeen(custodyOffer.senderBinding, now)
        } catch (pinError: Exception) {
            refuse("pinned exchange key changed for $originId (${pinError.message})"); return
        }
        if (existing == null && insertIncomingTransfer(manifest, originId, chatId, now) == null) return
        // Несколько хранителей (этап 8): каждый, кто представился, - в список;
        // подтверждения и инвентарь пойдут всем (не больше MAX_HOLDERS).
        val knownHolders = FileCustodyPdu.holders(existing?.custodianNodeId ?: "")
        if (custodianId !in knownHolders) {
            if (knownHolders.size >= FileCustodyPdu.MAX_HOLDERS) {
                Log.i(TAG, "Forwarded offer for $transferIdHex from ${custodianId.takeLast(8)}: enough holders already")
                sendCustodyAck(custodianId, transferIdHex, 0L, FileCustodyPdu.ACK_REFUSED, holderTag = custodianId)
                return
            }
            transferDao.setCustodian(transferIdHex, FileCustodyPdu.joinHolders(knownHolders + custodianId), now)
            inventorySentAt.remove(transferIdHex) // новый хранитель - инвентарь всем сразу
        }
        lastHolderHeard[transferIdHex] = custodianId // ответ на предложение - тому, кто его прислал
        Log.i(
            TAG,
            "Forwarded file offer accepted: $transferIdHex from ${originId.takeLast(8)} " +
                "via ${custodianId.takeLast(8)} (${manifest.displayName}, ${manifest.fileSize} B; holders=${knownHolders.size + 1})",
        )
        chunkStore.storeManifest(transferIdHex, custodyOffer.manifest)
        chunkStore.storeKeyEnvelope(transferIdHex, custodyOffer.keyEnvelope)
        if (keyVault.mode(transferIdHex) != FileTransferKeyVault.Mode.READY) {
            openAndImportKey(transferIdHex, custodyOffer.asOffer())
        }
        recoverDurableProgress(transferIdHex)
        bufferedChunks.remove(transferIdHex)?.let { buffered ->
            for ((chunkIndex, ciphertext) in buffered.toSortedMap()) {
                ingestChunkCiphertext(transferIdHex, chunkIndex, ciphertext)
                ciphertext.fill(0)
            }
        }
        val contiguous = contiguousReceived(transferIdHex)
        sendFileAck(transferIdHex, contiguous)
        val fresh = transferDao.getTransfer(transferIdHex) ?: return
        if (contiguous >= manifest.chunkCount.toLong()) {
            finalizeTransfer(fresh, manifest)
        } else if (fresh.state == "OFFERED") {
            advance(fresh, newState = "TRANSFERRING")
        }
    }

    /**
     * Отправитель просит подержать файл для получателя, который не в сети.
     * Берём, если отправитель - наш контакт, лимиты не выбраны и место есть;
     * иначе отвечаем отказом или «полон», и он идёт к следующему кандидату.
     */
    private suspend fun handleCustodyRequest(senderId: String, custodyOffer: FileCustodyPdu.Custody) {
        val manifest = crypto.parseManifest(custodyOffer.manifest)
        val transferIdHex = manifest.transferIdHex
        val now = nowMs()
        suspend fun answer(status: Byte, reason: String) {
            Log.i(TAG, "Custody request $transferIdHex from ${senderId.takeLast(8)}: $reason")
            sendCustodyAck(senderId, transferIdHex, 0L, status)
        }
        if (custodyOffer.originId != senderId) {
            answer(FileCustodyPdu.ACK_REFUSED, "origin is not the sender"); return
        }
        if (manifest.fileSize > Long.MAX_VALUE.toULong() || manifest.chunkCount > Long.MAX_VALUE.toULong()) {
            answer(FileCustodyPdu.ACK_REFUSED, "geometry"); return
        }
        if (manifest.senderNodeId != senderId || manifest.recipientNodeId != custodyOffer.recipientId) {
            answer(FileCustodyPdu.ACK_REFUSED, "manifest parties mismatch"); return
        }
        if (manifest.expiresAtMs <= now) {
            answer(FileCustodyPdu.ACK_REFUSED, "expired"); return
        }
        if (!crypto.verifyBinding(custodyOffer.senderBinding) ||
            crypto.bindingNodeId(custodyOffer.senderBinding) != senderId
        ) {
            answer(FileCustodyPdu.ACK_REFUSED, "sender binding"); return
        }
        if (!custody.acceptsFrom(senderId)) {
            answer(FileCustodyPdu.ACK_REFUSED, "not a contact"); return
        }
        try {
            pinner.pinFirstSeen(custodyOffer.senderBinding, now)
        } catch (pinError: Exception) {
            answer(FileCustodyPdu.ACK_REFUSED, "pinned exchange key changed (${pinError.message})"); return
        }
        val existing = transferDao.getTransfer(transferIdHex)
        if (existing != null) {
            if (existing.direction != "CUSTODY" || existing.originNodeId != senderId ||
                existing.peerNodeId != custodyOffer.recipientId
            ) {
                answer(FileCustodyPdu.ACK_REFUSED, "conflicts with local transfer row"); return
            }
            // Повтор предложения: напоминаем, сколько уже держим.
            recoverDurableProgress(transferIdHex)
            sendCustodyAck(senderId, transferIdHex, contiguousReceived(transferIdHex), FileCustodyPdu.ACK_OK)
            return
        }
        if (transferDao.countActiveCustody() >= FileCustodySender.MAX_CUSTODY_TRANSFERS) {
            answer(FileCustodyPdu.ACK_FULL, "too many files held"); return
        }
        if (transferDao.countActiveCustodyFrom(senderId) >= FileCustodySender.MAX_CUSTODY_PER_ORIGIN) {
            answer(FileCustodyPdu.ACK_FULL, "too many files held for this sender"); return
        }
        val needed = Math.addExact(
            manifest.fileSize.toLong(),
            Math.multiplyExact(manifest.chunkCount.toLong(), FileTransferChunkStore.AEAD_TAG_BYTES.toLong()),
        )
        if (needed > custody.headroomBytes()) {
            answer(FileCustodyPdu.ACK_FULL, "no space ($needed B needed)"); return
        }
        val entity = FileTransferEntity(
            transferId = transferIdHex,
            messageId = "custody-$transferIdHex",
            chatId = FileTransferChatRouting.CUSTODY_SCOPE,
            peerNodeId = custodyOffer.recipientId,
            direction = "CUSTODY",
            displayName = manifest.displayName,
            mediaType = manifest.mediaType,
            totalBytes = manifest.fileSize.toLong(),
            chunkSize = manifest.chunkSize.toInt(),
            chunkCount = manifest.chunkCount.toLong(),
            fileSha256 = manifest.fileSha256Hex,
            state = "HOLDING",
            createdAtMs = now,
            expiresAtMs = minOf(manifest.expiresAtMs, Math.addExact(now, FileCustodySender.CUSTODY_TTL_MS)),
            updatedAtMs = now,
            originNodeId = senderId,
        )
        if (!transferDao.insertNewTransfer(entity)) {
            answer(FileCustodyPdu.ACK_REFUSED, "row collision"); return
        }
        chunkStore.storeManifest(transferIdHex, custodyOffer.manifest)
        chunkStore.storeKeyEnvelope(transferIdHex, custodyOffer.keyEnvelope)
        chunkStore.storeOriginBinding(transferIdHex, custodyOffer.senderBinding)
        recoverDurableProgress(transferIdHex)
        Log.i(
            TAG,
            "Custody accepted: $transferIdHex from ${senderId.takeLast(8)} " +
                "for ${custodyOffer.recipientId.takeLast(8)} (${manifest.fileSize} B, ${manifest.chunkCount} chunks)",
        )
        bufferedChunks.remove(transferIdHex)?.let { buffered ->
            for ((chunkIndex, ciphertext) in buffered.toSortedMap()) {
                ingestCustodyChunk(transferIdHex, chunkIndex, ciphertext)
                ciphertext.fill(0)
            }
        }
        sendCustodyAck(senderId, transferIdHex, contiguousReceived(transferIdHex), FileCustodyPdu.ACK_OK)
    }

    private suspend fun handleCustodyChunk(
        senderId: String,
        transferIdHex: String,
        chunkIndex: Long,
        ciphertext: ByteArray,
    ) {
        val transfer = transferDao.getTransfer(transferIdHex)
        if (transfer == null || chunkStore.readManifest(transferIdHex) == null) {
            bufferOrDropChunk(transferIdHex, chunkIndex, ciphertext)
            return
        }
        when (transfer.direction) {
            "CUSTODY" -> {
                if (transfer.originNodeId != senderId) {
                    Log.w(TAG, "Custody chunk for $transferIdHex from a stranger ${senderId.takeLast(8)}; dropped")
                    return
                }
                ingestCustodyChunk(transferIdHex, chunkIndex, ciphertext)
            }
            "INCOMING" -> {
                if (senderId !in FileCustodyPdu.holders(transfer.custodianNodeId)) {
                    // Хранитель ещё не представился предложением (или это не он).
                    Log.w(TAG, "Forwarded chunk for $transferIdHex from unexpected ${senderId.takeLast(8)}; dropped")
                    return
                }
                holderSeenAt.getOrPut(transferIdHex) { HashMap<String, Long>() }[senderId] = nowMs()
                lastHolderHeard[transferIdHex] = senderId
                ingestChunkCiphertext(transferIdHex, chunkIndex, ciphertext)
            }
            else -> Log.w(TAG, "Custody chunk for own outgoing $transferIdHex; dropped")
        }
    }

    /** Кусок чужого файла на хранение: только геометрия и место, содержимое нам недоступно. */
    private suspend fun ingestCustodyChunk(transferIdHex: String, chunkIndex: Long, ciphertext: ByteArray) {
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        if (transfer.direction != "CUSTODY" || transfer.state == "COMPLETE") return
        val manifestBytes = chunkStore.readManifest(transferIdHex) ?: return
        val manifest = crypto.parseManifest(manifestBytes)
        val chunkCount = manifest.chunkCount.toLong()
        if (chunkIndex !in 0L until chunkCount) {
            Log.w(TAG, "Custody chunk index $chunkIndex out of range for $transferIdHex")
            return
        }
        if (transfer.expiresAtMs <= nowMs()) return
        val expectedPlaintext = plaintextLengthOf(manifest, chunkIndex)
        if (ciphertext.size != expectedPlaintext + FileTransferChunkStore.AEAD_TAG_BYTES) {
            Log.w(TAG, "Custody chunk $chunkIndex geometry mismatch for $transferIdHex")
            return
        }
        val stored = try {
            chunkStore.storeEncryptedChunk(transferIdHex, chunkIndex, ciphertext)
        } catch (storeError: Exception) {
            Log.w(TAG, "Custody chunk $chunkIndex rejected by store for $transferIdHex: ${storeError.message}")
            if (storeError is FileTransferChunkStore.StorageFullException) {
                // Места нет: честно говорим «полон» и освобождаем начатое -
                // отправитель уйдёт к следующему хранителю.
                sendCustodyAck(transfer.originNodeId, transferIdHex, 0L, FileCustodyPdu.ACK_FULL)
                runCatching { chunkStore.deleteTransfer(transferIdHex) }
                transferDao.deleteTransfer(transferIdHex)
                contiguousPrefixes.remove(transferIdHex)
            }
            return
        }
        val inserted = transferDao.insertChunkIgnore(
            FileTransferChunkEntity(
                transferId = transferIdHex,
                chunkIndex = chunkIndex,
                state = "HELD",
                ciphertextBytes = stored.ciphertextBytes,
                chunkSha256 = stored.sha256,
                updatedAtMs = nowMs(),
            )
        ) != -1L
        val contiguous = advanceContiguousPrefix(transferIdHex)
        if (!inserted) {
            sendCustodyAck(transfer.originNodeId, transferIdHex, contiguous, FileCustodyPdu.ACK_OK)
            return
        }
        val completedChunks = Math.addExact(transfer.completedChunks, 1L)
        val transferredBytes = Math.addExact(transfer.transferredBytes, expectedPlaintext.toLong())
        advance(
            transfer,
            newState = transfer.state,
            completedChunks = completedChunks,
            transferredBytes = transferredBytes,
        )
        if (completedChunks == chunkCount) {
            Log.i(TAG, "Custody complete: $transferIdHex ($chunkCount chunks) held for ${transfer.peerNodeId.takeLast(8)}")
        }
        // Подтверждение на каждый кусок, как у прямого приёма: id
        // детерминирован, а транспорт переключается на прямой канал, когда
        // он есть (SwitchingPacketTransport), так что очередь ретранслятора
        // это не нагружает.
        sendCustodyAck(transfer.originNodeId, transferIdHex, contiguous, FileCustodyPdu.ACK_OK)
    }

    private suspend fun handleCustodyAck(
        senderId: String,
        transferIdHex: String,
        contiguousChunks: Long,
        payload: ByteArray,
    ) {
        val status = FileCustodyPdu.ackStatus(payload)
        if (status == null) {
            Log.w(TAG, "Malformed custody ACK payload for $transferIdHex; dropped")
            return
        }
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        if (contiguousChunks !in 0L..transfer.chunkCount) {
            Log.w(TAG, "Out-of-range custody ACK $contiguousChunks for $transferIdHex; dropped")
            return
        }
        when (transfer.direction) {
            "OUTGOING" -> if (status != FileCustodyPdu.ACK_RELEASE) {
                custody.onCustodianAck(transferIdHex, senderId, contiguousChunks, status)
            }
            "CUSTODY" -> if (status == FileCustodyPdu.ACK_RELEASE) {
                if (transfer.originNodeId == senderId) custody.onOriginRelease(transferIdHex, senderId)
            } else if (transfer.peerNodeId == senderId) {
                custody.onRecipientAck(transferIdHex, senderId, contiguousChunks, status)
            }
            else -> Log.w(TAG, "Custody ACK for incoming $transferIdHex from ${senderId.takeLast(8)}; dropped")
        }
    }

    private suspend fun sendCustodyAck(
        to: String,
        transferIdHex: String,
        contiguousChunks: Long,
        status: Byte,
        holderTag: String? = null,
    ) {
        val me = identity.myNodeId() ?: return
        runCatching {
            val packet = FileTransferPacketCodec.encode(
                FileTransferPacketCodec.Packet(
                    FileTransferPacketCodec.Type.CUSTODY_ACK,
                    hexToBytes(transferIdHex),
                    contiguousChunks,
                    0,
                    1,
                    byteArrayOf(status),
                )
            )
            transport.send(
                FileTransferWire.custodyAckMessageId(transferIdHex, me, contiguousChunks, holderTag),
                FileTransferChatRouting.CUSTODY_SCOPE,
                to,
                FileTransferWire.encodeEncodedPacket(packet),
            )
        }.onFailure { error ->
            Log.w(TAG, "Custody ACK send failed for $transferIdHex: ${error.message}")
        }
    }

    /**
     * Инвентарь недостающего каждому хранителю (этап 8): недостающие куски
     * делятся полосами ([FileCustodyPdu.stripe]) между хранителями в порядке
     * их появления; хранитель старой версии тип не знает и продолжает слать
     * от префикса - лишнего от этого не станет, только дубли.
     */
    private suspend fun sendInventory(transfer: FileTransferEntity, holders: List<String>, contiguousChunks: Long) {
        val me = identity.myNodeId() ?: return
        val transferIdHex = transfer.transferId
        val have = chunkStore.storedChunkIndices(transferIdHex).toHashSet()
        val missing = ArrayList<Long>()
        var index = contiguousChunks
        while (index < transfer.chunkCount && missing.size < MAX_INVENTORY_CHUNKS) {
            if (index !in have) missing += index
            index++
        }
        if (missing.isEmpty()) return
        val now = nowMs()
        val first = wantSeq[transferIdHex] == null
        val seq = maxOf(now, (wantSeq[transferIdHex] ?: -1L) + 1)
        wantSeq[transferIdHex] = seq
        // Делят живые хранители: от кого куски шли недавно или кого ещё не
        // просили. Замолчавший (ушёл из сети) из дележа выпадает, его куски
        // достаются остальным; вернётся - следующий инвентарь учтёт.
        val seen = holderSeenAt[transferIdHex].orEmpty()
        val asked = holderAskedAt.getOrPut(transferIdHex) { HashMap<String, Long>() }
        val active = holders.indices.filter { position ->
            val holder = holders[position]
            val askedAt = asked[holder] ?: return@filter true // ещё не просили - пусть попробует
            now - maxOf(askedAt, seen[holder] ?: 0L) < HOLDER_STALE_MS
        }
        // Первый инвентарь: первый хранитель уже получил подтверждение и шлёт
        // окно от префикса - его не делим, иначе второй пришлёт то же самое.
        val reservedEnd = if (first) contiguousChunks + FileCustodySender.windowChunks(transfer.chunkSize) else 0L
        val stripes = FileCustodyPdu.assign(missing.toLongArray(), holders.size, active, reservedFor = 0, reservedEnd = reservedEnd)
        for ((position, holder) in holders.withIndex()) {
            val ranges = FileCustodyPdu.compressRanges(stripes[position])
            if (ranges.isNotEmpty()) asked[holder] = now
            runCatching {
                val packet = FileTransferPacketCodec.encode(
                    FileTransferPacketCodec.Packet(
                        FileTransferPacketCodec.Type.CUSTODY_WANT,
                        hexToBytes(transferIdHex),
                        contiguousChunks,
                        0,
                        1,
                        FileCustodyPdu.encodeWant(seq, ranges),
                    )
                )
                transport.send(
                    FileTransferWire.custodyWantMessageId(transferIdHex, me, holder, seq),
                    FileTransferChatRouting.CUSTODY_SCOPE,
                    holder,
                    FileTransferWire.encodeEncodedPacket(packet),
                )
            }.onFailure { error ->
                Log.w(TAG, "Custody WANT send failed for $transferIdHex: ${error.message}")
            }
        }
    }

    /** Получатель хранимой у меня передачи прислал инвентарь (этап 8). */
    private suspend fun handleCustodyWant(
        senderId: String,
        transferIdHex: String,
        contiguousChunks: Long,
        payload: ByteArray,
    ) {
        val want = runCatching { FileCustodyPdu.decodeWant(payload) }.getOrNull()
        if (want == null) {
            Log.w(TAG, "Malformed custody WANT payload for $transferIdHex; dropped")
            return
        }
        val transfer = transferDao.getTransfer(transferIdHex) ?: return
        if (transfer.direction != "CUSTODY" || transfer.peerNodeId != senderId) {
            Log.w(TAG, "Custody WANT for $transferIdHex from ${senderId.takeLast(8)} is not for a held transfer; dropped")
            return
        }
        if (contiguousChunks !in 0L..transfer.chunkCount) return
        custody.onRecipientWant(transferIdHex, senderId, contiguousChunks, want.seq, want.ranges)
    }

    private suspend fun insertIncomingTransfer(
        manifest: FileTransferManifestFfi,
        senderId: String,
        chatId: String,
        now: Long,
    ): FileTransferEntity? {
        val entity = FileTransferEntity(
            transferId = manifest.transferIdHex,
            messageId = FileTransferWire.chatPlaceholderMessageId(manifest.transferIdHex),
            chatId = chatId,
            peerNodeId = senderId,
            direction = "INCOMING",
            displayName = manifest.displayName,
            mediaType = manifest.mediaType,
            totalBytes = manifest.fileSize.toLong(),
            chunkSize = manifest.chunkSize.toInt(),
            chunkCount = manifest.chunkCount.toLong(),
            fileSha256 = manifest.fileSha256Hex,
            state = "OFFERED",
            createdAtMs = now,
            expiresAtMs = manifest.expiresAtMs,
            updatedAtMs = now,
        )
        return if (transferDao.insertNewTransfer(entity)) entity else null
    }

    private suspend fun advance(
        transfer: FileTransferEntity,
        newState: String,
        completedChunks: Long = transfer.completedChunks,
        transferredBytes: Long = transfer.transferredBytes,
        errorCode: String? = null,
    ): Int = transferDao.advanceProgress(
        transferId = transfer.transferId,
        state = newState,
        completedChunks = completedChunks,
        transferredBytes = transferredBytes,
        updatedAtMs = nowMs(),
        errorCode = errorCode,
    )

    private fun hexToBytes(transferIdHex: String): ByteArray =
        ByteArray(16) { index -> transferIdHex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Chat-level notification boundary implemented by the transport host. */
    fun interface FileChatNotifier {
        suspend fun onFileReceived(
            chatId: String,
            senderId: String,
            messageId: String,
            displayName: String,
            mediaType: String,
            totalBytes: Long,
            fileSha256: String,
        )
    }

    companion object {
        private const val TAG = "FileTransferReceiver"
        /** errorCode строки передачи, пока приём стоит из-за квоты «Место под пересылку». */
        const val ERROR_NO_SPACE = "NO_SPACE"
        const val MAX_PENDING_ITEMS = 64
        const val MAX_PENDING_BYTES = 32L * 1024 * 1024
        /** Полезная нагрузка CANCEL «получателю файл уже не нужен» (этап 10); прочие CANCEL - лишь заметка в журнале. */
        val CANCEL_DECLINED: ByteArray = byteArrayOf(2)
        const val MAX_DECLINED = 64
        const val MAX_BUFFERED_TRANSFERS = 32
        const val MAX_BUFFERED_CHUNKS = 64
        const val MAX_BUFFERED_CHUNK_BYTES = 16L * 1024 * 1024
        /** Инвентарь (этап 8) охватывает не больше стольких недостающих кусков за раз. */
        const val MAX_INVENTORY_CHUNKS = 4096
        /** Инвентарь хранителям - не чаще, чем раз в столько (подтверждения идут и так на каждый кусок). */
        const val INVENTORY_INTERVAL_MS = 10_000L
        /** Хранитель, от которого столько не было кусков (а просили), в дележе не участвует. */
        const val HOLDER_STALE_MS = 45_000L
        /** Сидов общей копии файла группы (K2), у которых качаем одновременно; лишние предложения - без ответа. */
        const val MAX_GROUP_SEEDS = 4
    }
}
