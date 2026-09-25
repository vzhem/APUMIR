package com.vladimir.messenger.data.group

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.file.AndroidFileSelection
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import com.vladimir.messenger.data.sticker.StickerLibrary
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.file.FileTransferRankPolicy
import com.vladimir.messenger.data.file.FileTransferReceiver
import com.vladimir.messenger.data.file.FileTransferRouter
import com.vladimir.messenger.data.file.OutgoingFilePreparationService
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.data.referral.ReferralRankStore
import com.vladimir.messenger.data.swarm.SwarmMode
import com.vladimir.messenger.data.swarm.SwarmPeerDirectory
import com.vladimir.messenger.data.swarm.SwarmSettings
import com.vladimir.messenger.util.GroupFileMarker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Файлы группы роем (docs/CHANNEL_SWARM_DESIGN.md, этап 9).
 *
 * Сообщение группы несёт только визитку файла (`APUFILE1:`, см.
 * [GroupFileMarker]); сам файл по группе веером не рассылается. Кому файл
 * нужен, тот просит его пакетом `fwant` у автора или у любого участника,
 * который уже получил файл целиком; сид отвечает обычной зашифрованной
 * передачей лично просителю (тот же `FileTransfer`, что и в личном чате:
 * конверт с ключом файла - под закреплённый ключ просителя, куски -
 * напрямую по LAN или QUIC). Получив файл, участник объявляет `fhave`
 * нескольким соседям и с этого момента сам раздаёт. Так автор отдаёт файл
 * не всем двумстам подписчикам, а нескольким, дальше он расходится сам.
 *
 * Здесь три роли одного телефона:
 *  - автор: [stage] кладёт копию выбранного файла в [GroupFileStore] (доступ
 *    к файлу из системного окна разовый, а просьбы приходят днями) и
 *    возвращает визитку; сообщение с ней отправляет GroupRepository;
 *  - проситель: [onCardSeen] решает, тянуть ли файл сразу ([shouldAutoFetch])
 *    или ждать нажатия «Скачать» ([request]); [pump] повторяет просьбу,
 *    меняя сидов, пока передача не появится;
 *  - сид: [onFileWant] готовит передачу просителю из своей копии (авторской
 *    или полученной) - не больше [MAX_PARALLEL_SEEDS] одновременно, остальные
 *    ждут очереди; [pump] освобождает куски отданных передач.
 *
 * Свои просьбы лежат и на диске ([GroupFileRequestStore], этап 10): после
 * перезапуска телефон помнит, что просил, у кого и сколько раз, и
 * продолжает круг сидов с того же места. Очередь чужих просьб - только в
 * памяти (проситель повторит сам). Начатые передачи продолжаются сами: сид
 * повторяет предложение, пока проситель не подтвердит всё.
 *
 * Полосы от нескольких сидов (K2, v11.70.25, `docs/CORE_ROADMAP.md`). У
 * файла группы теперь ОБЩИЙ манифест с меткой группы вместо получателя
 * (`grp_<id>`, ядро `create_group_file_manifest`), один ключ и одни куски:
 * автор шифрует файл один раз ([OutgoingFilePreparationService.prepareGroupCopy]),
 * получивший файл раздаёт прямо из своей входящей (куски и ключ у него
 * остались), и все сиды отдают байт в байт одно и то же. Проситель шлёт
 * `fwant` с меткой `#g1` («понимаю общие манифесты») одному сиду; тот
 * отвечает предложением с общим манифестом и конвертом ключа под ключ
 * просителя, приёмник его принимает и зовёт [onSeedJoined] - проситель
 * просит следующего сида, и так до [STRIPE_SEEDS]; приёмник принимает их
 * всех в одну передачу, делит недостающее инвентарём (WANT) и сливает файл
 * по sha256 (FileTransferReceiver). Чем больше участников уже скачали файл,
 * тем быстрее он приходит следующему. Сид старой версии метку не понимает и
 * отвечает прежней личной передачей - приёмник берёт её как раньше (один
 * источник), других сидов при этом не зовут.
 */
@Singleton
class GroupFileSwarm @Inject constructor(
    @ApplicationContext context: Context,
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    private val transferDao: FileTransferDao,
    private val peerStore: FileExchangePeerStore,
    private val preparation: OutgoingFilePreparationService,
    private val router: FileTransferRouter,
    private val delivery: GroupDelivery,
    private val directory: SwarmPeerDirectory,
) {
    private val appContext: Context = context.applicationContext
    private val store = GroupFileStore(File(appContext.noBackupFilesDir, "group_files/v1"))
    private val requestStore = GroupFileRequestStore(File(appContext.noBackupFilesDir, "group_files/requests.v1"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** Моя просьба о файле: у кого спрашивал, когда, сколько раз. */
    private class Pending(
        val groupId: String,
        val sha256: String,
        val messageId: String,
        val info: GroupFileMarker.Info,
        val startedAtMs: Long,
        val manual: Boolean,
    ) {
        var askedSeed: String? = null
        var askedAtMs: Long = 0L
        var attempts: Int = 0
        /** Кого уже спрашивал в этом круге: следующий - другой сид. */
        val tried = LinkedHashSet<String>()
        /** Сиды, которые уже шлют полосы общей копии (K2): их не переспрашиваем. */
        val striping = LinkedHashSet<String>()
    }

    /** Просьбы с диска прочитаны (один раз за запуск, при первом обращении). */
    @Volatile private var restored = false

    /** Чужая просьба, которую сейчас не могу выполнить (все места заняты). */
    private class Waiting(
        val groupId: String,
        val sha256: String,
        val messageId: String,
        val requester: String,
        val sinceMs: Long,
        /** Проситель понимает общие манифесты (K2): отдать из общей копии, когда освободится плечо. */
        val shared: Boolean = false,
    )

    private val pending = LinkedHashMap<String, Pending>()
    private val waiting = LinkedHashMap<String, Waiting>()
    /** Кто, по моим сведениям, раздаёт файл: ключ группы+хэша → узлы (автор - всегда первым). */
    private val seeds = object : LinkedHashMap<String, LinkedHashSet<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashSet<String>>?): Boolean =
            size > MAX_KEYS
    }
    /** Просителю по одному файлу - не чаще раза в минуту. */
    private val servedAt = ConcurrentHashMap<String, Long>()
    /** Передачи, которые я завёл как сид: их куски удаляются сразу после подтверждения. */
    private val seeded = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var cleanedLeftovers = false
    @Volatile private var lastSweepAt = 0L

    private val _pendingKeys = MutableStateFlow<Set<String>>(emptySet())
    /** Ключи файлов ([GroupFileMarker.key]), которые сейчас просим: карточка показывает «Запрошено…». */
    val pendingKeys: StateFlow<Set<String>> = _pendingKeys.asStateFlow()

    private val _servedCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Скольким участникам отдана моя общая копия файла (K2), по ключу файла; карточка автора: «Получили: N». */
    val servedCounts: StateFlow<Map<String, Int>> = _servedCounts.asStateFlow()

    // ── Автор ────────────────────────────────────────────────────────────────

    /**
     * Подготовить файл к раздаче группе: проверить ранг, посчитать хэш,
     * положить копию. Возвращает визитку для текста сообщения. Долго - не с
     * главного потока (читает файл дважды).
     */
    suspend fun stage(groupId: String, source: Uri): GroupFileMarker.Info = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val inspected = AndroidFileSelection.inspect(resolver, source)
        FileTransferRankPolicy.requireCanSend(
            qualifiedDirectReferrals = ReferralRankStore.qualifiedDirectCount(appContext),
            mediaType = inspected.mediaType,
            sizeBytes = inspected.sizeBytes,
        )
        check(inspected.sizeBytes > 0L) { "Пустой файл" }
        val input = resolver.openInputStream(source) ?: throw IllegalArgumentException("Не удалось открыть файл")
        input.use { store.put(groupId, inspected.sha256, inspected.displayName, it) }
        GroupFileMarker.Info(
            sha256 = inspected.sha256,
            sizeBytes = inspected.sizeBytes,
            mediaType = inspected.mediaType,
            displayName = inspected.displayName,
        )
    }

    /**
     * Приложить гифку из каталога: байты уже скачаны телефоном. Тот же путь,
     * что у [stage] (рейтинг, ша-256, хранилище, визитка), только источник -
     * память, а не проводник.
     */
    suspend fun stageGifBytes(
        groupId: String,
        bytes: ByteArray,
    ): GroupFileMarker.Info = withContext(Dispatchers.IO) {
        check(bytes.isNotEmpty()) { "Пустая гифка" }
        FileTransferRankPolicy.requireCanSend(
            qualifiedDirectReferrals = ReferralRankStore.qualifiedDirectCount(appContext),
            mediaType = "image/gif",
            sizeBytes = bytes.size.toLong(),
        )
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val sha256 = digest.digest(bytes).joinToString("") { "%02x".format(it) }
        val name = "gif_" + sha256.take(10) + ".gif"
        store.put(groupId, sha256, name, bytes.inputStream())
        GroupFileMarker.Info(
            sha256 = sha256,
            sizeBytes = bytes.size.toLong(),
            mediaType = "image/gif",
            displayName = name,
        )
    }

    /**
     * Раунд 166: приложить стикер из библиотеки. Байты уже на телефоне,
     * тип - image/webp (анимированный), имя - человеческое «Стикер.webp»:
     * карточка в ленте и уведомления без sha-строк.
     */
    suspend fun stageStickerBytes(
        groupId: String,
        bytes: ByteArray,
    ): GroupFileMarker.Info = withContext(Dispatchers.IO) {
        check(bytes.isNotEmpty()) { "Пустой стикер" }
        FileTransferRankPolicy.requireCanSend(
            qualifiedDirectReferrals = ReferralRankStore.qualifiedDirectCount(appContext),
            mediaType = "image/webp",
            sizeBytes = bytes.size.toLong(),
        )
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val sha256 = digest.digest(bytes).joinToString("") { "%02x".format(it) }
        // Раунд 170: видео-стикер webm едет со своим типом - принимающая
        // сторона рисует его покадрово (StickerAnimated).
        val webm = StickerLibrary.isWebmBytes(bytes)
        store.put(groupId, sha256, if (webm) "Стикер.webm" else "Стикер.webp", bytes.inputStream())
        GroupFileMarker.Info(
            sha256 = sha256,
            sizeBytes = bytes.size.toLong(),
            mediaType = if (webm) "video/webm" else "image/webp",
            displayName = if (webm) "Стикер.webm" else "Стикер.webp",
        )
    }

    /** Сообщение с визиткой не ушло - копия не нужна. */
    suspend fun unstage(groupId: String, sha256: String) = withContext(Dispatchers.IO) {
        runCatching { store.delete(groupId, sha256) }
        Unit
    }

    /** Авторская копия файла (для превью картинки в карточке автора). */
    fun authorCopy(groupId: String, sha256: String): File? =
        runCatching { store.file(groupId, sha256) }.getOrNull()

    /**
     * Группу покинули или удалили (этап 11): мои копии её файлов и просьбы
     * о её файлах больше не нужны; известные сиды - тоже. Незавершённые
     * передачи её файлов убирает обычный срок (7 дней), как и раньше.
     */
    suspend fun onGroupGone(groupId: String) {
        withContext(Dispatchers.IO) { runCatching { store.deleteGroup(groupId) } }
        // Общие копии её файлов (K2) больше не раздаём: плечи и строки долой.
        runCatching {
            for (row in transferDao.getSeeding()) {
                if (row.chatId != groupId) continue
                router.groupSeeder.forget(row.transferId)
                router.dropTransfer(row.transferId)
            }
        }.onFailure { Log.w(TAG, "drop group copies failed: ${it.message}") }
        val removed = mutex.withLock {
            val keys = pending.keys.filter { pending[it]?.groupId == groupId }
            keys.forEach { pending.remove(it) }
            seeds.keys.removeAll { it.startsWith("$groupId:") }
            waiting.entries.removeIf { it.value.groupId == groupId }
            if (keys.isNotEmpty()) publishPending()
            keys.size
        }
        if (removed > 0) persistPending()
        groupChatMemo.remove(groupId)
    }

    // ── Проситель ────────────────────────────────────────────────────────────

    /**
     * В ленту пришло сообщение с визиткой (не моё). Автор - первый известный
     * сид. Небольшой свежий файл тянем сразу, большой или давний (досланный
     * новичку старый пост: авторская копия могла уже истечь) - по нажатию
     * «Скачать».
     */
    suspend fun onCardSeen(groupId: String, messageId: String, authorId: String, sentAtMs: Long, info: GroupFileMarker.Info) {
        restoreIfNeeded()
        val key = GroupFileMarker.key(groupId, info.sha256)
        rememberSeed(key, authorId, first = true)
        if (!shouldAutoFetch(info)) return
        if (System.currentTimeMillis() - sentAtMs > AUTO_FETCH_MAX_AGE_MS) return
        if (hasTransfer(groupId, info.sha256)) return
        // Небольшая пауза со случайным разбросом: двести телефонов, получив
        // сообщение одновременно, не должны стучаться к автору в одну секунду.
        val jitter = (0L..AUTO_FETCH_JITTER_MS).random()
        scope.launch {
            delay(jitter)
            runCatching { request(groupId, messageId, info, authorId, manual = false) }
                .onFailure { Log.w(TAG, "auto request failed: ${it.message}") }
        }
    }

    /**
     * Попросить файл (нажатие «Скачать» или автоматически). Идёт к лучшему
     * известному сиду; повторы и смену сида делает [pump].
     */
    suspend fun request(
        groupId: String,
        messageId: String,
        info: GroupFileMarker.Info,
        authorId: String,
        manual: Boolean,
    ) {
        val me = myId() ?: return
        if (authorId == me) return
        restoreIfNeeded()
        val key = GroupFileMarker.key(groupId, info.sha256)
        rememberSeed(key, authorId, first = true)
        val now = System.currentTimeMillis()
        val entry = mutex.withLock {
            val existing = pending[key]
            if (existing != null && !manual) return
            val fresh = Pending(groupId, info.sha256, messageId, info, now, manual)
            if (existing != null) {
                // Ручной повтор: круг сидов заново, но не чаще раза в полминуты.
                if (now - existing.askedAtMs < MANUAL_REASK_MIN_MS) return
                fresh.attempts = existing.attempts
            }
            pending[key] = fresh
            publishPending()
            fresh
        }
        ask(entry, now)
    }

    /**
     * Просьбы с диска (этап 10): один раз за запуск, при первом обращении к
     * [pending]. Каждая восстанавливается вместе с известными сидами; давние
     * (старше [PENDING_TTL_MS]) и те, чей файл уже получен, отбрасываются.
     * Насос ([pump]) дальше ведёт их как обычные: повтор по времени, смена
     * сида, запасной сид.
     */
    private suspend fun restoreIfNeeded() {
        if (restored) return
        val loaded = withContext(Dispatchers.IO) { runCatching { requestStore.load() }.getOrDefault(emptyList()) }
        val now = System.currentTimeMillis()
        // Давние и уже полученные - вон (проверка базы до замка).
        val records = loaded.filter { record ->
            now - record.startedAtMs <= PENDING_TTL_MS &&
                transferDao.getForFile(record.groupId, record.info.sha256)
                    .none { it.direction == "INCOMING" && it.state == "COMPLETE" }
        }
        var kept = 0
        mutex.withLock {
            if (restored) return
            restored = true
            for (record in records) {
                val key = GroupFileMarker.key(record.groupId, record.info.sha256)
                if (pending.containsKey(key)) continue
                val entry = Pending(record.groupId, record.info.sha256, record.messageId, record.info, record.startedAtMs, record.manual)
                entry.attempts = record.attempts
                entry.askedSeed = record.askedSeed.ifBlank { null }
                entry.askedAtMs = record.askedAtMs
                if (record.askedSeed.isNotBlank()) entry.tried.add(record.askedSeed)
                pending[key] = entry
                val set = seeds.getOrPut(key) { LinkedHashSet<String>() }
                for (seed in record.seeds) if (set.size < MAX_SEEDS_PER_KEY) set.add(seed)
                kept++
            }
            if (kept > 0) publishPending()
        }
        if (kept > 0) Log.i(TAG, "restored $kept pending file request(s) from disk")
    }

    /** Просьба ушла ли уже по этому файлу (карточка: «Запрошено…»). */
    fun isPending(groupId: String, sha256: String): Boolean =
        GroupFileMarker.key(groupId, sha256) in _pendingKeys.value

    /**
     * Поднять просьбы с диска, если ещё не подняты: экран группы зовёт это,
     * открываясь, чтобы карточки сразу показывали «Запрошено…», а не
     * «Скачать», пока насос (20 с) не дошёл до первого круга.
     */
    fun warmUp() {
        if (restored) return
        scope.launch { runCatching { restoreIfNeeded() }.onFailure { Log.w(TAG, "restore failed: ${it.message}") } }
    }

    /** Файл небольшой (и сеть не мобильная для файлов побольше) - тянем без вопросов. */
    fun shouldAutoFetch(info: GroupFileMarker.Info): Boolean {
        if (info.sizeBytes <= 0L) return false
        SwarmSettings.init(appContext)
        if (SwarmSettings.mode.value == SwarmMode.UNLIMITED) return info.sizeBytes <= AUTO_FETCH_UNLIMITED_BYTES
        if (info.sizeBytes > AUTO_FETCH_BYTES) return false
        val metered = runCatching { SwarmSettings.isMetered(appContext) }.getOrDefault(false)
        return !metered || info.sizeBytes <= AUTO_FETCH_METERED_BYTES
    }

    private suspend fun ask(entry: Pending, now: Long) {
        val me = myId() ?: return
        val key = GroupFileMarker.key(entry.groupId, entry.sha256)
        val known = mutex.withLock { seeds[key]?.toList().orEmpty() }
        if (known.isEmpty()) return
        // Не автор первым, если есть кто-то ещё: нагрузку с автора снимаем,
        // как торрент снимает её с первого раздающего. Порядок среди прочих -
        // по ярусу и рейтингу узлов.
        val author = known.first()
        val others = runCatching { directory.order(known.drop(1).filter { it != me }) }
            .getOrDefault(known.drop(1))
        val order = others + author
        val seed = order.firstOrNull { it !in entry.tried && it !in entry.striping } ?: run {
            entry.tried.clear()
            order.firstOrNull { it !in entry.striping } ?: return
        }
        // Полосы (K2): просим одного, как раньше; когда его предложение с
        // ОБЩИМ манифестом принято, приёмник зовёт onSeedJoined, и тот просит
        // следующего - и так до STRIPE_SEEDS. Сид старой версии отвечает
        // личной передачей: общего манифеста нет, других не зовём (иначе
        // два-три старых телефона зря шифровали бы по полной копии, а
        // приёмник отказывал бы лишним CANCEL, как в этапе 10).
        entry.tried.add(seed)
        entry.askedSeed = seed
        entry.askedAtMs = now
        entry.attempts++
        persistPending()
        val binding = runCatching { FileExchangeKeyStore.publicBinding(appContext) }.getOrNull() ?: ByteArray(0)
        val envelope = GroupWire.buildFileWant(entry.groupId, entry.sha256, entry.messageId, binding, groupCapable = true)
        val report = delivery.deliver(entry.groupId, envelope, listOf(seed))
        Log.i(
            TAG,
            "file want sent key=${entry.sha256.take(12)} to=${seed.takeLast(8)} attempt=${entry.attempts} " +
                "delivered=${report.delivered}/${report.attempted}" +
                (if (entry.striping.isNotEmpty()) " striping=${entry.striping.size}" else ""),
        )
    }

    /**
     * Приёмник принял предложение общей копии от ещё одного сида (K2):
     * запомнить, что он шлёт полосы, и, пока сидов меньше [STRIPE_SEEDS],
     * позвать следующего известного - каждый новый сид ускоряет приём.
     */
    suspend fun onSeedJoined(chatId: String, seedId: String, fileSha256: String, seedCount: Int) {
        restoreIfNeeded()
        val key = GroupFileMarker.key(chatId, fileSha256)
        rememberSeed(key, seedId, first = false)
        val next = mutex.withLock {
            val entry = pending[key] ?: return
            entry.striping.add(seedId)
            if (seedCount >= STRIPE_SEEDS) return
            val me = myId()
            seeds[key]?.firstOrNull { it != me && it !in entry.striping && it !in entry.tried } ?: return
        }
        val entry = mutex.withLock { pending[key] } ?: return
        entry.tried.add(next)
        val binding = runCatching { FileExchangeKeyStore.publicBinding(appContext) }.getOrNull() ?: ByteArray(0)
        val envelope = GroupWire.buildFileWant(entry.groupId, entry.sha256, entry.messageId, binding, groupCapable = true)
        // В фоне: сюда приходят из приёмника под его замком.
        scope.launch {
            runCatching { delivery.deliver(entry.groupId, envelope, listOf(next)) }
                .onFailure { Log.w(TAG, "stripe want to ${next.takeLast(8)} failed: ${it.message}") }
        }
        Log.i(TAG, "stripe: seed ${seedId.takeLast(8)} joined ${fileSha256.take(12)} ($seedCount), asked ${next.takeLast(8)} too")
    }

    /** Сидер отдал просителю весь файл общей копии (K2): он теперь тоже сид. */
    suspend fun onServed(transferIdHex: String, requester: String) {
        val row = transferDao.getTransfer(transferIdHex) ?: return
        if (!isGroupChat(row.chatId)) return
        val key = GroupFileMarker.key(row.chatId, row.fileSha256)
        rememberSeed(key, requester, first = false)
        if (row.direction == "OUTGOING") {
            _servedCounts.value = _servedCounts.value + (key to router.groupSeeder.servedCount(transferIdHex))
        }
    }

    /**
     * Можно ли отдавать файл группы [groupId] узлу [requester]: то же правило,
     * что у просьбы (`fwant`) - участник, не забанен; в открытом сообществе и
     * незнакомый. Сидер спрашивает, поднимая плечо по подтверждению после
     * перезапуска, когда просьбы роя уже нет.
     */
    suspend fun mayServeFile(groupId: String, requester: String): Boolean {
        val me = myId() ?: return false
        if (requester == me || !requester.startsWith("pk_")) return false
        val group = groupDao.getGroupById(groupId) ?: return false
        if (groupDao.getMember(group.id, me) == null) return false
        val member = groupDao.getMember(group.id, requester)
        if (member?.isBanned == true) return false
        return member != null || group.isPublic
    }


    /** Сколько сидов файла известно, кроме меня. */
    private suspend fun knownSeedCount(entry: Pending): Int {
        val me = myId()
        return mutex.withLock { seeds[GroupFileMarker.key(entry.groupId, entry.sha256)]?.count { it != me } ?: 0 }
    }

    /** Другой участник получил файл целиком и готов раздавать. */
    suspend fun onFileHave(senderId: String, packet: GroupWire.Packet.FileHave) {
        val group = groupDao.getGroupById(packet.groupId) ?: return
        // Как в onFileWant: в открытом сообществе сидом может оказаться и
        // участник, которого нет в моём (неполном) списке. Хэш файла всё
        // равно проверяется при приёме - чужак может лишь потратить трафик.
        val member = groupDao.getMember(packet.groupId, senderId)
        if (member?.isBanned == true) return
        if (member == null && !group.isPublic) return
        rememberSeed(GroupFileMarker.key(packet.groupId, packet.sha256), senderId, first = false)
    }

    /**
     * Сид ответил «файла у меня нет» (этап 11): вычёркиваю его из сидов
     * этого файла и, если просьба ждёт именно его, спрашиваю следующего
     * сразу. Принимается только от того, кого я спрашивал, или от известного
     * сида - чужой пакет ничего не меняет.
     */
    suspend fun onFileNone(senderId: String, packet: GroupWire.Packet.FileNone) {
        val me = myId() ?: return
        restoreIfNeeded()
        val key = GroupFileMarker.key(packet.groupId, packet.sha256)
        val now = System.currentTimeMillis()
        val entry = mutex.withLock {
            val set = seeds[key] ?: return
            if (senderId !in set) return
            val entry = pending[key]
            // Автор остаётся первым в списке (порядок важен для ask), даже
            // если копии у него сейчас нет: он может вернуть её позже.
            if (set.first() != senderId) set.remove(senderId)
            if (entry == null || entry.askedSeed != senderId) return
            entry.tried.add(senderId)
            // Следующего спрашиваем, только если он есть: иначе ask пошёл бы
            // по кругу к тому же узлу, а тот снова ответил бы «нет» - и так
            // без остановки. Нет других - ждём обычного круга насоса.
            val hasNext = set.any { it != me && it != senderId && it !in entry.tried }
            if (!hasNext) {
                Log.i(TAG, "file none from ${senderId.takeLast(8)} for ${packet.sha256.take(12)}: no other seed yet")
                return
            }
            entry
        }
        if (hasLiveTransfer(entry.groupId, entry.sha256, now)) return
        Log.i(TAG, "file none from ${senderId.takeLast(8)} for ${packet.sha256.take(12)}: asking next seed")
        ask(entry, now)
    }

    /**
     * Предложение файла с таким хэшем от этого узла: в группу, если я просил
     * этот файл (у него или у кого угодно из сидов) и передача не идёт.
     * Застрявшая передача (сид пропал на полпути) второе предложение не
     * блокирует: новая строка от нового сида дойдёт, старая истечёт сама.
     */
    suspend fun routeOffer(senderId: String, fileSha256: String): FileTransferReceiver.OfferRouting {
        restoreIfNeeded()
        val entry = mutex.withLock { pending.values.firstOrNull { it.sha256 == fileSha256 } }
        val groupId = if (entry != null) {
            val key = GroupFileMarker.key(entry.groupId, fileSha256)
            val knownSeed = mutex.withLock { seeds[key]?.contains(senderId) == true }
            if (!knownSeed && entry.askedSeed != senderId) return FileTransferReceiver.OfferRouting.Unknown
            entry.groupId
        } else {
            // Просьбы в памяти нет (приложение перезапускалось, а сид
            // повторяет предложение по моей прежней просьбе): файл группы
            // узнаём по визитке в общей с отправителем группе.
            groupWithCardFrom(senderId, fileSha256) ?: return FileTransferReceiver.OfferRouting.Unknown
        }
        if (hasLiveTransfer(groupId, fileSha256, System.currentTimeMillis())) {
            return FileTransferReceiver.OfferRouting.Duplicate(groupId)
        }
        return FileTransferReceiver.OfferRouting.Chat(groupId)
    }

    /**
     * Файл получен целиком от одного сида, а от другого (запасного, этап 10)
     * тоже начала идти или ещё висит передача того же файла: лишние
     * незавершённые входящие строки убираем, а их сидам говорим CANCEL - иначе
     * они повторяли бы предложение и держали куски до конца срока.
     */
    private suspend fun cancelExtraOffers(groupId: String, sha256: String) {
        val rows = transferDao.getForFile(groupId, sha256)
        if (rows.none { it.direction == "INCOMING" && it.state == "COMPLETE" }) return
        for (row in rows) {
            if (row.direction != "INCOMING" || row.state == "COMPLETE") continue
            runCatching { router.declineIncoming(row) }
                .onFailure { Log.w(TAG, "decline extra offer ${row.transferId} failed: ${it.message}") }
        }
    }

    /**
     * Группа, где мы с [senderId] оба состоим и есть визитка файла [sha256];
     * в открытом сообществе отправитель может отсутствовать в моём (неполном)
     * списке участников. null - нет такой.
     */
    private suspend fun groupWithCardFrom(senderId: String, sha256: String): String? {
        val me = myId() ?: return null
        for (membership in groupDao.getMyMemberships(me)) {
            val groupId = membership.groupId
            val sender = groupDao.getMember(groupId, senderId)
            if (sender?.isBanned == true) continue
            if (sender == null && groupDao.getGroupById(groupId)?.isPublic != true) continue
            if (cardInfo(groupId, sha256) != null) return groupId
        }
        return null
    }

    /**
     * Состоим ли с узлом хотя бы в одном общем сообществе - или это сид, у
     * которого я просил файл (на большом канале участники друг друга по
     * списку не знают, а предложение от него всё равно должно дойти).
     */
    suspend fun sharesGroupWith(nodeId: String): Boolean {
        val me = myId() ?: return false
        if (runCatching { groupDao.countSharedGroups(me, nodeId) > 0 }.getOrDefault(false)) return true
        restoreIfNeeded()
        // Известный сид (я просил у него или он объявлял «файл у меня»):
        // само предложение приёмник всё равно проверяет - адресат, подпись
        // ключа, хэш файла по завершении.
        return mutex.withLock { seeds.values.any { nodeId in it } }
    }

    /**
     * Передача завершилась приёмом. Если чат - группа, это файл группы: снять
     * просьбу, объявить соседям «файл у меня». Возвращает true, если событие
     * обработано здесь (личный чат его не получает).
     */
    suspend fun onFileReceived(chatId: String, senderId: String, fileSha256: String): Boolean {
        val group = groupDao.getGroupById(chatId) ?: return false
        restoreIfNeeded()
        val key = GroupFileMarker.key(group.id, fileSha256)
        val removed = mutex.withLock {
            val entry = pending.remove(key)
            publishPending()
            entry
        }
        if (removed != null) persistPending()
        // Файл получен: второе предложение того же файла (запасной сид
        // успел ответить) больше не нужно - его строку убираем, а сиду
        // уходит CANCEL, чтобы он освободил место. В фоне: сюда приходят из
        // приёмника под его замком, а отказ идёт через тот же приёмник.
        scope.launch {
            runCatching { cancelExtraOffers(group.id, fileSha256) }
                .onFailure { Log.w(TAG, "cancel extra offers failed: ${it.message}") }
        }
        val messageId = removed?.messageId ?: cardMessageId(group.id, fileSha256) ?: return true
        val me = myId() ?: return true
        rememberSeed(key, senderId, first = false)
        rememberSeed(key, me, first = false)
        val members = groupDao.getMembers(group.id).asSequence()
            .filter { !it.isBanned && it.nodeId != me && it.nodeId != senderId }
            .map { it.nodeId }.toList()
        if (members.isEmpty()) return true
        val ordered = runCatching { directory.order(members) }.getOrDefault(members)
        val targets = ordered.take(HAVE_FANOUT)
        val envelope = GroupWire.buildFileHave(group.id, fileSha256, messageId)
        scope.launch {
            runCatching { delivery.deliver(group.id, envelope, targets) }
                .onFailure { Log.w(TAG, "file have fanout failed: ${it.message}") }
        }
        Log.i(TAG, "group file received key=${fileSha256.take(12)} group=${group.id} announced=${targets.size}")
        return true
    }

    // ── Сид ──────────────────────────────────────────────────────────────────

    /** Участник просит файл: если он у меня есть и есть место - готовлю ему передачу. */
    suspend fun onFileWant(senderId: String, packet: GroupWire.Packet.FileWant) {
        val me = myId() ?: return
        if (senderId == me) return
        val group = groupDao.getGroupById(packet.groupId) ?: return
        if (groupDao.getMember(group.id, me) == null) return
        // Закрытое сообщество - только известным участникам; открытое - и
        // незнакомому (на большом канале список участников до всех не
        // доходит: новичок получает `peers`, а не полный состав). Забаненным -
        // никогда. То же правило, что у просьбы о постах (`preq`).
        val requester = groupDao.getMember(group.id, senderId)
        if (requester?.isBanned == true) return
        if (requester == null && !group.isPublic) return
        val now = System.currentTimeMillis()
        val serveKey = GroupFileMarker.key(group.id, packet.sha256) + "|" + senderId
        val last = servedAt[serveKey] ?: 0L
        if (now - last < SERVE_MIN_INTERVAL_MS) return
        servedAt[serveKey] = now
        if (servedAt.size > MAX_SERVED_MEMO) servedAt.entries.removeIf { now - it.value > SERVE_MIN_INTERVAL_MS }
        // Ключ обмена просителя: закрепляем, чтобы запечатать ему конверт с
        // ключом файла. Чужой ключ (подписан не им) или смена ключа - не
        // закрепляем: проверка та же, что у HELLO файловой передачи.
        if (packet.binding.isNotEmpty()) {
            runCatching {
                check(uniffi.p2p_core.verifyFileExchangeBinding(packet.binding)) { "bad signature" }
                check(uniffi.p2p_core.fileExchangeBindingNodeId(packet.binding) == senderId) { "binding of another node" }
                peerStore.pinFirstSeen(packet.binding, now)
                com.vladimir.messenger.data.security.MessageSealer.remember(appContext, senderId, packet.binding)
            }.onFailure { Log.w(TAG, "requester binding not pinned (${senderId.takeLast(8)}): ${it.message}") }
        }
        // Полосы (K2): проситель понимает общие манифесты - отдаём из общей
        // копии (авторской или своей входящей), а не готовим личную.
        if (packet.groupCapable && serveShared(group.id, packet, senderId, now) != Shared.NONE) return
        // Общей копии нет (файл получен старой личной передачей) - по-старому.
        // Уже отдаю ему этот файл: не дублировать. Ждал его в сети - разбудить.
        val existing = transferDao.getForFile(group.id, packet.sha256)
            .firstOrNull { it.direction == "OUTGOING" && it.peerNodeId == senderId && it.state !in FINISHED_STATES }
        if (existing != null) {
            if (existing.state == "WAITING_RECIPIENT" || existing.state == "CUSTODIED") {
                router.resumeWaitingForRecipient()
            }
            router.pumpOutgoing()
            return
        }
        if (sourceFor(group.id, packet.sha256) == null) {
            Log.i(TAG, "file want from ${senderId.takeLast(8)} for ${packet.sha256.take(12)}: not here")
            // Этап 11: сказать просителю сразу, а не оставлять его ждать
            // полминуты тишины - он спросит следующего сида. Старая версия
            // просителя пакет молча отбросит и подождёт, как раньше.
            scope.launch {
                runCatching { delivery.deliver(group.id, GroupWire.buildFileNone(group.id, packet.sha256), listOf(senderId)) }
                    .onFailure { Log.w(TAG, "file none reply failed: ${it.message}") }
            }
            return
        }
        val enqueue = mutex.withLock {
            if (waiting.size >= MAX_WAITING) return@withLock false
            waiting[serveKey] = Waiting(group.id, packet.sha256, packet.messageId, senderId, now)
            true
        }
        if (!enqueue) return
        serveWaiting()
    }

    private enum class Shared { SENT, NO_KEY, UNREACHABLE, BUSY, NONE }

    /** Подготовка общей копии - по одной на файл: две просьбы разом не должны родить две копии. */
    private val prepLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Отдать просителю общую копию (K2). Источник - моя авторская копия
     * (`SEEDING`; готовится один раз из файла в [GroupFileStore]) или моя
     * входящая, собранная из общего манифеста (куски и ключ остались от
     * приёма). NONE - общей копии нет и сделать нечего: файл получен по
     * старой личной передаче или его вообще нет.
     */
    private suspend fun serveShared(groupId: String, packet: GroupWire.Packet.FileWant, requester: String, now: Long): Shared {
        val seeder = router.groupSeeder
        val source = prepLocks.getOrPut(GroupFileMarker.key(groupId, packet.sha256)) { Mutex() }.withLock {
            sharedSource(groupId, packet, now)
        } ?: return Shared.NONE
        return when (seeder.offer(source, requester)) {
            com.vladimir.messenger.data.file.GroupFileSeeder.OfferResult.SENT -> Shared.SENT
            com.vladimir.messenger.data.file.GroupFileSeeder.OfferResult.NO_KEY -> {
                // Ключа просителя нет (в просьбе его не было или он не прошёл):
                // попросим HELLO; проситель повторит просьбу сам.
                router.requestExchangeBinding(requester)
                Shared.NO_KEY
            }
            com.vladimir.messenger.data.file.GroupFileSeeder.OfferResult.UNREACHABLE -> {
                Log.i(TAG, "stripe: ${requester.takeLast(8)} not reachable directly for ${packet.sha256.take(12)}")
                Shared.UNREACHABLE
            }
            com.vladimir.messenger.data.file.GroupFileSeeder.OfferResult.BUSY -> {
                // Все плечи заняты: в очередь, serveWaiting дойдёт до него.
                val serveKey = GroupFileMarker.key(groupId, packet.sha256) + "|" + requester
                mutex.withLock {
                    if (waiting.size < MAX_WAITING) waiting[serveKey] = Waiting(groupId, packet.sha256, packet.messageId, requester, now, shared = true)
                }
                Shared.BUSY
            }
        }
    }

    /** Строка общей копии файла (готовая или только что подготовленная автором); null - её нет. */
    private suspend fun sharedSource(
        groupId: String,
        packet: GroupWire.Packet.FileWant,
        now: Long,
    ): com.vladimir.messenger.data.local.entity.FileTransferEntity? {
        val seeder = router.groupSeeder
        val rows = transferDao.getForFile(groupId, packet.sha256)
        var source = rows.firstOrNull { seeder.canSeed(it, router::hasTransferKey) }
        if (source == null) {
            // Автор: общая копия ещё не готовилась (первая просьба) - готовим
            // из авторского файла; это долго (хэш + шифрование), но один раз.
            val own = runCatching { store.file(groupId, packet.sha256) }.getOrNull()
            if (own == null || !own.isFile) return null
            val info = cardInfo(groupId, packet.sha256)
                ?: GroupFileMarker.Info(packet.sha256, own.length(), "application/octet-stream", own.name)
            val prepared: OutgoingFilePreparationService.PreparedTransfer? = try {
                preparation.prepareGroupCopy(
                    source = own,
                    displayName = info.displayName,
                    mediaType = info.mediaType,
                    messageId = packet.messageId,
                    groupId = groupId,
                    expectedSha256 = packet.sha256,
                    nowMs = now,
                )
            } catch (error: Exception) {
                Log.w(TAG, "group copy prepare failed for ${packet.sha256.take(12)}: ${error.message}")
                null
            }
            if (prepared == null) return null
            source = transferDao.getTransfer(prepared.transferId) ?: return null
            Log.i(TAG, "group copy ready ${packet.sha256.take(12)} transfer=${prepared.transferId} (${prepared.chunkCount} chunks)")
        }
        return source
    }

    /** Где лежит файл: авторская копия или полученный от других. */
    private suspend fun sourceFor(groupId: String, sha256: String): Pair<File, GroupFileMarker.Info>? {
        val info = cardInfo(groupId, sha256)
        val own = runCatching { store.file(groupId, sha256) }.getOrNull()
        if (own != null && own.isFile) {
            return own to (info ?: GroupFileMarker.Info(sha256, own.length(), "application/octet-stream", own.name))
        }
        val received = transferDao.getForFile(groupId, sha256)
            .firstOrNull { it.direction == "INCOMING" && it.state == "COMPLETE" } ?: return null
        val file = router.receivedFileFor(received) ?: return null
        return file to GroupFileMarker.Info(sha256, received.totalBytes, received.mediaType, received.displayName)
    }

    /** Отдать из очереди, сколько позволяют места. */
    private suspend fun serveWaiting() {
        val now = System.currentTimeMillis()
        val active = activeSeeding(now)
        var free = MAX_PARALLEL_SEEDS - active
        // Общие копии (K2) считаются по плечам сидера, личные - по передачам.
        var freeShared = com.vladimir.messenger.data.file.GroupFileSeeder.MAX_LEGS - router.groupSeeder.activeLegs()
        if (free <= 0 && freeShared <= 0) return
        val batch = mutex.withLock {
            waiting.entries.removeIf { now - it.value.sinceMs > WAITING_TTL_MS }
            val picked = ArrayList<Waiting>()
            val iterator = waiting.entries.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next().value
                if (item.shared) {
                    if (freeShared <= 0) continue
                    freeShared--
                } else {
                    if (free <= 0) continue
                    free--
                }
                picked.add(item)
                iterator.remove()
            }
            picked
        }
        for (item in batch) serve(item, now)
    }

    /** Сколько передач я сейчас отдаю как сид (без ждущих получателя). */
    private suspend fun activeSeeding(now: Long): Int =
        transferDao.getActiveOutgoing(now).count { it.transferId in seeded || isGroupChat(it.chatId) }

    private suspend fun serve(item: Waiting, now: Long): Boolean {
        if (item.shared) {
            val packet = GroupWire.Packet.FileWant(item.groupId, item.sha256, item.messageId, ByteArray(0), groupCapable = true)
            return serveShared(item.groupId, packet, item.requester, now) == Shared.SENT
        }
        val source = sourceFor(item.groupId, item.sha256) ?: return false
        val (file, info) = source
        return try {
            val prepared = preparation.prepareFromFile(
                source = file,
                displayName = info.displayName,
                mediaType = info.mediaType,
                messageId = item.messageId,
                chatId = item.groupId,
                recipientNodeId = item.requester,
                nowMs = now,
            )
            if (prepared.fileSha256 != item.sha256) {
                // Копия не та, что обещана визиткой: раздавать нельзя.
                Log.w(TAG, "group file copy hash mismatch for ${item.sha256.take(12)}; dropped")
                router.dropTransfer(prepared.transferId)
                runCatching { store.delete(item.groupId, item.sha256) }
                return false
            }
            seeded.add(prepared.transferId)
            Log.i(
                TAG,
                "seeding ${item.sha256.take(12)} (${info.sizeBytes} B) to ${item.requester.takeLast(8)} " +
                    "transfer=${prepared.transferId}",
            )
            scope.launch { runCatching { router.pumpOutgoing() } }
            true
        } catch (error: Exception) {
            val message = error.message.orEmpty()
            if (message.contains("binding is not pinned")) {
                // Ключа просителя нет (в просьбе его не было): попросим и
                // вернём просьбу в очередь - следующий круг попробует снова.
                router.requestExchangeBinding(item.requester)
                val key = GroupFileMarker.key(item.groupId, item.sha256) + "|" + item.requester
                mutex.withLock {
                    if (waiting.size < MAX_WAITING) {
                        waiting[key] = item
                    }
                }
            }
            Log.w(TAG, "seed prepare failed for ${item.requester.takeLast(8)}: $message")
            false
        }
    }

    // ── Насос ────────────────────────────────────────────────────────────────

    /**
     * Раз в цикл файлового насоса (20 с): повторить свои просьбы, отдать из
     * очереди, освободить куски отданного, убрать старые копии.
     */
    suspend fun pump() {
        if (!RustBridge.isRunning()) return
        val now = System.currentTimeMillis()
        runCatching { reask(now) }.onFailure { Log.w(TAG, "reask failed: ${it.message}") }
        runCatching { serveWaiting() }.onFailure { Log.w(TAG, "serve failed: ${it.message}") }
        runCatching { releaseServed(now) }.onFailure { Log.w(TAG, "release failed: ${it.message}") }
        if (now - lastSweepAt > SWEEP_INTERVAL_MS) {
            lastSweepAt = now
            runCatching { store.sweep(now) }.onFailure { Log.w(TAG, "sweep failed: ${it.message}") }
        }
    }

    private suspend fun reask(now: Long) {
        restoreIfNeeded()
        var changed = false
        val due = mutex.withLock {
            val expired = pending.values.filter { now - it.startedAtMs > PENDING_TTL_MS }.map {
                GroupFileMarker.key(it.groupId, it.sha256)
            }
            expired.forEach { pending.remove(it) }
            if (expired.isNotEmpty()) {
                publishPending()
                changed = true
            }
            pending.values.toList()
        }
        for (entry in due) {
            val key = GroupFileMarker.key(entry.groupId, entry.sha256)
            val rows = transferDao.getForFile(entry.groupId, entry.sha256)
                .filter { it.direction == "INCOMING" && it.state != "FAILED" }
            if (rows.any { it.state == "COMPLETE" }) {
                mutex.withLock { pending.remove(key); publishPending() }
                changed = true
                continue
            }
            // Передача идёт (куски приходят): сид повторяет предложение сам.
            // Стоит дольше STALL_MS - сид пропал, спрашиваем следующего.
            if (rows.any { now - it.updatedAtMs < STALL_MS }) continue
            // Полосы (K2): раз стоит, сиды, что слали полосы, пропали - их
            // можно спрашивать снова наравне с остальными.
            entry.striping.clear()
            if (entry.attempts >= MAX_ATTEMPTS) {
                mutex.withLock { pending.remove(key); publishPending() }
                changed = true
                Log.i(TAG, "file request given up key=${entry.sha256.take(12)} after ${entry.attempts} attempts")
                continue
            }
            // Запасной сид (этап 10). Сидов известно несколько, а первый не
            // ответил (не в сети, занят очередью) или пропал на полпути
            // (передача стоит): другого спрашиваем через REASK_FAST_MS, не
            // дожидаясь растущего интервала. Так лишь первые FAST_ATTEMPTS
            // раз - дальше обычный шаг, чтобы не долбить группу зря. Лишнее
            // предложение получатель отклонит (CANCEL), сид освободит место.
            val seedCount = knownSeedCount(entry)
            val untried = entry.tried.size < seedCount
            val fast = seedCount >= 2 && untried && entry.attempts < FAST_ATTEMPTS
            val interval = if (fast) {
                // Большому файлу сид готовит копию дольше (хэш и шифрование):
                // даём ему на это время, иначе второй сид начнёт зря.
                (REASK_FAST_MS + entry.info.sizeBytes / PREP_BYTES_PER_MS).coerceAtMost(REASK_BASE_MS)
            } else {
                (REASK_BASE_MS * entry.attempts.coerceAtLeast(1)).coerceAtMost(REASK_MAX_MS)
            }
            if (now - entry.askedAtMs < interval) continue
            ask(entry, now)
        }
        if (changed) persistPending()
    }

    /**
     * Узел появился в сети (пульс присутствия). Если он - известный сид
     * файла, который я жду, спрашиваю сразу, не дожидаясь очередного круга.
     * Если я сид и отдавал ему файл, а он пропадал, - передача просыпается.
     */
    suspend fun onPeerOnline(nodeId: String) {
        val now = System.currentTimeMillis()
        restoreIfNeeded()
        val waitingForHim = transferDao.getWaitingRecipient().any { it.peerNodeId == nodeId && isGroupChat(it.chatId) }
        if (waitingForHim) {
            runCatching { router.resumeWaitingForRecipient() }
                .onFailure { Log.w(TAG, "resume for ${nodeId.takeLast(8)} failed: ${it.message}") }
        }
        val due = mutex.withLock {
            pending.values.filter { entry ->
                val key = GroupFileMarker.key(entry.groupId, entry.sha256)
                seeds[key]?.contains(nodeId) == true && now - entry.askedAtMs > PRESENCE_REASK_MIN_MS
            }
        }
        for (entry in due) {
            if (hasLiveTransfer(entry.groupId, entry.sha256, now)) continue
            entry.tried.clear()
            entry.tried.addAll(mutex.withLock { seeds[GroupFileMarker.key(entry.groupId, entry.sha256)]?.filter { it != nodeId }.orEmpty() })
            entry.striping.remove(nodeId) // вернулся - пусть шлёт полосу заново (K2)
            ask(entry, now)
        }
    }

    /**
     * Куски отданной передачи нужны только до итогового подтверждения: копия
     * у каждого просителя своя (конверт под его ключ), и на большой группе
     * они бы съели диск автора. Освобождаем сразу. Ждущие получателя дольше
     * [SEED_WAIT_TTL_MS] - убираем: проситель попросит снова, когда появится.
     */
    private suspend fun releaseServed(now: Long) {
        if (!cleanedLeftovers) {
            cleanedLeftovers = true
            for (row in transferDao.getCompleted()) {
                if (row.direction == "OUTGOING" && isGroupChat(row.chatId)) router.releaseOutgoingChunks(row.transferId)
            }
        }
        for (id in seeded.toList()) {
            val row = transferDao.getTransfer(id)
            if (row == null) {
                seeded.remove(id)
                continue
            }
            when {
                row.state == "COMPLETE" -> {
                    router.releaseOutgoingChunks(id)
                    seeded.remove(id)
                }
                row.state in FINISHED_STATES -> seeded.remove(id)
                (row.state == "WAITING_RECIPIENT" || row.state == "CUSTODIED") &&
                    now - row.updatedAtMs > SEED_WAIT_TTL_MS -> {
                    router.dropTransfer(id)
                    seeded.remove(id)
                }
            }
        }
    }

    // ── Мелочи ───────────────────────────────────────────────────────────────

    private fun myId(): String? = RustBridge.nodeId()

    private suspend fun hasTransfer(groupId: String, sha256: String): Boolean =
        transferDao.getForFile(groupId, sha256).any { it.direction == "INCOMING" && it.state != "FAILED" }

    /** Файл получен или его приём идёт прямо сейчас (куски приходили не позже STALL_MS назад). */
    private suspend fun hasLiveTransfer(groupId: String, sha256: String, nowMs: Long): Boolean =
        transferDao.getForFile(groupId, sha256).any {
            it.direction == "INCOMING" && it.state != "FAILED" &&
                (it.state == "COMPLETE" || nowMs - it.updatedAtMs < STALL_MS)
        }

    /** Приём стоит: последний кусок был давно (карточка предлагает «Скачать снова»). */
    fun isStalled(transfer: com.vladimir.messenger.data.local.entity.FileTransferEntity, nowMs: Long = System.currentTimeMillis()): Boolean =
        transfer.direction == "INCOMING" && transfer.state != "COMPLETE" && transfer.state != "FAILED" &&
            nowMs - transfer.updatedAtMs >= STALL_MS

    private val groupChatMemo = ConcurrentHashMap<String, Boolean>()

    /** Чат передачи - сообщество (файл группы), а не личный чат. */
    suspend fun isGroupChat(chatId: String): Boolean {
        groupChatMemo[chatId]?.let { return it }
        val isGroup = groupDao.getGroupById(chatId) != null
        if (groupChatMemo.size > 256) groupChatMemo.clear()
        groupChatMemo[chatId] = isGroup
        return isGroup
    }

    private suspend fun rememberSeed(key: String, nodeId: String, first: Boolean) {
        if (nodeId.isBlank()) return
        mutex.withLock {
            val set = seeds.getOrPut(key) { LinkedHashSet<String>() }
            if (first && set.isEmpty()) {
                set.add(nodeId)
            } else if (nodeId !in set && set.size < MAX_SEEDS_PER_KEY) {
                set.add(nodeId)
            }
        }
    }

    /** Визитка файла из сообщения группы (имя и тип для раздачи полученного). */
    private suspend fun cardInfo(groupId: String, sha256: String): GroupFileMarker.Info? =
        messageDao.getByContentPattern(groupId, "%" + GroupFileMarker.PREFIX + sha256 + ":%")
            .asSequence().mapNotNull { GroupFileMarker.parse(it.content) }
            .firstOrNull { it.sha256 == sha256 }

    private suspend fun cardMessageId(groupId: String, sha256: String): String? =
        messageDao.getByContentPattern(groupId, "%" + GroupFileMarker.PREFIX + sha256 + ":%")
            .firstOrNull { GroupFileMarker.parse(it.content)?.sha256 == sha256 }?.id

    private fun publishPending() {
        _pendingKeys.value = pending.keys.toSet()
    }

    /**
     * Снимок своих просьб - на диск (этап 10). Пишется после каждой отправки
     * просьбы и после снятия; файл маленький, запись в фоне. Ошибка записи
     * не мешает работе: в худшем случае после перезапуска просьба забудется,
     * как было до v11.70.20.
     */
    private fun persistPending() {
        scope.launch {
            val snapshot = mutex.withLock {
                pending.values.map { entry ->
                    val key = GroupFileMarker.key(entry.groupId, entry.sha256)
                    GroupFileRequestStore.Record(
                        groupId = entry.groupId,
                        info = entry.info,
                        messageId = entry.messageId,
                        startedAtMs = entry.startedAtMs,
                        manual = entry.manual,
                        attempts = entry.attempts,
                        askedSeed = entry.askedSeed.orEmpty(),
                        askedAtMs = entry.askedAtMs,
                        seeds = seeds[key]?.toList().orEmpty(),
                    )
                }
            }
            runCatching { requestStore.save(snapshot) }
                .onFailure { Log.w(TAG, "persist pending failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "GroupFileSwarm"
        /** Столько передач отдаю одновременно как сид; остальные просьбы ждут. */
        const val MAX_PARALLEL_SEEDS = 3
        /** Полосы (K2): до стольких сидов зовём на один файл, по одному по мере принятия предложений (приёмник берёт до MAX_GROUP_SEEDS). */
        const val STRIPE_SEEDS = 3
        /** Очередь чужих просьб и её срок. */
        const val MAX_WAITING = 200
        const val WAITING_TTL_MS = 6L * 60 * 60 * 1000
        /** Повтор своей просьбы: с 90 с, растёт с числом попыток до 10 мин; всего сутки. */
        const val REASK_BASE_MS = 90_000L
        const val REASK_MAX_MS = 10L * 60 * 1000
        /**
         * Запасной сид (этап 10): когда сидов известно несколько, а первый не
         * ответил, второго спрашиваем уже через столько (плюс время на
         * подготовку копии: [PREP_BYTES_PER_MS]), и так первые [FAST_ATTEMPTS] раз.
         */
        const val REASK_FAST_MS = 30_000L
        const val FAST_ATTEMPTS = 3
        /** Сид шифрует копию примерно с такой скоростью (нижняя оценка для старых телефонов). */
        const val PREP_BYTES_PER_MS = 2L * 1024
        const val PENDING_TTL_MS = 24L * 60 * 60 * 1000
        const val MAX_ATTEMPTS = 40
        /** Приём без новых кусков дольше этого - застрял: просим у следующего сида. */
        const val STALL_MS = 3L * 60 * 1000
        const val MANUAL_REASK_MIN_MS = 30_000L
        const val PRESENCE_REASK_MIN_MS = 45_000L
        /** Карточка старше этого сама файл не тянет (авторская копия живёт неделю). */
        const val AUTO_FETCH_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
        /** Скольким соседям сообщаю «файл у меня». */
        const val HAVE_FANOUT = 6
        /** Автоматически тянем файлы до этого размера (на мобильном интернете - до меньшего). */
        const val AUTO_FETCH_BYTES = 16L * 1024 * 1024
        const val AUTO_FETCH_METERED_BYTES = 2L * 1024 * 1024
        /** Режим «Без ограничений»: тянем и большие, но не гигабайты без спроса. */
        const val AUTO_FETCH_UNLIMITED_BYTES = 256L * 1024 * 1024
        const val AUTO_FETCH_JITTER_MS = 4_000L
        const val SERVE_MIN_INTERVAL_MS = 60_000L
        const val MAX_SERVED_MEMO = 2_000
        const val MAX_SEEDS_PER_KEY = 16
        const val MAX_KEYS = 512
        /** Отданная передача, ждущая получателя дольше этого, убирается (проситель попросит снова). */
        const val SEED_WAIT_TTL_MS = 2L * 60 * 60 * 1000
        const val SWEEP_INTERVAL_MS = 60L * 60 * 1000
        private val FINISHED_STATES = setOf("COMPLETE", "CANCELLED", "FAILED", "EXPIRED")
    }
}
