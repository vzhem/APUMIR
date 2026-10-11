package com.vladimir.messenger.data.story

import android.content.Context
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
    )

    private const val PREFS = "stories"
    private const val KEY = "items"
    private const val SEEN_KEY = "seen"
    private val lock = Any()
    private var appContext: Context? = null
    private var loaded = false
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
                _items.value = parse(prefs()?.getString(KEY, null))
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
            Story(
                id = o.getString("id"),
                authorId = authorId,
                authorName = o.optString("name", authorName).ifBlank { authorName },
                text = o.getString("text"),
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
    fun publish(authorId: String, text: String, bg: Int): Story {
        val story = Story(
            id = java.util.UUID.randomUUID().toString(),
            authorId = authorId,
            authorName = "",
            text = text,
            bg = bg.coerceIn(0, BG_COUNT - 1),
            createdAtMs = System.currentTimeMillis(),
            mine = true,
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

    private fun add(story: Story) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val kept = _items.value
                .filter { now - it.createdAtMs < LIFETIME_MS && it.id != story.id }
            save(kept + story)
        }
    }

    private fun save(list: List<Story>) {
        _items.value = list
        _seen.value = _seen.value.intersect(list.map { it.id }.toSet())
        prefs()?.edit()?.putString(KEY, toJson(list))?.putStringSet(SEEN_KEY, _seen.value)?.apply()
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
                    .put("mine", s.mine),
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
                )
            }
        }.getOrDefault(emptyList())
    }
}
