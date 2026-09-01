package com.vladimir.messenger.data.call

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Выделенный сокет голоса звонка в общей Wi-Fi (CALLS_BOOTSTRAP.md, 8.4, путь 1).
 *
 * Отдельно от LanDirectChannel файлов: 50 кадров/с нельзя гнать через файловый
 * конвейер (его route делает Room-запросы на кадр). Устройство как у файлового
 * канала: принимающая сторона — сервер на фиксированном порту 42109, звонящий
 * подключается по endpoint из сигналов offer/accept, соединение одно на звонок,
 * полнодуплексное: обе стороны пишут кадры в один сокет.
 *
 * Провод (бинарный, length-prefixed, без неоднозначности разделителей):
 *   handshake:  "APUCALLHS1|<nodeId>|<callId>|<proto>"  → ответ "APUCALLHS1|ok|<nodeId>"
 *   кадр:       [u32 len][u16 codec][u32 seq][u64 ptsMs][payload]
 *   len = 14 + payload.size (заголовок 2+4+8), кадр целиком ≤ 64 КиБ.
 *
 * Сам сокет ничего не шифрует и никого не аутентифицирует: кадры уже закрыты
 * AES-128-GCM ключом звонка (см. CallMediaCrypto), а входящее соединение принимается,
 * только если его callId совпал с активным (армирует CallManager).
 */
class CallAudioChannel private constructor() {

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "call-audio-channel").apply { isDaemon = true }
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile var listenPort: Int = 0
        private set

    /** Звонок, ради которого сервер принимает входящие. null — входящих нет. */
    @Volatile var activeCallId: String? = null

    /** Node id подставляет менеджер (как у LanDirectChannel — без зависимости от Rust). */
    @Volatile var myNodeId: String = "pk_unknown"

    /** Кадр от собеседника (уже зашифрованный payload — расшифрует движок). */
    @Volatile var onFrame: ((seq: Long, ptsMs: Long, payload: ByteArray) -> Unit)? = null

    /** Сокет поднялся: входящий принят и опознан, либо исходящий соединился. */
    @Volatile var onConnected: (() -> Unit)? = null

    /** Сокет звонка умер (таймаут/обрыв). */
    @Volatile var onClosed: (() -> Unit)? = null

    @Volatile private var callSocket: Socket? = null

    // ── Серверная сторона (принимающий звонок телефон) ──────────────────────

    /** Идемпотентно. Биндит все интерфейсы: фиксированный порт звонков, иначе эфемерный. */
    @Synchronized
    fun startServer() {
        if (server != null) return
        val local = try {
            ServerSocket(CALL_LAN_PORT, 8)
        } catch (e: IOException) {
            diag("call-server fixed port $CALL_LAN_PORT busy, using ephemeral: ${e.message}")
            ServerSocket(0, 8)
        }
        server = local
        listenPort = local.localPort
        diag("call-server started on port $listenPort")
        executor.execute { acceptLoop(local) }
    }

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                break
            }
            executor.execute { serveConnection(socket) }
        }
    }

    private fun serveConnection(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = IDLE_READ_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val handshake = readTextFrame(input)
            val fields = handshake.split('|')
            require(fields.size == 4 && fields[0] == HANDSHAKE_PREFIX) { "bad call handshake" }
            require(fields[1].startsWith("pk_")) { "bad call node id" }
            require(fields[2] == activeCallId && activeCallId != null) { "unknown call id" }
            require(fields[3] == CallWire.PROTO_TCP1) { "unsupported call proto" }
            writeTextFrame(output, "$HANDSHAKE_PREFIX|ok|$myNodeId")
            output.flush()
            diag("call-handshake ok from ${socket.inetAddress?.hostAddress}")
            require(ensureAdopted(socket)) { "call socket already adopted" }
            readFrames(input)
        } catch (e: Exception) {
            diag("call-connection dropped: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            releaseSocket(socket)
        }
    }

    // ── Клиентская сторона (звонящий) ───────────────────────────────────────

    fun isOpen(): Boolean {
        val socket = callSocket
        return socket != null && !socket.isClosed
    }

    /**
     * Наш IPv4 в общей Wi-Fi для сигналов offer/accept. null = нет Wi-Fi-интерфейса
     * или сервер звонков не поднялся — собеседнику шлём "-", стучаться не надо.
     * Выбор интерфейса — у файлового канала (та же логика, что и в LanDirectChannel).
     */
    fun lanEndpointHost(): String? {
        if (listenPort !in 1024..65535) return null
        val address = runCatching {
            com.vladimir.messenger.data.file.LanDirectChannel.get().lanEndpoint()
        }.getOrNull() ?: return null
        return address.hostAddress
    }

    /** Открыть канал с паузами на установление (зовёт менеджер из CONNECTING). */
    suspend fun awaitOpen(host: String, port: Int): Boolean = open(host, port)

    /** Подключиться к сокету звонка собеседника, рукопожаться, начать читать кадры. */
    suspend fun open(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        if (isOpen()) return@withContext true
        val expectedCallId = activeCallId ?: return@withContext false
        val socket = Socket()
        val ok = try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HELLO_REPLY_TIMEOUT_MS
            val output = DataOutputStream(socket.getOutputStream())
            writeTextFrame(output, "$HANDSHAKE_PREFIX|$myNodeId|$expectedCallId|${CallWire.PROTO_TCP1}")
            output.flush()
            val input = DataInputStream(socket.getInputStream())
            val reply = readTextFrame(input)
            val fields = reply.split('|')
            require(fields.size == 3 && fields[0] == HANDSHAKE_PREFIX && fields[1] == "ok") {
                "bad call hello reply"
            }
            socket.soTimeout = IDLE_READ_TIMEOUT_MS
            true
        } catch (e: Exception) {
            diag("call-connect to $host:$port failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (ok) {
            val adopted = ensureAdopted(socket)
            if (adopted) {
                executor.execute {
                    try {
                        readFrames(DataInputStream(socket.getInputStream()))
                    } catch (e: Exception) {
                        diag("call-read ended: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        releaseSocket(socket)
                    }
                }
                true
            } else {
                // Гонка: входящее соединение уже принято — исходящее не нужно.
                try { socket.close() } catch (ignored: IOException) { }
                isOpen()
            }
        } else {
            try { socket.close() } catch (ignored: IOException) { }
            false
        }
    }

    // ── Кадры ───────────────────────────────────────────────────────────────

    /** Отправить один голосовой кадр. false = сокета нет/умер, зови текстовый фолбэк. */
    fun sendFrame(seq: Long, ptsMs: Long, payload: ByteArray): Boolean {
        val socket = callSocket ?: return false
        if (socket.isClosed) return false
        return try {
            val output = DataOutputStream(socket.getOutputStream())
            synchronized(socket) {
                output.writeInt(FRAME_HEADER_BYTES + payload.size)
                output.writeShort(CallWire.CODEC_PCM_16K)
                output.writeInt((seq and 0xFFFFFFFFL).toInt())
                output.writeLong(ptsMs)
                output.write(payload)
                output.flush()
            }
            true
        } catch (e: IOException) {
            diag("call-send failed: ${e.javaClass.simpleName}: ${e.message}")
            closeCall()
            false
        }
    }

    private fun readFrames(input: DataInputStream) {
        while (true) {
            val length = input.readInt()
            require(length in (FRAME_HEADER_BYTES + 1)..MAX_CALL_FRAME_BYTES) { "bad call frame length" }
            val codec = input.readShort()
            val seq = input.readInt().toLong() and 0xFFFFFFFFL
            val ptsMs = input.readLong()
            val payload = ByteArray(length - FRAME_HEADER_BYTES)
            input.readFully(payload)
            if (codec.toInt() == CallWire.CODEC_PCM_16K) {
                onFrame?.invoke(seq, ptsMs, payload)
            }
        }
    }

    // ── Жизненный цикл ──────────────────────────────────────────────────────

    /** Гасит сокет звонка (сервер живёт — телефон может принимать следующие звонки). */
    fun closeCall() {
        val socket = callSocket ?: return
        callSocket = null
        try { socket.close() } catch (ignored: IOException) { }
    }

    /** Гонка двух подключений: первое живое остаётся, лишний сокет не принимается. */
    @Synchronized
    private fun ensureAdopted(socket: Socket): Boolean {
        val existing = callSocket
        if (existing != null && !existing.isClosed) return false
        callSocket = socket
        executor.execute { onConnected?.invoke() }
        return true
    }

    @Synchronized
    private fun releaseSocket(socket: Socket) {
        if (callSocket === socket) {
            callSocket = null
            executor.execute { onClosed?.invoke() }
        }
        try { socket.close() } catch (ignored: IOException) { }
    }

    private fun readTextFrame(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 1..MAX_TEXT_FRAME_BYTES) { "bad call text frame" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun writeTextFrame(output: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun diag(message: String) {
        android.util.Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "CallAudioChannel"
        private const val HANDSHAKE_PREFIX = "APUCALLHS1"

        /** Фиксированный порт сокета звонка (рядом с 42108 файлового канала). */
        const val CALL_LAN_PORT = 42109
        private const val FRAME_HEADER_BYTES = 14
        private const val MAX_CALL_FRAME_BYTES = 64 * 1024
        private const val MAX_TEXT_FRAME_BYTES = 4 * 1024
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HELLO_REPLY_TIMEOUT_MS = 2_000
        private const val IDLE_READ_TIMEOUT_MS = 15 * 60 * 1000

        @Volatile private var instance: CallAudioChannel? = null

        fun get(): CallAudioChannel {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) instance = CallAudioChannel()
                }
            }
            return instance!!
        }
    }
}
