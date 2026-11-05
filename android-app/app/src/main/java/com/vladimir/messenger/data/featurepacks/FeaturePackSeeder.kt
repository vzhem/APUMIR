package com.vladimir.messenger.data.featurepacks

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.file.FileTransferReceiver
import com.vladimir.messenger.data.file.FileTransferRouter
import com.vladimir.messenger.data.file.GroupFileSeeder
import com.vladimir.messenger.data.file.OutgoingFilePreparationService
import com.vladimir.messenger.data.group.GroupDelivery
import com.vladimir.messenger.data.group.GroupWire
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.security.MessageSealer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Раздача пакетов функций (docs/FEATURE_PACKS.md). Порядок источников —
 * как решил владелец: официальный сайт в первую очередь и без
 * ограничений, рой — если сайт не отвечает или перегружен:
 *
 *  1) **Официальный сайт**: zip с `apu.clan.su/downloads`, SHA-256 — по
 *     официальному манифесту. Скачавший телефон сам становится раздающим.
 *  2) **Рой**: сосед, у которого пакет уже скачан, отдаёт его файловой
 *     машиной (виртуальное сообщество [CHAT_ID], как рой APK); телефон
 *     получает пакет и сам становится раздающим.
 *  3) **Другие соседи**: объявления `fpk` собираются от всех, просьба
 *     `fpwant` уходит следующему, если первый отказал (`fpnone`).
 *
 * Целостность: пакет принимается только при совпадении SHA-256 с
 * объявлением соседа или официальным манифестом. Ранги не участвуют:
 * раздавать и получать может каждый телефон.
 */
@Singleton
class FeaturePackSeeder @Inject constructor(
    @ApplicationContext context: Context,
    private val transferDao: FileTransferDao,
    private val peerStore: FileExchangePeerStore,
    private val delivery: GroupDelivery,
    private val router: FileTransferRouter,
    private val preparation: Provider<OutgoingFilePreparationService>,
) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** Фаза пакета на этом телефоне — для раздела «Дополнительные функции». */
    enum class Phase { NOT_INSTALLED, ASKING_SWARM, DOWNLOADING, OFFICIAL_DOWNLOAD, INSTALLED, FAILED }

    data class PackState(
        val packId: String,
        val phase: Phase,
        val receivedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        /** Человекочитаемая причина отказа (фаза FAILED). */
        val failure: String = "",
        /** Скольким соседям этот телефон уже отдал пакет целиком. */
        val served: Int = 0,
    )

    private val _states = MutableStateFlow<Map<String, PackState>>(emptyMap())
    val states: StateFlow<Map<String, PackState>> = _states.asStateFlow()

    /** Объявления соседей: packId -> nodeId -> (sha256, размер, когда). */
    private data class Offer(val nodeId: String, val sha256: String, val sizeBytes: Long, val atMs: Long)

    private val offers = HashMap<String, LinkedHashMap<String, Offer>>()
    private val triedSeeds = HashSet<String>()
    @Volatile private var officialTriedFor: String? = null
    /** Когда ушли в рой после молчания сайта; помпа отсчитывает ожидание от неё. */
    @Volatile private var swarmStartedAtMs = 0L
    @Volatile private var lastAnnounceAtMs = 0L

    private fun packDir(): File = File(appContext.filesDir, "feature_packs").apply { mkdirs() }

    private fun packFile(pack: FeaturePack, abi: String): File =
        File(packDir(), pack.downloadFileName(abi))

    init {
        scope.launch { runCatching { restoreFromDisk() } }
    }

    private suspend fun restoreFromDisk() {
        val abi = FeaturePackStore.deviceAbi()
        val restored = HashMap<String, PackState>()
        for (pack in FeaturePackCatalog.packs) {
            val installed = FeaturePackStore.installed(appContext, pack.id)
            if (installed != null && installed.abi == abi && packFile(pack, abi).isFile) {
                restored[pack.id] = PackState(pack.id, Phase.INSTALLED, totalBytes = installed.sizeBytes)
            }
        }
        FeaturePackStore.pending(appContext)?.let { pending ->
            if (restored[pending.packId]?.phase != Phase.INSTALLED) {
                restored[pending.packId] = PackState(pending.packId, Phase.ASKING_SWARM)
            }
        }
        mutex.withLock { _states.value = restored }
    }

    private fun setState(state: PackState) {
        _states.value = _states.value + (state.packId to state)
    }

    fun stateFor(packId: String): PackState =
        _states.value[packId] ?: PackState(packId, Phase.NOT_INSTALLED)

    // ── Просьба: сначала рой, потом официальный сайт ──────────────────────

    /**
     * Владелец включил пакет: сначала официальный сайт (без ограничений).
     * Если сайт не отвечает или перегружен — просим у роя; соседей без
     * пакета помпа за [SWARM_WAIT_MS] честно переводит в «не удалось».
     */
    suspend fun request(packId: String) {
        val pack = FeaturePackCatalog.pack(packId) ?: return
        val abi = FeaturePackStore.deviceAbi()
        FeaturePackStore.installed(appContext, packId)?.let { installed ->
            if (installed.abi == abi && packFile(pack, abi).isFile) {
                setState(PackState(packId, Phase.INSTALLED, totalBytes = installed.sizeBytes))
                return
            }
        }
        mutex.withLock {
            triedSeeds.clear()
            officialTriedFor = packId
            FeaturePackStore.savePending(
                appContext,
                FeaturePackStore.Pending(packId, abi, offers[packId]?.values?.firstOrNull()?.sha256.orEmpty(), System.currentTimeMillis()),
            )
            setState(PackState(packId, Phase.OFFICIAL_DOWNLOAD, totalBytes = pack.sizeBytesFor(abi)))
        }
        // Сайт — в первую очередь; рой подключится сам, если сайт не ответит.
        scope.launch { downloadFromOfficial(pack, abi, fallbackToSwarm = true) }
    }

    /** Отмена текущей догрузки (установленный пакет не трогаем). */
    suspend fun cancel(packId: String) {
        mutex.withLock {
            if (FeaturePackStore.pending(appContext)?.packId == packId) {
                FeaturePackStore.clearPending(appContext)
            }
            if (stateFor(packId).phase != Phase.INSTALLED) {
                setState(PackState(packId, Phase.NOT_INSTALLED))
            }
        }
    }

    private suspend fun askSwarm(pack: FeaturePack, abi: String) {
        val targets = runCatching { RustBridge.liveSwarmPeerIds() }.getOrDefault(emptyList()).take(ASK_FANOUT)
        if (targets.isNotEmpty()) {
            runCatching {
                delivery.deliver(CHAT_ID, GroupWire.buildFeaturePackAsk(pack.id, abi), targets)
            }.onFailure { Log.w(TAG, "pack ask fan-out failed: ${it.message}") }
        }
        Log.i(TAG, "pack ${pack.id}: asked ${targets.size} node(s), known offers=${offers[pack.id]?.size ?: 0}")
        // Объявление уже приходило раньше — не ждём новых, просим сразу.
        offers[pack.id]?.values?.firstOrNull { it.nodeId !in triedSeeds }?.let { offer ->
            sendWant(offer, pack, abi)
        }
    }

    private suspend fun sendWant(offer: Offer, pack: FeaturePack, abi: String) {
        mutex.withLock {
            triedSeeds += offer.nodeId
            val pending = FeaturePackStore.pending(appContext)
            if (pending != null && pending.packId == pack.id && pending.sha256 != offer.sha256) {
                FeaturePackStore.savePending(appContext, pending.copy(sha256 = offer.sha256))
            }
            setState(PackState(pack.id, Phase.ASKING_SWARM, totalBytes = offer.sizeBytes))
        }
        val binding = runCatching { FileExchangeKeyStore.publicBinding(appContext) }.getOrNull() ?: ByteArray(0)
        runCatching {
            delivery.deliver(
                CHAT_ID,
                GroupWire.buildFeaturePackWant(pack.id, abi, offer.sha256, binding),
                listOf(offer.nodeId),
            )
        }.onFailure { Log.w(TAG, "pack want failed: ${it.message}") }
        Log.i(TAG, "pack ${pack.id}: want -> ${offer.nodeId.takeLast(8)} sha=${offer.sha256.take(12)}")
    }

    // ── Входящие пакеты роя ───────────────────────────────────────────────

    /** Сосед объявил: «пакет у меня есть». Запоминаем и, если ждём, просим. */
    suspend fun onFeaturePackHave(senderId: String, packet: GroupWire.Packet.FeaturePackHave) {
        if (packet.abi != FeaturePackStore.deviceAbi()) return
        if (FeaturePackCatalog.pack(packet.packId) == null) return
        val installed = FeaturePackStore.installed(appContext, packet.packId)
        if (installed != null && installed.sha256 == packet.sha256) return
        mutex.withLock {
            val byNode = offers.getOrPut(packet.packId) { LinkedHashMap() }
            byNode[senderId] = Offer(senderId, packet.sha256, packet.sizeBytes, packet.atMs)
            if (byNode.size > MAX_OFFERS_PER_PACK) {
                byNode.values.sortedBy { it.atMs }.take(byNode.size - MAX_OFFERS_PER_PACK)
                    .forEach { byNode.remove(it.nodeId) }
            }
        }
        Log.i(TAG, "pack ${packet.packId}: offer from ${senderId.takeLast(8)} (${packet.sizeBytes} B)")
        val pending = FeaturePackStore.pending(appContext)
        if (pending != null && pending.packId == packet.packId && stateFor(packet.packId).phase == Phase.ASKING_SWARM) {
            val pack = FeaturePackCatalog.pack(packet.packId) ?: return
            sendWant(Offer(senderId, packet.sha256, packet.sizeBytes, packet.atMs), pack, packet.abi)
        }
    }

    /** Сосед просит пакет у нас: отдаём, если он у нас установлен. */
    suspend fun onFeaturePackWant(senderId: String, packet: GroupWire.Packet.FeaturePackWant) {
        val pack = FeaturePackCatalog.pack(packet.packId) ?: return
        val installed = FeaturePackStore.installed(appContext, packet.packId)
        if (installed == null || installed.abi != packet.abi ||
            (packet.sha256.isNotEmpty() && packet.sha256 != installed.sha256)
        ) {
            replyNone(packet.packId, packet.abi, packet.sha256.ifEmpty { installed?.sha256.orEmpty() }, senderId)
            return
        }
        if (packet.binding.isNotEmpty()) {
            runCatching {
                check(uniffi.p2p_core.verifyFileExchangeBinding(packet.binding)) { "bad signature" }
                check(uniffi.p2p_core.fileExchangeBindingNodeId(packet.binding) == senderId) { "binding of another node" }
                peerStore.pinFirstSeen(packet.binding, System.currentTimeMillis())
                MessageSealer.remember(appContext, senderId, packet.binding)
            }.onFailure { Log.w(TAG, "pack requester binding not pinned: ${it.message}") }
        }
        scope.launch {
            val source = seedSourceRow(installed.sha256)
            if (source == null) {
                replyNone(packet.packId, packet.abi, installed.sha256, senderId)
                return@launch
            }
            when (router.groupSeeder.offer(source, senderId)) {
                GroupFileSeeder.OfferResult.SENT ->
                    Log.i(TAG, "pack ${packet.packId} offered to ${senderId.takeLast(8)}")
                GroupFileSeeder.OfferResult.NO_KEY -> {
                    router.requestExchangeBinding(senderId)
                    replyNone(packet.packId, packet.abi, installed.sha256, senderId)
                }
                GroupFileSeeder.OfferResult.UNREACHABLE,
                GroupFileSeeder.OfferResult.BUSY ->
                    replyNone(packet.packId, packet.abi, installed.sha256, senderId)
            }
        }
    }

    /** Сосед отказал: пробуем следующего из объявлений. */
    suspend fun onFeaturePackNone(senderId: String, packet: GroupWire.Packet.FeaturePackNone) {
        val pending = FeaturePackStore.pending(appContext) ?: return
        if (pending.packId != packet.packId) return
        val pack = FeaturePackCatalog.pack(packet.packId) ?: return
        mutex.withLock { triedSeeds += senderId }
        offers[packet.packId]?.values?.firstOrNull { it.nodeId !in triedSeeds && it.sha256 == packet.sha256 }
            ?.let { sendWant(it, pack, packet.abi) }
    }

    /** Сосед спрашивает «у тебя есть пакет?»: отвечаем объявлением лично ему. */
    suspend fun onFeaturePackAsk(senderId: String, packet: GroupWire.Packet.FeaturePackAsk) {
        val installed = FeaturePackStore.installed(appContext, packet.packId) ?: return
        if (installed.abi != packet.abi) return
        runCatching {
            delivery.deliver(
                CHAT_ID,
                GroupWire.buildFeaturePackHave(
                    installed.packId, installed.abi, installed.sha256,
                    installed.sizeBytes, System.currentTimeMillis(),
                ),
                listOf(senderId),
            )
        }.onFailure { Log.w(TAG, "pack ask reply failed: ${it.message}") }
    }

    private suspend fun replyNone(packId: String, abi: String, sha256: String, to: String) {
        if (sha256.isEmpty()) return
        runCatching {
            delivery.deliver(CHAT_ID, GroupWire.buildFeaturePackNone(packId, abi, sha256), listOf(to))
        }.onFailure { Log.w(TAG, "pack none reply failed: ${it.message}") }
    }

    // ── Приём файловой машиной: куда легла пришедшая копия ────────────────

    /**
     * Маршрут предложения файла от соседа: наш пакет — в виртуальное
     * сообщество [CHAT_ID]; уже установленный — дубликат; чужое — не к нам.
     */
    suspend fun routePackOffer(senderId: String, fileSha256: String): FileTransferReceiver.OfferRouting {
        FeaturePackStore.installed(appContext, pendingPackFor(fileSha256))
            ?.takeIf { it.sha256 == fileSha256 }
            ?.let { return FileTransferReceiver.OfferRouting.Duplicate(CHAT_ID) }
        val pending = FeaturePackStore.pending(appContext) ?: return FileTransferReceiver.OfferRouting.Unknown
        if (pending.sha256.isEmpty() || pending.sha256 == fileSha256) {
            if (pending.sha256.isEmpty()) {
                FeaturePackStore.savePending(appContext, pending.copy(sha256 = fileSha256))
            }
            return FileTransferReceiver.OfferRouting.Chat(CHAT_ID)
        }
        return FileTransferReceiver.OfferRouting.Unknown
    }

    private fun pendingPackFor(sha256: String): String =
        FeaturePackStore.pending(appContext)?.takeIf { it.sha256 == sha256 }?.packId.orEmpty()

    /**
     * Файл пакета дошёл целиком: сверяем с тем, что ждали, переселяем в
     * каталог пакетов и становимся раздающими для соседей.
     */
    suspend fun onFileReceived(chatId: String, senderId: String, fileSha256: String): Boolean {
        if (chatId != CHAT_ID) return false
        val pending = FeaturePackStore.pending(appContext) ?: return false
        if (pending.sha256.isNotEmpty() && pending.sha256 != fileSha256) return false
        val pack = FeaturePackCatalog.pack(pending.packId) ?: return false
        val row = transferDao.getForFile(CHAT_ID, fileSha256)
            .firstOrNull { it.direction == "INCOMING" && it.state == "COMPLETE" } ?: return false
        val received = router.receivedFileFor(row) ?: return false
        val installedFile = installVerifiedCopy(pack, pending.abi, received, fileSha256) ?: run {
            setState(PackState(pack.id, Phase.FAILED, failure = "checksum"))
            FeaturePackStore.clearPending(appContext)
            return true
        }
        FeaturePackStore.saveInstalled(
            appContext,
            FeaturePackStore.Installed(pack.id, pending.abi, fileSha256, installedFile.length()),
        )
        FeaturePackRuntime.extract(appContext, pack, pending.abi, installedFile)
        FeaturePackStore.clearPending(appContext)
        setState(PackState(pack.id, Phase.INSTALLED, totalBytes = installedFile.length()))
        Log.i(TAG, "pack ${pack.id} installed from swarm (${installedFile.length()} B), now seeding")
        announceInstalled()
        return true
    }

    private suspend fun installVerifiedCopy(pack: FeaturePack, abi: String, source: File, expectedSha256: String): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                check(sha256OfFile(source) == expectedSha256) { "pack checksum mismatch" }
                val target = packFile(pack, abi)
                val temporary = File(packDir(), target.name + ".tmp")
                source.copyTo(temporary, overwrite = true)
                check(sha256OfFile(temporary) == expectedSha256) { "pack copy checksum mismatch" }
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                target
            }.getOrNull()
        }

    // ── Официальный сайт: первый источник, рой — запасной ─────────────────

    /**
     * Помпа раздела: показывает прогресс передачи от соседа и честно
     * завершает просьбу, если рой молчит дольше [SWARM_WAIT_MS]
     * (официальный сайт к этому моменту уже пробовали первым).
     */
    suspend fun pump() {
        val pending = FeaturePackStore.pending(appContext)
        if (pending != null) {
            val pack = FeaturePackCatalog.pack(pending.packId)
            if (pack != null) {
                val row = if (pending.sha256.isNotEmpty()) {
                    transferDao.getForFile(CHAT_ID, pending.sha256)
                        .firstOrNull { it.direction == "INCOMING" && it.state != "COMPLETE" }
                } else {
                    null
                }
                val swarmSince = if (swarmStartedAtMs > 0L) swarmStartedAtMs else pending.startedAtMs
                when {
                    row != null -> setState(
                        PackState(pack.id, Phase.DOWNLOADING, row.transferredBytes, row.totalBytes),
                    )
                    stateFor(pack.id).phase == Phase.ASKING_SWARM &&
                        System.currentTimeMillis() - swarmSince > SWARM_WAIT_MS -> {
                        FeaturePackStore.clearPending(appContext)
                        setState(PackState(pack.id, Phase.FAILED, failure = "swarm timeout"))
                        Log.i(TAG, "pack ${pack.id}: swarm did not answer in time")
                    }
                }
            }
        }
        announceInstalled()
    }

    private suspend fun downloadFromOfficial(pack: FeaturePack, abi: String, fallbackToSwarm: Boolean = false) {
        setState(PackState(pack.id, Phase.OFFICIAL_DOWNLOAD, totalBytes = pack.sizeBytesFor(abi)))
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val manifest = httpGetText(MANIFEST_URL) ?: error("manifest unavailable")
                val entries = JSONObject(manifest).optJSONArray("packs") ?: error("manifest has no packs")
                var entry: JSONObject? = null
                for (i in 0 until entries.length()) {
                    val candidate = entries.optJSONObject(i) ?: continue
                    if (candidate.optString("id") == pack.id && candidate.optString("abi") == abi) {
                        entry = candidate
                        break
                    }
                }
                val found = entry ?: error("pack not published on official site yet")
                val sha = found.optString("sha256")
                val fileName = found.optString("file").ifEmpty { pack.downloadFileName(abi) }
                check(GroupWire.isSha256(sha)) { "manifest checksum missing" }
                val temporary = File(packDir(), "$fileName.download")
                httpDownloadTo("$DOWNLOADS_BASE/$fileName", temporary) ?: error("download failed")
                check(sha256OfFile(temporary) == sha) { "official checksum mismatch" }
                val target = packFile(pack, abi)
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                sha to target
            }
        }
        result.onSuccess { (sha, target) ->
            FeaturePackStore.saveInstalled(
                appContext,
                FeaturePackStore.Installed(pack.id, abi, sha, target.length()),
            )
            FeaturePackRuntime.extract(appContext, pack, abi, target)
            FeaturePackStore.clearPending(appContext)
            setState(PackState(pack.id, Phase.INSTALLED, totalBytes = target.length()))
            // Скачал с сайта — стань раздающим для роя.
            scope.launch { runCatching { prepareSeedCopy(pack, abi, target, sha) } }
            announceInstalled()
            Log.i(TAG, "pack ${pack.id} installed from official site (${target.length()} B)")
        }.onFailure { error ->
            Log.w(TAG, "pack ${pack.id} official download failed: ${error.message}")
            if (fallbackToSwarm) {
                // Сайт не отвечает или перегружен — спрашиваем рой.
                swarmStartedAtMs = System.currentTimeMillis()
                setState(PackState(pack.id, Phase.ASKING_SWARM, totalBytes = pack.sizeBytesFor(abi)))
                scope.launch { askSwarm(pack, abi) }
            } else {
                FeaturePackStore.clearPending(appContext)
                setState(PackState(pack.id, Phase.FAILED, failure = error.message ?: "official download failed"))
            }
        }
    }

    // ── Раздача: своя копия для файловой машины ───────────────────────────

    private suspend fun seedSourceRow(sha256: String) =
        transferDao.getForFile(CHAT_ID, sha256).firstOrNull { row ->
            router.groupSeeder.canSeed(row, router::hasTransferKey)
        } ?: run {
            // Официальная копия лежит файлом, но общей копии ещё нет: строим.
            val installed = FeaturePackCatalog.packs
                .firstOrNull { FeaturePackStore.installed(appContext, it.id)?.sha256 == sha256 }
                ?.let { pack -> pack to FeaturePackStore.installed(appContext, pack.id)!! }
            if (installed != null) {
                val (pack, record) = installed
                prepareSeedCopy(pack, record.abi, packFile(pack, record.abi), record.sha256)
                transferDao.getForFile(CHAT_ID, sha256).firstOrNull { row ->
                    router.groupSeeder.canSeed(row, router::hasTransferKey)
                }
            } else {
                null
            }
        }

    private suspend fun prepareSeedCopy(pack: FeaturePack, abi: String, file: File, sha256: String) {
        preparation.get().prepareGroupCopy(
            source = file,
            displayName = pack.downloadFileName(abi),
            mediaType = "application/zip",
            messageId = CHAT_ID,
            groupId = CHAT_ID,
            expectedSha256 = sha256,
        )
    }

    private var announceMutex = Mutex()

    private suspend fun announceInstalled() {
        val now = System.currentTimeMillis()
        if (now - lastAnnounceAtMs < ANNOUNCE_INTERVAL_MS) return
        announceMutex.withLock {
            if (now - lastAnnounceAtMs < ANNOUNCE_INTERVAL_MS) return
            lastAnnounceAtMs = now
        }
        val abi = FeaturePackStore.deviceAbi()
        val installed = FeaturePackCatalog.packs.mapNotNull { pack ->
            FeaturePackStore.installed(appContext, pack.id)?.takeIf { it.abi == abi }
        }
        if (installed.isEmpty()) return
        val targets = runCatching { RustBridge.liveSwarmPeerIds() }.getOrDefault(emptyList()).take(ASK_FANOUT)
        if (targets.isEmpty()) return
        for (record in installed) {
            runCatching {
                delivery.deliver(
                    CHAT_ID,
                    GroupWire.buildFeaturePackHave(record.packId, record.abi, record.sha256, record.sizeBytes, now),
                    targets,
                )
            }.onFailure { Log.w(TAG, "pack announce failed: ${it.message}") }
        }
    }

    // ── HTTP официального сайта (без библиотек, как остальной проект) ────

    private fun httpGetText(url: String): String? = openConnection(url)?.let { connection ->
        try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpDownloadTo(url: String, target: File): Boolean {
        val connection = openConnection(url) ?: return false
        return try {
            if (connection.responseCode !in 200..299) return false
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "APU Android feature packs")
        }
    }.getOrNull()

    companion object {
        private const val TAG = "FeaturePackSeeder"

        /** Виртуальное сообщество файловой машины для пакетов (как `apkseed`). */
        const val CHAT_ID = "packseed"

        /** Официальный сайт: манифест пакетов и сами zip рядом с APK. */
        const val DOWNLOADS_BASE = "https://apu.clan.su/downloads"
        const val MANIFEST_URL = "$DOWNLOADS_BASE/feature-packs.json"

        /** Сколько соседей спрашиваем о пакете одним веером. */
        private const val ASK_FANOUT = 8
        private const val MAX_OFFERS_PER_PACK = 16

        /** Рой (запасной после сайта) ждём столько, дальше — честный отказ. */
        private const val SWARM_WAIT_MS = 90_000L

        /** Объявления «пакет у меня» повторяем не чаще раза в 10 минут. */
        private const val ANNOUNCE_INTERVAL_MS = 10 * 60_000L

        private const val HTTP_TIMEOUT_MS = 20_000
    }
}

/** SHA-256 файла пакета: сверка с объявлением роя и официальным манифестом. */
internal fun sha256OfFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
