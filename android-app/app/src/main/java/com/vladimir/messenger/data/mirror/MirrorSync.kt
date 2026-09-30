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
import com.vladimir.messenger.data.channel.PostViewWire
import com.vladimir.messenger.data.file.FileExchangeKeyStore
import com.vladimir.messenger.data.gif.GifLibrary
import com.vladimir.messenger.data.group.GroupInviteLinks
import com.vladimir.messenger.data.heart.HeartWire
import com.vladimir.messenger.data.reaction.ReactionWire
import com.vladimir.messenger.data.receipt.ReadReceiptWire
import com.vladimir.messenger.data.repository.MessageDeletionRepository
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

    /**
     * р229: сейчас применяется зеркальный кадр. Пока флаг стоит, ответные
     * рассылки тени подавляются: иначе «принял событие - ответил» уходило бы
     * обратно активному и возвращалось петлёй.
     */
    @Volatile
    @PublishedApi
    internal var applyingFrame = false

    fun isApplyingFrame(): Boolean = applyingFrame

    /** Ведёт ли это устройство сеть (у тени движка нет). */
    fun isActiveDevice(): Boolean = channel?.isEngineUp() == true

    /**
     * Выполнить блок как «применение зеркального кадра». Inline: внутри
     * зовутся suspend-функции разбора (репозитории), а обычная лямбда их не
     * пропустит.
     */
    inline fun <T> duringApply(block: () -> T): T {
        applyingFrame = true
        return try {
            block()
        } finally {
            applyingFrame = false
        }
    }

    /** Исходящую тени несём через активного партнёра (шифросессия одна). */
    fun routeOutgoing(): MirrorChannel? =
        channel?.takeIf { it.canCarryOutgoing() }

    fun publishSentEcho(id: String, chatId: String, content: String, ts: Long, recipientId: String, status: String) {
        runCatching { channel?.publishSentEcho(id, chatId, content, ts, recipientId, status) }
    }

    /** р227: входящий служебный конверт - пусть партнёр применит его у себя. */
    fun publishEnvelope(senderId: String, chatId: String, messageId: String, text: String) {
        runCatching { channel?.publishEnvelope(senderId, chatId, messageId, text) }
    }

    /**
     * р228: действие, сделанное на ТЕНИ (реакция, удаление, просмотр,
     * сердечко, прочтение), -> активному.
     *
     * @return true, если конверт ушёл партнёру: тогда своей сети не пробуем
     *         (у тени её нет, а у активного сессия одна).
     */
    fun deliverAction(peerId: String, groupId: String, chatId: String, text: String): Boolean =
        runCatching {
            if (applyingFrame) false
            else channel?.publishOutgoingEnvelope(peerId, groupId, chatId, text) == true
        }.getOrDefault(false)

    /**
     * р228: СВОЁ действие активного (реакция, удаление, просмотр, сердечко,
     * прочтение) - отдать партнёрскому устройству, чтобы он показал то же
     * самое. Шлёт только активный; тень ничего не делает (её действия идут
     * обратным путём через [deliverAction]).
     */
    fun publishOwnAction(peerId: String, groupId: String, chatId: String, text: String) {
        runCatching { channel?.publishActiveEnvelope(peerId, groupId, chatId, text) }
    }

    /**
     * р228: групповой конверт, сделанный на ТЕНИ (сообщение, пост, тема,
     * закреп, состав) -> активному: тот применит его у себя и разошлёт
     * участникам одной сессией аккаунта.
     */
    fun deliverGroupEnvelope(groupId: String, envelope: String): Boolean =
        runCatching {
            if (applyingFrame) false
            else channel?.publishOutgoingEnvelope("", groupId, groupId, envelope) == true
        }.getOrDefault(false)

    /**
     * р228: переписку прочитали на этом устройстве - снять непрочитанное и
     * на партнёрском, иначе бейджи разойдутся. Ничего не рассылает в сеть,
     * поэтому кадр безопасно звать и с активного, и с тени.
     */
    fun publishReadSync(peerId: String, groupId: String, topicId: String = "") {
        runCatching { channel?.publishReadSync(peerId, groupId, topicId) }
    }

    fun close() {
        runCatching { channel?.shutdown() }
        channel = null
    }
}

/**
 * р227: служебные конверты, которые зеркало гоняет между устройствами одной
 * личности. Здесь только «безопасные»: их разбор на втором устройстве -
 * чистая запись в базу (реакция, удаление, просмотр поста, сердечко,
 * прочтение, ссылка на гифку), без сети и без байтов. Файловые и звонковые
 * конверты придут отдельными этапами: им нужны байты и своя логика ответа.
 */
object MirrorEnvelopes {
    fun isSafe(text: String): Boolean =
        com.vladimir.messenger.data.group.GroupWire.isGroupPacket(text) ||
            ReactionWire.isReactionPacket(text) ||
            text.startsWith(MessageDeletionRepository.PREFIX + "|") ||
            PostViewWire.isViewPacket(text) ||
            HeartWire.isHeartPacket(text) ||
            ReadReceiptWire.isReadReceipt(text) ||
            GifLibrary.isGifRef(text)
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
        /** р227: применить служебный конверт, пришедший от партнёра. */
        suspend fun applyEnvelope(senderId: String, chatId: String, messageId: String, text: String)
        /**
         * р228: действие, сделанное на партнёрском устройстве - применить у
         * себя (без сети).
         */
        suspend fun onActionFromPartner(peerId: String, groupId: String, chatId: String, text: String)
        /**
         * р228: конверт, сделанный на тени, отправить в сеть. Зовётся только
         * у активного: у тени своей сессии нет.
         */
        suspend fun onActionToPeers(peerId: String, groupId: String, text: String)
        /** р228: на партнёрском устройстве сняли непрочитанное - снять и у себя. */
        suspend fun onReadFromPartner(peerId: String, groupId: String, topicId: String)
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

    /** Ведёт ли ЭТО устройство сеть (активное) или живёт зеркалом. */
    fun isEngineUp(): Boolean = engineUp

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

    /**
     * р228: действие с тени -> активному. Шлёт только тень и только при
     * живом партнёре: своей сети у неё нет. Активный отправит конверт
     * собеседнику или участникам группы своей сессией и применит действие
     * у себя.
     *
     * @param peerId собеседник личного чата (пусто для группового действия)
     * @param groupId группа/канал (пусто для личного действия)
     * @return true - кадр ушёл партнёру.
     */
    fun publishOutgoingEnvelope(
        peerId: String,
        groupId: String,
        chatId: String,
        text: String,
    ): Boolean {
        if (engineUp) return false
        if (!canCarryOutgoing()) return false
        if (text.isEmpty() || text.length > MAX_ENVELOPE_CHARS) return false
        if (peerId.isBlank() && groupId.isBlank()) return false
        val body = JSONObject()
            .put("p", peerId)
            .put("g", groupId)
            .put("c", chatId)
            .put("t", text)
        val sealed = sealPayload(body) ?: return false
        return sendJson(JSONObject().put("t", "ev").put("k", "outenv").put("d", sealed))
    }

    /**
     * р228: снять непрочитанное и на партнёрском устройстве. Шлём в обе
     * стороны (активный напрямую, тень - тем же каналом): применения кадра
     * повторно не рассылают, петли нет.
     */
    fun publishReadSync(peerId: String, groupId: String, topicId: String) {
        if (peerId.isBlank() && groupId.isBlank()) return
        if (wsRef.get() == null) return
        val body = JSONObject()
            .put("p", peerId)
            .put("g", groupId)
            .put("tp", topicId)
        val sealed = sealPayload(body) ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sendJson(JSONObject().put("t", "ev").put("k", "read").put("d", sealed))
        }
    }

    /**
     * р228: своё (сделанное здесь) действие активного - партнёру, чтобы тот
     * показал то же самое. У тени партнёром является активный, поэтому кадр
     * шлёт только ведущее сеть устройство.
     */
    fun publishActiveEnvelope(peerId: String, groupId: String, chatId: String, text: String): Boolean {
        if (!engineUp) return false
        if (wsRef.get() == null) return false
        if (text.isEmpty() || text.length > MAX_ENVELOPE_CHARS) return false
        if (peerId.isBlank() && groupId.isBlank()) return false
        val body = JSONObject()
            .put("p", peerId)
            .put("g", groupId)
            .put("c", chatId)
            .put("t", text)
        val sealed = sealPayload(body) ?: return false
        return sendJson(JSONObject().put("t", "ev").put("k", "outenv").put("d", sealed))
    }

    /**
     * р227: входящий служебный конверт (реакция, удаление, просмотр поста,
     * сердечко, прочтение, ссылка на гифку) -> зеркалу, чтобы и оно его
     * применило. Шлёт только активный: у тени сети нет. Кадр маленький,
     * потолок открытого текста - [MAX_ENVELOPE_CHARS].
     */
    fun publishEnvelope(senderId: String, chatId: String, messageId: String, text: String) {
        if (!engineUp) return
        if (senderId.isBlank() || text.isEmpty() || text.length > MAX_ENVELOPE_CHARS) return
        if (!MirrorEnvelopes.isSafe(text)) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val body = JSONObject()
                .put("s", senderId)
                .put("c", chatId)
                .put("m", messageId)
                .put("t", text)
            val sealed = sealPayload(body) ?: return@launch
            sendJson(JSONObject().put("t", "ev").put("k", "env").put("d", sealed))
        }
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
            "read" -> {
                // р228: на партнёре сняли непрочитанное - снимаем и у себя.
                val body = openPayload(wire) ?: return
                scope.launch {
                    bridge.onReadFromPartner(
                        peerId = body.optString("p"),
                        groupId = body.optString("g"),
                        topicId = body.optString("tp"),
                    )
                }
            }
            "outenv" -> {
                // р228: действие партнёрского устройства. Применяем всегда,
                // а в сеть отправляем только если сеть ведём мы (у тени
                // сессии нет, кадр придёт от активного вторым шагом).
                val body = openPayload(wire) ?: return
                val text = body.optString("t")
                if (text.isBlank()) return
                scope.launch {
                    MirrorHub.duringApply {
                        bridge.onActionFromPartner(
                            peerId = body.optString("p"),
                            groupId = body.optString("g"),
                            chatId = body.optString("c"),
                            text = text,
                        )
                    }
                    if (engineUp) {
                        bridge.onActionToPeers(
                            peerId = body.optString("p"),
                            groupId = body.optString("g"),
                            text = text,
                        )
                    }
                }
            }
            "env" -> {
                // р227: служебный конверт от партнёра - применяем тем же
                // разбором, что и на активном устройстве (сети у тени нет).
                val body = openPayload(wire) ?: return
                val senderId = body.optString("s")
                if (senderId.isBlank()) return
                scope.launch {
                    MirrorHub.duringApply {
                        bridge.applyEnvelope(
                            senderId,
                            body.optString("c"),
                            body.optString("m"),
                            body.optString("t"),
                        )
                    }
                }
            }
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

        /** р227: потолок открытого текста зеркального конверта (кадр - 512 КиБ). */
        private const val MAX_ENVELOPE_CHARS = 48_000
    }
}
