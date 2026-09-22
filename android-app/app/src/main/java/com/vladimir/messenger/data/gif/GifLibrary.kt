package com.vladimir.messenger.data.gif

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.peer.PeerRatingStore
import com.vladimir.messenger.data.repository.ChatRepository
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Одна гифка в библиотеке телефона (или в каталоге другого телефона). */
data class GifLibEntry(
    val sha256: String,
    val giphyId: String,
    val tag: String,
    val displayName: String,
    val sizeBytes: Long,
    val addedAtMs: Long,
)

/**
 * Гифка из каталога РОЯ: запись + кто её хранит. Хранители отсортированы
 * по свежести онлайна (свежий - первым), имя телефона подставляет UI.
 */
data class SwarmGif(
    val entry: GifLibEntry,
    val holders: List<String>,
)

/**
 * Раунд 121: СВОЙ каталог гифок на телефонах роя.
 *
 * Внешний каталог (Giphy) лимитирован (100 запросов в час), внутренний -
 * безлимитный. Поэтому каждая скачанная гифка ПОСЕЛЯЕТСЯ на телефоне
 * (байты + маленькое превью + индекс), а телефоны обмениваются каталогами
 * «что у кого лежит» служебными конвертами APUGIF1 (едут как обычные
 * сообщения, E2E-зашифрованы, наружу не видны). Кнопка «Рой» в окне
 * гифок показывает общий каталог; выбор шлёт хранителю просьбу, и тот
 * отправляет гифку обычной защищённой передачей файлов. Внешний ресурс
 * нужен только если гифки нет ни у кого.
 *
 * Формат конвертов (разбирается ДО сохранения в чат, как реакции):
 *   APUGIF1|ask                 - «дай свой каталог»
 *   APUGIF1|have|<i>|<n>|<json> - порция каталога (i из n), json - массив
 *                                 [sha, giphyId, tag, name, size]
 *   APUGIF1|want|<sha>          - «пришли мне эту гифку файлом»
 */
object GifLibrary {

    private const val TAG = "GifLibrary"
    const val WIRE_PREFIX = "APUGIF1"

    // Раунд 123: лимита на число гифок в библиотеке БОЛЬШЕ НЕТ - сколько
    // человек использует, столько и хранится (решение владельца).

    /** Максимум записей в каталоге одного собеседника. */
    private const val MAX_PEER_ITEMS = 2000

    /** Максимум собеседников в общем каталоге «что у кого». */
    private const val MAX_PEERS = 100

    /** Гифка больше этого не попадает в библиотеку (насос и так режет 25 МБ). */
    private const val MAX_GIF_BYTES = 30 * 1024 * 1024

    /** Потолок одной порции каталога: личный конверт шифрования несёт 4096 символов. */
    private const val MAX_BATCH_CHARS = 2200

    private const val PREFS = "apu_gif_library"

    private val mutex = Mutex()

    /** sha только что добавленной гифки - VM автоматически досылает её в чат. */
    private val arrivals = MutableSharedFlow<String>(extraBufferCapacity = 16)

    fun arrivalsFlow(): SharedFlow<String> = arrivals.asSharedFlow()

    /** Незавершённые сборки каталогов собеседников: peer -> (всего, порции). */
    private val pendingBatches = ConcurrentHashMap<String, MutableMap<Int, List<GifLibEntry>>>()

    // ── Файлы ────────────────────────────────────────────────────────────

    private fun dir(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, "giflib").apply { mkdirs() }

    fun gifFile(context: Context, sha256: String): File? {
        if (!isSafeSha(sha256)) return null
        val f = File(dir(context), "$sha256.gif")
        return f.takeIf { it.isFile }
    }

    fun previewFile(context: Context, sha256: String): File? {
        if (!isSafeSha(sha256)) return null
        val f = File(dir(context), "$sha256.jpg")
        return f.takeIf { it.isFile }
    }

    private fun indexFile(context: Context) =
        File(context.applicationContext.filesDir, "gif_library_index.json")

    private fun peerFile(context: Context) =
        File(context.applicationContext.filesDir, "gif_peer_catalog.json")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isSafeSha(sha: String): Boolean =
        sha.length == 64 && sha.all { it.isDigit() || it in 'a'..'f' }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // ── Моя библиотека ──────────────────────────────────────────────────

    /**
     * Поселить гифку: байты + превью + запись в индекс. Повторное добавление
     * того же файла обновляет отметку времени (LRU) и метку поиска.
     */
    suspend fun add(
        context: Context,
        bytes: ByteArray,
        giphyId: String?,
        tag: String?,
        displayName: String?,
    ): GifLibEntry? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > MAX_GIF_BYTES) return@withContext null
        val sha = sha256Hex(bytes)
        val app = context.applicationContext
        try {
            val gif = File(dir(app), "$sha.gif")
            val tmp = File(dir(app), ".$sha.gif.tmp")
            tmp.outputStream().use { it.write(bytes) }
            if (!tmp.renameTo(gif)) {
                gif.delete()
                tmp.renameTo(gif)
            }
            writePreview(app, bytes, sha)
            val now = System.currentTimeMillis()
            val saved = mutex.withLock {
                val items = loadIndex(app).toMutableList()
                val existing = items.indexOfFirst { it.sha256 == sha }
                val entry = if (existing >= 0) {
                    val old = items.removeAt(existing)
                    GifLibEntry(
                        sha256 = sha,
                        giphyId = giphyId?.takeIf { it.isNotBlank() } ?: old.giphyId,
                        tag = tag?.takeIf { it.isNotBlank() } ?: old.tag,
                        displayName = displayName?.takeIf { it.isNotBlank() } ?: old.displayName,
                        sizeBytes = bytes.size.toLong(),
                        addedAtMs = now,
                    )
                } else {
                    GifLibEntry(
                        sha256 = sha,
                        giphyId = giphyId.orEmpty(),
                        tag = tag.orEmpty(),
                        displayName = displayName.orEmpty().ifBlank { "gif_${sha.take(8)}.gif" },
                        sizeBytes = bytes.size.toLong(),
                        addedAtMs = now,
                    )
                }
                items.add(0, entry)
                saveIndex(app, items)
                entry
            }
            arrivals.emit(sha)
            saved
        } catch (e: Exception) {
            Log.w(TAG, "gif add failed: ${e.message}")
            null
        }
    }

    /** Поселить гифку из готового файла (например, принятой по рою). */
    suspend fun addFromFile(
        context: Context,
        file: File,
        giphyId: String?,
        tag: String?,
    ): GifLibEntry? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() > MAX_GIF_BYTES) return@withContext null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
        add(context, bytes, giphyId, tag, file.name)
    }

    suspend fun entries(context: Context): List<GifLibEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { loadIndex(context) }
    }

    suspend fun bySha(context: Context, sha256: String): GifLibEntry? =
        entries(context).firstOrNull { it.sha256 == sha256 }

    /** Мой sha для гифки внешнего каталога - чтобы не качать её second раз. */
    suspend fun shaForGiphyId(context: Context, giphyId: String): String? =
        entries(context).firstOrNull { it.giphyId == giphyId }?.sha256

    suspend fun librarySize(context: Context): Int = entries(context).size

    private fun loadIndex(context: Context): List<GifLibEntry> = try {
        val text = indexFile(context).readText()
        val arr = JSONObject(text).optJSONArray("items") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            GifLibEntry(
                sha256 = o.optString("sha"),
                giphyId = o.optString("gid"),
                tag = o.optString("tag"),
                displayName = o.optString("name"),
                sizeBytes = o.optLong("size"),
                addedAtMs = o.optLong("at"),
            )
        }.filter { isSafeSha(it.sha256) }.sortedByDescending { it.addedAtMs }
    } catch (_: Exception) {
        emptyList()
    }

    private fun saveIndex(context: Context, items: List<GifLibEntry>) {
        val arr = JSONArray()
        for (e in items) {
            arr.put(
                JSONObject()
                    .put("sha", e.sha256)
                    .put("gid", e.giphyId)
                    .put("tag", e.tag)
                    .put("name", e.displayName)
                    .put("size", e.sizeBytes)
                    .put("at", e.addedAtMs),
            )
        }
        val out = JSONObject().put("v", 1).put("items", arr)
        val dst = indexFile(context)
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        tmp.writeText(out.toString())
        if (!tmp.renameTo(dst)) {
            dst.delete()
            tmp.renameTo(dst)
        }
    }

    private fun writePreview(context: Context, bytes: ByteArray, sha: String) {
        val preview = File(dir(context), "$sha.jpg")
        if (preview.isFile) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 240 || bounds.outHeight / (sample * 2) >= 240) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return
        val scaled = if (bitmap.width > 320 || bitmap.height > 320) {
            val scale = minOf(320f / bitmap.width, 320f / bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        try {
            preview.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    // ── Каталог роя: «что у кого хранится» ──────────────────────────────

    /** Принять порцию каталога. Вернёт true, когда собраны все порции. */
    suspend fun receivePeerBatch(
        context: Context,
        peerId: String,
        index: Int,
        total: Int,
        items: List<GifLibEntry>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (peerId.isBlank() || total !in 1..300 || index !in 0 until total) return@withContext false
        val slots = pendingBatches.getOrPut(peerId) { ConcurrentHashMap() }
        slots[index] = items
        if (slots.size < total) return@withContext false
        pendingBatches.remove(peerId)
        val merged = (0 until total)
            .flatMap { slots[it] ?: emptyList() }
            .distinctBy { it.sha256 }
            .take(MAX_PEER_ITEMS)
        mutex.withLock {
            val root = loadPeerRoot(context)
            root.put(peerId, peerCatalogToJson(merged, System.currentTimeMillis()))
            // Держим каталог компактным: свежие 50 собеседников.
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
     * Свои гифки не дублируются - они в разделе «Мои».
     */
    suspend fun swarmCatalog(context: Context): List<SwarmGif> = withContext(Dispatchers.IO) {
        val mine = mutex.withLock { loadIndex(context) }.map { it.sha256 }.toSet()
        val root = mutex.withLock { loadPeerRoot(context) }
        val bySha = LinkedHashMap<String, MutableList<Pair<String, GifLibEntry>>>()
        for (peer in peerIds(root)) {
            val obj = root.optJSONObject(peer) ?: continue
            val items = jsonToPeerItems(obj)
            for (entry in items) {
                if (entry.sha256 in mine) continue
                bySha.getOrPut(entry.sha256) { mutableListOf() }.add(peer to entry)
            }
        }
        bySha.values.map { holders ->
            // Хранители: свежий каталог - свежий телефон.
            val sorted = holders.sortedByDescending { (_, e) -> e.addedAtMs }
            SwarmGif(
                entry = sorted.first().second,
                holders = sorted.map { it.first }.distinct(),
            )
        }.sortedByDescending { it.entry.addedAtMs }
    }

    suspend fun peerCatalogCounts(context: Context): Map<String, Int> = withContext(Dispatchers.IO) {
        val root = mutex.withLock { loadPeerRoot(context) }
        peerIds(root).associateWith {
            root.optJSONObject(it)?.optJSONArray("items")?.length() ?: 0
        }
    }

    /** Список ключей каталога: keys() доступен везде, keySet() - нет. */
    private fun peerIds(root: JSONObject): List<String> {
        val out = ArrayList<String>()
        val it = root.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    private fun loadPeerRoot(context: Context): JSONObject = try {
        JSONObject(peerFile(context).readText())
    } catch (_: Exception) {
        JSONObject()
    }

    private fun savePeerRoot(context: Context, root: JSONObject) {
        val dst = peerFile(context)
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(dst)) {
            dst.delete()
            tmp.renameTo(dst)
        }
    }

    private fun peerCatalogToJson(items: List<GifLibEntry>, at: Long): JSONObject {
        val arr = JSONArray()
        for (e in items) arr.put(entryToJson(e))
        return JSONObject().put("at", at).put("items", arr)
    }

    private fun entryToJson(e: GifLibEntry): JSONObject =
        JSONObject()
            .put("sha", e.sha256)
            .put("gid", e.giphyId)
            .put("tag", e.tag)
            .put("name", e.displayName)
            .put("size", e.sizeBytes)
            .put("at", e.addedAtMs)

    private fun jsonToPeerItems(obj: JSONObject): List<GifLibEntry> {
        val arr = obj.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val sha = o.optString("sha")
            if (!isSafeSha(sha)) return@mapNotNull null
            GifLibEntry(
                sha256 = sha,
                giphyId = o.optString("gid"),
                tag = o.optString("tag"),
                displayName = o.optString("name"),
                sizeBytes = o.optLong("size"),
                addedAtMs = o.optLong("at"),
            )
        }
    }

    // ── Провод: APUGIF1 ─────────────────────────────────────────────────

    data class GifPacket(
        val kind: String, // "ask" | "have" | "want"
        val index: Int,
        val total: Int,
        val items: List<GifLibEntry>,
        val sha256: String,
    )

    fun isGifPacket(text: String?): Boolean =
        text != null && text.length <= 8000 && text.startsWith("$WIRE_PREFIX|")

    fun parseGifPacket(text: String?): GifPacket? {
        // Локальная non-null копия: smart-cast через вызов isGifPacket не работает.
        val t = text ?: return null
        if (!isGifPacket(t)) return null
        // Разбор с limit: тег/имя внутри JSON могут нести "|", хвост цельный.
        return when {
            t == "$WIRE_PREFIX|ask" -> GifPacket("ask", 0, 1, emptyList(), "")
            t.startsWith("$WIRE_PREFIX|want|") -> {
                val sha = t.removePrefix("$WIRE_PREFIX|want|")
                if (isSafeSha(sha)) GifPacket("want", 0, 1, emptyList(), sha) else null
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
                        GifLibEntry(
                            sha256 = sha,
                            giphyId = row.optString(1),
                            tag = row.optString(2),
                            displayName = row.optString(3),
                            sizeBytes = row.optLong(4),
                            addedAtMs = 0L,
                        )
                    }
                } catch (_: Exception) {
                    return null
                }
                GifPacket("have", index, total, items, "")
            }
            else -> null
        }
    }

    /** Мой каталог порциями под потолок конверта (по символам, не по числу). */
    suspend fun buildHaveBatches(context: Context): List<String> = withContext(Dispatchers.IO) {
        val items = mutex.withLock { loadIndex(context) }
        val batches = mutableListOf<JSONArray>()
        var current = JSONArray()
        var currentLen = 0
        for (e in items) {
            val row = JSONArray()
                .put(e.sha256)
                .put(e.giphyId)
                .put(e.tag.take(40))
                .put(e.displayName.take(60))
                .put(e.sizeBytes)
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
     * Разослать свой каталог своим и попросить ихние. Вызывается при старте
     * сервиса и при открытии окна гифок. Тротлимб в prefs: объявление - раз
     * в 10 минут на телефон (сразу - если библиотека изменилась), просьба -
     * раз в сутки.
     */
    suspend fun syncWithSwarm(
        context: Context,
        chatRepository: ChatRepository,
        force: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val fingerprint = mutex.withLock {
            val items = loadIndex(app)
            "${items.size}:${items.firstOrNull()?.sha256.orEmpty()}"
        }
        val changed = prefs(app).getString("fp", "") != fingerprint
        val contactIds = runCatching { chatRepository.getAllContactIds() }.getOrDefault(emptyList())
        for (peer in contactIds.filter { it.isNotBlank() }.distinct()) {
            val chat = chatRepository.getChatByContactId(peer) ?: continue
            val announceDue = force || changed ||
                now - prefs(app).getLong("ann_$peer", 0L) > 6 * 3600_000L
            if (announceDue && now - prefs(app).getLong("ann_$peer", 0L) > 10 * 60_000L) {
                val batches = buildHaveBatches(app)
                var sent = true
                for (b in batches) {
                    sent = sent && RustBridge.sendMessage(
                        UUID.randomUUID().toString(), chat.id, peer, b,
                    )
                }
                if (sent) {
                    prefs(app).edit()
                        .putLong("ann_$peer", now)
                        .putString("fp", fingerprint)
                        .apply()
                    Log.i(TAG, "catalog announced to ${peer.takeLast(8)}: ${batches.size} batch(es)")
                }
            }
            if (now - prefs(app).getLong("ask_$peer", 0L) > 24 * 3600_000L) {
                val sent = RustBridge.sendMessage(
                    UUID.randomUUID().toString(), chat.id, peer, "$WIRE_PREFIX|ask",
                )
                if (sent) prefs(app).edit().putLong("ask_$peer", now).apply()
            }
        }
    }

    /**
     * Попросить гифку у роя: хранитель выбирается по свежести онлайна
     * (рейтинг узлов). Возвращает имя телефона-хранителя или null.
     */
    /**
     * Раунд 122: попросить гифку у роя. Просьба уходит ТРЁМ лучшим
     * хранителям (свежий онлайн выше в рейтинге) - гифку принесёт самый
     * быстрый/доступный, остальных получатель вежливо остановит
     * (FileTransferReceiver отклоняет дубль, который уже едет).
     * Возвращает имя первого хранителя для статуса или null.
     */
    suspend fun requestGif(
        context: Context,
        chatRepository: ChatRepository,
        sha256: String,
        holders: List<String>,
    ): String? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        // Повторное нажатие на ту же гифку чаще 2 минут - без новой просьбы.
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
                UUID.randomUUID().toString(), chat.id, holder, "$WIRE_PREFIX|want|$sha256",
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

    // ── Ожидаемые гифки (нажали в каталоге - ждём файл от роя) ──────────

    private val pendingWants = ConcurrentHashMap<String, Long>()

    /** Раунд 122: когда по этой гифке последний раз уходила просьба. */
    private val wantSentAt = ConcurrentHashMap<String, Long>()

    fun rememberWant(sha256: String) {
        pendingWants[sha256] = System.currentTimeMillis()
        while (pendingWants.size > 32) {
            val oldest = pendingWants.entries.minByOrNull { it.value } ?: break
            pendingWants.remove(oldest.key)
        }
    }

    fun isWanted(sha256: String): Boolean = pendingWants.containsKey(sha256)

    fun forgetWant(sha256: String) {
        pendingWants.remove(sha256)
    }
}
