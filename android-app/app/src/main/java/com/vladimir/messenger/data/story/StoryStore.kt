package com.vladimir.messenger.data.story

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Истории: текст на фоне, живут 24 часа. Пакет идёт контактам отдельным путём
 * доставки (ChatRepository.sendStoryPacket) и не попадает в переписку ни у
 * отправителя, ни у получателя. Здесь же хранится список для ленты.
 */
object StoryStore {
    const val PREFIX = "APUSTORY1:"
    const val LIFETIME_MS = 24L * 60L * 60L * 1000L
    /** Число фонов в палитре (см. StoryBackgrounds). */
    const val BG_COUNT = 4

    data class Story(
        val id: String,
        val authorId: String,
        val authorName: String,
        val text: String,
        val bg: Int,
        val createdAtMs: Long,
        val mine: Boolean,
        val photoB64: String? = null,
    )

    private val photoParts = StoryPhotoParts()

    private const val PREFS = "stories"
    private const val KEY = "items"
    private const val SEEN_KEY = "seen"
    private val lock = Any()
    private var appContext: Context? = null
    private var loaded = false
    private val diskWriter = Executors.newSingleThreadExecutor()
    private fun offlineFile(): AtomicFile? = appContext?.let {
        AtomicFile(File(it.filesDir, "stories-offline.json"))
    }
    private val _items = MutableStateFlow<List<Story>>(emptyList())
    private val _seen = MutableStateFlow<Set<String>>(emptySet())

    /** Идентификаторы чужих историй, которые уже открывали (кольцо в ленте становится серым). */
    val seen: StateFlow<Set<String>> = _seen.asStateFlow()

    /** Все сохранённые истории (включая истёкшие, пока не очищены). */
    val items: StateFlow<List<Story>> = _items.asStateFlow()

    /** Подключить контекст приложения (из MessengerApplication) и загрузить список. */
    fun attach(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (!loaded) {
                loaded = true
                // Durable local snapshot contains the actual photo, not a remote URL
                // or a URI permission that can disappear after a reboot.
                val stored = runCatching { offlineFile()?.openRead()?.bufferedReader()?.use { it.readText() } }.getOrNull()
                _items.value = parse(stored ?: prefs()?.getString(KEY, null))
                    .filter { System.currentTimeMillis() - it.createdAtMs in 0 until LIFETIME_MS }
                if (stored == null && _items.value.isNotEmpty()) save(_items.value)
                _seen.value = prefs()?.getStringSet(SEEN_KEY, null)?.toSet().orEmpty().intersect(active().map { it.id }.toSet())
            }
        }
    }

    /** Актуальные истории: моложе 24 часов. */
    fun active(nowMs: Long = System.currentTimeMillis()): List<Story> =
        _items.value.filter { nowMs - it.createdAtMs < LIFETIME_MS }

    /** Отметить историю просмотренной. Хранятся только id из актуальных историй. */
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

    /** Сохранить историю, пришедшую от контакта. false - пакет не распознан. */
    fun receive(authorId: String, authorName: String, content: String): Boolean {
        if (!isStoryPacket(content)) return false
        val story = runCatching {
            val o = JSONObject(content!!.removePrefix(PREFIX))
            val ts = o.getLong("ts")
            val now = System.currentTimeMillis()
            if (ts < now - LIFETIME_MS || ts > now + 300_000) return true
            val photo = if (o.optString("kind") == "photo") {
                photoParts.receive(authorId, o.getString("id"), o.getInt("part"), o.getInt("total"),
                    o.getString("data"), now) ?: return true
            } else null
            Story(
                photoB64 = photo,
                id = o.getString("id"),
                authorId = authorId,
                authorName = o.optString("name", authorName).ifBlank { authorName },
                text = o.getString("text").take(200),
                bg = o.optInt("bg", 0).coerceIn(0, BG_COUNT - 1),
                createdAtMs = o.getLong("ts"),
                mine = false,
            )
        }.getOrNull() ?: return true
        // Пакет распознан, даже если битый: в переписку он в любом случае не идёт.
        add(story)
        return true
    }

    /** Создать свою историю (текст, фон) и вернуть её для рассылки. */
    fun publish(authorId: String, text: String, bg: Int, photoB64: String? = null): Story {
        require(photoB64 == null || photoB64.length <= StoryPhotoParts.MAX_SIZE)
        val story = Story(
            id = java.util.UUID.randomUUID().toString(),
            authorId = authorId,
            authorName = "",
            text = text,
            bg = bg.coerceIn(0, BG_COUNT - 1),
            createdAtMs = System.currentTimeMillis(),
            mine = true,
            photoB64 = photoB64,
        )
        add(story)
        return story
    }

    /** Пакет для отправки контактам (без своего идентификатора: его знает получатель). */
    fun encode(story: Story): String = PREFIX + JSONObject()
        .put("id", story.id)
        .put("name", story.authorName)
        .put("text", story.text)
        .put("bg", story.bg)
        .put("ts", story.createdAtMs)
        .toString()

    /** Small packets fit relay transport; all retain the hidden story prefix. */
    fun encodePackets(story: Story): List<String> {
        val photo = story.photoB64 ?: return listOf(encode(story))
        val chunks = photo.chunked(StoryPhotoParts.CHUNK)
        return chunks.mapIndexed { index, chunk ->
            val json = JSONObject(encode(story).removePrefix(PREFIX))
                .put("kind", "photo").put("part", index).put("total", chunks.size).put("data", chunk)
            PREFIX + json.toString()
        }
    }

    private fun add(story: Story) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val kept = _items.value
                .filter { now - it.createdAtMs < LIFETIME_MS && it.id != story.id }
            save((kept + story).takeLast(100).let { list ->
                val photos = list.filter { it.photoB64 != null }.takeLast(30).map { it.id }.toSet()
                list.filter { it.photoB64 == null || it.id in photos }
            })
        }
    }

    private fun save(list: List<Story>) {
        _items.value = list
        _seen.value = _seen.value.intersect(list.map { it.id }.toSet())
        prefs()?.edit()?.putStringSet(SEEN_KEY, _seen.value)?.apply()
        // Serial writer preserves snapshot order. AtomicFile keeps the previous
        // complete snapshot if Android stops the process during a write.
        val file = offlineFile() ?: return
        val snapshot = list.toList()
        diskWriter.execute {
            var stream: java.io.FileOutputStream? = null
            try {
                val bytes = toJson(snapshot).toByteArray(Charsets.UTF_8)
                stream = file.startWrite()
                stream.write(bytes)
                file.finishWrite(stream)
                prefs()?.edit()?.remove(KEY)?.apply()
            } catch (_: Exception) {
                stream?.let { file.failWrite(it) }
            }
        }
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toJson(list: List<Story>): String {
        val array = JSONArray()
        list.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("authorId", s.authorId)
                    .put("authorName", s.authorName)
                    .put("text", s.text)
                    .put("bg", s.bg)
                    .put("ts", s.createdAtMs)
                    .put("mine", s.mine)
                    .put("photo", s.photoB64),
            )
        }
        return array.toString()
    }

    private fun parse(raw: String?): List<Story> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Story(
                    id = o.getString("id"),
                    authorId = o.getString("authorId"),
                    authorName = o.optString("authorName", ""),
                    text = o.getString("text"),
                    bg = o.optInt("bg", 0),
                    createdAtMs = o.getLong("ts"),
                    mine = o.optBoolean("mine", false),
                    photoB64 = o.optString("photo").takeIf { it.isNotBlank() && it != "null" && it.length <= StoryPhotoParts.MAX_SIZE },
                )
            }
        }.getOrDefault(emptyList())
    }
}
