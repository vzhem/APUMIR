package com.vladimir.messenger.data.swarm

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.logging.Logger
import kotlin.concurrent.thread

/** Результат живой проверки введённого IPv6-прокси. */
sealed interface Ipv6ProxyProbeResult {
    data class Ok(val latencyMs: Long) : Ipv6ProxyProbeResult
    data object InvalidConfig : Ipv6ProxyProbeResult
    data object AuthFailed : Ipv6ProxyProbeResult
    data class Unreachable(val reason: String) : Ipv6ProxyProbeResult
}

/** Приёмник настроек прокси на стороне нативного движка (подменяется в JVM-тестах). */
interface Ipv6ProxyEngineSink {
    fun setSocks5Proxy(host: String, port: Int, username: String, password: String)
    fun clearSocks5Proxy()
    fun wakeNetwork()
}

/**
 * Исполняемый слой пользовательского IPv6-прокси:
 *  1) локальный переводчик `SOCKS5 (127.0.0.1) -> HTTP CONNECT` ([Ipv6HttpConnectBridge]),
 *     чтобы купленный HTTP(S)-прокси туннелировал и MQTT-сессии Rust-ядра;
 *  2) глобальные `ProxySelector` и `Authenticator` для всех HTTP/HTTPS-запросов
 *     приложения (CloudflareRelay, реестр, зеркало, обновления) с обходом LAN;
 *  3) живая проверка рукопожатия и логина/пароля ([probe]).
 */
object Ipv6ProxyRuntime {

    private val logger: Logger = Logger.getLogger("Ipv6ProxyRuntime")
    private const val DEFAULT_PROBE_TIMEOUT_MS = 6_000
    private const val DEFAULT_HTTP_PROBE_HOST = "p2p-relay.apuchat.workers.dev"
    private const val DEFAULT_HTTP_PROBE_PORT = 443

    @Volatile
    private var activeConfig: Ipv6ProxyConfig = Ipv6ProxyConfig()

    @Volatile
    private var selectorsInstalled = false
    private var fallbackSelector: ProxySelector? = null
    private var bridge: Ipv6HttpConnectBridge? = null

    private fun diag(message: String) {
        logger.warning(message)
    }

    @Synchronized
    fun apply(config: Ipv6ProxyConfig, sink: Ipv6ProxyEngineSink) {
        ensureJvmSelectorsInstalled()
        if (!config.isActive) {
            val wasActive = activeConfig.isActive
            activeConfig = config.copy(enabled = false)
            stopBridgeLocked()
            if (wasActive) {
                sink.clearSocks5Proxy()
                sink.wakeNetwork()
            }
            return
        }

        activeConfig = config
        when (config.protocol) {
            Ipv6ProxyProtocol.SOCKS5 -> {
                stopBridgeLocked()
                sink.setSocks5Proxy(config.host, config.port, config.username, config.password)
                sink.wakeNetwork()
            }
            Ipv6ProxyProtocol.HTTP -> {
                val running = bridge
                val localBridge = if (running != null && running.matches(config) && running.isAlive()) {
                    running
                } else {
                    stopBridgeLocked()
                    Ipv6HttpConnectBridge(config).also {
                        it.start()
                        bridge = it
                    }
                }
                sink.setSocks5Proxy("127.0.0.1", localBridge.localPort, "", "")
                sink.wakeNetwork()
            }
        }
    }

    fun currentActiveConfig(): Ipv6ProxyConfig = activeConfig

    fun selectProxiesForUri(uri: URI?, fallback: ProxySelector? = fallbackSelector): List<Proxy> {
        val cfg = activeConfig
        if (!cfg.isActive || uri == null) {
            return fallback?.select(uri)?.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme !in PROXY_SCHEMES) {
            return listOf(Proxy.NO_PROXY)
        }
        val host = uri.host
        if (Ipv6ProxyParser.isBypassHost(host)) {
            return listOf(Proxy.NO_PROXY)
        }
        val cleanUriHost = host?.trim()?.trimStart('[')?.trimEnd(']')
        if (cleanUriHost != null && cleanUriHost.equals(cfg.host, ignoreCase = true) && uri.port == cfg.port) {
            return listOf(Proxy.NO_PROXY)
        }
        val proxyType = when (cfg.protocol) {
            Ipv6ProxyProtocol.SOCKS5 -> Proxy.Type.SOCKS
            Ipv6ProxyProtocol.HTTP -> Proxy.Type.HTTP
        }
        val proxy = Proxy(proxyType, InetSocketAddress(cfg.host, cfg.port))
        return listOf(proxy, Proxy.NO_PROXY)
    }

    private val PROXY_SCHEMES = setOf("http", "https", "ws", "wss", "socket")

    /**
     * Проверить доступность и авторизацию прокси реальным сетевым рукопожатием.
     */
    fun probe(
        config: Ipv6ProxyConfig,
        timeoutMs: Int = DEFAULT_PROBE_TIMEOUT_MS,
        httpProbeHost: String = DEFAULT_HTTP_PROBE_HOST,
        httpProbePort: Int = DEFAULT_HTTP_PROBE_PORT,
    ): Ipv6ProxyProbeResult {
        if (!config.isConfigured) return Ipv6ProxyProbeResult.InvalidConfig
        val startedAt = System.currentTimeMillis()
        return try {
            when (config.protocol) {
                Ipv6ProxyProtocol.SOCKS5 -> probeSocks5(config, timeoutMs, startedAt)
                Ipv6ProxyProtocol.HTTP -> probeHttpConnect(
                    config = config,
                    timeoutMs = timeoutMs,
                    targetHost = httpProbeHost,
                    targetPort = httpProbePort,
                    startedAt = startedAt,
                )
            }
        } catch (e: Exception) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            diag("IPv6 proxy probe failed (${config.safeSummary()}): $detail")
            Ipv6ProxyProbeResult.Unreachable(detail)
        }
    }

    private fun probeSocks5(
        config: Ipv6ProxyConfig,
        timeoutMs: Int,
        startedAt: Long,
    ): Ipv6ProxyProbeResult {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(config.host, config.port), timeoutMs)
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val greeting = if (config.hasAuth) {
                byteArrayOf(0x05, 0x02, 0x00, 0x02)
            } else {
                byteArrayOf(0x05, 0x01, 0x00)
            }
            output.write(greeting)
            output.flush()

            val reply = readExactBytes(input, 2)
            if (reply[0] != 0x05.toByte()) {
                return Ipv6ProxyProbeResult.Unreachable("SOCKS5 bad version ${reply[0].toInt() and 0xFF}")
            }
            when (reply[1].toInt() and 0xFF) {
                0x00 -> Unit
                0x02 -> {
                    if (!config.hasAuth) return Ipv6ProxyProbeResult.AuthFailed
                    val userBytes = config.username.toByteArray(StandardCharsets.UTF_8)
                    val passBytes = config.password.toByteArray(StandardCharsets.UTF_8)
                    val authPacket = ByteArray(3 + userBytes.size + passBytes.size)
                    authPacket[0] = 0x01
                    authPacket[1] = userBytes.size.toByte()
                    System.arraycopy(userBytes, 0, authPacket, 2, userBytes.size)
                    authPacket[2 + userBytes.size] = passBytes.size.toByte()
                    System.arraycopy(passBytes, 0, authPacket, 3 + userBytes.size, passBytes.size)
                    output.write(authPacket)
                    output.flush()
                    val authReply = readExactBytes(input, 2)
                    if (authReply[1] != 0x00.toByte()) {
                        return Ipv6ProxyProbeResult.AuthFailed
                    }
                }
                0xFF -> return if (!config.hasAuth) {
                    Ipv6ProxyProbeResult.AuthFailed
                } else {
                    Ipv6ProxyProbeResult.Unreachable("SOCKS5 auth method rejected")
                }
                else -> return Ipv6ProxyProbeResult.Unreachable("SOCKS5 method ${reply[1].toInt() and 0xFF}")
            }
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            return Ipv6ProxyProbeResult.Ok(elapsed)
        }
    }

    private fun probeHttpConnect(
        config: Ipv6ProxyConfig,
        timeoutMs: Int,
        targetHost: String,
        targetPort: Int,
        startedAt: Long,
    ): Ipv6ProxyProbeResult {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(config.host, config.port), timeoutMs)
            val statusCode = sendHttpConnectAndReadStatus(
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                targetHost = targetHost,
                targetPort = targetPort,
                username = config.username,
                password = config.password,
            )
            return when (statusCode) {
                200 -> Ipv6ProxyProbeResult.Ok((System.currentTimeMillis() - startedAt).coerceAtLeast(1L))
                407, 401 -> Ipv6ProxyProbeResult.AuthFailed
                else -> Ipv6ProxyProbeResult.Unreachable("HTTP $statusCode")
            }
        }
    }

    @Synchronized
    private fun stopBridgeLocked() {
        bridge?.close()
        bridge = null
    }

    @Synchronized
    private fun ensureJvmSelectorsInstalled() {
        if (selectorsInstalled) return
        val previous = ProxySelector.getDefault()
        fallbackSelector = previous
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = selectProxiesForUri(uri, previous)

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                if (ioe != null) {
                    diag("Proxy connectFailed for $uri via $sa: ${ioe.message}")
                }
                previous?.connectFailed(uri, sa, ioe)
            }
        })
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val cfg = activeConfig
                if (!cfg.isActive || !cfg.hasAuth) return null
                val portMatches = requestingPort == cfg.port || requestingPort <= 0
                val cleanReqHost = requestingHost?.trim()?.trimStart('[')?.trimEnd(']')
                val hostMatches = cleanReqHost.isNullOrEmpty() ||
                    cleanReqHost.equals(cfg.host, ignoreCase = true)
                val isProxyChallenge = requestorType == Authenticator.RequestorType.PROXY ||
                    (cfg.protocol == Ipv6ProxyProtocol.SOCKS5 && hostMatches && portMatches)
                return if (isProxyChallenge && hostMatches && portMatches) {
                    PasswordAuthentication(cfg.username, cfg.password.toCharArray())
                } else {
                    null
                }
            }
        })
        selectorsInstalled = true
    }
}

/**
 * Локальный мост `127.0.0.1:<ephemeral>` из SOCKS5 (который умеет Rust-движок)
 * во внешний HTTP CONNECT прокси с поддержкой `Proxy-Authorization: Basic`.
 */
internal class Ipv6HttpConnectBridge(
    private val config: Ipv6ProxyConfig,
) : AutoCloseable {

    private val logger: Logger = Logger.getLogger("Ipv6HttpConnectBridge")
    private val serverSocket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "apu-ipv6-http-bridge").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    val localPort: Int
        get() = serverSocket.localPort

    fun matches(other: Ipv6ProxyConfig): Boolean =
        config.protocol == other.protocol &&
            config.host == other.host &&
            config.port == other.port &&
            config.username == other.username &&
            config.password == other.password

    fun isAlive(): Boolean = !closed && !serverSocket.isClosed

    private fun diag(message: String) {
        logger.warning(message)
    }

    fun start() {
        thread(name = "apu-ipv6-http-accept", isDaemon = true) {
            while (!closed && !serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    if (!closed) diag("Bridge accept stopped: ${e.message}")
                    break
                }
                pool.execute { handleClient(client) }
            }
        }
    }

    private fun handleClient(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 10_000
            client.tcpNoDelay = true
            val cIn = BufferedInputStream(client.getInputStream())
            val cOut = client.getOutputStream()

            val greetHead = readExactBytes(cIn, 2)
            if (greetHead[0] != 0x05.toByte()) return
            val nMethods = greetHead[1].toInt() and 0xFF
            if (nMethods > 0) readExactBytes(cIn, nMethods)
            cOut.write(byteArrayOf(0x05, 0x00))
            cOut.flush()

            val reqHead = readExactBytes(cIn, 4)
            if (reqHead[0] != 0x05.toByte() || reqHead[1] != 0x01.toByte()) {
                cOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                cOut.flush()
                return
            }
            val targetHost = when (reqHead[3].toInt() and 0xFF) {
                0x01 -> InetAddress.getByAddress(readExactBytes(cIn, 4)).hostAddress ?: return
                0x03 -> {
                    val len = readExactBytes(cIn, 1)[0].toInt() and 0xFF
                    String(readExactBytes(cIn, len), StandardCharsets.UTF_8)
                }
                0x04 -> InetAddress.getByAddress(readExactBytes(cIn, 16)).hostAddress ?: return
                else -> return
            }
            val portBytes = readExactBytes(cIn, 2)
            val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            val up = Socket()
            upstream = up
            up.soTimeout = 10_000
            up.tcpNoDelay = true
            up.connect(InetSocketAddress(config.host, config.port), 10_000)

            val status = sendHttpConnectAndReadStatus(
                input = up.getInputStream(),
                output = up.getOutputStream(),
                targetHost = targetHost,
                targetPort = targetPort,
                username = config.username,
                password = config.password,
            )
            if (status != 200) {
                cOut.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                cOut.flush()
                up.close()
                return
            }

            cOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            cOut.flush()

            client.soTimeout = 0
            up.soTimeout = 0

            val reverse = pool.submit {
                pumpStream(up.getInputStream(), cOut, client, up)
            }
            pumpStream(cIn, up.getOutputStream(), up, client)
            reverse.get()
        } catch (e: Exception) {
            if (!closed) diag("Bridge session ended: ${e.message}")
        } finally {
            closeQuietly(upstream)
            closeQuietly(client)
        }
    }

    private fun pumpStream(
        input: InputStream,
        output: OutputStream,
        peerToShutdown: Socket,
        other: Socket,
    ) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                output.write(buf, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // Закрытие одной из сторон туннеля завершает встречный поток штатно.
        } finally {
            try {
                if (!peerToShutdown.isClosed) peerToShutdown.shutdownOutput()
            } catch (_: IOException) {
                // Сокет уже закрыт второй стороной.
            }
            if (other.isClosed && !peerToShutdown.isClosed) {
                closeQuietly(peerToShutdown)
            }
        }
    }

    override fun close() {
        closed = true
        closeServerQuietly(serverSocket)
        pool.shutdownNow()
    }
}

private fun closeQuietly(socket: Socket?) {
    try {
        socket?.close()
    } catch (_: IOException) {
        // Ошибки при закрытии сокета освобождению ресурса не мешают.
    }
}

private fun closeServerQuietly(serverSocket: ServerSocket?) {
    try {
        serverSocket?.close()
    } catch (_: IOException) {
        // Ошибки при закрытии серверного сокета освобождению ресурса не мешают.
    }
}

internal fun readExactBytes(input: InputStream, count: Int): ByteArray {
    val out = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = input.read(out, offset, count - offset)
        if (read < 0) throw EOFException("Unexpected EOF after $offset/$count bytes")
        offset += read
    }
    return out
}

internal fun sendHttpConnectAndReadStatus(
    input: InputStream,
    output: OutputStream,
    targetHost: String,
    targetPort: Int,
    username: String,
    password: String,
): Int {
    val authority = if (targetHost.contains(':') && !targetHost.startsWith("[")) {
        "[$targetHost]:$targetPort"
    } else {
        "$targetHost:$targetPort"
    }
    val req = StringBuilder(256)
        .append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
        .append("Host: ").append(authority).append("\r\n")
        .append("Proxy-Connection: keep-alive\r\n")
    if (username.isNotEmpty()) {
        val token = Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8)
        )
        req.append("Proxy-Authorization: Basic ").append(token).append("\r\n")
    }
    req.append("\r\n")
    output.write(req.toString().toByteArray(StandardCharsets.UTF_8))
    output.flush()

    val headerBytes = ByteArrayOutputStream(256)
    var state = 0
    while (headerBytes.size() < 8192) {
        val b = input.read()
        if (b < 0) throw EOFException("HTTP proxy closed before response headers")
        headerBytes.write(b)
        state = when {
            state == 0 && b == '\r'.code -> 1
            state == 1 && b == '\n'.code -> 2
            state == 2 && b == '\r'.code -> 3
            state == 3 && b == '\n'.code -> 4
            b == '\r'.code -> 1
            else -> 0
        }
        if (state == 4) break
    }
    if (state != 4) throw IOException("HTTP proxy header too large")
    val headerText = String(headerBytes.toByteArray(), StandardCharsets.ISO_8859_1)
    val statusLine = headerText.lineSequence().firstOrNull()?.trim().orEmpty()
    if (!statusLine.startsWith("HTTP/1.")) {
        throw IOException("Invalid HTTP proxy status: ${statusLine.take(40)}")
    }
    val parts = statusLine.split(' ').filter { it.isNotEmpty() }
    return parts.getOrNull(1)?.toIntOrNull()
        ?: throw IOException("Malformed HTTP proxy status code: ${statusLine.take(40)}")
}
