package com.vladimir.messenger.data.update

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import com.vladimir.messenger.data.file.FileTransferReceiver
import com.vladimir.messenger.data.file.FileTransferRouter
import com.vladimir.messenger.data.file.GroupFileSeeder
import com.vladimir.messenger.data.file.OutgoingFilePreparationService
import com.vladimir.messenger.data.group.GroupDelivery
import com.vladimir.messenger.data.group.GroupWire
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.security.MessageSealer
import com.vladimir.messenger.data.swarm.SwarmPeerDirectory
import com.vladimir.messenger.service.UpdateChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

/**
 * Раздача обновления APU (APK) роем (docs/UPDATE_SEEDING.md).
 *
 * APK — «файл группы» в виртуальном сообществе [ApkUpdate.CHAT_ID]: общий
 * манифест `grp_apkseed` (ядро, K2), один ключ и одни зашифрованные куски;
 * любой телефон с файлом целиком — сид. Файловая машина (манифест,
 * конверты, куски, ACK, инвентарь, [GroupFileSeeder]) не трогается: здесь
 * только тонкий слой — кто что раздаёт, кто просит, и карточка в настройках.
 *
 * Куски APK идут только по прямому каналу (LAN/QUIC), как у файлов групп:
 * брокер несёт лишь сигнальные пакеты `upk`/`upwant`/`upnone`
 * ([GroupWire]). Старые телефоны эти виды не знают и молча отбрасывают.
 *
 * Здесь две роли одного телефона:
 *  - СИД: [markAsSeed] / [markReceivedApkAsSeed] помечают файл (проверка
 *    «это APK», версия новее текущей, sha256, общая копия), [onUpdateWant]
 *    отвечает просителю общим манифестом и конвертом под его ключ;
 *  - проситель: [onUpdatePack] складывает объявления соседей (только
 *    версии новее моей), [requestUpdate] начинает круг просьб `upwant`,
 *    [pump] повторяет и меняет сидов, [onUpdateNone] — следующий сид.
 *
 * Скачанное обновление становится сидом СРАЗУ (строка `INCOMING/COMPLETE`
 * — куски и ключ у телефона остались, перешифровки нет): новая версия
 * расходится по сети без человека. После установки и перезапуска
 * [maybeAutoReseed] подтверждает раздачу той же строкой.
 */
@Singleton
class ApkSeeder @Inject constructor(
    @ApplicationContext context: Context,
    private val transferDao: FileTransferDao,
    private val groupDao: GroupDao,
    private val contactDao: ContactDao,
    private val peerStore: FileExchangePeerStore,
    private val delivery: GroupDelivery,
    private val directory: SwarmPeerDirectory,
    private val router: FileTransferRouter,
    private val preparation: Provider<OutgoingFilePreparationService>,
    private val updateChecker: UpdateChecker,
) {
    private val appContext: Context = context.applicationContext
    private val seedRoot: File = File(appContext.noBackupFilesDir, ApkUpdate.DIR_NAME)
    private val store = ApkUpdateStore(seedRoot)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    // ── Состояние для настроек ──────────────────────────────────────────────

    /** Моя раздача: версия, файл, сколько получили. */
    data class SeedUi(
        val version: String,
        val name: String,
        val sizeBytes: Long,
        val transferId: String?,
        val served: Int,
        val preparing: Boolean,
    )

    /** Объявление соседа (только версии новее моей). */
    data class Offer(
        val nodeId: String,
        val version: String,
        val sha256: String,
        val sizeBytes: Long,
        val atMs: Long,
    )

    /** Приём обновления: ход по строке передачи. */
    data class Download(
        val version: String,
        val receivedBytes: Long,
        val totalBytes: Long,
    )

    /** Обновление скачано: можно ставить и уже раздаём. */
    data class Ready(
        val version: String,
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
        val transferId: String,
        /**
         * Локальный файл, если он не из строки передачи (официальная загрузка,
         * взятая в раздел автоматически): установка по нему, раздача — по копии.
         */
        val sourcePath: String? = null,
    )

    /** Принятый в чат APK-файл (кандидат на раздачу). */
    data class ReceivedApk(
        val transferId: String,
        val chatId: String,
        val fromNodeId: String,
        val displayName: String,
        val sizeBytes: Long,
        val atMs: Long,
        /** Версия из самого APK (или из имени файла); null — попросить у человека. */
        val versionGuess: String?,
    )

    private val _seed = MutableStateFlow<SeedUi?>(null)
    val seed: StateFlow<SeedUi?> = _seed.asStateFlow()

    private val _offers = MutableStateFlow<List<Offer>>(emptyList())
    val offers: StateFlow<List<Offer>> = _offers.asStateFlow()

    private val _download = MutableStateFlow<Download?>(null)
    val download: StateFlow<Download?> = _download.asStateFlow()

    private val _ready = MutableStateFlow<Ready?>(null)
    val ready: StateFlow<Ready?> = _ready.asStateFlow()

    private val _receivedApks = MutableStateFlow<List<ReceivedApk>>(emptyList())
    val receivedApks: StateFlow<List<ReceivedApk>> = _receivedApks.asStateFlow()

    // ── Моя просьба о новой версии ──────────────────────────────────────────

    private class PendingAsk(val version: String, val sha256: String, val startedAtMs: Long) {
        var askedSeed: String? = null
        var askedAtMs: Long = 0L
        var attempts: Int = 0
        /** Кого уже спрашивали в этом круге: следующий — другой сид. */
        val tried = LinkedHashSet<String>()
        /** Кто уже шлёт полосы (предложение принято приёмником, K2). */
        val striping = LinkedHashSet<String>()
    }

    @Volatile private var pending: PendingAsk? = null
    @Volatile private var initDone = false
    @Volatile private var lastAnnounceAtMs = 0L
    @Volatile private var lastReceivedScanAtMs = 0L

    /**
     * Скачался наш файл обновления (DownloadManager шлёт ACTION_DOWNLOAD_COMPLETE):
     * забираем его в раздел «Обновления» сами — версия читается из APK, карточка
     * «Обновить до vX» появляется сама, раздача соседям начинается сама.
     * Если процесс был мёртв в момент завершения — догоним сканом при старте
     * ([adoptCompletedDownloads]) и при открытии настроек.
     */
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            scope.launch { runCatching { adoptCompletedDownloads() } }
        }
    }

    init {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Системная рассылка DownloadManager: NOT_EXPORTED её получает.
                appContext.registerReceiver(
                    downloadReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
        }.onFailure { Log.w(TAG, "download receiver register failed: ${it.message}") }
        scope.launch {
            runCatching { ensureInit() }.onFailure { Log.w(TAG, "init failed: ${it.message}") }
        }
    }

    /** Восстановить состояние с диска; не дороже одного раза (повтор при сбое). */
    private suspend fun ensureInit() {
        if (initDone) return
        mutex.withLock {
            if (initDone) return@withLock
            initFromDisk()
            initDone = true
        }
    }

    // ── Сид: пометить раздачу ───────────────────────────────────────────────

    /**
     * Раздавать файл [source] как версию [rawVersion]. Проверяет: файл —
     * APK; версия разбирается и НОВЕЕ текущей; sha256. Копирует файл в
     * [seedRoot], строит общую копию (`OUTGOING/SEEDING`) и объявляет
     * соседям `upk`. Более старая помеченная версия заменяется (стирается).
     * [displayName] — настоящее имя файла (из SAF/проводника), чтобы в
     * карточке и объявлении не оказалось служебного имени временной копии.
     * Возвращает текст для карточки: пусто — ок.
     */
    suspend fun markAsSeed(
        source: File,
        rawVersion: String,
        autoReseed: Boolean,
        displayName: String? = null,
    ): String {
        val version = ApkUpdate.normalize(rawVersion)
        if (ApkUpdate.parseVersion(version) == null) {
            return "Версия не распознана. Укажите числовую, например 11.70.29"
        }
        if (!ApkUpdate.isNewer(version, currentAppVersion())) {
            return "Это не новая версия: текущая ${ApkUpdate.normalize(currentAppVersion())}"
        }
        if (!ApkUpdate.looksLikeApk(source)) {
            return "Файл не похож на APK (нет AndroidManifest.xml или classes.dex)"
        }
        val size = source.length()
        if (size > GroupWire.MAX_UPDATE_SIZE_BYTES) {
            return "Файл больше 4 ГБ — это не APK обновления"
        }
        return startSeeding(source, version, size, displayName?.takeIf { it.isNotBlank() } ?: source.name, autoReseed)
    }

    /**
     * Общий хвост пометки раздачи: sha256, копия в [seedRoot], общая копия
     * кусков (`prepareGroupCopy`), запись о раздаче, объявление `upk`.
     * Сюда приходят и ручная пометка ([markAsSeed]), и автоматический забор
     * скачанного файла ([adoptDownloadedApk]).
     */
    private suspend fun startSeeding(
        source: File,
        version: String,
        size: Long,
        name: String,
        autoReseed: Boolean,
    ): String {
        val sha = ApkUpdate.sha256OfFile(source)
        replaceSeedIfDifferent(sha)
        // Копия: исходный файл — чужой (из чата, из папок), раздаём свою.
        val copy = File(seedRoot, "$sha.apk")
        val temporary = File(seedRoot, "$sha.apk.tmp")
        check(seedRoot.mkdirs() || seedRoot.isDirectory) { "Cannot create seed directory" }
        try {
            Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(temporary.toPath(), copy.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            temporary.delete()
            return "Не удалось скопировать файл: ${error.message}"
        }
        _seed.value = SeedUi(version, name, size, null, 0, preparing = true)
        return mutex.withLock {
            val prepared = try {
                preparation.get().prepareGroupCopy(
                    source = copy,
                    displayName = name,
                    mediaType = "application/vnd.android.package-archive",
                    messageId = ApkUpdate.CHAT_ID,
                    groupId = ApkUpdate.CHAT_ID,
                    expectedSha256 = sha,
                )
            } catch (error: Exception) {
                Log.w(TAG, "seed prepare failed for $sha: ${error.message}")
                null
            }
            if (prepared == null) {
                _seed.value = null
                "Ядро не умеет общие манифесты — обновите приложение"
            } else {
                store.saveSeed(
                    ApkUpdateStore.SeedInfo(version, sha, size, name, System.currentTimeMillis(), autoReseed),
                )
                _seed.value = SeedUi(version, name, size, prepared.transferId, 0, preparing = false)
                Log.i(TAG, "seeding update v$version sha=${sha.take(12)} transfer=${prepared.transferId}")
                scope.launch { runCatching { announce() } }
                ""
            }
        }
    }

    /**
     * Раздавать УЖЕ принятый APK (из «Полученных»): строка `INCOMING/COMPLETE`
     * подходит как есть — куски и ключ у телефона остались, перешифровки нет.
     * [rawVersion] — версия для объявления (угадай из имени или введи).
     */
    suspend fun markReceivedApkAsSeed(transferId: String, rawVersion: String, autoReseed: Boolean): String {
        val version = ApkUpdate.normalize(rawVersion)
        if (ApkUpdate.parseVersion(version) == null) {
            return "Версия не распознана. Укажите числовую, например 11.70.29"
        }
        if (!ApkUpdate.isNewer(version, currentAppVersion())) {
            return "Это не новая версия: текущая ${ApkUpdate.normalize(currentAppVersion())}"
        }
        val row = transferDao.getTransfer(transferId) ?: return "Файл не найден"
        if (row.direction != "INCOMING" || row.state != "COMPLETE") return "Файл ещё не скачан целиком"
        if (!row.displayName.lowercase().endsWith(".apk")) return "Это не APK"
        val file = router.receivedFileFor(row) ?: return "Файл на месте не найден"
        replaceSeedIfDifferent(row.fileSha256)
        store.saveSeed(
            ApkUpdateStore.SeedInfo(version, row.fileSha256, row.totalBytes, row.displayName, System.currentTimeMillis(), autoReseed),
        )
        _seed.value = SeedUi(version, row.displayName, row.totalBytes, row.transferId, router.groupSeeder.servedCount(row.transferId), preparing = false)
        Log.i(TAG, "seeding received update v$version sha=${row.fileSha256.take(12)} transfer=${row.transferId}")
        scope.launch { runCatching { announce() } }
        return ""
    }

    /** Остановить раздачу: объявление и копия долой. */
    suspend fun unmark() {
        val info = store.loadSeed() ?: run {
            _seed.value = null
            return
        }
        dropSeedCopy(info)
        store.saveSeed(null)
        _seed.value = null
        Log.i(TAG, "unmarked update seed v${info.version}")
    }

    // ── Автоматический забор скачанного файла (docs/UPDATE_SEEDING.md) ──────

    /**
     * Версия, вшитая в сам APK (`versionName` из AndroidManifest). Это
     * надёжнее угадывания по имени файла: человек не вводит ничего.
     * null — архив не читается (не APK или манифест не разобран).
     */
    private fun archiveVersion(file: File): String? = runCatching {
        val info = appContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        info?.versionName?.takeIf { version -> version.isNotBlank() }
    }.getOrNull()

    /**
     * Скачанный файл обновления сам встаёт в раздел «Обновления»:
     * версия читается из APK (fallback — из имени файла), файл проверяется,
     * ставится в раздачу соседям и появляется карточка «Обновить до vX»
     * (если версия новее текущей). Если версия РАВНА текущей (файл поставили,
     * а раздел не успел его взять) — файл просто становится раздачей:
     * та же авто-привязка, что и после установки. Возвращает текст ошибки:
     * пусто — ок.
     */
    suspend fun adoptDownloadedApk(file: File, displayName: String? = null): String {
        runCatching { ensureInit() }.onFailure { Log.w(TAG, "init failed: ${it.message}") }
        if (!file.isFile) return "Файл не найден"
        val name = displayName?.takeIf { it.isNotBlank() } ?: file.name
        // Уже брали этот файл (путь и размер совпадают) — не считаем sha256
        // большого файла зря при каждом старте. Туда же попадают и окончательные
        // отказы (не APK, версия не определена, старее текущей): они не станут
        // браться позже, а пересчитывать их каждые 5 минут не нужно.
        val already = store.loadAdopted().any { it.path == file.absolutePath && it.sizeBytes == file.length() }
        if (already) return ""
        if (!ApkUpdate.looksLikeApk(file)) return "Файл не похож на APK (нет AndroidManifest.xml или classes.dex)"
        val size = file.length()
        if (size > GroupWire.MAX_UPDATE_SIZE_BYTES) return "Файл больше 4 ГБ — это не APK обновления"
        val sha = ApkUpdate.sha256OfFile(file)
        fun forget(message: String): String {
            store.addAdopted(
                ApkUpdateStore.Adopted(file.absolutePath, name, sha, size, System.currentTimeMillis()),
            )
            return message
        }
        val existingSeed = store.loadSeed()
        if (existingSeed?.sha256 == sha) {
            // Уже раздаём именно этот файл — просто помним, что взяли его.
            return forget("")
        }
        val rawVersion = archiveVersion(file)
            ?: ApkUpdate.versionFromName(name)
            ?: return forget("Не удалось определить версию файла")
        val version = ApkUpdate.normalize(rawVersion)
        if (ApkUpdate.parseVersion(version) == null) return forget("Версия не распознана: $rawVersion")
        val current = ApkUpdate.normalize(currentAppVersion())
        val comparison = ApkUpdate.compareVersions(version, current)
        when {
            comparison == null -> return forget("Версию $version не с чем сравнить (текущая $current)")
            comparison < 0 -> return forget("Файл старее текущей версии ($current) — обновлением не станет")
        }
        if (existingSeed != null && comparison <= 0) {
            // Уже раздаём более новую версию — файл с этой (или меньшей)
            // версией раздачу не заменяет.
            return forget("")
        }
        val error = startSeeding(file, version, size, name, autoReseed = true)
        if (error.isNotBlank()) return error
        store.addAdopted(
            ApkUpdateStore.Adopted(file.absolutePath, name, sha, size, System.currentTimeMillis()),
        )
        if (comparison > 0) {
            val row = transferDao.getForFile(ApkUpdate.CHAT_ID, sha)
                .firstOrNull { it.direction == "OUTGOING" && it.state == "SEEDING" }
            _ready.value = Ready(
                version = version,
                name = name,
                sizeBytes = size,
                sha256 = sha,
                transferId = row?.transferId ?: "",
                sourcePath = File(seedRoot, "$sha.apk").absolutePath,
            )
        }
        Log.i(TAG, "adopted downloaded update v$version from ${file.absolutePath}")
        return ""
    }

    /**
     * Забрать все завершённые загрузки APK у DownloadManager: файл скачивался
     * с официального сайта (или по ссылке) — сам встаёт в раздел «Обновления»
     * и начинает раздаваться. Вызывается по ACTION_DOWNLOAD_COMPLETE, при
     * старте (догнать, скачанное пока приложение было закрыто) и при
     * открытии настроек.
     */
    suspend fun adoptCompletedDownloads() {
        val downloads = runCatching { updateChecker.completedApkDownloads() }.getOrDefault(emptyList())
        for (download in downloads) {
            runCatching { adoptDownloadedApk(download.file, displayName = download.file.name) }
                .onFailure { Log.w(TAG, "adopt failed for ${download.file.name}: ${it.message}") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { Log.i(TAG, "adopt skipped ${download.file.name}: $it") }
        }
    }

    /** Открылись настройки: заодно догнать завершённые загрузки. */
    fun pickupDownloadedApks() {
        scope.launch { runCatching { adoptCompletedDownloads() } }
    }

    // ── Сид: ответы на просьбы ──────────────────────────────────────────────

    /**
     * Узел просит обновление (`upwant`). Отдаю, только если это моя версия
     * и есть готовая копия; конверт ключа сидер печатает под ключ просителя
     * (не закреплён — попросим HELLO, проситель повторит или пойдёт к
     * другому сиду). Все отказы — `upnone`, чтобы круг просьб не висел.
     */
    suspend fun onUpdateWant(senderId: String, packet: GroupWire.Packet.UpdateWant) {
        val info = store.loadSeed()
        if (info == null || info.sha256 != packet.sha256 || !ApkUpdate.isSame(info.version, packet.version)) {
            replyNone(packet.version, packet.sha256, senderId)
            return
        }
        // Ключ обмена просителя: закрепляем, чтобы запечатать ему конверт.
        // Та же проверка, что у `fwant` в рое файлов группы.
        if (packet.binding.isNotEmpty()) {
            runCatching {
                check(uniffi.p2p_core.verifyFileExchangeBinding(packet.binding)) { "bad signature" }
                check(uniffi.p2p_core.fileExchangeBindingNodeId(packet.binding) == senderId) { "binding of another node" }
                peerStore.pinFirstSeen(packet.binding, System.currentTimeMillis())
                MessageSealer.remember(appContext, senderId, packet.binding)
            }.onFailure { Log.w(TAG, "requester binding not pinned (${senderId.takeLast(8)}): ${it.message}") }
        }
        val source = transferDao.getForFile(ApkUpdate.CHAT_ID, packet.sha256)
            .firstOrNull { router.groupSeeder.canSeed(it, router::hasTransferKey) }
        if (source == null) {
            Log.i(TAG, "update want from ${senderId.takeLast(8)} for ${packet.sha256.take(12)}: copy not ready")
            replyNone(packet.version, packet.sha256, senderId)
            return
        }
        when (router.groupSeeder.offer(source, senderId)) {
            GroupFileSeeder.OfferResult.SENT ->
                Log.i(TAG, "update offered v${packet.version} to ${senderId.takeLast(8)}")
            GroupFileSeeder.OfferResult.NO_KEY -> {
                router.requestExchangeBinding(senderId)
                replyNone(packet.version, packet.sha256, senderId)
            }
            GroupFileSeeder.OfferResult.UNREACHABLE,
            GroupFileSeeder.OfferResult.BUSY -> replyNone(packet.version, packet.sha256, senderId)
        }
    }

    // ── Проситель: объявления и просьбы ────────────────────────────────────

    /**
     * Сосед объявил «я раздаю обновление» (`upk`). Показываем, только если
     * версия НОВЕЕ моей — тогда «всем, у кого ниже версия»: у равных и более
     * новых телефонов предложение невидимо. По узлу — последнее объявление
     * (более новая версия заменяет старую).
     */
    suspend fun onUpdatePack(senderId: String, packet: GroupWire.Packet.UpdatePack) {
        if (senderId == myId()) return
        if (!ApkUpdate.isNewer(packet.version, currentAppVersion())) return
        if (store.loadSeed()?.version == packet.version) return
        val now = System.currentTimeMillis()
        val current = store.loadOffers()
        val existing = current.firstOrNull { it.nodeId == senderId }
        if (existing != null) {
            val compare = ApkUpdate.compareVersions(packet.version, existing.version) ?: 0
            if (compare < 0) return
        }
        val incoming = ApkUpdateStore.Offer(senderId, packet.version, packet.sha256, packet.sizeBytes, now)
        val next = (current.filterNot { it.nodeId == senderId } + incoming)
            .filter { ApkUpdate.isNewer(it.version, currentAppVersion()) && now - it.atMs <= OFFER_TTL_MS }
        store.saveOffers(next)
        publishOffers(next)
        Log.i(TAG, "update offer from ${senderId.takeLast(8)}: v${packet.version} (${packet.sizeBytes} B)")
    }

    /** «Нет этой версии» (`upnone`): вычёркиваю сида, спрашиваю следующего. */
    suspend fun onUpdateNone(senderId: String, packet: GroupWire.Packet.UpdateNone) {
        val p = pending ?: return
        if (p.version != packet.version || p.sha256 != packet.sha256) return
        if (senderId == p.askedSeed || senderId in p.tried) {
            p.tried.add(senderId)
            scope.launch { runCatching { askNext() }.onFailure { Log.w(TAG, "ask next failed: ${it.message}") } }
        }
    }

    /**
     * Узел спрашивает «есть что-нибудь новее моей версии?» (`upask`) —
     * кнопка «Проверить новую версию» на его телефоне. Если моя раздача
     * новее его версии — отвечаю одним `upk` ИМЕННО этому узлу (не всем:
     * обычный веер объявлений он уже получает по расписанию).
     */
    suspend fun onUpdateAsk(senderId: String, packet: GroupWire.Packet.UpdateAsk) {
        val info = store.loadSeed() ?: return
        if (!ApkUpdate.isNewer(info.version, packet.version)) return
        runCatching {
            delivery.deliver(
                ApkUpdate.CHAT_ID,
                GroupWire.buildUpdatePack(info.version, info.sha256, info.sizeBytes, System.currentTimeMillis()),
                listOf(senderId),
            )
        }.onFailure { Log.w(TAG, "update ask reply failed: ${it.message}") }
        Log.i(TAG, "update ask from ${senderId.takeLast(8)} (v${packet.version}): answered v${info.version}")
    }

    /**
     * «Проверить новую версию» на телефоне: шлём `upask` со своей версией
     * всем известным узлам; кто раздаёт новее — объявится `upk` (карточка
     * в настройках обновится сама). Возвращает, сколько узлов опрашено.
     */
    suspend fun askNeighbors(): Int {
        val version = ApkUpdate.normalize(currentAppVersion())
        if (ApkUpdate.parseVersion(version) == null) return 0
        val targets = knownNodes()
        if (targets.isEmpty()) return 0
        runCatching {
            delivery.deliver(ApkUpdate.CHAT_ID, GroupWire.buildUpdateAsk(version), targets)
        }.onFailure { Log.w(TAG, "update ask fan-out failed: ${it.message}") }
        Log.i(TAG, "update asked ${targets.size} node(s) for something newer than v$version")
        return targets.size
    }

    /** Начать качать объявленную версию (карточка «Скачать»). */
    suspend fun requestUpdate(nodeId: String) {
        val offer = _offers.value.firstOrNull { it.nodeId == nodeId } ?: return
        val seeds = _offers.value.filter { it.version == offer.version && it.sha256 == offer.sha256 }.map { it.nodeId }
        startPending(offer.version, offer.sha256, seeds)
    }

    /**
     * Ещё один сид присоединился к приёму общей копии (приёмник принял его
     * предложение с меткой `grp_apkseed`, K2). Пока сидов меньше
     * [STRIPE_SEEDS], просим следующего известного сида той же версии —
     * куски качаются со ВСЕХ телефонов, у которых есть файл, а не с одного.
     * Вызывается из хука `onOfferAccepted` маршрутизатора.
     */
    suspend fun onSeedJoined(chatId: String, seedId: String, fileSha256: String, seedCount: Int) {
        if (chatId != ApkUpdate.CHAT_ID) return
        val p = pending ?: return
        if (p.sha256 != fileSha256) return
        p.striping.add(seedId)
        if (seedCount >= STRIPE_SEEDS) return
        val me = myId() ?: return
        val candidates = knownSeedsFor(p).filter { it != me && it !in p.striping }
        if (candidates.isEmpty()) return
        val next = runCatching { directory.order(candidates) }.getOrDefault(candidates).first()
        scope.launch { runCatching { askFirst(next, p) } }
        Log.i(TAG, "stripe: seed ${seedId.takeLast(8)} joined v${p.version} ($seedCount), asked ${next.takeLast(8)} too")
    }

    /** Остановить приём: отказ сидам и убрать местную строку. */
    suspend fun cancelDownload() {
        val p = pending ?: return
        pending = null
        store.savePending(null)
        for (row in transferDao.getForFile(ApkUpdate.CHAT_ID, p.sha256)) {
            if (row.direction == "INCOMING" && row.state != "COMPLETE") {
                runCatching { router.declineIncoming(row) }
                runCatching { router.dropTransfer(row.transferId) }
            }
        }
        _download.value = null
        Log.i(TAG, "update download cancelled")
    }

    // ── Файловая машина: маршрутизация и завершение ─────────────────────────

    /**
     * Куда класть предложение с меткой `grp_apkseed`: только если я сам
     * запрашивал этот файл (просьба в памяти или объявление, по которому
     * начал качать). Файл уже идёт или уже получен — лишнее предложение
     * отклоняется (CANCEL).
     */
    suspend fun routeApkOffer(senderId: String, fileSha256: String): FileTransferReceiver.OfferRouting {
        val wanted = pending?.sha256 == fileSha256 ||
            _offers.value.any { it.sha256 == fileSha256 && ApkUpdate.isNewer(it.version, currentAppVersion()) }
        if (!wanted) return FileTransferReceiver.OfferRouting.Unknown
        val rows = transferDao.getForFile(ApkUpdate.CHAT_ID, fileSha256)
        if (rows.any { it.direction == "INCOMING" && it.state != "FAILED" }) {
            return FileTransferReceiver.OfferRouting.Duplicate(ApkUpdate.CHAT_ID)
        }
        return FileTransferReceiver.OfferRouting.Chat(ApkUpdate.CHAT_ID)
    }

    /**
     * Завершена передача в [ApkUpdate.CHAT_ID]. Файл получен: «Готово»,
     * можно ставить; и телефон СРАЗУ становится сидом (строка-источник —
     * его `INCOMING/COMPLETE`, без перешифровки) — новая версия расходится.
     * Возвращает true: строку в личный чат писать не нужно.
     */
    suspend fun onFileReceived(chatId: String, senderId: String, fileSha256: String): Boolean {
        if (chatId != ApkUpdate.CHAT_ID) return false
        scope.launch {
            runCatching {
                val row = transferDao.getForFile(ApkUpdate.CHAT_ID, fileSha256)
                    .firstOrNull { it.direction == "INCOMING" && it.state == "COMPLETE" }
                if (row == null) return@runCatching
                clearPendingIfMatches(fileSha256)
                val version = pendingVersionFor(fileSha256)
                    ?: store.loadOffers().firstOrNull { it.sha256 == fileSha256 }?.version
                    ?: guessVersionFromName(row.displayName)
                    ?: ApkUpdate.normalize(currentAppVersion())
                _ready.value = Ready(version, row.displayName, row.totalBytes, row.fileSha256, row.transferId)
                _download.value = null
                // Авто-раздача: версия новее моей — раздаём другим.
                if (ApkUpdate.isNewer(version, currentAppVersion())) {
                    val error = markReceivedApkAsSeed(row.transferId, version, autoReseed = true)
                    if (error.isNotBlank()) Log.w(TAG, "auto seed failed: $error")
                }
                Log.i(TAG, "update apk complete v$version sha=${fileSha256.take(12)}")
            }.onFailure { Log.w(TAG, "apk complete handling failed: ${it.message}") }
        }
        return true
    }

    /**
     * Авто-раздача после установки: версия приложения стала равной
     * помеченной — подтверждаем, что копия на месте, и продолжаем раздавать.
     * Копии нет — честно очищаем (файл можно отметить заново).
     */
    suspend fun maybeAutoReseed() {
        val info = store.loadSeed() ?: return
        if (!info.autoReseed) return
        if (!ApkUpdate.isSame(info.version, currentAppVersion())) return
        val row = transferDao.getForFile(ApkUpdate.CHAT_ID, info.sha256)
            .firstOrNull { router.groupSeeder.canSeed(it, router::hasTransferKey) }
        if (row == null) {
            Log.i(TAG, "auto reseed: copy of v${info.version} not found, seed cleared")
            store.saveSeed(null)
            _seed.value = null
            return
        }
        _seed.value = SeedUi(info.version, info.name, info.sizeBytes, row.transferId, router.groupSeeder.servedCount(row.transferId), preparing = false)
        scope.launch { runCatching { announce() } }
    }

    /**
     * Установить скачанное обновление (FileProvider, как у UpdateChecker).
     * Возвращает текст ошибки для карточки: пусто — установщик запущен.
     * Ошибки НЕ молчат: молчаливый отказ выглядит как «кнопка не работает»
     * (так и было: файл принятого обновления лежит в noBackupFilesDir,
     * которого не было в file_paths.xml — getUriForFile бросал, корутина
     * умирала молча).
     */
    suspend fun installReady(): String {
        val readyInfo = _ready.value ?: return "Обновление уже установлено — карточка устарела"
        // Файл обновления: принятая копия (строка INCOMING/COMPLETE) или
        // скачанный с сайта файл, взятый в раздел автоматически.
        val file: File? = readyInfo.transferId.takeIf { it.isNotBlank() }
            ?.let { transferId -> transferDao.getTransfer(transferId)?.let { row -> router.receivedFileFor(row) } }
            ?.takeIf { it.isFile }
            ?: readyInfo.sourcePath?.let { path -> File(path) }?.takeIf { it.isFile }
        if (file == null) {
            Log.w(TAG, "install refused: update file not found (transfer=${readyInfo.transferId}, source=${readyInfo.sourcePath})")
            return "Файл обновления не найден на телефоне — скачайте заново"
        }
        if (ApkUpdate.sha256OfFile(file) != readyInfo.sha256) {
            Log.w(TAG, "install refused: file sha changed")
            return "Файл изменился после скачивания — обновление не запущено"
        }
        return runCatching {
            val authority = "${appContext.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(appContext, authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(intent)
            Log.i(TAG, "install intent started for v${readyInfo.version}")
            ""
        }.getOrElse { error ->
            Log.e(TAG, "install intent failed: ${error.message}")
            // Нет права «устанавливать неизвестные приложения» — Android
            // отклоняет запуск установщика; просим включить его для APU.
            "Android не открыл установщик: ${error.message}. " +
                "Разрешите APU «устанавливать неизвестные приложения» в настройках системы"
        }
    }

    /** Список принятых APK (для «Раздать полученный») — не чаще, чем в помпе. */
    fun refreshReceivedApks() {
        scope.launch { runCatching { scanReceivedApks() } }
    }

    // ── Помпа (файловый цикл, 20 с) ─────────────────────────────────────────

    suspend fun pump() {
        if (!RustBridge.isRunning()) return
        runCatching { ensureInit() }.onFailure { Log.w(TAG, "init failed: ${it.message}") }
        val now = System.currentTimeMillis()
        runCatching { reask(now) }.onFailure { Log.w(TAG, "reask failed: ${it.message}") }
        runCatching { refreshDownloadState() }.onFailure { Log.w(TAG, "download state failed: ${it.message}") }
        runCatching { refreshSeedState(now) }.onFailure { Log.w(TAG, "seed state failed: ${it.message}") }
        runCatching { sweepOffers(now) }.onFailure { Log.w(TAG, "sweep offers failed: ${it.message}") }
        if (now - lastReceivedScanAtMs > RECEIVED_SCAN_INTERVAL_MS) {
            lastReceivedScanAtMs = now
            runCatching { scanReceivedApks() }.onFailure { Log.w(TAG, "received scan failed: ${it.message}") }
            runCatching { adoptCompletedDownloads() }.onFailure { Log.w(TAG, "download adopt failed: ${it.message}") }
        }
    }

    /** Узел появился в сети: если он сид моей просьбы — спрашиваю сразу. */
    suspend fun onPeerOnline(nodeId: String) {
        val p = pending ?: return
        val seeds = knownSeedsFor(p)
        if (nodeId in seeds && System.currentTimeMillis() - p.askedAtMs > PRESENCE_REASK_MIN_MS) {
            p.tried.clear()
            scope.launch { runCatching { askFirst(nodeId, p) } }
        }
    }

    // ── Внутреннее ──────────────────────────────────────────────────────────

    private suspend fun initFromDisk() {
        val now = System.currentTimeMillis()
        val offers = store.loadOffers()
            .filter { ApkUpdate.isNewer(it.version, currentAppVersion()) && now - it.atMs <= OFFER_TTL_MS }
        store.saveOffers(offers)
        publishOffers(offers)
        store.loadPending()?.let { saved ->
            pending = PendingAsk(saved.version, saved.sha256, saved.startedAtMs).also {
                it.attempts = saved.attempts
                it.askedSeed = saved.askedSeed.ifBlank { null }
                it.askedAtMs = saved.askedAtMs
                saved.seeds.forEach { seed -> it.tried.add(seed) }
            }
            _download.value = downloadRow(saved.version, saved.sha256)
        }
        // Готовое скачанное (перезапуск): карточка «Установить».
        runCatching {
            val completed = transferDao.getCompleted()
                .filter { it.chatId == ApkUpdate.CHAT_ID && it.direction == "INCOMING" }
            completed.firstOrNull()?.let { row ->
                val version = store.loadPending()?.takeIf { it.sha256 == row.fileSha256 }?.version
                    ?: store.loadOffers().firstOrNull { it.sha256 == row.fileSha256 }?.version
                    ?: guessVersionFromName(row.displayName)
                    ?: ApkUpdate.normalize(currentAppVersion())
                if (_ready.value == null) {
                    _ready.value = Ready(version, row.displayName, row.totalBytes, row.fileSha256, row.transferId)
                }
            }
        }
        // Раздаём версию НОВЕЕ установленной (официальную загрузку взяли в
        // раздел, но поставить ещё не успели): карточка «Установить» — та же
        // копия из [seedRoot], имя файла уже записано в раздаче.
        runCatching {
            val info = store.loadSeed()
            if (_ready.value == null && info != null && ApkUpdate.isNewer(info.version, currentAppVersion())) {
                val copy = File(seedRoot, "${info.sha256}.apk")
                if (copy.isFile && ApkUpdate.sha256OfFile(copy) == info.sha256) {
                    val row = transferDao.getForFile(ApkUpdate.CHAT_ID, info.sha256)
                        .firstOrNull { it.direction == "OUTGOING" && it.state == "SEEDING" }
                    _ready.value = Ready(
                        version = info.version,
                        name = info.name,
                        sizeBytes = info.sizeBytes,
                        sha256 = info.sha256,
                        transferId = row?.transferId ?: "",
                        sourcePath = copy.absolutePath,
                    )
                }
            }
        }
        runCatching { maybeAutoReseed() }
        runCatching { scanReceivedApks() }
        // Скачанное с официального сайта, пока приложение было закрыто:
        // само встанет в раздел и начнёт раздаваться. В фоне: adopt берёт
        // тот же mutex (startSeeding), а initFromDisk уже под ним — прямой
        // вызов здесь дал бы дедлок.
        scope.launch { runCatching { adoptCompletedDownloads() } }
        refreshSeedState(now)
    }

    private fun publishOffers(offers: List<ApkUpdateStore.Offer>) {
        val ui = offers.map { Offer(it.nodeId, it.version, it.sha256, it.sizeBytes, it.atMs) }
            .sortedWith(Comparator { a, b -> ApkUpdate.compareVersions(b.version, a.version) ?: 0 })
        _offers.value = ui
    }

    private fun myId(): String? = RustBridge.nodeId()

    private fun currentAppVersion(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    /**
     * Заменить помеченную раздачу на другую (более новая версия): старая
     * копия и объявление долой. «Каждый раз заменяя версию более новой».
     */
    private suspend fun replaceSeedIfDifferent(newSha: String) {
        val info = store.loadSeed() ?: return
        if (info.sha256 == newSha) return
        dropSeedCopy(info)
        store.saveSeed(null)
        Log.i(TAG, "seed replaced: v${info.version} -> new build")
    }

    private suspend fun dropSeedCopy(info: ApkUpdateStore.SeedInfo) {
        for (row in transferDao.getForFile(ApkUpdate.CHAT_ID, info.sha256)) {
            if (row.direction == "OUTGOING") {
                runCatching { router.groupSeeder.forget(row.transferId) }
                runCatching { router.dropTransfer(row.transferId) }
            }
        }
        runCatching { File(seedRoot, "${info.sha256}.apk").delete() }
    }

    /** Кому объявлять: все контакты + участники моих сообществ (с потолком). */
    private suspend fun knownNodes(): List<String> {
        val me = myId() ?: return emptyList()
        val set = LinkedHashSet<String>()
        runCatching { contactDao.allIds() }.getOrDefault(emptyList())
            .forEach { if (it != me && it.startsWith("pk_")) set.add(it) }
        runCatching {
            val members = LinkedHashSet<String>()
            for (membership in groupDao.getMyMemberships(me)) {
                for (member in groupDao.getMembers(membership.groupId)) {
                    if (member.nodeId != me && !member.isBanned) members.add(member.nodeId)
                }
            }
            val ordered = runCatching { directory.order(members.toList()) }.getOrDefault(members.toList())
            ordered.take(MAX_GROUP_ANNOUNCE).forEach { if (it != me) set.add(it) }
        }
        return set.toList()
    }

    /** Объявить о своей раздаче всем известным узлам. */
    private suspend fun announce() {
        val info = store.loadSeed() ?: return
        val now = System.currentTimeMillis()
        if (now - lastAnnounceAtMs < ANNOUNCE_INTERVAL_MS) return
        val targets = knownNodes()
        if (targets.isEmpty()) return
        val text = GroupWire.buildUpdatePack(info.version, info.sha256, info.sizeBytes, now)
        runCatching { delivery.deliver(ApkUpdate.CHAT_ID, text, targets) }
            .onFailure { Log.w(TAG, "announce failed: ${it.message}") }
        lastAnnounceAtMs = now
        Log.i(TAG, "update announced v${info.version} to ${targets.size} node(s)")
    }

    private suspend fun replyNone(version: String, sha256: String, to: String) {
        runCatching {
            delivery.deliver(ApkUpdate.CHAT_ID, GroupWire.buildUpdateNone(version, sha256), listOf(to))
        }.onFailure { Log.w(TAG, "update none reply failed: ${it.message}") }
    }

    // ── Просьбы: круг сидов ─────────────────────────────────────────────────

    private suspend fun startPending(version: String, sha256: String, seeds: List<String>) {
        val now = System.currentTimeMillis()
        val p = PendingAsk(version, sha256, now)
        seeds.forEach { p.tried.add(it) }
        pending = p
        store.savePending(
            ApkUpdateStore.Pending(version, sha256, now, 0, "", 0L, seeds),
        )
        _ready.value = null // новое скачивание перезаписывает «Готово»
        askNext()
    }

    private suspend fun knownSeedsFor(p: PendingAsk): Set<String> {
        val fromOffers = store.loadOffers()
            .filter { it.version == p.version && it.sha256 == p.sha256 }
            .map { it.nodeId }
        val fromDisk = store.loadPending()?.seeds.orEmpty()
        return (fromOffers + fromDisk).toSet()
    }

    private suspend fun askNext() {
        val p = pending ?: return
        val me = myId() ?: return
        val candidates = knownSeedsFor(p).filter { it != me }
        if (candidates.isEmpty()) {
            Log.w(TAG, "update ask: no known seeds for v${p.version}")
            return
        }
        val ordered = runCatching { directory.order(candidates) }.getOrDefault(candidates)
        val seed = ordered.firstOrNull { it !in p.tried } ?: run {
            p.tried.clear()
            ordered.firstOrNull { it !in p.tried } ?: return
        }
        askFirst(seed, p)
    }

    private suspend fun askFirst(preferred: String, p: PendingAsk) {
        val me = myId() ?: return
        if (preferred == me) return
        val now = System.currentTimeMillis()
        p.tried.add(preferred)
        p.askedSeed = preferred
        p.askedAtMs = now
        p.attempts++
        store.savePending(
            ApkUpdateStore.Pending(p.version, p.sha256, p.startedAtMs, p.attempts, preferred, now, knownSeedsFor(p).toList()),
        )
        val binding = runCatching { FileExchangeKeyStore.publicBinding(appContext) }.getOrNull() ?: ByteArray(0)
        val text = GroupWire.buildUpdateWant(p.version, p.sha256, binding)
        runCatching { delivery.deliver(ApkUpdate.CHAT_ID, text, listOf(preferred)) }
            .onFailure { Log.w(TAG, "update want to ${preferred.takeLast(8)} failed: ${it.message}") }
        Log.i(TAG, "update want v${p.version} to ${preferred.takeLast(8)} attempt=${p.attempts}")
    }

    private suspend fun reask(now: Long) {
        val p = pending ?: return
        if (now - p.startedAtMs > PENDING_TTL_MS || p.attempts >= MAX_ATTEMPTS) {
            clearPending()
            return
        }
        if (downloadRow(p.version, p.sha256) != null) return // куски приходят
        val interval = (REASK_BASE_MS * p.attempts.coerceAtLeast(1)).coerceAtMost(REASK_MAX_MS)
        if (now - p.askedAtMs < interval) return
        askNext()
    }

    private suspend fun clearPending() {
        pending = null
        store.savePending(null)
        _download.value = null
    }

    private suspend fun clearPendingIfMatches(sha256: String) {
        val p = pending ?: return
        if (p.sha256 == sha256) clearPending()
    }

    private fun pendingVersionFor(sha256: String): String? =
        pending?.takeIf { it.sha256 == sha256 }?.version ?: store.loadPending()?.takeIf { it.sha256 == sha256 }?.version

    /** Строка приёма: есть живая INCOMING передачи в apkseed — её ход. */
    private suspend fun downloadRow(version: String, sha256: String): Download? {
        val row = transferDao.getForFile(ApkUpdate.CHAT_ID, sha256)
            .firstOrNull { it.direction == "INCOMING" && it.state != "COMPLETE" && it.state != "FAILED" }
            ?: return null
        return Download(version, row.transferredBytes, row.totalBytes)
    }

    private suspend fun refreshDownloadState() {
        val p = pending
        if (p == null) {
            if (_download.value != null) _download.value = null
            return
        }
        _download.value = downloadRow(p.version, p.sha256)
    }

    /** Раздача: живая ли копия, сколько получили. */
    private suspend fun refreshSeedState(now: Long) {
        val info = store.loadSeed()
        if (info == null) {
            if (_seed.value != null) _seed.value = null
            return
        }
        val current = _seed.value
        if (current?.version == info.version && current.preparing) return // подготовка идёт
        val row = transferDao.getForFile(ApkUpdate.CHAT_ID, info.sha256)
            .firstOrNull { router.groupSeeder.canSeed(it, router::hasTransferKey) }
        _seed.value = SeedUi(
            version = info.version,
            name = info.name,
            sizeBytes = info.sizeBytes,
            transferId = row?.transferId,
            served = row?.let { router.groupSeeder.servedCount(it.transferId) } ?: 0,
            preparing = row == null,
        )
        if (row != null && now - lastAnnounceAtMs >= ANNOUNCE_INTERVAL_MS) {
            runCatching { announce() }
        }
    }

    /** Давние объявления и не более новые, чем моя версия — долой. */
    private suspend fun sweepOffers(now: Long) {
        val offers = store.loadOffers()
        val kept = offers.filter {
            ApkUpdate.isNewer(it.version, currentAppVersion()) && now - it.atMs <= OFFER_TTL_MS
        }
        if (kept.size != offers.size) {
            store.saveOffers(kept)
            publishOffers(kept)
        }
    }

    /**
     * Угадать версию из имени файла: первое числовое `11.70.29` в строке
     * (APU-v11.70.29.apk, «APU 11.70.29 beta.apk»). null — не найдено,
     * попросим у человека.
     */
    private fun guessVersionFromName(fileName: String): String? = ApkUpdate.versionFromName(fileName)

    private suspend fun scanReceivedApks() {
        val rows = runCatching { transferDao.getCompleted() }.getOrDefault(emptyList())
            .filter { it.direction == "INCOMING" && it.chatId != ApkUpdate.CHAT_ID && it.displayName.lowercase().endsWith(".apk") }
        val list = rows.mapNotNull { row ->
            val file = router.receivedFileFor(row) ?: return@mapNotNull null
            if (!file.isFile) return@mapNotNull null
            ReceivedApk(
                transferId = row.transferId,
                chatId = row.chatId,
                fromNodeId = row.peerNodeId,
                displayName = row.displayName,
                sizeBytes = row.totalBytes,
                atMs = row.updatedAtMs,
                // Сначала версия из самого APK; если не читается — из имени.
                versionGuess = archiveVersion(file) ?: guessVersionFromName(row.displayName),
            )
        }.sortedByDescending { it.atMs }
            .take(MAX_RECEIVED_LIST)
        _receivedApks.value = list
    }

    companion object {
        private const val TAG = "ApkSeeder"

        /** Объявление раздачи: не чаще раза в столько. */
        const val ANNOUNCE_INTERVAL_MS = 10L * 60 * 1000
        /** Объявление соседа живёт столько, а потом тихо сгорает. */
        const val OFFER_TTL_MS = 3L * 24 * 60 * 60 * 1000
        /** Моя просьба живёт сутки (как у файлов групп). */
        const val PENDING_TTL_MS = 24L * 60 * 60 * 1000
        const val MAX_ATTEMPTS = 40
        const val REASK_BASE_MS = 90_000L
        const val REASK_MAX_MS = 10L * 60 * 1000
        const val PRESENCE_REASK_MIN_MS = 45_000L
        /** Учасников сообществ в объявлении: потолок веера. */
        const val MAX_GROUP_ANNOUNCE = 500
        /** Список «Полученных APK» в настройках: не больше стольких. */
        const val MAX_RECEIVED_LIST = 20
        const val RECEIVED_SCAN_INTERVAL_MS = 5L * 60 * 1000
        /** Сколько сидов одновременно шлют полосы кусков (K2, как у групп). */
        const val STRIPE_SEEDS = 3
    }
}
