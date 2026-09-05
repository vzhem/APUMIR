package com.vladimir.messenger.data

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.file.FileTransferWire
import com.vladimir.messenger.data.security.MessageSealer
import com.vladimir.messenger.data.security.SealedWire
import uniffi.p2p_core.ChatFfi
import uniffi.p2p_core.CoreEventFfi
import uniffi.p2p_core.MessageFfi
import uniffi.p2p_core.P2pCoreHandle
import uniffi.p2p_core.createEngine
import uniffi.p2p_core.createEngineDurable
import uniffi.p2p_core.createEngineWithKeys
import uniffi.p2p_core.getVersion
import uniffi.p2p_core.initializeCore

object RustBridge {

    private const val TAG = "RustBridge"
    private const val MAX_RELAY_WAKE_WINDOW_MILLIS = 30_000L

    @Volatile
    private var engine: P2pCoreHandle? = null

    @Volatile
    private var coreInitialized: Boolean = false

    /**
     * Контекст приложения для шифрования переписки. Точка отправки не suspend
     * и вызывается из многих мест, поэтому контекст ставится один раз на
     * старте сервиса, а не протаскивается через каждый вызов.
     */
    @Volatile
    private var appContext: Context? = null

    fun attachContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * M8-C: [relayDbPath] — собственный SQLite-файл durable encrypted relay
     * custody (app-private). Передаётся только после того, как
     * [com.vladimir.messenger.data.security.RelayAtRestMasterKey.installIntoCore]
     * попытался установить at-rest ключ: установленный ключ + путь =
     * durable-encrypted режим; без ключа движок честно уйдёт в RAM-only.
     */
    @Synchronized
    fun initialize(
        displayName: String,
        existingPublicKey: String? = null,
        existingPrivateKey: String? = null,
        relayDbPath: String? = null,
    ): Boolean {
        if (engine != null) {
            Log.w(TAG, "Engine already initialized")
            return true
        }

        return try {
            if (!coreInitialized) {
                val initResult = initializeCore()
                Log.i(TAG, "initializeCore(): $initResult")
                coreInitialized = true
            }

            Log.i(TAG, "Rust version: ${getVersion()}")

            val e = if (!relayDbPath.isNullOrBlank()) {
                // M8-C: durable-режим. Пустые строки ключей = «сгенерировать новые».
                Log.i(TAG, "Creating durable engine, relayDbPath=$relayDbPath")
                createEngineDurable(
                    displayName,
                    existingPublicKey ?: "",
                    existingPrivateKey ?: "",
                    relayDbPath,
                )
            } else if (!existingPublicKey.isNullOrEmpty()) {
                Log.i(TAG, "Restoring engine with key: ${existingPublicKey.take(16)}")
                createEngineWithKeys(displayName, existingPublicKey, existingPrivateKey ?: "")
            } else {
                Log.i(TAG, "Creating engine without existing keys")
                createEngine(displayName)
            }

            val ok = e.start()
            if (ok) {
                engine = e
                Log.i(TAG, "Engine started. NodeId: ${e.nodeId()}")
                // M8-C: честный режим custody виден в логах (acceptance M8-F).
                Log.i(
                    TAG,
                    "Relay custody mode: ${e.relayCustodyMode()}, quarantined: ${e.relayQuarantineCount()}"
                )
            } else {
                Log.e(TAG, "Engine.start() returned false")
            }
            ok
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to initialize engine", ex)
            false
        }
    }

    @Synchronized
    fun shutdown() {
        try {
            engine?.stop()
            engine = null
            Log.i(TAG, "Engine stopped")
        } catch (ex: Exception) {
            Log.e(TAG, "Error stopping engine", ex)
        }
    }

    /** Результат одного ограниченного M8-E wake-окна. */
    data class RelayWakeResult(
        val engineStartedByWorker: Boolean,
        val gossipTriggered: Boolean,
        val custodyMode: String,
        val quarantineCount: Long,
    )

    /**
     * M8-E slice 1: одно bounded receive-only окно для WorkManager.
     *
     * Монитор объекта удерживается на всём окне намеренно: обычный foreground
     * service не сможет одновременно создать второй engine. Если service уже
     * владеет engine, worker только просит bounded gossip и НЕ останавливает его.
     * Если engine поднят worker-ом, он гарантированно остановится в finally.
     */
    @Synchronized
    fun runBoundedRelayWake(
        displayName: String,
        existingPublicKey: String?,
        existingPrivateKey: String?,
        relayDbPath: String,
        activeWindowMillis: Long,
    ): RelayWakeResult {
        require(activeWindowMillis in 1_000L..MAX_RELAY_WAKE_WINDOW_MILLIS) {
            "relay wake window must be 1s..${MAX_RELAY_WAKE_WINDOW_MILLIS}ms"
        }

        if (engine?.isRunning() == true) {
            return RelayWakeResult(
                engineStartedByWorker = false,
                gossipTriggered = triggerGossipDiscovery(),
                custodyMode = relayCustodyMode(),
                quarantineCount = relayQuarantineCount(),
            )
        }

        val started = initialize(
            displayName = displayName,
            existingPublicKey = existingPublicKey,
            existingPrivateKey = existingPrivateKey,
            relayDbPath = relayDbPath,
        )
        if (!started) {
            return RelayWakeResult(false, false, "disabled", 0L)
        }

        return try {
            val gossipTriggered = triggerGossipDiscovery()
            Thread.sleep(activeWindowMillis)
            RelayWakeResult(
                engineStartedByWorker = true,
                gossipTriggered = gossipTriggered,
                custodyMode = relayCustodyMode(),
                quarantineCount = relayQuarantineCount(),
            )
        } finally {
            shutdown()
        }
    }

    fun isRunning(): Boolean = engine?.isRunning() == true

    fun nodeId(): String? = engine?.nodeId()
    fun publicKey(): String? = engine?.publicKey()

    fun networkStatus(): String = engine?.networkStatus() ?: "offline"
    fun connectedPeers(): Long = engine?.connectedPeers()?.toLong() ?: 0L

    fun triggerGossipDiscovery(): Boolean {
        return try {
            engine?.triggerGossipDiscovery() ?: false
        } catch (e: Exception) {
            android.util.Log.w(TAG, "triggerGossipDiscovery failed: ${e.message}")
            false
        }
    }

    fun onNetworkAvailable() {
        try { engine?.onNetworkAvailable() }
        catch (ex: Exception) { Log.e(TAG, "onNetworkAvailable error", ex) }
    }

    fun onNetworkLost() {
        try { engine?.onNetworkLost() }
        catch (ex: Exception) { Log.e(TAG, "onNetworkLost error", ex) }
    }

    fun sendMessage(
        messageId: String,
        chatId: String,
        recipientId: String,
        text: String
    ): Boolean {
        return try {
            // Rust owns the persistent direct/offline mesh send path. A false result means the
            // message remains phone-owned QUEUED_OFFLINE; do not create a transient MQTT session
            // or claim SENT merely because a local publish request was accepted.
            // ШИФРОВАНИЕ: наружу уходит запечатанный конверт, а не открытый
            // текст. Прямой QUIC защищён TLS, но путь через ретранслятор идёт
            // по чужому брокеру, где открытый текст читается кем угодно.
            val payload = sealOutgoing(recipientId, text)
            val sentDirectly = engine?.sendMessage(messageId, chatId, recipientId, payload) == true
            Log.i(TAG, "Rust send result: direct=$sentDirectly sealed=${payload !== text}")
            sentDirectly
        } catch (ex: Exception) {
            Log.e(TAG, "sendMessage error", ex)
            false
        }
    }

    /**
     * Запечатать исходящее, если ключ собеседника известен.
     *
     * Уже запечатанное не трогаем. Если ключа ещё нет, возвращаем исходный
     * текст: обмен ключами идёт пакетом HELLO и занимает секунды, а молча
     * ронять сообщение хуже. Такое возможно только до первого обмена ключами.
     */
    private fun sealOutgoing(recipientId: String, text: String): String {
        if (!recipientId.startsWith("pk_")) return text
        if (SealedWire.isSealed(text)) return text
        // HELLO несёт сам открытый ключ и обязан идти незапечатанным: иначе
        // первый обмен ключами заклинит - шифровать нечем, пока ключ не пришёл.
        // Секрета в нём нет, это открытая часть подписанной привязки.
        if (text.startsWith(FileTransferWire.HELLO_PREFIX)) return text
        val context = appContext ?: return text
        val myNodeId = nodeId() ?: return text
        val sealed = MessageSealer.seal(context, myNodeId, recipientId, text)
        if (sealed == null) {
            Log.w(TAG, "No key yet for ${recipientId.takeLast(8)}; sending unsealed")
            return text
        }
        return sealed
    }

    fun receiveMessage(
        messageId: String,
        chatId: String,
        senderId: String,
        encryptedText: String,
        timestamp: Long
    ) {
        try {
            engine?.receiveMessage(messageId, chatId, senderId, encryptedText, timestamp)
        } catch (ex: Exception) {
            Log.e(TAG, "receiveMessage error", ex)
        }
    }

    fun markMessageRead(messageId: String): Boolean {
        return try {
            engine?.markMessageRead(messageId) == true
        } catch (ex: Exception) {
            Log.e(TAG, "markMessageRead error", ex)
            false
        }
    }

    fun createChat(chatId: String): Boolean {
        return try {
            engine?.createChat(chatId) == true
        } catch (ex: Exception) {
            Log.e(TAG, "createChat error", ex)
            false
        }
    }

    fun addContact(userId: String, displayName: String): Boolean {
        return try {
            engine?.addContact(userId, displayName) == true
        } catch (ex: Exception) {
            Log.e(TAG, "addContact error", ex)
            false
        }
    }

    fun generateInvite(): String {
        return engine?.generateInvite() ?: ""
    }

    fun connectViaInvite(link: String): Boolean {
        android.util.Log.i("RustBridge", "connectViaInvite: $link")
        val result = engine?.connectViaInvite(link) ?: false
        android.util.Log.i("RustBridge", "connectViaInvite result: $result")
        return result
    }
    fun getChats(): List<ChatFfi> {
        return try {
            engine?.getChats() ?: emptyList()
        } catch (ex: Exception) {
            Log.e(TAG, "getChats error", ex)
            emptyList()
        }
    }

    fun getMessages(chatId: String, limit: Int = 50): List<MessageFfi> {
        return try {
            engine?.getMessages(chatId, limit.toULong()) ?: emptyList()
        } catch (ex: Exception) {
            Log.e(TAG, "getMessages error", ex)
            emptyList()
        }
    }

    fun pollEvent(): CoreEventFfi? {
        return try {
            engine?.pollEvent()
        } catch (ex: Exception) {
            Log.e(TAG, "pollEvent error", ex)
            null
        }
    }

    fun drainEvents(): List<CoreEventFfi> {
        return try {
            engine?.drainEvents() ?: emptyList()
        } catch (ex: Exception) {
            Log.e(TAG, "drainEvents error", ex)
            emptyList()
        }
    }

    fun pendingEvents(): Long = engine?.pendingEvents()?.toLong() ?: 0L

    /** M8-C: честный режим relay custody ("durable-encrypted" / "ram-only" / "disabled"). */
    fun relayCustodyMode(): String = try {
        engine?.relayCustodyMode() ?: "disabled"
    } catch (e: Exception) {
        Log.w(TAG, "relayCustodyMode failed: ${e.message}")
        "unknown"
    }

    /**
     * «Любая сеть»: направить все MQTT-соединения движка через SOCKS5-прокси.
     * Глобальная функция (работает и без engine — применится при следующем подключении).
     */
    fun setMqttSocks5Proxy(host: String, port: Int, username: String, password: String): Boolean =
        try {
            uniffi.p2p_core.setMqttSocks5Proxy(host, port.toUShort(), username, password)
            Log.i(TAG, "MQTT SOCKS5 proxy set: $host:$port")
            true
        } catch (e: Exception) {
            Log.w(TAG, "setMqttSocks5Proxy failed: ${e.message}")
            false
        }

    /**
     * Параллельный QUIC-поток для файлов: отправка напрямую БЕЗ relay queue.
     * true = QUIC-доставка удалась; false = получатель недоступен напрямую.
     */
    fun sendDirectPayload(recipientId: String, payload: String): Boolean = try {
        val handle = engine
        if (handle != null) {
            // Прямой путь уже под TLS 1.3, но запечатываем и его: тот же
            // payload при смене маршрута может уйти через ретранслятор.
            handle.sendDirectPayload(recipientId, sealOutgoing(recipientId, payload))
        } else {
            false
        }
    } catch (e: Exception) {
        Log.w(TAG, "sendDirectPayload failed: ${e.message}")
        false
    }

    /** «Любая сеть»: MQTT снова напрямую. */
    fun clearMqttSocks5Proxy() = try {
        uniffi.p2p_core.clearMqttSocks5Proxy()
        Log.i(TAG, "MQTT SOCKS5 proxy cleared")
    } catch (e: Exception) {
        Log.w(TAG, "clearMqttSocks5Proxy failed: ${e.message}")
    }

    /** M8-C: число relay-записей в карантине (диагностика честной потери custody). */
    fun relayQuarantineCount(): Long = try {
        engine?.relayQuarantineCount()?.toLong() ?: 0L
    } catch (e: Exception) {
        Log.w(TAG, "relayQuarantineCount failed: ${e.message}")
        0L
    }

    fun sendMessageMqtt(toNodeId: String, payload: String): Boolean {
        return try {
            engine?.sendMessageMqtt(toNodeId, payload) ?: false
        } catch (e: Exception) {
            android.util.Log.w("RustBridge", "MQTT send failed: ${e.message}")
            false
        }
    }
    fun sendViaTcp(host: String, port: Int, payload: String): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 10000)
            socket.getOutputStream().write(payload.toByteArray())
            socket.getOutputStream().flush()
            val ack = ByteArray(3)
            socket.getInputStream().read(ack)
            socket.close()
            android.util.Log.i("RustBridge", "TCP sent to $host:$port, ACK=${String(ack)}")
            true
        } catch (e: Exception) {
            android.util.Log.w("RustBridge", "TCP failed to $host:$port: ${e.message}")
            false
        }
    }
    /**
     * Получить публичный ключ текущего устройства.
     */
    fun getPublicKey(nodeId: String): String? {
        return try {
            engine?.publicKey()
        } catch (e: Exception) {
            android.util.Log.e("RustBridge", "getPublicKey failed", e)
            null
        }
    }


    fun sendDeliveryAck(messageId: String, recipientId: String): Boolean {
        return try {
            // Отправляем ACK через MQTT в формате: ack|messageId
            val ackPayload = "ack|$messageId"
            engine?.sendMessageMqtt(recipientId, ackPayload) ?: false
        } catch (e: Exception) {
            android.util.Log.w("RustBridge", "sendDeliveryAck failed: ${e.message}")
            false
        }
    }

}
