package com.vladimir.messenger.data

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.file.FileTransferWire
import com.vladimir.messenger.data.file.FileUdpWire
import com.vladimir.messenger.data.file.LanDirectChannel
import com.vladimir.messenger.data.security.MessageSealer
import kotlinx.coroutines.launch
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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

object RustBridge {

    private const val TAG = "RustBridge"
    private const val MAX_RELAY_WAKE_WINDOW_MILLIS = 30_000L

    @Volatile
    private var engine: P2pCoreHandle? = null

    @Volatile
    private var engineRunning: Boolean = false

    @Volatile
    private var cachedNodeId: String? = null

    @Volatile
    private var cachedPublicKey: String? = null

    @Volatile
    private var cachedNetworkStatus: String = "offline"

    @Volatile
    private var cachedConnectedPeers: Long = 0L

    @Volatile
    private var coreInitialized: Boolean = false

    // Engine start/stop operations share one FIFO executor. In particular, a new service
    // start is queued after the previous service's asynchronous shutdown, so lifecycle work
    // can never race into two native engines or accidentally cancel a required stop.
    private val lifecycleThread = AtomicReference<Thread?>()
    private val lifecycleExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "apu-core-lifecycle").also { thread ->
            thread.isDaemon = true
            lifecycleThread.set(thread)
        }
    }

    private fun isMainThread(): Boolean {
        val mainLooper = android.os.Looper.getMainLooper() ?: return false
        return android.os.Looper.myLooper() === mainLooper
    }

    private fun rejectMainThreadCall(operation: String): Boolean {
        if (!isMainThread()) return false
        Log.w(TAG, "$operation refused on the main thread", Throwable("main-thread native call"))
        return true
    }

    private fun <T> onLifecycleThread(block: () -> T): T {
        if (Thread.currentThread() === lifecycleThread.get()) return block()
        val future = lifecycleExecutor.submit(Callable<T> { block() })
        try {
            return future.get()
        } catch (e: InterruptedException) {
            // In particular, cancelling a WorkManager relay wake interrupts its bounded sleep,
            // whose finally block stops the worker-owned engine before the queue advances.
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw e
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("Core lifecycle task failed", cause)
            }
        }
    }

    private fun enqueueLifecycle(block: () -> Unit) {
        try {
            lifecycleExecutor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    Log.e(TAG, "Asynchronous core lifecycle task failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not queue core lifecycle task", e)
        }
    }

    /**
     * Контекст приложения для шифрования переписки. Точка отправки не suspend
     * и вызывается из многих мест, поэтому контекст ставится один раз на
     * старте сервиса, а не протаскивается через каждый вызов.
     */
    @Volatile
    private var appContext: Context? = null

    /** Свой поток для подтверждений: они не должны тормозить приём. */
    private val ackScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    /**
     * р226: инициализировать ядро БЕЗ движка и сети. Нужно зеркалу: там
     * движок намеренно не запускается (сеть ведёт партнёр), но шифрование
     * зеркальных кадров (MessageSealer) зовёт функции ядра.
     */
    fun ensureCoreOnly() {
        if (coreInitialized) return
        if (isMainThread()) {
            Log.w(TAG, "ensureCoreOnly queued off the main thread")
            enqueueLifecycle { ensureCoreOnlyLocked() }
            return
        }
        try {
            onLifecycleThread { ensureCoreOnlyLocked() }
        } catch (ex: Exception) {
            Log.e(TAG, "ensureCoreOnly error", ex)
        }
    }

    private fun ensureCoreOnlyLocked() {
        if (coreInitialized) return
        try {
            val initResult = initializeCore()
            Log.i(TAG, "initializeCore() [mirror-only]: $initResult")
            coreInitialized = true
        } catch (ex: Exception) {
            Log.e(TAG, "ensureCoreOnly error", ex)
        }
    }

    fun attachContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * р228: адрес личности для устройства-зеркала. Движка у тени нет (сеть
     * ведёт партнёр), но личность та же, поэтому без этого `nodeId()` вернул
     * бы null и групповые конверты не применялись бы (проверка «я участник»).
     * Ставится только сервисом в режиме зеркала.
     */
    @Volatile
    private var shadowNodeId: String? = null

    fun setShadowNodeId(nodeId: String) {
        if (nodeId.isNotBlank()) {
            shadowNodeId = nodeId
            cachedNodeId = nodeId
        }
    }

    /**
     * M8-C: [relayDbPath] — собственный SQLite-файл durable encrypted relay
     * custody (app-private). Передаётся только после того, как
     * [com.vladimir.messenger.data.security.RelayAtRestMasterKey.installIntoCore]
     * попытался установить at-rest ключ: установленный ключ + путь =
     * durable-encrypted режим; без ключа движок честно уйдёт в RAM-only.
     */
    fun initialize(
        displayName: String,
        existingPublicKey: String? = null,
        existingPrivateKey: String? = null,
        relayDbPath: String? = null,
    ): Boolean {
        if (rejectMainThreadCall("initialize")) return false
        return try {
            onLifecycleThread {
                initializeLocked(displayName, existingPublicKey, existingPrivateKey, relayDbPath)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to initialize engine", ex)
            false
        }
    }

    /** Must run on [lifecycleExecutor], so engine startup is serialized with shutdown. */
    private fun initializeLocked(
        displayName: String,
        existingPublicKey: String?,
        existingPrivateKey: String?,
        relayDbPath: String?,
    ): Boolean {
        val current = engine
        if (current != null) {
            if (runCatching { current.isRunning() }.getOrDefault(false)) {
                engineRunning = true
                Log.w(TAG, "Engine already initialized")
                return true
            }
            engine = null
            engineRunning = false
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
                shadowNodeId = null
                engineRunning = true
                cachedNetworkStatus = "offline"
                cachedConnectedPeers = 0L
                cachedNodeId = runCatching { e.nodeId() }.getOrNull()
                cachedPublicKey = runCatching { e.publicKey() }.getOrNull()
                Log.i(TAG, "Engine started. NodeId: $cachedNodeId")
                // Diagnostics must not turn a successfully-started engine into a failed startup.
                val custodyMode = runCatching { e.relayCustodyMode() }.getOrDefault("unknown")
                val quarantined = runCatching { e.relayQuarantineCount() }.getOrDefault(0uL)
                Log.i(TAG, "Relay custody mode: $custodyMode, quarantined: $quarantined")
            } else {
                engine = null
                engineRunning = false
                Log.e(TAG, "Engine.start() returned false")
            }
            ok
        } catch (ex: Exception) {
            engineRunning = false
            Log.e(TAG, "Failed to initialize engine", ex)
            false
        }
    }

    /**
     * A service stop is requested on the Android main thread. Queue the actual native stop so
     * onDestroy never waits for a QUIC/relay call holding the engine's internal mutex.
     */
    fun shutdown() {
        if (isMainThread()) {
            shutdownAsync()
            return
        }
        try {
            onLifecycleThread { shutdownLocked() }
        } catch (ex: Exception) {
            Log.e(TAG, "Error stopping engine", ex)
        }
    }

    fun shutdownAsync() {
        enqueueLifecycle { shutdownLocked() }
    }

    private fun shutdownLocked() {
        val current = engine
        if (current == null) {
            engineRunning = false
            cachedNetworkStatus = "offline"
            cachedConnectedPeers = 0L
            return
        }
        try {
            current.stop()
            Log.i(TAG, "Engine stopped")
        } catch (ex: Exception) {
            Log.e(TAG, "Error stopping engine", ex)
        } finally {
            if (engine === current) engine = null
            engineRunning = false
            cachedNetworkStatus = "offline"
            cachedConnectedPeers = 0L
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
     * The entire wake window is one serialized lifecycle task: a foreground service cannot
     * create another native engine while this worker owns the bounded engine instance.
     */
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
        if (rejectMainThreadCall("runBoundedRelayWake")) {
            return RelayWakeResult(false, false, "disabled", 0L)
        }

        return try {
            onLifecycleThread {
                val current = engine
                if (current != null && runCatching { current.isRunning() }.getOrDefault(false)) {
                    engineRunning = true
                    RelayWakeResult(
                        engineStartedByWorker = false,
                        gossipTriggered = triggerGossipDiscovery(),
                        custodyMode = relayCustodyMode(),
                        quarantineCount = relayQuarantineCount(),
                    )
                } else if (!initializeLocked(
                        displayName,
                        existingPublicKey,
                        existingPrivateKey,
                        relayDbPath,
                    )
                ) {
                    RelayWakeResult(false, false, "disabled", 0L)
                } else {
                    try {
                        val gossipTriggered = triggerGossipDiscovery()
                        Thread.sleep(activeWindowMillis)
                        RelayWakeResult(
                            engineStartedByWorker = true,
                            gossipTriggered = gossipTriggered,
                            custodyMode = relayCustodyMode(),
                            quarantineCount = relayQuarantineCount(),
                        )
                    } finally {
                        shutdownLocked()
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Bounded relay wake failed", ex)
            RelayWakeResult(false, false, "disabled", 0L)
        }
    }

    /** Main-thread reads are cache-only; a native state check can wait on the engine mutex. */
    fun isRunning(): Boolean {
        if (isMainThread()) return engineRunning
        val current = engine
        if (current == null) {
            engineRunning = false
            return false
        }
        val running = runCatching { current.isRunning() }.getOrDefault(false)
        engineRunning = running
        return running
    }

    /**
     * Раунд 171: есть ли у телефона хоть какой-то сетевой интерфейс (LAN
     * тоже считается). Сети нет вовсе - тяжелые синхронизации каталогов
     * пропускаем: иначе каждый send офлайн жжёт до 5-10 секунд на
     * QUIC-таймаутах и копит очередь за блокировкой движка.
     */
    fun isNetworkUp(): Boolean {
        val context = appContext ?: return true
        return runCatching {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return true
            cm.activeNetwork != null
        }.getOrDefault(true)
    }

    /**
     * Строка сборки ядра («p2p_core 0.1.0 · сборка v11.70.23 · 2 брокера»)
     * для экрана «О приложении». Первая функция моста, добавленная после
     * того, как CI начал перегенерировать `p2p_core.kt` из `lib.udl`: если
     * она видна на телефоне - ядро больше не заморожено.
     */
    fun coreBuildInfo(): String = try {
        // Полное имя: одноимённая функция объекта иначе вызвала бы саму себя.
        uniffi.p2p_core.coreBuildInfo()
    } catch (e: Throwable) {
        Log.w(TAG, "coreBuildInfo failed: ${e.message}")
        "недоступно"
    }

    fun nodeId(): String? {
        // Identity is stable for an engine lifetime and is cached at start; avoid taking the
        // native mutex on every keystroke, message, file packet, or Room invalidation.
        (cachedNodeId ?: shadowNodeId)?.let { return it }
        if (isMainThread()) return null
        return engine?.let { runCatching { it.nodeId() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?.also { cachedNodeId = it }
    }

    fun publicKey(): String? {
        cachedPublicKey?.let { return it }
        if (isMainThread()) return null
        return engine?.let { runCatching { it.publicKey() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?.also { cachedPublicKey = it }
    }

    fun networkStatus(): String {
        if (isMainThread()) return cachedNetworkStatus
        val liveStatus = engine?.let { runCatching { it.networkStatus() }.getOrNull() }
        if (liveStatus != null) cachedNetworkStatus = liveStatus
        return liveStatus ?: cachedNetworkStatus
    }

    fun connectedPeers(): Long {
        if (isMainThread()) return cachedConnectedPeers
        val livePeers = engine?.let { runCatching { it.connectedPeers().toLong() }.getOrNull() }
        if (livePeers != null) cachedConnectedPeers = livePeers
        return livePeers ?: cachedConnectedPeers
    }

    fun triggerGossipDiscovery(): Boolean {
        return try {
            engine?.triggerGossipDiscovery() ?: false
        } catch (e: Exception) {
            android.util.Log.w(TAG, "triggerGossipDiscovery failed: ${e.message}")
            false
        }
    }

    /**
     * K4-1 / docs/ADDRESS_BOOK.md: «свои» для личного presence. Движок в
     * первую же минуту стучит этим узлам сохранённым адресам, а
     * онлайн-ответившие возвращают свежий адрес — азбука адресов
     * актуализируется. Возвращает сколько движок реально удержал (лимит
     * 256); ошибка или отсутствие движка — 0.
     */
    fun setPresenceAudience(ids: List<String>): UInt {
        if (ids.isEmpty()) return 0u
        return try {
            val taken = engine?.setPresenceAudience(ids) ?: 0u
            android.util.Log.i(TAG, "Presence audience: ${ids.size} passed, $taken kept")
            taken
        } catch (e: Exception) {
            android.util.Log.w(TAG, "setPresenceAudience failed: ${e.message}")
            0u
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
        // Native send may wait several seconds while holding the engine mutex. Refuse it on
        // main as a final safety net; all production callers must dispatch through IO first.
        if (rejectMainThreadCall("sendMessage(chat=$chatId)")) return false
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
        // APULAN1 - служебный сигнал «где ты в локальной сети»: адрес и порт,
        // которыми поднимается ПРЯМОЙ канал. Его разбирает транспортный слой
        // ДО расшифровки, поэтому запечатанный сигнал просто не опознаётся:
        // канал не поднимается, и связь скатывается в одну сторону.
        // Секрета в нём нет - это адрес в локальной сети.
        if (LanDirectChannel.isLanSignalText(text)) return text
        // APUUDP1 - служебные сигналы UDP-канала файловой передачи (мобильная,
        // docs/SWARM_MOBILE.md): кандидаты NAT-пробивания. Как APULAN1: адрес +
        // публичная (подписанная) привязка, секрета нет; транспортный слой
        // разбирает их до расшифровки.
        if (FileUdpWire.isUdpSignalText(text)) return text
        val context = appContext ?: return text
        val sealed = MessageSealer.seal(context, recipientId, text)
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
        if (rejectMainThreadCall("addContact")) return false
        return try {
            engine?.addContact(userId, displayName) == true
        } catch (ex: Exception) {
            Log.e(TAG, "addContact error", ex)
            false
        }
    }

    fun generateInvite(): String {
        if (rejectMainThreadCall("generateInvite")) return ""
        return engine?.generateInvite() ?: ""
    }

    fun connectViaInvite(link: String): Boolean {
        if (rejectMainThreadCall("connectViaInvite")) return false
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
    fun sendDirectPayload(recipientId: String, payload: String): Boolean {
        if (rejectMainThreadCall("sendDirectPayload")) return false
        return try {
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
    }

    /**
     * K3: бинарный кусок файла по прямому QUIC-каналу. Ядро собирает APUF-кадр
     * и уезжает им одним стримом с приоритетом данных.
     *
     * В отличие от [sendDirectPayload] содержимое НЕ запечатывается
     * MessageSealer: байты уже зашифрованы ключом передачи (AES-GCM, ключ
     * зашит в конверт получателю), двойное запечатывание кадр сломало бы.
     *
     * @param ciphertextChunkLen полная длина зашифрованного куска (с тегом)
     * @param ciphertext диапазон внутри куска, начиная с [chunkOffset]
     * @return true = получатель подтвердил приём стрима;
     *         false = недоступен напрямую (ждём следующего цикла)
     */
    fun sendFileChunk(
        recipientId: String,
        transferIdHex: String,
        chunkIndex: Long,
        chunkOffset: Int,
        ciphertextChunkLen: Int,
        ciphertext: ByteArray,
    ): Boolean {
        if (rejectMainThreadCall("sendFileChunk")) return false
        return try {
            val handle = engine ?: return false
            handle.sendFileChunk(
                recipientId,
                transferIdHex,
                chunkIndex,
                chunkOffset.toUInt(),
                ciphertextChunkLen.toUInt(),
                ciphertext,
            )
        } catch (e: Throwable) {
            // Полный catch (Throwable): мост может быть перегенерирован CI под
            // старое ядро — тогда метод физически отсутствует, и файл должен
            // уехать текстовым путём, а не ронять передачу.
            Log.w(TAG, "sendFileChunk failed: ${e.message}")
            false
        }
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
        if (rejectMainThreadCall("sendMessageMqtt")) return false
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
        if (isMainThread()) return cachedPublicKey
        return publicKey()
    }


    /**
     * Подтверждение доставки. Уходит в ФОН и не задерживает обработку.
     *
     * Раньше вызов был синхронным: при недоступном брокере он удерживал поток
     * до нескольких секунд, а очередь входящих в это время стояла. Телефон
     * показывал «Приложение не отвечает» и грелся. Отправителю всё равно, на
     * какой миллисекунде ушло подтверждение, поэтому ждать здесь нечего.
     */
    fun sendDeliveryAck(messageId: String, recipientId: String): Boolean {
        ackScope.launch {
            try {
                // р243: ДВА пути, а не один.
                //
                // Раньше подтверждение уходило только через брокера (MQTT),
                // а сообщения едут прямым каналом. Если брокер недоступен -
                // сообщение доходило, а вторая галочка не появлялась никогда:
                // ровно жалоба владельца «дошло, а двух синих галочек нет».
                //
                // Прямой канал брокера не требует, поэтому сначала он.
                val payload = "ack|$messageId"
                // Обёртка sendDirectPayload сама запечатывает полезное (см. ниже):
                // здесь только выбираем получателя.
                val direct = runCatching { sendDirectPayload(recipientId, payload) }
                    .getOrDefault(false)
                if (direct) {
                    Log.i(TAG, "direct delivery ACK sent for $messageId")
                }
                // Очередь брокера - запасной путь: она хранит подтверждение
                // до востребования, поэтому дубликат безвреден.
                engine?.sendMessageMqtt(recipientId, payload)
            } catch (e: Exception) {
                android.util.Log.w("RustBridge", "sendDeliveryAck failed: ${e.message}")
            }
        }
        return true
    }

}
