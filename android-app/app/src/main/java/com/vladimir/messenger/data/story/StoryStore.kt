package com.vladimir.messenger.data.story

import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics
import com.vladimir.messenger.data.diagnostics.OperationalCounter
import android.content.Context
import android.util.AtomicFile
import com.vladimir.messenger.data.swarm.ContentAddressedChunks
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object StoryStore {
    const val PREFIX = "APUSTORY1:"
    const val LIFETIME_MS = 24L * 60 * 60 * 1000
    const val BG_COUNT = 4

    /**
     * Насколько время создания истории может опережать наши часы.
     *
     * Телефоны сверяют время не мгновенно, поэтому небольшое «будущее» — норма.
     * Раньше такие пакеты считались просрочкой и просто пропадали: ни в отчёте,
     * ни в журнале нельзя было понять, что история не дошла из-за расхождения
     * часов. Теперь у этой причины своё имя ([OperationalCounter.STORY_CLOCK_SKEW])
     * и одна понятная строка в журнале.
     */
    private const val FUTURE_TOLERANCE_MS = 300_000L

    data class Story(val id: String, val authorId: String, val authorName: String,
        val text: String, val bg: Int, val createdAtMs: Long, val mine: Boolean,
        val photoB64: String? = null, val photoCircle: Boolean = false, val video: StoryVideoMedia? = null)

    private val photoParts = StoryPhotoParts()
    private const val PREFS = "stories"
    private const val KEY = "items"
    private const val SEEN_KEY = "seen"
    private const val TOMB_KEY = "revoked"
    private val lock = Any()
    private val tombstones = HashMap<String, Long>()

    /** Авторы, о расхождении часов с которыми журнал уже предупредил (в памяти). */
    private val clockSkewNoted = LinkedHashSet<String>()
    private const val MAX_CLOCK_SKEW_NOTES = 8
    private var appContext: Context? = null
    private var loaded = false
    private val diskWriter = Executors.newSingleThreadExecutor()
    private fun offlineFile(): AtomicFile? = appContext?.let {
        AtomicFile(File(it.filesDir, "stories-offline.json"))
    }
    private val _items = MutableStateFlow<List<Story>>(emptyList())
    private val _seen = MutableStateFlow<Set<String>>(emptySet())
    val seen: StateFlow<Set<String>> = _seen.asStateFlow()
    val items: StateFlow<List<Story>> = _items.asStateFlow()

    fun attach(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (!loaded) {
                loaded = true
                val stored = runCatching {
                    offlineFile()?.openRead()?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                _items.value = parse(stored ?: prefs()?.getString(KEY, null))
                    .filter { System.currentTimeMillis() - it.createdAtMs in 0 until LIFETIME_MS }
                if (stored == null && _items.value.isNotEmpty()) save(_items.value)
                _seen.value = prefs()?.getStringSet(SEEN_KEY, null)?.toSet()
                    .orEmpty().intersect(active().map { it.id }.toSet())
                loadTombstones(System.currentTimeMillis())
            }
        }
    }

    fun active(nowMs: Long = System.currentTimeMillis()): List<Story> =
        _items.value.filter { nowMs - it.createdAtMs in 0 until LIFETIME_MS }

    fun markSeen(id: String) {
        synchronized(lock) {
            if (id in _seen.value) return
            val alive = active().map { it.id }.toSet()
            if (id !in alive) return
            val next = (_seen.value + id).intersect(alive)
            _seen.value = next
            prefs()?.edit()?.putStringSet(SEEN_KEY, next)?.apply()
        }
    }

    fun isStoryPacket(content: String?): Boolean = content?.startsWith(PREFIX) == true

    fun receive(authorId: String, authorName: String, content: String): Boolean {
        if (!isStoryPacket(content)) return false
        if (authorId.length !in 1..256) return true
        // Отклик на историю (значок, комментарий, сводка, просьба сводки) едет
        // тем же префиксом, но своим конвертом: разбираем его ДО json истории и
        // до сборки фото, иначе чужой значок сбросил бы части чьего-то снимка.
        if (StoryFeedbackStore.isFeedback(content)) {
            return StoryFeedbackStore.receive(authorId, content)
        }
        if (content!!.toByteArray(Charsets.UTF_8).size > 16 * 1024) {
            OperationalDiagnostics.tracker.count(OperationalCounter.STORY_INVALID)
            photoParts.discardAuthor(authorId); return true
        }
        val story = runCatching {
            val o = JSONObject(content.removePrefix(PREFIX))
            val ts = o.getLong("ts")
            val now = System.currentTimeMillis()
            photoParts.sweep(now)
            val id = o.getString("id")
            if (id.length !in 1..100) return true
            val kind = o.optString("kind")
            if (ts < now - LIFETIME_MS) {
                OperationalDiagnostics.tracker.count(OperationalCounter.STORY_EXPIRED)
                photoParts.discard(authorId, id); return true
            }
            if (ts > now + FUTURE_TOLERANCE_MS) {
                OperationalDiagnostics.tracker.count(OperationalCounter.STORY_CLOCK_SKEW)
                noteClockSkew(authorId)
                photoParts.discard(authorId, id); return true
            }
            if (kind == StoryRevoke.KIND) {
                OperationalDiagnostics.tracker.count(OperationalCounter.STORY_REVOKE_APPLIED)
                applyRemoteRevoke(authorId, id, ts, now)
                return true
            }
            if (kind == "photo" && o.has("content") && o.getLong("expiresAt") <= now) {
                OperationalDiagnostics.tracker.count(OperationalCounter.STORY_EXPIRED)
                photoParts.discard(authorId, id); return true
            }
            val photo = when (kind) {
                "photo" -> if (o.has("content")) {
                    photoParts.receiveContentAddressed(
                        author = authorId, storyId = id,
                        index = o.getInt("part"), total = o.getInt("total"),
                        contentId = o.getString("content"),
                        contentSizeBytes = o.getInt("size"),
                        chunkSizeBytes = o.getInt("chunkSize"),
                        expiresAtMs = o.getLong("expiresAt"),
                        chunkId = o.getString("chunk"),
                        data = o.getString("data"), now = now,
                    ) ?: run { OperationalDiagnostics.tracker.count(OperationalCounter.STORY_PHOTO_PENDING); return true }
                } else {
                    photoParts.receiveLegacy(
                        authorId, id, o.getInt("part"), o.getInt("total"), o.getString("data"), now,
                    ) ?: run { OperationalDiagnostics.tracker.count(OperationalCounter.STORY_PHOTO_PENDING); return true }
                }
                // Пакет без kind — текстовая история, не «отмена фото». Сбрасывать
                // сборку здесь нельзя: иначе подпись затирает уже принятый снимок,
                // и у друга остаётся одна подпись при живом фото у автора.
                else -> null
            }
            Story(id, authorId, o.optString("name", authorName).ifBlank { authorName },
                o.optString("videoCaption", o.getString("text")).take(200), o.optInt("bg", 0).coerceIn(0, BG_COUNT - 1), ts, false, photo, o.optBoolean("photoCircle", false),
                o.optJSONObject("video")?.let { StoryVideoWire.decode(it) })
        }.getOrNull() ?: run { OperationalDiagnostics.tracker.count(OperationalCounter.STORY_PHOTO_PENDING); return true }
        // Счётчик приёма раньше никто не увеличивал: в отчёте «Логи» нельзя
        // было отличить «история доехала и показана» от «история не дошла
        // вовсе» — а это первое, что нужно знать по жалобе «историй не видно».
        if (add(story)) OperationalDiagnostics.tracker.count(OperationalCounter.STORY_RECEIVED)
        return true
    }

    /**
     * О расхождении часов говорим в журнал один раз на автора: пакетов у
     * фотоистории десятки, и повторять одну и ту же строку незачем. Идентификатор
     * автора остаётся только в памяти и в журнал не выводится.
     */
    private fun noteClockSkew(author: String) {
        val first = synchronized(lock) {
            if (author in clockSkewNoted) {
                false
            } else {
                if (clockSkewNoted.size >= MAX_CLOCK_SKEW_NOTES) {
                    clockSkewNoted.remove(clockSkewNoted.first())
                }
                clockSkewNoted.add(author)
                true
            }
        }
        if (first) {
            com.vladimir.messenger.data.diagnostics.TransferDiagnostics.recordWarning(
                "story",
                "пакет истории отклонён: время создания в будущем — часы отправителя расходятся с нашими",
            )
        }
    }

    fun publish(authorId: String, text: String, bg: Int, photoB64: String? = null, photoCircle: Boolean = false, video: StoryVideoMedia? = null, id: String = java.util.UUID.randomUUID().toString()): Story {
        require(photoB64 == null || isValidPhotoB64(photoB64))
        val story = Story(
            id, authorId, "",
            text.take(200), bg.coerceIn(0, BG_COUNT - 1), System.currentTimeMillis(), true, photoB64, photoB64 != null && photoCircle && video == null, video)
        add(story); return story
    }

    fun encode(story: Story): String = PREFIX + JSONObject()
        .put("id", story.id).put("name", story.authorName)
        .put("text", if (story.video != null) "Видеоистория — обновите APU для просмотра.\n${story.text}" else story.text)
        .put("videoCaption", if (story.video != null) story.text else null)
        .put("video", story.video?.let { StoryVideoWire.encode(it) }).put("bg", story.bg).put("ts", story.createdAtMs)
        .put("photoCircle", story.photoCircle).toString()

    fun encodeRevoke(story: Story): String = PREFIX + JSONObject()
        .put("id", story.id).put("kind", StoryRevoke.KIND).put("ts", story.createdAtMs).toString()

    fun encodePackets(story: Story): List<String> {
        val photo = story.photoB64 ?: return listOf(encode(story))
        val bytes = ContentAddressedChunks.decodeCanonicalBase64(photo)
            ?: throw IllegalArgumentException("not canonical base64")
        require(ContentAddressedChunks.encodeBase64(bytes) == photo) { "not canonical" }
        require(bytes.size <= StoryPhotoParts.MAX_PHOTO_BYTES)
        val expiresAt = safeAdd(story.createdAtMs, LIFETIME_MS)
        val photoText = photo.toByteArray(Charsets.US_ASCII)
        val manifest = ContentAddressedChunks.manifest(photoText, StoryPhotoParts.CHUNK_BYTES, expiresAt)
        val chunks = ContentAddressedChunks.split(photoText, manifest)
        val common = JSONObject(encode(story).removePrefix(PREFIX))
            .put("kind", "photo")
            .put("content", manifest.contentId)
            .put("size", manifest.sizeBytes)
            .put("chunkSize", manifest.chunkSizeBytes)
            .put("expiresAt", manifest.expiresAtMs)
            .put("photoSha", ContentAddressedChunks.sha256Hex(bytes))
            .put("photoSize", bytes.size)
            .put("total", manifest.totalChunks)
        return chunks.map { chunk ->
            val wire = PREFIX + JSONObject(common.toString())
                .put("part", chunk.index)
                .put("chunk", chunk.id)
                .put("data", String(chunk.bytes, Charsets.US_ASCII))
                .toString()
            require(wire.toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "packet too large" }
            wire
        }
    }

    fun revokeLocal(storyId: String): Boolean = revokeMine(storyId) != null

    /**
     * Снять свою историю с ленты и вернуть снимок для рассылки `kind=revoke`.
     * Без пакета друзья продолжали бы видеть историю до конца суток.
     */
    fun revokeMine(storyId: String): Story? = synchronized(lock) {
        if (storyId.length !in 1..100) return@synchronized null
        val story = _items.value.firstOrNull { it.mine && it.id == storyId } ?: return@synchronized null
        applyRemoteRevoke(story.authorId, story.id, story.createdAtMs, System.currentTimeMillis())
        story
    }

    private fun applyRemoteRevoke(authorId: String, storyId: String, createdAtMs: Long, nowMs: Long) {
        synchronized(lock) {
            photoParts.discard(authorId, storyId)
            appContext?.let { StoryVideoStorage.remove(it, authorId, storyId) }
            val until = StoryRevoke.tombstoneUntilMs(createdAtMs)
            val key = StoryRevoke.key(authorId, storyId)
            tombstones[key] = maxOf(tombstones[key] ?: 0L, until)
            persistTombstones(nowMs)
            save(StoryRevoke.remaining(_items.value, authorId, storyId))
        }
        // Значки и комментарии снятой истории уходят вместе с ней: у призрака
        // откликов не бывает, а книга откликов не должна жить дольше ленты.
        StoryFeedbackStore.forget(storyId)
    }

    private fun loadTombstones(nowMs: Long) {
        tombstones.clear()
        prefs()?.getStringSet(TOMB_KEY, null).orEmpty().forEach { raw ->
            val parts = raw.split('|')
            if (parts.size == 3) {
                val until = parts[2].toLongOrNull() ?: return@forEach
                tombstones[StoryRevoke.key(parts[0], parts[1])] = until
            }
        }
        persistTombstones(nowMs)
    }

    private fun persistTombstones(nowMs: Long) {
        val live = tombstones.filterValues { StoryRevoke.isActive(it, nowMs) }
        tombstones.clear()
        tombstones.putAll(live)
        val stored = live.map { (key, until) ->
            val nl = key.indexOf('\n')
            if (nl <= 0) return@map null
            "${key.substring(0, nl)}|${key.substring(nl + 1)}|$until"
        }.filterNotNull().toSet()
        prefs()?.edit()?.putStringSet(TOMB_KEY, stored)?.apply()
    }

    private fun isValidPhotoB64(value: String): Boolean {
        if (value.length > StoryPhotoParts.MAX_SIZE) return false
        val b = ContentAddressedChunks.decodeCanonicalBase64(value) ?: return false
        return b.isNotEmpty() && b.size <= StoryPhotoParts.MAX_PHOTO_BYTES
    }

    private fun safeAdd(l: Long, r: Long): Long =
        if (l < 0) 0L else if (l > Long.MAX_VALUE - r) Long.MAX_VALUE else l + r

    /**
     * Две копии одной истории: фото, которое уже есть, не затираем подписью.
     * Иначе пакет без `kind` подменяет снимок пустым `photoB64`, и у друга
     * остаётся одна подпись, хотя автор по-прежнему видит свою копию с фото.
     */
    internal fun mergeIncoming(existing: Story?, incoming: Story): Story {
        if (existing == null || existing.id != incoming.id) return incoming
        return incoming.copy(
            authorId = existing.authorId,
            authorName = incoming.authorName.ifBlank { existing.authorName },
            photoB64 = incoming.photoB64 ?: existing.photoB64,
            video = existing.video ?: incoming.video,
            photoCircle = if (incoming.photoB64 != null) incoming.photoCircle else existing.photoCircle,
            mine = existing.mine || incoming.mine,
            createdAtMs = existing.createdAtMs,
        )
    }

    private fun add(story: Story): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        persistTombstones(now)
        val tomb = tombstones[StoryRevoke.key(story.authorId, story.id)]
        if (tomb != null && StoryRevoke.isActive(tomb, now)) return@synchronized false
        val previous = _items.value.firstOrNull { it.id == story.id }
        if (previous != null && previous.authorId != story.authorId) return@synchronized false
        if (previous?.video != null && story.video != null && previous.video != story.video) return@synchronized false
        val merged = mergeIncoming(previous, story)
        val kept = _items.value.filter { now - it.createdAtMs in 0 until LIFETIME_MS && it.id != merged.id }
        save((kept + merged).takeLast(100).let { list ->
            val ids = list.filter { it.photoB64 != null }.takeLast(30).map { it.id }.toSet()
            list.filter { it.photoB64 == null || it.id in ids }
        })
        previous == null || (previous.photoB64 == null && merged.photoB64 != null)
    }

    private fun save(list: List<Story>) {
        _items.value = list
        _seen.value = _seen.value.intersect(list.map { it.id }.toSet())
        prefs()?.edit()?.putStringSet(SEEN_KEY, _seen.value)?.apply()
        // Serial writer preserves snapshot order. AtomicFile keeps the previous
        // complete snapshot if Android stops the process during a write.
        val file = offlineFile() ?: return
        val snapshot = list.toList()
        diskWriter.execute { writeSnapshot(file, snapshot) }
    }

    /** Enqueue only after an AtomicFile snapshot is committed, off the main thread. */
    fun persistForDelivery(): Boolean {
        return try {
            val future = synchronized(lock) {
                val file = offlineFile() ?: return false
                val snapshot = _items.value.toList()
                diskWriter.submit<Boolean> { writeSnapshot(file, snapshot) }
            }
            future.get(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) { false }
    }

    private fun writeSnapshot(file: AtomicFile, snapshot: List<Story>): Boolean {
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = file.startWrite()
            stream.write(toJson(snapshot).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            prefs()?.edit()?.remove(KEY)?.apply()
            true
        } catch (_: Exception) {
            stream?.let { file.failWrite(it) }
            false
        }
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toJson(list: List<Story>): String {
        val arr = JSONArray()
        list.forEach { s -> arr.put(JSONObject()
            .put("id", s.id).put("authorId", s.authorId)
            .put("authorName", s.authorName).put("text", s.text)
            .put("bg", s.bg).put("ts", s.createdAtMs).put("mine", s.mine)
            .put("photo", s.photoB64).put("photoCircle", s.photoCircle)
            .put("video", s.video?.let { StoryVideoWire.encode(it) })) }
        return arr.toString()
    }

    private fun parse(raw: String?): List<Story> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Story(
                    id = o.getString("id"),
                    authorId = o.getString("authorId"),
                    authorName = o.optString("authorName", ""),
                    text = o.getString("text"),
                    bg = o.optInt("bg", 0),
                    createdAtMs = o.getLong("ts"),
                    mine = o.optBoolean("mine", false),
                    photoCircle = o.optBoolean("photoCircle", false),
                    video = o.optJSONObject("video")?.let { StoryVideoWire.decode(it) },
                    photoB64 = o.optString("photo").takeIf { it.isNotBlank() && it != "null" && isValidPhotoB64(it) },
                )
            }
        }.getOrDefault(emptyList())
    }
}
