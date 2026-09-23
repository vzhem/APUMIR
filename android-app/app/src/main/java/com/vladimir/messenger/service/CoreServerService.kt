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
import com.vladimir.messenger.data.security.MessageSealer
import com.vladimir.messenger.data.security.SealedWire
import com.vladimir.messenger.data.referral.ReferralRankStore
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import com.vladimir.messenger.data.security.RelayAtRestMasterKey
import com.vladimir.messenger.service.NotificationHelper
import com.vladimir.messenger.service.BotApi
import com.vladimir.messenger.data.repository.ContactRepository
import com.vladimir.messenger.util.NodeIds
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.UUID
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
    @Inject lateinit var identityBackup: com.vladimir.messenger.data.security.IdentityBackup
    @Inject lateinit var addressBookBackup: com.vladimir.messenger.data.backup.AddressBookBackup
    @Inject lateinit var addressBookSwarm: com.vladimir.messenger.data.backup.AddressBookSwarmBackup
    @Inject lateinit var readReceipts: com.vladimir.messenger.data.receipt.ReadReceiptRepository
    @Inject lateinit var messageDeletion: com.vladimir.messenger.data.repository.MessageDeletionRepository
    @Inject lateinit var hearts: com.vladimir.messenger.data.heart.HeartRepository
    @Inject lateinit var postViews: com.vladimir.messenger.data.channel.PostViewRepository
    @Inject lateinit var groupRouter: com.vladimir.messenger.data.group.GroupRouter
    @Inject lateinit var groupRepository: com.vladimir.messenger.data.group.GroupRepository
    @Inject lateinit var groupFiles: com.vladimir.messenger.data.group.GroupFileSwarm
    @Inject lateinit var swarmPeerDirectory: com.vladimir.messenger.data.swarm.SwarmPeerDirectory
    @Inject lateinit var apkSeeder: com.vladimir.messenger.data.update.ApkSeeder
    @Inject lateinit var referralAttributionRouter: com.vladimir.messenger.data.referral.ReferralAttributionRouter
    @Inject lateinit var callManager: com.vladimir.messenger.data.call.CallManager
    @Inject lateinit var reactionRepository: com.vladimir.messenger.data.reaction.ReactionRepository
    @Inject lateinit var gifPreparation: com.vladimir.messenger.data.file.OutgoingFilePreparationService
    @Inject lateinit var stickerLibrary: com.vladimir.messenger.data.sticker.StickerLibrary
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

    // Раунд 121: свой каталог гифок роя (APUGIF1).
    private val gifCatalogSentAt = mutableMapOf<String, Long>()
    private val gifAskHandledAt = mutableMapOf<String, Long>()
    private val gifWantServedAt = mutableMapOf<String, Long>()

    // Раунд 139: свой каталог стикеров роя (APUSTK1) - как у гифок.
    private val stickerCatalogSentAt = mutableMapOf<String, Long>()
    private val stickerAskHandledAt = mutableMapOf<String, Long>()
    private val stickerWantServedAt = mutableMapOf<String, Long>()

    // Раунд 141: уведомления без хрупкого окна «2 секунды» - базой служит
    // момент старта сервиса, повторы гасятся множеством оглашённых id.
    private val serviceStartedAtMs = System.currentTimeMillis()
    private val notifiedMessageIds = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Служебные конверты каталога гифок: ask/have/want. Разбираются до
     * сохранения в чат (как реакции), поэтому «мусорных» строк в переписке
     * не появляется.
     */
    private fun handleGifEnvelope(senderId: String, chatId: String, messageId: String, text: String) {
        val packet = com.vladimir.messenger.data.gif.GifLibrary.parseGifPacket(text) ?: return
        val now = System.currentTimeMillis()
        when (packet.kind) {
            "ask" -> {
                if (now - (gifAskHandledAt[senderId] ?: 0L) < 10 * 60_000L) return
                gifAskHandledAt[senderId] = now
                serviceScope.launch {
                    runCatching { announceMyGifCatalogTo(senderId) }
                        .onFailure { Log.w(TAG, "gif catalog announce failed: ${it.message}") }
                }
            }
            "have" -> {
                serviceScope.launch {
                    runCatching {
                        com.vladimir.messenger.data.gif.GifLibrary.receivePeerBatch(
                            applicationContext, senderId, packet.index, packet.total, packet.items,
                        )
                    }.onFailure { Log.w(TAG, "gif catalog batch failed: ${it.message}") }
                }
            }
            "want" -> {
                val key = "$senderId|${packet.sha256}"
                if (now - (gifWantServedAt[key] ?: 0L) < 10 * 60_000L) return
                gifWantServedAt[key] = now
                serviceScope.launch {
                    runCatching { serveGifFromLibrary(senderId, chatId, packet.sha256) }
                        .onFailure { Log.w(TAG, "gif serve failed: ${it.message}") }
                }
            }
            "ref" -> {
                // Раунд 130: ссылка от телефона v11.74.25 - та же карточка.
                serviceScope.launch {
                    runCatching {
                        chatRepository.insertReceivedGifRefMessage(
                            chatId = chatId,
                            senderId = senderId,
                            messageId = messageId,
                            sha256 = packet.sha256,
                            timestamp = System.currentTimeMillis(),
                        )
                    }.onFailure { Log.w(TAG, "gif ref (legacy) insert failed: ${it.message}") }
                }
            }
            "thumb" -> {
                // Раунд 129: просят миниатюру - отдаём крошечный jpeg (тихо).
                val key = "T$senderId|${packet.sha256}"
                if (now - (gifWantServedAt[key] ?: 0L) < 5 * 60_000L) return
                gifWantServedAt[key] = now
                serviceScope.launch {
                    runCatching {
                        val b64 = com.vladimir.messenger.data.gif.GifLibrary
                            .tinyThumbPayload(applicationContext, packet.sha256)
                            ?: return@runCatching
                        val chat = chatRepository.getChatByContactId(senderId)
                            ?: return@runCatching
                        RustBridge.sendMessage(
                            UUID.randomUUID().toString(), chat.id, senderId,
                            com.vladimir.messenger.data.gif.GifLibrary.WIRE_PREFIX +
                                "|thmb|" + packet.sha256 + "|" + b64,
                        )
                    }.onFailure { Log.w(TAG, "gif thumb serve failed: ${it.message}") }
                }
            }
            "thmb" -> {
                // Раунд 129: приехала миниатюра - в кэш, сетка обновится сама.
                serviceScope.launch {
                    runCatching {
                        com.vladimir.messenger.data.gif.GifLibrary.receiveThumb(
                            applicationContext, packet.sha256, packet.payload,
                        )
                    }.onFailure { Log.w(TAG, "gif thumb receive failed: ${it.message}") }
                }
            }
        }
    }

    /**
     * Раунд 131: убедиться, что гифка из ссылки есть в моей библиотеке.
     * Нет - тихо попросить у хранителей из каталога сети.
     */
    private suspend fun ensureGifBytesForRef(sha256: String) {
        val have = com.vladimir.messenger.data.gif.GifLibrary
            .gifFile(applicationContext, sha256)?.isFile == true
        if (have) return
        val holders = com.vladimir.messenger.data.gif.GifLibrary
            .swarmCatalog(applicationContext)
            .firstOrNull { it.entry.sha256 == sha256 }?.holders.orEmpty()
        if (holders.isEmpty()) return
        com.vladimir.messenger.data.gif.GifLibrary.rememberWant(sha256)
        com.vladimir.messenger.data.gif.GifLibrary.requestGif(
            applicationContext, chatRepository, sha256, holders,
        )
    }

    private suspend fun announceMyGifCatalogTo(peerId: String) {
        val now = System.currentTimeMillis()
        if (now - (gifCatalogSentAt[peerId] ?: 0L) < 10 * 60_000L) return
        gifCatalogSentAt[peerId] = now
        val chat = chatRepository.getChatByContactId(peerId) ?: return
        val batches = com.vladimir.messenger.data.gif.GifLibrary.buildHaveBatches(applicationContext)
        for (batch in batches) {
            RustBridge.sendMessage(UUID.randomUUID().toString(), chat.id, peerId, batch)
        }
        Log.i(TAG, "GIF catalog sent to ${peerId.takeLast(8)}: ${batches.size} batch(es)")
    }

    /**
     * Собеседник попросил гифку из моего каталога (раунд 128): передаю БАЙТЫ
     * защищённой передачей файлов ТИХО - без сообщения в наш чат. Телефон
     * просителя сам положит гифку в тот чат, откуда пришла ссылка.
     */
    private suspend fun serveGifFromLibrary(peerId: String, chatId: String, sha256: String) {
        val app = applicationContext
        val entry = com.vladimir.messenger.data.gif.GifLibrary.bySha(app, sha256) ?: return
        val file = com.vladimir.messenger.data.gif.GifLibrary.gifFile(app, sha256) ?: return
        val messageId = UUID.randomUUID().toString()
        try {
            gifPreparation.prepareFromFile(
                source = file,
                displayName = entry.displayName.ifBlank { "gif_${sha256.take(8)}.gif" },
                mediaType = "image/gif",
                messageId = messageId,
                chatId = chatId,
                recipientNodeId = peerId,
            )
            fileTransferRouter.pumpOutgoing()
            Log.i(TAG, "GIF ${sha256.take(12)} served to ${peerId.takeLast(8)} (silent)")
        } catch (e: Exception) {
            if (e.message.orEmpty().contains("binding is not pinned")) {
                fileTransferRouter.requestExchangeBinding(peerId)
            }
            throw e
        }
    }

    /**
     * Раунд 140: служебные конверты ЗАПАСНОГО пути (Cloudflare relay).
     * Цепочка - та же фильтрация, что в handleEvent / «message_received»:
     * стикеры, гифки, группы, реакции, удаления и прочее разбираются ДО
     * сохранения. Раньше запасной путь сохранял всё как есть, и конверт
     * «APUSTK1|ask» падал в переписку мусорным текстом (владелец, скрин
     * 2026-09-23, оба телефона на последней версии). Если добавляете новый
     * конверт - добавьте его и здесь, и в основной цепочке.
     * Возвращает true, если текст служебный и в чат ему дороги нет.
     */
    private suspend fun routeIncomingEnvelope(
        senderId: String,
        chatId: String,
        messageId: String,
        text: String,
    ): Boolean {
        // Раунд 141: каждый страж в защитной обёртке - если какой-то
        // роутер упал на обычном письме, письмо ДОЛЖНО доехать до чата
        // и уведомления, а не исчезнуть молча.
        if (runCatching { fileTransferRouter.routeIncoming(senderId, chatId, messageId, text) }
            .getOrDefault(false)
        ) {
            return true
        }
        if (com.vladimir.messenger.data.gif.GifLibrary.isGifRef(text)) {
            val refSha = com.vladimir.messenger.data.gif.GifLibrary.gifRefSha(text)
            if (refSha != null && chatId.isNotBlank()) {
                runCatching {
                    chatRepository.insertReceivedGifRefMessage(
                        chatId = chatId,
                        senderId = senderId,
                        messageId = messageId,
                        sha256 = refSha,
                        timestamp = System.currentTimeMillis(),
                    )
                }.onFailure { Log.w(TAG, "CF gif ref insert failed: " + it.message) }
                runCatching { ensureGifBytesForRef(refSha) }
                    .onFailure { Log.w(TAG, "CF gif ref fetch failed: " + it.message) }
            }
            return true
        }
        if (com.vladimir.messenger.data.gif.GifLibrary.isGifPacket(text)) {
            runCatching { handleGifEnvelope(senderId, chatId, messageId, text) }
            return true
        }
        if (com.vladimir.messenger.data.sticker.StickerLibrary.isStickerPacket(text)) {
            runCatching { handleStickerEnvelope(senderId, chatId, messageId, text) }
            return true
        }
        if (runCatching { groupRouter.routeIncoming(senderId, chatId, messageId, text) }
            .getOrDefault(false)
        ) {
            return true
        }
        if (runCatching { reactionRepository.routeIncoming(senderId, text) }.getOrDefault(false)) return true
        if (runCatching { messageDeletion.routeIncoming(senderId, text) }.getOrDefault(false)) return true
        if (runCatching { postViews.routeIncoming(senderId, text) }.getOrDefault(false)) return true
        if (runCatching { hearts.routeIncoming(senderId, text) }.getOrDefault(false)) return true
        if (runCatching { addressBookSwarm.routeIncoming(senderId, text) }.getOrDefault(false)) return true
        if (runCatching { readReceipts.routeIncoming(senderId, text) }.getOrDefault(false)) return true
        if (runCatching { referralAttributionRouter.routeIncoming(senderId, text) }.getOrDefault(false)) return true
        if (runCatching { callManager.routeIncoming(senderId, chatId, messageId, text) }
            .getOrDefault(false)
        ) {
            return true
        }
        return false
    }

    /**
     * Раунд 139: служебные конверты каталога СТИКЕРОВ (APUSTK1). Разбираются
     * до сохранения в чат (как гифковые), поэтому в переписке мусора нет.
     */
    private fun handleStickerEnvelope(senderId: String, chatId: String, messageId: String, text: String) {
        val packet = com.vladimir.messenger.data.sticker.StickerLibrary.parseStickerPacket(text) ?: return
        val now = System.currentTimeMillis()
        when (packet.kind) {
            "ask" -> {
                if (now - (stickerAskHandledAt[senderId] ?: 0L) < 10 * 60_000L) return
                stickerAskHandledAt[senderId] = now
                serviceScope.launch {
                    runCatching { announceMyStickerCatalogTo(senderId) }
                        .onFailure { Log.w(TAG, "sticker catalog announce failed: ${it.message}") }
                }
            }
            "have" -> {
                serviceScope.launch {
                    runCatching {
                        com.vladimir.messenger.data.sticker.StickerLibrary.receivePeerBatch(
                            applicationContext, senderId, packet.index, packet.total, packet.items,
                        )
                    }.onFailure { Log.w(TAG, "sticker catalog batch failed: ${it.message}") }
                }
            }
            "want" -> {
                val key = "$senderId|${packet.sha256}"
                if (now - (stickerWantServedAt[key] ?: 0L) < 10 * 60_000L) return
                stickerWantServedAt[key] = now
                serviceScope.launch {
                    runCatching { serveStickerFromLibrary(senderId, chatId, packet.sha256) }
                        .onFailure { Log.w(TAG, "sticker serve failed: ${it.message}") }
                }
            }
            "thumb" -> {
                // Просят миниатюру - отдаём крошечный jpeg (тихо).
                val key = "T$senderId|${packet.sha256}"
                if (now - (stickerWantServedAt[key] ?: 0L) < 5 * 60_000L) return
                stickerWantServedAt[key] = now
                serviceScope.launch {
                    runCatching {
                        val b64 = com.vladimir.messenger.data.sticker.StickerLibrary
                            .tinyThumbPayload(applicationContext, packet.sha256)
                            ?: return@runCatching
                        val chat = chatRepository.getChatByContactId(senderId)
                            ?: return@runCatching
                        RustBridge.sendMessage(
                            UUID.randomUUID().toString(), chat.id, senderId,
                            com.vladimir.messenger.data.sticker.StickerLibrary.WIRE_PREFIX +
                                "|thmb|" + packet.sha256 + "|" + b64,
                        )
                    }.onFailure { Log.w(TAG, "sticker thumb serve failed: ${it.message}") }
                }
            }
            "thmb" -> {
                // Приехала миниатюра - в кэш, сетка панели обновится сама.
                serviceScope.launch {
                    runCatching {
                        com.vladimir.messenger.data.sticker.StickerLibrary.receiveThumb(
                            applicationContext, packet.sha256, packet.payload,
                        )
                    }.onFailure { Log.w(TAG, "sticker thumb receive failed: ${it.message}") }
                }
            }
        }
    }

    /** Рассказать свой каталог стикеров одному собеседнику (раунд 139). */
    private suspend fun announceMyStickerCatalogTo(peerId: String) {
        val now = System.currentTimeMillis()
        if (now - (stickerCatalogSentAt[peerId] ?: 0L) < 10 * 60_000L) return
        stickerCatalogSentAt[peerId] = now
        val chat = chatRepository.getChatByContactId(peerId) ?: return
        val batches = stickerLibrary.buildHaveBatches()
        for (batch in batches) {
            RustBridge.sendMessage(UUID.randomUUID().toString(), chat.id, peerId, batch)
        }
        Log.i(TAG, "Sticker catalog sent to ${peerId.takeLast(8)}: ${batches.size} batch(es)")
    }

    /**
     * Собеседник попросил стикер из моего каталога: передаю БАЙТЫ защищённой
     * передачей файлов ТИХО - без сообщения в наш чат. Телефон просителя сам
     * положит стикер в библиотеку и отправит его в тот чат, откуда просьба.
     */
    private suspend fun serveStickerFromLibrary(peerId: String, chatId: String, sha256: String) {
        val app = applicationContext
        val entry = stickerLibrary.entryOf(sha256) ?: return
        val file = entry.file.takeIf { it.isFile } ?: return
        val messageId = UUID.randomUUID().toString()
        try {
            gifPreparation.prepareFromFile(
                source = file,
                displayName = entry.name.ifBlank { "sticker_${sha256.take(8)}.png" },
                mediaType = com.vladimir.messenger.data.sticker.StickerLibrary.mimeFor(file),
                messageId = messageId,
                chatId = chatId,
                recipientNodeId = peerId,
            )
            fileTransferRouter.pumpOutgoing()
            Log.i(TAG, "Sticker ${sha256.take(12)} served to ${peerId.takeLast(8)} (silent)")
        } catch (e: Exception) {
            if (e.message.orEmpty().contains("binding is not pinned")) {
                fileTransferRouter.requestExchangeBinding(peerId)
            }
            throw e
        }
    }

    private suspend fun gifLibraryBootstrap() {
        // Рассказать свой каталог и попросить чужие (тротлимб в GifLibrary).
        com.vladimir.messenger.data.gif.GifLibrary.syncWithSwarm(
            applicationContext, chatRepository, force = false,
        )
    }

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
        Log.i(TAG, "CoreServerService started (action=" + (intent?.action ?: "null") + ")")
        startForeground(NOTIFICATION_ID, buildNotification("Подключение..."))

        // Раунд 141: аварийный каркас - каждый старт перевзводит системный
        // будильник «+5 минут». Если процесс убьют или усыпят, будильник
        // поднимет сервис сам и письмо доложится (см. EmergencyKeepAlive).
        EmergencyKeepAliveReceiver.scheduleNext(applicationContext)

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
                // Сколько места под пересылку отдаю - контактам, для их рейтинга.
                runCatching { groupRepository.publishMyCapabilities() }
            }
            // Подвинул ползунок «Место под пересылку» - контакты узнают сразу.
            // init до подписки: иначе чтение сохранённого значения само
            // выглядело бы как смена ползунка.
            com.vladimir.messenger.data.swarm.StorageSettings.init(applicationContext)
            serviceScope.launch {
                com.vladimir.messenger.data.swarm.StorageSettings.quotaBytes
                    .drop(1)
                    .collect { runCatching { groupRepository.publishMyCapabilities(force = true) } }
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
            // ШИФРОВАНИЕ: контекст ставим до старта движка, иначе первые
            // исходящие успеют уйти открытыми.
            RustBridge.attachContext(applicationContext)
            serviceScope.launch { fileTransferRouter.warmSealingKeys() }
            // Досылка сундука личности: человек мог задать пароль без сети.
            // Пока конверт не на сервере, восстановиться можно только с этого
            // телефона, поэтому пробуем при каждом старте.
            serviceScope.launch {
                runCatching { identityBackup.flushPending(applicationContext) }
                    .onFailure { Log.w(TAG, "Досылка сундука не удалась: ${it.message}") }
            }

            val atRestKeyOk = RelayAtRestMasterKey.installIntoCore(applicationContext)
            Log.i(TAG, "Relay at-rest key installed: $atRestKeyOk")

            // Азбука адресов: на свежей установке файла ещё нет — тянем
            // облачную копию ДО создания движка (ядро читает файл один раз
            // при старте). На обычном запуске это мгновенный no-op.
            runCatching { addressBookBackup.restoreBeforeStart() }
                .onFailure { Log.w(TAG, "AddressBook restore failed: ${it.message}") }

            // Собственный SQLite-файл relay custody (app-private, WAL).
            val relayDbPath = File(filesDir, "apu_relay.sqlite").absolutePath
            val ok = RustBridge.initialize(displayName, existingPubKey, existingPrivKey, relayDbPath)
            if (ok) {
                val nodeId = RustBridge.nodeId()
                Log.i(TAG, "Engine OK. NodeId=$nodeId")

                // Азбука адресов (docs/ADDRESS_BOOK.md): засевать «свои»,
                // чтобы в первую же минуту после старта личное presence
                // стукнуло к сохранённым адресам, а онлайн-соседи ответили
                // presence со СВЕЖИМ адресом — файл актуализируется.
                serviceScope.launch {
                    runCatching {
                        val ids = swarmPeerDirectory.audienceIds()
                        RustBridge.setPresenceAudience(ids)
                        Log.i(TAG, "Presence audience seeded: ${ids.size} ids")
                    }.onFailure {
                        Log.w(TAG, "Presence audience seed failed: ${it.message}")
                    }
                }

                // Раунд 121: свой каталог гифок - разослать и спросить чужие
                // (тротлимб внутри; при старте уходит после подключения).
                serviceScope.launch {
                    runCatching { gifLibraryBootstrap() }
                        .onFailure { Log.w(TAG, "gif catalog bootstrap failed: ${it.message}") }
                }

                // Раунд 139: свой каталог стикеров - так же разослать и спросить.
                serviceScope.launch {
                    runCatching {
                        stickerLibrary.syncWithSwarm(chatRepository, force = false)
                    }.onFailure { Log.w(TAG, "sticker catalog bootstrap failed: ${it.message}") }
                }

                // Прокси-автопилот: первичный цикл при старте — проверить пул, убрать мёртвых,
                // выбрать и подключить лучшего (без принудительного сбора).
                serviceScope.launch {
                    try {
                        proxyAutopilot.cycle()
                    } catch (e: Exception) {
                        Log.w(TAG, "Proxy autopilot startup cycle: ${e.message}")
                    }
                }

                // Облачная копия азбуки: первая через 3 минуты (адреса уже
                // насобирались), дальше каждый час; шлём только если файл
                // менялся (см. AddressBookBackup.backupIfDue).
                serviceScope.launch {
                    kotlinx.coroutines.delay(3 * 60 * 1000L)
                    while (true) {
                        runCatching { addressBookBackup.backupIfDue() }
                            .onFailure { Log.w(TAG, "AddressBook backup failed: ${it.message}") }
                        runCatching { addressBookBackup.swarmHourlyTick() }
                            .onFailure { Log.w(TAG, "AddressBook swarm tick: ${it.message}") }
                        kotlinx.coroutines.delay(60 * 60 * 1000L)
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
                        // Тот же страж, что и у ядра: отправитель обязан быть
                        // узлом, иначе ниже вырос бы чат с собеседником «unknown».
                        if (!NodeIds.isNodeId(senderId)) {
                            Log.w(TAG, "CF payload with non-node sender '" + senderId.take(24) + "' dropped")
                            return@launch
                        }
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
                                    // ШИФРОВАНИЕ: это второй, независимый путь приёма. Без
                                    // расшифровки здесь в чат попал бы конверт как текст.
                                    val cfContent = if (SealedWire.isSealed(parsed.content)) {
                                        MessageSealer.open(applicationContext, parsed.content)
                                    } else {
                                        parsed.content
                                    }
                                    if (cfContent == null) {
                                        Log.w(TAG, "CF sealed envelope not opened msgId=$messageId; skipped")
                                    } else {
                                        // Раунд 140: служебные конверты разбираются ДО
                                        // сохранения - тем же стражем, что и основной
                                        // путь. У неизвестного отправителя чат ради
                                        // конверта не создаётся.
                                        val knownChat = chatRepository.getChatByContactId(senderId)
                                        if (routeIncomingEnvelope(senderId, knownChat?.id ?: "", messageId, cfContent)) {
                                            Log.i(TAG, "CF service envelope handled msgId=$messageId")
                                            try {
                                                RustBridge.sendDeliveryAck(messageId, senderId)
                                            } catch (e: Exception) {
                                                Log.w(TAG, "CF envelope ACK failed: " + e.message)
                                            }
                                        } else {
                                            val contact = contactRepository.getContactById(senderId)
                                            val contactName = contact?.displayName ?: senderId.take(16)
                                            val chat = chatRepository.getOrCreateChat(senderId, contactName)
                                            chatRepository.saveIncomingMessage(
                                                chatId = chat.id,
                                                senderId = senderId,
                                                messageId = messageId,
                                                content = cfContent,
                                                timestamp = parsed.timestamp,
                                                channel = MessageChannel.CF,
                                            )
                                            Log.i(TAG, "CF message handled for chat ${chat.id} msgId=$messageId")
                                        }
                                    }
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
                                // Раунд 140: но только не служебный конверт - он
                                // разбирается стражем, в чат ему дороги нет.
                                val legacyMessageId = java.util.UUID.randomUUID().toString()
                                val knownChat = chatRepository.getChatByContactId(senderId)
                                if (routeIncomingEnvelope(senderId, knownChat?.id ?: "", legacyMessageId, parsed.raw)) {
                                    Log.i(TAG, "CF legacy service envelope handled")
                                } else {
                                    val contact = contactRepository.getContactById(senderId)
                                    val contactName = contact?.displayName ?: senderId.take(16)
                                    val chat = chatRepository.getOrCreateChat(senderId, contactName)
                                    chatRepository.saveIncomingMessage(
                                        chatId = chat.id,
                                        senderId = senderId,
                                        messageId = legacyMessageId,
                                        content = parsed.raw,
                                        timestamp = System.currentTimeMillis(),
                                        channel = MessageChannel.CF,
                                    )
                                    Log.i(TAG, "CF plain-text saved to chat ${chat.id}: ${parsed.raw.take(30)}")
                                }
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
                    // Раунд 141: прежнее окно «письмо младше 2 секунд от
                    // отметки времени» молчало, когда часы телефонов
                    // расходились или письмо ехало через запасной канал
                    // дольше двух секунд - уведомления «переставали
                    // приходить» (владелец, 2026-09-23). Теперь: оглашаем
                    // всё, что появилось с момента старта сервиса (минус
                    // две минуты - доложить написанное, пока процесс был
                    // мёртв), и ещё не оглашённое: дубль core+CF гасится
                    // по id, старые записи при восстановлении бэкапа -
                    // базой старта.
                    if (notifiedMessageIds.size > 4096) notifiedMessageIds.clear()
                    val recentIncoming = messages.filter {
                        !it.isFromMe &&
                            it.timestamp >= serviceStartedAtMs - 120_000L &&
                            notifiedMessageIds.add(it.id) &&
                            !com.vladimir.messenger.util.InlineImage.isPart(it.content)
                    }
                    for (msg in recentIncoming) {
                        try {
                            // Тема/пост, где написано сообщение: тап открывает
                            // именно его. Вызов позиционный: именованные
                            // аргументы + значение по умолчанию ловили
                            // фантомную «Argument type mismatch» в K2.
                            val topicId = msg.topicId?.takeIf { it.isNotBlank() }
                            // Раунд 131: ссылка на гифку - в уведомлении
                            // аккуратное «🖼 Гифка», а не служебная строка.
                            val text = if (
                                com.vladimir.messenger.data.gif.GifLibrary.isGifRef(msg.content)
                            ) {
                                "🖼 Гифка"
                            } else {
                                com.vladimir.messenger.util.InlineImage
                                    .stripImage(msg.content).ifBlank { "Фото" }.take(200)
                            }
                            notificationHelper.showMessageNotification(
                                msg.chatId, msg.senderId, text, true, topicId
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
                // K3: события файловых кусков приходят пучками (пока файл
                // льётся стримами). Пока очередь не пуста - работаем
                // коротким шагом, иначе куски ждали бы полные POLL_INTERVAL_MS
                // и ACK-окно отправителя почти не двигалось. В простое шаг
                // прежний (POLL_INTERVAL_MS), пустые опросы дёшевы.
                val busy = try {
                    val events = RustBridge.drainEvents()
                    events.forEach { event -> handleEvent(event) }
                    RustBridge.pendingEvents() > 0L || events.size >= BUSY_BATCH
                } catch (ex: Exception) {
                    Log.e(TAG, "Event polling error", ex)
                    false
                }
                try {
                    val status = RustBridge.networkStatus()
                    val peers = RustBridge.connectedPeers()
                    updateNotification(notificationText(status, peers))
                } catch (ex: Exception) {
                    Log.e(TAG, "Status polling error", ex)
                }
                delay(if (busy) BUSY_POLL_INTERVAL_MS else POLL_INTERVAL_MS)
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
                // Файлы группы роем (этап 9): повторить свои просьбы, отдать
                // из очереди, освободить куски отданного.
                try {
                    groupFiles.pump()
                } catch (ex: Exception) {
                    Log.w(TAG, "Group file pump error: ${ex.message}")
                }
                // Рой APK (docs/UPDATE_SEEDING.md): повторить просьбы о
                // новой версии, обновить карточку «Обновления», объявить
                // о своей раздаче.
                try {
                    apkSeeder.pump()
                } catch (ex: Exception) {
                    Log.w(TAG, "Apk seeder pump error: ${ex.message}")
                }
                // Раунд 137: недоставленные «удалить у всех» - личные (до ack)
                // и групповые (эпидемией через помпу репозитория).
                try {
                    messageDeletion.pump()
                } catch (ex: Exception) {
                    Log.w(TAG, "Deletion pump error: ${ex.message}")
                }
                try {
                    groupRepository.pumpDeletions()
                } catch (ex: Exception) {
                    Log.w(TAG, "Group deletion pump error: ${ex.message}")
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
            // K3: бинарный кусок файла по прямому QUIC (APUF-кадр из ядра).
            // Это не сообщение переписки: текста, чата и отправителя в
            // кадре нет — адресация по transferId, подлинность подтверждает
            // AES-GCM тег целого куска в приёмнике. Служебный, быстрый путь.
            "file_chunk_received" -> {
                val transferIdHex = event.transferId
                val chunkIndex = event.chunkIndex
                val chunkOffset = event.chunkOffset
                val ciphertextChunkLen = event.ciphertextChunkLen
                val ciphertextRange = event.payload
                if (transferIdHex == null || chunkIndex == null || chunkOffset == null ||
                    ciphertextChunkLen == null || ciphertextRange == null
                ) {
                    Log.w(TAG, "file_chunk_received missing fields; dropped")
                    return
                }
                runCatching {
                    fileTransferRouter.onBinaryChunk(
                        transferIdHex,
                        chunkIndex,
                        chunkOffset.toInt(),
                        ciphertextChunkLen.toInt(),
                        ciphertextRange,
                    )
                }.onFailure { ex ->
                    Log.w(TAG, "file_chunk_received handling failed: ${ex.message}")
                }
                return
            }

            "message_received" -> {
                val originalTs = event.timestamp
                val ts = originalTs ?: System.currentTimeMillis()
                Log.i(TAG, "📨 MESSAGE_RECEIVED: sender=${event.senderId} msgId=${event.messageId} originalTs=$originalTs ts=$ts text=${event.text?.take(30)}")
                val senderId = event.senderId ?: return
                val chatId = event.chatId ?: return
                val messageId = event.messageId ?: return
                val rawText = event.text ?: return
                val timestamp = ts

                // Отправитель обязан быть узлом (pk_…). Ядро режет входящую
                // строку на четыре поля, первое поле не проверяя: строка без
                // конверта (например, голосовой пакет `APUCALL1|ab|…`, ушедший
                // как есть) даёт отправителя «APUCALL1», а ниже из него вырос
                // бы контакт-призрак «Contact APUCALL1» с обрывком пакета
                // вместо переписки (владелец, скриншот 2026-09-13).
                if (!NodeIds.isNodeId(senderId)) {
                    Log.w(
                        TAG,
                        "Dropped message with non-node sender '" + senderId.take(24) +
                            "' msgId=" + messageId.take(24) + " text=" + rawText.take(24),
                    )
                    return
                }

                // ШИФРОВАНИЕ: конверт вскрывается ДО любых разборщиков, иначе
                // групповые, файловые и служебные пакеты не будут узнаны.
                //
                // Не вскрывшийся конверт чаще всего адресован не нам (мы лишь
                // ретранслятор) - это нормально и не ошибка. Но ровно так же
                // выглядит и настоящая поломка, поэтому пишем WARN с приметами:
                // раунд 80 стоил дня поисков именно потому, что сообщение
                // исчезало бесследно.
                val sealed = SealedWire.isSealed(rawText)
                val text = if (sealed) {
                    val opened = MessageSealer.open(applicationContext, rawText)
                    if (opened == null) {
                        val known = MessageSealer.canSeal(applicationContext, senderId)
                        Log.w(
                            TAG,
                            "Sealed envelope NOT opened msgId=$messageId " +
                                "sender=${senderId.takeLast(8)} senderKeyKnown=$known " +
                                "bytes=${rawText.length} - relaying for another node, " +
                                "or our key does not match",
                        )
                        return
                    }
                    opened
                } else {
                    rawText
                }

                Log.i(TAG, "Message from $senderId in chat $chatId (sealed=$sealed)")
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
                    // Раунд 129: ССЫЛКА на гифку - сам контент сообщения
                    // («APUGIFREF1|<sha>»). Перехватывается здесь: в чат
                    // ложится карточка от лица отправителя, байты каждый
                    // телефон тихо тянет с хранителей. Даже если конверт
                    // утечёт через запасной путь доставки и сохранится как
                    // обычное сообщение - экран чата рисует по нему карточку.
                    if (com.vladimir.messenger.data.gif.GifLibrary.isGifRef(text)) {
                        val refSha = com.vladimir.messenger.data.gif.GifLibrary.gifRefSha(text)
                        if (refSha != null) {
                            serviceScope.launch {
                                runCatching {
                                    chatRepository.insertReceivedGifRefMessage(
                                        chatId = chatId,
                                        senderId = senderId,
                                        messageId = messageId,
                                        sha256 = refSha,
                                        timestamp = System.currentTimeMillis(),
                                    )
                                }.onFailure { Log.w(TAG, "gif ref insert failed: " + it.message) }
                                // Раунд 131: байты тянем СРАЗУ, не дожидаясь,
                                // пока человек откроет чат - карточка оживает сама.
                                runCatching { ensureGifBytesForRef(refSha) }
                                    .onFailure { Log.w(TAG, "gif ref fetch failed: " + it.message) }
                            }
                        }
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "GIF ref ACK failed: " + e.message)
                        }
                        return
                    }

                    if (com.vladimir.messenger.data.gif.GifLibrary.isGifPacket(text)) {
                        handleGifEnvelope(senderId, chatId, messageId, text)
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "GIF packet ACK failed: " + e.message)
                        }
                        return
                    }

                    if (com.vladimir.messenger.data.sticker.StickerLibrary.isStickerPacket(text)) {
                        handleStickerEnvelope(senderId, chatId, messageId, text)
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Sticker packet ACK failed: " + e.message)
                        }
                        return
                    }

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

                    // Раунд 135: «удали у всех» в личном чате - конверт
                    // APUDEL1 разбирается до сохранения, в переписку ему
                    // дороги нет (как у реакций).
                    if (messageDeletion.routeIncoming(senderId, text)) {
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Delete packet ACK failed: " + e.message)
                        }
                        return
                    }

                    // Просмотр поста канала: счётчик под записью.
                    if (postViews.routeIncoming(senderId, text)) {
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Post view ACK failed: " + e.message)
                        }
                        return
                    }

                    // Сердечко профилю: рейтинг популярности. Разбирается до
                    // сохранения, иначе служебный конверт лёг бы в переписку.
                    if (hearts.routeIncoming(senderId, text)) {
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Heart packet ACK failed: " + e.message)
                        }
                        return
                    }

                    // Рой-копии азбуки: store/ack/ask/give. Конверт может нести
                    // до 120 КБ шифрованных байтов — в чат и автоконтакты ему
                    // дороги нет.
                    if (addressBookSwarm.routeIncoming(senderId, text)) {
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Swarm backup ACK failed: " + e.message)
                        }
                        return
                    }

                    // Отчёт «прочитано»: у отправителя галочки станут синими.
                    // Разбирается здесь же, до сохранения, иначе служебный
                    // конверт осел бы в переписке мусорной строкой.
                    if (readReceipts.routeIncoming(senderId, text)) {
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Read receipt ACK failed: " + e.message)
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
                            val autoName = NodeIds.autoName(senderId)
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
                // Рейтинг узлов: отмечаем, что он был на месте. Из таких
                // наблюдений и складывается «кому слать в первую очередь».
                runCatching {
                    com.vladimir.messenger.data.peer.PeerRatingStore.recordSighting(
                        context = applicationContext,
                        peerId = peerId,
                        address = event.status,
                        nowMs = now,
                    )
                }
                val lastSeen = knownPeers[peerId] ?: 0L
                knownPeers[peerId] = now  // свежесть ДО дедупа: пульс = жизнь
                val lightTouch = now - lastSeen < PEER_DEDUP_MS
                // Хранение у третьего телефона (этап 7 роя): маршрутизатор
                // файлов ведёт свой список «кто в сети» - по нему выбирается
                // хранитель и отдаётся хранимое появившемуся получателю.
                serviceScope.launch {
                    runCatching { fileTransferRouter.markOnline(peerId) }
                        .onFailure { Log.w(TAG, "custody presence failed: ${it.message}") }
                    // Файл группы, который я жду, а его сид только что появился:
                    // спросить сразу, не дожидаясь очередного круга (этап 9).
                    if (!lightTouch) {
                        runCatching { groupFiles.onPeerOnline(peerId) }
                            .onFailure { Log.w(TAG, "group file presence failed: ${it.message}") }
                        // Обновление, которое я жду, может раздавать именно
                        // этот узел: спросить сразу, не дожидаясь круга.
                        runCatching { apkSeeder.onPeerOnline(peerId) }
                            .onFailure { Log.w(TAG, "apk seeder presence failed: ${it.message}") }
                    }
                }

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
                            // Имя пришло с presence - просить нечего.
                        } else if (!contactRepository.isRealName(peerName) &&
                            !contactRepository.isRealName(existing.displayName)
                        ) {
                            // И в контакте, и в presence - «буквыцифры».
                            // Ждать роевую рассылку @имени можно часами,
                            // поэтому просим узел представиться адресно.
                            serviceScope.launch {
                                runCatching { groupRepository.requestIdentity(peerId) }
                                    .onFailure { Log.w(TAG, "whois failed: ${it.message}") }
                            }
                        } else if (contactRepository.isRealName(existing.displayName)) {
                            // Отдельная подстраховка для таблицы чатов: имя в
                            // контакте могли уже поправить вручную, а в шапке
                            // переписки так и осталось техническое - там своя
                            // копия имени. Дёшево и только при полном пульсе.
                            chatRepository.updateContactName(peerId, existing.displayName)
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
                    } else if (contactRepository.isRealName(peerName)) {
                        // Переустановка приложения выдаёт узлу НОВЫЙ адрес, а
                        // человек остаётся прежним. Раньше такой узел просто
                        // игнорировался: старая запись висела с мёртвым адресом,
                        // имя не обновлялось, сообщения уходили в пустоту - и
                        // это выглядело как «имя не приходит» и «связь в одну
                        // сторону». Presence несёт настоящее имя, поэтому
                        // переселяем прежнюю запись на новый адрес вместе с
                        // перепиской. Заглушки («Contact ab12») не трогаем:
                        // по ним человека не опознать.
                        val twins = runCatching {
                            contactRepository.findStaleTwins(peerName, peerId)
                        }.getOrDefault(emptyList())
                        if (twins.isNotEmpty()) {
                            runCatching {
                                // addContact вернёт ошибку «уже есть», если
                                // запись успели создать параллельно - это не
                                // помеха переносу, поэтому результат не проверяем.
                                contactRepository.addContact(peerName, peerId)
                                chatRepository.getOrCreateChat(peerId, peerName)
                                for (old in twins) {
                                    chatRepository.absorbChatOf(old, peerId)
                                    contactRepository.deleteContact(old)
                                }
                                Log.i(TAG, "Контакт $peerName переехал на новый адрес; перенесено записей: ${twins.size}")
                            }.onFailure { Log.w(TAG, "Не удалось перенести контакт: ${it.message}") }
                        } else {
                            if (!lightTouch) Log.i(TAG, "ℹ Peer не в контактах: $peerName ($peerId) — нужен invite link")
                            if (lightTouch) return
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
                fileTransferRouter.markOffline(peerId)
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
                    fileTransferRouter.markOffline(pid)
                    // Пропущенный круг - минус к доступности в рейтинге.
                    runCatching {
                        com.vladimir.messenger.data.peer.PeerRatingStore
                            .recordMiss(applicationContext, pid)
                    }
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
        // K3: шаг опроса, пока события приходят пучком (идёт файл): кусок
        // не ждёт следующего полного цикла, ACK-окно отправителя движется.
        private const val BUSY_POLL_INTERVAL_MS = 100L
        private const val BUSY_BATCH = 8
        /** Под каким именем нас уже записал справочник. */
        private const val REGISTRY_NAME_KEY = "registry_registered_name"
        /** Когда записал. */
        private const val REGISTRY_AT_KEY = "registry_registered_at"
        /** Повторяем запись не чаще раза в неделю. */
        private const val REGISTRY_REFRESH_MS = 7L * 24 * 60 * 60 * 1000
    }
}