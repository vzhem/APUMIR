package com.vladimir.messenger.data.schedule

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Отложенные сообщения. Хранятся на телефоне (SharedPreferences), а отправку
 * делает WorkManager через [com.vladimir.messenger.worker.ScheduledSendWorker],
 * поэтому сообщение уйдёт, даже если приложение закрыли.
 */
object ScheduledSendStore {

    data class Item(
        val id: String,
        val isGroup: Boolean,
        /** chatId для личного чата или groupId для группы. */
        val targetId: String,
        /** Тема группы; пусто для личного чата и группы без тем. */
        val topicId: String,
        val text: String,
        val sendAtMs: Long,
        val quiet: Boolean,
    )

    private const val PREFS = "scheduled_send"
    private const val KEY = "items"
    private val lock = Any()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    private var loaded = false

    /** Текущий список отложенных (для баннера в чате). */
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            _items.value = parse(prefs(context).getString(KEY, null))
        }
    }

    fun add(context: Context, item: Item) {
        synchronized(lock) {
            load(context)
            save(context, _items.value + item)
        }
    }

    fun remove(context: Context, id: String) {
        synchronized(lock) {
            load(context)
            save(context, _items.value.filterNot { it.id == id })
        }
    }

    fun get(context: Context, id: String): Item? {
        synchronized(lock) {
            load(context)
            return _items.value.firstOrNull { it.id == id }
        }
    }

    private fun save(context: Context, list: List<Item>) {
        _items.value = list
        prefs(context).edit().putString(KEY, toJson(list)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toJson(list: List<Item>): String {
        val array = JSONArray()
        list.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("isGroup", item.isGroup)
                    .put("targetId", item.targetId)
                    .put("topicId", item.topicId)
                    .put("text", item.text)
                    .put("sendAtMs", item.sendAtMs)
                    .put("quiet", item.quiet),
            )
        }
        return array.toString()
    }

    private fun parse(raw: String?): List<Item> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Item(
                    id = o.getString("id"),
                    isGroup = o.getBoolean("isGroup"),
                    targetId = o.getString("targetId"),
                    topicId = o.optString("topicId", ""),
                    text = o.getString("text"),
                    sendAtMs = o.getLong("sendAtMs"),
                    quiet = o.optBoolean("quiet", false),
                )
            }
        }.getOrDefault(emptyList())
    }
}
