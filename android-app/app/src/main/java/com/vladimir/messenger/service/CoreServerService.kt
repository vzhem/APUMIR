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
import kotlinx.coroutines.flow.drop
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
    @Inject lateinit var groupRouter: com.vladimir.messenger.data.group.GroupRouter
    @Inject lateinit var groupRepository: com.vladimir.messenger.data.group.GroupRepository
    @Inject lateinit var referralAttributionRouter: com.vladimir.messenger.data.referral.ReferralAttributionRouter
    @Inject lateinit var callManager: com.vladimir.messenger.data.call.CallManager
    @Inject lateinit var reactionRepository: com.vladimir.messenger.data.reaction.ReactionRepository
    private var gossipStarted = false
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

    /**
     * Heartbeat-присутствие: ядро шлёт peer_discovered ~каждые 30 с по MQTT
     * (и ~каждые 15 с по mDNS в LAN), а peer_lost практически НЕ генерирует —
     * самолётный режим у собеседника никакого события не даёт. Поэтому «в сети»
     * = «живая метка свежее TTL»: который peers мы зажгли, тех сами и гасятся.
     */
    private val onlineMarked = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    // Ядро объявляет о себе раз в минуту (было раз в 30 секунд), поэтому
    // «пропал» = три пропуска подряд = 200 секунд. Уборка тоже реже: она
    // будит процесс, а быстрее гасить «в сети» смысла нет.
    private val PRESENCE_TTL_MS = 200_000L    // ~3 пропущенных MQTT-пульса
    private val PRESENCE_SWEEP_MS = 60_000L
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
                // Холодный старт: peer_lost не доезжал, пока нас не было —
                // гасим устаревшие «в сети», живых тут же включит peer_discovered.
                try {
                    contactRepository.setAllOffline()
                    chatRepository.setAllContactsOffline()
                } catch (e: Exception) {
                    Log.w(TAG, "Presence reset failed: ${e.message}")
                }
                startPresenceReaper()
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
                    // Регистрация в справочнике нужна один раз: она сообщает
                    // серверу-указателю мой ключ и имя. Раньше запрос уходил
                    // при КАЖДОМ запуске сервиса, то есть по нескольку раз в
                    // день на мобильном интернете. Повторяем, только если имя
                    // изменилось или прошла неделя.
                    val lastName = prefs.getString(REGISTRY_NAME_KEY, null)
                    val lastAtMs = prefs.getLong(REGISTRY_AT_KEY, 0L)
                    val weekPassed = System.currentTimeMillis() - lastAtMs > REGISTRY_REFRESH_MS
                    if (lastName != myName || weekPassed) {
                        Log.i(TAG, "Registering in registry: nodeId=${myNodeId.take(16)}... name=$myName")
                        val ok = botApi.registerMyself(myNodeId, myPubKey, myName)
                        Log.i(TAG, "Registry registration: $ok")
                        if (ok) {
                            prefs.edit()
                                .putString(REGISTRY_NAME_KEY, myName)
                                .putLong(REGISTRY_AT_KEY, System.currentTimeMillis())
                                .apply()
                        }
                    } else {
                        Log.i(TAG, "Registry registration skipped: already registered as $myName")
                    }
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
        startForeground(NOTIFICATION_ID, buildNotification("Подключение..."))

        // Роевые публикации: моё @имя и каталог групп - при старте и при смене имени.
        if (!gossipStarted) {
            gossipStarted = true
            serviceScope.launch {
                kotlinx.coroutines.delay(3000)
                // Сверка «Контакты» = главная: у каждого контакта ровно один
                // чат, дубли схлопнуты. Разово при старте, чтобы разошедшиеся
                // за прошлые версии списки сошлись сами.
                runCatching { contactRepository.reconcileChats() }
                    .onFailure { Log.w(TAG, "reconcileChats: " + it.message) }
                // Без force: если рассылали недавно, повтор пропускается сам.
                runCatching { groupRepository.publishMyNickname() }
                runCatching { groupRepository.publishMyDirectory() }
                // Аватары: сначала поднять присланные из базы в витрину,
                // затем разослать свой контактам (раунд 40).
                runCatching { groupRepository.loadAvatars() }
                runCatching { groupRepository.publishMyAvatar() }
            }
            serviceScope.launch {
                com.vladimir.messenger.ui.theme.UsernameHolder.name
                    .drop(1)
                    // Имя сменили руками - рассылаем сразу, не дожидаясь паузы.
                    .collect { runCatching { groupRepository.publishMyNickname(force = true) } }
            }
            // Сменил аватар - сразу разослать новый по контактам.
            serviceScope.launch {
                com.vladimir.messenger.ui.theme.AvatarHolder.uri
                    .drop(1)
                    .collect { runCatching { groupRepository.publishMyAvatar(force = true) } }
            }
        }

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

                updateNotification("Сеть APU работает")
                startEventPolling()
            } else {
                updateNotification("Не удалось подключиться")
                stopServiceSafely()
            }
        }
        return START_STICKY
    }

    /**
     * Система убивает приложение (ForegroundServiceDidNotStopInTimeException),
     * если foreground-сервис не уложился в таймаут остановки. Поэтому сначала
     * снимаем foreground-статус и уведомление, и только затем stopSelf().
     */
    private fun stopServiceSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }
        stopSelf()
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
                stopServiceSafely()
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
        // Снимаем foreground-статус первым делом, чтобы система не считала
        // сервис зависшим, пока мы закрываем реле и Rust-ядро.
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground in onDestroy failed", e)
        }
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
                    updateNotification(notificationText(status, peers))
                } catch (ex: Exception) {
                    Log.e(TAG, "Event polling error", ex)
                }
                delay(POLL_INTERVAL_MS)
            }
        }

        // F3: periodic safety-net pump — resumes PREPARED/TRANSFERRING transfers after restart
        // and keeps multi-day offline retries alive. Live ACKs open the next bounded window
        // immediately; this cadence must not throttle an active transfer.
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

                    // Группы: APUGRP1-конверт разбирается здесь же, ДО авто-создания
                    // контакта. Иначе каждое групповое событие превратилось бы в личный
                    // чат с отправителем.
                    if (groupRouter.routeIncoming(senderId, chatId, messageId, text)) {
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                            Log.i(TAG, "Group packet ACK sent for msgId=$messageId")
                        } catch (e: Exception) {
                            Log.w(TAG, "Group packet ACK failed: " + e.message)
                        }
                        return
                    }

                    // Реакции на сообщения: APUREACT1-конверт разбирается здесь
                    // же, до авто-создания контакта, иначе значок превратился бы
                    // в мусорную строку в чате.
                    if (reactionRepository.routeIncoming(senderId, text)) {
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Reaction packet ACK failed: " + e.message)
                        }
                        return
                    }

                    // Реферальная атрибуция: служебный конверт «я пришёл по твоей
                    // ссылке». Разбирается здесь же и ДО авто-создания контакта —
                    // иначе вместо начисления ранга пригласившему появился бы чат
                    // с мусорным текстом.
                    if (referralAttributionRouter.routeIncoming(senderId, text)) {
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                            Log.i(TAG, "Referral packet ACK sent for msgId=$messageId")
                        } catch (e: Exception) {
                            Log.w(TAG, "Referral packet ACK failed: " + e.message)
                        }
                        return
                    }

                    // Звонки: APUCALL1-конверты разбираются здесь же, ДО
                    // авто-создания контакта, — иначе сигнализация звонка
                    // превратилась бы в мусорное сообщение личного чата.
                    if (callManager.routeIncoming(senderId, chatId, messageId, text)) {
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                            Log.i(TAG, "Call packet ACK sent for msgId=$messageId")
                        } catch (e: Exception) {
                            Log.w(TAG, "Call packet ACK failed: " + e.message)
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
                            // Тот же собеседник мог уже завести чат другим путём
                            // (обмен QR, приглашение): сводим в один, иначе в
                            // списке висят две одинаковые строки.
                            val merged = chatRepository.mergeDuplicateChats(senderId)
                            if (merged != null) chat = merged
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
                knownPeers[peerId] = now  // свежесть ДО дедупа: пульс = жизнь
                val lightTouch = now - lastSeen < PEER_DEDUP_MS

                // Обновляем только СУЩЕСТВУЮЩИЕ контакты (НЕ создаём новые автоматически)
                try {
                    val existing = contactRepository.getContactByFingerprint(peerId)
                    if (existing != null) {
                        // «В сети» — при КАЖДОМ живом пульсе (переходы дёшевы).
                        if (onlineMarked.add(peerId)) {
                            contactRepository.updateOnlineStatus(peerId, true)
                            // И в чаты: шапка лички и точка в списке читают таблицу chats
                            try { chatRepository.updateContactOnlineStatus(peerId, true) } catch (_: Exception) {}
                            Log.i(TAG, "🟢 ONLINE: $peerName")
                        }
                        if (lightTouch) return  // пульс учли, тяжёлую синхру не дёргаем
                        Log.i(TAG, "👋 PEER DISCOVERED: $peerId ($peerName) — запуск full sync")
                        // Настоящее имя из presence подменяет заглушку. Раньше
                        // условие требовало, чтобы старое имя начиналось с
                        // «Contact » ИЛИ было ровно «Anonymous», а имя из QR
                        // сохранялось как «Contact a1b2c3d4» лишь иногда - на
                        // части телефонов заглушка так и не заменялась.
                        if (existing.displayName != peerName &&
                            contactRepository.isRealName(peerName) &&
                            contactRepository.isPlaceholderName(existing.displayName)
                        ) {
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
                                fileTransferRouter.resumeWaitingForRecipient()
                            } catch (e: Exception) {
                                Log.w(TAG, "File pump after peer discovery failed: ${e.message}")
                            }
                        }
                    } else {
                        // Контакт НЕ существует — игнорируем
                        if (!lightTouch) Log.i(TAG, "ℹ Peer не в контактах: $peerName ($peerId) — нужен invite link")
                        if (lightTouch) return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Update contact failed", e)
                }

            }

            "peer_lost" -> {
                val peerId = event.peerId ?: return
                Log.i(TAG, "Peer lost: $peerId")
                onlineMarked.remove(peerId)
                knownPeers.remove(peerId)
                try { contactRepository.updateOnlineStatus(peerId, false) } catch (_: Exception) {}
                try { chatRepository.updateContactOnlineStatus(peerId, false) } catch (_: Exception) {}
            }

            "network_status_changed" -> {
                val status = event.status ?: "unknown"
                Log.i(TAG, "Network status: $status")
                updateNotification(notificationText(status, RustBridge.connectedPeers()))

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

    /**
     * Караул присутствия: метка онлайна без свежих пульсов дольше TTL → «не в сети».
     * Самолётный режим у Жени = пульсы пропали, события peer_lost ядро не даёт.
     */
    private fun startPresenceReaper() {
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(PRESENCE_SWEEP_MS)
                val now = System.currentTimeMillis()
                val staleIds = synchronized(onlineMarked) {
                    onlineMarked.filter { pid ->
                        now - (knownPeers[pid] ?: 0L) > PRESENCE_TTL_MS
                    }
                }
                for (pid in staleIds) {
                    onlineMarked.remove(pid)
                    knownPeers.remove(pid)
                    Log.i(TAG, "⚫ OFFLINE (TTL): $pid")
                    try { contactRepository.updateOnlineStatus(pid, false) } catch (_: Exception) {}
                    try { chatRepository.updateContactOnlineStatus(pid, false) } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Русский текст постоянного уведомления.
     *
     * Ядро отдаёт статус словами offline/connecting/connected/relayed, а число
     * собеседников - цифрой. В шторке владелец хочет видеть родную речь, а не
     * «connected - 2 peers».
     */
    private fun notificationText(status: String, peers: Long): String {
        val head = when (status.lowercase()) {
            "connected" -> "На связи"
            "relayed" -> "На связи через ретранслятор"
            "connecting" -> "Подключение..."
            "offline" -> "Нет соединения"
            else -> "Сеть APU"
        }
        return if (peers > 0) "$head - рядом ${peersWord(peers)}" else head
    }

    /** «1 собеседник», «2 собеседника», «5 собеседников». */
    private fun peersWord(peers: Long): String {
        val n = (peers % 100).toInt()
        val word = when {
            n in 11..14 -> "собеседников"
            n % 10 == 1 -> "собеседник"
            n % 10 in 2..4 -> "собеседника"
            else -> "собеседников"
        }
        return "$peers $word"
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
        /** Под каким именем нас уже записал справочник. */
        private const val REGISTRY_NAME_KEY = "registry_registered_name"
        /** Когда записал. */
        private const val REGISTRY_AT_KEY = "registry_registered_at"
        /** Повторяем запись не чаще раза в неделю. */
        private const val REGISTRY_REFRESH_MS = 7L * 24 * 60 * 60 * 1000
    }
}