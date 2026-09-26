package com.vladimir.messenger.data.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.peer.PeerRatingStore
import com.vladimir.messenger.data.repository.ChatRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Стикер из каталога РОЯ (раунд 139): чей-то стикер, который хранят другие
 * телефоны нашей сети. [holders] - кто хранит (свежий первым).
 */
data class SwarmSticker(
    val sha256: String,
    val name: String,
    val sizeBytes: Long,
    val atMs: Long,
    val holders: List<String>,
)

/**
 * Служебный конверт каталога стикеров (разбирается ДО сохранения в чат,
 * как у гифок): E2E-зашифрованное личное сообщение, наружу не видно.
 */
data class StickerPacket(
    val kind: String, // "ask" | "have" | "want" | "thumb" | "thmb"
    val index: Int,
    val total: Int,
    val items: List<SwarmSticker>,
    val sha256: String,
    /** Хвост кадра (например, base64 миниатюры) для kind = "thmb". */
    val payload: String = "",
)

/**
 * Стикеры: библиотека телефона + РОЕВОЙ КАТАЛОГ сети (раунды 138-139).
 * Стикер уезжает в чат картинкой (файловой машиной), а каталоги живут так:
 *
 *  - «Мои» - добавленные человеком (кнопка «+» в панели, из хранилища);
 *  - «Недавние» - что последним отправлял (до [MAX_RECENTS] штук);
 *  - «Из сети» - стикеры других телефонов роя: телефоны обмениваются
 *    каталогами служебными конвертами APUSTK1 (едут как обычные личные
 *    сообщения, E2E-зашифрованы, наружу не видны). Выбрал чужой стикер -
 *    байты тихо доезжают от хранителей защищённой передачей файлов
 *    (просьба трём лучшим), стикер ложится в «Мои» и отправляется.
 *
 * Формат - как у ApkUpdateStore: строка на запись, поля через `|`,
 * имя в base64url, атомарная перезапись, испорченная строка пропускается.
 */
@Singleton
class StickerLibrary @Inject constructor(
    @ApplicationContext context: Context,
) {
    /** Один стикер библиотеки: файл и метаданные для сетки панели. */
    data class StickerEntry(
        val sha256: String,
        val file: File,
        val name: String,
        val atMs: Long,
    )

    private val appContext = context.applicationContext
    private val root: File = File(appContext.noBackupFilesDir, DIR_NAME)

    // ── Мои стикеры ─────────────────────────────────────────────────────────

    /**
     * Раунд 165: альбом стикеров архивом .zip - в библиотеку все картинки
     * (webp/png/jpg/gif/bmp; видео-webm пропускаем). Дубли по sha не пишутся.
     * Возврат: «добавлено» к «всего картинок», null - архив не открылся.
     */
    fun addZip(uri: android.net.Uri): kotlin.Pair<Int, Int>? {
        val bytes = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        var added = 0
        var total = 0
        runCatching {
            val zip = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes))
            val imageExts = setOf("webp", "png", "jpg", "jpeg", "gif", "bmp")
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/')
                    val ext = name.substringAfterLast('.', "").lowercase()
                    // Раунд 170: webm - анимированные стикеры генераторов,
                    // храним как есть (рисует StickerAnimated покадрово).
                    if (ext in imageExts || ext == "webm") {
                        total++
                        if (addBytes(zip.readBytes(), name.substringBeforeLast('.')) != null) added++
                    }
                }
                zip.closeEntry()
            }
            zip.close()
        }
        return added to total
    }

    /** Раунд 163: файл стикера по sha - «Избранное» рисует и делится им. */
    @Synchronized
    fun fileOf(sha256: String): java.io.File? =
        File(root, "$sha256.img").takeIf { it.isFile }

    @Synchronized
    fun all(): List<StickerEntry> {
        val file = File(root, INDEX_FILE)
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrElse { return emptyList() }
        if (lines.firstOrNull()?.trim() != INDEX_HEADER) return emptyList()
        return lines.asSequence().drop(1).mapNotNull { parse(it) }
            .sortedByDescending { it.atMs }
            .filter { it.file.isFile }
            .toList()
    }

    /**
     * Добавить стикер из хранилища (SAF/галерея). Читаем байты, считаем
     * sha256, кладём копию к себе (исходник может исчезнуть). Дубль по
     * sha256 не добавляется второй раз.
     */
    @Synchronized
    fun add(uri: android.net.Uri): StickerEntry? {
        val bytes = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
        }.getOrNull() ?: return null
        return addBytes(bytes, null)
    }

    /**
     * Поселить стикер из готовых байтов (раунд 139): из хранилища или
     * ТИХО от хранителя роя, чьи байты приехали защищённой передачей
     * файлов. Дубль по sha256 не добавляется второй раз.
     */
    @Synchronized
    fun addBytes(bytes: ByteArray, name: String?): StickerEntry? {
        if (bytes.isEmpty() || bytes.size > MAX_STICKER_BYTES) return null
        val sha = sha256(bytes)
        check(root.mkdirs() || root.isDirectory) { "Cannot create sticker directory" }
        val file = File(root, "$sha.img")
        if (!file.isFile) {
            val temporary = File(root, "$sha.img.tmp")
            runCatching {
                temporary.writeBytes(bytes)
                try {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }.onFailure {
                temporary.delete()
                return null
            }
        }
        if (all().any { it.sha256 == sha }) {
            emitArrival(sha)
            return entryOf(sha)
        }
        val display = name?.takeIf { it.isNotBlank() } ?: "sticker_${sha.take(8)}.png"
        val body = buildString {
            append(INDEX_HEADER).append('\n')
            for (entry in all()) append(format(entry)).append('\n')
            append(INDEX_HEADER).append('|').append(sha).append('|')
                .append(encode(display.take(60))).append('|')
                .append(System.currentTimeMillis()).append('\n')
        }
        val saved = writeIndex(body)
        if (saved) emitArrival(sha)
        return if (saved) entryOf(sha) else null
    }

    /** Стикер по sha (для «Недавних», чьи строки хранят только sha). */
    @Synchronized
    fun entryOf(sha256: String): StickerEntry? =
        all().firstOrNull { it.sha256 == sha256 }

    // ── Недавние ────────────────────────────────────────────────────────────

    /** Последние отправленные: sha + время, свежие вверху. */
    @Synchronized
    fun recents(): List<StickerEntry> {
        val file = File(root, RECENTS_FILE)
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrElse { return emptyList() }
        if (lines.firstOrNull()?.trim() != RECENTS_HEADER) return emptyList()
        return lines.asSequence().drop(1)
            .mapNotNull { row ->
                val sha = row.substringBefore('|')
                if (sha.isBlank()) null else sha
            }
            .mapNotNull { entryOf(it) }
            .take(MAX_RECENTS)
            .toList()
    }

    /** Запомнить отправленный стикер наверху «Недавних». */
    @Synchronized
    fun touch(sha256: String) {
        if (entryOf(sha256) == null) return
        val previous = runCatching {
            val file = File(root, RECENTS_FILE)
            if (!file.isFile) emptyList()
            else file.readLines(StandardCharsets.UTF_8)
                .drop(1)
                .map { it.substringBefore('|') }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        val next = (listOf(sha256) + previous.filter { it != sha256 }).take(MAX_RECENTS)
        val body = buildString {
            append(RECENTS_HEADER).append('\n')
            for (sha in next) append(sha).append('|').append(System.currentTimeMillis()).append('\n')
        }
        write(RECENTS_FILE, body)
    }

    // ── Роевой каталог стикеров (раунд 139): как у гифок ────────────────────

    /**
     * Мой каталог порциями под потолок личного конверта (по символам).
     * Строка порции: APUSTK1|have|<i>|<n>|<json>, json - массив строк
     * [sha, имя, размер, время].
     */
    suspend fun buildHaveBatches(): List<String> = withContext(Dispatchers.IO) {
        val items = all()
        val batches = mutableListOf<JSONArray>()
        var current = JSONArray()
        var currentLen = 0
        for (e in items) {
            val row = JSONArray()
                .put(e.sha256)
                .put(e.name.take(60))
                .put(e.file.length())
                .put(e.atMs)
            val rowLen = row.toString().length
            if (current.length() > 0 && currentLen + rowLen + 1 > MAX_BATCH_CHARS) {
                batches.add(current)
                current = JSONArray()
                currentLen = 0
            }
            current.put(row)
            currentLen += rowLen + 1
        }
        if (current.length() > 0) batches.add(current)
        val total = batches.size
        batches.mapIndexed { i, arr -> "$WIRE_PREFIX|have|$i|$total|$arr" }
    }

    /**
     * Разослать свой каталог стикеров своим и попросить ихние. Вызывается
     * при старте сервиса и при открытии панели. Тротлимб в prefs:
     * объявление - раз в 10 минут на телефон (сразу - если библиотека
     * изменилась), просьба - раз в сутки. Как у гифок (раунд 121).
     */
    suspend fun syncWithSwarm(
        chatRepository: ChatRepository,
        force: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Раунд 171: сети нет вовсе - не жжём по 5-10 секунд на каждый
        // send офлайн (ANR-шторм при пропавшей связи).
        if (!RustBridge.isNetworkUp()) return@withContext
        val items = all()
        val fingerprint = "${items.size}:${items.firstOrNull()?.sha256.orEmpty()}"
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val changed = prefs.getString("fp", "") != fingerprint
        val contactIds = runCatching { chatRepository.getAllContactIds() }.getOrDefault(emptyList())
        // Дедлайн цикла: даже при пропавшей связи посреди синка не копим
        // многосекундные блокировки за движком.
        val deadline = now + 8_000L
        for (peer in contactIds.filter { it.isNotBlank() }.distinct()) {
            if (System.currentTimeMillis() > deadline) break
            val chat = chatRepository.getChatByContactId(peer) ?: continue
            val announceDue = force || changed ||
                now - prefs.getLong("ann_$peer", 0L) > 6 * 3600_000L
            // Раунд 167: force - объявить СРАЗУ (только что добавили стикеры
            // - рой узнаёт немедленно), без 10-минутной вежливости.
            if (announceDue &&
                (force || now - prefs.getLong("ann_$peer", 0L) > 10 * 60_000L)
            ) {
                val batches = buildHaveBatches()
                var sent = true
                for (b in batches) {
                    sent = sent && RustBridge.sendMessage(
                        UUID.randomUUID().toString(), chat.id, peer, b,
                    )
                }
                if (sent) {
                    prefs.edit()
                        .putLong("ann_$peer", now)
                        .putString("fp", fingerprint)
                        .apply()
                    Log.i(TAG, "sticker catalog announced to ${peer.takeLast(8)}: ${batches.size} batch(es)")
                }
            }
            if (now - prefs.getLong("ask_$peer", 0L) > 24 * 3600_000L) {
                val sent = RustBridge.sendMessage(
                    UUID.randomUUID().toString(), chat.id, peer, "$WIRE_PREFIX|ask",
                )
                if (sent) prefs.edit().putLong("ask_$peer", now).apply()
            }
        }
    }

    // ── Общее ───────────────────────────────────────────────────────────────

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun format(entry: StickerEntry): String =
        INDEX_HEADER + "|" + entry.sha256 + "|" + encode(entry.name) + "|" + entry.atMs

    private fun parse(line: String): StickerEntry? {
        val parts = line.split('|')
        if (parts.size != 4) return null
        val sha = parts[1]
        val name = decode(parts[2]) ?: return null
        val at = parts[3].toLongOrNull() ?: return null
        if (sha.length != 64 || sha.any { it !in '0'..'9' && it !in 'a'..'f' } ||
            name.isBlank() || at < 0L
        ) {
            return null
        }
        return StickerEntry(sha, File(root, "$sha.img"), name, at)
    }

    private fun writeIndex(body: String): Boolean = write(INDEX_FILE, body)

    private fun write(fileName: String, body: String): Boolean {
        check(root.mkdirs() || root.isDirectory) { "Cannot create sticker directory" }
        val file = File(root, fileName)
        val temporary = File(root, "$fileName.tmp")
        return try {
            temporary.writeText(body, StandardCharsets.UTF_8)
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (error: Exception) {
            temporary.delete()
            false
        }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String? = try {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        private const val TAG = "StickerLibrary"
        private const val DIR_NAME = "stickers/v1"
        private const val INDEX_FILE = "index.v1"
        private const val INDEX_HEADER = "APUSTICK1"
        private const val RECENTS_FILE = "recents.v1"
        private const val RECENTS_HEADER = "APUSTICKREC1"
        private const val PEER_CATALOG_FILE = "peer_catalog.v1"
        private const val PREFS_NAME = "apu_sticker_swarm"

        /** Сколько «Недавних» помним. */
        const val MAX_RECENTS = 24

        /** Служебный префикс конвертов каталога стикеров (раунд 139). */
        const val WIRE_PREFIX = "APUSTK1"

        /** Стикер больше этого в библиотеку не попадает (насос режет 25 МБ). */
        private const val MAX_STICKER_BYTES = 30 * 1024 * 1024

        /** Максимум записей в каталоге одного собеседника. */
        private const val MAX_PEER_ITEMS = 2000

        /** Максимум собеседников в общем каталоге «что у кого». */
        private const val MAX_PEERS = 100

        /** Потолок одной порции каталога: личный конверт несёт 4096 символов. */
        private const val MAX_BATCH_CHARS = 2200

        private fun dir(context: Context): File =
            File(context.applicationContext.noBackupFilesDir, DIR_NAME).apply { mkdirs() }

        private fun isSafeSha(sha: String): Boolean =
            sha.length == 64 && sha.all { it.isDigit() || it in 'a'..'f' }

        // ── Приехавшие стикеры ──────────────────────────────────────────────

        private val arrivals = MutableSharedFlow<String>(extraBufferCapacity = 16)

        /** sha стикера, который только что лёг в библиотеку (для автоотправки). */
        fun arrivalsFlow(): SharedFlow<String> = arrivals.asSharedFlow()

        private fun emitArrival(sha: String) {
            arrivals.tryEmit(sha)
        }

        // ── Ожидаемые стикеры (нажали в «Из сети» - ждём файл от роя) ───────

        private val pendingWants = ConcurrentHashMap<String, Long>()

        /** Раунд 139: когда по этому стикеру последний раз уходила просьба. */
        private val wantSentAt = ConcurrentHashMap<String, Long>()

        fun rememberWant(sha256: String) {
            pendingWants[sha256] = System.currentTimeMillis()
            while (pendingWants.size > 64) {
                val oldest = pendingWants.entries.minByOrNull { it.value } ?: break
                pendingWants.remove(oldest.key)
            }
        }

        fun isWanted(sha256: String): Boolean = pendingWants.containsKey(sha256)

        fun forgetWant(sha256: String) {
            pendingWants.remove(sha256)
        }

        // ── Провод: APUSTK1 ─────────────────────────────────────────────────

        fun isStickerPacket(text: String?): Boolean =
            text != null && text.length <= 8000 && text.startsWith("$WIRE_PREFIX|")

        fun parseStickerPacket(text: String?): StickerPacket? {
            // Локальная non-null копия: smart-cast через isStickerPacket не работает.
            val t = text ?: return null
            if (!isStickerPacket(t)) return null
            return when {
                t == "$WIRE_PREFIX|ask" -> StickerPacket("ask", 0, 1, emptyList(), "")
                t.startsWith("$WIRE_PREFIX|want|") -> {
                    val sha = t.removePrefix("$WIRE_PREFIX|want|")
                    if (isSafeSha(sha)) StickerPacket("want", 0, 1, emptyList(), sha) else null
                }
                t.startsWith("$WIRE_PREFIX|thumb|") -> {
                    val sha = t.removePrefix("$WIRE_PREFIX|thumb|")
                    if (isSafeSha(sha)) StickerPacket("thumb", 0, 1, emptyList(), sha) else null
                }
                t.startsWith("$WIRE_PREFIX|thmb|") -> {
                    val parts = t.split("|", limit = 4)
                    if (parts.size != 4) return null
                    val sha = parts[2]
                    if (!isSafeSha(sha) || parts[3].length !in 16..2600) return null
                    StickerPacket("thmb", 0, 1, emptyList(), sha, parts[3])
                }
                t.startsWith("$WIRE_PREFIX|have|") -> {
                    val parts = t.split("|", limit = 5)
                    if (parts.size != 5) return null
                    val index = parts[2].toIntOrNull() ?: return null
                    val total = parts[3].toIntOrNull() ?: return null
                    val items = try {
                        val arr = JSONArray(parts[4])
                        (0 until arr.length()).mapNotNull { i ->
                            val row = arr.optJSONArray(i) ?: return@mapNotNull null
                            val sha = row.optString(0)
                            if (!isSafeSha(sha)) return@mapNotNull null
                            SwarmSticker(
                                sha256 = sha,
                                name = row.optString(1),
                                sizeBytes = row.optLong(2),
                                atMs = row.optLong(3),
                                holders = emptyList(),
                            )
                        }
                    } catch (_: Exception) {
                        return null
                    }
                    StickerPacket("have", index, total, items, "")
                }
                else -> null
            }
        }

        /**
         * Попросить стикер у роя: просьба уходит ТРЁМ лучшим хранителям
         * (свежий онлайн выше в рейтинге) - байты принесёт самый доступный,
         * остальных получатель вежливо остановит (дубль уже едущей передачи
         * отклоняется). Возвращает имя первого хранителя для статуса,
         * "" - если недавно уже просили, null - если некому просить.
         */
        suspend fun requestSticker(
            context: Context,
            chatRepository: ChatRepository,
            sha256: String,
            holders: List<String>,
        ): String? = withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val now = System.currentTimeMillis()
            val last = wantSentAt[sha256]
            if (last != null && now - last < 2 * 60_000L) return@withContext ""
            wantSentAt[sha256] = now
            while (wantSentAt.size > 64) {
                val oldest = wantSentAt.entries.minByOrNull { it.value } ?: break
                wantSentAt.remove(oldest.key)
            }
            val ranked = holders.sortedByDescending { holder ->
                val stats = PeerRatingStore.statsFor(app, holder)
                val fresh = stats?.lastSeenMs?.takeIf { now - it < 600_000L } ?: 0L
                (fresh / 1000L) + (stats?.sightings ?: 0L).coerceAtMost(1000L)
            }
            var sentTo = 0
            val firstName = StringBuilder()
            for (holder in ranked) {
                if (sentTo >= 3) break
                val chat = chatRepository.getChatByContactId(holder) ?: continue
                val sent = RustBridge.sendMessage(
                    UUID.randomUUID().toString(), chat.id, holder,
                    "$WIRE_PREFIX|want|$sha256",
                )
                if (sent) {
                    if (sentTo == 0) firstName.append(chat.contactName.ifBlank { "телефоном" })
                    sentTo++
                }
            }
            when (sentTo) {
                0 -> null
                1 -> firstName.toString()
                else -> "$firstName (и ещё ${sentTo - 1})"
            }
        }

        // ── Миниатюры чужих стикеров (как у гифок, раунд 129) ───────────────

        private val thumbArrivals = MutableSharedFlow<String>(extraBufferCapacity = 32)

        /** sha стикера, чья миниатюра только что приехала (для сетки панели). */
        fun thumbArrivalsFlow(): SharedFlow<String> = thumbArrivals.asSharedFlow()

        private val thumbAskedAt = ConcurrentHashMap<String, Long>()

        /** Крошечная миниатюра (<=96px), если уже скачана с хранителя. */
        fun tinyThumbFile(context: Context, sha256: String): File? {
            if (!isSafeSha(sha256)) return null
            return File(dir(context), "t$sha256.jpg").takeIf { it.isFile }
        }

        /** Сохранить приехавшую миниатюру (base64 jpeg, маленькая). */
        suspend fun receiveThumb(context: Context, sha256: String, b64: String): Boolean =
            withContext(Dispatchers.IO) {
                if (!isSafeSha(sha256)) return@withContext false
                if (b64.length !in 16..2600) return@withContext false
                val bytes = runCatching {
                    android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                }.getOrNull() ?: return@withContext false
                if (bytes.isEmpty() || bytes.size > 2000) return@withContext false
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (opts.outWidth <= 0) return@withContext false
                val f = File(dir(context), "t$sha256.jpg")
                runCatching { f.writeBytes(bytes) }.getOrElse { return@withContext false }
                thumbArrivals.emit(sha256)
                true
            }

        /** Миниатюра для отдачи: из кэша или сжать из своего стикера. */
        suspend fun tinyThumbPayload(context: Context, sha256: String): String? =
            withContext(Dispatchers.IO) {
                if (!isSafeSha(sha256)) return@withContext null
                val cached = File(dir(context), "t$sha256.jpg")
                if (cached.isFile && cached.length() in 1..2000) {
                    return@withContext android.util.Base64.encodeToString(
                        cached.readBytes(), android.util.Base64.NO_WRAP,
                    )
                }
                val src = File(dir(context), "$sha256.img").takeIf { it.isFile }
                    ?: return@withContext null
                val b64 = encodeTinyThumb(src) ?: return@withContext null
                runCatching {
                    File(dir(context), "t$sha256.jpg").writeBytes(
                        android.util.Base64.decode(b64, android.util.Base64.NO_WRAP),
                    )
                }
                b64
            }

        /** Сжать до крошечного jpeg, влезающего в служебный кадр (~2 КБ). */
        private fun encodeTinyThumb(src: File): String? {
            val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return null
            for (size in intArrayOf(96, 80, 64)) {
                for (quality in intArrayOf(55, 45, 35)) {
                    val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, size, size, true)
                    val out = java.io.ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    val b64 = android.util.Base64.encodeToString(
                        out.toByteArray(), android.util.Base64.NO_WRAP,
                    )
                    if (b64.length <= 2100) return b64
                }
            }
            return null
        }

        /** Попросить миниатюру у лучшего доступного хранителя. */
        suspend fun requestThumb(
            context: Context,
            chatRepository: ChatRepository,
            sha256: String,
            holders: List<String>,
        ): Boolean = withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val now = System.currentTimeMillis()
            val last = thumbAskedAt[sha256]
            if (last != null && now - last < 5 * 60_000L) return@withContext false
            thumbAskedAt[sha256] = now
            while (thumbAskedAt.size > 256) {
                val oldest = thumbAskedAt.entries.minByOrNull { it.value } ?: break
                thumbAskedAt.remove(oldest.key)
            }
            val ranked = holders.sortedByDescending { holder ->
                val stats = PeerRatingStore.statsFor(app, holder)
                val fresh = stats?.lastSeenMs?.takeIf { now - it < 600_000L } ?: 0L
                (fresh / 1000L) + (stats?.sightings ?: 0L).coerceAtMost(1000L)
            }
            for (holder in ranked) {
                val chat = chatRepository.getChatByContactId(holder) ?: continue
                val sent = RustBridge.sendMessage(
                    UUID.randomUUID().toString(), chat.id, holder,
                    "$WIRE_PREFIX|thumb|$sha256",
                )
                if (sent) return@withContext true
            }
            false
        }

        // ── Каталог роя: «что у кого хранится» ──────────────────────────────

        /** Незавершённые сборки каталогов собеседников: peer -> (порции). */
        private val pendingBatches = ConcurrentHashMap<String, MutableMap<Int, List<SwarmSticker>>>()

        private val swarmMutex = Mutex()

        private fun peerFile(context: Context) = File(dir(context), PEER_CATALOG_FILE)

        /** Принять порцию каталога. Вернёт true, когда собраны все порции. */
        suspend fun receivePeerBatch(
            context: Context,
            peerId: String,
            index: Int,
            total: Int,
            items: List<SwarmSticker>,
        ): Boolean = withContext(Dispatchers.IO) {
            if (peerId.isBlank() || total !in 1..300 || index !in 0 until total) {
                return@withContext false
            }
            val slots = pendingBatches.getOrPut(peerId) { ConcurrentHashMap() }
            slots[index] = items
            if (slots.size < total) return@withContext false
            pendingBatches.remove(peerId)
            val merged = (0 until total)
                .flatMap { slots[it] ?: emptyList() }
                .distinctBy { it.sha256 }
                .take(MAX_PEER_ITEMS)
            swarmMutex.withLock {
                val root = loadPeerRoot(context)
                root.put(peerId, peerCatalogToJson(merged, System.currentTimeMillis()))
                // Держим каталог компактным: свежие собеседники.
                while (root.length() > MAX_PEERS) {
                    val oldest = peerIds(root)
                        .minByOrNull { root.optJSONObject(it)?.optLong("at") ?: 0L } ?: break
                    root.remove(oldest)
                }
                savePeerRoot(context, root)
            }
            true
        }

        /**
         * Общий каталог роя: слияние каталогов всех собеседников по sha256.
         * Свои стикеры не дублируются - они в «Моих».
         */
        suspend fun swarmCatalog(
            context: Context,
            myShas: Set<String>,
        ): List<SwarmSticker> = withContext(Dispatchers.IO) {
            val root = swarmMutex.withLock { loadPeerRoot(context) }
            val bySha = LinkedHashMap<String, MutableList<SwarmSticker>>()
            for (peer in peerIds(root)) {
                val obj = root.optJSONObject(peer) ?: continue
                val arr = obj.optJSONArray("items") ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sha = o.optString("sha")
                    if (!isSafeSha(sha) || sha in myShas) continue
                    bySha.getOrPut(sha) { mutableListOf() }.add(
                        SwarmSticker(
                            sha256 = sha,
                            name = o.optString("name"),
                            sizeBytes = o.optLong("size"),
                            atMs = o.optLong("at"),
                            holders = listOf(peer),
                        ),
                    )
                }
            }
            bySha.values.map { parts ->
                SwarmSticker(
                    sha256 = parts.first().sha256,
                    name = parts.first().name,
                    sizeBytes = parts.first().sizeBytes,
                    atMs = parts.maxOf { it.atMs },
                    holders = parts.map { it.holders.first() }.distinct(),
                )
            }.sortedByDescending { it.atMs }
        }

        private fun loadPeerRoot(context: Context): JSONObject = try {
            JSONObject(peerFile(context).readText())
        } catch (_: Exception) {
            JSONObject()
        }

        private fun savePeerRoot(context: Context, root: JSONObject) {
            check(dir(context).isDirectory) { "Cannot create sticker directory" }
            val dst = peerFile(context)
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            runCatching {
                tmp.writeText(root.toString())
                if (!tmp.renameTo(dst)) {
                    dst.delete()
                    tmp.renameTo(dst)
                }
            }
        }

        /** Список ключей каталога: keys() доступен везде, keySet() - нет. */
        private fun peerIds(root: JSONObject): List<String> {
            val out = ArrayList<String>()
            val it = root.keys()
            while (it.hasNext()) out.add(it.next())
            return out
        }

        private fun peerCatalogToJson(items: List<SwarmSticker>, at: Long): JSONObject {
            val arr = JSONArray()
            for (e in items) {
                arr.put(
                    JSONObject()
                        .put("sha", e.sha256)
                        .put("name", e.name)
                        .put("size", e.sizeBytes)
                        .put("at", e.atMs),
                )
            }
            return JSONObject().put("at", at).put("items", arr)
        }

        /** Тип картинки по подписи файла (для тихой передачи хранителем). */
        fun mimeFor(file: File): String {
            val head = ByteArray(16)
            val read = runCatching { java.io.FileInputStream(file).use { it.read(head) } }
                .getOrDefault(0)
            if (read >= 12) {
                when {
                    head[0] == 0x89.toByte() && head[1] == 0x50.toByte() -> return "image/png"
                    head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() -> return "image/gif"
                    head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> return "image/jpeg"
                    String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> return "image/webp"
                    head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                        head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> return "video/webm"
                }
            }
            return "image/png"
        }

        /** Полный файл стикера библиотеки по sha (раунд 172, для панели). */
        fun libraryFile(context: Context, sha256: String): File? {
            if (!isSafeSha(sha256)) return null
            return File(dir(context), "$sha256.img").takeIf { it.isFile }
        }

        /** Раунд 170: это видео-стикер webm (EBML-подпись)? */
        fun isWebmBytes(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
                bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()

        /** Видео-стикер webm по файлу библиотеки (файлы без расширения). */
        fun isWebmFile(file: java.io.File): Boolean = try {
            val head = ByteArray(4)
            java.io.FileInputStream(file).use { input ->
                val read = input.read(head)
                read == 4 && isWebmBytes(head)
            }
        } catch (error: Exception) {
            false
        }
    }
}
