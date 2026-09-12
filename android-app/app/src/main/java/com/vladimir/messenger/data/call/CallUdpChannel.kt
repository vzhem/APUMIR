package com.vladimir.messenger.data.call

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Прямой UDP-канал звонка через интернет с пробиванием NAT (CALLS_BOOTSTRAP.md, 8.4, путь 2).
 *
 * Один UDP-сокет на звонок. Сначала собираем кандидатов — адреса, по которым
 * нас можно достать: внешний адрес:порт от STUN (RFC 5389, свой минимальный
 * кодек StunCodec), глобальные IPv6 своих интерфейсов, IPv4 в Wi-Fi.
 * Кандидаты уезжают собеседнику пакетом `cand` (по мосту и по durable-пути),
 * его кандидаты приходят так же. Дальше обе стороны одновременно шлют друг
 * другу пробы на все адреса (каждые 200 мс): исходящая проба открывает дырку
 * в своём NAT, входящая проба собеседника через неё проходит. Проба с
 * `seen=1` означает «твои пробы до меня доходят»; получив такую, мы знаем, что
 * работают оба направления, и закрепляем адрес — дальше весь голос идёт в него.
 *
 * Сам канал ничего не шифрует и не разбирает: наружу отдаёт сырые датаграммы
 * с адресом источника, на вход берёт готовые пакеты CallLinkWire (control-пробы
 * и media-пачки закрыты теми же ключами, что на мосту). Датаграммы — короткие
 * (2 ADPCM-кадра ≈ 380 байт), в MTU влезают без фрагментации.
 *
 * Внешний ресурс тут один — STUN-сервер, и он вспомогательный: ответа ждём не
 * дольше STUN_WAIT_MS, без него остаются свои адреса (IPv6 через мобильную
 * сеть часто работает напрямую). Не пробилось за PROBE_TOTAL_MS — канал
 * закрывается, голос остаётся на мосту через брокер. Симметричный NAT с обеих
 * сторон пробить нельзя — это штатный исход, не ошибка.
 */
class CallUdpChannel(
    private val callId: String,
    private val onDatagram: (bytes: ByteArray, length: Int, from: InetSocketAddress) -> Unit,
    private val onState: (UdpState) -> Unit,
) {
    enum class UdpState { PROBING, OPEN, CLOSED }

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var state = UdpState.PROBING
    @Volatile private var lockedPeer: InetSocketAddress? = null
    @Volatile private var peerSeen = false
    @Volatile private var lastInboundAtMs = 0L
    @Volatile private var lastOutboundAtMs = 0L
    @Volatile private var probeStartedAtMs = 0L
    @Volatile private var probeTargets: List<InetSocketAddress> = emptyList()
    @Volatile private var probeFactory: ((seen: Boolean) -> ByteArray?)? = null

    private var readerThread: Thread? = null
    private var proberThread: Thread? = null
    private val random = SecureRandom()

    // STUN: ждём ответ с нашим txId на любом из серверов.
    @Volatile private var stunTxId: ByteArray? = null
    @Volatile private var stunReflexive: InetSocketAddress? = null
    @Volatile private var stunLatch: CountDownLatch? = null

    private val sentCount = AtomicInteger()
    private val receivedCount = AtomicInteger()

    val localPort: Int get() = socket?.localPort ?: 0
    fun isOpen(): Boolean = state == UdpState.OPEN && lockedPeer != null
    fun isClosed(): Boolean = state == UdpState.CLOSED
    fun peerSeen(): Boolean = peerSeen
    fun lockedPeer(): InetSocketAddress? = lockedPeer
    fun stats(): String = "sent=${sentCount.get()} recv=${receivedCount.get()} peer=${lockedPeer}"

    /** Открыть сокет и поток чтения. false = UDP недоступен (сокет не открылся). */
    fun open(): Boolean {
        if (socket != null) return true
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = false
                receiveBufferSize = 256 * 1024
                bind(InetSocketAddress(0))
            }
        } catch (e: Exception) {
            Log.w(TAG, "udp socket failed: ${e.message}")
            return false
        }
        socket = s
        readerThread = Thread({ runReader(s) }, "call-udp-r").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * Собрать кандидатов (блокирует до STUN_WAIT_MS, звать не с главного потока):
     * отражённый адрес от STUN, глобальные IPv6, IPv4 Wi-Fi. Порядок = предпочтение.
     */
    fun gatherCandidates(): List<Pair<String, Int>> {
        val s = socket ?: return emptyList()
        val port = s.localPort
        val result = LinkedHashMap<String, Int>()
        val reflexive = queryStun(s)
        if (reflexive != null) result[literal(reflexive.address)] = reflexive.port
        localAddresses().forEach { address -> result.putIfAbsent(literal(address), port) }
        val list = result.entries.take(CallWire.MAX_CANDIDATES).map { it.key to it.value }
        Log.i(TAG, "udp candidates for ${callId.take(8)}: $list")
        return list
    }

    /**
     * Начать пробивание к адресам собеседника. factory даёт свежий зашифрованный
     * control-пакет `probe` (новый seq на каждый вызов — nonce не повторяется).
     * Идемпотентно: повторный вызов лишь добавляет адреса.
     */
    fun startProbing(peerEndpoints: List<Pair<String, Int>>, factory: (seen: Boolean) -> ByteArray?) {
        if (state == UdpState.CLOSED) return
        val targets = peerEndpoints.mapNotNull { (ip, port) ->
            val address = parseLiteral(ip) ?: return@mapNotNull null
            runCatching { InetSocketAddress(address, port) }.getOrNull()
        }
        if (targets.isEmpty()) return
        probeFactory = factory
        val started: Thread = synchronized(this) {
            val merged = LinkedHashSet<InetSocketAddress>(probeTargets)
            merged += targets
            probeTargets = merged.toList()
            if (proberThread != null) return
            probeStartedAtMs = System.currentTimeMillis()
            val thread = Thread({ runProber() }, "call-udp-p").apply { isDaemon = true }
            proberThread = thread
            thread
        }
        onState(UdpState.PROBING)
        started.start()
    }

    /**
     * Менеджер расшифровал пробу собеседника, пришедшую с адреса from.
     * peerSeesUs = в пробе стоял seen=1 (наши пробы до него доходят) — только
     * тогда адрес можно закреплять: одного «мы его слышим» мало, наш NAT может
     * быть симметричным, и обратный путь к нему не открыт.
     */
    fun onPeerProbe(from: InetSocketAddress, peerSeesUs: Boolean) {
        if (state == UdpState.CLOSED) return
        peerSeen = true
        lastInboundAtMs = System.currentTimeMillis()
        if (peerSeesUs) lock(from)
    }

    /**
     * Расшифрованный НЕ-пробный пакет собеседника (голос или сигнал) с адреса from.
     * Такие пакеты он шлёт по UDP, только закрепив наш адрес, то есть получив
     * нашу пробу с seen=1 — значит, путь работает в обе стороны, даже если его
     * собственная проба с seen=1 к нам потерялась.
     */
    fun onPeerPacket(from: InetSocketAddress) {
        peerSeen = true
        lastInboundAtMs = System.currentTimeMillis()
        lock(from)
    }

    private fun lock(from: InetSocketAddress) {
        synchronized(this) {
            if (lockedPeer != null || state == UdpState.CLOSED) return
            lockedPeer = from
            state = UdpState.OPEN
        }
        Log.i(TAG, "udp punched for ${callId.take(8)}: $from after ${System.currentTimeMillis() - probeStartedAtMs} ms")
        onState(UdpState.OPEN)
    }

    /** Отправить пакет закреплённому адресу собеседника. false = канал не пробит/закрыт. */
    fun send(packet: ByteArray): Boolean {
        val s = socket ?: return false
        val peer = lockedPeer ?: return false
        if (state != UdpState.OPEN) return false
        return try {
            s.send(DatagramPacket(packet, packet.size, peer))
            sentCount.incrementAndGet()
            lastOutboundAtMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.w(TAG, "udp send failed: ${e.message}")
            false
        }
    }

    fun close() {
        synchronized(this) {
            if (state == UdpState.CLOSED) return
            state = UdpState.CLOSED
        }
        val s = socket
        socket = null
        runCatching { s?.close() }
        proberThread?.interrupt()
        stunLatch?.countDown()
        onState(UdpState.CLOSED)
        Log.i(TAG, "udp closed for ${callId.take(8)}: ${stats()}")
    }

    // ── Потоки ──────────────────────────────────────────────────────────────

    private fun runReader(s: DatagramSocket) {
        val buf = ByteArray(4096)
        val packet = DatagramPacket(buf, buf.size)
        try {
            while (state != UdpState.CLOSED) {
                packet.setData(buf, 0, buf.size)
                s.receive(packet)
                val from = packet.socketAddress as? InetSocketAddress ?: continue
                val length = packet.length
                if (length <= 0) continue
                if (StunCodec.isStun(buf, length)) {
                    val txId = stunTxId ?: continue
                    val mapped = StunCodec.parseBindingResponse(buf, length, txId) ?: continue
                    stunReflexive = mapped
                    stunLatch?.countDown()
                    continue
                }
                receivedCount.incrementAndGet()
                val copy = buf.copyOf(length)
                runCatching { onDatagram(copy, length, from) }
                    .onFailure { Log.w(TAG, "udp datagram handler failed: ${it.message}") }
            }
        } catch (e: Exception) {
            if (state != UdpState.CLOSED) Log.w(TAG, "udp read ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            close()
        }
    }

    /** Пробы каждые PROBE_EVERY_MS до пробития (потом — keepalive), обрыв по тишине. */
    private fun runProber() {
        val s = socket ?: return
        try {
            while (state != UdpState.CLOSED) {
                val now = System.currentTimeMillis()
                val locked = lockedPeer
                if (locked == null) {
                    // Слышим его, но он нас нет: даём ещё столько же — его NAT мог открыться позже.
                    val budget = if (peerSeen) PROBE_TOTAL_MS * 2 else PROBE_TOTAL_MS
                    if (now - probeStartedAtMs > budget) {
                        Log.i(TAG, "udp punching gave up for ${callId.take(8)} (peerSeen=$peerSeen)")
                        close()
                        return
                    }
                    val probe = probeFactory?.invoke(peerSeen)
                    if (probe != null) {
                        probeTargets.forEach { target ->
                            runCatching { s.send(DatagramPacket(probe, probe.size, target)) }
                        }
                    }
                    Thread.sleep(PROBE_EVERY_MS)
                } else {
                    // Пробито: ещё немного проб с seen=1 (собеседник мог ещё не закрепиться),
                    // затем keepalive, если голос почему-то не течёт, и сторож тишины.
                    if (lastInboundAtMs > 0 && now - lastInboundAtMs > DEAD_AFTER_MS) {
                        Log.i(TAG, "udp silent ${DEAD_AFTER_MS / 1000}s for ${callId.take(8)}: closing")
                        close()
                        return
                    }
                    val since = now - probeStartedAtMs
                    val needProbe = since < PROBE_TOTAL_MS || now - lastOutboundAtMs > KEEPALIVE_MS
                    if (needProbe) {
                        val probe = probeFactory?.invoke(true)
                        if (probe != null) send(probe)
                    }
                    Thread.sleep(if (since < PROBE_TOTAL_MS) PROBE_EVERY_MS else KEEPALIVE_MS / 2)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "udp prober ended: ${e.message}")
        }
    }

    // ── STUN и адреса ───────────────────────────────────────────────────────

    private fun queryStun(s: DatagramSocket): InetSocketAddress? {
        val txId = ByteArray(StunCodec.TX_ID_BYTES).also { random.nextBytes(it) }
        val latch = CountDownLatch(1)
        stunTxId = txId
        stunReflexive = null
        stunLatch = latch
        val request = StunCodec.bindingRequest(txId)
        var sent = 0
        val resolved = resolveStunServers()
        for (target in resolved) {
            if (runCatching { s.send(DatagramPacket(request, request.size, target)) }.isSuccess) sent++
        }
        if (sent == 0) {
            Log.i(TAG, "stun unavailable (dns/blocked) — candidates are local addresses only")
            return null
        }
        latch.await(STUN_WAIT_MS, TimeUnit.MILLISECONDS)
        val reflexive = stunReflexive
        stunLatch = null
        if (reflexive == null) Log.i(TAG, "stun no answer in ${STUN_WAIT_MS} ms")
        return reflexive
    }

    /**
     * DNS STUN-серверов с общим потолком времени: при заблокированном DNS
     * getAllByName может висеть десятки секунд, а звонок ждать не должен.
     */
    private fun resolveStunServers(): List<InetSocketAddress> {
        val out = java.util.concurrent.ConcurrentLinkedQueue<InetSocketAddress>()
        val latch = CountDownLatch(STUN_SERVERS.size)
        STUN_SERVERS.forEach { (host, port) ->
            Thread({
                try {
                    val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
                    // IPv4-адрес сервера: отражение нужно именно для IPv4-NAT.
                    val target = addresses?.firstOrNull { it is Inet4Address }
                    if (target != null) out += InetSocketAddress(target, port)
                } finally {
                    latch.countDown()
                }
            }, "call-stun-dns").apply { isDaemon = true }.start()
        }
        latch.await(STUN_DNS_WAIT_MS, TimeUnit.MILLISECONDS)
        return out.toList()
    }

    /** Глобальные IPv6 и site-local IPv4 (Wi-Fi/Ethernet) своих интерфейсов, без link-local и loopback. */
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
                }
            }
        }
        return v6.take(2) + v4.take(2)
    }

    private fun isUniqueLocal(address: Inet6Address): Boolean =
        (address.address[0].toInt() and 0xFE) == 0xFC

    /** Только литералы, никакого DNS: строка из cand приходит от собеседника. */
    private fun parseLiteral(ip: String): InetAddress? {
        if (!CallWire.isValidIpLiteral(ip)) return null
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
        private const val TAG = "CallUdpChannel"

        /** Публичные STUN-серверы (только отражение адреса, ни байта голоса через них). */
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
    }
}
