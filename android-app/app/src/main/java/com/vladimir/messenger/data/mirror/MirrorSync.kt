package com.vladimir.messenger.data.mirror

// =============================================================================
// MIRRORSYNC.KT — живое зеркало устройств одной личности (раунд 226)
// =============================================================================
// Владелец: «пишешь на телефоне - сразу везде; входящее приходит сразу и на
// телефон, и на ПК; мгновенно, если оба в сети; кто долго был офлайн - сам
// догоняет при появлении сети».
//
// Как это работает без порчи криптографии:
//  - Два устройства одной личности (после «Синхронизировать аккаунт») не могут
//    сидеть в сети одновременно: у ядра один MQTT client_id (p2pm_<node>),
//    брокер выкидывает второго, а два отправителя с одной сессией ломают
//    рывок-шифрование. Поэтому сеть ВЕДЁТ одно устройство (АКТИВНОЕ), второе
//    (ЗЕРКАЛО) живёт на зеркальных событиях через лёгкий WebSocket к воркеру.
//  - Кто активен, решается сами устройствами: у кого ядро стартовало раньше,
//    тот и активен; при равенстве - меньший тег устройства. Как только активное
//    пропало (сервис убит, сеть умерла) дольше ~30 с - зеркало само повышает
//    себя, запускает ядро и продолжает работу. Появившийся позже - зеркалом.
//  - Всё содержимое кадров запечатано [MessageSealer] НА САМОГО СЕБЯ: вскрыть
//    может только устройство той же личности. Воркер видит только маршруты.
//  - Догон: устройства обмениваются «свежестью» (максимальный ts сообщения),
//    отставшее само запрашивает хвост - так пропуски закрываются без кнопок.
//
// В v1 зеркалятся ТЕКСТЫ личных чатов (входящие и исходящие) и их догон.
// Файлы, звонки, каналы/сообщества - остаются на принявшем их устройстве и
// переносятся полной копией («Синхронизировать аккаунт»).
// =============================================================================

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.group.GroupInviteLinks
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import com.vladimir.messenger.data.security.MessageSealer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/** Строка переписки, гуляющая между устройствами одной личности. */
data class MirrorRow(
    val id: String,
    val chatId: String,
    val contactName: String,
    val senderId: String,
    val content: String,
    val timestamp: Long,
    val mine: Boolean,
    val recipientId: String,
    val status: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("chat", chatId)
        .put("name", contactName)
        .put("sender", senderId)
        .put("text", content)
        .put("ts", timestamp)
        .put("mine", if (mine) 1 else 0)
        .put("to", recipientId)
        .put("st", status)

    companion object {
        fun fromJson(o: JSONObject): MirrorRow = MirrorRow(
            id = o.optString("id"),
            chatId = o.optString("chat"),
            contactName = o.optString("name"),
            senderId = o.optString("sender"),
            content = o.optString("text"),
            timestamp = o.optLong("ts"),
            mine = o.optInt("mine") == 1,
            recipientId = o.optString("to"),
            status = o.optString("st", "SENT"),
        )
    }
}

/** Доступ слоя репозиториев к каналу зеркала (без циклических зависимостей). */
object MirrorHub {
    @Volatile
    var channel: MirrorChannel? = null

    /** Исходящую тени несём через активного партнёра (шифросессия одна). */
    fun routeOutgoing(): MirrorChannel? =
        channel?.takeIf { it.canCarryOutgoing() }

    fun publishSentEcho(id: String, chatId: String, content: String, ts: Long, recipientId: String, status: String) {
        runCatching { channel?.publishSentEcho(id, chatId, content, ts, recipientId, status) }
    }

    fun close() {
        runCatching { channel?.shutdown() }
        channel = null
    }
}

/** Служебное: тег этого устройства и полка зеркала. */
object MirrorSync {
    private const val PREFS = "apu_mirror"

    fun deviceTag(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("dev", null)?.let { return it }
        val fresh = (0 until 8).joinToString("") {
            String.format("%02x", (Math.random() * 255).toInt())
        }
        prefs.edit().putString("dev", fresh).apply()
        return fresh
    }

    fun shelf(nodeId: String): String =
        sha256("apu-mirror-v1|" + nodeId.trim().lowercase()).take(32)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }
}

/**
 * Канал зеркала: WS к воркеру, роли, сердцебиение, догон.
 * Базу трогает только через [Bridge] - его реализует CoreServerService.
 */
class MirrorChannel(
    private val context: Context,
    private val scope: CoroutineScope,
    private val nodeId: String,
    private val deviceTag: String,
    /** Ядро этого устройства в сети (активное) или намеренно не запущено (зеркало). */
    private val engineUp: Boolean,
    private val engineSince: Long,
    private val bridge: Bridge,
) : WebSocketListener() {

    interface Bridge {
        suspend fun maxMessageTimestamp(): Long
        suspend fun rowsSince(since: Long, limit: Int): List<MirrorRow>
        suspend fun pendingOutgoing(limit: Int): List<MirrorRow>
        suspend fun applyIncoming(row: MirrorRow, notify: Boolean)
        suspend fun applySent(row: MirrorRow)
        suspend fun onOutgoingFromPartner(row: MirrorRow)
        fun onPromote()
        fun onDeferToShadow()
    }

    private val TAG = "MirrorChannel"
    private val shelf = MirrorSync.shelf(nodeId)

    private val wsRef = AtomicReference<WebSocket?>(null)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var backoffMs = 2_000L
    private var closedByUs = false
    private var loopJob: Job? = null

    // Партнёр (другое устройство той же личности)
    @Volatile private var partnerDev: String? = null
    @Volatile private var partnerEng: Boolean = false
    @Volatile private var partnerSince: Long = Long.MAX_VALUE
    @Volatile private var partnerMaxTs: Long = 0L
    @Volatile private var partnerLastSeen: Long = 0L
    /** Свежесть СВОЕЙ базы (кэш: hello/hb шлются из потоков okhttp). */
    @Volatile private var myMaxTs: Long = 0L
    private val startedAt = System.currentTimeMillis()

    private val deferFired = AtomicBoolean(false)
    private val promoteFired = AtomicBoolean(false)
    private var catchupInFlight = false

    /** Активный всегда может нести исходящие; тень - только при живом партнёре. */
    fun canCarryOutgoing(): Boolean =
        if (engineUp) wsRef.get() != null
        else partnerEng && partnerFresh() && wsRef.get() != null

    private fun partnerFresh(): Boolean =
        partnerDev != null && System.currentTimeMillis() - partnerLastSeen < 15_000

    fun start() {
        closedByUs = false
        ensureSelfBinding()
        connect()
        loopJob = scope.launch { maintenanceLoop() }
    }

    fun shutdown() {
        closedByUs = true
        loopJob?.cancel()
        runCatching { wsRef.getAndSet(null)?.close(1000, "bye") }
    }

    // ── Публикация событий (зовёт сервис/репозиторий) ───────────────────────

    /** Входящее сохранено на активном - показать на зеркале немедленно. */
    fun publishIncoming(row: MirrorRow) {
        if (!engineUp) return
        // Запечатывание - вызов ядра: не на главном потоке.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { sendEvent("in", row.toJson()) }
    }

    /** Исходящее ушло с активного - отразить на зеркале. */
    fun publishSentEcho(id: String, chatId: String, content: String, ts: Long, recipientId: String, status: String) {
        if (!engineUp) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sendEvent("sent", MirrorRow(id, chatId, "", "", content, ts, true, recipientId, status).toJson())
        }
    }

    /** Исходящая строка тени -> активному (тот отправит через свою сессию). */
    fun publishOutgoing(row: MirrorRow): Boolean {
        if (engineUp) return false
        return sendEvent("out", row.toJson())
    }

    // ── Транспорт ────────────────────────────────────────────────────────────

    private fun connect() {
        if (closedByUs) return
        try {
            val request = Request.Builder()
                .url("wss://" + GroupInviteLinks.WEB_HOST + "/mirror/" + shelf + "?dev=" + deviceTag)
                .build()
            val ws = client.newWebSocket(request, this)
            wsRef.set(ws)
        } catch (e: Exception) {
            Log.w(TAG, "connect failed: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (closedByUs) return
        scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            connect()
        }
    }

    private fun sendJson(obj: JSONObject): Boolean {
        val ws = wsRef.get() ?: return false
        return try {
            ws.send(obj.toString())
        } catch (e: Exception) {
            false
        }
    }

    private fun hello(): JSONObject = JSONObject()
        .put("t", "hello")
        .put("dev", deviceTag)
        .put("eng", if (engineUp) 1 else 0)
        .put("since", if (engineUp) engineSince else 0L)
        .put("max", myMaxTs)

    private fun heartbeat(): JSONObject = JSONObject()
        .put("t", "hb")
        .put("dev", deviceTag)
        .put("eng", if (engineUp) 1 else 0)
        .put("since", if (engineUp) engineSince else 0L)
        .put("max", myMaxTs)

    private fun sendEvent(kind: String, payload: JSONObject): Boolean {
        if (wsRef.get() == null) return false
        val sealed = sealPayload(payload) ?: return false
        return sendJson(JSONObject().put("t", "ev").put("k", kind).put("d", sealed))
    }

    // ── Шифрование: запечатать на самого себя ────────────────────────────────

    private fun ensureSelfBinding() {
        runCatching {
            if (!MessageSealer.canSeal(context, nodeId)) {
                FileExchangeKeyStore.publicBinding(context)?.let { binding ->
                    MessageSealer.remember(context, nodeId, binding)
                }
            }
        }
    }

    private fun sealPayload(payload: JSONObject): String? {
        ensureSelfBinding()
        return MessageSealer.seal(context, nodeId, payload.toString())
    }

    private fun openPayload(wire: String): JSONObject? =
        MessageSealer.open(context, wire)?.let { JSONObject(it) }

    // ── Цикл обслуживания: сердцебиение, роли, догон ─────────────────────────

    private suspend fun maintenanceLoop() {
        var lastHb = 0L
        while (kotlin.coroutines.coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            runCatching { myMaxTs = bridge.maxMessageTimestamp() }
            // Партнёр протух - забыть его состояние.
            if (partnerDev != null && now - partnerLastSeen > 30_000) {
                partnerDev = null
                partnerEng = false
                partnerSince = Long.MAX_VALUE
                partnerMaxTs = 0L
                catchupInFlight = false
            }
            val partnerAlive = partnerFresh()
            // Роли: активный уступает более старому ядру; зеркало повышается,
            // если активного нет дольше 30 с (или оно так и не появилось).
            if (engineUp && partnerEng && partnerAlive && deferFired.compareAndSet(false, true)) {
                val iAmOlder = engineSince < partnerSince
                val sameAgeLowerTag = engineSince == partnerSince && deviceTag < (partnerDev ?: "")
                if (!iAmOlder && !sameAgeLowerTag) {
                    Log.i(TAG, "партнёр старше - ухожу в зеркало")
                    bridge.onDeferToShadow()
                } else {
                    deferFired.set(false)
                }
            }
            if (!engineUp && promoteFired.compareAndSet(false, true)) {
                val neverSeen = partnerDev == null && now - startedAt > 45_000
                val lost = partnerDev != null && !partnerAlive && now - partnerLastSeen > 30_000
                val partnerWithoutEngine = partnerAlive && !partnerEng
                if (neverSeen || lost || partnerWithoutEngine) {
                    Log.i(TAG, "активного нет - повышаюсь (neverSeen=$neverSeen lost=$lost dead=$partnerWithoutEngine)")
                    bridge.onPromote()
                } else {
                    promoteFired.set(false)
                }
            }
            // Сердцебиение: рядом с партнёром чаще, в одиночку - реже.
            val interval = if (partnerAlive) 5_000L else 20_000L
            if (now - lastHb >= interval && wsRef.get() != null) {
                lastHb = now
                sendJson(heartbeat())
            }
            delay(2_500L)
        }
    }

    private fun onPartnerFrame(obj: JSONObject) {
        val dev = obj.optString("dev")
        if (dev.isBlank() || dev == deviceTag) return
        partnerDev = dev
        partnerEng = obj.optInt("eng") == 1
        partnerSince = obj.optLong("since", 0L).takeIf { it > 0 } ?: Long.MAX_VALUE
        val max = obj.optLong("max", 0L)
        val becameFreshMax = max > partnerMaxTs
        partnerMaxTs = max
        partnerLastSeen = System.currentTimeMillis()
        maybeCatchup(force = becameFreshMax)
    }

    /** Отстаю от партнёра - сам запросить хвост. */
    private fun maybeCatchup(force: Boolean) {
        if (catchupInFlight && !force) return
        if (partnerMaxTs > myMaxTs + 5_000) {
            catchupInFlight = true
            sendJson(
                JSONObject()
                    .put("t", "req")
                    .put("dev", deviceTag)
                    .put("since", (myMaxTs - 60_000L).coerceAtLeast(0L))
            )
        }
    }

    /** Партнёр отстал - выслать хвост порциями. */
    private fun serveCatchup(since: Long) {
        scope.launch {
            val rows = bridge.rowsSince(since, BATCH_ROWS)
            val more = rows.size >= BATCH_ROWS
            val upto = rows.maxOfOrNull { it.timestamp } ?: since
            var chunk = rows
            // Кадр не бесконечный: если не запечатался - режем порцию.
            while (chunk.isNotEmpty()) {
                val body = JSONObject()
                    .put("rows", JSONArray().apply { chunk.forEach { put(it.toJson()) } })
                    .put("more", if (chunk.size == rows.size) more else false)
                    .put("upto", upto)
                val sealed = sealPayload(body)
                if (sealed != null) {
                    sendJson(JSONObject().put("t", "ev").put("k", "batch").put("d", sealed))
                    return@launch
                }
                chunk = chunk.take(chunk.size / 2)
            }
            // Даже одна строка не запечаталась (экзотика) - всё равно сдвинуть
            // партнёра с места, иначе он будет просить один и тот же хвост.
            val body = JSONObject().put("rows", JSONArray()).put("more", false).put("upto", upto)
            sealPayload(body)?.let { sealed ->
                sendJson(JSONObject().put("t", "ev").put("k", "batch").put("d", sealed))
            }
        }
    }

    private fun handleEventBatch(wire: String) {
        val body = openPayload(wire) ?: return
        val rows = body.optJSONArray("rows") ?: return
        val upto = body.optLong("upto", 0L)
        val more = body.optBoolean("more", false)
        scope.launch {
            var last = 0L
            for (i in 0 until rows.length()) {
                val row = MirrorRow.fromJson(rows.getJSONObject(i))
                if (row.id.isBlank()) continue
                if (row.mine) bridge.applySent(row) else bridge.applyIncoming(row, notify = false)
                last = row.timestamp
            }
            if (more && last > 0) {
                sendJson(JSONObject().put("t", "req").put("dev", deviceTag).put("since", last))
            } else if (rows.length() == 0 && upto > 0) {
                // Пустая порция со сдвигом: дальше просить уже от upto.
                sendJson(JSONObject().put("t", "req").put("dev", deviceTag).put("since", upto))
            } else {
                catchupInFlight = false
            }
        }
    }

    private fun handleEvent(kind: String, wire: String) {
        when (kind) {
            "in" -> {
                val row = openPayload(wire)?.let { MirrorRow.fromJson(it) } ?: return
                scope.launch { bridge.applyIncoming(row, notify = true) }
            }
            "sent" -> {
                val row = openPayload(wire)?.let { MirrorRow.fromJson(it) } ?: return
                scope.launch { bridge.applySent(row) }
            }
            "out" -> {
                // Только активный отправляет: тень шлёт через партнёра.
                if (!engineUp) return
                val row = openPayload(wire)?.let { MirrorRow.fromJson(it) } ?: return
                scope.launch { bridge.onOutgoingFromPartner(row) }
            }
            "batch" -> handleEventBatch(wire)
        }
    }

    // ── WebSocketListener ────────────────────────────────────────────────────

    override fun onOpen(webSocket: WebSocket, response: Response) {
        backoffMs = 2_000L
        sendJson(hello())
        // Тень: невостребованные исходящие (ушли, пока партнёра не было).
        if (!engineUp) {
            scope.launch {
                bridge.pendingOutgoing(50).forEach { row ->
                    publishOutgoing(row)
                }
            }
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val obj = JSONObject(text)
            when (obj.optString("t")) {
                "hello", "hb" -> onPartnerFrame(obj)
                "req" -> {
                    val dev = obj.optString("dev")
                    if (dev.isNotBlank() && dev != deviceTag) serveCatchup(obj.optLong("since", 0L))
                }
                "ev" -> handleEvent(obj.optString("k"), obj.optString("d"))
            }
        } catch (e: Exception) {
            Log.d(TAG, "bad frame: ${e.message}")
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        wsRef.set(null)
        scheduleReconnect()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        wsRef.set(null)
        scheduleReconnect()
    }

    companion object {
        private const val BATCH_ROWS = 30
    }
}
