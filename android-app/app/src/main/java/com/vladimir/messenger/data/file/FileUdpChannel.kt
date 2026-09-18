package com.vladimir.messenger.data.file

import android.util.Log
import com.vladimir.messenger.data.call.StunCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * UDP-канал файловой передачи через интернет с пробиванием NAT
 * (docs/SWARM_MOBILE.md). Движок — тот же, что у звонков
 * (`CallUdpChannel` + `StunCodec`): сокет, кандидаты (STUN-отражение,
 * IPv6, IPv4), пробы каждые 200 мс, флаг `seen=1`, фиксация адреса,
 * keep-alive 4 с, смерть 8 с. Разница: датаграммы несут фрагменты
 * файловых пакетов (`FileUdpWire`), а привязка в пробе — подписанный
 * ключ обмена узла (проверяется получателем).
 *
 * Один канал на собеседника ([FileUdpChannels]). Тексты фрагментов — те
 * же `apu-file1|…`, что по LAN и брокеру: приёмный путь один
 * (`FileTransferRouter.routeIncoming`). Потерянные датаграммы добираются
 * WANT/ACK-механизмом файловой машины (идемпотентно по (transferId,
 * chunkIndex)). Симметричный NAT с обеих сторон не пробивается — канал
 * закрывается, роутер скатывается на брокер.
 */
class FileUdpChannel internal constructor(
    private val peerNodeId: String,
    private val sessionTag: ByteArray,
    private val myBinding: () -> ByteArray?,
    private val verifyProbe: (binding: ByteArray) -> Boolean,
    private val onDataPacket: (peerNodeId: String, text: String) -> Unit,
) {
    enum class State { PROBING, OPEN, CLOSED }

    @Volatile var state = State.PROBING
        private set
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var lockedPeer: InetSocketAddress? = null
    @Volatile private var peerSeen = false
    @Volatile private var peerSessionTag: ByteArray? = null
    @Volatile private var lastInboundAtMs = 0L
    @Volatile private var lastOutboundAtMs = 0L
    @Volatile private var lastUsedAtMs = System.currentTimeMillis()
    @Volatile private var probeStartedAtMs = 0L
    @Volatile private var probeTargets: List<InetSocketAddress> = emptyList()

    private var readerThread: Thread? = null
    private var proberThread: Thread? = null
    private val random = SecureRandom()
    private val fragIdSeq = AtomicLong(1)
    private val reassembler = FileUdpWire.Reassembler()

    // STUN: ждём ответ с нашим txId (только на этапе gather).
    @Volatile private var stunTxId: ByteArray? = null
    @Volatile private var stunReflexive: InetSocketAddress? = null
    @Volatile private var stunLatch: CountDownLatch? = null

    private val sentCount = AtomicLong()
    private val receivedCount = AtomicLong()

    val tag: ByteArray get() = sessionTag
    val shortId: String get() = peerNodeId.takeLast(8)

    fun isOpen(): Boolean = state == State.OPEN && lockedPeer != null
    fun isClosed(): Boolean = state == State.CLOSED
    fun peerSeen(): Boolean = peerSeen

    fun createSocket(): Boolean {
        if (socket != null) return true
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = false
                receiveBufferSize = 256 * 1024
                bind(InetSocketAddress(0))
            }
        } catch (e: Exception) {
            Log.w(TAG, "udp socket failed for $shortId: ${e.message}")
            return false
        }
        socket = s
        readerThread = Thread({ runReader(s) }, "file-udp-r-$shortId").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * Кандидаты (блокирует до ~3 с, звать с рабочего потока): STUN-отражение,
     * глобальные IPv6, IPv4 Wi-Fi.
     */
    fun gatherCandidates(): List<Pair<String, Int>> {
        val s = socket ?: return emptyList()
        val port = s.localPort
        val result = LinkedHashMap<String, Int>()
        val reflexive = queryStun(s)
        if (reflexive != null) result[literal(reflexive.address)] = reflexive.port
        localAddresses().forEach { address -> result.putIfAbsent(literal(address), port) }
        val list = result.entries.take(FileUdpWire.MAX_CANDIDATES).map { it.key to it.value }
        Log.i(TAG, "udp candidates for $shortId: $list")
        return list
    }

    /** Установить тег сессии собеседника (из его сигнала). */
    fun setPeerSessionTag(tag: ByteArray) {
        peerSessionTag = tag
    }

    /** Начать пробы на адресах собеседника (идемпотентно, адреса сливаются). */
    fun startProbing(peerEndpoints: List<Pair<String, Int>>) {
        if (state == State.CLOSED) return
        val targets = peerEndpoints.mapNotNull { (ip, port) ->
            val address = parseLiteral(ip) ?: return@mapNotNull null
            runCatching { InetSocketAddress(address, port) }.getOrNull()
        }
        if (targets.isEmpty()) return
        synchronized(this) {
            val merged = LinkedHashSet<InetSocketAddress>(probeTargets)
            merged += targets
            probeTargets = merged.toList()
            if (proberThread != null) return
            probeStartedAtMs = System.currentTimeMillis()
            Thread({ runProber() }, "file-udp-p-$shortId").apply { isDaemon = true; start() }
        }
    }

    /** Отправить файл-текст закреплённому адресам (часть → датаграммы). */
    fun sendText(text: String): Boolean {
        val s = socket ?: return false
        val peer = lockedPeer ?: return false
        if (state != State.OPEN) return false
        if (!FileUdpWire.isSplittableText(text)) {
            Log.w(TAG, "udp text not splittable for $shortId: ${text.length} chars")
            return false
        }
        val fragId = fragIdSeq.getAndIncrement() and 0xFFFFFFFFL
        val parts = FileUdpWire.splitForDatagrams(text, sessionTag, fragId)
        var ok = true
        for (part in parts) {
            val sent = try {
                s.send(DatagramPacket(part, part.size, peer))
                true
            } catch (e: Exception) {
                Log.w(TAG, "udp send failed for $shortId: ${e.message}")
                false
            }
            if (sent) sentCount.incrementAndGet() else ok = false
        }
        lastOutboundAtMs = System.currentTimeMillis()
        lastUsedAtMs = lastOutboundAtMs
        return ok
    }

    fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        val s = socket
        socket = null
        runCatching { s?.close() }
        proberThread?.interrupt()
        proberThread = null
        stunLatch?.countDown()
        Log.i(TAG, "udp closed for $shortId: sent=${sentCount.get()} recv=${receivedCount.get()}")
    }

    // ── Потоки ──────────────────────────────────────────────────────────────

    private fun runReader(s: DatagramSocket) {
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        try {
            while (state != State.CLOSED) {
                packet.setData(buf, 0, buf.size)
                s.receive(packet)
                val from = packet.socketAddress as? InetSocketAddress ?: continue
                val length = packet.length
                if (length <= 0) continue
                handleDatagram(buf, length, from)
            }
        } catch (e: Exception) {
            if (state != State.CLOSED) Log.w(TAG, "udp read ended for $shortId: ${e.javaClass.simpleName}")
        } finally {
            close()
        }
    }

    private fun handleDatagram(buf: ByteArray, length: Int, from: InetSocketAddress) {
        if (StunCodec.isStun(buf, length)) {
            val txId = stunTxId ?: return
            val mapped = StunCodec.parseBindingResponse(buf, length, txId) ?: return
            stunReflexive = mapped
            stunLatch?.countDown()
            return
        }
        val bytes = buf.copyOf(length)
        val probe = FileUdpWire.parseProbe(bytes)
        if (probe != null) {
            if (!verifyProbe(probe.binding)) {
                // Пробы с чужой/битой привязкой — спам или не наш узел: отбрасываем.
                return
            }
            receivedCount.incrementAndGet()
            lastInboundAtMs = System.currentTimeMillis()
            peerSeen = true
            // Он видит наши пробы (seen=1) — оба направления открыты: фиксировать адрес.
            if (probe.seen) lock(from)
            return
        }
        val part = FileUdpWire.parseDataPart(bytes) ?: return
        val peerTag = peerSessionTag ?: return
        if (!part.sessionTag.contentEquals(peerTag)) return
        receivedCount.incrementAndGet()
        lastInboundAtMs = System.currentTimeMillis()
        lastUsedAtMs = lastInboundAtMs
        lock(from)
        val text = reassembler.put(part) ?: return
        onDataPacket(peerNodeId, text)
    }

    private fun lock(from: InetSocketAddress) {
        synchronized(this) {
            if (lockedPeer != null || state == State.CLOSED) return
            lockedPeer = from
            state = State.OPEN
        }
        Log.i(TAG, "udp punched for $shortId: $from after ${System.currentTimeMillis() - probeStartedAtMs} ms")
    }

    private fun runProber() {
        val s = socket ?: return
        try {
            while (state != State.CLOSED) {
                val now = System.currentTimeMillis()
                val locked = lockedPeer
                if (locked == null) {
                    // Слышим его, но он нас нет: даём ещё столько же (его NAT мог открыться позже).
                    val budget = if (peerSeen) PROBE_TOTAL_MS * 2 else PROBE_TOTAL_MS
                    if (now - probeStartedAtMs > budget) {
                        Log.i(TAG, "udp punching gave up for $shortId (peerSeen=$peerSeen)")
                        close()
                        return
                    }
                    val binding = myBinding()
                    if (binding == null) {
                        // Ключ обмена ещё не сгенерирован — подождём, а не бросим пробы.
                        Thread.sleep(PROBE_EVERY_MS)
                        continue
                    }
                    val probe = FileUdpWire.encodeProbe(peerSeen, binding)
                    probeTargets.forEach { target ->
                        runCatching { s.send(DatagramPacket(probe, probe.size, target)) }
                    }
                    Thread.sleep(PROBE_EVERY_MS)
                } else {
                    // Пробито: keep-alive (пробы с seen=1) и сторожа тишины/простоя.
                    if (lastInboundAtMs > 0 && now - lastInboundAtMs > DEAD_AFTER_MS) {
                        Log.i(TAG, "udp silent ${DEAD_AFTER_MS / 1000}s for $shortId: closing")
                        close()
                        return
                    }
                    if (now - lastUsedAtMs > IDLE_CLOSE_MS) {
                        Log.i(TAG, "udp idle ${IDLE_CLOSE_MS / 1000}s for $shortId: closing (reopens on demand)")
                        close()
                        return
                    }
                    if (now - lastOutboundAtMs > KEEPALIVE_MS) {
                        val binding = myBinding()
                        if (binding != null) {
                            val probe = FileUdpWire.encodeProbe(true, binding)
                            runCatching { s.send(DatagramPacket(probe, probe.size, locked)) }
                            sentCount.incrementAndGet()
                            lastOutboundAtMs = now
                        }
                    }
                    Thread.sleep(KEEPALIVE_MS / 2)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "udp prober ended for $shortId: ${e.message}")
        }
    }

    // ── STUN и адреса (тот же подход, что в CallUdpChannel) ─────────────────

    private fun queryStun(s: DatagramSocket): InetSocketAddress? {
        val txId = ByteArray(StunCodec.TX_ID_BYTES).also { random.nextBytes(it) }
        val latch = CountDownLatch(1)
        stunTxId = txId
        stunReflexive = null
        stunLatch = latch
        val request = StunCodec.bindingRequest(txId)
        var sent = 0
        for (target in resolveStunServers()) {
            if (runCatching { s.send(DatagramPacket(request, request.size, target)) }.isSuccess) sent++
        }
        if (sent == 0) {
            Log.i(TAG, "stun unavailable for $shortId (dns/blocked) — local addresses only")
            return null
        }
        latch.await(STUN_WAIT_MS, TimeUnit.MILLISECONDS)
        val reflexive = stunReflexive
        stunLatch = null
        if (reflexive == null) Log.i(TAG, "stun no answer for $shortId in ${STUN_WAIT_MS} ms")
        return reflexive
    }

    private fun resolveStunServers(): List<InetSocketAddress> {
        val out = java.util.concurrent.ConcurrentLinkedQueue<InetSocketAddress>()
        val latch = CountDownLatch(Companion.STUN_SERVERS.size)
        Companion.STUN_SERVERS.forEach { (host, port) ->
            Thread({
                try {
                    val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
                    val target = addresses?.firstOrNull { it is Inet4Address }
                    if (target != null) out += InetSocketAddress(target, port)
                } finally {
                    latch.countDown()
                }
            }, "file-stun-dns-$shortId").apply { isDaemon = true }.start()
        }
        latch.await(STUN_DNS_WAIT_MS, TimeUnit.MILLISECONDS)
        return out.toList()
    }

    private fun localAddresses(): List<InetAddress> {
        val v6 = ArrayList<InetAddress>()
        val v4 = ArrayList<InetAddress>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement() ?: continue
            if (!runCatching { network.isUp }.getOrDefault(false) || network.isLoopback || network.isVirtual) continue
            val name = network.name.lowercase()
            if (name.startsWith("tun") || name.startsWith("ppp") || name.contains("dummy")) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement() ?: continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress || address.isAnyLocalAddress) continue
                when (address) {
                    is Inet6Address -> if (!address.isSiteLocalAddress && !isUniqueLocal(address)) v6 += address
                    is Inet4Address -> if (address.isSiteLocalAddress) v4 += address
                    else -> Unit
                }
            }
        }
        return v6.take(2) + v4.take(2)
    }

    private fun isUniqueLocal(address: Inet6Address) =
        (address.address[0].toInt() and 0xFE) == 0xFC

    private fun parseLiteral(ip: String): InetAddress? {
        if (!com.vladimir.messenger.data.call.CallWire.isValidIpLiteral(ip)) return null
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            if (!android.net.InetAddresses.isNumericAddress(ip)) return null
            return runCatching { android.net.InetAddresses.parseNumericAddress(ip) }.getOrNull()
        }
        return runCatching { InetAddress.getByName(ip) }.getOrNull()
    }

    private fun literal(address: InetAddress): String {
        val host = address.hostAddress ?: return ""
        val zone = host.indexOf('%')
        return if (zone > 0) host.substring(0, zone) else host
    }

    companion object {
        private const val TAG = "FileUdpChannel"

        val STUN_SERVERS: List<Pair<String, Int>> = listOf(
            "stun.l.google.com" to 19302,
            "stun.cloudflare.com" to 3478,
        )
        const val STUN_WAIT_MS = 1_500L
        const val STUN_DNS_WAIT_MS = 1_500L
        const val PROBE_EVERY_MS = 200L
        const val PROBE_TOTAL_MS = 8_000L
        const val KEEPALIVE_MS = 4_000L
        const val DEAD_AFTER_MS = 8_000L
        /** Без пакетов и проб столько — канал закрывается (откроется заново по требованию). */
        const val IDLE_CLOSE_MS = 60_000L
    }
}

/**
 * Менеджер UDP-каналов файловой передачи: один канал на собеседника,
 * обмен кандидатами сигналами `ufseek`/`ufcand` (через брокера — доходит
 * по мобильной), пробы — по UDP.
 *
 * [sendSignal] — доставка сигнала по durable-пути mesh (брокер/QUIC);
 * [verifyPeerBinding] — проверка подписанной привязки узла в сигналах и
 * пробах (подпись + `pk_`-узел); [onDataPacket] — собранный файл-текст
 * (уходит в `routeIncoming`).
 */
class FileUdpChannels(
    private val myBindingProvider: () -> ByteArray?,
    private val sendSignal: (recipientNodeId: String, text: String) -> Unit,
    private val verifyPeerBinding: (senderNodeId: String, binding: ByteArray) -> Boolean,
    private val onDataPacket: (peerNodeId: String, text: String) -> Unit,
) {
    private val channels = ConcurrentHashMap<String, FileUdpChannel>()
    private val lastUfseekAtMs = ConcurrentHashMap<String, Long>()
    private val random = SecureRandom()

    /**
     * Канал на [peerNodeId]; мёртвый (закрытый после неудачных проб)
     * заменяется свежим — с новым sessionTag.
     */
    private fun channelFor(peerNodeId: String): FileUdpChannel {
        val existing = channels[peerNodeId]
        if (existing != null && !existing.isClosed()) return existing
        if (existing != null) channels.remove(peerNodeId, existing)
        val tag = ByteArray(FileUdpWire.SESSION_TAG_BYTES).also { random.nextBytes(it) }
        val channel = FileUdpChannel(
            peerNodeId = peerNodeId,
            sessionTag = tag,
            myBinding = myBindingProvider,
            verifyProbe = { binding -> verifyPeerBinding(peerNodeId, binding) },
            onDataPacket = onDataPacket,
        ).also { it.createSocket() }
        val prev = channels.putIfAbsent(peerNodeId, channel)
        if (prev != null && !prev.isClosed()) {
            // Гонка: другой поток успел первым — наш канал (и сокет) не нужен.
            channel.close()
            return prev
        }
        return channel
    }

    /**
     * Подготовить канал на [peerNodeId] и, если давно не спрашивали,
     * отправить `ufseek` с нашими кандидатами. Не блокирует.
     */
    fun ensureRequested(peerNodeId: String) {
        val channel = channelFor(peerNodeId)
        if (!channel.isOpen()) {
            val now = System.currentTimeMillis()
            val last = lastUfseekAtMs[peerNodeId] ?: 0L
            if (now - last >= UFSEEK_THROTTLE_MS) {
                lastUfseekAtMs[peerNodeId] = now
                Thread({
                    val binding = myBindingProvider() ?: return@Thread
                    val candidates = channel.gatherCandidates()
                    if (candidates.isEmpty()) return@Thread
                    val text = FileUdpWire.buildUdpSignal(FileUdpWire.KIND_UFSEEK, binding, candidates, channel.tag)
                    runCatching { sendSignal(peerNodeId, text) }
                        .onFailure { Log.w(TAG, "ufseek to ${peerNodeId.takeLast(8)} failed: ${it.message}") }
                    Log.i(TAG, "ufseek sent to ${peerNodeId.takeLast(8)}: ${candidates.size} candidate(s)")
                }, "file-udp-ufseek-${peerNodeId.takeLast(8)}").apply { isDaemon = true }.start()
            }
        }
    }

    /** Текст по UDP: true, только если канал OPEN и датаграммы ушли. */
    fun trySend(peerNodeId: String, text: String): Boolean {
        val channel = channels[peerNodeId] ?: return false
        return channel.sendText(text)
    }

    /** Канал на узле пробит (прямой режим — UDP). */
    fun isPunched(peerNodeId: String): Boolean =
        channels[peerNodeId]?.isOpen() == true

    /**
     * Сигнал от [senderNodeId] (из `routeIncoming`): открыть/дополнить
     * канал, ответить `ufcand` (на ufseek), начать пробы.
     */
    fun onUdpSignal(senderNodeId: String, signal: FileUdpWire.UdpSignal) {
        if (!verifyPeerBinding(senderNodeId, signal.binding)) {
            Log.w(TAG, "udp signal from ${senderNodeId.takeLast(8)}: binding rejected")
            return
        }
        val channel = channelFor(senderNodeId)
        channel.setPeerSessionTag(signal.sessionTag)
        val replyWithUfcand = signal.kind == FileUdpWire.KIND_UFSEEK
        Thread({
            val binding = myBindingProvider()
            val candidates = channel.gatherCandidates()
            if (replyWithUfcand && binding != null && candidates.isNotEmpty()) {
                val text = FileUdpWire.buildUdpSignal(FileUdpWire.KIND_UFCAND, binding, candidates, channel.tag)
                runCatching { sendSignal(senderNodeId, text) }
                    .onFailure { Log.w(TAG, "ufcand to ${senderNodeId.takeLast(8)} failed: ${it.message}") }
                Log.i(TAG, "ufcand sent to ${senderNodeId.takeLast(8)}: ${candidates.size} candidate(s)")
            }
            channel.startProbing(signal.endpoints)
        }, "file-udp-signal-${senderNodeId.takeLast(8)}").apply { isDaemon = true }.start()
    }

    fun closeAll() {
        channels.values.forEach { it.close() }
        channels.clear()
    }

    companion object {
        private const val TAG = "FileUdpChannels"
        /** Не чаще одного `ufseek` на столько на собеседника. */
        const val UFSEEK_THROTTLE_MS = 30_000L
    }
}
