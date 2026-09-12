package com.vladimir.messenger.data.call

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Голосовой мост звонка через публичный MQTT-брокер: ОДНО постоянное TCP-
 * соединение на весь звонок, свой поток чтения, свой поток записи, минимальный
 * MQTT 3.1.1 (MqttPacket) — без ядра, без FFI, без 5-секундного опроса событий.
 *
 * Зачем отдельно от ядра. Голос через `RustBridge.sendMessageMqtt` открывал
 * НОВОЕ соединение с брокером на каждую публикацию (~1 с, блокирующе), а
 * приёмник видел пакеты только при опросе `drainEvents` раз в 5 с. При таком
 * пути через мобильную сеть звонок физически не мог состояться: сигналы ехали
 * секундами, голос не доезжал вовсе. Мост держит соединение открытым, кадры
 * уходят сразу (QoS0, без ожидания подтверждения), приём — блокирующее чтение
 * сокета, то есть задержка = сети, а не таймеру.
 *
 * Тема — `apucall1/<sha256(callId, nodeId получателя)>`, вне `p2pm2/#`: ядро
 * её не видит и своих событий не плодит. Мост не заменяет durable-сигнализацию
 * ядра (offer/accept/bye по-прежнему идут и через relay), а ускоряет её и несёт
 * голос. Если брокер недоступен — мост честно не поднимется, звонок пойдёт
 * прежним путём.
 */
class CallBrokerLink(
    private val callId: String,
    private val myNodeId: String,
    private val peerNodeId: String,
    private val onPacket: (ByteArray) -> Unit,
    private val onState: (LinkState) -> Unit,
) {
    enum class LinkState { CONNECTING, OPEN, CLOSED }

    private val myTopic = CallLinkWire.topicFor(callId, myNodeId)
    private val peerTopic = CallLinkWire.topicFor(callId, peerNodeId)

    @Volatile private var socket: Socket? = null
    @Volatile private var state = LinkState.CONNECTING
    private val closed: Boolean get() = state == LinkState.CLOSED
    @Volatile private var lastInboundAtMs = 0L
    @Volatile var brokerHost: String = ""
        private set

    /** Исходящие MQTT-пакеты; переполнение — выкидываем самые старые (живой голос, не почта). */
    private val outQueue = ArrayBlockingQueue<ByteArray>(64)
    private val sentCount = AtomicInteger()
    private val receivedCount = AtomicInteger()

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null

    fun isOpen(): Boolean = state == LinkState.OPEN

    /** Ещё соединяемся с брокером (ни OPEN, ни CLOSED). */
    fun isConnecting(): Boolean = state == LinkState.CONNECTING

    fun stats(): String = "sent=${sentCount.get()} recv=${receivedCount.get()} broker=$brokerHost"

    /** Поднять мост в фоне: перебирает брокеры по очереди, первый принявший CONNECT + SUBSCRIBE побеждает. */
    fun start() {
        if (readerThread != null) return
        onState(LinkState.CONNECTING)
        readerThread = Thread({ runReader() }, "call-link-$callId".take(24)).apply {
            isDaemon = true
            start()
        }
    }

    /** Отправить пакет собеседнику (в его тему). false = мост закрыт или пакет не влезает. */
    fun send(packet: ByteArray): Boolean {
        if (closed) return false
        val wire = MqttPacket.publish(peerTopic, packet) ?: return false
        return enqueueWire(wire)
    }

    /** Очередь хранит уже готовые MQTT-пакеты; переполнение — выкидываем самый старый. */
    private fun enqueueWire(wire: ByteArray): Boolean {
        if (!outQueue.offer(wire)) {
            outQueue.poll()
            outQueue.offer(wire)
        }
        return true
    }

    fun close() {
        synchronized(this) {
            if (closed) return
            state = LinkState.CLOSED
        }
        outQueue.clear()
        val s = socket
        socket = null
        if (s != null) {
            runCatching { s.getOutputStream().write(MqttPacket.DISCONNECT) }
            runCatching { s.close() }
        }
        writerThread?.interrupt()
        onState(LinkState.CLOSED)
        Log.i(TAG, "link closed for ${callId.take(8)}: ${stats()}")
    }

    // ── Потоки ──────────────────────────────────────────────────────────────

    private fun runReader() {
        var connected: Socket? = null
        for ((host, port) in BROKERS) {
            if (closed) break
            connected = runCatching { connect(host, port) }
                .onFailure { Log.w(TAG, "link connect $host failed: ${it.message}") }
                .getOrNull()
            if (connected != null) {
                brokerHost = host
                break
            }
        }
        val s = connected
        if (s == null || closed) {
            runCatching { s?.close() }
            close()
            return
        }
        synchronized(this) {
            if (closed) {
                runCatching { s.close() }
                return
            }
            socket = s
            state = LinkState.OPEN
        }
        lastInboundAtMs = System.currentTimeMillis()
        writerThread = Thread({ runWriter(s) }, "call-link-w").apply {
            isDaemon = true
            start()
        }
        onState(LinkState.OPEN)
        Log.i(TAG, "link open via $brokerHost for ${callId.take(8)}")
        try {
            readLoop(DataInputStream(s.getInputStream()))
        } catch (e: Exception) {
            if (!closed) Log.w(TAG, "link read ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            close()
        }
    }

    /** CONNECT → CONNACK → SUBSCRIBE(своя тема) → SUBACK, всё с таймаутами. */
    @Throws(IOException::class)
    private fun connect(host: String, port: Int): Socket {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
            val out = s.getOutputStream()
            val input = DataInputStream(s.getInputStream())
            // ≤ 23 знаков: столько любой брокер обязан принять по MQTT 3.1.1.
            val clientId = "apc" + myNodeId.takeLast(10) + callId.take(10)
            out.write(MqttPacket.connect(clientId, KEEP_ALIVE_SEC))
            out.flush()
            val (first, body) = readPacket(input)
            if (!MqttPacket.connackAccepted(first, body)) throw IOException("CONNACK refused")
            out.write(MqttPacket.subscribe(1, myTopic, 0))
            out.flush()
            // Ждём SUBACK; редкий чужой пакет до него (retained мусор) пропускаем.
            var acked = false
            repeat(4) {
                if (!acked) {
                    val (type, ackBody) = readPacket(input)
                    if (MqttPacket.packetType(type) == MqttPacket.TYPE_SUBACK) {
                        if (ackBody.size < 3 || (ackBody[2].toInt() and 0xFF) == 0x80) throw IOException("SUBACK refused")
                        acked = true
                    }
                }
            }
            if (!acked) throw IOException("no SUBACK")
            s.soTimeout = READ_TIMEOUT_MS
            return s
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
    }

    @Throws(IOException::class)
    private fun readLoop(input: DataInputStream) {
        while (!closed) {
            val (first, body) = readPacket(input)
            lastInboundAtMs = System.currentTimeMillis()
            when (MqttPacket.packetType(first)) {
                MqttPacket.TYPE_PUBLISH -> {
                    val publish = MqttPacket.parsePublish(first, body) ?: continue
                    if (publish.packetId != 0) enqueueWire(MqttPacket.puback(publish.packetId))
                    if (publish.topic != myTopic) continue
                    receivedCount.incrementAndGet()
                    runCatching { onPacket(publish.payload) }
                        .onFailure { Log.w(TAG, "link packet handler failed: ${it.message}") }
                }
                MqttPacket.TYPE_PINGRESP, MqttPacket.TYPE_PUBACK, MqttPacket.TYPE_SUBACK -> Unit
                MqttPacket.TYPE_DISCONNECT -> throw IOException("broker disconnected")
                else -> Unit
            }
        }
    }

    private fun runWriter(s: Socket) {
        val out: OutputStream = BufferedOutputStream(s.getOutputStream(), 16 * 1024)
        var lastPingAtMs = System.currentTimeMillis()
        try {
            while (!closed) {
                val packet = outQueue.poll(PING_EVERY_MS, TimeUnit.MILLISECONDS)
                val now = System.currentTimeMillis()
                // PINGREQ по расписанию независимо от нагрузки: PINGRESP — единственный
                // ответ брокера на односторонний поток QoS0, по нему видно, жив ли он.
                if (now - lastPingAtMs >= PING_EVERY_MS) {
                    out.write(MqttPacket.PINGREQ)
                    lastPingAtMs = now
                    if (packet == null) out.flush()
                }
                // Брокер молчит дольше двух keep-alive: соединение мертво.
                if (now - lastInboundAtMs > DEAD_AFTER_MS) throw IOException("broker silent")
                if (packet == null) continue
                out.write(packet)
                sentCount.incrementAndGet()
                // Слить всё, что накопилось, одним flush — меньше системных вызовов.
                while (true) {
                    val more = outQueue.poll() ?: break
                    out.write(more)
                    sentCount.incrementAndGet()
                }
                out.flush()
            }
        } catch (e: Exception) {
            if (!closed) Log.w(TAG, "link write ended: ${e.javaClass.simpleName}: ${e.message}")
            close()
        }
    }

    @Throws(IOException::class)
    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val first = input.readUnsignedByte()
        val length = MqttPacket.readRemainingLength(input)
        if (length > MqttPacket.MAX_INCOMING_BODY_BYTES) throw IOException("oversized packet $length")
        val body = ByteArray(length)
        input.readFully(body)
        return first to body
    }

    companion object {
        private const val TAG = "CallBrokerLink"

        /** Те же брокеры, что у ядра (rust-core MQTT_BROKERS) — они уже пропущены сетью. */
        val BROKERS: List<Pair<String, Int>> = listOf(
            "broker.hivemq.com" to 1883,
            "broker.emqx.io" to 1883,
        )

        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 90_000
        private const val KEEP_ALIVE_SEC = 30
        private const val PING_EVERY_MS = 10_000L
        private const val DEAD_AFTER_MS = 65_000L
    }
}
