package com.vladimir.messenger.data.file

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * F4-F v1: direct phone-to-phone LAN data channel.
 *
 * Bulk file packets travel over ONE plain TCP socket between two phones on the
 * same Wi-Fi network; the mesh (MQTT) stays responsible for signalling and as a
 * fallback transport. The receiving phone IS the server (binds 0.0.0.0).
 *
 * Wire format (binary, length-prefixed; no delimiter ambiguity):
 *   handshake frame: "APULANHS1|<senderNodeId>"
 *   packet frame:    [u32 chatLen][chatId][u32 msgLen][messageId][u32 textLen][text]
 *
 * Security: the transport itself authenticates nothing - exactly like the mesh
 * path, authentication happens inside FileTransferReceiver (pinned identity
 * bindings for offers, AEAD per chunk bound to the manifest). A raw socket can
 * only deliver ciphertext that will fail those checks for a wrong sender.
 */
class LanDirectChannel internal constructor() {

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lan-direct-channel").apply { isDaemon = true }
    }

    /** Recipient node id -> open client connection. */
    private val channels = ConcurrentHashMap<String, Socket>()

    /** Recipient node id -> endpoint announced via mesh signalling. */
    private val offeredEndpoints = ConcurrentHashMap<String, InetSocketAddress>()

    /** Recipient node id -> deferred completed when an offer arrives. */
    private val awaitingOffer = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Recipient node id -> wall time of the last failed establish attempt. */
    private val lastEstablishFailure = ConcurrentHashMap<String, Long>()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var serverPort: Int = 0

    /**
     * Router hook for frames received on the SERVER side. Set once by the
     * FileTransferRouter after construction (breaks the construction cycle).
     */
    @Volatile
    var incomingRoute: (suspend (senderId: String, chatId: String, messageId: String, text: String) -> Boolean)? = null

    /** Optional diagnostic sink; the router wires it to logcat. Keeps this class pure Kotlin. */
    @Volatile
    var onDiagnostic: ((message: String) -> Unit)? = null

    internal fun diag(message: String) {
        onDiagnostic?.invoke(message)
    }

    // ── Server side (receiving phone) ────────────────────────────────

    /** Idempotent. Binds all interfaces on an ephemeral port. Safe without Wi-Fi. */
    @Synchronized
    fun startServer() {
        if (server != null) return
        val local = try {
            val fixed = ServerSocket(LAN_DISCOVERY_PORT, 8)
            diag("lan-server bound to fixed discovery port $LAN_DISCOVERY_PORT")
            fixed
        } catch (e: IOException) {
            diag("lan-server fixed port $LAN_DISCOVERY_PORT busy, using ephemeral: ${e.message}")
            ServerSocket(0, 8)
        }
        server = local
        serverPort = local.localPort
        val ifaceSummary = StringBuilder()
        try {
            val nets = NetworkInterface.getNetworkInterfaces()
            while (nets != null && nets.hasMoreElements()) {
                val network = nets.nextElement() ?: continue
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement() ?: continue
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        if (ifaceSummary.isNotEmpty()) ifaceSummary.append(' ')
                        ifaceSummary.append(network.name).append('=').append(address.hostAddress)
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        diag("lan-server started on port $serverPort, wifi-endpoint=${lanEndpoint()?.hostAddress ?: "none"}, ifaces=[$ifaceSummary]")
        executor.execute { acceptLoop(local) }
    }

    val listenPort: Int get() = serverPort

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                break
            }
            diag("lan-accept from ${socket.inetAddress?.hostAddress}")
            executor.execute { serveConnection(socket) }
        }
    }

    private fun serveConnection(socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = IDLE_READ_TIMEOUT_MS
                val input = DataInputStream(client.getInputStream())
                val handshake = readTextFrame(input)
                val fields = handshake.split('|')
                require(fields.size == 2 && fields[0] == HANDSHAKE_PREFIX) { "bad lan handshake" }
                val senderId = fields[1]
                require(senderId.startsWith("pk_") && senderId.length >= 10) { "bad lan sender id" }
                val route = incomingRoute ?: return
                // Identify ourselves so the connecting peer can verify it
                // reached the intended recipient (and subnet discovery works).
                val output = DataOutputStream(client.getOutputStream())
                writeTextFrame(output, "$SIGNAL_PREFIX|iam|$myNodeId")
                output.flush()
                diag("lan-handshake ok from $senderId")
                var frames = 0
                while (true) {
                    val chatLength = input.readInt()
                    require(chatLength in 1..512) { "bad lan chat length" }
                    val chatId = ByteArray(chatLength).also { input.readFully(it) }.toString(Charsets.UTF_8)
                    val msgLength = input.readInt()
                    require(msgLength in 1..512) { "bad lan message length" }
                    val messageId = ByteArray(msgLength).also { input.readFully(it) }.toString(Charsets.UTF_8)
                    val text = readTextFrame(input)
                    // Bridge from the blocking socket reader thread into the
                    // suspend routing pipeline (this is a dedicated IO thread).
                    runBlocking { route(senderId, chatId, messageId, text) }
                    frames++
                    if (frames == 1 || frames % 512 == 0) diag("lan-frames from $senderId: $frames")
                }
            }
        } catch (e: Exception) {
            // Connection closed, idle timeout or malformed frame: drop it. The
            // durable mesh path is unaffected; the sender falls back to MQTT.
            diag("lan-connection dropped: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── Client side (sending phone) ──────────────────────────────────

    fun hasChannel(recipientNodeId: String): Boolean {
        val socket = channels[recipientNodeId] ?: return false
        return !socket.isClosed
    }

    /**
     * Sends one packet frame over the open channel. Returns false (and drops
     * the channel) on any IO problem so the caller can fall back to the mesh.
     */
    suspend fun sendPacket(
        recipientNodeId: String,
        chatId: String,
        messageId: String,
        text: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val socket = channels[recipientNodeId]
        if (socket == null || socket.isClosed) return@withContext false
        try {
            val output = DataOutputStream(socket.getOutputStream())
            synchronized(socket) {
                writeChunk(output, chatId.toByteArray(Charsets.UTF_8))
                writeChunk(output, messageId.toByteArray(Charsets.UTF_8))
                writeTextFrame(output, text)
                output.flush()
            }
            true
        } catch (e: IOException) {
            diag("lan-send failed: ${e.javaClass.simpleName}: ${e.message}")
            dropChannel(recipientNodeId)
            false
        }
    }

    /** Connects to the announced endpoint, performs the handshake and verifies the peer identity. */
    suspend fun openChannel(recipientNodeId: String, host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        if (hasChannel(recipientNodeId)) return@withContext true
        val socket = Socket()
        diag("lan-connecting to $host:$port for $recipientNodeId")
        val connected = try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = IAM_REPLY_TIMEOUT_MS
            val output = DataOutputStream(socket.getOutputStream())
            writeTextFrame(output, "$HANDSHAKE_PREFIX|$myNodeId")
            output.flush()
            val input = DataInputStream(socket.getInputStream())
            val iam = readTextFrame(input)
            val fields = iam.split('|')
            require(fields.size == 3 && fields[0] == SIGNAL_PREFIX && fields[1] == "iam") { "bad lan identity reply" }
            require(fields[2] == recipientNodeId) { "lan peer mismatch: connected to ${fields[2]}" }
            socket.soTimeout = 0
            true
        } catch (e: Exception) {
            diag("lan-connect to $host:$port failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (connected) {
            diag("lan-channel open to $recipientNodeId at $host:$port")
            channels[recipientNodeId] = socket
            true
        } else {
            try { socket.close() } catch (ignored: IOException) { }
            false
        }
    }

    /**
     * Mesh-independent peer discovery: scan the local /24 on the fixed LAN
     * port, verify each responder's identity frame and keep the connection
     * when it matches [recipientNodeId]. Used when mesh signalling is dead
     * (broker offline) but both phones share a Wi-Fi network.
     */
    suspend fun discoverPeer(recipientNodeId: String): Boolean = withContext(Dispatchers.IO) {
        if (serverPort != LAN_DISCOVERY_PORT) {
            diag("lan-discover skipped: server not on fixed port (port=$serverPort)")
            return@withContext false
        }
        val self = lanEndpoint()
        val selfHost = self?.hostAddress
        if (selfHost.isNullOrBlank()) {
            diag("lan-discover skipped: no local Wi-Fi endpoint")
            return@withContext false
        }
        val prefix = selfHost.substringBeforeLast('.')
        diag("lan-discover scanning $prefix.1-254:$LAN_DISCOVERY_PORT for $recipientNodeId")
        val semaphore = Semaphore(24)
        try {
            coroutineScope {
                for (hostIndex in 1..254) {
                    launch {
                        semaphore.withPermit {
                            val host = "$prefix.$hostIndex"
                            if (host == selfHost) return@withPermit
                            var socket: Socket? = null
                            try {
                                socket = Socket()
                                socket.tcpNoDelay = true
                                socket.connect(InetSocketAddress(host, LAN_DISCOVERY_PORT), DISCOVERY_CONNECT_TIMEOUT_MS)
                                socket.soTimeout = IAM_REPLY_TIMEOUT_MS
                                val output = DataOutputStream(socket.getOutputStream())
                                writeTextFrame(output, "$HANDSHAKE_PREFIX|$myNodeId")
                                output.flush()
                                val input = DataInputStream(socket.getInputStream())
                                val iam = readTextFrame(input)
                                val fields = iam.split('|')
                                if (fields.size == 3 && fields[0] == SIGNAL_PREFIX && fields[1] == "iam") {
                                    if (fields[2] == recipientNodeId) {
                                        socket.soTimeout = 0
                                        if (channels.putIfAbsent(recipientNodeId, socket) == null) {
                                            diag("lan-discover FOUND $recipientNodeId at $host:$LAN_DISCOVERY_PORT")
                                            socket = null // ownership transferred to the channel map
                                        } else {
                                            diag("lan-discover duplicate channel for $recipientNodeId ignored")
                                        }
                                    } else {
                                        diag("lan-discover peer ${fields[2]} at $host skipped")
                                    }
                                }
                            } catch (expected: Exception) {
                                // Refused/timeout: the normal case while scanning.
                            } finally {
                                try { socket?.close() } catch (ignored: IOException) { }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            diag("lan-discover scan failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        val found = hasChannel(recipientNodeId)
        diag("lan-discover result for $recipientNodeId: $found")
        found
    }

    fun dropChannel(recipientNodeId: String) {
        val socket = channels.remove(recipientNodeId) ?: return
        try { socket.close() } catch (ignored: IOException) { }
    }

    /**
     * One-shot delivery of a short signal frame (e.g. an offer) straight to
     * the peer's LAN server, bypassing the mesh entirely. Used when a request
     * carried the requester's endpoint.
     */
    suspend fun sendSignalFrame(host: String, port: Int, chatId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = CONNECT_TIMEOUT_MS
                val output = DataOutputStream(socket.getOutputStream())
                writeTextFrame(output, "$HANDSHAKE_PREFIX|$myNodeId")
                writeChunk(output, chatId.toByteArray(Charsets.UTF_8))
                writeChunk(output, ("lan-signal-" + System.nanoTime()).toByteArray(Charsets.UTF_8))
                writeTextFrame(output, text)
                output.flush()
                true
            }
        } catch (e: IOException) {
            diag("lan-signal-frame to $host:$port failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ── Mesh signalling (APULAN1 texts ride the ordinary mesh path) ──

    fun buildRequestText(): String {
        val port = serverPort
        val endpoint = lanEndpoint()
        if (port in 1024..65535 && endpoint != null) {
            return "$SIGNAL_PREFIX|req|${endpoint.hostAddress}|$port"
        }
        return "$SIGNAL_PREFIX|req"
    }

    fun isRequestText(text: String): Boolean =
        text == "$SIGNAL_PREFIX|req" || text.startsWith("$SIGNAL_PREFIX|req|")

    /** Endpoint carried inside "APULAN1|req|<ip>|<port>" so the receiver can answer via socket. */
    fun parseRequestEndpoint(text: String): InetSocketAddress? {
        val fields = text.split('|')
        if (fields.size != 4 || fields[0] != SIGNAL_PREFIX || fields[1] != "req") return null
        val host = fields[2]
        val port = fields[3].toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1024..65535) return null
        return InetSocketAddress(host, port)
    }

    fun parseOfferText(text: String): InetSocketAddress? {
        val fields = text.split('|')
        if (fields.size != 4 || fields[0] != SIGNAL_PREFIX || fields[1] != "offer") return null
        val host = fields[2]
        val port = fields[3].toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1024..65535) return null
        return InetSocketAddress(host, port)
    }

    fun buildOfferText(): String? {
        val endpoint = lanEndpoint() ?: return null
        return "$SIGNAL_PREFIX|offer|${endpoint.hostAddress}|$serverPort"
    }

    /** Sender side: an offer arrived from the mesh. Wakes awaitChannel(). */
    fun onOfferReceived(senderNodeId: String, endpoint: InetSocketAddress) {
        diag("lan-offer received from $senderNodeId: ${endpoint.hostString}:${endpoint.port}")
        offeredEndpoints[senderNodeId] = endpoint
        awaitingOffer[senderNodeId]?.complete(true)
    }

    /**
     * Sender side: ask for an endpoint (signalSender delivers the request over
     * the mesh) and wait for the offer + connect. EVERY failure path records a
     * TTL stamp so bulk packets never stall on repeated establish attempts.
     */
    suspend fun awaitChannel(
        recipientNodeId: String,
        nowMs: Long,
        signalSender: suspend (String) -> Boolean,
    ): Boolean {
        if (hasChannel(recipientNodeId)) return true
        val lastFailure = lastEstablishFailure[recipientNodeId] ?: 0L
        if (nowMs - lastFailure < ESTABLISH_RETRY_TTL_MS) return false
        val deferred = CompletableDeferred<Boolean>()
        awaitingOffer[recipientNodeId] = deferred
        try {
            val cached = offeredEndpoints[recipientNodeId]
            val endpoint = if (cached != null) {
                cached
            } else {
                if (!signalSender(buildRequestText())) {
                    diag("lan-seek $recipientNodeId: signal send failed, falling back to mesh")
                    lastEstablishFailure[recipientNodeId] = nowMs
                    return false
                }
                val offered = withTimeoutOrNull(SEEK_ENDPOINT_TIMEOUT_MS) { deferred.await() }
                if (offered != true) {
                    diag("lan-seek $recipientNodeId: no offer within ${SEEK_ENDPOINT_TIMEOUT_MS}ms, falling back to mesh")
                    lastEstablishFailure[recipientNodeId] = nowMs
                    return false
                }
                val received = offeredEndpoints[recipientNodeId]
                if (received == null) {
                    diag("lan-seek $recipientNodeId: offer vanished")
                    lastEstablishFailure[recipientNodeId] = nowMs
                    return false
                }
                received
            }
            val host = endpoint.address?.hostAddress ?: endpoint.hostString
            if (host == null) {
                diag("lan-seek $recipientNodeId: endpoint has no host")
                lastEstablishFailure[recipientNodeId] = nowMs
                return false
            }
            val connected = openChannel(recipientNodeId, host, endpoint.port)
            if (!connected) {
                diag("lan-seek $recipientNodeId: connect failed, falling back to mesh")
                lastEstablishFailure[recipientNodeId] = nowMs
                offeredEndpoints.remove(recipientNodeId)
            }
            return connected
        } finally {
            awaitingOffer.remove(recipientNodeId)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** First site-local IPv4 of a Wi-Fi-like interface, or null. */
    fun lanEndpoint(): java.net.InetAddress? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = ArrayList<java.net.InetAddress>()
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement() ?: continue
            if (!network.isUp || network.isLoopback || network.isVirtual) continue
            val name = network.name.lowercase()
            val cellular = name.contains("rmnet") || name.contains("ccmni") || name.contains("usb")
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement() ?: continue
                if (address.isLoopbackAddress || address.isSiteLocalAddress.not()) continue
                if (address is java.net.Inet4Address) {
                    if (name.contains("wlan")) return address
                    if (!cellular) candidates.add(address)
                }
            }
        }
        return candidates.firstOrNull()
    }

    /** Node id injected by the router (keeps this class free of Android deps). */
    @Volatile var myNodeId: String = "pk_unknown"

    fun closeAll() {
        channels.keys.toList().forEach { dropChannel(it) }
        try { server?.close() } catch (ignored: IOException) { }
        server = null
        serverPort = 0
    }

    private fun readTextFrame(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 1..MAX_FRAME_BYTES) { "bad lan frame length" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun writeTextFrame(output: DataOutputStream, text: String) {
        writeChunk(output, text.toByteArray(Charsets.UTF_8))
    }

    private fun writeChunk(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    companion object {
        const val SIGNAL_PREFIX = "APULAN1"
        private const val HANDSHAKE_PREFIX = "APULANHS1"
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val IAM_REPLY_TIMEOUT_MS = 1_500
        private const val DISCOVERY_CONNECT_TIMEOUT_MS = 300
        /** Fixed port enables mesh-independent subnet discovery. */
        const val LAN_DISCOVERY_PORT = 42108
        private const val SEEK_ENDPOINT_TIMEOUT_MS = 5_000L
        private const val ESTABLISH_RETRY_TTL_MS = 15_000L
        private const val IDLE_READ_TIMEOUT_MS = 15 * 60 * 1000

        fun isLanSignalText(text: String): Boolean = text.startsWith("$SIGNAL_PREFIX|")

        @Volatile private var instance: LanDirectChannel? = null

        fun get(): LanDirectChannel {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) instance = LanDirectChannel()
                }
            }
            return instance!!
        }
    }
}

/**
 * Packet transport that prefers the direct LAN channel and falls back to the
 * mesh (MQTT relay) transport. Establishes the channel lazily on the first
 * packet for a recipient; establish failures are rate-limited so the mesh
 * path is never blocked for long.
 */
class SwitchingPacketTransport(
    private val mesh: PacketTransport,
    private val lan: LanDirectChannel,
) : PacketTransport {

    private val lanFrames = java.util.concurrent.atomic.AtomicLong(0)
    private val meshFrames = java.util.concurrent.atomic.AtomicLong(0)

    override suspend fun send(
        messageId: String,
        chatId: String,
        recipientNodeId: String,
        text: String,
    ): Boolean {
        if (LanDirectChannel.isLanSignalText(text)) {
            return mesh.send(messageId, chatId, recipientNodeId, text)
        }
        if (lan.hasChannel(recipientNodeId)) {
            if (lan.sendPacket(recipientNodeId, chatId, messageId, text)) {
                val sent = lanFrames.incrementAndGet()
                if (sent == 1L || sent % 128L == 0L) {
                    lan.diag("lan-path via=lan lanFrames=$sent meshFrames=${meshFrames.get()}")
                }
                return true
            }
        } else {
            val established = lan.awaitChannel(
                recipientNodeId,
                System.currentTimeMillis(),
            ) { requestText -> mesh.send(messageId, chatId, recipientNodeId, requestText) }
            if (established && lan.sendPacket(recipientNodeId, chatId, messageId, text)) {
                val sent = lanFrames.incrementAndGet()
                lan.diag("lan-path via=lan lanFrames=$sent meshFrames=${meshFrames.get()}")
                return true
            }
        }
        val sent = meshFrames.incrementAndGet()
        if (sent == 1L || sent % 256L == 0L) {
            lan.diag("lan-path via=mesh lanFrames=${lanFrames.get()} meshFrames=$sent")
        }
        return mesh.send(messageId, chatId, recipientNodeId, text)
    }
}
