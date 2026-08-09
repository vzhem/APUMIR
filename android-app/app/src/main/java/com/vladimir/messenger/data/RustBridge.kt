package com.vladimir.messenger.data

import android.util.Log
import uniffi.p2p_core.ChatFfi
import uniffi.p2p_core.CoreEventFfi
import uniffi.p2p_core.MessageFfi
import uniffi.p2p_core.P2pCoreHandle
import uniffi.p2p_core.createEngine
import uniffi.p2p_core.createEngineWithKeys
import uniffi.p2p_core.getVersion
import uniffi.p2p_core.initializeCore

object RustBridge {

    private const val TAG = "RustBridge"

    @Volatile
    private var engine: P2pCoreHandle? = null

    @Volatile
    private var coreInitialized: Boolean = false

    fun initialize(
        displayName: String,
        existingPublicKey: String? = null,
        existingPrivateKey: String? = null,
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

            val e = if (!existingPublicKey.isNullOrEmpty()) {
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
            } else {
                Log.e(TAG, "Engine.start() returned false")
            }
            ok
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to initialize engine", ex)
            false
        }
    }

    fun shutdown() {
        try {
            engine?.stop()
            engine = null
            Log.i(TAG, "Engine stopped")
        } catch (ex: Exception) {
            Log.e(TAG, "Error stopping engine", ex)
        }
    }

    fun isRunning(): Boolean = engine?.isRunning() == true

    fun nodeId(): String? = engine?.nodeId()
    fun publicKey(): String? = engine?.publicKey()

    fun networkStatus(): String = engine?.networkStatus() ?: "offline"
    fun connectedPeers(): Long = engine?.connectedPeers()?.toLong() ?: 0L

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
            val sent = engine?.sendMessage(messageId, chatId, recipientId, text) == true
            // MQTT fallback: only pk_ recipients (avoid duplicate chats)
            if (recipientId.startsWith("pk_")) {
                val myId = nodeId() ?: "unknown"
                // Формат: senderId|messageId|chatId|recipientId|text
                val payload = "$myId|$messageId|$chatId|$recipientId|$text"
                val mqttOk = sendMessageMqtt(recipientId, payload)
                Log.i(TAG, "MQTT fallback to $recipientId: $mqttOk")
            }
            sent
        } catch (ex: Exception) {
            Log.e(TAG, "sendMessage error", ex)
            false
        }
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

}
