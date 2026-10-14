package com.vladimir.messenger.data.schedule

import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics
import com.vladimir.messenger.data.diagnostics.OperationalCounter
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
        /** Old persisted schedules remain sender-timed; new ones pre-deliver. */
        val preDeliver: Boolean = true,
        val started: Boolean = false,
        val recipients: List<String>? = null,
        val confirmed: List<String> = emptyList(),
        val ready: List<String> = emptyList(),
        val handedOff: List<String> = emptyList(),
        val wire: String = "",
        val ownerId: String = "",
        val lastError: String = "",
        val nextPeer: Int = 0,
        val ackedPackets: List<String> = emptyList(),
        val cancelRequested: Boolean = false,
        val cancelAcked: List<String> = emptyList(),

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
            save(context, _items.value.filterNot { it.id == item.id } + item)
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

    /** Atomic claim/update: cancellation cannot race the first network send. */
    fun update(context: Context, id: String, transform: (Item) -> Item): Item? = synchronized(lock) {
        load(context)
        val old = _items.value.firstOrNull { it.id == id } ?: return@synchronized null
        val next = transform(old)
        save(context, _items.value.map { if (it.id == id) next else it })
        next
    }

    /** Completion and a user's cancellation request must not race and lose the request. */
    fun completeDelivery(context: Context, id: String): Boolean = synchronized(lock) {
        load(context)
        val item = _items.value.firstOrNull { it.id == id } ?: return@synchronized true
        if (item.cancelRequested) return@synchronized false
        save(context, _items.value.filterNot { it.id == id })
        true
    }

    /** Persist intent BEFORE stopping timers or sending requests; an in-flight packet may still arrive. */
    fun requestCancel(context: Context, id: String): Item? = synchronized(lock) {
        load(context)
        val item = _items.value.firstOrNull { it.id == id } ?: return@synchronized null
        if (!item.started || item.wire.isEmpty() || item.recipients.orEmpty().isEmpty()) {
            save(context, _items.value.filterNot { it.id == id })
            OperationalDiagnostics.tracker.count(OperationalCounter.SCHEDULE_CANCEL_LOCAL_BEFORE_SEND)
            return@synchronized null
        }
        val next = item.copy(cancelRequested = true, nextPeer = 0, lastError = "")
        save(context, _items.value.map { if (it.id == id) next else it })
        if (!item.cancelRequested) OperationalDiagnostics.tracker.count(OperationalCounter.SCHEDULE_CANCEL_REQUESTED)
        next
    }

    /** Cache-only numeric view: do not parse message bodies just to export diagnostics. */
    fun cancelDiagnostics(): String = synchronized(lock) {
        val requests = _items.value.filter { it.cancelRequested }
        val waiting = requests.sumOf { item -> item.recipients.orEmpty().count { it !in item.cancelAcked } }
        "[отмена отложенных сообщений]\nsource=current_store_cache · cancel_items=${requests.size} · waiting_recipient_receipts=$waiting\n" +
            "Кэш может ещё не быть загружен; транспортный handoff не подтверждает отмену у получателя.\n"
    }

    private fun save(context: Context, list: List<Item>) {
        check(prefs(context).edit().putString(KEY, toJson(list)).commit()) { "Cannot persist scheduled messages" }
        _items.value = list
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
                    .put("quiet", item.quiet)
                    .put("preDeliver", item.preDeliver)
                    .put("started", item.started)
                    .put("recipients", item.recipients?.let { JSONArray(it) })
                    .put("confirmed", JSONArray(item.confirmed))
                    .put("ready", JSONArray(item.ready))
                    .put("handedOff", JSONArray(item.handedOff))
                    .put("wire", item.wire)
                    .put("ownerId", item.ownerId)
                    .put("lastError", item.lastError)
                    .put("nextPeer", item.nextPeer)
                    .put("ackedPackets", JSONArray(item.ackedPackets))
                    .put("cancelRequested", item.cancelRequested)
                    .put("cancelAcked", JSONArray(item.cancelAcked)),
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
                    preDeliver = o.optBoolean("preDeliver", false),
                    started = o.optBoolean("started", false),
                    recipients = o.optJSONArray("recipients")?.let { a -> (0 until a.length()).map { a.getString(it) } },
                    ready = o.optJSONArray("ready")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    confirmed = o.optJSONArray("confirmed")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    handedOff = o.optJSONArray("handedOff")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    wire = o.optString("wire", ""),
                    ownerId = o.optString("ownerId", ""),
                    lastError = o.optString("lastError", ""),
                    nextPeer = o.optInt("nextPeer", 0),
                    cancelRequested = o.optBoolean("cancelRequested", false),
                    cancelAcked = o.optJSONArray("cancelAcked")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    ackedPackets = o.optJSONArray("ackedPackets")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }
}
