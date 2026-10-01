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
import com.vladimir.messenger.data.receipt.DeliveryAckWire
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

    /** р240: сколько своих сообщений ещё не отправлено (обновляет сервис). */
    @Volatile private var pendingOutgoing: Int = -1

    fun setPendingOutgoing(count: Int) {
        pendingOutgoing = count
    }

    /**
     * р243: собственный адрес узла, взятый из ядра ОДИН раз.
     *
     * Раньше `RustBridge.nodeId()` дёргался на каждом нажатии клавиши (страж
     * «печатает»), на каждом обновлении чата и на каждом входящем сообщении.
     * Это вызов через JNI в ядро: пока он отвечает, главный поток стоит - у
     * владельца это выглядело как «приложение подвисает на 5 секунд, когда
     * начинаешь печатать» и «APU не отвечает». Адрес узла не меняется, пока
     * работает движок, поэтому кэшируем его и обновляем в цикле обслуживания.
     */
    @Volatile private var selfNodeId: String = ""

    /** Свой адрес из кэша: безопасно вызывать с главного потока. */
    fun nodeIdCached(): String = selfNodeId

    /** Обновить кэш адреса. Зовёт канал зеркала: он и так знает свой узел. */
    internal fun noteSelfNodeId(id: String) {
        if (id.isNotBlank()) selfNodeId = id
    }

    /** р242: когда последний раз пришло/ушло настоящее сообщение (диагностика). */
    @Volatile private var lastIncomingAt: Long = 0L
    @Volatile private var lastOutgoingAt: Long = 0L

    fun noteIncoming() {
        lastIncomingAt = System.currentTimeMillis()
    }

    fun noteOutgoing() {
        lastOutgoingAt = System.currentTimeMillis()
    }

    /**
     * р242: сколько пакетов пришло от СОБСТВЕННОГО узла (в контакты попал свой
     * адрес). По этому числу в диагностике сразу видно, что собеседник - это вы
     * сами: сообщения «не доходят» именно поэтому.
     */
    @Volatile private var selfPackets: Int = 0

    fun noteSelfPacket() {
        selfPackets++
    }

    private fun ago(at: Long): String {
        if (at <= 0L) return "не было"
        val sec = (System.currentTimeMillis() - at) / 1000
        return when {
            sec < 90 -> "$sec с назад"
            sec < 3600 -> "${sec / 60} мин назад"
            else -> "${sec / 3600} ч назад"
        }
    }

    /**
     * р240: состояние синхронизации устройств одной фразой - для экрана
     * диагностики. Показывает роль, партнёра, канал и недоотправленные
     * сообщения: по этим четырём строкам сразу видно, где заминка.
     */
    fun debugStatus(): String {
        val mirror = runCatching { channel?.debugState() }.getOrNull()
            ?: "зеркало не запущено (устройство одно)"
        val pending = if (pendingOutgoing < 0) "неизвестно" else pendingOutgoing.toString()
        val me = selfNodeId.ifBlank {
            runCatching { com.vladimir.messenger.data.RustBridge.nodeId() }.getOrNull().orEmpty()
        }
        return mirror +
            "\nмой узел: " + me.take(16).ifBlank { "неизвестен" } +
            "\nпоследнее входящее: " + ago(lastIncomingAt) +
            "\nпоследнее исходящее: " + ago(lastOutgoingAt) +
            "\nнедоотправленных сообщений: " + pending +
            (if (selfPackets > 0) "\nпакетов от собственного узла отброшено: " + selfPackets else "")
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
     * р230: принятый файл готов - рассказать о нём партнёрскому устройству.
     * Шлёт только активное: у тени сети нет.
     */
    fun publishFileMeta(meta: org.json.JSONObject) {
        runCatching { channel?.publishFileMeta(meta) }
    }

    /** р230: попросить байты файла у партнёра (тень просит активного). */
    fun requestFileBytes(transferId: String, displayName: String, totalBytes: Long) {
        runCatching { channel?.requestFileBytes(transferId, displayName, totalBytes) }
    }

    /**
     * р245: попросить у партнёра байты ГИФКИ по её отпечатку.
     *
     * Гифка ходит по переписке ссылкой, а байты каждый телефон тянет сам.
     * Активному есть откуда (роя хранителей он видит), а у тени сети нет - её
     * карточка оставалась пустой. Теперь тень просит байты у активного: гонять
     * гифку по зеркальному каналу (десятки-сотни килобайт) можно.
     */
    fun requestGifBytes(sha256: String) {
        runCatching { channel?.requestGifBytes(sha256) }
    }

    /**
     * р245: отдать байты гифки партнёру БЕЗ просьбы.
     *
     * Так делает тень, когда отправляет гифку из своей библиотеки: сети у неё
     * нет, поэтому хранителем для собеседника она быть не может - байты
     * уходят активному, тот кладёт их в свою библиотеку и объявляет каталог
     * рою. Иначе у собеседника (и у активного) карточка осталась бы пустой.
     */
    fun pushGifBytes(sha256: String) {
        runCatching { channel?.pushGifBytes(sha256) }
    }

    /**
     * р231: тень с готовым исходящим файлом просит движок у активного.
     * Отправка файла требует своей сетевой сессии, а она может быть только
     * у одного устройства; активный уступает роль, тень поднимается и шлёт.
     */
    fun claimEngine(): Boolean = channel?.claimEngine() == true

    /** Тень и есть живой активный партнёр (значит, просить движок есть у кого). */
    fun canClaimEngine(): Boolean = channel?.canClaimEngine() == true

    /**
     * р233: звонковый сигнал партнёрскому устройству. Входящий звонок звонит
     * на обоих, ответ/завершение/отклонение видны обоим. Шлём с любого
     * устройства: у звонка, как и у файлов, роли могут меняться.
     */
    fun publishCallSignal(signal: org.json.JSONObject) {
        runCatching { channel?.publishCallSignal(signal) }
    }

    /**
     * р234: изменение азбуки адресов (контакт добавлен, переименован,
     * удалён). Шлём в обе стороны: база у каждого устройства своя, а
     * список людей должен совпадать. Повторно не рассылаем при применении
     * чужого кадра - иначе «принял - отправил» замкнулось бы петлёй.
     */
    fun publishContact(signal: org.json.JSONObject) {
        if (isApplyingFrame()) return
        runCatching { channel?.publishContact(signal) }
    }

    /**
     * р235: собеседник печатает - второму устройству личности, чтобы
     * индикатор был на обоих телефонах. Шлёт то устройство, которое
     * получило пакет из сети (у тени сессии нет).
     */
    fun publishTyping(peerId: String, typing: Boolean) {
        if (peerId.isBlank()) return
        runCatching { channel?.publishTyping(peerId, typing) }
    }

    /**
     * р236: черновик сообщения - второму устройству личности. Ключ черновика
     * (адрес собеседника или идентификатор группы) одинаков на обоих
     * устройствах, поэтому текст переносится как есть. При применении чужого
     * кадра повторно не рассылаем - иначе получилась бы петля.
     */
    fun publishDraft(key: String, text: String) {
        if (key.isBlank()) return
        if (isApplyingFrame()) return
        runCatching { channel?.publishDraft(key, text) }
    }

    /**
     * Закрепление/открепление личного сообщения или публикации канала.
     * Собеседнику/подписчикам личный закреп не уходит, но на устройствах
     * одной личности он совпадает. id сообщения у устройств общий, поэтому
     * кадра достаточно. При применении чужого кадра не рассылаем повторно.
     */
    fun publishPin(messageId: String, pinned: Boolean) {
        if (messageId.isBlank()) return
        if (isApplyingFrame()) return
        runCatching { channel?.publishPin(messageId, pinned) }
    }

    /** A home-inbox pin; personal ids are contact ids, group ids are stable ids. */
    fun publishInboxPin(kind: String, itemId: String, pinned: Boolean, pinnedAtMs: Long) {
        if (kind.isBlank() || itemId.isBlank() || isApplyingFrame()) return
        runCatching { channel?.publishInboxPin(kind, itemId, pinned, pinnedAtMs) }
    }

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
    /**
     * р244: подтверждение доставки («ack|id»).
     *
     * Это служебная строка, а не текст переписки. Её получает устройство,
     * которое приняло сообщение из сети, - и обязано передать партнёрскому,
     * иначе на втором телефоне той же личности галочка так и останется одна
     * (сообщение видно, а «доставлено» нет).
     */
    fun isServiceAck(text: String): Boolean = DeliveryAckWire.messageId(text) != null

    fun isSafe(text: String): Boolean =
        isServiceAck(text) ||
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
        /**
         * р230: принятый файл завершился - показать его и на партнёрском
         * устройстве (строка передачи + карточка в чате).
         */
        suspend fun onFileMeta(meta: JSONObject): Boolean
        /** р230: кусок файла от партнёра - сложить в приёмник. */
        suspend fun onFileChunk(transferId: String, seq: Int, last: Boolean, bytes: ByteArray)
        /** р230: прочитать кусок своего файла, чтобы отдать партнёру. */
        suspend fun fileBytesFor(transferId: String, displayName: String, offset: Long, size: Int): ByteArray?
        /** р232: начинается прямой (LAN) приём файла - забыть недокачанное. */
        suspend fun onFilePullStart(transferId: String)
        /** р233: звонковый сигнал партнёрского устройства (звонит на обоих). */
        suspend fun onCallFromPartner(signal: JSONObject)
        /** р234: контакт изменился на партнёрском устройстве. */
        suspend fun onContactFromPartner(signal: JSONObject)
        /** р235: собеседник печатает (увидело партнёрское устройство). */
        suspend fun onTypingFromPartner(peerId: String, typing: Boolean)
        /** р236: черновик с партнёрского устройства (текст незакрытой строки). */
        suspend fun onDraftFromPartner(key: String, text: String)
        /** р238: сообщение закреплено/откреплено на партнёрском устройстве. */
        suspend fun onPinFromPartner(messageId: String, pinned: Boolean)
        /** Home-inbox conversation pin changed on the partner device. */
        suspend fun onInboxPinFromPartner(
            kind: String,
            itemId: String,
            pinned: Boolean,
            pinnedAtMs: Long,
        )
        fun onPromote()
        fun onDeferToShadow()
        /**
         * р231: активного просят уступить движок (партнёр хочет отправить
         * файл). Возврат true - уступаем и перезапускаемся зеркалом.
         */
        fun onEngineClaimed(): Boolean
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
    /** р231: когда в последний раз просили движок (передача роли). */
    @Volatile private var lastClaimAt = 0L

    /** Ведёт ли ЭТО устройство сеть (активное) или живёт зеркалом. */
    fun isEngineUp(): Boolean = engineUp

    /**
     * р240: человекочитаемое состояние для экрана диагностики (владелец,
     * Настройки -> «Диагностика синхронизации»). Без имён и секретов: только
     * роль, партнёр и канал - этого хватает, чтобы понять, почему не едет.
     */
    fun debugState(): String {
        val dev = partnerDev
        val agoSec = if (dev != null) (System.currentTimeMillis() - partnerLastSeen) / 1000 else -1
        return buildString {
            append("роль: ").append(
                if (engineUp) "ведущий (сеть веду я)" else "зеркало (сети у меня нет)"
            )
            append("\nпартнёр: ")
            if (dev.isNullOrBlank()) {
                append("не видно")
            } else {
                append(dev)
                append(", ").append(agoSec).append(" с назад")
                append(", движок у него: ").append(if (partnerEng) "да" else "нет")
            }
            append("\nканал зеркала: ")
                .append(if (wsRef.get() != null) "подключён" else "нет связи")
            append("\nметка устройства: ").append(deviceTag)
        }
    }

    /** Активный всегда может нести исходящие; тень - только при живом партнёре. */
    fun canCarryOutgoing(): Boolean =
        if (engineUp) wsRef.get() != null
        else partnerEng && partnerFresh() && wsRef.get() != null

    /**
     * р231: тень, рядом с которой есть живой активный. Именно у него можно
     * попросить движок, чтобы отправить файл со второго устройства.
     */
    fun canClaimEngine(): Boolean =
        !engineUp && partnerEng && partnerFresh() && wsRef.get() != null

    /**
     * р231: попросить у активного движок. Просим один раз в минуту: передача
     * роли - перезапуск сервиса на обоих устройствах, частить нельзя.
     */
    fun claimEngine(): Boolean {
        if (!canClaimEngine()) return false
        val now = System.currentTimeMillis()
        if (now - lastClaimAt < CLAIM_COOLDOWN_MS) return false
        lastClaimAt = now
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sendEvent("claim", JSONObject().put("r", "engine"))
        }
        return true
    }

    /**
     * р233: звонковый сигнал партнёру (звонит на обоих, состояние общее).
     * Отправка - тем же зеркальным каналом, запечатана на себя.
     */
    fun publishCallSignal(signal: JSONObject) {
        if (wsRef.get() == null) return
        val sealed = sealPayload(signal) ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sendJson(JSONObject().put("t", "ev").put("k", "call").put("d", sealed))
        }
    }

    /** р234: изменение контакта - партнёрскому устройству (списки должны совпадать). */
    fun publishContact(signal: JSONObject) {
        if (wsRef.get() == null) return
        val sealed = sealPayload(signal) ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sendJson(JSONObject().put("t", "ev").put("k", "contact").put("d", sealed))
        }
    }

    /** р235: «собеседник печатает» - партнёрскому устройству личности. */
    fun publishTyping(peerId: String, typing: Boolean) {
        if (wsRef.get() == null) return
        val sealed = sealPayload(
            JSONObject().put("p", peerId).put("on", if (typing) 1 else 0),
        ) ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sendJson(JSONObject().put("t", "ev").put("k", "typing").put("d", sealed))
        }
    }

    /** р236: черновик - партнёрскому устройству личности. */
    fun publishDraft(key: String, text: String) {
        if (wsRef.get() == null) return
        if (text.length > DRAFT_MAX_CHARS) return
        // р243: запечатывание (вызов в ядро + AES) уходит в фоновый поток.
        // Раньше оно шло прямо по главному - на каждом изменении поля ввода.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sealed = sealPayload(JSONObject().put("k", key).put("t", text)) ?: return@launch
            sendJson(JSONObject().put("t", "ev").put("k", "draft").put("d", sealed))
        }
    }

    /** Личный закреп сообщения/поста канала - партнёрскому устройству личности. */
    fun publishPin(messageId: String, pinned: Boolean) {
        if (wsRef.get() == null) return
        // р243: запечатывание - в фоне (см. publishDraft).
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sealed = sealPayload(
                JSONObject().put("id", messageId).put("on", if (pinned) 1 else 0),
            ) ?: return@launch
            sendJson(JSONObject().put("t", "ev").put("k", "pin").put("d", sealed))
        }
    }

    /** Pin or unpin a home-inbox conversation on the partner device. */
    fun publishInboxPin(kind: String, itemId: String, pinned: Boolean, pinnedAtMs: Long) {
        if (wsRef.get() == null) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sealed = sealPayload(
                JSONObject()
                    .put("kind", kind)
                    .put("id", itemId)
                    .put("on", if (pinned) 1 else 0)
                    .put("at", pinnedAtMs),
            ) ?: return@launch
            sendJson(JSONObject().put("t", "ev").put("k", "inbox_pin").put("d", sealed))
        }
    }

    /** р245: когда в последний раз просили байты гифки (не частим просьбами). */
    private val gifRequestedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** р245: недособранные гифки, которые шлёт партнёр. */
    private val gifIncoming =
        java.util.concurrent.ConcurrentHashMap<String, java.io.ByteArrayOutputStream>()

    private fun partnerFresh(): Boolean =
        partnerDev != null && System.currentTimeMillis() - partnerLastSeen < 15_000

    fun start() {
        closedByUs = false
        ensureSelfBinding()
        // р243: свой адрес нужен сразу - до первого прохода цикла. Канал его и
        // так знает (получен при создании), поэтому вызова в ядро нет.
        MirrorHub.noteSelfNodeId(nodeId)
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
     * р230: «файл принят» - метаданные партнёрскому устройству. Байты он
     * попросит сам ([requestFileBytes]): так передача не начнётся, если
     * второе устройство сейчас не в сети или файл ему не нужен.
     */
    fun publishFileMeta(meta: JSONObject) {
        if (!engineUp) return
        if (wsRef.get() == null) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sealed = sealPayload(meta) ?: return@launch
            sendJson(JSONObject().put("t", "ev").put("k", "filemeta").put("d", sealed))
        }
    }

    /** р230: тень просит у активного байты принятого файла. */
    fun requestFileBytes(transferId: String, displayName: String, totalBytes: Long): Boolean {
        if (engineUp) return false
        if (!canCarryOutgoing()) return false
        if (transferId.isBlank()) return false
        val body = JSONObject()
            .put("id", transferId)
            .put("name", displayName)
            .put("size", totalBytes)
        val sealed = sealPayload(body) ?: return false
        return sendJson(JSONObject().put("t", "ev").put("k", "reqfile").put("d", sealed))
    }

    /** р230: отдать партнёру кусок файла (шлёт тот, у кого байты есть). */
    private fun sendFileChunk(transferId: String, seq: Int, last: Boolean, bytes: ByteArray) {
        val body = JSONObject()
            .put("id", transferId)
            .put("seq", seq)
            .put("last", if (last) 1 else 0)
            .put("b64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        val sealed = sealPayload(body) ?: return
        sendJson(JSONObject().put("t", "ev").put("k", "filechunk").put("d", sealed))
    }

    /**
     * р245: тень просит байты гифки у активного. Гифка небольшая (единицы
     * мегабайт), но канал зеркала общий с сообщениями, поэтому порции те же,
     * что у файлов, и с той же паузой.
     *
     * Повторы намеренно редкие: просьба может уйти раньше, чем активный сам
     * скачает байты с хранителей, - тогда он их не отдаст, а мы попробуем
     * снова следующим заходом.
     */
    fun requestGifBytes(sha256: String): Boolean {
        if (engineUp) return false
        if (!canCarryOutgoing()) return false
        if (!isHexSha(sha256)) return false
        val now = System.currentTimeMillis()
        val last = gifRequestedAt[sha256] ?: 0L
        if (now - last < GIF_REQUEST_COOLDOWN_MS) return false
        gifRequestedAt[sha256] = now
        val sealed = sealPayload(JSONObject().put("sha", sha256)) ?: return false
        return sendJson(JSONObject().put("t", "ev").put("k", "reqgif").put("d", sealed))
    }

    /** р245: порция байтов гифки партнёру. */
    private fun sendGifChunk(sha256: String, seq: Int, last: Boolean, bytes: ByteArray) {
        val body = JSONObject()
            .put("sha", sha256)
            .put("seq", seq)
            .put("last", if (last) 1 else 0)
            .put("b64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        val sealed = sealPayload(body) ?: return
        sendJson(JSONObject().put("t", "ev").put("k", "gifchunk").put("d", sealed))
    }

    /**
     * р245: отдать гифку партнёру, если байты у нас есть. Байтов нет - молчим:
     * значит, мы сами ещё не скачали их с хранителей (или это ссылка вовсе без
     * локальной копии). Тень повторит просьбу позже.
     */
    private fun serveGif(sha256: String) {
        if (!engineUp) return
        val file = com.vladimir.messenger.data.gif.GifLibrary.gifFile(context, sha256)
            ?: com.vladimir.messenger.data.gif.GifLibrary.previewFile(context, sha256)
            ?: run {
                Log.i(TAG, "gif mirror: байтов нет локально " + sha256.take(8))
                return
            }
        sendGifFile(sha256, file)
    }

    /**
     * р245: тень отдаёт байты гифки из своей библиотеки без просьбы - активный
     * положит их к себе и объявит рой (см. [MirrorHub.pushGifBytes]).
     */
    fun pushGifBytes(sha256: String): Boolean {
        if (engineUp) return false
        if (!canCarryOutgoing()) return false
        if (!isHexSha(sha256)) return false
        val file = com.vladimir.messenger.data.gif.GifLibrary.gifFile(context, sha256) ?: return false
        sendGifFile(sha256, file)
        return true
    }

    /** Отправка байтов гифки порциями (общая для отдачи по просьбе и push). */
    private fun sendGifFile(sha256: String, file: java.io.File) {
        if (file.length() > GIF_MIRROR_MAX_BYTES) {
            // Канал зеркала идёт через воркер и общий с сообщениями: совсем
            // большой гифке (или гифке-видео) здесь не место. Такая остаётся
            // там, где её скачали.
            Log.i(TAG, "gif mirror: пропуск, " + file.length() + " Б > " + GIF_MIRROR_MAX_BYTES)
            return
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) return@launch
            var offset = 0
            var seq = 0
            while (offset < bytes.size) {
                val end = minOf(offset + GIF_CHUNK_BYTES, bytes.size)
                sendGifChunk(sha256, seq, end >= bytes.size, bytes.copyOfRange(offset, end))
                seq++
                offset = end
                kotlinx.coroutines.delay(FILE_CHUNK_PAUSE_MS)
            }
            Log.i(TAG, "gif mirror: отдано " + bytes.size + " Б (" + seq + " порций) " + sha256.take(8))
        }
    }

    /**
     * р245: порция гифки от партнёра. Складываем в библиотеку тем же путём, что
     * и принятую по рою: карточка в чате оживёт сама (GifLibrary оповещает
     * подписчиков).
     */
    private suspend fun applyGifChunk(sha256: String, last: Boolean, bytes: ByteArray) {
        val buf = gifIncoming.getOrPut(sha256) { java.io.ByteArrayOutputStream() }
        if (buf.size() + bytes.size > GIF_MIRROR_MAX_BYTES) {
            gifIncoming.remove(sha256)
            Log.w(TAG, "gif mirror: слишком много байтов " + sha256.take(8) + ", бросаю")
            return
        }
        buf.write(bytes)
        if (!last) return
        gifIncoming.remove(sha256)
        val all = buf.toByteArray()
        if (all.isEmpty()) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val tmp = java.io.File(context.cacheDir, "gif-mirror-" + sha256.take(16) + ".gif")
            runCatching {
                tmp.outputStream().use { it.write(all) }
                com.vladimir.messenger.data.gif.GifLibrary.addFromFile(context, tmp, null, null)
                tmp.delete()
            }.onFailure { Log.w(TAG, "gif mirror: не сохранилось " + sha256.take(8) + ": " + it.message) }
        }
        Log.i(TAG, "gif mirror: получено " + all.size + " Б " + sha256.take(8))
    }

    /** Отпечаток гифки - 64 знака шестнадцатеричных. */
    private fun isHexSha(sha: String): Boolean =
        sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * р230: выдать файл партнёру целиком, порциями. Размер ограничен
     * [FILE_MIRROR_MAX_BYTES]: канал зеркала идёт через воркер, и гигабайты
     * по нему гнать нельзя - такие файлы остаются на принявшем устройстве.
     */
    private fun serveFile(transferId: String, displayName: String, totalBytes: Long) {
        if (!engineUp) return
        if (totalBytes <= 0 || totalBytes > MirrorLan.MAX_BYTES) {
            Log.i(TAG, "file mirror skipped: $totalBytes B (> ${MirrorLan.MAX_BYTES})")
            return
        }
        if (totalBytes > FILE_MIRROR_MAX_BYTES) {
            // р232: большой файл через зеркальный канал не гоняем - отдаём
            // напрямую по локальной сети (если партнёр рядом, он заберёт сам).
            startLanServe(transferId, displayName, totalBytes)
            return
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var offset = 0L
            var seq = 0
            while (offset < totalBytes) {
                val want = minOf(FILE_CHUNK_BYTES.toLong(), totalBytes - offset).toInt()
                val chunk = runCatching {
                    bridge.fileBytesFor(transferId, displayName, offset, want)
                }.getOrNull()
                if (chunk == null || chunk.isEmpty()) {
                    Log.w(TAG, "file mirror: чтение не удалось на $offset")
                    return@launch
                }
                val isLast = offset + chunk.size >= totalBytes
                sendFileChunk(transferId, seq, isLast, chunk)
                seq++
                offset += chunk.size
                // Небольшая пауза: канал общий с сообщениями, не забиваем его.
                kotlinx.coroutines.delay(FILE_CHUNK_PAUSE_MS)
            }
            Log.i(TAG, "file mirror: отдано $offset Б ($seq порций) id=$transferId")
        }
    }

    /**
     * р232: большой файл - поднимаем одноразовый сервер в локальной сети и
     * говорим партнёру адрес. Если он в другой сети, ничего не выйдет: у него
     * останется карточка (как и было до этого раунда).
     */
    private fun startLanServe(transferId: String, displayName: String, totalBytes: Long) {
        val host = MirrorLan.localIpv4()
        if (host == null) {
            Log.i(TAG, "lan serve impossible: нет локального адреса")
            return
        }
        val sender = MirrorLan.Sender(transferId, totalBytes) { offset, size ->
            bridge.fileBytesFor(transferId, displayName, offset, size)
        }
        val port = sender.start() ?: run {
            Log.w(TAG, "lan serve: сервер не поднялся")
            return
        }
        Log.i(TAG, "lan serve on $host:$port (${totalBytes} Б, id=${transferId.take(8)})")
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sendEvent(
                "lan",
                JSONObject()
                    .put("id", transferId)
                    .put("host", host)
                    .put("port", port)
                    .put("tok", sender.token)
                    .put("size", totalBytes),
            )
        }
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
            // р243: свой адрес нужен на каждом сообщении и на каждом нажатии
            // клавиши, но не должен стоить вызова в ядро там - держим кэш.
            MirrorHub.noteSelfNodeId(nodeId)
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
            "filemeta" -> {
                // р230: файл принят на партнёрском устройстве - показать его
                // и здесь и попросить байты.
                val meta = openPayload(wire) ?: return
                scope.launch {
                    val applied = MirrorHub.duringApply { bridge.onFileMeta(meta) }
                    if (applied) {
                        requestFileBytes(
                            transferId = meta.optString("id"),
                            displayName = meta.optString("name"),
                            totalBytes = meta.optLong("size", 0L),
                        )
                    }
                }
            }
            "reqfile" -> {
                // р230: тень просит байты - отдаём порциями (канал общий).
                if (!engineUp) return
                val body = openPayload(wire) ?: return
                val transferId = body.optString("id")
                if (transferId.isBlank()) return
                serveFile(transferId, body.optString("name"), body.optLong("size", 0L))
            }
            "filechunk" -> {
                // р230: порция файла от партнёра - в приёмник.
                val body = openPayload(wire) ?: return
                val transferId = body.optString("id")
                val b64 = body.optString("b64")
                if (transferId.isBlank() || b64.isBlank()) return
                val bytes = runCatching {
                    android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                }.getOrNull() ?: return
                scope.launch {
                    MirrorHub.duringApply {
                        bridge.onFileChunk(transferId, body.optInt("seq", 0), body.optInt("last", 0) == 1, bytes)
                    }
                }
            }
            "reqgif" -> {
                // р245: тень просит байты гифки - отдаём, если они у нас есть.
                if (!engineUp) return
                val body = openPayload(wire) ?: return
                val sha = body.optString("sha")
                if (sha.isBlank()) return
                serveGif(sha)
            }
            "gifchunk" -> {
                // р245: порция гифки от партнёра - в библиотеку.
                val body = openPayload(wire) ?: return
                val sha = body.optString("sha")
                val b64 = body.optString("b64")
                if (sha.isBlank() || b64.isBlank()) return
                val bytes = runCatching {
                    android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                }.getOrNull() ?: return
                scope.launch {
                    if (body.optInt("seq", 0) == 0) Log.i(TAG, "gif mirror: порции гифки " + sha.take(8))
                    MirrorHub.duringApply {
                        applyGifChunk(sha, body.optInt("last", 0) == 1, bytes)
                    }
                }
            }
            "lan" -> {
                // р232: большой файл отдают напрямую в локальной сети - забрать
                // самим и сложить в тот же приёмник, что и зеркальные порции.
                val body = openPayload(wire) ?: return
                val transferId = body.optString("id")
                val host = body.optString("host")
                val port = body.optInt("port", 0)
                val token = body.optString("tok")
                val totalBytes = body.optLong("size", 0L)
                if (transferId.isBlank() || host.isBlank() || port <= 0 || token.isBlank()) return
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    // Недокачанные куски забываем: файл придёт целиком.
                    runCatching { bridge.onFilePullStart(transferId) }
                    val pulled = MirrorLan.pull(host, port, token, totalBytes) { seq, last, bytes ->
                        MirrorHub.duringApply {
                            bridge.onFileChunk(transferId, seq, last, bytes)
                        }
                    }
                    Log.i(TAG, "lan pull id=${transferId.take(8)} ok=$pulled")
                }
            }
            "call" -> {
                // р233: звонковый сигнал партнёра - показать входящий и здесь,
                // снять его при ответе/завершении на той стороне.
                val body = openPayload(wire) ?: return
                scope.launch { bridge.onCallFromPartner(body) }
            }
            "contact" -> {
                // р234: контакт добавлен/переименован/удалён на партнёре -
                // привести свою азбуку адресов к тому же виду.
                val body = openPayload(wire) ?: return
                scope.launch {
                    MirrorHub.duringApply { bridge.onContactFromPartner(body) }
                }
            }
            "typing" -> {
                // р235: печатает собеседник - показать и здесь (на оба
                // устройства личности индикатор приходит одинаково).
                val body = openPayload(wire) ?: return
                val peerId = body.optString("p")
                if (peerId.isBlank()) return
                scope.launch { bridge.onTypingFromPartner(peerId, body.optInt("on", 0) == 1) }
            }
            "pin" -> {
                // р238: закреп с партнёрского устройства - закрепить и здесь.
                val body = openPayload(wire) ?: return
                val id = body.optString("id")
                if (id.isBlank()) return
                scope.launch {
                    MirrorHub.duringApply {
                        bridge.onPinFromPartner(id, body.optInt("on", 0) == 1)
                    }
                }
            }
            "inbox_pin" -> {
                val body = openPayload(wire) ?: return
                val kind = body.optString("kind")
                val id = body.optString("id")
                if (kind.isBlank() || id.isBlank()) return
                scope.launch {
                    MirrorHub.duringApply {
                        bridge.onInboxPinFromPartner(
                            kind = kind,
                            itemId = id,
                            pinned = body.optInt("on", 0) == 1,
                            pinnedAtMs = body.optLong("at", 0L),
                        )
                    }
                }
            }
            "draft" -> {
                // р236: черновик с партнёрского устройства - положить под тот
                // же ключ (открытый чат подхватит текст сам).
                val body = openPayload(wire) ?: return
                val key = body.optString("k")
                if (key.isBlank()) return
                scope.launch {
                    MirrorHub.duringApply { bridge.onDraftFromPartner(key, body.optString("t")) }
                }
            }
            "claim" -> {
                // р231: партнёр-тень просит движок (отправка файла со второго
                // устройства). Отвечает только активный: уступит - тень сама
                // поднимется (её сторож видит партнёра без движка).
                if (!engineUp) return
                val body = openPayload(wire) ?: return
                if (body.optString("r") != "engine") return
                scope.launch {
                    val defer = runCatching { bridge.onEngineClaimed() }.getOrDefault(false)
                    Log.i(TAG, "claim: движок просят, уступаю=$defer")
                }
            }
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

        /** р230: порция файла (16 КиБ -> ~22 КиБ base64, влезает в кадр и в потолок конверта). */
        private const val FILE_CHUNK_BYTES = 16 * 1024

        /** р245: порция байтов гифки - та же логика, что у файловых порций. */
        private const val GIF_CHUNK_BYTES = 16 * 1024

        /** р245: как часто повторять просьбу о байтах одной гифки. */
        private const val GIF_REQUEST_COOLDOWN_MS = 90_000L

        /** р245: потолок гифки, которую гоним по зеркальному каналу. */
        private const val GIF_MIRROR_MAX_BYTES = 8L * 1024 * 1024

        /** р230: пауза между порциями - канал зеркала общий с сообщениями. */
        private const val FILE_CHUNK_PAUSE_MS = 60L

        /** р230: больше этого файл через зеркало не гоняем (он остаётся на принявшем устройстве). */
        const val FILE_MIRROR_MAX_BYTES = 24L * 1024 * 1024

        /** р231: не чаще раза в минуту просим передачу роли (это перезапуск на обоих). */
        private const val CLAIM_COOLDOWN_MS = 60_000L

        /** р236: черновик длиннее этого в кадр зеркала не кладём (кадр - 512 КиБ). */
        private const val DRAFT_MAX_CHARS = 8_000
    }
}
