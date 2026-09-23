package com.vladimir.messenger.data.file

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.repository.ChatRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Facade the service layer talks to: routes incoming packet/handshake texts (before they are
 * stored as chat messages) and drives the sender pump. Everything rides the existing durable
 * Rust transport, so multi-day offline custody and restart resume come from the M8 machinery.
 *
 * File-HELLO handshake: contacts without a pinned exchange binding automatically receive a tiny
 * signed HELLO (durable, deterministic per-pair message ID, throttled); a first-time pin
 * auto-replies, so two phones that never exchanged files still end up with both directions
 * pinned and the first real transfer can go through.
 */
@Singleton
class FileTransferRouter @Inject constructor(
    @ApplicationContext context: Context,
    private val transferDao: FileTransferDao,
    private val peerStore: FileExchangePeerStore,
    private val chatRepository: ChatRepository,
    private val contactDao: com.vladimir.messenger.data.local.dao.ContactDao,
    /**
     * Файлы группы роем (этап 9). Через Provider: рой сам зависит от этого
     * маршрутизатора (готовит и качает передачи), а Dagger кольцо напрямую не
     * собирает. Берётся только при обработке пакетов, не при создании.
     */
    private val groupFiles: javax.inject.Provider<com.vladimir.messenger.data.group.GroupFileSwarm>,
    /**
     * Общие копии файлов групп (K2): манифест `grp_`, один ключ, куски
     * зашифрованы один раз; конверт ключа - каждому просителю свой. Через
     * Provider по той же причине, что и рой.
     */
    private val preparation: javax.inject.Provider<OutgoingFilePreparationService>,
    /**
     * Рой APK (обновление роем, docs/UPDATE_SEEDING.md). Через Provider по
     * той же причине, что и рой файлов: сидер зависит от этого маршрутизатора
     * (готовит и качает передачи), и кольцо Dagger не собирает напрямую.
     * Берётся только при обработке предложений и завершении передачи.
     */
    private val apkSeeder: javax.inject.Provider<com.vladimir.messenger.data.update.ApkSeeder>,
) {
    private val appContext: Context
    private val sender: FileTransferSender
    private val receiver: FileTransferReceiver
    private val custodySender: FileCustodySender
    /** Раздача общих копий файлов групп полосами нескольким просителям (K2). */
    val groupSeeder: GroupFileSeeder
    private val transport: PacketTransport
    /**
     * Кто сейчас в сети - по пульсу присутствия (`peer_discovered`), ведёт
     * CoreServerService через [markOnline]/[markOffline]. Нужно хранителю:
     * отдавать чужой файл получателю имеет смысл, только когда он появился.
     */
    private val onlinePeers = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private lateinit var lanChannel: LanDirectChannel
    /**
     * UDP-каналы файловых пакетов через интернет (мобильная связь,
     * docs/SWARM_MOBILE.md): один канал на собеседника, пробивание NAT,
     * обмен кандидатами сигналами `ufseek`/`ufcand` через брокера.
     */
    private lateinit var udpChannels: FileUdpChannels
    /**
     * Последний прямой режим на собеседника: `lan`|`udp`|`quic`|`broker`.
     * APUF-кадры (K3) идут только по QUIC: если прямой режим — UDP,
     * бинарный путь не включаем (и понижаем уже включённый).
     */
    private val lastDirectMode = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun isPeerViaUdp(nodeId: String): Boolean = lastDirectMode[nodeId] == "udp"

    /**
     * K3: бинарный путь кусков — APUF-кадр уезжает стримом только по прямому
     * QUIC (LAN-TCP остаётся на текстовые фрагменты). Если прямой режим на
     * узле — UDP (мобильная, docs/SWARM_MOBILE.md), передачу понижаем в
     * текстовый путь: те же фрагменты, но по UDP. Ссылка на [sender]
     * разрешается в момент вызова (после init), поэтому это свойство класса,
     * а не локальный лямбда в init.
     */
    private val binarySend: (String, String, Long, Int, Int, ByteArray) -> Boolean =
        { recipientId, transferIdHex, chunkIndex, chunkOffset, chunkLen, range ->
            if (isPeerViaUdp(recipientId)) {
                // Понижение — и дальше текстовый путь.
                sender.demoteBinary(transferIdHex)
                false
            } else {
                runCatching {
                    RustBridge.sendFileChunk(
                        recipientId,
                        transferIdHex,
                        chunkIndex,
                        chunkOffset,
                        chunkLen,
                        range,
                    )
                }.getOrDefault(false)
            }
        }
    private lateinit var chunkStore: FileTransferChunkStore
    private val receivedStore: ReceivedFileStore
    private val lastHelloAt = HashMap<String, Long>()

    init {
        appContext = context.applicationContext
        chunkStore = FileTransferChunkStore.forApplication(appContext)
        val receivedStoreLocal = ReceivedFileStore(File(appContext.noBackupFilesDir, "file_received/v1"))
        receivedStore = receivedStoreLocal
        val transportLocal: PacketTransport = RustPacketTransport()
        val lan = LanDirectChannel.get()
        syncLanIdentity()
        lan.onDiagnostic = { message -> Log.i(TAG, message) }
        lan.incomingRoute = { senderId, chatId, messageId, text ->
            routeIncoming(senderId, chatId, messageId, text)
        }
        lanChannel = lan
        // Мобильная связь (docs/SWARM_MOBILE.md): UDP-каналы с пробиванием
        // NAT для файловых пакетов. Сигналы `ufseek`/`ufcand` идут через
        // durable-брокера (доходят по мобильной), данные — прямым UDP.
        // Приёмный путь у пакетов один: routeIncoming (как у LAN/брокера).
        udpChannels = FileUdpChannels(
            myBindingProvider = { FileExchangeKeyStore.publicBinding(appContext) },
            sendSignal = { recipientId, signalText ->
                runCatching {
                    RustBridge.sendMessage(
                        "udp-sig-" + System.nanoTime(),
                        FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE,
                        recipientId,
                        signalText,
                    )
                }.getOrDefault(false)
            },
            verifyPeerBinding = { nodeId, binding ->
                runCatching {
                    uniffi.p2p_core.verifyFileExchangeBinding(binding) &&
                        uniffi.p2p_core.fileExchangeBindingNodeId(binding) == nodeId
                }.getOrDefault(false)
            },
            onDataPacket = { peerId, packetText ->
                CoroutineScope(Dispatchers.IO).launch {
                    routeIncoming(
                        senderId = peerId,
                        chatId = FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE,
                        messageId = "udp-" + System.nanoTime(),
                        text = packetText,
                    )
                }
            },
        )
        val switchingTransport: PacketTransport = SwitchingPacketTransport(transportLocal, lan)
        transport = switchingTransport
        val crypto: FileCryptoGateway = FfiFileCryptoGateway()
        val identity: LocalExchangeIdentity = AndroidLocalExchangeIdentity(appContext)
        val keyVault: TransferKeyVaultAccess = AndroidTransferKeyVaultAccess(appContext)
        val notifier = FileTransferReceiver.FileChatNotifier(
            { chatId, senderId, messageId, displayName, mediaType, totalBytes, fileSha256 ->
                // Рой APK (docs/UPDATE_SEEDING.md): файл в виртуальное
                // сообщество «apkseed» — строка в чат не нужна; сидер сам
                // отметит «получено» и сразу начнёт раздавать соседям.
                val apkUpdate = runCatching { apkSeeder.get().onFileReceived(chatId, senderId, fileSha256) }
                    .onFailure { Log.w(TAG, "apk update completion hook failed: ${it.message}") }
                    .getOrDefault(false)
                // Файл группы (этап 9): карточка уже есть в ленте темы, строка
                // в личный чат не нужна; рой снимает просьбу и объявляет
                // соседям «файл у меня».
                val groupFile = if (!apkUpdate) {
                    runCatching { groupFiles.get().onFileReceived(chatId, senderId, fileSha256) }
                        .onFailure { Log.w(TAG, "group file completion hook failed: ${it.message}") }
                        .getOrDefault(false)
                } else {
                    false
                }
                if (!apkUpdate && !groupFile) {
                    // Раунд 134: байты гифки приезжают ТИХО (раунд 128 - обмен
                    // только ссылками). Карточка-ссылка уже стоит в чате и
                    // оживёт сама, когда байты лягут в библиотеку (хук ниже);
                    // плейсхолдер «Сохранено» рядом с ней и есть задвоение,
                    // что на скрине владельца 2026-09-23. Тихо - только ту
                    // гифку, которую этот телефон сам просил у хранителя
                    // (isWanted): присланный скрепкой .gif по-прежнему
                    // показывает пузырь, его никто не просил из каталога.
                    val gifSilent = mediaType.equals("image/gif", ignoreCase = true) &&
                        runCatching {
                            com.vladimir.messenger.data.gif.GifLibrary.isWanted(fileSha256)
                        }.getOrDefault(false)
                    if (!gifSilent) {
                        chatRepository.saveIncomingMessage(
                            chatId = chatId,
                            senderId = senderId,
                            messageId = messageId,
                            content = formatPlaceholder(displayName, mediaType, totalBytes),
                            timestamp = System.currentTimeMillis(),
                            recipientId = RustBridge.nodeId() ?: "",
                        )
                    }
                }
                    // Раунд 121: принятая гифка оседает в библиотеке телефона -
                    // она становится частью СВОЕГО каталога роя (без внешнего
                    // ресурса): announces/выдача - через APUGIF1.
                    if (mediaType.equals("image/gif", ignoreCase = true)) {
                        runCatching {
                            val row = transferDao.getForFile(chatId, fileSha256)
                                .firstOrNull { it.direction == "INCOMING" && it.state == "COMPLETE" }
                            val plain = row?.let { receivedFileFor(it) }
                            if (plain != null) {
                                com.vladimir.messenger.data.gif.GifLibrary.addFromFile(
                                    appContext, plain, null, null,
                                )
                            }
                        }.onFailure { Log.w(TAG, "gif library hook failed: ${it.message}") }
                    }
            },
        )
        val directSend: (String, String) -> Boolean = { recipientId, payload ->
            // F4-F: LAN direct channel first (phone-to-phone TCP over shared
            // Wi-Fi). Falls back to the QUIC direct path when LAN is not
            // available. Mesh signalling is used to find the endpoint, and
            // when the mesh itself is dead a /24 subnet discovery scan runs.
            // The router singleton is created before the Rust engine is up,
            // so the LAN identity is re-synced here on every use (during
            // startup RustBridge.nodeId() is null and the old one-shot
            // assignment left myNodeId empty — servers then rejected every
            // handshake with "bad lan sender id").
            syncLanIdentity()
            val lanOk = runBlocking {
                val scope = FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE
                val quick = lan.hasChannel(recipientId) &&
                    lan.sendPacket(recipientId, scope, "lan-" + System.nanoTime(), payload)
                if (quick) {
                    true
                } else {
                    val viaSignal = lan.awaitChannel(recipientId, System.currentTimeMillis()) { requestText ->
                        transportLocal.send("lan-seek-" + System.nanoTime(), scope, recipientId, requestText)
                    }
                    val established = viaSignal || lan.discoverPeer(recipientId)
                    established && lan.sendPacket(recipientId, scope, "lan-" + System.nanoTime(), payload)
                }
            }
            if (lanOk) {
                lastDirectMode[recipientId] = "lan"
                true
            } else {
                // Мобильная связь (docs/SWARM_MOBILE.md): если UDP-канал
                // пробит — через него (тот же текст, что по LAN). Пока
                // пробиваем — ensureRequested отправит ufseek (троттлинг
                // внутри), а пакет уедет дальше по цепочке.
                udpChannels.ensureRequested(recipientId)
                if (udpChannels.trySend(recipientId, payload)) {
                    lastDirectMode[recipientId] = "udp"
                    true
                } else {
                    val quicOk = try {
                        com.vladimir.messenger.data.RustBridge.sendDirectPayload(recipientId, payload)
                    } catch (_: Exception) {
                        false
                    }
                    if (quicOk) {
                        lastDirectMode[recipientId] = "quic"
                        true
                    } else {
                        // Прямого нет вовсе: если собеседник в сети по
                        // presence — через durable-брокера (по мобильной
                        // медленно, но передача не «умирает»). Оффлайновый
                        // собеседник не грузим: пауза как раньше.
                        if (isPeerOnline(recipientId)) {
                            val viaBroker = runCatching {
                                runBlocking {
                                    transportLocal.send(
                                        "frag-" + System.nanoTime(),
                                        FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE,
                                        recipientId,
                                        payload,
                                    )
                                }
                            }.getOrDefault(false)
                            if (viaBroker) {
                                lastDirectMode[recipientId] = "broker"
                            }
                            viaBroker
                        } else {
                            false
                        }
                    }
                }
            }
        }
        val senderLocal = FileTransferSender(
            transferDao = transferDao,
            chunkStore = chunkStore,
            transport = switchingTransport,
            ownBindingProvider = { FileExchangeKeyStore.publicBinding(appContext) },
            // Наблюдения для рейтинга узлов: скорость прямой отдачи и то,
            // дошло ли вообще. Узел, который стабильно берёт данные быстро,
            // поднимется в очереди на отправку.
            onDirectSend = { peerId, bytes, millis, ok ->
                runCatching {
                    com.vladimir.messenger.data.peer.PeerRatingStore
                        .recordTransfer(appContext, peerId, bytes, millis)
                    com.vladimir.messenger.data.peer.PeerRatingStore
                        .recordDelivery(appContext, peerId, ok)
                    if (ok) {
                        // Дошло напрямую - значит узел действительно
                        // достижим извне, а не только через ретранслятор.
                        com.vladimir.messenger.data.peer.PeerRatingStore
                            .recordDirectReach(appContext, peerId)
                    }
                }
            },
            directTransport = directSend,
            binaryTransport = binarySend,
        )
        sender = senderLocal
        val custodyLocal = FileCustodySender(
            transferDao = transferDao,
            chunkStore = chunkStore,
            directTransport = directSend,
            ownBindingProvider = { FileExchangeKeyStore.publicBinding(appContext) },
            myNodeId = { RustBridge.nodeId() },
            candidates = { recipientId, totalBytes -> custodyCandidates(recipientId, totalBytes) },
            isOnline = { nodeId -> isPeerOnline(nodeId) },
            // Файлы группы (этап 9) на хранение не отдаём: см. FileCustodySender.
            custodyAllowed = { transfer ->
                runCatching { !groupFiles.get().isGroupChat(transfer.chatId) }.getOrDefault(true)
            },
        )
        custodySender = custodyLocal
        val seederLocal = GroupFileSeeder(
            transferDao = transferDao,
            chunkStore = chunkStore,
            directTransport = directSend,
            ownBindingProvider = { FileExchangeKeyStore.publicBinding(appContext) },
            wrapKey = { transferIdHex, requester ->
                // Автору ключ общей копии сделала подготовка; получившему -
                // приёмник (импорт из конверта сида). Оба лежат в хранилище
                // ключей под id передачи, и конверт просителю печатается с
                // байтами общего манифеста.
                preparation.get().wrapGroupKey(transferIdHex, requester)
            },
            mayServe = { row, requester ->
                if (row.chatId == com.vladimir.messenger.data.update.ApkUpdate.CHAT_ID) {
                    // Рой APK (docs/UPDATE_SEEDING.md): «членства в группе»
                    // нет — куски может взять любой проситель. Сидер сам уже
                    // проверил версию и sha; правило «только младшим версиям»
                    // держит проситель (старшие сами не просят).
                    runCatching {
                        val me = RustBridge.nodeId()
                        requester != me && requester.startsWith("pk_")
                    }.getOrDefault(false)
                } else {
                    runCatching { groupFiles.get().mayServeFile(row.chatId, requester) }.getOrDefault(false)
                }
            },
        )
        groupSeeder = seederLocal
        receiver = FileTransferReceiver(
            transferDao = transferDao,
            chunkStore = chunkStore,
            receivedStore = receivedStoreLocal,
            pinner = { binding, pinnedAtMs ->
                val result = peerStore.pinFirstSeen(binding, pinnedAtMs)
                // Тот же закреплённый ключ шифрует и переписку — отдельного
                // обмена ключами заводить не нужно.
                runCatching {
                    com.vladimir.messenger.data.security.MessageSealer
                        .remember(appContext, result.nodeId, binding)
                }
                result.newlyPinned
            },
            crypto = crypto,
            keyVault = keyVault,
            identity = identity,
            transport = switchingTransport,
            ackSink = { transferIdHex, contiguousChunks ->
                senderLocal.onReceiverAck(transferIdHex, contiguousChunks)
                // ACK progress immediately opens the next bounded window. The periodic job is
                // only a restart/offline retry safety net, never a throughput throttle.
                senderLocal.pumpOnce()
            },
            notifier = notifier,
            custody = FileTransferReceiver.CustodyPolicy(
                // Держим чужие файлы только для контактов: чужак не должен
                // занимать место на телефоне. Получатель контактом быть не
                // обязан - это забота отправителя.
                acceptsFrom = { originId -> contactDao.getContactById(originId) != null },
                headroomBytes = {
                    com.vladimir.messenger.data.swarm.StorageSettings
                        .headroom(appContext, chunkStore.currentStoredBytes())
                },
                // Пересланный файл - в чат с его отправителем, а не с
                // хранителем; нет чата - создаём, как для обычного входящего
                // сообщения от контакта.
                chatIdFor = { originId ->
                    runCatching {
                        chatRepository.getChatByContactId(originId)?.id
                            ?: contactDao.getContactById(originId)?.let { contact ->
                                chatRepository.getOrCreateChat(originId, contact.displayName).id
                            }
                    }.getOrNull()
                },
                onCustodianAck = { transferIdHex, from, contiguous, status ->
                    custodyLocal.onCustodianAck(transferIdHex, from, contiguous, status)
                },
                onRecipientAck = { transferIdHex, from, contiguous, status ->
                    custodyLocal.onRecipientAck(transferIdHex, from, contiguous, status)
                },
                onRecipientWant = { transferIdHex, from, contiguous, seq, ranges ->
                    custodyLocal.onRecipientWant(transferIdHex, from, contiguous, seq, ranges)
                },
                onOriginRelease = { transferIdHex, from -> custodyLocal.onOriginRelease(transferIdHex, from) },
            ),
            // Файл группы (этап 9): предложение от сида ложится в группу, а не
            // в личный чат; лишнее (файл уже идёт от другого) - отбрасывается.
            routeOffer = { senderId, fileSha256 ->
                val groupRouting = runCatching { groupFiles.get().routeOffer(senderId, fileSha256) }
                    .getOrDefault(FileTransferReceiver.OfferRouting.Unknown)
                if (groupRouting !is FileTransferReceiver.OfferRouting.Unknown) {
                    groupRouting
                } else {
                    // Рой APK (docs/UPDATE_SEEDING.md): рой групп этого файла
                    // не знает — принимает в сообщество «apkseed» сидер, и
                    // только если я сам запрашивал это обновление.
                    runCatching { apkSeeder.get().routeApkOffer(senderId, fileSha256) }
                        .getOrDefault(FileTransferReceiver.OfferRouting.Unknown)
                }
            },
            groupSeed = FileTransferReceiver.GroupSeedHooks(
                onAck = { transferIdHex, from, contiguous -> seederLocal.onAck(transferIdHex, from, contiguous) },
                onWant = { transferIdHex, from, contiguous, seq, ranges ->
                    seederLocal.onWant(transferIdHex, from, contiguous, seq, ranges)
                },
                onCancel = { transferIdHex, from -> seederLocal.onCancel(transferIdHex, from) },
                onOfferAccepted = { chatId, seedId, fileSha256, seedCount ->
                    runCatching { groupFiles.get().onSeedJoined(chatId, seedId, fileSha256, seedCount) }
                        .onFailure { Log.w(TAG, "group seed hook failed: ${it.message}") }
                    // Рой APK (docs/UPDATE_SEEDING.md): куски обновления тоже
                    // со всех сидов — просим следующего, пока их меньше трёх.
                    runCatching { apkSeeder.get().onSeedJoined(chatId, seedId, fileSha256, seedCount) }
                        .onFailure { Log.w(TAG, "apk seed stripe hook failed: ${it.message}") }
                },
            ),
            // K3: собеседник подтвердил, что принимает APUF-кадры — передатчик
            // переводит нашу исходящую передачу на бинарный канал.
            onFcap = { transferIdHex, from, maxFramePayload ->
                // K3+UDP (docs/SWARM_MOBILE.md): APUF-кадры идут только по
                // прямому QUIC. Если прямой режим на узле — UDP, бинарный
                // путь не включаем (текстовые фрагменты по UDP).
                if (isPeerViaUdp(from)) {
                    Log.i(TAG, "FCAP $transferIdHex from ${from.takeLast(8)}: peer via UDP, binary path off")
                } else {
                    senderLocal.markBinaryCapable(transferIdHex, maxFramePayload)
                }
            },
        )
        seederLocal.onServed = { transferIdHex, requester ->
            runCatching { groupFiles.get().onServed(transferIdHex, requester) }
        }
        // LAN server starts only after sender/receiver exist: an early incoming
        // frame must never hit a half-constructed router.
        lan.startServer()
        Log.i(TAG, "router init complete: lan build=2026-08-25-B, lan port=${lan.listenPort}, node=${lan.myNodeId}")
    }

    /** True when the text was a file packet/handshake; the caller must skip chat-text handling. */
    suspend fun routeIncoming(senderId: String, chatId: String, messageId: String, text: String): Boolean {
        if (LanDirectChannel.isLanSignalText(text)) {
            handleLanSignal(senderId, text)
            return true
        }
        // Сигналы UDP-канала файловой передачи (мобильная, docs/SWARM_MOBILE.md):
        // `ufseek`/`ufcand` несут кандидаты NAT-пробивания. Идут по durable-пути
        // (доходят по мобильной); канальное состояние меняется в менеджере.
        if (FileUdpWire.isUdpSignalText(text)) {
            val signal = FileUdpWire.parseUdpSignal(text)
            if (signal != null) {
                udpChannels.onUdpSignal(senderId, signal)
            }
            return true
        }
        if (FileTransferWire.isHelloText(text)) {
            when (receiver.onHelloText(senderId, text)) {
                FileTransferReceiver.HelloResult.PINNED_NEW -> {
                    runCatching { sendHello(senderId, force = true) }
                        .onFailure { Log.w(TAG, "File HELLO auto-reply failed: ${it.message}") }
                }
                else -> Unit
            }
            return true
        }
        if (!FileTransferWire.isFilePacketText(text)) return false

        // Хранение у третьего телефона: хранитель и отправитель могут не
        // иметь общего чата (получатель с хранителем - тем более). Чат здесь
        // не нужен: приёмник сам находит чат с отправителем для пересланного
        // файла, а чужой файл на хранении чата не имеет вовсе.
        if (FileTransferWire.peekType(text)?.isCustody == true) {
            return receiver.onIncomingText(senderId, FileTransferChatRouting.CUSTODY_SCOPE, messageId, text)
        }

        // Chat UUIDs are device-local. In particular, direct QUIC frames carry the explicit
        // "direct" transport scope rather than a remote chat UUID. Resolve it to THIS phone's
        // chat before the receiver writes transfer state; never let the sentinel reach Room.
        val localChatId = runCatching { chatRepository.getChatByContactId(senderId)?.id }
            .onFailure { Log.w(TAG, "Cannot resolve local chat for incoming file packet: ${it.message}") }
            .getOrNull()
        val resolvedChatId = FileTransferChatRouting.resolve(chatId, localChatId)
        if (resolvedChatId == null) {
            // Файл группы (этап 9): сид и проситель могут быть незнакомы, но
            // состоят в одном сообществе. Метка `direct` идёт в приёмник как
            // есть: чат для предложения он спрашивает у роя, а в базу метка
            // не попадает (см. FileTransferReceiver.handleOffer).
            val sharesGroup = runCatching { groupFiles.get().sharesGroupWith(senderId) }.getOrDefault(false)
            if (!sharesGroup) {
                Log.w(TAG, "Direct file packet dropped: no local chat for sender ${senderId.takeLast(8)}")
                return true
            }
            return receiver.onIncomingText(senderId, FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE, messageId, text)
        }
        if (chatId == FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE) {
            Log.i(TAG, "Direct file packet routed to local chat $resolvedChatId")
        }
        return receiver.onIncomingText(senderId, resolvedChatId, messageId, text)
    }

    /**
     * K3: бинарный диапазон зашифрованного куска из ядра (событие
     * "file_chunk_received"). В кадре нет текста/чата/отправителя —
     * адресация по transferId, а подлинность гарантирует AES-GCM тег
     * целого куска. Служебный путь, не переписки: чат не нужен.
     */
    suspend fun onBinaryChunk(
        transferIdHex: String,
        chunkIndex: Long,
        chunkOffset: Int,
        ciphertextChunkLen: Int,
        ciphertextRange: ByteArray,
    ) {
        runCatching {
            receiver.onBinaryChunk(transferIdHex, chunkIndex, chunkOffset, ciphertextChunkLen, ciphertextRange)
        }.onFailure { error ->
            Log.w(TAG, "Binary file chunk for $transferIdHex dropped: ${error.message}")
        }
    }

    /**
     * F4-F v1 LAN signalling over the mesh: "APULAN1|req" is answered with
     * "APULAN1|offer|<lan-ip>|<port>" so the peer can open a direct socket.
     */
    /**
     * Refreshes the LAN channel identity from the live Rust engine. The router
     * singleton is constructed early in process start, when the engine may not
     * have a node id yet; calling this before every LAN use keeps the identity
     * correct without restarting the LAN server.
     */
    private fun syncLanIdentity() {
        val nodeId = RustBridge.nodeId() ?: return
        val lan = LanDirectChannel.get()
        if (nodeId.startsWith("pk_") && lan.myNodeId != nodeId) {
            val previous = lan.myNodeId
            lan.myNodeId = nodeId
            Log.i(TAG, "lan identity synced: ${nodeId.take(16)} (was ${if (previous.isBlank()) "blank" else previous.take(16)})")
        }
    }

    private suspend fun handleLanSignal(senderId: String, text: String) {
        syncLanIdentity()
        val endpoint = lanChannel.parseOfferText(text)
        if (endpoint != null) {
            lanChannel.onOfferReceived(senderId, endpoint)
            return
        }
        if (!lanChannel.isRequestText(text)) return
        val offer = lanChannel.buildOfferText()
        if (offer == null) {
            Log.w(TAG, "LAN request from $senderId but no local Wi-Fi endpoint found")
            return
        }
        Log.i(TAG, "LAN request from $senderId, replying with offer $offer")
        runCatching {
            transport.send(
                "lan-" + System.nanoTime(),
                FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE,
                senderId,
                offer,
            )
        }.onFailure { Log.w(TAG, "LAN offer reply failed: ${it.message}") }
        // The request carried the requester's own LAN endpoint: deliver the
        // offer straight to the sender's socket as well, so channel setup does
        // not depend on the (slow, chunk-flooded) mesh in either direction.
        val requester = lanChannel.parseRequestEndpoint(text)
        if (requester != null) {
            val host = requester.address?.hostAddress ?: requester.hostString
            if (host != null) {
                runCatching {
                    lanChannel.sendSignalFrame(host, requester.port, FileTransferChatRouting.DIRECT_TRANSPORT_SCOPE, offer)
                }.onSuccess { delivered ->
                    Log.i(TAG, "LAN offer socket delivery to $host:${requester.port} ok=$delivered")
                }
            }
        }
    }

    /**
     * Получатель появился: возобновить все передачи, которые его ждали.
     */
    suspend fun resumeWaitingForRecipient() {
        val resumed = transferDao.resumeAllWaitingRecipient(System.currentTimeMillis())
        if (resumed > 0) {
            Log.i(TAG, "Resumed $resumed file transfer(s) waiting for recipient")
            pumpOutgoing()
        }
    }

    /** Drives all resumable outgoing transfers plus contact key handshakes; safe to call periodically. */
    suspend fun pumpOutgoing(): FileTransferSender.PumpSummary? {
        // Раунд 120: насос всегда уходит в фоновый поток. Внутри - блокирующие
        // прямые отправки (QUIC до секунд на кадр) и чтение кусков из базы;
        // вызов с главного потока (ViewModel при отправке) давал
        // «Приложение не отвечает» вплоть до убийства системы.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!RustBridge.isRunning()) {
                Log.d(TAG, "File pump skipped: engine not running")
                return@withContext null
            }
            runCatching { sendHelloHandshakes() }
                .onFailure { Log.w(TAG, "File HELLO sweep failed: ${it.message}") }
            val summary = sender.pumpOnce()
            pumpCustody()
            runCatching { groupSeeder.pump() }
                .onFailure { Log.w(TAG, "group seed pump failed: ${it.message}") }
            summary
        }
    }

    /** Ключ передачи на месте (для проверки, годится ли строка как источник общей копии, K2). */
    fun hasTransferKey(transferId: String): Boolean =
        runCatching { FileTransferKeyVault.mode(appContext, transferId) == FileTransferKeyVault.Mode.READY }
            .getOrDefault(false)

    /**
     * Хранение у третьего телефона (этап 7 роя): своим ожидающим передачам
     * ищем хранителя, чужие хранимые отдаём появившимся получателям, старое
     * подчищаем. Ошибки здесь не должны ронять обычный насос.
     */
    private suspend fun pumpCustody() {
        runCatching {
            val now = System.currentTimeMillis()
            val resumed = transferDao.resumeStaleCustodied(now, now - FileCustodySender.DIRECT_RETRY_AFTER_MS)
            if (resumed > 0) Log.i(TAG, "custody: $resumed transfer(s) retried directly after custody")
            val origin = custodySender.pumpOrigin()
            val forward = custodySender.pumpForwarding()
            val swept = custodySender.sweep()
            if (origin.originPumped > 0 || forward.forwarded > 0 || swept > 0) {
                Log.i(
                    TAG,
                    "custody pump: offered=${origin.originPumped} forwarded=${forward.forwarded} " +
                        "packets=${origin.packets + forward.packets} swept=$swept",
                )
            }
        }.onFailure { Log.w(TAG, "custody pump failed: ${it.message}") }
    }

    /** Пульс присутствия: узел в сети. Будит выдачу хранимых для него файлов. */
    suspend fun markOnline(nodeId: String) {
        val first = onlinePeers.put(nodeId, System.currentTimeMillis()) == null
        if (first && RustBridge.isRunning()) {
            val held = runCatching { transferDao.getCustodyForRecipient(nodeId, System.currentTimeMillis()) }
                .getOrDefault(emptyList())
            if (held.isNotEmpty()) {
                Log.i(TAG, "custody: ${held.size} held file(s) for ${nodeId.takeLast(8)} who just came online")
                runCatching { custodySender.pumpForwarding() }
                    .onFailure { Log.w(TAG, "custody forward on presence failed: ${it.message}") }
            }
        }
    }

    fun markOffline(nodeId: String) {
        onlinePeers.remove(nodeId)
    }

    private fun isPeerOnline(nodeId: String): Boolean {
        val seen = onlinePeers[nodeId] ?: return false
        return System.currentTimeMillis() - seen < ONLINE_TTL_MS
    }

    /**
     * Кандидаты в хранители: контакты в сети, кроме получателя, лучшие по
     * рейтингу первыми; узлы, объявившие место (`cap`) меньше файла, - в
     * хвост, но не выбрасываются (объявление могло устареть, ответит «полон»).
     */
    private suspend fun custodyCandidates(recipientId: String, totalBytes: Long): List<String> {
        val now = System.currentTimeMillis()
        val online = onlinePeers.entries
            .filter { now - it.value < ONLINE_TTL_MS && it.key != recipientId && it.key.startsWith("pk_") }
            .map { it.key }
        if (online.isEmpty()) return emptyList()
        val contacts = runCatching { contactDao.allIds().toSet() }.getOrDefault(emptySet())
        val eligible = online.filter { it in contacts }
        if (eligible.isEmpty()) return emptyList()
        val ordered = com.vladimir.messenger.data.peer.PeerRatingStore.preferredOrder(appContext, eligible, now)
        val (roomy, tight) = ordered.partition { id ->
            val offered = com.vladimir.messenger.data.peer.PeerRatingStore.statsFor(appContext, id)?.offeredBytes ?: 0L
            offered <= 0L || offered >= totalBytes
        }
        return (roomy + tight).take(MAX_CUSTODY_CANDIDATES)
    }

    /** Сколько чужих байт держим для других (строка «Занято сейчас…» в настройках). */
    suspend fun custodyHeldBytes(): Long = runCatching { transferDao.custodyHeldBytes() }.getOrDefault(0L)

    /** UI escape hatch when preparation reports the recipient binding is not pinned yet. */
    suspend fun requestExchangeBinding(recipientNodeId: String) {
        if (!RustBridge.isRunning()) return
        runCatching { sendHello(recipientNodeId, force = false) }
            .onFailure { Log.w(TAG, "File HELLO request failed: ${it.message}") }
    }

    /**
     * Немедленно отдать собеседнику свой ключ.
     *
     * Вызывается после пересканирования QR. Без этого ключ ушёл бы только со
     * следующим кругом фоновой рассылки, а сброс пина сделали обе стороны -
     * и обе молча ждали бы друг друга. `force` обходит защиту от частых
     * повторов: пересканирование QR - редкое осознанное действие человека.
     */
    suspend fun announceMyKeyTo(recipientNodeId: String) {
        if (!RustBridge.isRunning()) return
        runCatching { sendHello(recipientNodeId, force = true) }
            .onFailure { Log.w(TAG, "File HELLO announce failed: ${it.message}") }
    }

    /**
     * Пользовательская «очистка зависших» (настройки → Передача файлов): отменяет все
     * незавершённые ИСХОДЯЩИЕ передачи (передатчик больше не пытается их докачать) и
     * удаляет их локальные зашифрованные куски. Входящие и завершённые не трогает.
     * @return число отменённых передач.
     */
    suspend fun cancelStalledOutgoing(): Int {
        val cancelled = transferDao.cancelAllOutgoing(System.currentTimeMillis())
        val rows = transferDao.getCancelled()
        var cleanedFiles = 0
        for (row in rows) {
            if (runCatching { chunkStore.deleteTransfer(row.transferId) }.getOrDefault(false)) {
                cleanedFiles++
            }
        }
        Log.i(TAG, "Cancelled $cancelled stalled outgoing transfers (files cleaned: $cleanedFiles)")
        return cancelled
    }

    /**
     * Освобождает место: удаляет локальные файлы (куски и принятые копии) завершённых
     * передач и их строки истории. Принятые файлы, уже сохранённые пользователем в папку,
     * остаются у него.
     * @return число очищенных передач.
     */
    suspend fun purgeCompletedTransfers(): Int {
        val completed = transferDao.getCompleted()
        for (row in completed) {
            runCatching { chunkStore.deleteTransfer(row.transferId) }
            runCatching { receivedStore.deleteTransfer(row.transferId) }
            transferDao.deleteTransfer(row.transferId)
        }
        Log.i(TAG, "Purged ${completed.size} completed transfers")
        return completed.size
    }

    /**
     * Куски отданной передачи больше не нужны (получатель подтвердил всё):
     * освободить место, строку истории оставить. Рой файлов группы (этап 9)
     * зовёт это сразу после подтверждения - у каждого просителя своя копия
     * (конверт под его ключ), и на большой группе они бы съели диск сида.
     */
    fun releaseOutgoingChunks(transferId: String) {
        runCatching { chunkStore.deleteTransfer(transferId) }
            .onFailure { Log.w(TAG, "release chunks failed for $transferId: ${it.message}") }
    }

    /**
     * Лишняя входящая (рой, этап 10): файл уже получен от другого сида.
     * Отправителю - CANCEL (он остановит и освободит место), у себя - убрать
     * строку и куски. Завершённые не трогаем.
     */
    suspend fun declineIncoming(transfer: com.vladimir.messenger.data.local.entity.FileTransferEntity) {
        if (transfer.direction != "INCOMING" || transfer.state == "COMPLETE") return
        receiver.declineTransfer(transfer)
        dropTransfer(transfer.transferId)
    }

    /** Убрать передачу целиком: куски, принятую копию, строку. Для незавершённых и лишних. */
    suspend fun dropTransfer(transferId: String) {
        runCatching { chunkStore.deleteTransfer(transferId) }
        runCatching { receivedStore.deleteTransfer(transferId) }
        runCatching { transferDao.deleteTransfer(transferId) }
            .onFailure { Log.w(TAG, "drop transfer failed for $transferId: ${it.message}") }
    }

    /** Verified plaintext of a completed incoming transfer (app-private storage), if present. */
    fun receivedFileFor(transfer: com.vladimir.messenger.data.local.entity.FileTransferEntity): java.io.File? {
        if (transfer.direction != "INCOMING" || transfer.state != "COMPLETE") return null
        return runCatching { receivedStore.receivedFile(transfer.transferId, transfer.displayName) }
            .getOrNull()
    }

    /**
     * Раунд 43: файл превью для пузыря в чате. Входящая картинка - принятый
     * plaintext после COMPLETE; исходящая - маленькое превью, записанное при
     * подготовке передачи. Раунд 120: у исходящей гифки превью - сама гифка
     * (.gif), чтобы пузырь отправителя анимировался; старые передачи остаются
     * на .jpg.
     */
    fun previewFileFor(transfer: com.vladimir.messenger.data.local.entity.FileTransferEntity): java.io.File? {
        if (!transfer.mediaType.startsWith("image/")) return null
        if (transfer.direction == "INCOMING") return receivedFileFor(transfer)
        val base = "file_preview/v1/" + transfer.transferId
        if (transfer.mediaType.equals("image/gif", ignoreCase = true)) {
            val gif = java.io.File(appContext.noBackupFilesDir, base + ".gif")
            if (gif.isFile) return gif
        }
        val f = java.io.File(appContext.noBackupFilesDir, base + ".jpg")
        return if (f.isFile) f else null
    }

    /** Copies a completed incoming transfer's plaintext to the user-chosen SAF destination. */
    suspend fun exportReceivedFile(
        transfer: com.vladimir.messenger.data.local.entity.FileTransferEntity,
        target: android.net.Uri,
    ): Boolean {
        val source = receivedFileFor(transfer) ?: return false
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val output = appContext.contentResolver.openOutputStream(target) ?: return@runCatching false
                output.use { source.inputStream().use { input -> input.copyTo(it) } }
                true
            }.getOrDefault(false)
        }
    }

    /**
     * Разогреть кэш шифрования уже закреплёнными ключами.
     *
     * Без этого у существующих пользователей первые сообщения после
     * обновления ушли бы открытыми: ключи лежат в базе, но кэш пуст, а точка
     * отправки не может обращаться к базе.
     */
    suspend fun warmSealingKeys() {
        runCatching {
            val bindings = peerStore.allBindings()
            for ((nodeId, binding) in bindings) {
                com.vladimir.messenger.data.security.MessageSealer
                    .remember(appContext, nodeId, binding)
            }
            Log.i(TAG, "Sealing keys warmed: ${bindings.size}")
        }.onFailure { Log.w(TAG, "Sealing warm-up failed: ${it.message}") }
    }

    /** Когда последний раз проверяли, со всеми ли обменялись ключами. */
    @Volatile
    private var lastHelloSweepAt = 0L

    private suspend fun sendHelloHandshakes() {
        val now = System.currentTimeMillis()
        // Насос крутится каждые 20 секунд, но перебирать все контакты и
        // разбирать их подписанные ключи так часто незачем: когда обмен уже
        // состоялся, работа выполняется впустую и только греет телефон.
        // Раз в пять минут достаточно - новый контакт получит ключ и раньше,
        // через ответ на своё же HELLO.
        // Первый проход после запуска выполняем ВСЕГДА: иначе обмен ключами
        // с новым контактом отложился бы на пять минут.
        if (lastHelloSweepAt != 0L && now - lastHelloSweepAt < HELLO_SWEEP_INTERVAL_MS) return
        lastHelloSweepAt = now

        val myBinding = FileExchangeKeyStore.publicBinding(appContext) ?: return
        // Раунд 81: раньше брались только собеседники, у которых УЖЕ есть чат.
        // Контакт, добавленный по QR, чата ещё не имеет, поэтому ключами с ним
        // никто не обменивался: первое личное сообщение уходило незашифрованным,
        // а имя не приходило вовсе. Берём объединение чатов и контактов.
        val fromChats = runCatching { chatRepository.getAllContactIds() }.getOrDefault(emptyList())
        val fromContacts = runCatching { contactDao.observeAllContactsOnce().map { it.id } }
            .getOrDefault(emptyList())
        val contacts = (fromChats + fromContacts).distinct()
        for (contactId in contacts) {
            if (!contactId.startsWith("pk_")) continue
            val pinned = runCatching { peerStore.bindingFor(contactId) != null }.getOrDefault(true)
            if (!pinned) sendHello(contactId, force = false, now = now, binding = myBinding)
        }
    }

    private suspend fun sendHello(
        recipientNodeId: String,
        force: Boolean,
        now: Long = System.currentTimeMillis(),
        binding: ByteArray? = null,
    ) {
        if (!recipientNodeId.startsWith("pk_")) return
        val myNodeId = RustBridge.nodeId() ?: return
        val myBinding = binding ?: FileExchangeKeyStore.publicBinding(appContext) ?: return
        if (!force) {
            val last = lastHelloAt[recipientNodeId] ?: 0L
            if (now - last < HELLO_MIN_INTERVAL_MS) return
        }
        lastHelloAt[recipientNodeId] = now
        transport.send(
            FileTransferWire.helloMessageId(myNodeId, recipientNodeId),
            recipientNodeId,
            recipientNodeId,
            FileTransferWire.encodeHelloBinding(myBinding),
        )
        Log.i(TAG, "File HELLO sent to ${recipientNodeId.takeLast(8)} (force=$force)")
    }

    companion object {
        private const val TAG = "FileTransferRouter"
        const val HELLO_MIN_INTERVAL_MS = 60_000L
        /** Пульс присутствия идёт раз в минуту; три пропуска - узел «не в сети» (как в сервисе). */
        const val ONLINE_TTL_MS = 200_000L
        const val MAX_CUSTODY_CANDIDATES = 8

        /** Как часто сверять, со всеми ли контактами обменялись ключами. */
        const val HELLO_SWEEP_INTERVAL_MS = 5 * 60_000L

        fun formatPlaceholder(displayName: String, mediaType: String, totalBytes: Long): String {
            val kind = when {
                mediaType.startsWith("image/") -> "🖼"
                mediaType.startsWith("video/") -> "🎬"
                mediaType.startsWith("audio/") -> "🎵"
                else -> "📎"
            }
            return "$kind $displayName (${formatSize(totalBytes)})"
        }

        fun formatSize(totalBytes: Long): String = when {
            totalBytes >= 1024 * 1024 -> "%.1f МБ".format(totalBytes / (1024.0 * 1024.0))
            totalBytes >= 1024 -> "%.1f КБ".format(totalBytes / 1024.0)
            else -> "$totalBytes Б"
        }
    }
}
