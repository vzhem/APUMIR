package com.vladimir.messenger.service

import com.vladimir.messenger.data.link.ServiceHost
import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics
import com.vladimir.messenger.data.diagnostics.OperationalCounter
import com.vladimir.messenger.data.diagnostics.DiagnosticPhase
import com.vladimir.messenger.data.diagnostics.RuntimeDiagnostics
import com.vladimir.messenger.data.diagnostics.DiagnosticOperation

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.vladimir.messenger.data.call.CallIncoming
import android.util.Log
import com.vladimir.messenger.domain.model.MessageChannel
import com.vladimir.messenger.data.relay.RelayEnvelope
import com.vladimir.messenger.domain.model.MessageStatus
import androidx.core.app.NotificationCompat
import com.vladimir.messenger.MainActivity
import com.vladimir.messenger.MessengerApplication
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.diagnostics.Counters
import com.vladimir.messenger.data.diagnostics.TransferDiagnostics
import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.repository.MtProxyRepository
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import com.vladimir.messenger.data.security.MessageSealer
import com.vladimir.messenger.data.security.SealedWire
import com.vladimir.messenger.data.referral.ReferralRankStore
import com.vladimir.messenger.data.receipt.DeliveryAckWire
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
import kotlinx.coroutines.async
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

    /** Досылка офлайн-очереди своим ритмом: коротким шагом, пока есть что слать. */
    private val chatOutboxPump: ChatOutboxPump by lazy {
        ChatOutboxPump(appDatabase.messageDao(), chatRepository)
    }
    @Inject lateinit var contactRepository: ContactRepository
    @Inject lateinit var mtProxyRepository: MtProxyRepository
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var botApi: BotApi
    @Inject lateinit var updateChecker: UpdateChecker
    @Inject lateinit var fileTransferRouter: com.vladimir.messenger.data.file.FileTransferRouter
    @Inject lateinit var identityBackup: com.vladimir.messenger.data.security.IdentityBackup
    @Inject lateinit var addressBookBackup: com.vladimir.messenger.data.backup.AddressBookBackup
    @Inject lateinit var addressBookSwarm: com.vladimir.messenger.data.backup.AddressBookSwarmBackup
    @Inject lateinit var linkShortener: com.vladimir.messenger.data.link.LinkShortener
    @Inject lateinit var readReceipts: com.vladimir.messenger.data.receipt.ReadReceiptRepository
    @Inject lateinit var messageDeletion: com.vladimir.messenger.data.repository.MessageDeletionRepository
    @Inject lateinit var messageEdit: com.vladimir.messenger.data.repository.MessageEditRepository
    @Inject lateinit var hearts: com.vladimir.messenger.data.heart.HeartRepository
    @Inject lateinit var postViews: com.vladimir.messenger.data.channel.PostViewRepository
    @Inject lateinit var deferredDelivery: com.vladimir.messenger.data.schedule.DeferredDelivery
    @Inject lateinit var groupRouter: com.vladimir.messenger.data.group.GroupRouter
    @Inject lateinit var groupRepository: com.vladimir.messenger.data.group.GroupRepository
    @Inject lateinit var groupFiles: com.vladimir.messenger.data.group.GroupFileSwarm
    @Inject lateinit var swarmPeerDirectory: com.vladimir.messenger.data.swarm.SwarmPeerDirectory
    @Inject lateinit var apkSeeder: com.vladimir.messenger.data.update.ApkSeeder
    @Inject lateinit var referralAttributionRouter: com.vladimir.messenger.data.referral.ReferralAttributionRouter
    @Inject lateinit var heartGameNetwork: com.vladimir.messenger.data.heartgame.HeartGameNetwork
    @Inject lateinit var peerRankRouter: com.vladimir.messenger.data.rank.PeerRankRouter
    @Inject lateinit var peerRankStore: com.vladimir.messenger.data.rank.PeerRankStore
    @Inject lateinit var peerRankBroadcaster: com.vladimir.messenger.data.rank.PeerRankBroadcaster
    @Inject lateinit var callManager: com.vladimir.messenger.data.call.CallManager
    @Inject lateinit var reactionRepository: com.vladimir.messenger.data.reaction.ReactionRepository
    @Inject lateinit var repostRepository: com.vladimir.messenger.data.repost.RepostRepository
    // Отклики на истории: значки и комментарии зрителей (см. docs/STORY_REACTIONS_AND_COMMENTS.md).
    @Inject lateinit var storyFeedback: com.vladimir.messenger.data.story.StoryFeedbackRepository
    @Inject lateinit var groupDao: com.vladimir.messenger.data.local.dao.GroupDao
    @Inject lateinit var appDatabase: com.vladimir.messenger.data.local.AppDatabase
    @Inject lateinit var inboxPinDao: com.vladimir.messenger.data.local.dao.InboxPinDao
    @Inject lateinit var counters: com.vladimir.messenger.data.channel.PostCounterRepository
    @Inject lateinit var swarmBudget: com.vladimir.messenger.data.swarm.SwarmBudget
    @Inject lateinit var gifPreparation: com.vladimir.messenger.data.file.OutgoingFilePreparationService
    @Inject lateinit var stickerLibrary: com.vladimir.messenger.data.sticker.StickerLibrary
    private var gossipStarted = false
    @Inject lateinit var proxyAutopilot: com.vladimir.messenger.service.ProxyAutopilot

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastWakeLockAtElapsed = 0L
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkMonitor: NetworkMonitor? = null
    private val serviceJob = kotlinx.coroutines.SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val startupInitialized = java.util.concurrent.atomic.AtomicBoolean(false)
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
    // Ядро объявляет о себе раз в минуту (было раз в 30 секунд): «пропал» = три
    // пропуска подряд = 200 секунд. Уборка реже: она будит процесс, спешить некуда.
    private val PRESENCE_TTL_MS = 200_000L    // ~3 пропущенных MQTT-пульса
    private val PRESENCE_SWEEP_MS = 60_000L
    private val FILE_PUMP_INTERVAL_MS = 20000L
    private val INITIAL_FILE_PUMP_DELAY_MS = 5000L
    /** Boot-time readiness only: a failed key unlock cannot be diagnosed as a peer-key change. */
    @Volatile private var localCryptoReadyAtStartup = false

    // Раунд 121: свой каталог гифок роя (APUGIF1).
    private val gifCatalogSentAt = mutableMapOf<String, Long>()
    private val gifAskHandledAt = mutableMapOf<String, Long>()
    private val gifWantServedAt = mutableMapOf<String, Long>()

    // Раунд 139: свой каталог стикеров роя (APUSTK1) - как у гифок.
    private val stickerCatalogSentAt = mutableMapOf<String, Long>()
    private val stickerAskHandledAt = mutableMapOf<String, Long>()
    private val stickerWantServedAt = mutableMapOf<String, Long>()

    // Keep only ids in the rolling notification look-back window; Room's query is likewise
    // bounded to two minutes and indexed by (isFromMe, timestamp).
    private val notifiedMessageIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
            RuntimeDiagnostics.measure(DiagnosticOperation.FILE_PUMP) { fileTransferRouter.pumpOutgoing() }
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
    private suspend fun markMessageDelivered(messageId: String): Boolean {
        deferredDelivery.noteTransportAck(messageId)
        return chatRepository.markOutgoingMessageDelivered(messageId)
    }

    /**
     * A successfully opened sealed message proves the sender's signed file/message
     * binding. Learn it before sending the ACK: otherwise a receiver that missed
     * HELLO can display the message but cannot send a sealed reply. A changed
     * binding is reported, never overwritten automatically.
     */
    private suspend fun rememberOpenedMessageBinding(senderId: String, wire: String) {
        when (fileTransferRouter.rememberOpenedMessageBinding(senderId, wire)) {
            com.vladimir.messenger.data.file.FileTransferRouter.OpenedMessageBindingResult.KEY_CHANGED -> {
                TransferDiagnostics.notePeerKeyChanged()
                Log.w(TAG, "opened message binding differs from pinned peer binding")
            }
            com.vladimir.messenger.data.file.FileTransferRouter.OpenedMessageBindingResult.PINNED_NEW -> {
                Log.i(TAG, "learned sender binding from opened message")
            }
            else -> Unit
        }
    }

    /**
     * Уведомить отправителя, что его приглашение отклонено настройками
     * приватности этого телефона. Самому получателю по-прежнему полная
     * тишина; отправитель получает служебный пакет и видит человеческое
     * уведомление «не доставлено: вы не контакт» или «абонент запретил
     * всем». Не чаще раза в час одному человеку (claimRejectNotice).
     */
    private suspend fun sendInviteReject(senderId: String, content: String) {
        if (!senderId.startsWith("pk_")) return
        val reason = when (
            com.vladimir.messenger.data.profile.InvitePrivacyStore.value(applicationContext)
        ) {
            com.vladimir.messenger.data.profile.InvitePrivacy.NOBODY ->
                com.vladimir.messenger.data.invite.InviteRejectWire.Reason.NOBODY
            com.vladimir.messenger.data.profile.InvitePrivacy.CONTACTS ->
                com.vladimir.messenger.data.invite.InviteRejectWire.Reason.NOT_CONTACT
            com.vladimir.messenger.data.profile.InvitePrivacy.ALL -> return
        }
        if (!com.vladimir.messenger.data.profile.InvitePrivacyStore
                .claimRejectNotice(applicationContext, senderId)
        ) {
            return
        }
        // Название - только у одиночной карточки; у мульти-приглашения
        // отправителю уходит общая формулировка без названия.
        val title = com.vladimir.messenger.util.GroupInviteCardSender
            .parseCard(content)?.title.orEmpty()
        val packet = com.vladimir.messenger.data.invite.InviteRejectWire.build(reason, title)
        val direct = runCatching { RustBridge.sendDirectPayload(senderId, packet) }
            .getOrDefault(false)
        if (!direct) {
            runCatching {
                RustBridge.sendMessage(java.util.UUID.randomUUID().toString(), "", senderId, packet)
            }
        }
        Log.i(TAG, "invite reject notice sent to $senderId reason=$reason")
    }

    /** Входящий отказ на МОЁ приглашение: заметка в чат + уведомление. */
    private suspend fun handleInviteReject(senderId: String, text: String) {
        val notice = com.vladimir.messenger.data.invite.InviteRejectWire.parse(text) ?: return
        runCatching { chatRepository.insertInviteRejectNotice(senderId, notice) }
            .onFailure { Log.w(TAG, "invite reject notice insert failed: ${it.message}") }
    }

    private suspend fun routeIncomingEnvelope(
        senderId: String,
        chatId: String,
        messageId: String,
        text: String,
        authenticated: Boolean = false,
    ): Boolean {
        // Pairing and large authenticated mirror frames must win over deferred
        // delivery as well as over the normal chat parser. Devices of one
        // account share a routing node id, so neither path may self-drop these
        // control packets before the pairing/device MAC is checked.
        if (com.vladimir.messenger.data.pairing.DevicePairingWire.isPairingText(text)) {
            com.vladimir.messenger.data.pairing.DevicePairingController
                .routeIncoming(applicationContext, senderId, text)
            return true
        }
        if (deferredDelivery.routeIncoming(senderId, chatId, messageId, text, authenticated)) return true
        // р243/р244: подтверждение доставки, пришедшее прямым каналом. Второй
        // путь приёма обязан разбирать его так же, как основной: иначе строка
        // «ack|…» вырастет в личный чат. Даже некорректный ACK с зарезервированным
        // префиксом съедаем, но статус меняем только для разобранного id.
        if (com.vladimir.messenger.data.reply.DirectReplyWire.isPacket(text)) {
            com.vladimir.messenger.data.reply.DirectReplyWire.parse(text)?.let { packet ->
                runCatching { chatRepository.applyDirectReply(packet) }
                    .onFailure { Log.w(TAG, "direct reply apply failed: ${it.message}") }
            }
            return true
        }
        if (DeliveryAckWire.isPacket(text)) {
            val ackedId = DeliveryAckWire.messageId(text)
            if (ackedId != null) {
                // ACK-кадр нужен и партнёру той же личности, но не сохраняется.
                mirror?.publishEnvelope(senderId, chatId, messageId, text)
                runCatching { markMessageDelivered(ackedId) }
                    .onSuccess { changed ->
                        Log.i(TAG, "📬 direct delivery ACK (relay path): msgId=$ackedId changed=$changed")
                    }
            } else {
                Log.w(TAG, "malformed delivery ACK on relay path; dropped")
            }
            return true
        }

        // Отказ на моё приглашение (настройки приватности получателя):
        // заметка в чат с ним и обычное уведомление, в переписку служебная
        // строка не ложится.
        if (com.vladimir.messenger.data.invite.InviteRejectWire.isPacket(text)) {
            routePacket(TAG, "inviteReject") { handleInviteReject(senderId, text); true }
            return true
        }

        // р227: этот путь (запасной, через облачный релей) - второй вход
        // служебных конвертов; зеркалу их отдаём так же, как основной путь.
        mirror?.publishEnvelope(senderId, chatId, messageId, text)

        // Раунд 141: каждый страж в защитной обёртке - если какой-то
        // роутер упал на обычном письме, письмо ДОЛЖНО доехать до чата
        // и уведомления, а не исчезнуть молча.
        if (routePacket(TAG, "fileTransfer") { fileTransferRouter.routeIncoming(senderId, chatId, messageId, text) }) {
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
            routePacket(TAG, "gif") { handleGifEnvelope(senderId, chatId, messageId, text); true }
            return true
        }
        if (com.vladimir.messenger.data.sticker.StickerLibrary.isStickerPacket(text)) {
            routePacket(TAG, "sticker") { handleStickerEnvelope(senderId, chatId, messageId, text); true }
            return true
        }
        if (routePacket(TAG, "group") { groupRouter.routeIncoming(senderId, chatId, messageId, text) }) {
            return true
        }
        if (routePacket(TAG, "reaction") { reactionRepository.routeIncoming(senderId, text) }) return true
        if (routePacket(TAG, "repost") { repostRepository.routeIncoming(senderId, text) }) return true
        if (routePacket(TAG, "deletion") { messageDeletion.routeIncoming(senderId, text) }) return true
        if (routePacket(TAG, "edit") { messageEdit.routeIncoming(senderId, text) }) return true
        if (routePacket(TAG, "postViews") { postViews.routeIncoming(senderId, text) }) return true
        if (routePacket(TAG, "hearts") { hearts.routeIncoming(senderId, text) }) return true
        if (routePacket(TAG, "addressBook") { addressBookSwarm.routeIncoming(senderId, text) }) return true
        if (routePacket(TAG, "linkShortener") { linkShortener.routeIncoming(senderId, text) }) return true
        if (routePacket(TAG, "readReceipts") { readReceipts.routeIncoming(senderId, text) }) return true
        if (routePacket(TAG, "referral") { referralAttributionRouter.routeIncoming(senderId, text) }) return true
        // Ранг собеседника (APURANK1): знак VIP у имени. Поглощается здесь же,
        // чтобы служебная строка не стала текстом в переписке.
        // Счёт игры и ранг - раздельно (см. RankPacketRouting): сбой одного
        // конверта не должен съедать второй, иначе знак VIP не появляется.
        if (routeRankOrHeartPacket(heartGameNetwork, peerRankRouter, senderId, text)) return true
        if (routePacket(TAG, "call") { callManager.routeIncoming(senderId, chatId, messageId, text) }) {
            return true
        }
        // р235: «печатает…» - самый лёгкий служебный пакет; разбирается
        // последним, чтобы не мешать остальным.
        if (routePacket(TAG, "typing") {
                com.vladimir.messenger.data.typing.TypingRouter.routeIncoming(senderId, text)
            }
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
            RuntimeDiagnostics.measure(DiagnosticOperation.FILE_PUMP) { fileTransferRouter.pumpOutgoing() }
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
        OperationalDiagnostics.tracker.coreStarting()
        CoreStatus.markStarting()
        OperationalDiagnostics.phase(DiagnosticPhase.SERVICE_START)
        Log.i(TAG, "CoreServerService created")
        TransferDiagnostics.record("core", "служба ядра запускается")
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "P2PMessenger::CoreWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(CallIncoming.CORE_WAKE_LOCK_MS)
        }
        lastWakeLockAtElapsed = SystemClock.elapsedRealtime()
        OperationalDiagnostics.wake.acquire(lastWakeLockAtElapsed, CallIncoming.CORE_WAKE_LOCK_MS)

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

        
        // Сердечки: общая рассылка раз в 6 часов, только когда телефон свободен (HeartSyncPolicy).
        // Ядро живёт в этом сервисе постоянно, поэтому цикл здесь, а не в экране игры.
        serviceScope.launch {
            while (isActive) {
                try {
                    heartGameNetwork.broadcastIfNeeded()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Ядро или подпись недоступны: повторим на следующем круге.
                }
                delay(com.vladimir.messenger.data.heartgame.HeartSyncPolicy.CHECK_PERIOD_MS)
            }
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

    /**
     * Войти в foreground с ЯВНЫМ типом сервиса.
     *
     * Android 14+ проверяет разрешения по объявленному типу, а Android 15
     * ограничивает `dataSync` шестью часами в сутки и запрещает запускать его
     * из BOOT_COMPLETED (docs/AUDIT_2026-10-26.md, P0-1). Поэтому на API 34+
     * берём `specialUse` («постоянная p2p-доставка»), а на 29-33 — `dataSync`:
     * тип `specialUse` появился только в API 34, и передавать его неизвестной
     * системе нельзя.
     *
     * Тип передаётся явно, а не через манифест (двухаргументный
     * `startForeground`), иначе на Android 15 учитывался бы и `dataSync`
     * из манифеста, и лимит в 6 часов вернулся бы.
     *
     * @return false — система отказала в foreground (тогда сервис уже
     *         остановлен, а будильник разбудит его позже).
     */
    private fun enterForeground(notification: Notification): Boolean {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            true
        } catch (e: Exception) {
            // Android 12+ может запретить старт из фона (например, BOOT_COMPLETED
            // на части прошивок). Раньше это исключение не перехватывалось
            // вообще на этом пути, а внешние стартеры глушили его runCatching —
            // сервис молча не работал, и причина нигде не появлялась.
            OperationalDiagnostics.tracker.count(OperationalCounter.FOREGROUND_START_REFUSED)
            Log.w(TAG, "startForeground refused (sdk=" + Build.VERSION.SDK_INT + ")", e)
            runCatching { EmergencyKeepAlive.onServiceRefused(applicationContext) }
            stopSelf()
            false
        }
    }

    /**
     * Android 15: истёк бюджет времени foreground-сервиса. Остановиться нужно
     * за несколько секунд, иначе система убивает процесс
     * (`ForegroundServiceDidNotStopInTimeException`). На API 34+ основной путь
     * идёт через `specialUse`, у которого такого лимита нет, поэтому сюда
     * попадаем только в крайнем случае (старый APK, нестандартная прошивка).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        OperationalDiagnostics.tracker.count(OperationalCounter.FOREGROUND_TIMEOUT)
        Log.w(TAG, "foreground timeout (type=" + fgsType + "); stopping and rescheduling")
        runCatching { EmergencyKeepAlive.onServiceRefused(applicationContext) }
        stopSelf(startId)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        OperationalDiagnostics.tracker.count(OperationalCounter.SERVICE_START_COMMAND)
        Log.i(TAG, "CoreServerService started (action=" + (intent?.action ?: "null") + ")")
        if (!enterForeground(buildNotification("Подключение..."))) {
            // Без foreground сервис живёт секунды и будет убит системой с
            // невнятной ошибкой; выходим сами, будильник поднимет позже.
            return START_NOT_STICKY
        }

        // Никогда не создаём native engine до завершения онбординга. Сервис
        // может быть поднят будильником или старым APK, пока prefs ещё не
        // содержат identity; такой Anonymous engine затем перехватывает
        // CreateIdentityUseCase и оставляет launcher на пустом routing id.
        val prefs = getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        val identityReady = prefs.getBoolean("identity_created", false) &&
            com.vladimir.messenger.data.security.DeviceIdentityMarker.isPresent(applicationContext)
        if (!identityReady) {
            Log.i(TAG, "CoreServerService deferred: local identity is not ready")
            CoreStatus.report("Ожидание создания identity")
            CoreStatus.markReady()
            stopServiceSafely()
            return START_NOT_STICKY
        }

        // Раунд 141 / P1-6: аварийная страховка. Раньше здесь взводился
        // ТОЧНЫЙ будильник «+5 минут» на каждый старт — 288 пробуждений в
        // сутки в Doze. Теперь сервис живёт и держит одну неточную страховку
        // в час; настойчивая лестница 5→15→30→60 минут включается только
        // когда сервис поднять не удалось (EmergencyKeepAlive.onServiceRefused).
        EmergencyKeepAlive.onServiceStarted(applicationContext)

        // The keep-alive alarm (and network restarts) can deliver another start intent to the
        // same Service instance. Foreground notification/alarm maintenance above is safe to repeat;
        // the long-lived startup pumps and collectors below must only be created once.
        if (!startupInitialized.compareAndSet(false, true)) {
            Log.i(TAG, "Duplicate start intent; existing service workers remain active")
            return START_STICKY
        }

        // р240: счётчик недоотправленных - для экрана «Диагностика
        // синхронизации» в настройках. Обновляем редко: это подсказка, а не
        // боевой счётчик.
        // р242: тот же цикл раз в минуту досылает PENDING - не полагаемся на
        // событие «увидели собеседника», которое может быть пропущено.
        serviceScope.launch {
            // Раунд 261: первая итерация - не сразу: холодному старту важнее
            // поднять движок, а не считать незавершённые отправки.
            kotlinx.coroutines.delay(10_000L)
            var sincePump = 0
            while (true) {
                runCatching { chatRepository.countPendingOutgoing() }
                    .onSuccess { com.vladimir.messenger.data.mirror.MirrorHub.setPendingOutgoing(it) }
                // р243: держим свежим кэш своего адреса - он нужен и без
                // зеркала (страж «печатает», отсев сообщений от себя).
                runCatching {
                    com.vladimir.messenger.data.mirror.MirrorHub
                        .noteSelfNodeId(RustBridge.nodeId().orEmpty())
                }
                if (sincePump >= 2) {
                    sincePump = 0
                    runCatching { chatRepository.pumpPendingOutgoing() }
                        .onFailure { Log.w(TAG, "pending pump failed: ${it.message}") }
                    // р245: медиа. У тени нет сети, поэтому байты гифок ей
                    // взять неоткуда - просим у активного партнёра. Карточка в
                    // чате к этому моменту уже есть, оживёт по приходу байтов.
                    runCatching { pumpMirrorMedia() }
                        .onFailure { Log.w(TAG, "media pump failed: ${it.message}") }
                    // р249: байты файлов, которые тень не смогла попросить в
                    // момент приёма (была офлайн или без активного партнёра).
                    runCatching { pumpMirrorFiles() }
                        .onFailure { Log.w(TAG, "file bytes pump failed: ${it.message}") }
                }
                val wakeHeld = wakeLock?.isHeld == true
                val wakeAge = SystemClock.elapsedRealtime() - lastWakeLockAtElapsed
                if (CallIncoming.shouldRefreshWakeLock(wakeHeld, wakeAge)) {
                    runCatching { wakeLock?.acquire(CallIncoming.CORE_WAKE_LOCK_MS) }.onSuccess {
                        if (wakeLock?.isHeld == true) OperationalDiagnostics.wake.acquire(SystemClock.elapsedRealtime(), CallIncoming.CORE_WAKE_LOCK_MS)
                    }
                    lastWakeLockAtElapsed = SystemClock.elapsedRealtime()
                }
                sincePump++
                kotlinx.coroutines.delay(30_000L)
            }
        }

        // Убрать из базы мусор прошлых версий: «печатает…» из r235-r238 и
        // UUID-shaped ACKs, которые клиенты до r247 могли сохранить как текст.
        serviceScope.launch {
            runCatching { chatRepository.cleanupTypingJunk() }
                .onFailure { Log.w(TAG, "Typing junk cleanup failed: ${it.message}") }
            runCatching { chatRepository.cleanupDeliveryAckJunk() }
                .onFailure { Log.w(TAG, "Delivery ACK junk cleanup failed: ${it.message}") }
        }

        // Роевые публикации: моё @имя и каталог групп - при старте и при смене имени.
        if (!gossipStarted) {
            gossipStarted = true
            serviceScope.launch {
                // Раунд 261: сверки и роевые публикации не нужны в первую
                // секунду - откладываем на 30 с, чтобы не конкурировать с
                // подъёмом движка за диск и сеть.
                kotlinx.coroutines.delay(30_000)
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
                // День рождения: своя (видимая) дата — контактам, скрытая не
                // уходит вовсе. Даты друзей лежат в своей книге.
                runCatching { groupRepository.publishMyBirthday() }
                // Эмодзи-статус у профиля: объявить свой сети при старте.
                runCatching { groupRepository.publishMyEmojiStatus() }
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
            // Задал, изменил или скрыл день рождения - сразу объявить (скрытие
            // шлёт отзыв: у друзей старая дата не должна остаться).
            serviceScope.launch {
                com.vladimir.messenger.data.profile.BirthdayStore.mine
                    .drop(1)
                    .collect { runCatching { groupRepository.publishMyBirthday(force = true) } }
            }
            // Сменил или убрал эмодзи-статус — сеть узнаёт сразу (уборка = отзыв).
            serviceScope.launch {
                com.vladimir.messenger.data.profile.EmojiStatusStore.mine
                    .drop(1)
                    .collect { runCatching { groupRepository.publishMyEmojiStatus(force = true) } }
            }
        }

        var displayName = prefs.getString("display_name", "Anonymous") ?: "Anonymous"
        // сли Anonymous но профиль уже создан - перечитать
        if (displayName == "Anonymous" && prefs.getBoolean("identity_created", false)) {
            displayName = prefs.getString("display_name", "Anonymous") ?: "Anonymous"
        }
        val existingPubKey = prefs.getString("existing_public_key", null)
        val existingPrivKey = prefs.getString("existing_private_key", null)

        Log.i(TAG, "Starting engine: displayName=$displayName existingKey=${existingPubKey?.take(16)}")

        serviceScope.launch {
            // Раунд 263: честный статус для сплэша - этап подготовки ключей.
            CoreStatus.report("Готовим ключи безопасности…")
            // R0.5/S3: legacy routing ID остаётся неизменным; реальный Ed25519
            // signing sidecar устанавливается до engine start и пока используется
            // только diagnostics/future signed features.
            val legacyRoutingId = existingPubKey
                ?: prefs.getString("node_id", null)
                .orEmpty()
            // The signed identity is captured ONCE when Rust starts. Give its Keystore unlock
            // priority over relay custody and address-book restoration on a cold KeyMint;
            // competing operations previously had the same 4s deadline and could leave the
            // running core permanently in legacy-only mode until a restart.
            localCryptoReadyAtStartup = false
            val identityCreated = prefs.getBoolean("identity_created", false)
            // Wrap nullable installer results: null from withTimeoutOrNull means a timeout,
            // whereas a completed installer may also return null (unavailable/mismatch).
            val signingAttempt = if (identityCreated) {
                kotlinx.coroutines.withTimeoutOrNull(8_000) {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        IdentitySigningKeyStore.installIntoCore(applicationContext, legacyRoutingId)
                    }
                    Unit to result
                }
            } else null
            val signing = signingAttempt?.second
            val signingInstall = when {
                !identityCreated -> "skipped_no_identity"
                signingAttempt == null -> "timeout"
                signing == null -> "unavailable"
                else -> "ready"
            }
            Log.i(
                TAG,
                "Identity signing mode: ${signing?.mode ?: "legacy-only"}, " +
                    "keyId=${signing?.keyId?.take(12) ?: "none"}"
            )
            val fileAttempt = if (signing != null) {
                kotlinx.coroutines.withTimeoutOrNull(8_000) {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        IdentitySigningKeyStore.existingVerifiedBinding(applicationContext)?.let { identityBinding ->
                            FileExchangeKeyStore.initialize(applicationContext, legacyRoutingId, identityBinding)
                        }
                    }
                    Unit to result
                }
            } else null
            val fileExchange = fileAttempt?.second
            val fileExchangeInstall = when {
                signing == null -> "skipped_no_signing"
                fileAttempt == null -> "timeout"
                fileExchange == null -> "unavailable"
                else -> "ready"
            }
            Log.i(
                TAG,
                "File exchange identity: ${if (fileExchange != null) "ready" else "unavailable"}, " +
                    "key=${fileExchange?.publicKeySha256Prefix ?: "none"}"
            )
            localCryptoReadyAtStartup = signing != null && fileExchange != null
            TransferDiagnostics.noteCryptoBootstrap(signingInstall, fileExchangeInstall, null)
            // Для человека: плашка «этот телефон молчит» вверху списка чатов.
            // Ядро запоминает identity на старте, позже не пересматривает, поэтому
            // решение принимается здесь один раз - и без ключей, только причина.
            com.vladimir.messenger.data.security.IdentityHealth
                .noteBoot(signingInstall, fileExchangeInstall)
            if (signing == null && identityCreated) {
                TransferDiagnostics.recordWarning("crypto", "подписывающий ключ не установлен до старта ядра; прямой подписанный рой недоступен")
                // Причина словами для отчёта: `signing_mode_reason` заполняется
                // только вызовом mode(), иначе там `not_probed` - а отличить
                // сброшенный Keystore от чужого блоба нужно и человеку, и отчёту.
                // Чтение: mode() не создаёт ни ключей, ни семени.
                runCatching { IdentitySigningKeyStore.mode(applicationContext) }
            }
            if (signing != null && fileExchange == null) {
                TransferDiagnostics.recordWarning("crypto", "ключ обмена недоступен при старте; личные конверты и файлы могут не открываться")
            }

            // The other bounded pre-start tasks may now run in parallel: they cannot evict
            // the signing identity from the current core's snapshot.
            val atRestDeferred = async {
                (kotlinx.coroutines.withTimeoutOrNull(4_000) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        RelayAtRestMasterKey.installIntoCore(applicationContext)
                    }
                }) ?: false
            }
            val bookDeferred = async {
                runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(6_000) {
                        addressBookBackup.restoreBeforeStart()
                    }
                }.onFailure { Log.w(TAG, "AddressBook restore failed: ${it.message}") }
            }

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

            // р226: живое зеркало. Устройство-зеркало НЕ стартует ядро и
            // релеи: сеть ведёт партнёр той же личности, этот экран живёт
            // на зеркальных событиях. Партнёр пропал - канал сам попросит
            // повышение (перезапуск уже с движком).
            if (prefs.getBoolean("mirror_defer_engine", false)) {
                startAsMirrorShadow(legacyRoutingId)
                // Раунд 263: зеркало живёт на событиях партнёра - для
                // интерфейса это тоже «готово», заставка не нужна.
                CoreStatus.report("Зеркальный режим: сеть ведёт партнёр")
                CoreStatus.markReady()
                return@launch
            }

            // Раунд 261: параллельные пред-шаги к этому моменту уже доделались
            // (или доехали до своего потолка) - движок стартует без ожидания.
            val atRestKeyOk = atRestDeferred.await()
            Log.i(TAG, "Relay at-rest key installed: $atRestKeyOk")
            // Азбука адресов: на свежей установке облачная копия тянулась
            // параллельно; ядро читает файл один раз при старте.
            bookDeferred.await()

            // Собственный SQLite-файл relay custody (app-private, WAL).
            CoreStatus.report("Поднимаем ядро и сеть…")
            val relayDbPath = File(filesDir, "apu_relay.sqlite").absolutePath
            val ok = RustBridge.initialize(displayName, existingPubKey, existingPrivKey, relayDbPath)
            if (ok) {
                val nodeId = RustBridge.nodeId()
                Log.i(TAG, "Engine OK. NodeId=$nodeId")
                if (signing != null) {
                    val matchesCoreRouting = nodeId != null && legacyRoutingId == nodeId
                    TransferDiagnostics.noteCryptoBootstrap(signingInstall, fileExchangeInstall, matchesCoreRouting)
                    if (!matchesCoreRouting) {
                        localCryptoReadyAtStartup = false
                        TransferDiagnostics.recordWarning("crypto", "подписывающая identity не совпала с routing ID запущенного ядра; прямые подписанные маршруты недоступны")
                    }
                }
                // Environment updates remain bounded; no packet-triggered polling or new timer thread.
                serviceScope.launch {
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        RustBridge.refreshOnlineHelperPolicy()
                        kotlinx.coroutines.delay(30_000)
                    }
                }
                TransferDiagnostics.count(Counters.CORE_STARTS)
                TransferDiagnostics.recordSuccess("core", "ядро поднято")
                // DownloadManager is a separate Android process: an APK
                // queued on fallback can continue using GitHub long after a
                // signed peer appears, unless we cancel the active job.
                serviceScope.launch {
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        runCatching { updateChecker.suspendExternalDownloadsForSwarm() }
                            .onFailure { Log.w(TAG, "Update download barrier: ${it.message}") }
                        kotlinx.coroutines.delay(2_500L)
                    }
                }
                // Раунд 263: ядро поднято - сплэш может отпустить человека.
                CoreStatus.markReady()

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

                // A first-installation book is recovered ONLY after the core
                // has had time to discover direct mDNS/signed neighbours.
                serviceScope.launch {
                    kotlinx.coroutines.delay(25_000L)
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        runCatching { addressBookBackup.autoRestoreIfEmpty() }
                            .onFailure { Log.w(TAG, "AddressBook post-search restore: ${it.message}") }
                        // A snapshot learned from the fallback server must go
                        // to the next reachable signed neighbour promptly;
                        // it is not enough to wait for the hourly backup loop.
                        runCatching { addressBookBackup.swarmHourlyTick() }
                            .onFailure { Log.w(TAG, "AddressBook peer distribution: ${it.message}") }
                        kotlinx.coroutines.delay(60_000L)
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
                // No inbound handler exists here yet (above is TODO). Polling
                // Telegram every five seconds cannot deliver a message and
                // must not bypass swarm discovery just because a token exists.
                Log.i(TAG, "Telegram relay inactive: inbound delivery not implemented")
            }

            // Cloudflare Workers relay (основной запасной канал)
            val cfUrl = prefs.getString("cloudflare_relay_url", ServiceHost.BASE) ?: ServiceHost.BASE
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
                                if (markMessageDelivered(messageId)) {
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
                                    if (DeliveryAckWire.isPacket(existingMsg.content)) {
                                        // Старый ACK мог быть уже записан до обновления.
                                        // Он всё равно подтверждает исходящую строку, но
                                        // ACK самому ACK отправлять нельзя.
                                        DeliveryAckWire.messageId(existingMsg.content)?.let {
                                            markMessageDelivered(it)
                                        }
                                    } else {
                                        // р243: дубликат - это повтор от отправителя,
                                        // который нашей галочки не увидел. Молчание
                                        // здесь и было причиной «висит одна галочка»:
                                        // подтверждение отправляем ЗАНОВО.
                                        runCatching { RustBridge.sendDeliveryAck(messageId, senderId) }
                                    }
                                } else {
                                    // ШИФРОВАНИЕ: это второй, независимый путь приёма. Без
                                    // расшифровки здесь в чат попал бы конверт как текст.
                                    val cfContent = if (SealedWire.isSealed(parsed.content)) {
                                        MessageSealer.open(applicationContext, parsed.content)
                                    } else {
                                        parsed.content
                                    }
                                    if (cfContent == null) {
                                        val knownHere = MessageSealer
                                            .canSeal(applicationContext, senderId)
                                        // Тот же различитель, что в основном пути:
                                        // недавно что-то открылось — ключ рабочий,
                                        // значит это старая копия в пути.
                                        val leftoverHere = knownHere &&
                                            com.vladimir.messenger.data.security.KeyDesyncNotice
                                                .isWorkingRecently(applicationContext, senderId)
                                        Log.w(
                                            TAG,
                                            "CF sealed envelope not opened msgId=$messageId " +
                                                "leftover=$leftoverHere; skipped",
                                        )
                                        com.vladimir.messenger.data.diagnostics.TransferDiagnostics
                                            .noteSealedNotOpened(
                                                senderKeyKnown = knownHere,
                                                staleKeyCopy = leftoverHere,
                                                localIdentityUnavailable = !localCryptoReadyAtStartup,
                                            )
                                    } else {
                                        // Ключ рабочий: гасим плашку и запоминаем
                                        // время успеха — по нему отличаются старые
                                        // копии в пути от настоящего рассинхрона.
                                        com.vladimir.messenger.data.security.KeyDesyncNotice
                                            .opened(applicationContext, senderId)
                                        if (SealedWire.isSealed(parsed.content)) {
                                            try {
                                                rememberOpenedMessageBinding(senderId, parsed.content)
                                            } catch (error: Exception) {
                                                Log.w(TAG, "CF opened message binding learn failed: ${error.message}")
                                            }
                                        }
                                        // Раунд 140: служебные конверты разбираются ДО
                                        // сохранения - тем же стражем, что и основной
                                        // путь. У неизвестного отправителя чат ради
                                        // конверта не создаётся.
                                        val knownChat = chatRepository.getChatByContactId(senderId)
                                        val isDeliveryAck = DeliveryAckWire.isPacket(cfContent)
                                        if (routeIncomingEnvelope(senderId, knownChat?.id ?: "", messageId, cfContent, SealedWire.isSealed(parsed.content) && MessageSealer.openedByExpectedSender(parsed.content, senderId))) {
                                            Log.i(TAG, "CF service envelope handled msgId=$messageId")
                                            // Never generate an ACK-of-ACK. RelayEnvelope's own
                                            // custody receipt below remains a separate protocol.
                                            if (!isDeliveryAck) {
                                                try {
                                                    RustBridge.sendDeliveryAck(messageId, senderId)
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "CF envelope ACK failed: " + e.message)
                                                }
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
                                            if (chatRepository.isInviteBlocked(senderId, cfContent)) {
                                                runCatching { sendInviteReject(senderId, cfContent) }
                                            }
                                            // Диагностика: и этот путь считает входящие
                                            // - иначе через релей сообщения были бы
                                            // невидимы в отчёте.
                                            com.vladimir.messenger.data.diagnostics
                                                .TransferDiagnostics.noteMessageIncoming()
                                            // Раунд 175: как и в основном пути -
                                            // техническое имя -> адресный whois.
                                            runCatching {
                                                val shown = contactRepository.getContactById(senderId)?.displayName
                                                if (shown != null && contactRepository.isPlaceholderName(shown)) {
                                                    groupRepository.requestIdentity(senderId)
                                                }
                                            }
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
                                    if (chatRepository.isInviteBlocked(senderId, parsed.raw)) {
                                        runCatching { sendInviteReject(senderId, parsed.raw) }
                                    }
                                    com.vladimir.messenger.data.diagnostics
                                        .TransferDiagnostics.noteMessageIncoming()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "CF message handling failed", e)
                    }
                }
            }
            // Раунд 214 (разгрузка воркера): релей-поллинг ВЫКЛЮЧЕН. В воркере
            // нет маршрутов /poll и /send - каждый телефон поллил каждые 10
            // секунд и всегда получал 404: ~8 640 ПУСТЫХ запросов в сутки с
            // телефона, именно это выжигало дневной лимит Cloudflare
            // (утренний 1027 «plan limits»). Запасной канал сообщений -
            // Telegram relay выше. Класс сохранён: если маршруты когда-нибудь
            // вернутся, включается префом cf_relay_enabled (default false).
            if (prefs.getBoolean("cf_relay_enabled", false)) {
                cloudflareRelay?.start()
                Log.i(TAG, "Cloudflare relay enabled by pref: $cfUrl")
            } else {
                Log.i(TAG, "Cloudflare relay polling off (no /poll on worker; saves quota)")
            }

                if (nodeId != null) {
                    prefs.edit()
                        .putString("public_key", nodeId)
                        .putString("node_id", nodeId)
                        .putString("existing_public_key", nodeId)
                        .putString("existing_private_key", nodeId)
                        .apply()
                }

                // р226: это устройство ведёт сеть - держать зеркало-партнёра
                // в курсе и отдавать ему хвосты, если он отстал.
                startMirrorChannel(
                    nodeId = nodeId ?: legacyRoutingId,
                    engineUp = true,
                )

                updateNotification("Сеть APU работает")
                startEventPolling()
            } else {
                updateNotification("Не удалось подключиться")
                TransferDiagnostics.count(Counters.CORE_FAILURES)
                TransferDiagnostics.recordFailure("core", "ядро не поднялось — ограниченный режим")
                // Раунд 263: движок не поднялся - не держим человека на
                // заставке: честный ограниченный режим, сплэш отпускаем.
                CoreStatus.report("Ядро в ограниченном режиме")
                CoreStatus.markReady()
                stopServiceSafely()
            }
        }
        return START_STICKY
    }

    // ── р226: живое зеркало устройств одной личности ────────────────────────

    private var mirror: com.vladimir.messenger.data.mirror.MirrorChannel? = null
    private val mirrorRestarting = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Тень: движок и релеи не трогаем, живём на событиях партнёра. */
    private fun startAsMirrorShadow(nodeId: String) {
        Log.i(TAG, "Mirror shadow: движок не стартуем, живём на событиях партнёра")
        runCatching { RustBridge.attachContext(applicationContext) }
        runCatching { RustBridge.ensureCoreOnly() }
        // Личность у тени та же, а движка нет: без этого адреса групповые
        // конверты не применялись бы (проверка «я участник»).
        runCatching { RustBridge.setShadowNodeId(nodeId) }
        updateNotification("APU: сеть ведёт другое устройство (зеркало)")
        startMirrorChannel(nodeId = nodeId, engineUp = false)
    }

    private fun startMirrorChannel(nodeId: String, engineUp: Boolean) {
        if (nodeId.isBlank()) return
        // Без личности зеркалить нечего (полка выводится из узла).
        val mirrorPrefs = getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        if (!mirrorPrefs.getBoolean("identity_created", false)) return
        val channel = com.vladimir.messenger.data.mirror.MirrorChannel(
            context = applicationContext,
            scope = serviceScope,
            nodeId = nodeId,
            deviceTag = com.vladimir.messenger.data.mirror.MirrorSync.deviceTag(applicationContext),
            engineUp = engineUp,
            engineSince = if (engineUp) System.currentTimeMillis() else Long.MAX_VALUE,
            bridge = mirrorBridge(),
        )
        mirror = channel
        channel.start()
        com.vladimir.messenger.data.mirror.MirrorHub.bind(channel)
    }

    private fun mirrorBridge() = object : com.vladimir.messenger.data.mirror.MirrorChannel.Bridge {
        override suspend fun maxMessageTimestamp(): Long = chatRepository.maxMessageTimestamp()

        override suspend fun rowsSince(since: Long, limit: Int) =
            chatRepository.mirrorRowsSince(since, limit)

        override suspend fun pendingOutgoing(limit: Int) =
            chatRepository.mirrorPendingOutgoing(limit)

        override suspend fun applyIncoming(
            row: com.vladimir.messenger.data.mirror.MirrorRow,
            notify: Boolean,
        ) {
            val (chatId, name) = chatRepository.applyMirrorIncoming(row)
            // Пустой chatId = сообщение не сохранено (в т.ч. запрещённое
            // приглашение): уведомления быть не должно.
            if (chatId.isBlank()) return
            if (notify) {
                // SOS и на зеркальном устройстве - тревога, а не обычное
                // уведомление: паузы звука её не касаются.
                if (com.vladimir.messenger.util.SosMessage.isSos(row.content)) {
                    runCatching {
                        notificationHelper.showSosNotification(
                            chatId, row.senderId, null,
                            messageId = row.id,
                        )
                    }
                    return
                }
                // р249: у чата на паузе не показываем сообщение в шторке.
                if (runCatching { chatRepository.isChatMuted(chatId) }.getOrDefault(false)) {
                    Log.i(TAG, "notification suppressed: чат без звука (зеркало)")
                    return
                }
                runCatching {
                    notificationHelper.showMessageNotification(
                        chatId,
                        row.senderId,
                        com.vladimir.messenger.util.ChatPreviews.human(row.content) ?: "Сообщение",
                        true,
                        null,
                        quiet = com.vladimir.messenger.util.QuietMessage.isQuiet(row.content),
                        messageId = row.id,
                    )
                }
            }
        }

        override suspend fun applySent(row: com.vladimir.messenger.data.mirror.MirrorRow) {
            chatRepository.applyMirrorSent(row)
        }

        override suspend fun applyEnvelope(
            senderId: String,
            chatId: String,
            messageId: String,
            text: String,
        ) {
            applyMirrorEnvelope(senderId, chatId, messageId, text)
        }

        override suspend fun onActionFromPartner(
            peerId: String,
            groupId: String,
            chatId: String,
            text: String,
        ) {
            mirrorApplyActionLocally(peerId, groupId, text)
        }

        override suspend fun onActionToPeers(peerId: String, groupId: String, text: String) {
            mirrorSendActionToPeers(peerId, groupId, text)
        }

        override suspend fun onReadFromPartner(peerId: String, groupId: String, topicId: String) {
            mirrorApplyRead(peerId, groupId, topicId)
        }

        override suspend fun onFileMeta(meta: org.json.JSONObject): Boolean =
            runCatching { fileTransferRouter.applyMirrorFileMeta(meta) }
                .onFailure { Log.w(TAG, "Mirror file meta failed: ${it.message}") }
                .getOrDefault(false)

        override suspend fun onFileChunk(
            transferId: String,
            offset: Long,
            seq: Int,
            last: Boolean,
            bytes: ByteArray,
        ) {
            runCatching { fileTransferRouter.applyMirrorFileChunk(transferId, offset, seq, last, bytes) }
                .onFailure { Log.w(TAG, "Mirror file chunk failed: ${it.message}") }
        }

        override suspend fun mirrorFileBytesPresent(
            transferId: String,
            displayName: String,
            totalBytes: Long,
        ): Boolean = fileTransferRouter.mirrorFileBytesPresent(transferId, displayName, totalBytes)

        override suspend fun mirrorFileBytesReceived(transferId: String): Long =
            fileTransferRouter.mirrorReceivedBytesDurable(transferId)

        override suspend fun fileBytesFor(
            transferId: String,
            displayName: String,
            offset: Long,
            size: Int,
        ): ByteArray? = fileTransferRouter.readMirrorFileChunk(transferId, displayName, offset, size)

        override suspend fun onFilePullStart(transferId: String) {
            runCatching { fileTransferRouter.resetMirrorWriter(transferId) }
                .onFailure { Log.w(TAG, "Mirror LAN pull reset failed: ${it.message}") }
        }

        override suspend fun onLanPullFailed(transferId: String) {
            runCatching { fileTransferRouter.noteLanPullFailed(transferId) }
                .onFailure { Log.w(TAG, "Mirror LAN fallback failed: ${it.message}") }
        }

        override suspend fun onCallFromPartner(signal: org.json.JSONObject) {
            runCatching { callManager.onMirrorCall(signal) }
                .onFailure { Log.w(TAG, "Mirror call signal failed: ${it.message}") }
        }

        override suspend fun onContactFromPartner(signal: org.json.JSONObject) {
            runCatching { contactRepository.applyMirrorContact(signal) }
                .onFailure { Log.w(TAG, "Mirror contact failed: ${it.message}") }
        }

        override suspend fun onTypingFromPartner(peerId: String, typing: Boolean) {
            // р235: индикатор «печатает…» - мимолётное состояние в памяти.
            if (typing) {
                com.vladimir.messenger.data.typing.TypingPeer.peerTyping(peerId)
            } else {
                com.vladimir.messenger.data.typing.TypingPeer.peerStopped(peerId)
            }
        }

        override suspend fun onDraftFromPartner(key: String, text: String) {
            // р236: черновик с партнёрского устройства - под тот же ключ.
            runCatching { com.vladimir.messenger.data.draft.DraftStore.save(key, text) }
                .onFailure { Log.w(TAG, "Mirror draft failed: ${it.message}") }
        }

        override suspend fun onPinFromPartner(messageId: String, pinned: Boolean) {
            // Личные закрепы сообщений и публикаций канала должны совпадать
            // на обоих устройствах человека; групповые закрепы идут GroupWire.
            runCatching { chatRepository.setMessagePinned(messageId, pinned) }
                .onFailure { Log.w(TAG, "Mirror pin failed: ${it.message}") }
                .onSuccess { result ->
                    when (result) {
                        com.vladimir.messenger.data.local.dao.MessagePinMutation.LIMIT_REACHED ->
                            Log.w(TAG, "Mirror pin ignored: local pin limit reached")
                        com.vladimir.messenger.data.local.dao.MessagePinMutation.SCOPE_CONFLICT ->
                            Log.w(TAG, "Mirror pin ignored: target belongs to another pin scope")
                        else -> Unit
                    }
                }
        }

        override suspend fun onChatFlagsFromPartner(
            kind: String,
            itemId: String,
            archived: Boolean,
            mutedUntilMs: Long,
        ) {
            runCatching {
                if (kind == com.vladimir.messenger.data.repository.ChatRepository.CHAT_FLAG_GROUP) {
                    groupRepository.applyMirrorChatFlags(itemId, archived, mutedUntilMs)
                } else {
                    chatRepository.applyMirrorChatFlags(itemId, archived, mutedUntilMs)
                }
            }.onFailure { Log.w(TAG, "Mirror chat flags failed: ${it.message}") }
        }

        override suspend fun onDeleteForMeFromPartner(
            peerId: String,
            groupId: String,
            messageId: String,
        ) {
            runCatching { messageDeletion.applyMirrorDeleteForMe(peerId, groupId, messageId) }
                .onFailure { Log.w(TAG, "Mirror delete-for-me failed: ${it.message}") }
        }

        override suspend fun onInboxPinFromPartner(
            kind: String,
            itemId: String,
            pinned: Boolean,
            pinnedAtMs: Long,
        ) {
            val pinKind = com.vladimir.messenger.data.local.dao.InboxPinKind.fromWireValue(kind)
                ?: return
            val result = when (pinKind) {
                com.vladimir.messenger.data.local.dao.InboxPinKind.PERSONAL ->
                    inboxPinDao.setPersonalPinned(itemId, pinned, pinnedAtMs)
                com.vladimir.messenger.data.local.dao.InboxPinKind.GROUP ->
                    inboxPinDao.setGroupPinned(itemId, pinned, pinnedAtMs)
            }
            when (result) {
                com.vladimir.messenger.data.local.dao.InboxPinMutation.LIMIT_REACHED ->
                    Log.w(TAG, "Mirror inbox pin ignored: local ten-pin limit reached")
                com.vladimir.messenger.data.local.dao.InboxPinMutation.NOT_FOUND ->
                    Log.w(TAG, "Mirror inbox pin ignored: local conversation not found")
                else -> Unit
            }
        }

        override suspend fun mirrorContactSnapshot(): List<org.json.JSONObject> =
            contactRepository.mirrorContactSnapshot()

        override suspend fun mirrorFileSnapshot(): List<org.json.JSONObject> =
            fileTransferRouter.mirrorCompletedFileSnapshot()

        override suspend fun mirrorAccountSnapshot(): List<org.json.JSONObject> =
            com.vladimir.messenger.data.mirror.MirrorAccountSnapshot.exportRows(applicationContext, appDatabase)

        override suspend fun applyAccountSnapshot(rows: List<org.json.JSONObject>): Int =
            com.vladimir.messenger.data.mirror.MirrorAccountSnapshot.applyRows(applicationContext, appDatabase, rows)

        override suspend fun onOutgoingFromPartner(row: com.vladimir.messenger.data.mirror.MirrorRow) {
            // id чатов на устройствах разные - ищем чат по узлу получателя.
            val chat = chatRepository.getChatByContactId(row.recipientId)
                ?: chatRepository.getOrCreateChat(
                    row.recipientId,
                    NodeIds.autoName(row.recipientId),
                )
            if (row.manualRetry) {
                chatRepository.retryOutgoingFromMirror(
                    row = row,
                    localChatId = chat.id,
                )
            } else {
                chatRepository.sendMessage(
                    chatId = chat.id,
                    recipientId = row.recipientId,
                    content = row.content,
                    fixedMessageId = row.id,
                    fromMirror = true,
                    reply = row.replyToId.takeIf { it.isNotBlank() }?.let {
                        com.vladimir.messenger.data.reply.DirectReplyWire.Target(
                            messageId = it,
                            author = row.replyAuthor,
                            text = row.replyText,
                        )
                    },
                )
            }
        }

        override fun onPromote() {
            val prefs = applicationContext.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("mirror_defer_engine", false)
                .putLong("mirror_role_switch_at", System.currentTimeMillis())
                .apply()
            Log.i(TAG, "Mirror: повышение - перезапуск с движком")
            restartForMirrorRole()
        }

        override fun onDeferToShadow() {
            val prefs = applicationContext.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("mirror_defer_engine", true)
                .putLong("mirror_role_switch_at", System.currentTimeMillis())
                .apply()
            Log.i(TAG, "Mirror: партнёр старше - ухожу в зеркало")
            restartForMirrorRole()
        }

        /**
         * р231: партнёр-тень просит движок, чтобы отправить свой файл (у тени
         * своей сетевой сессии нет). Уступаем, если сами сейчас ничего не
         * отдаём; иначе отказ - тень повторит просьбу позже.
         */
        override fun onEngineClaimed(): Boolean {
            if (fileTransferRouter.hasRecentTransferActivity(System.currentTimeMillis())) {
                Log.i(TAG, "Mirror claim: отказ, передача (отдача или приём) ещё идёт")
                return false
            }
            if (callManager.isBusy()) {
                Log.i(TAG, "Mirror claim: отказ, идёт звонок")
                return false
            }
            val prefs = applicationContext.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("mirror_defer_engine", true)
                .putLong("mirror_role_switch_at", System.currentTimeMillis())
                .apply()
            Log.i(TAG, "Mirror claim: уступаю движок партнёру (его исходящий файл)")
            restartForMirrorRole()
            return true
        }
    }

    /**
     * р227: применить служебный конверт, пришедший от партнёра-зеркала.
     *
     * Разбор - теми же репозиториями, что и на активном устройстве; сети у
     * тени нет, поэтому ответные отправки (подтверждения) она не делает - их
     * сделает активный. Идентификаторы чатов у устройств СВОИ, поэтому личный
     * чат находим по отправителю, а не по числу из конверта.
     */
    /**
     * р245: насос медиа. Только для устройства-зеркала: у него нет сети, и
     * байты гифок взять ему неоткуда, кроме активного партнёра. Ищем ссылки в
     * свежей переписке, которых нет в библиотеке, и просим по одной за заход
     * (канал зеркала общий с сообщениями - не забиваем его).
     */
    private suspend fun pumpMirrorMedia() {
        if (com.vladimir.messenger.data.mirror.MirrorHub.isActiveDevice()) {
            // Активный: байты гифок могли прийти от тени (push) - объявляем
            // каталог рою, чтобы собеседник знал, у кого их забрать.
            runCatching { gifLibraryBootstrap() }
                .onFailure { Log.w(TAG, "gif catalog announce failed: ${it.message}") }
            return
        }
        val app = applicationContext
        val contents = chatRepository.recentMessageContents(24)
        var asked = 0
        for (content in contents) {
            if (asked >= 2) break
            if (!com.vladimir.messenger.data.gif.GifLibrary.isGifRef(content)) continue
            val sha = com.vladimir.messenger.data.gif.GifLibrary.gifRefSha(content) ?: continue
            if (com.vladimir.messenger.data.gif.GifLibrary.gifFile(app, sha) != null) continue
            com.vladimir.messenger.data.mirror.MirrorHub.requestGifBytes(sha)
            asked++
        }
        if (asked > 0) Log.i(TAG, "media pump: запрошено $asked гифк(а) у активного")
    }

    /** р249: когда в последний раз просили у партнёра байты этой передачи. */
    private val mirrorFileAskedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * р249: насос байтов файлов. Только для устройства-зеркала: своей сети у
     * него нет, поэтому докачать принятый (или подготовленный) файл можно
     * только у активного партнёра.
     *
     * Закрывает разрыв «карточка приехала, а байты попросить было некого»:
     * раньше просьба уходила единственный раз - в ответ на кадр «файл принят».
     * Если второе устройство было офлайн или канал поднялся позже, карточка
     * оставалась пустой навсегда. Теперь насос добирает такие файлы сам, по
     * одной передаче за заход: канал зеркала общий с сообщениями.
     */
    private suspend fun pumpMirrorFiles() {
        if (com.vladimir.messenger.data.mirror.MirrorHub.isActiveDevice()) return
        val now = System.currentTimeMillis()
        val auto = runCatching { fileTransferRouter.transfersMissingBytes(3) }.getOrDefault(emptyList())
        // р249: большой файл - только тот, который человек уже открывал здесь
        // или который не удалось забрать по локальной сети. Автоматом сотни
        // мегабайт в фоне не качаем.
        val wanted = if (auto.isEmpty()) {
            runCatching { fileTransferRouter.transfersMissingBigBytes(1) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        for (row in auto + wanted) {
            val last = mirrorFileAskedAt[row.transferId] ?: 0L
            if (now - last < MIRROR_FILE_RETRY_MS) continue
            mirrorFileAskedAt[row.transferId] = now
            val offset = fileTransferRouter.mirrorReceivedBytesDurable(row.transferId)
            val big = row.totalBytes > com.vladimir.messenger.data.mirror.MirrorChannel.FILE_MIRROR_MAX_BYTES
            com.vladimir.messenger.data.mirror.MirrorHub.requestFileBytes(
                row.transferId,
                row.displayName,
                row.totalBytes,
                offset = offset,
                big = big,
                sha256 = row.fileSha256,
            )
            Log.i(TAG, "file bytes pump: прошу ${row.displayName} (${row.totalBytes} Б, с $offset)")
            break
        }
    }

    private suspend fun applyMirrorEnvelope(
        senderId: String,
        chatId: String,
        messageId: String,
        text: String,
    ): Boolean {
        if (deferredDelivery.routeIncoming(senderId, chatId, messageId, text, authenticated = true, fromMirror = true)) return true
        // р244: подтверждение доставки пришло с партнёрского устройства -
        // помечаем им же и здешнюю строку (переписка общая на обеих).
        if (DeliveryAckWire.isPacket(text)) {
            val ackedId = DeliveryAckWire.messageId(text)
            if (ackedId != null) {
                runCatching { markMessageDelivered(ackedId) }
                    .onSuccess { changed ->
                        Log.i(TAG, "📬 delivery ACK via mirror: msgId=$ackedId changed=$changed")
                    }
            } else {
                Log.w(TAG, "malformed delivery ACK from mirror; dropped")
            }
            return true
        }
        if (!com.vladimir.messenger.data.mirror.MirrorEnvelopes.isSafe(text)) return false
        // Ранг собеседника едет в обёртке «печатает…» (см. RankWire.WRAPPER).
        // Разбираем его РАНЬШЕ индикатора набора: иначе конверт был бы прочитан
        // как «партнёр перестал печатать» и молча пропал бы вместе с рангом.
        if (routeRankOrHeartPacket(heartGameNetwork, peerRankRouter, senderId, text)) {
            Log.i(TAG, "Mirror envelope: ранг или сердечки собеседника применены")
            return true
        }
        // р239: «печатает…» от партнёра - показать индикатор и не сохранять.
        val mirrorTyping = com.vladimir.messenger.data.typing.TypingWire.parse(text)
        if (mirrorTyping != null) {
            if (mirrorTyping) {
                com.vladimir.messenger.data.typing.TypingPeer.peerTyping(senderId)
            } else {
                com.vladimir.messenger.data.typing.TypingPeer.peerStopped(senderId)
            }
            return true
        }
        if (com.vladimir.messenger.data.reply.DirectReplyWire.isPacket(text)) {
            com.vladimir.messenger.data.reply.DirectReplyWire.parse(text)?.let { packet ->
                chatRepository.applyDirectReply(packet)
            }
            return true
        }
        // Групповой конверт: у группы один и тот же id на всех устройствах,
        // поэтому разбираем его тем же путём, что и обычное входящее.
        if (com.vladimir.messenger.data.group.GroupWire.isGroupPacket(text)) {
            val applied = routePacket(TAG, "group") { groupRouter.routeIncoming(senderId, chatId, messageId, text) }
            if (applied) Log.i(TAG, "Mirror envelope: групповой конверт применён")
            return applied
        }
        val localChatId = runCatching { chatRepository.getChatByContactId(senderId)?.id }
            .getOrNull() ?: chatId
        if (routePacket(TAG, "reaction") { reactionRepository.routeIncoming(senderId, text) }) {
            Log.i(TAG, "Mirror envelope: reaction from ${senderId.takeLast(8)} applied")
            return true
        }
        if (routePacket(TAG, "repost") { repostRepository.routeIncoming(senderId, text) }) {
            Log.i(TAG, "Mirror envelope: repost from ${senderId.takeLast(8)} applied")
            return true
        }
        if (routePacket(TAG, "deletion") { messageDeletion.routeIncoming(senderId, text) }) {
            Log.i(TAG, "Mirror envelope: deletion from ${senderId.takeLast(8)} applied")
            return true
        }
        if (routePacket(TAG, "edit") { messageEdit.routeIncoming(senderId, text) }) {
            Log.i(TAG, "Mirror envelope: edit from ${senderId.takeLast(8)} applied")
            return true
        }
        if (routePacket(TAG, "postViews") { postViews.routeIncoming(senderId, text) }) {
            return true
        }
        if (routePacket(TAG, "hearts") { hearts.routeIncoming(senderId, text) }) {
            return true
        }
        if (routePacket(TAG, "readReceipts") { readReceipts.routeIncoming(senderId, text) }) {
            return true
        }
        if (com.vladimir.messenger.data.gif.GifLibrary.isGifRef(text)) {
            val refSha = com.vladimir.messenger.data.gif.GifLibrary.gifRefSha(text)
            if (refSha != null && localChatId.isNotBlank()) {
                runCatching {
                    chatRepository.insertReceivedGifRefMessage(
                        chatId = localChatId,
                        senderId = senderId,
                        messageId = messageId.ifBlank { java.util.UUID.randomUUID().toString() },
                        sha256 = refSha,
                        timestamp = System.currentTimeMillis(),
                    )
                }.onFailure { Log.w(TAG, "Mirror gif ref insert failed: ${it.message}") }
                // Байты гифки дотянет следующий этап (прямой канал устройств);
                // пока карточка живёт в переписке и докачается с хранителей,
                // когда это устройство снова станет активным.
            }
            return true
        }
        return false
    }

    /**
     * р228: на партнёрском устройстве прочитали переписку - снять
     * непрочитанное здесь. Пишем прямо в базу (не через репозиторий):
     * применение кадра не должно повторно его рассылать.
     */
    private suspend fun mirrorApplyRead(peerId: String, groupId: String, topicId: String) {
        runCatching {
            if (groupId.isNotBlank()) {
                if (topicId.isNotBlank()) groupDao.markTopicRead(topicId)
                groupDao.markGroupRead(groupId)
                Log.i(TAG, "Mirror read: непрочитанное группы снято по партнёру")
                return@runCatching
            }
            if (peerId.isBlank()) return@runCatching
            val chat = chatRepository.getChatByContactId(peerId) ?: return@runCatching
            chatRepository.markAsRead(chat.id)
            Log.i(TAG, "Mirror read: непрочитанное чата снято по партнёру")
        }.onFailure { Log.w(TAG, "Mirror read apply failed: ${it.message}") }
    }

    /**
     * р228: действие сделано на теневом устройстве - привести СВОЮ базу в то
     * же состояние, чтобы устройства не разошлись. Ни сети, ни ответных
     * отправок здесь нет: конверт в сеть уйдёт отдельно.
     */
    private suspend fun mirrorApplyActionLocally(peerId: String, groupId: String, text: String) {
        // Разосланный с тени ранг («печатает…» с полезной нагрузкой APURANK1):
        // это наше собственное звание, локально записывать нечего - пакет просто
        // уходит собеседникам. Проверяем ДО «печатает…»: иначе он попал бы в
        // чужую ветку и в журнале значился бы не тем, чем является.
        if (com.vladimir.messenger.data.rank.RankWire.isRankPacket(text)) {
            Log.i(TAG, "Mirror action: ранг собеседникам, локально применять нечего")
            return
        }
        // р235: «печатает…» с тени - локально показывать нечего (это наше
        // собственное состояние), пакет просто уходит собеседнику.
        if (com.vladimir.messenger.data.typing.TypingWire.isTypingPacket(text)) return
        // Групповой конверт (сообщение, пост, комментарий, тема, закреп,
        // состав): применяем тем же разбором, что и входящий, но от своего
        // имени - на партнёрском устройстве это «моё» сообщение.
        if (groupId.isNotBlank() &&
            com.vladimir.messenger.data.group.GroupWire.isGroupPacket(text)
        ) {
            val me = RustBridge.nodeId().orEmpty()
            runCatching { groupRouter.routeIncoming(me, groupId, "", text) }
                .onFailure { Log.w(TAG, "Mirror group packet apply failed: ${it.message}") }
            Log.i(TAG, "Mirror action: групповой конверт применён локально")
            return
        }
        if (routePacket(TAG, "reaction.mirror") { reactionRepository.applyMirrorOutgoing(peerId, groupId, text) }) {
            Log.i(TAG, "Mirror action: реакция с тени применена локально")
            return
        }
        if (routePacket(TAG, "repost.mirror") { repostRepository.applyMirrorOutgoing(peerId, groupId, text) }) {
            Log.i(TAG, "Mirror action: репост с тени применён локально")
            return
        }
        if (peerId.isNotBlank() &&
            routePacket(TAG, "deletion.mirror") { messageDeletion.applyMirrorOutgoing(peerId, text) }
        ) {
            Log.i(TAG, "Mirror action: удаление с тени применено локально")
            return
        }
        if (peerId.isNotBlank() &&
            routePacket(TAG, "edit.mirror") { messageEdit.applyMirrorOutgoing(peerId, text) }
        ) {
            Log.i(TAG, "Mirror action: правка с тени применена локально")
            return
        }
        if (routePacket(TAG, "postViews.mirror") { postViews.applyMirrorOutgoing(text) }) return
        if (routePacket(TAG, "hearts.mirror") { hearts.applyMirrorOutgoing(text) }) return
        if (routePacket(TAG, "readReceipts.mirror") { readReceipts.applyMirrorOutgoing(text) }) return
        Log.w(TAG, "Mirror action: конверт с тени не распознан, только пересылка")
    }

    /**
     * р228: отправить конверт, сделанный на тени, в сеть - единственной
     * сессией активного устройства. Личный чат: собеседнику; групповое
     * действие: участникам группы по бюджету служебного канала, как это
     * делает сам репозиторий на активном устройстве.
     */
    private suspend fun mirrorSendActionToPeers(peerId: String, groupId: String, text: String) {
        if (groupId.isNotBlank()) {
            // Групповое сообщение/пост: рассылка участникам - штатной доставкой
            // группы (она же выбирает получателей и ведёт учёт манифестов).
            if (com.vladimir.messenger.data.group.GroupWire.isGroupPacket(text)) {
                val report = runCatching { groupRepository.fanoutEnvelope(groupId, text) }
                    .getOrNull()
                Log.i(
                    TAG,
                    "Mirror action: групповой конверт разослан " +
                        "${report?.delivered ?: 0}/${report?.attempted ?: 0}",
                )
                return
            }
            val group = groupDao.getGroupById(groupId)
            if (group == null) {
                Log.w(TAG, "Mirror action: группы $groupId нет на этом устройстве")
                return
            }
            val me = RustBridge.nodeId().orEmpty()
            val recipients = runCatching { counters.signalTargets(group, me) }.getOrDefault(emptyList())
            var sent = 0
            for (peer in recipients) {
                if (!swarmBudget.tryAcquire(com.vladimir.messenger.data.swarm.SwarmLane.SIGNAL)) break
                RustBridge.sendMessage(java.util.UUID.randomUUID().toString(), groupId, peer, text)
                sent++
            }
            Log.i(TAG, "Mirror action: групповой конверт ушёл $sent/${recipients.size}")
            return
        }
        if (peerId.isBlank()) return
        val chat = chatRepository.getChatByContactId(peerId)
            ?: chatRepository.getOrCreateChat(peerId, NodeIds.autoName(peerId))
        val sent = RustBridge.sendMessage(java.util.UUID.randomUUID().toString(), chat.id, peerId, text)
        Log.i(TAG, "Mirror action: конверт ушёл собеседнику ${peerId.takeLast(8)} sent=$sent")
    }

    /**
     * Смена роли = штатный перезапуск сервиса (как reconnect): тень поднимет
     * движок, активный - уйдёт в тень. Защита от частых переключений.
     */
    private fun restartForMirrorRole() {
        if (mirrorRestarting.getAndSet(true)) return
        // Отдельный scope: serviceScope кан cancell'ится при stopSelf раньше,
        // чем успеет выполниться перезапуск.
        val restartScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
        )
        restartScope.launch {
            // Короткая пауза: партнёр должен увидеть смену нашего состояния.
            kotlinx.coroutines.delay(1500)
            try {
                stopServiceSafely()
                val intent = android.content.Intent(applicationContext, CoreServerService::class.java)
                applicationContext.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Mirror role restart failed", e)
            } finally {
                mirrorRestarting.set(false)
                restartScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
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

    /** Службу остановили ради перезапуска: «быстрый подъём» не нужен, поднимемся сами. */
    @Volatile
    private var intentionalRestart = false

    private fun reconnectWithBackoff() {
        val delay = minOf(1000L * (1L shl reconnectAttempt.coerceAtMost(6)), maxBackoff)
        reconnectAttempt++
        Log.w(TAG, "Reconnect attempt #$reconnectAttempt in ${delay}ms")
        serviceScope.launch {
            kotlinx.coroutines.delay(delay)
            try {
                // Перезапуск сервиса (onDestroy + onStartCommand)
                intentionalRestart = true
                stopServiceSafely()
                val restartIntent = android.content.Intent(applicationContext, CoreServerService::class.java)
                applicationContext.startForegroundService(restartIntent)
                intentionalRestart = false
                reconnectAttempt = 0
                Log.i(TAG, "Reconnect: service restarted")
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect failed", e)
                reconnectWithBackoff()
            }
        }
    }

    override fun onDestroy() {
        OperationalDiagnostics.tracker.count(OperationalCounter.SERVICE_DESTROYED)
        OperationalDiagnostics.phase(DiagnosticPhase.SERVICE_STOP)
        // Снимаем foreground-статус первым делом, чтобы система не считала
        // сервис зависшим, пока мы закрываем реле и Rust-ядро.
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground in onDestroy failed", e)
        }
        // Cancel every service-owned polling loop/collector. Repeated start intents are
        // idempotent, but a real service destruction must release this entire coroutine tree.
        serviceJob.cancel()
        telegramRelay?.stop()
        cloudflareRelay?.stop()
        com.vladimir.messenger.data.mirror.MirrorHub.close()
        mirror = null
        Log.i(TAG, "CoreServerService destroyed")
        TransferDiagnostics.record("core", "служба ядра остановлена")
        eventPollingJob?.cancel()
        filePumpJob?.cancel()
        networkMonitor?.stop()
        RustBridge.shutdownAsync()
        try {
            multicastLock?.release()
            Log.i(TAG, "MulticastLock released")
        } catch (_: Exception) { /* освобождение блокировки в onDestroy: повторять нечего */ }
        OperationalDiagnostics.wake.release(SystemClock.elapsedRealtime())
        wakeLock?.release()
        // «Убили» — быстрый подъём; осознанный перезапуск (reconnect) сюда не относится.
        ServiceLifecycleHooks.onDestroyed(applicationContext, planned = intentionalRestart)
        super.onDestroy()
    }

    /** Человек смахнул приложение из задач: подъём через две минуты, как в Telegram. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        ServiceLifecycleHooks.onTaskRemoved(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * р249: уведомления этой переписки выключены - личный чат это или группа.
     *
     * Уведомление не создаётся вообще: пауза скрывает сообщение из шторки.
     * Срок живёт в базе, поэтому пауза снимается сама, без таймера.
     */
    private suspend fun isMutedForNotifications(chatId: String): Boolean {
        if (chatId.isBlank()) return false
        if (runCatching { chatRepository.isChatMuted(chatId) }.getOrDefault(false)) return true
        return runCatching { groupRepository.isGroupMuted(chatId) }.getOrDefault(false)
    }

    private fun startEventPolling() {
        
        serviceScope.launch {
            while (isActive) {
                try { deferredDelivery.pumpPending() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { Log.w(TAG, "Deferred delivery assist failed", error) }
                delay(30_000L)
            }
        }

        // Observer for incoming messages → show notifications
        serviceScope.launch {
            Log.i(TAG, "Starting message observer for notifications")
            chatRepository.observeRecentIncomingMessages()
                .collect { messages ->
                    Log.d(TAG, "Message observer received ${messages.size} recent incoming messages")
                    // A rolling two-minute window catches delayed messages after process restart
                    // without accumulating every message seen since this foreground service began.
                    val cutoff = System.currentTimeMillis() - 120_000L
                    notifiedMessageIds.entries.forEach { (id, timestamp) ->
                        if (timestamp < cutoff) notifiedMessageIds.remove(id, timestamp)
                    }
                    val recentIncoming = messages.filter {
                        !it.isFromMe &&
                            !com.vladimir.messenger.data.schedule.DeferredInboxStore.owns(applicationContext, it.id) &&
                            it.timestamp >= cutoff &&
                            notifiedMessageIds.putIfAbsent(it.id, it.timestamp) == null &&
                            !com.vladimir.messenger.util.InlineImage.isPart(it.content) &&
                            // Раунд 156: конверты роя (стикеры/миниатюры) -
                            // служебные, не звоним (владелец).
                            !com.vladimir.messenger.util.ChatPreviews.isServiceEnvelope(it.content)
                    }
                    for (msg in recentIncoming) {
                        try {
                            // SOS из группы: тревога не знает пауз звука -
                            // активно оповещаем отдельным громким путём,
                            // даже если группа у получателя без звука.
                            if (com.vladimir.messenger.util.SosMessage.isSos(msg.content)) {
                                runCatching {
                                    notificationHelper.showSosNotification(
                                        msg.chatId, msg.senderId,
                                        msg.topicId?.takeIf { it.isNotBlank() },
                                        messageId = msg.id,
                                    )
                                }.onFailure { Log.w(TAG, "SOS notification failed: ${it.message}") }
                                continue
                            }
                            // р249: «без звука» - ни уведомления, ни звука.
                            // Проверяем и личный чат, и группу: у сообщения
                            // один chatId, а чем он окажется - решаем по базе
                            // (два дешёвых запроса по первичному ключу).
                            if (isMutedForNotifications(msg.chatId)) {
                                Log.i(TAG, "notification suppressed: без звука ${msg.chatId.take(12)}")
                                continue
                            }
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
                                // Раунд 156: стикер-конверты и прочие служебные
                                // строки - человеческими подписями.
                                com.vladimir.messenger.util.ChatPreviews
                                    .human(
                                        com.vladimir.messenger.util.InlineImage
                                            .stripImage(msg.content).ifBlank { "Фото" }
                                    )
                                    ?.take(200) ?: "Фото"
                            }
                            notificationHelper.showMessageNotification(
                                msg.chatId, msg.senderId, text, true, topicId,
                                quiet = com.vladimir.messenger.util.QuietMessage.isQuiet(msg.content),
                                messageId = msg.id,
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to show notification: ${e.message}")
                        }
                    }
                }
        }

        eventPollingJob = serviceScope.launch {
            Log.i(TAG, "Event polling started")
            var lastStatusRefreshAtMs = 0L
            while (isActive) {
                // K3: события файловых кусков приходят пучками (пока файл
                // льётся стримами). Пока очередь не пуста - работаем
                // коротким шагом, иначе куски ждали бы полные POLL_INTERVAL_MS
                // и ACK-окно отправителя почти не двигалось. В простое шаг
                // прежний (POLL_INTERVAL_MS), пустые опросы дёшевы.
                val busy = try {
                    RuntimeDiagnostics.measure(DiagnosticOperation.CORE_EVENTS) {
                        val events = RustBridge.drainEvents()
                        if (events.isNotEmpty()) DeliveryCadence.touch()
                        events.forEach { event -> handleEvent(event) }
                        RustBridge.pendingEvents() > 0L || events.size >= BUSY_BATCH
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "Event polling error", ex)
                    false
                }
                val statusNowMs = android.os.SystemClock.elapsedRealtime()
                if (statusNowMs - lastStatusRefreshAtMs >= POLL_INTERVAL_MS) {
                    lastStatusRefreshAtMs = statusNowMs
                    // Метка жизни: по ней сторож понимает, что нас убили без onDestroy.
                    runCatching { DeliveryAssurance.markAlive(applicationContext) }
                    try {
                        val status = RustBridge.networkStatus()
                        val peers = RustBridge.connectedPeers()
                        updateNotification(notificationText(status, peers))
                    } catch (ex: Exception) {
                        Log.e(TAG, "Status polling error", ex)
                    }
                }
                // Пока экран смотрят - четверть секунды, иначе прежние пять.
                delay(DeliveryCadence.pollDelayMs(busy))
            }
        }

        // F3: periodic safety-net pump — resumes PREPARED/TRANSFERRING transfers after restart
        // and keeps multi-day offline retries alive. Live ACKs open the next bounded window
        // immediately; this cadence must not throttle an active transfer.
        filePumpJob = serviceScope.launch {
            kotlinx.coroutines.delay(INITIAL_FILE_PUMP_DELAY_MS)
            chatOutboxPump.start(serviceScope)
            while (isActive) {
                try {
                    RuntimeDiagnostics.measure(DiagnosticOperation.FILE_PUMP) { fileTransferRouter.pumpOutgoing() }
                } catch (ex: Exception) {
                    Log.w(TAG, "File pump error: ${ex.message}")
                }
                try {
                    RuntimeDiagnostics.measure(DiagnosticOperation.STORY_PUMP) { chatRepository.pumpStoryOutbox() }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { TransferDiagnostics.recordWarning("story", "цикл досылки истории не завершён; повтор при следующем цикле") }
                // Отклики на истории досылаются своим насосом: значок или
                // комментарий зрителя уходит автору, автор рассылает сводку
                // остальным зрителям. Плановый цикл - страховка; основной
                // толчок репозиторий делает сам, сразу после действия человека.
                try {
                    RuntimeDiagnostics.measure(DiagnosticOperation.STORY_PUMP) { storyFeedback.pump() }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { TransferDiagnostics.recordWarning("story", "цикл досылки откликов истории не завершён; повтор при следующем цикле") }
                // Файлы группы роем (этап 9): повторить свои просьбы, отдать
                // из очереди, освободить куски отданного.
                try {
                    RuntimeDiagnostics.measure(DiagnosticOperation.GROUP_FILE_PUMP) { groupFiles.pump() }
                } catch (ex: Exception) {
                    Log.w(TAG, "Group file pump error: ${ex.message}")
                }
                // Рой APK (docs/UPDATE_SEEDING.md): повторить просьбы о
                // новой версии, обновить карточку «Обновления», объявить
                // о своей раздаче.
                try {
                    RuntimeDiagnostics.measure(DiagnosticOperation.APK_PUMP) { apkSeeder.pump() }
                } catch (ex: Exception) {
                    Log.w(TAG, "Apk seeder pump error: ${ex.message}")
                }
                // Раунд 137: недоставленные «удалить у всех» - личные (до ack)
                // и групповые (эпидемией через помпу репозитория).
                try {
                    RuntimeDiagnostics.measure(DiagnosticOperation.DIRECT_DELETE_PUMP) { messageDeletion.pump() }
                } catch (ex: Exception) {
                    Log.w(TAG, "Deletion pump error: ${ex.message}")
                }
                try {
                    RuntimeDiagnostics.measure(DiagnosticOperation.GROUP_DELETE_PUMP) { groupRepository.pumpDeletions() }
                } catch (ex: Exception) {
                    Log.w(TAG, "Group deletion pump error: ${ex.message}")
                }
                // Заявки на вступление: хранятся на телефоне и досылаются, пока владелец не ответит.
                try {
                    groupRepository.pumpJoinRequests()
                } catch (ex: Exception) {
                    Log.w(TAG, "Join request pump error: ${ex.message}")
                }
                // Раунд 179: недоставленные сообщения (гифки-ссылки и текст
                // QUEUED_OFFLINE) досылает свой насос - коротким шагом, пока
                // есть что досылать (см. ChatOutboxPump; раньше это был один
                // круг из трёх по двадцать секунд, то есть раз в минуту).
                delay(FILE_PUMP_INTERVAL_MS)
            }
        }
    }

    private suspend fun handleEvent(event: CoreEventFfi) {
        Log.d(TAG, "Event: ${event.eventType}")
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

                // Отправитель обязан быть узлом (pk_…): ядро не проверяет первое поле, и
                // строка без конверта (`APUCALL1|ab|…`) дала бы контакт-призрак «Contact
                // APUCALL1» с обрывком пакета вместо переписки (владелец, скрин 2026-09-13).
                if (!NodeIds.isNodeId(senderId)) {
                    Log.w(
                        TAG,
                        "Dropped message with non-node sender '" + senderId.take(24) +
                            "' msgId=" + messageId.take(24) + " text=" + rawText.take(24),
                    )
                    // Диагностика (2026-10-07): такой пакет в переписку не попадёт
                    // НИКОГДА, и без счётчика он выглядел бы как «сообщение не
                    // пришло» без причины. Число видно в разделе [сообщения].
                    com.vladimir.messenger.data.diagnostics.TransferDiagnostics.noteBadSender()
                    return
                }

                // Pairing packets are intentionally raw and use the account routing node. Two
                // phones of one account therefore look like "from me" here. Route this before
                // the self-node filter; otherwise the primary silently discards the join request
                // and the joiner can only sit on the six-digit code screen forever. The controller
                // still authenticates the packet and ignores an own-device echo by device_id.
                if (!SealedWire.isSealed(rawText) &&
                    com.vladimir.messenger.data.pairing.DevicePairingWire.isPairingText(rawText)
                ) {
                    com.vladimir.messenger.data.pairing.DevicePairingController
                        .routeIncoming(applicationContext, senderId, rawText)
                    return
                }

                // ACK is intentionally a raw control packet so a receiver that
                // learned the sender binding from the just-opened message can
                // return it even before HELLO. Handle it before the self-node
                // filter: mirror devices may share the routing node id, and the
                // old order turned an ACK into an ACK-of-ACK and dropped it.
                if (!SealedWire.isSealed(rawText) && DeliveryAckWire.isPacket(rawText)) {
                    val ackedId = DeliveryAckWire.messageId(rawText)
                    if (ackedId != null) {
                        mirror?.publishEnvelope(senderId, chatId, messageId, rawText)
                        serviceScope.launch {
                            try {
                                val changed = markMessageDelivered(ackedId)
                                Log.i(TAG, "📬 raw delivery ACK: msgId=$ackedId changed=$changed")
                            } catch (error: Exception) {
                                Log.w(TAG, "raw delivery ACK apply failed: ${error.message}")
                            }
                        }
                    } else {
                        Log.w(TAG, "malformed raw delivery ACK; dropped")
                    }
                    return
                }

                // р239: сообщение «от себя» (в контакты попал собственный узел, «Мой QR»
                // можно отсканировать самому). Эхо входящим не показываем и не звоним:
                // владелец, скрин 30.09 16:40. Своя строка уже есть как отправленная.
                // р243: адрес из кэша зеркала (обновляется в цикле) - вызов в
                // ядро на каждом входящем тормозил разбор очереди.
                val myNodeId = com.vladimir.messenger.data.mirror.MirrorHub.nodeIdCached()
                if (myNodeId.isNotBlank() && senderId == myNodeId) {
                    Log.w(TAG, "self-addressed packet ignored msgId=$messageId text=" + rawText.take(24))
                    // р242: считаем такие пакеты для диагностики - по счётчику
                    // видно, что «собеседник» это собственный узел.
                    com.vladimir.messenger.data.mirror.MirrorHub.noteSelfPacket()
                    runCatching { RustBridge.sendDeliveryAck(messageId, senderId) }
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
                        // Владелец 2026-10-07: после обмена QR в сети летят СТАРЫЕ копии
                        // со прежним ключом. Если от собеседника только что ЧТО-ТО открылось,
                        // ключ рабочий, а этот конверт — остаток в пути: не тревожим.
                        val leftover = known &&
                            com.vladimir.messenger.data.security.KeyDesyncNotice
                                .isWorkingRecently(applicationContext, senderId)
                        Log.w(
                            TAG,
                            "Sealed envelope NOT opened msgId=$messageId " +
                                "sender=${senderId.takeLast(8)} senderKeyKnown=$known " +
                                "leftover=$leftover bytes=${rawText.length} - relaying " +
                                "for another node, or our key does not match",
                        )
                        // «Сообщение не пришло» чаще всего выглядит именно так:
                        // конверт не вскрылся, и раньше об этом знал только
                        // системный журнал. Теперь это предупреждение в журнале
                        // отчёта и число в разделе [сообщения].
                        com.vladimir.messenger.data.diagnostics.TransferDiagnostics
                            .noteSealedNotOpened(
                                senderKeyKnown = known,
                                staleKeyCopy = leftover,
                                localIdentityUnavailable = !localCryptoReadyAtStartup,
                            )
                        if (known && !leftover && localCryptoReadyAtStartup) {
                            // An unopened envelope cannot prove the recipient or a stale peer
                            // key. Offer a neutral warning only if our own keys were available
                            // at boot. Re-announcing our signed PUBLIC binding is safe; never
                            // reset a key or tell the user to rescan QR based on this alone.
                            com.vladimir.messenger.data.security.KeyDesyncNotice
                                .note(applicationContext, senderId)
                            runCatching { fileTransferRouter.requestExchangeBinding(senderId) }
                                .onFailure { Log.w(TAG, "key re-announce failed: ${it.message}") }
                        }
                        return
                    }
                    // Переписка с ним снова открывается — подсказка «не
                    // открывается» больше не нужна и не должна висеть сутками.
                    com.vladimir.messenger.data.security.KeyDesyncNotice
                        .opened(applicationContext, senderId)
                    try {
                        rememberOpenedMessageBinding(senderId, rawText)
                    } catch (error: Exception) {
                        Log.w(TAG, "opened message binding learn failed: ${error.message}")
                    }
                    opened
                } else {
                    rawText
                }

                Log.i(TAG, "Message from $senderId in chat $chatId (sealed=$sealed)")

                // P3 device pairing: handled before any chat logic, never shown as a message.
                if (com.vladimir.messenger.data.pairing.DevicePairingWire.isPacket(text)) {
                    com.vladimir.messenger.data.pairing.DevicePairingController
                        .routeIncoming(applicationContext, senderId, text)
                    return
                }

                if (deferredDelivery.routeIncoming(senderId, chatId, messageId, text, sealed && MessageSealer.openedByExpectedSender(rawText, senderId))) {
                    runCatching { RustBridge.sendDeliveryAck(messageId, senderId) }
                    return
                }

                // р243/р247: подтверждение доставки, пришедшее ПРЯМЫМ каналом.
                // В v140 оно стало идти обычным P2P-пакетом `ack|id`; клиенты
                // до v140 не распознавали его, поэтому свежая версия фильтрует
                if (com.vladimir.messenger.data.reply.DirectReplyWire.isPacket(text)) {
                    com.vladimir.messenger.data.reply.DirectReplyWire.parse(text)?.let { packet ->
                        runCatching { chatRepository.applyDirectReply(packet) }
                            .onFailure { Log.w(TAG, "direct reply apply failed: ${it.message}") }
                    }
                    runCatching { RustBridge.sendDeliveryAck(messageId, senderId) }
                    return
                }

                // Подтверждения ходили только через брокера (MQTT): если он недоступен,
                // сообщение доходило, а вторая галочка не появлялась (жалоба 30.09).
                // Теперь оно может прийти и напрямую (RustBridge.sendDeliveryAck), здесь его
                // узнаём и помечаем своё сообщение доставленным. Строка служебная.
                //
                // Raw ACKs are handled before the self-node filter above; keep this guard
                // for legacy sealed ACKs, whose prefix shows only after MessageSealer.open().
                if (DeliveryAckWire.isPacket(text)) {
                    val ackedId = DeliveryAckWire.messageId(text)
                    if (ackedId != null) {
                        // р244: сначала партнёрскому устройству той же личности -
                        // иначе на нём галочка останется одна, хотя сообщение ушло
                        // именно с него.
                        mirror?.publishEnvelope(senderId, chatId, messageId, text)
                        serviceScope.launch {
                            runCatching {
                                markMessageDelivered(ackedId)
                            }.onSuccess { changed ->
                                Log.i(TAG, "📬 direct delivery ACK: msgId=$ackedId changed=$changed")
                            }
                        }
                    } else {
                        Log.w(TAG, "malformed direct delivery ACK; dropped")
                    }
                    // ACKs are control packets: no chat row, notification, or ACK-of-ACK.
                    return
                }

                // р239 (ВАЖНО): «печатает…» - мимолётный сигнал, в переписке места нет.
                // Разбирается ЗДЕСЬ, до зеркала, авто-создания контакта и сохранения:
                // r235 поставил разбор только в routeIncomingEnvelope (резервный путь CF),
                // и пакет APUTYP1|1 лёг в чат с уведомлением (владелец, скрины 30.09);
                // поэтому рядом есть страж и в ChatRepository.saveIncomingMessage.
                // Ранг и сердечки едут в ТОЙ ЖЕ обёртке APUTYP1 - разбирать их надо РАНЬШЕ
                // индикатора набора, иначе ранг терялся и VIP-значок не появлялся.
                if (routeRankOrHeartPacket(heartGameNetwork, peerRankRouter, senderId, text)) {
                    runCatching { RustBridge.sendDeliveryAck(messageId, senderId) }
                    Log.i(TAG, "Rank/heart envelope handled before typing (main path)")
                    return
                }

                val typingSignal = com.vladimir.messenger.data.typing.TypingWire.parse(text)
                if (typingSignal != null) {
                    if (typingSignal) {
                        com.vladimir.messenger.data.typing.TypingPeer.peerTyping(senderId)
                    } else {
                        com.vladimir.messenger.data.typing.TypingPeer.peerStopped(senderId)
                    }
                    // Второму устройству личности - тем же кадром (индикатор
                    // должен быть на обоих телефонах).
                    com.vladimir.messenger.data.mirror.MirrorHub.publishTyping(senderId, typingSignal)
                    runCatching { RustBridge.sendDeliveryAck(messageId, senderId) }
                    Log.d(TAG, "Typing packet handled (typing=$typingSignal), not saved")
                    return
                }

                try {
                    // р227: служебный конверт (реакция, удаление, просмотр,
                    // сердечко, прочтение, ссылка на гифку) уходит и
                    // партнёру-зеркалу: тот применит его у себя теми же
                    // разборщиками. Тексты сюда не попадают - у них свой путь
                    // (publishIncoming), двойников не будет.
                    mirror?.publishEnvelope(senderId, chatId, messageId, text)

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

                    // Группы: APUGRP1 разбирается здесь же, ДО авто-создания контакта,
                    // иначе групповое событие стало бы личным чатом с отправителем.
                    // Раунд 129: ССЫЛКА на гифку - сам контент («APUGIFREF1|<sha>»): в чат
                    // ложится карточка от лица отправителя, байты телефон тянет с хранителей.
                    // Даже если конверт утечёт запасным путём и сохранится обычным
                    // сообщением - экран чата рисует по нему карточку.
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
                    // Счётчик репостов: APURPOST1 тоже не сообщение, а сигнал.
                    if (repostRepository.routeIncoming(senderId, text)) {
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Repost packet ACK failed: " + e.message)
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

                    // Правка своего сообщения (APUEDIT1): применяется к
                    // строке автора, в переписку служебной строкой не идёт.
                    if (messageEdit.routeIncoming(senderId, text)) {
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Edit packet ACK failed: " + e.message)
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

                    // On-demand short-link mapping from a directly signed peer.
                    // Keep both paths in step so APUSHORT1 never creates a chat.
                    if (linkShortener.routeIncoming(senderId, text)) {
                        runCatching { RustBridge.sendDeliveryAck(messageId, senderId) }
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

                    // Ранг собеседника: APURANK1-конверт «у меня такой ранг».
                    // Разбирается ДО авто-создания контакта: иначе служебная
                    // строка создала бы чат с мусорным текстом у того, кто ещё
                    // не добавил этого человека.
                    if (routeRankOrHeartPacket(heartGameNetwork, peerRankRouter, senderId, text)) {
                        acknowledgeRankPacket(messageId, senderId, "Rank packet")
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
                    // Отказ на МОЁ приглашение: получатель отклонил его
                    // настройками приватности. Показываем заметку в чате с
                    // ним и уведомление; служебная строка в переписку не идёт.
                    if (com.vladimir.messenger.data.invite.InviteRejectWire.isPacket(text)) {
                        runCatching { handleInviteReject(senderId, text) }
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Invite reject ACK failed: " + e.message)
                        }
                        return
                    }

                    // Запрещённое настройками приглашение выбрасываем ДО
                    // создания контакта и чата: ни строки в списке чатов, ни
                    // уведомления, ни непрочитанных. Подтверждение доставки
                    // отправителю уходит как обычно - пусть не повторяет.
                    if (chatRepository.isInviteBlocked(senderId, text)) {
                        try {
                            RustBridge.sendDeliveryAck(messageId, senderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "blocked invite ACK failed: " + e.message)
                        }
                        Log.i(TAG, "invite blocked by privacy setting from $senderId - dropped silently")
                        // У меня тишина, но отправитель должен узнать, что
                        // приглашение не дошло и почему.
                        runCatching { sendInviteReject(senderId, text) }
                        return
                    }
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

                    val incomingSaved = chatRepository.saveIncomingMessage(
                        chatId = chat.id,
                        senderId = senderId,
                        messageId = messageId,
                        content = text,
                        timestamp = timestamp,
                        recipientId = RustBridge.nodeId() ?: "",
                    )
                    Log.i(TAG, "Saved incoming message to chat ${chat.id}")
                    // Диагностика (2026-10-07): встречное направление в отчёте.
                    // Показываем в журнале первое и каждое двадцатое сообщение,
                    // а в разделе [сообщения] - точные числа и свежесть.
                    com.vladimir.messenger.data.diagnostics.TransferDiagnostics.noteMessageIncoming()
                    // р242: для диагностики - когда последний раз что-то приходило.
                    com.vladimir.messenger.data.mirror.MirrorHub.noteIncoming()
                    // р226: мгновенно отразить входящее на зеркале-партнёре.
                    // На второй телефон уходит только сохранённое: выброшенное
                    // приглашение не должно всплыть уведомлением на зеркале.
                    if (incomingSaved) {
                        mirror?.publishIncoming(
                            com.vladimir.messenger.data.mirror.MirrorRow(
                                id = messageId,
                                chatId = chat.id,
                                contactName = chat.contactName,
                                senderId = senderId,
                                content = text,
                                timestamp = timestamp,
                                mine = false,
                                recipientId = RustBridge.nodeId() ?: "",
                                status = "DELIVERED",
                            )
                        )
                    }
                    
                    // Отправить ACK отправителю
                    try {
                        RustBridge.sendDeliveryAck(messageId, senderId)
                        Log.i(TAG, "📤 Delivery ACK sent for msgId=$messageId")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠ ACK send failed: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving incoming message", e)
                    // Без этой записи сбой сохранения выглядел бы как «молча не
                    // пришло»: числа подтверждений растут, а переписка пуста.
                    com.vladimir.messenger.data.diagnostics.TransferDiagnostics.recordFailure(
                        "msg",
                        "входящее сообщение не удалось сохранить (подробности в системном журнале)",
                    )
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
                    // Раунд 180 (аудит-2): регистр «кто онлайн» для файлов -
                    // только на «тяжёлом» пульсе (раз в 30 с), а не на каждый
                    // пульс: при живой сети это были десятки лишних записей
                    // в минуту без какой-нибудь пользы.
                    if (!lightTouch) {
                        runCatching { fileTransferRouter.markOnline(peerId) }
                            .onFailure { Log.w(TAG, "custody presence failed: ${it.message}") }
                    }
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
                        // Точный момент последнего пульса запоминаем при первом
                        // появлении и затем не чаще тяжёлого круга (30 с):
                        // контактам есть что показать после ухода абонента, а
                        // база не получает запись на каждый MQTT-пульс.
                        val becameOnline = onlineMarked.add(peerId)
                        if (becameOnline || !lightTouch) {
                            contactRepository.updatePresence(peerId, true, now)
                        }
                        if (becameOnline) {
                            // И в чаты: шапка лички и точка в списке читают таблицу chats
                            try { chatRepository.updateContactOnlineStatus(peerId, true) } catch (e: Exception) { Log.w(TAG, "presence online failed for ${peerId.takeLast(8)}: ${e.message}") }
                            // Новенький в сети: точечно синхронизируем сердечки (с антиспамом в HeartSyncPolicy).
                            serviceScope.launch {
                                try {
                                    heartGameNetwork.syncWithNewcomer(existing.id)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) { Log.w(TAG, "heart sync for newcomer failed: ${e.message}") }
                            }
                            // Ранг новенького не знаем - а значок VIP у его
                            // имени нужен сразу, не после недельной рассылки.
                            // Просим конверт ранга адресно (ограничитель
                            // внутри: не чаще раза в сутки одному).
                            serviceScope.launch {
                                runCatching {
                                    if (peerRankStore.qualifiedFor(peerId) == null) {
                                        peerRankBroadcaster.requestRank(peerId)
                                    }
                                }
                            }
                            Log.i(TAG, "🟢 ONLINE: $peerName")
                        }
                        if (lightTouch) return  // пульс учли, тяжёлую синхру не дёргаем
                        // Раунд 186 (аудит-6): повторяется на каждый «тяжёлый»
                        // пульс (раз в 30 с на узел) - в релизе не пишем.
                        if (com.vladimir.messenger.BuildConfig.DEBUG) {
                            Log.i(TAG, "👋 PEER DISCOVERED: $peerId ($peerName) — запуск full sync")
                        }
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
                        // Раунд 186 (аудит-6): то же - только для отладки.
                        if (com.vladimir.messenger.BuildConfig.DEBUG) {
                            Log.i(TAG, "✅ Обновлён существующий контакт: $peerName")
                        }
                        
                        // FULL SYNC только для существующих контактов
                        serviceScope.launch {
                            try {
                                chatRepository.retryPendingMessagesForPeer(peerId)
                            } catch (e: Exception) {
                                Log.e(TAG, "Retry failed", e)
                            }
                            // История догоняет собеседника тем же толчком, что и
                            // переписка: пока он в сети, прямой канал работает, а
                            // внешний при живом соседе закрыт. Без этого пакеты
                            // истории ждали своей минуты и окно проходило мимо.
                            try {
                                chatRepository.pumpStoryOutbox(wakePeer = peerId)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                Log.w(TAG, "Story pump after peer discovery failed: ${e.message}")
                            }
                            try {
                                RuntimeDiagnostics.measure(DiagnosticOperation.FILE_PUMP) { fileTransferRouter.pumpOutgoing() }
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
                // Берём время последнего живого пульса до очистки памяти, а
                // не момент, когда транспорт заметил разрыв: так «был(а)»
                // остаётся временем, когда контакт действительно видели.
                val lastSeenAtMs = knownPeers.remove(peerId) ?: System.currentTimeMillis()
                fileTransferRouter.markOffline(peerId)
                try { contactRepository.updatePresence(peerId, false, lastSeenAtMs) } catch (e: Exception) { Log.w(TAG, "presence offline failed: ${e.message}") }
                try { chatRepository.updateContactOnlineStatus(peerId, false) } catch (e: Exception) { Log.w(TAG, "chat online flag failed: ${e.message}") }
            }

            "network_status_changed" -> {
                val status = event.status ?: "unknown"
                Log.i(TAG, "Network status: $status")
                updateNotification(notificationText(status, RustBridge.connectedPeers()))

                if (status == "connected") {
                    // Вне обработчика: вызов ядра блокирующий (до 10 с), и пока
                    // он ждал, входящие сообщения стояли в очереди событий.
                    serviceScope.launch {
                        try {
                            val retried = chatRepository.retryAllPendingMessages()
                            if (retried > 0) {
                                Log.i(TAG, "Retried $retried pending messages after connected")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Retry all pending failed", e)
                        }
                    }
                    serviceScope.launch {
                        try {
                            RuntimeDiagnostics.measure(DiagnosticOperation.FILE_PUMP) { fileTransferRouter.pumpOutgoing() }
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
                        markMessageDelivered(messageId)
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
        return NotificationCompat.Builder(this, MessengerApplication.CHANNEL_SERVICE_ID)
            .setContentTitle("APU")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
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
                    val lastSeenAtMs = knownPeers.remove(pid) ?: now
                    fileTransferRouter.markOffline(pid)
                    // Пропущенный круг - минус к доступности в рейтинге.
                    runCatching {
                        com.vladimir.messenger.data.peer.PeerRatingStore
                            .recordMiss(applicationContext, pid)
                    }
                    Log.i(TAG, "⚫ OFFLINE (TTL): $pid")
                    try { contactRepository.updatePresence(pid, false, lastSeenAtMs) } catch (e: Exception) { Log.w(TAG, "presence TTL-offline failed: ${e.message}") }
                    try { chatRepository.updateContactOnlineStatus(pid, false) } catch (e: Exception) { Log.w(TAG, "chat TTL-offline flag failed: ${e.message}") }
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
        /** р249: как часто тень повторяет просьбу о байтах одного файла. */
        private const val MIRROR_FILE_RETRY_MS = 5L * 60 * 1000

        const val EXTRA_DISPLAY_NAME = "display_name"
        const val POLL_INTERVAL_MS = 5000L
        private const val BUSY_BATCH = 8
        /** Под каким именем нас уже записал справочник. */
        private const val REGISTRY_NAME_KEY = "registry_registered_name"
        /** Когда записал. */
        private const val REGISTRY_AT_KEY = "registry_registered_at"
        /** Повторяем запись не чаще раза в неделю. */
        private const val REGISTRY_REFRESH_MS = 7L * 24 * 60 * 60 * 1000
    }
}
