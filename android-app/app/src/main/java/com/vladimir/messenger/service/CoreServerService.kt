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
import com.vladimir.messenger.data.relay.RelayEnvelope
import com.vladimir.messenger.domain.model.MessageStatus
import androidx.core.app.NotificationCompat
import com.vladimir.messenger.MainActivity
import com.vladimir.messenger.MessengerApplication
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.repository.MtProxyRepository
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import com.vladimir.messenger.data.referral.ReferralRankStore
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import com.vladimir.messenger.data.security.RelayAtRestMasterKey
import com.vladimir.messenger.service.NotificationHelper
import com.vladimir.messenger.service.BotApi
import com.vladimir.messenger.data.repository.ContactRepository
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
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
    @Inject lateinit var fileTransferRouter: com.vladimir.messenger.data.file.FileTransferRouter
    @Inject lateinit var proxyAutopilot: com.vladimir.messenger.service.ProxyAutopilot

    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkMonitor: NetworkMonitor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var eventPollingJob: Job? = null
    private var filePumpJob: Job? = null
    private var lastNotificationText: String = ""
    private val knownPeers = mutableMapOf<String, Long>()  // peerId -> lastSeenMs
    private val PEER_DEDUP_MS = 30000L  // 60 сек дедупликация
    private val FILE_PUMP_INTERVAL_MS = 20000L
    private val INITIAL_FILE_PUMP_DELAY_MS = 5000L

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
            // R0.5/S3: legacy routing ID остаётся неизменным; реальный Ed25519
            // signing sidecar устанавливается до engine start и пока используется
            // только diagnostics/future signed features.
            val legacyRoutingId = existingPubKey
                ?: prefs.getString("node_id", null)
                .orEmpty()
            val signing = if (prefs.getBoolean("identity_created", false)) {
                IdentitySigningKeyStore.installIntoCore(applicationContext, legacyRoutingId)
            } else {
                null
            }
            Log.i(
                TAG,
                "Identity signing mode: ${signing?.mode ?: "legacy-only"}, " +
                    "keyId=${signing?.keyId?.take(12) ?: "none"}"
            )
            val fileExchange = if (signing != null) {
                IdentitySigningKeyStore.existingVerifiedBinding(applicationContext)?.let { identityBinding ->
                    FileExchangeKeyStore.initialize(applicationContext, legacyRoutingId, identityBinding)
                }
            } else null
            Log.i(
                TAG,
                "File exchange identity: ${if (fileExchange != null) "ready" else "unavailable"}, " +
                    "key=${fileExchange?.publicKeySha256Prefix ?: "none"}"
            )

            // M8-C slice 3: Android Keystore мост. Устанавливаем at-rest ключ
            // СТРОГО ДО старта движка. Недоступность ключа = честный RAM-only
            // degrade (durable custody не заявляется, файл не создаётся).
            val atRestKeyOk = RelayAtRestMasterKey.installIntoCore(applicationContext)
            Log.i(TAG, "Relay at-rest key installed: $atRestKeyOk")

            // Собственный SQLite-файл relay custody (app-private, WAL).
            val relayDbPath = File(filesDir, "apu_relay.sqlite").absolutePath
            val ok = RustBridge.initialize(displayName, existingPubKey, existingPrivKey, relayDbPath)
            if (ok) {
                val nodeId = RustBridge.nodeId()
                Log.i(TAG, "Engine OK. NodeId=$nodeId")

                // Прокси-автопилот: первичный цикл при старте — проверить пул, убрать мёртвых,
                // выбрать и подключить лучшего (без принудительного сбора).
                serviceScope.launch {
                    try {
                        proxyAutopilot.cycle()
                    } catch (e: Exception) {
                        Log.w(TAG, "Proxy autopilot startup cycle: ${e.message}")
                    }
                }

            // Telegram Bot relay (запасной канал)
            val tgToken = prefs.getString("telegram_bot_token", "") ?: ""
            if (tgToken.isNotBlank()) {
                val tgProxyHost = prefs.getString("tg_proxy_host", "") ?: ""
                val tgProxyPort = prefs.getInt("tg_proxy_port", 0)
                telegramRelay = TelegramRelay(
                    botToken = tgToken,
                    myNodeId = nodeId ?: "",
                    scope = serviceScope,
                    proxyRepo = mtProxyRepository,
                    autopilot = proxyAutopilot,
                    automaticProxyAllowed = {
                        FileTransferRankPolicy.canUseAutomaticProxy(
                            ReferralRankStore.qualifiedDirectCount(applicationContext)
                        )
                    }
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
                Log.i(TAG, "CF relay payload from $senderId: ${payload.take(50)}")
                serviceScope.launch {
                    try {
                        when (val parsed = RelayEnvelope.parse(payload)) {
                            is RelayEnvelope.Parsed.Ack -> {
                                // G1 fix: ACK, доставленный через relay, → DELIVERED у отправителя.
                                val messageId = parsed.messageId
                                val existing = chatRepository.getMessageById(messageId)
                                if (existing != null && existing.isFromMe &&
                                    existing.status != MessageStatus.DELIVERED &&
                                    existing.status != MessageStatus.READ
                                ) {
                                    chatRepository.updateMessageStatus(messageId, MessageStatus.DELIVERED)
                                    Log.i(TAG, "✅ CF ACK from $senderId → DELIVERED msgId=$messageId")
                                } else {
                                    Log.d(TAG, "CF ACK ignored (msgId=$messageId not found / not mine / already delivered)")
                                }
                            }

                            is RelayEnvelope.Parsed.Message -> {
                                val messageId = parsed.messageId
                                // Дедупликация: сообщение уже в БД
                                val existingMsg = chatRepository.getMessageById(messageId)
                                if (existingMsg != null) {
                                    Log.i(TAG, "CF duplicate skipped (already in DB): messageId=$messageId")
                                } else {
                                    val contact = contactRepository.getContactById(senderId)
                                    val contactName = contact?.displayName ?: senderId.take(16)
                                    val chat = chatRepository.getOrCreateChat(senderId, contactName)
                                    chatRepository.saveIncomingMessage(
                                        chatId = chat.id,
                                        senderId = senderId,
                                        messageId = messageId,
                                        content = parsed.content,
                                        timestamp = parsed.timestamp,
                                        channel = MessageChannel.CF,
                                    )
                                    Log.i(TAG, "CF message saved to chat ${chat.id}: ${parsed.content.take(30)}")
                                }
                                // G1 fix: отправить ACK обратно отправителю через relay
                                // (отправитель узнаёт о доставке, даже если был офлайн в момент приёма).
                                try {
                                    val ack = RelayEnvelope.buildAck(messageId, RustBridge.nodeId() ?: "")
                                    cloudflareRelay?.sendMessage(senderId, ack)
                                    Log.i(TAG, "📤 CF ACK sent to $senderId for msgId=$messageId")
                                } catch (e: Exception) {
                                    Log.w(TAG, "CF ACK send failed: ${e.message}")
                                }
                            }

                            is RelayEnvelope.Parsed.Other -> {
                                // Legacy plain-text payload (не envelope) — сохраняем как раньше.
                                val messageId = java.util.UUID.randomUUID().toString()
                                val contact = contactRepository.getContactById(senderId)
                                val contactName = contact?.displayName ?: senderId.take(16)
                                val chat = chatRepository.getOrCreateChat(senderId, contactName)
                                chatRepository.saveIncomingMessage(
                                    chatId = chat.id,
                                    senderId = senderId,
                                    messageId = messageId,
                                    content = parsed.raw,
                                    timestamp = System.currentTimeMillis(),
                                    channel = MessageChannel.CF,
                                )
                                Log.i(TAG, "CF plain-text saved to chat ${chat.id}: ${parsed.raw.take(30)}")
                            }
                        }
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
        filePumpJob?.cancel()
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

        // F3: periodic sender pump — resumes PREPARED/TRANSFERRING transfers after restart,
        // advances windows on receiver ACKs and keeps multi-day offline retries alive. The
        // sender itself throttles re-pumps; file traffic yields via per-packet pacing.
        filePumpJob = serviceScope.launch {
            kotlinx.coroutines.delay(INITIAL_FILE_PUMP_DELAY_MS)
            while (isActive) {
                try {
                    fileTransferRouter.pumpOutgoing()
                } catch (ex: Exception) {
                    Log.w(TAG, "File pump error: ${ex.message}")
                }
                delay(FILE_PUMP_INTERVAL_MS)
            }
        }
    }

    private suspend fun handleEvent(event: CoreEventFfi) {
        Log.d(TAG, "Event: ${event.eventType}")
        Log.d(TAG, "📥 Event: ${event.eventType}")
        Log.d(TAG, "📥 Event: ${event.eventType}")
        when (event.eventType) {
            "message_received" -> {
                val originalTs = event.timestamp
                val ts = originalTs ?: System.currentTimeMillis()
                Log.i(TAG, "📨 MESSAGE_RECEIVED: sender=${event.senderId} msgId=${event.messageId} originalTs=$originalTs ts=$ts text=${event.text?.take(30)}")
                val senderId = event.senderId ?: return
                val chatId = event.chatId ?: return
                val messageId = event.messageId ?: return
                val text = event.text ?: return
                val timestamp = ts

                Log.i(TAG, "Message from $senderId in chat $chatId: $text")
                try {
                    // F3: file packets ride the same durable transport but must never be stored
                    // as chat text. Relay cleanup still happens through the per-message ACK below.
                    if (fileTransferRouter.routeIncoming(senderId, chatId, messageId, text)) {
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                            Log.i(TAG, "📤 File packet ACK sent for msgId=$messageId")
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠ File packet ACK failed: ${e.message}")
                        }
                        return
                    }
                    // P2P RELAY ФИЛЬТРАЦИЯ:
                    // Сохраняем ТОЛЬКО если контакт уже добавлен (это наш собеседник)
                    // Иначе — это широковещательное сообщение не для нас (ретранслируем, но не сохраняем)
                    // P2P ФИЛЬТРАЦИЯ: ищем существующий чат с этим контактом
                    // Если чата нет — не создаём (пользователь сам решает с кем общаться)
                    var chat = chatRepository.getChatByContactId(senderId)
                    if (chat == null) {
                        Log.i(TAG, "No chat with $senderId - auto-creating contact and chat")
                        try {
                            val autoName = "Contact " + senderId.takeLast(8)
                            val contactResult = contactRepository.addContact(autoName, senderId)
                            if (contactResult.isFailure && contactResult.exceptionOrNull()?.message != "Contact already exists") {
                                Log.e(TAG, "Auto-add contact failed: " + contactResult.exceptionOrNull()?.message)
                                return
                            }
                            chat = chatRepository.getOrCreateChat(senderId, autoName)
                            Log.i(TAG, "Auto-created chat " + chat.id + " for " + senderId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Auto-create failed", e)
                            return
                        }
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
                    
                    // Отправить ACK отправителю
                    try {
                        RustBridge.sendDeliveryAck(messageId, senderId)
                        Log.i(TAG, "📤 Delivery ACK sent for msgId=$messageId")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠ ACK send failed: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving incoming message", e)
                }
            }

            "peer_discovered" -> {
                val peerId = event.peerId ?: return
                if (!peerId.startsWith("pk_")) return  // Skip mDNS duplicates
                val peerName = event.displayName?.takeIf { it.isNotBlank() } ?: "Anonymous"
                val now = System.currentTimeMillis()
                val lastSeen = knownPeers[peerId] ?: 0L
                if (now - lastSeen < PEER_DEDUP_MS) return  // дедупликация
                knownPeers[peerId] = now
                Log.i(TAG, "👋 PEER DISCOVERED: $peerId ($peerName) — запуск full sync")

                // Обновляем только СУЩЕСТВУЮЩИЕ контакты (НЕ создаём новые автоматически)
                try {
                    val existing = contactRepository.getContactByFingerprint(peerId)
                    if (existing != null) {
                        // Контакт существует — обновляем online status
                        contactRepository.updateOnlineStatus(peerId, true)
                        if (existing.displayName != peerName && peerName.isNotBlank() && peerName != "Unknown" && peerName != "Anonymous" && (existing.displayName.startsWith("Contact ") || existing.displayName == "Anonymous")) {
                            contactRepository.updateDisplayName(existing.id, peerName)
                            chatRepository.updateContactName(peerId, peerName)
                        }
                        Log.i(TAG, "✅ Обновлён существующий контакт: $peerName")
                        
                        // FULL SYNC только для существующих контактов
                        serviceScope.launch {
                            try {
                                chatRepository.retryPendingMessagesForPeer(peerId)
                            } catch (e: Exception) {
                                Log.e(TAG, "Retry failed", e)
                            }
                            try {
                                fileTransferRouter.pumpOutgoing()
                            } catch (e: Exception) {
                                Log.w(TAG, "File pump after peer discovery failed: ${e.message}")
                            }
                        }
                    } else {
                        // Контакт НЕ существует — игнорируем
                        Log.i(TAG, "ℹ Peer не в контактах: $peerName ($peerId) — нужен invite link")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Update contact failed", e)
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
                    serviceScope.launch {
                        try {
                            fileTransferRouter.pumpOutgoing()
                        } catch (e: Exception) {
                            Log.w(TAG, "File pump after network connect failed: ${e.message}")
                        }
                    }
                }
            }

            "message_status_changed" -> {
                Log.i(TAG, "Message status changed: ${event.messageId} -> ${event.status}")
            }

            "keys_generated" -> Log.i(TAG, "Keys generated")
            "engine_started" -> Log.i(TAG, "Engine started OK")
            "error" -> Log.e(TAG, "Engine error: ${event.text}")
            "delivery_ack" -> {
                val messageId = event.messageId ?: return
                Log.i(TAG, "✅ DELIVERY_ACK received: msgId=$messageId from ${event.senderId}")
                serviceScope.launch {
                    try {
                        chatRepository.updateMessageStatus(messageId, com.vladimir.messenger.domain.model.MessageStatus.DELIVERED)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to mark DELIVERED", e)
                    }
                }
            }
            
            else -> Log.d(TAG, "🔍 Event: type=${event.eventType} sender=${event.senderId} peer=${event.peerId} msg=${event.messageId} text=${event.text?.take(30)}")
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
            .setContentTitle("APU")
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