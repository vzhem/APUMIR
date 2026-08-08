package com.vladimir.messenger.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.vladimir.messenger.domain.model.MessageChannel
import org.json.JSONObject
import androidx.core.app.NotificationCompat
import com.vladimir.messenger.MainActivity
import com.vladimir.messenger.MessengerApplication
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.repository.MtProxyRepository
import com.vladimir.messenger.service.NotificationHelper
import com.vladimir.messenger.service.BotApi
import com.vladimir.messenger.data.repository.ContactRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.p2p_core.CoreEventFfi
import javax.inject.Inject

@AndroidEntryPoint
class CoreServerService : Service() {

    private val TAG = "CoreServerService"
    private var telegramRelay: TelegramRelay? = null
    private var cloudflareRelay: CloudflareRelay? = null
    private val NOTIFICATION_ID = 1001

    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var contactRepository: ContactRepository
    @Inject lateinit var mtProxyRepository: MtProxyRepository
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var botApi: BotApi

    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkMonitor: NetworkMonitor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var eventPollingJob: Job? = null
    private var lastNotificationText: String = ""
    private val knownPeers = mutableMapOf<String, Long>()  // peerId -> lastSeenMs
    private val PEER_DEDUP_MS = 60_000L  // 60 сек дедупликация

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CoreServerService created")
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "P2PMessenger::CoreWakeLock"
        ).apply { acquire(10 * 60 * 1000L) }

        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("P2PMessenger::MdnsLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "MulticastLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock", e)
        }

        
        // ================================================================
        // РЕГИСТРАЦИЯ В CLOUDFLARE WORKER REGISTRY
        // (независимо от наличия telegram_bot_token)
        // ================================================================
        serviceScope.launch {
            try {
                // Ждём пока RustBridge инициализируется
                var attempts = 0
                while (RustBridge.nodeId() == null && attempts < 30) {
                    kotlinx.coroutines.delay(500)
                    attempts++
                }
                
                val myNodeId = RustBridge.nodeId()
                val myPubKey = RustBridge.publicKey()
                val prefs = getSharedPreferences("p2p_prefs", MODE_PRIVATE)
                val myName = prefs.getString("display_name", null) ?: "Unknown"
                
                if (myNodeId != null && myPubKey != null && myPubKey.isNotBlank()) {
                    Log.i(TAG, "Registering in registry: nodeId=${myNodeId.take(16)}... name=$myName")
                    val ok = botApi.registerMyself(myNodeId, myPubKey, myName)
                    Log.i(TAG, "Registry registration: $ok")
                } else {
                    Log.w(TAG, "Cannot register: nodeId=$myNodeId, pubKey=${myPubKey?.take(16) ?: "null"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Registry registration failed", e)
            }
        }

        networkMonitor = NetworkMonitor(this).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "CoreServerService started")
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

        val prefs = getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        var displayName = prefs.getString("display_name", "Anonymous") ?: "Anonymous"
        // сли Anonymous но профиль уже создан - перечитать
        if (displayName == "Anonymous" && prefs.getBoolean("identity_created", false)) {
            displayName = prefs.getString("display_name", "Anonymous") ?: "Anonymous"
        }
        val existingPubKey = prefs.getString("existing_public_key", null)
        val existingPrivKey = prefs.getString("existing_private_key", null)

        Log.i(TAG, "Starting engine: displayName=$displayName existingKey=${existingPubKey?.take(16)}")

        serviceScope.launch {
            val ok = RustBridge.initialize(displayName, existingPubKey, existingPrivKey)
            if (ok) {
                val nodeId = RustBridge.nodeId()
                Log.i(TAG, "Engine OK. NodeId=$nodeId")

            // Telegram Bot relay (запасной канал)
            val tgToken = prefs.getString("telegram_bot_token", "") ?: ""
            if (tgToken.isNotBlank()) {
                val tgProxyHost = prefs.getString("tg_proxy_host", "") ?: ""
                val tgProxyPort = prefs.getInt("tg_proxy_port", 0)
                telegramRelay = TelegramRelay(
                    botToken = tgToken,
                    myNodeId = nodeId ?: "",
                    scope = serviceScope,
                    proxyRepo = mtProxyRepository
                )
                telegramRelay?.onMessageReceived = { senderId: String, payload: String ->
                    Log.i(TAG, "TG relay message from $senderId")
                    // TODO: обработать входящее сообщение из TG
                }
                telegramRelay?.start()


                Log.i(TAG, "Telegram relay enabled")
            }

            // Cloudflare Workers relay (основной запасной канал)
            val cfUrl = prefs.getString("cloudflare_relay_url", "https://p2p-relay.1985vzhem.workers.dev") ?: "https://p2p-relay.1985vzhem.workers.dev"
            cloudflareRelay = CloudflareRelay(cfUrl, nodeId ?: "", serviceScope)
            cloudflareRelay?.onMessageReceived = { senderId: String, payload: String ->
                Log.i(TAG, "CF relay message from $senderId: ${payload.take(50)}")
                serviceScope.launch {
                    try {
                        // Парсить JSON payload от ChatRepository (если это наше сообщение)
                        val (messageId, content, timestamp) = try {
                            val json = JSONObject(payload)
                            if (json.optString("type") == "message") {
                                Triple(
                                    json.optString("messageId", java.util.UUID.randomUUID().toString()),
                                    json.optString("content", payload),
                                    json.optLong("timestamp", System.currentTimeMillis())
                                )
                            } else {
                                Triple(java.util.UUID.randomUUID().toString(), payload, System.currentTimeMillis())
                            }
                        } catch (e: Exception) {
                            // Не JSON — обычный plain-text
                            Triple(java.util.UUID.randomUUID().toString(), payload, System.currentTimeMillis())
                        }

                        // Дедупликация: проверить есть ли messageId в БД
                        val existingMsg = chatRepository.getMessageById(messageId)
                        if (existingMsg != null) {
                            Log.i(TAG, "CF duplicate skipped (already in DB): messageId=$messageId")
                            // Можно обновить channel, если CF подтвердил доставку
                            if (existingMsg.channel == MessageChannel.UNKNOWN || existingMsg.channel == MessageChannel.MQTT) {
                                // CF подтвердил доставку — оставляем channel как есть
                                Log.d(TAG, "Message already exists via another channel")
                            }
                            return@launch
                        }

                        // Получить/создать чат (используя senderId напрямую как contactId)
                        val contact = contactRepository.getContactById(senderId)
                        val contactName = contact?.displayName ?: senderId.take(16)
                        val chat = chatRepository.getOrCreateChat(senderId, contactName)
                        chatRepository.saveIncomingMessage(
                            chatId = chat.id,
                            senderId = senderId,
                            messageId = messageId,
                            content = content,
                            timestamp = timestamp,
                            channel = MessageChannel.CF,
                        )
                        Log.i(TAG, "CF message saved to chat ${chat.id}: ${content.take(30)}")
                    } catch (e: Exception) {
                        Log.e(TAG, "CF message handling failed", e)
                    }
                }
            }
            cloudflareRelay?.start()
            Log.i(TAG, "Cloudflare relay enabled: $cfUrl")

                if (nodeId != null) {
                    prefs.edit()
                        .putString("public_key", nodeId)
                        .putString("node_id", nodeId)
                        .putString("existing_public_key", nodeId)
                        .putString("existing_private_key", nodeId)
                        .apply()
                }

                updateNotification("P2P Active - ${nodeId?.take(8) ?: ""}...")
                startEventPolling()
            } else {
                updateNotification("Connection failed")
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * Reconnect с exponential backoff: 1s -> 2s -> 4s -> ... -> 60s max
     */
    private var reconnectAttempt = 0
    private val maxBackoff = 60_000L

    private fun reconnectWithBackoff() {
        val delay = minOf(1000L * (1L shl reconnectAttempt.coerceAtMost(6)), maxBackoff)
        reconnectAttempt++
        Log.w(TAG, "Reconnect attempt #$reconnectAttempt in ${delay}ms")
        serviceScope.launch {
            kotlinx.coroutines.delay(delay)
            try {
                // Перезапуск сервиса (onDestroy + onStartCommand)
                stopSelf()
                val restartIntent = android.content.Intent(applicationContext, CoreServerService::class.java)
                applicationContext.startForegroundService(restartIntent)
                reconnectAttempt = 0
                Log.i(TAG, "Reconnect: service restarted")
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect failed", e)
                reconnectWithBackoff()
            }
        }
    }

    override fun onDestroy() {
        telegramRelay?.stop()
        cloudflareRelay?.stop()
        Log.i(TAG, "CoreServerService destroyed")
        eventPollingJob?.cancel()
        networkMonitor?.stop()
        RustBridge.shutdown()
        try {
            multicastLock?.release()
            Log.i(TAG, "MulticastLock released")
        } catch (_: Exception) {}
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startEventPolling() {
        
        // Observer for incoming messages → show notifications
        serviceScope.launch {
            Log.i(TAG, "Starting message observer for notifications")
            chatRepository.observeAllMessages()
                .collect { messages ->
                    Log.d(TAG, "Message observer received ${messages.size} messages")
                    // Фильтруем только новые входящие (lastSeen = 0 или не прочитаны)
                    // Простая логика: если сообщение появилось в последние 2 секунды и входящее
                    val now = System.currentTimeMillis()
                    val recentIncoming = messages.filter { 
                        !it.isFromMe && (now - it.timestamp) < 2000 
                    }
                    for (msg in recentIncoming) {
                        try {
                            notificationHelper.showMessageNotification(
                                chatId = msg.chatId,
                                senderId = msg.senderId,
                                messageText = msg.content.take(200),
                                isIncoming = true
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to show notification: ${e.message}")
                        }
                    }
                }
        }

        eventPollingJob = serviceScope.launch {
            Log.i(TAG, "Event polling started")
            while (isActive) {
                try {
                    val events = RustBridge.drainEvents()
                    events.forEach { event -> handleEvent(event) }
                    val status = RustBridge.networkStatus()
                    val peers = RustBridge.connectedPeers()
                    updateNotification("$status - $peers peers")
                } catch (ex: Exception) {
                    Log.e(TAG, "Event polling error", ex)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun handleEvent(event: CoreEventFfi) {
        Log.d(TAG, "Event: ${event.eventType}")
        when (event.eventType) {
            "message_received" -> {
                val senderId = event.senderId ?: return
                val chatId = event.chatId ?: return
                val messageId = event.messageId ?: return
                val text = event.text ?: return
                val timestamp = event.timestamp ?: System.currentTimeMillis()

                Log.i(TAG, "Message from $senderId in chat $chatId: $text")
                try {
                    // P2P RELAY ФИЛЬТРАЦИЯ:
                    // Сохраняем ТОЛЬКО если контакт уже добавлен (это наш собеседник)
                    // Иначе — это широковещательное сообщение не для нас (ретранслируем, но не сохраняем)
                    // P2P ФИЛЬТРАЦИЯ: ищем чат по chatId из события
                    // chatId — это UUID переписки между конкретными людьми
                    // Если у нас нет такого чата — это чужая переписка, пропускаем
                    val chat = chatRepository.getChatById(chatId)
                    if (chat == null) {
                        Log.d(TAG, "No chat with id=$chatId — skipping (relay only, not our conversation)")
                        return
                    }
                    
                    // Дополнительная проверка: senderId должен быть контактом в этом чате
                    if (chat.contactId != senderId) {
                        Log.d(TAG, "Sender $senderId != chat.contactId ${chat.contactId} — skipping")
                        return
                    }
                    
                    chatRepository.saveIncomingMessage(
                        chatId = chat.id,
                        senderId = senderId,
                        messageId = messageId,
                        content = text,
                        timestamp = timestamp,
                        recipientId = RustBridge.nodeId() ?: "",
                    )
                    Log.i(TAG, "Saved incoming message to chat ${chat.id}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving incoming message", e)
                }
            }

            "peer_discovered" -> {
                val peerId = event.peerId ?: return
                if (!peerId.startsWith("pk_")) return  // Skip mDNS duplicates
                val peerName = event.displayName ?: "Unknown"
                val now = System.currentTimeMillis()
                val lastSeen = knownPeers[peerId] ?: 0L
                if (now - lastSeen < PEER_DEDUP_MS) return  // дедупликация
                knownPeers[peerId] = now
                Log.i(TAG, "Peer discovered: $peerId ($peerName)")

                // Автосоздание контакта и чата
                try {
                    contactRepository.updateOnlineStatus(peerId, true)
                    // Обновляем имя если изменилось (например после перезапуска)
                    val existing = contactRepository.getContactByFingerprint(peerId)
                    if (existing == null) {
                        contactRepository.addContact(peerName, peerId)
                    } else if (existing.displayName != peerName && peerName != "Unknown") {
                        contactRepository.updateDisplayName(existing.id, peerName)
                    }
                    // Всегда обновляем имя в чате (на случай если контакт создан с Anonymous)
                    if (peerName != "Unknown" && peerName != "Anonymous") {
                        chatRepository.updateContactName(peerId, peerName)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Update contact failed", e)
                }

                try {
                    val retried = chatRepository.retryPendingMessagesForPeer(peerId)
                    if (retried > 0) {
                        Log.i(TAG, "Retried $retried pending messages for peer=$peerId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Retry pending failed", e)
                }
            }

            "peer_lost" -> {
                val peerId = event.peerId ?: return
                Log.i(TAG, "Peer lost: $peerId")
                try { contactRepository.updateOnlineStatus(peerId, false) } catch (_: Exception) {}
            }

            "network_status_changed" -> {
                val status = event.status ?: "unknown"
                Log.i(TAG, "Network status: $status")
                updateNotification("$status - ${RustBridge.connectedPeers()} peers")

                if (status == "connected") {
                    try {
                        val retried = chatRepository.retryAllPendingMessages()
                        if (retried > 0) {
                            Log.i(TAG, "Retried $retried pending messages after connected")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Retry all pending failed", e)
                    }
                }
            }

            "message_status_changed" -> {
                Log.i(TAG, "Message status changed: ${event.messageId} -> ${event.status}")
            }

            "keys_generated" -> Log.i(TAG, "Keys generated")
            "engine_started" -> Log.i(TAG, "Engine started OK")
            "error" -> Log.e(TAG, "Engine error: ${event.text}")
            else -> Log.d(TAG, "Unknown event: ${event.eventType}")
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MessengerApplication.CHANNEL_ID)
            .setContentTitle("P2P Messenger")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        if (status == lastNotificationText) return
        lastNotificationText = status
        val notification = buildNotification(status)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val POLL_INTERVAL_MS = 5000L
    }
}