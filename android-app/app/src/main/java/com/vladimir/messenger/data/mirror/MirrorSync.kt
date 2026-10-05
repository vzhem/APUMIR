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

    /**
     * р230: попросить байты файла у партнёра (тень просит активного).
     *
     * @param offset с какого места просить: после обрыва канал продолжает
     *        с недоскачанного места, а не сначала.
     * @param big файл больше обычного зеркального потолка: его согласен отдать
     *        только явный запрос (человек открыл файл на втором устройстве
     *        или прямой локальный канал не вышел).
     */
    fun requestFileBytes(
        transferId: String,
        displayName: String,
        totalBytes: Long,
        offset: Long = 0L,
        big: Boolean = false,
    ) {
        runCatching { channel?.requestFileBytes(transferId, displayName, totalBytes, offset, big) }
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

    /**
     * р249: архив и «без звука» - партнёрскому устройству.
     *
     * Флаги живут в базе каждого устройства, поэтому без кадра телефоны
     * расходились: убрал чат в архив здесь - на втором он остался в списке.
     * Ключ переписки устойчивый (узел собеседника или идентификатор группы),
     * поэтому кадр одинаково понимают оба устройства.
     */
    fun publishChatFlags(kind: String, itemId: String, archived: Boolean, mutedUntilMs: Long) {
        if (kind.isBlank() || itemId.isBlank()) return
        if (isApplyingFrame()) return
        runCatching { channel?.publishChatFlags(kind, itemId, archived, mutedUntilMs) }
    }

    /**
     * р249: удаление сообщения «у меня» - партнёрскому устройству.
     *
     * Раньше «удалить у себя» жило только на том телефоне, где его сделали:
     * строка исчезала здесь и оставалась на втором - устройства расходились
     * именно в этом месте. Собеседнику такое удаление не уходит (это личное
     * дело владельца переписки), поэтому кадра с устойчивым ключом чата и
     * идентификатором сообщения достаточно.
     */
    fun publishDeleteForMe(peerId: String, groupId: String, messageId: String) {
        if (messageId.isBlank()) return
        if (peerId.isBlank() && groupId.isBlank()) return
        if (isApplyingFrame()) return
        runCatching { channel?.publishDeleteForMe(peerId, groupId, messageId) }
    }

    /**
     * р246: снимок профиля (имя, ник, тема, аватар, обои) - партнёрскому
     * устройству. Живая синхронизация профиля: изменил на одном телефоне -
     * второй подтянет сам, без полной копии и перезапуска.
     */
    fun publishProfileState(json: String) {
        runCatching { channel?.publishProfileState(json) }
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
        /**
         * р249: прямой (LAN) приём не удался - устройства в разных сетях.
         * Мост просит тот же файл по зеркальному каналу и запоминает, что
         * человек его ждёт (большой файл автоматом не качаем).
         */
        suspend fun onLanPullFailed(transferId: String)
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
        /**
         * р249: на партнёрском устройстве удалили сообщение «у меня» - стереть
         * ту же строку и здесь (собеседнику удаление не уходит).
         */
        suspend fun onDeleteForMeFromPartner(peerId: String, groupId: String, messageId: String)
        /**
         * р249: на партнёрском устройстве изменили архив или звук переписки.
         *
         * @param kind «p» - личный чат (ключ - узел собеседника), «g» - группа
         *             или канал (ключ - идентификатор группы).
         */
        suspend fun onChatFlagsFromPartner(
            kind: String,
            itemId: String,
            archived: Boolean,
            mutedUntilMs: Long,
        )
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

    /**
     * р249: общая (старая) полка - по открытому адресу личности. В ней живут
     * устройства без маркера, и в неё же мы заходим на разведку, если потеряли
     * партнёра (см. [maybeProbeLegacy]).
     */
    private val legacyShelf = MirrorSync.shelf(nodeId)

    /** р249: маркер комнаты. Есть - сидим в защищённой полке, нет - в общей. */
    @Volatile private var token: MirrorRoomToken.RoomToken? = MirrorRoomToken.load(context)

    /** р249: до этого времени сидим в ОБЩЕЙ комнате, даже имея маркер. */
    @Volatile private var holdLegacyUntil = 0L

    /** р249: полка, в которой открыто текущее соединение. */
    @Volatile private var currentShelf = ""

    /** р249: когда последняя разведка в общей комнате закончилась. */
    @Volatile private var lastProbeEndAt = 0L

    /** р249: когда в последний раз видели партнёра (в любой комнате). */
    @Volatile private var lastPartnerAt = 0L

    /** р249: печать полки партнёра из его hello/hb. */
    @Volatile private var partnerStamp = ""

    /**
     * р249: умеет ли партнёр договариваться о комнате (0 - старая сборка).
     * Пока партнёр старый, маркер ему не отдаём и в защищённую комнату не
     * уходим: иначе он останется в одиночестве, а зеркало встанет.
     */
    @Volatile private var partnerRoomV = 0

    /** р249: свои запросы-числа, на которые ждём ответа партнёра. */
    private val authNonces = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** р249: как часто повторять обмен маркером. */
    @Volatile private var lastAuthAt = 0L

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

    /** р249: когда в последний раз досылали накопленные исходящие тени. */
    @Volatile private var lastPendingFlushAt = 0L

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
            // р249: чем живёт комната - защищённая она или общая. Сам маркер
            // не показываем: по нему входят в комнату.
            append("\nкомната зеркала: ")
                .append(if (inSecureRoom()) "защищённая (по маркеру)" else "общая (старая)")
            if (partnerStamp.isNotBlank()) {
                append(", печать партнёра: ").append(partnerStamp)
            }
            // р246: совпадает ли профиль с партнёрским устройством; если нет -
            // какие поля расходятся (владелец просил видеть расхождения онлайн).
            append("\n").append(ProfileMirror.diagLine(context))
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

    /**
     * р249: архив и «без звука» - партнёрскому устройству. Кадр крошечный и
     * идемпотентный: повторная отправка того же состояния ничего не ломает.
     */
    fun publishChatFlags(kind: String, itemId: String, archived: Boolean, mutedUntilMs: Long) {
        if (wsRef.get() == null) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sealed = sealPayload(
                JSONObject()
                    .put("k", kind)
                    .put("id", itemId)
                    .put("a", if (archived) 1 else 0)
                    .put("m", mutedUntilMs),
            ) ?: return@launch
            sendJson(JSONObject().put("t", "ev").put("k", "chatflag").put("d", sealed))
        }
    }

    /**
     * р249: удаление сообщения «у меня» - партнёрскому устройству.
     *
     * Ключ переписки устойчивый (узел собеседника или идентификатор группы),
     * поэтому партнёр найдёт у себя ту же строку по идентификатору сообщения,
     * даже если номер его чата другой.
     */
    fun publishDeleteForMe(peerId: String, groupId: String, messageId: String) {
        if (wsRef.get() == null) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sealed = sealPayload(
                JSONObject()
                    .put("p", peerId)
                    .put("g", groupId)
                    .put("id", messageId),
            ) ?: return@launch
            sendJson(JSONObject().put("t", "ev").put("k", "delf").put("d", sealed))
        }
    }

    /**
     * р246: снимок профиля - партнёрскому устройству. Запечатан на самого
     * себя, как остальные кадры зеркала.
     */
    fun publishProfileState(json: String) {
        if (wsRef.get() == null) return
        val body = runCatching { JSONObject(json) }.getOrNull() ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sealed = sealPayload(body) ?: return@launch
            sendJson(JSONObject().put("t", "ev").put("k", "profstate").put("d", sealed))
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
        // р246: живой профиль - привести картинки к локальным файлам и сразу
        // показать себя партнёру, чтобы расхождения нашлись сами.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            ProfileMirror.publishNow(context)
        }
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

    /**
     * р230: тень просит у активного байты принятого файла.
     *
     * р249: просьба уходит в фоне - запечатывание кадра это вызов в ядро и
     * AES, а зовут отсюда и по нажатию «открыть файл» на втором устройстве.
     */
    fun requestFileBytes(
        transferId: String,
        displayName: String,
        totalBytes: Long,
        offset: Long = 0L,
        big: Boolean = false,
    ): Boolean {
        if (engineUp) return false
        if (!canCarryOutgoing()) return false
        if (transferId.isBlank()) return false
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val body = JSONObject()
                .put("id", transferId)
                .put("name", displayName)
                .put("size", totalBytes)
                .put("off", offset)
                .put("big", if (big) 1 else 0)
            val sealed = sealPayload(body) ?: return@launch
            sendJson(JSONObject().put("t", "ev").put("k", "reqfile").put("d", sealed))
        }
        return true
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
    private fun serveFile(
        transferId: String,
        displayName: String,
        totalBytes: Long,
        offset: Long = 0L,
        big: Boolean = false,
    ) {
        if (!engineUp) return
        if (totalBytes <= 0 || totalBytes > MirrorLan.MAX_BYTES) {
            Log.i(TAG, "file mirror skipped: $totalBytes B (> ${MirrorLan.MAX_BYTES})")
            return
        }
        if (!big && totalBytes > FILE_MIRROR_MAX_BYTES) {
            // р232: большой файл через зеркальный канал не гоняем - отдаём
            // напрямую по локальной сети (если партнёр рядом, он заберёт сам).
            startLanServe(transferId, displayName, totalBytes)
            return
        }
        if (big && totalBytes > FILE_MIRROR_BIG_MAX_BYTES) {
            // р249: предел есть и у явной просьбы: гигабайт гнать через воркер
            // нельзя - это часы трафика и место на приёмнике. Такой файл живёт
            // там, где его приняли, и переносится копией аккаунта.
            Log.i(TAG, "file mirror skipped: $totalBytes B (> $FILE_MIRROR_BIG_MAX_BYTES, явная просьба)")
            return
        }
        val chunkBytes = if (big) BIG_CHUNK_BYTES else FILE_CHUNK_BYTES
        val pause = if (big) BIG_CHUNK_PAUSE_MS else FILE_CHUNK_PAUSE_MS
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var pos = offset.coerceAtLeast(0L)
            var seq = 0
            while (pos < totalBytes) {
                val want = minOf(chunkBytes.toLong(), totalBytes - pos).toInt()
                val chunk = runCatching {
                    bridge.fileBytesFor(transferId, displayName, pos, want)
                }.getOrNull()
                if (chunk == null || chunk.isEmpty()) {
                    Log.w(TAG, "file mirror: чтение не удалось на $pos")
                    return@launch
                }
                val isLast = pos + chunk.size >= totalBytes
                sendFileChunk(transferId, seq, isLast, chunk)
                seq++
                pos += chunk.size
                // Небольшая пауза: канал общий с сообщениями, не забиваем его.
                if (pause > 0L) kotlinx.coroutines.delay(pause)
            }
            Log.i(TAG, "file mirror: отдано ${pos - offset} Б ($seq порций) id=$transferId big=$big")
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

    // ── р249: комната зеркала (маркер, разведка, согласование) ────────────────

    /** Полка, в которой нам сейчас место. */
    private fun shelfNow(now: Long = System.currentTimeMillis()): String {
        val tok = token ?: return legacyShelf
        return if (now < holdLegacyUntil) legacyShelf else MirrorRoomToken.shelf(tok.value)
    }

    /** Сидим ли мы сейчас в защищённой (маркерной) комнате. */
    private fun inSecureRoom(now: Long = System.currentTimeMillis()): Boolean =
        token != null && shelfNow(now) != legacyShelf

    /** Закрыть соединение и открыть его уже в нужной комнате. */
    private fun reconnect(reason: String) {
        Log.i(TAG, "комната зеркала: $reason")
        // Ссылку обнуляем ДО закрытия: тогда отложенный onClosed старого
        // сокета не погасит новое соединение (сравнение по ссылке в onClosed).
        val stale = wsRef.getAndSet(null)
        runCatching { stale?.close(1000, "room") }
        connect()
    }

    /**
     * р249: разведка в общей комнате.
     *
     * Партнёр мог остаться без маркера (например, телефон восстановили из
     * старой копии) либо получить другой маркер - тогда мы с ним в РАЗНЫХ
     * комнатах и не увидим друг друга никогда. Поэтому изредка заходим в
     * старую общую полку и смотрим, не стоит ли там наш партнёр.
     *
     * Ходим туда только если второе устройство вообще есть (партнёра видели
     * на этой неделе) и только когда его давно не видно. У одного устройства
     * в сети разведка не включается вовсе.
     */
    private fun maybeProbeLegacy(now: Long) {
        if (!inSecureRoom(now)) return
        if (partnerDev != null) return
        if (now - lastProbeEndAt < PROBE_COOLDOWN_MS) return
        if (now - lastPartnerAt > PARTNER_MEMORY_MS) return
        holdLegacyUntil = now + PROBE_MS
        reconnect("партнёра давно не видно - разведка в общей комнате")
    }

    /** р249: полка изменилась (вышла разведка или появился маркер) - перейти. */
    private fun maybeSwitchRoom(now: Long) {
        val want = shelfNow(now)
        if (want == currentShelf) return
        reconnect(if (want == legacyShelf) "ухожу в общую комнату" else "возвращаюсь в защищённую комнату")
        if (want != legacyShelf) lastProbeEndAt = now
    }

    /**
     * р249: старый партнёр не умеет договариваться о комнате - остаёмся с ним
     * в общей полке, иначе он оглохнет, а зеркало встанет.
     *
     * Держим общую комнату, пока такой партнёр рядом: кадры он понимает,
     * просто не знает про маркеры. Обновится - сам скажет версию, и тогда
     * маркеры согласуем.
     */
    private fun keepLegacyForOldPartner() {
        if (partnerRoomV >= ROOM_PROTOCOL_V) return
        if (token == null) return
        val now = System.currentTimeMillis()
        val hold = now + OLD_PARTNER_HOLD_MS
        if (hold > holdLegacyUntil) holdLegacyUntil = hold
    }

    /**
     * р249: согласовать маркер комнаты с партнёром.
     *
     * В защищённой комнате согласовывать нечего: попасть в неё можно, только
     * зная маркер, а значит, он у обоих один. Вся работа идёт в общей комнате:
     * там мы либо создаём маркер (его сделает ведущий), либо отдаём свой,
     * либо принимаем более старый маркер партнёра.
     */
    private fun maybeRoomHandshake(now: Long) {
        if (inSecureRoom(now)) return
        if (partnerDev == null) return
        if (!partnerFresh()) return
        // Старая сборка: маркер она не поймёт, а мы без неё не уходим.
        if (partnerRoomV < ROOM_PROTOCOL_V) return
        val mine = MirrorRoomToken.stamp(token?.value)
        // Пустая печать у обоих - это не «договорились», а «маркера нет ни у
        // кого»: как раз тот случай, когда его надо завести.
        if (mine.isNotBlank() && mine == partnerStamp) {
            // Маркеры совпали: партнёр уже знает наш, можно уходить в защищённую.
            if (holdLegacyUntil > now) holdLegacyUntil = 0L
            return
        }
        if (now - lastAuthAt < AUTH_COOLDOWN_MS) return
        lastAuthAt = now
        // Ни у кого маркера нет - заводит его ведущий: правило обязано дать
        // ровно одного создателя, иначе устройства разойдутся по комнатам.
        if (token == null && partnerStamp.isBlank() && iAmDesignated()) {
            val created = MirrorRoomToken.create(context)
            if (created != null) {
                token = created
                holdLegacyUntil = now + HOLD_AFTER_CREATE_MS
                Log.i(TAG, "маркер комнаты создан - отдам партнёру")
            }
        }
        val nonce = freshNonce()
        authNonces[nonce] = now
        if (authNonces.size > 32) {
            val oldest = authNonces.entries.sortedBy { it.value }.take(8).map { it.key }
            oldest.forEach { authNonces.remove(it) }
        }
        sendEvent("authq", JSONObject().put("n", nonce))
    }

    /** р249: кто, если что, заводит маркер: ведущий, а при равенстве - меньший тег. */
    private fun iAmDesignated(): Boolean {
        val dev = partnerDev ?: return false
        if (engineUp && !partnerEng) return true
        if (!engineUp && partnerEng) return false
        return deviceTag < dev
    }

    /**
     * р249: партнёр спрашивает число - отвечаем и отдаём свой маркер.
     *
     * Ответ внутри запечатанного кадра: прочитать число мог только тот, у кого
     * есть закрытый ключ этой личности, то есть своё устройство. Поэтому
     * маркер безопасно класть в тот же ответ - посторонний его не увидит.
     */
    private fun onAuthQuery(body: JSONObject) {
        val nonce = body.optString("n")
        if (nonce.isBlank()) return
        val mine = token
        val ok = sendEvent(
            "authp",
            JSONObject()
                .put("n", nonce)
                .put("r", sha256Hex("apu-mirror-auth-v1|" + nonce))
                .put("rt", mine?.value ?: "")
                .put("rta", mine?.createdAtMs ?: 0L),
        )
        // Отдали маркер - даём партнёру время перейти в защищённую комнату.
        if (ok && mine != null) {
            holdLegacyUntil = System.currentTimeMillis() + HOLD_AFTER_OFFER_MS
        }
    }

    /** р249: ответ партнёра на наше число - проверить и, может, принять его маркер. */
    private fun onAuthProof(body: JSONObject) {
        val nonce = body.optString("n")
        val issuedAt = authNonces.remove(nonce) ?: return
        if (System.currentTimeMillis() - issuedAt > AUTH_TTL_MS) return
        if (body.optString("r") != sha256Hex("apu-mirror-auth-v1|" + nonce)) {
            Log.w(TAG, "комната зеркала: ответ не на моё число - игнорирую")
            return
        }
        val value = body.optString("rt", "")
        if (value.isBlank()) return
        val at = body.optLong("rta", 0L)
        val theirs = MirrorRoomToken.RoomToken(value, if (at > 0L) at else System.currentTimeMillis())
        if (!MirrorRoomToken.wins(theirs, token)) return
        if (MirrorRoomToken.save(context, theirs)) {
            token = theirs
            holdLegacyUntil = 0L
            Log.i(TAG, "комната зеркала: принял маркер партнёра")
        }
    }

    /** р249: случайное число для проверки партнёра (SecureRandom потокобезопасен). */
    private val random = java.security.SecureRandom()

    private fun freshNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    // ── Транспорт ────────────────────────────────────────────────────────────

    private fun connect() {
        if (closedByUs) return
        try {
            val room = shelfNow()
            val previous = currentShelf
            val request = Request.Builder()
                .url("wss://" + GroupInviteLinks.WEB_HOST + "/mirror/" + room + "?dev=" + deviceTag)
                .build()
            val ws = client.newWebSocket(request, this)
            wsRef.set(ws)
            // Полку запоминаем только когда соединение реально открылось:
            // иначе неудачная попытка перехода осталась бы незамеченной.
            currentShelf = room
            if (previous.isNotBlank() && previous != room) {
                Log.i(TAG, "комната зеркала: " + if (room == legacyShelf) "общая" else "защищённая")
            }
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

    // р246: в hello/hb идёт отпечаток профиля - устройства сами видят, что
    // профиль разъехался, и запрашивают полный снимок (maybeRequestProfile).
    private fun hello(): JSONObject = baseFrame("hello")

    private fun heartbeat(): JSONObject = baseFrame("hb")

    // р249: печать полки едет в каждом hello/hb - по ней партнёр видит, что
    // маркеры разъехались и комнату надо согласовывать.
    private fun baseFrame(kind: String): JSONObject = JSONObject()
        .put("t", kind)
        .put("dev", deviceTag)
        .put("eng", if (engineUp) 1 else 0)
        .put("since", if (engineUp) engineSince else 0L)
        .put("max", myMaxTs)
        .put("sk", MirrorRoomToken.stamp(token?.value))
        .put("mkv", ROOM_PROTOCOL_V)
        .put("pd", ProfileMirror.cachedDigest(context))
        .put("pd", ProfileMirror.cachedDigest(context))

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
        // р249: метка комнаты внутри кадра. Запечатанный кадр может вскрыть
        // только устройство этой личности, но ЗАПЕЧАТАТЬ на наш открытый ключ
        // умеет кто угодно - метка и отличает своих от чужих.
        val mark = MirrorRoomToken.frameMark(token?.value)
        if (mark.isNotBlank()) runCatching { payload.put("mk", mark) }
        return MessageSealer.seal(context, nodeId, payload.toString())
    }

    /**
     * р249: вскрыть кадр партнёра и проверить метку комнаты.
     *
     * Метку требуем только в защищённой комнате: там маркер по построению
     * знают оба. В общей комнате метки нет ни у кого (это старый порядок), и
     * требовать её - значит оглохнуть к партнёру, который ещё не обновился.
     */
    private fun openPayload(wire: String): JSONObject? {
        val payload = MessageSealer.open(context, wire)?.let { JSONObject(it) } ?: return null
        val mark = MirrorRoomToken.frameMark(token?.value)
        if (mark.isNotBlank() && inSecureRoom() && payload.optString("mk") != mark) {
            Log.w(TAG, "кадр без метки комнаты - чужой, отбрасываю")
            return null
        }
        return payload
    }

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
                // р249: партнёр пропал - про его комнату тоже забываем.
                partnerStamp = ""
                partnerRoomV = 0
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
            // р249: комната зеркала - защищённая по маркеру или общая.
            maybeProbeLegacy(now)
            maybeSwitchRoom(now)
            maybeRoomHandshake(now)
            delay(2_500L)
        }
    }

    private fun onPartnerFrame(obj: JSONObject) {
        val dev = obj.optString("dev")
        if (dev.isBlank() || dev == deviceTag) return
        // р249: партнёра только что не было видно - запоминаем, чтобы тень
        // дослала накопленные исходящие (см. flushPendingOutgoing).
        val partnerAppeared = !partnerFresh()
        partnerDev = dev
        partnerEng = obj.optInt("eng") == 1
        // р249: печать полки партнёра и сам факт встречи (по нему решаем, есть
        // ли вообще второе устройство - без него разведка не нужна).
        partnerStamp = obj.optString("sk", "")
        partnerRoomV = obj.optInt("mkv", 0)
        lastPartnerAt = System.currentTimeMillis()
        keepLegacyForOldPartner()
        partnerSince = obj.optLong("since", 0L).takeIf { it > 0 } ?: Long.MAX_VALUE
        val max = obj.optLong("max", 0L)
        val becameFreshMax = max > partnerMaxTs
        partnerMaxTs = max
        partnerLastSeen = System.currentTimeMillis()
        maybeCatchup(force = becameFreshMax)
        // р249: партнёр объявился - у тени могли копиться исходящие, которым
        // некому было уйти (писали при отсутствующем активном).
        if (partnerAppeared) flushPendingOutgoing()
        // р246: отпечаток профиля партнёра отличается от нашего - запросить
        // полный снимок и слиться (онлайн-поиск расхождений профиля).
        ProfileMirror.notePartnerDigest(obj.optString("pd", ""))
        maybeRequestProfile()
    }

    /** р246: расхождение отпечатков профиля - запросить снимок (не чаще 10 с). */
    @Volatile private var lastProfReqAt = 0L

    private fun maybeRequestProfile() {
        val pd = ProfileMirror.partnerDigest
        if (pd.isBlank()) return
        if (pd == ProfileMirror.cachedDigest(context)) return
        val now = System.currentTimeMillis()
        if (now - lastProfReqAt < 10_000L) return
        lastProfReqAt = now
        sendJson(JSONObject().put("t", "reqprof").put("dev", deviceTag))
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
            // р249: согласование комнаты - до всяких сообщений.
            "authq" -> onAuthQuery(openPayload(wire) ?: return)
            "authp" -> onAuthProof(openPayload(wire) ?: return)
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
                // р249: со смещением (продолжение после обрыва) и с явным
                // согласием на большой файл.
                if (!engineUp) return
                val body = openPayload(wire) ?: return
                val transferId = body.optString("id")
                if (transferId.isBlank()) return
                serveFile(
                    transferId,
                    body.optString("name"),
                    body.optLong("size", 0L),
                    offset = body.optLong("off", 0L),
                    big = body.optInt("big", 0) == 1,
                )
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
                    if (!pulled) {
                        // р249: устройства в РАЗНЫХ сетях - прямой канал не
                        // вышел, и файл остался только на активном. Раньше на
                        // этом всё и кончалось: на втором устройстве навсегда
                        // оставалась пустая карточка. Теперь просим тот же
                        // файл по зеркальному каналу, порциями; просьбу и
                        // учёт ведёт мост (там же счётчик для возобновления).
                        runCatching { bridge.onLanPullFailed(transferId) }
                        Log.i(TAG, "lan pull failed -> прошу файл зеркалом id=${transferId.take(8)}")
                    }
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
            "chatflag" -> {
                // р249: архив/звук изменились на партнёре - привести свою базу
                // к тому же виду. Кадр про личное состояние владельца, в сеть
                // не уходит; повторной рассылки нет.
                val body = openPayload(wire) ?: return
                val id = body.optString("id")
                if (id.isBlank()) return
                scope.launch {
                    MirrorHub.duringApply {
                        bridge.onChatFlagsFromPartner(
                            kind = body.optString("k"),
                            itemId = id,
                            archived = body.optInt("a", 0) == 1,
                            mutedUntilMs = body.optLong("m", 0L),
                        )
                    }
                }
            }
            "delf" -> {
                // р249: на партнёре удалили сообщение «у меня» - стереть ту же
                // строку и здесь. Собственное удаление партнёра, поэтому
                // повторной рассылки нет (кадр не проходит через сеть).
                val body = openPayload(wire) ?: return
                val messageId = body.optString("id")
                if (messageId.isBlank()) return
                scope.launch {
                    MirrorHub.duringApply {
                        bridge.onDeleteForMeFromPartner(
                            peerId = body.optString("p"),
                            groupId = body.optString("g"),
                            messageId = messageId,
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
            "profstate" -> {
                // р246: снимок профиля партнёра - слить поля; если аватар или
                // обои победили, но байтов ещё нет - попросить их.
                val body = openPayload(wire) ?: return
                val incoming = ProfileMirror.State.fromJson(body)
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val need = ProfileMirror.onPartnerState(context, incoming)
                    need.forEach { kind ->
                        val name = if (kind == ProfileMirror.FIELD_AV) {
                            incoming.avName
                        } else {
                            incoming.wallName
                        }
                        val sealed = sealPayload(
                            JSONObject().put("k", kind).put("n", name)
                        ) ?: return@forEach
                        sendJson(
                            JSONObject().put("t", "ev").put("k", "reqpbytes").put("d", sealed)
                        )
                    }
                }
            }
            "reqprof" -> {
                // р246: партнёр увидел расхождение отпечатков - отдать снимок.
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    ProfileMirror.publishNow(context)
                }
            }
            "reqpbytes" -> {
                // р246: партнёр просит байты аватара/обоев - отдать порциями.
                val body = openPayload(wire) ?: return
                val kind = body.optString("k")
                val name = body.optString("n")
                val file = ProfileMirror.imageFile(context, kind, name) ?: return
                serveProfileImage(kind, name, file)
            }
            "pbytes" -> {
                // р246: порция картинки профиля от партнёра - сложить и применить.
                val body = openPayload(wire) ?: return
                val kind = body.optString("k")
                val name = body.optString("n")
                val b64 = body.optString("b64")
                if (kind.isBlank() || name.isBlank() || b64.isBlank()) return
                val bytes = runCatching {
                    android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                }.getOrNull() ?: return
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    applyProfileChunk(kind, name, body.optInt("last", 0) == 1, bytes)
                }
            }
        }
    }

    // ── р246: байты картинок профиля (аватар/обои) ──────────────────────────

    /** Недособранные картинки профиля от партнёра. */
    private val profIncoming =
        java.util.concurrent.ConcurrentHashMap<String, java.io.ByteArrayOutputStream>()

    private fun applyProfileChunk(kind: String, name: String, last: Boolean, bytes: ByteArray) {
        val key = kind + "|" + name
        val buf = profIncoming.getOrPut(key) { java.io.ByteArrayOutputStream() }
        if (buf.size() + bytes.size > ProfileMirror.IMAGE_MAX_BYTES) {
            profIncoming.remove(key)
            Log.w(TAG, "profile bytes: слишком много $key, бросаю")
            return
        }
        buf.write(bytes)
        if (!last) return
        profIncoming.remove(key)
        val all = buf.toByteArray()
        if (all.isEmpty()) return
        ProfileMirror.storeRemoteImage(context, kind, name, all)
    }

    /** Отдать картинку профиля порциями (те же порции и пауза, что у гифок). */
    private fun serveProfileImage(kind: String, name: String, file: java.io.File) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) return@launch
            var offset = 0
            var seq = 0
            while (offset < bytes.size) {
                val end = minOf(offset + FILE_CHUNK_BYTES, bytes.size)
                val body = JSONObject()
                    .put("k", kind)
                    .put("n", name)
                    .put("seq", seq)
                    .put("last", if (end >= bytes.size) 1 else 0)
                    .put("b64", android.util.Base64.encodeToString(
                        bytes.copyOfRange(offset, end), android.util.Base64.NO_WRAP))
                val sealed = sealPayload(body) ?: return@launch
                sendJson(JSONObject().put("t", "ev").put("k", "pbytes").put("d", sealed))
                seq++
                offset = end
                kotlinx.coroutines.delay(FILE_CHUNK_PAUSE_MS)
            }
            Log.i(TAG, "profile bytes: отдано ${bytes.size} Б ($seq порций) $kind/$name")
        }
    }

    // ── WebSocketListener ────────────────────────────────────────────────────

    override fun onOpen(webSocket: WebSocket, response: Response) {
        backoffMs = 2_000L
        sendJson(hello())
        // р246: канал ожил - сразу показать партнёру свой профиль.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            ProfileMirror.publishNow(context)
        }
        // Тень: невостребованные исходящие (ушли, пока партнёра не было).
        flushPendingOutgoing()
    }

    /**
     * р249: дослать исходящие, которые тень накопила без партнёра.
     *
     * Раньше это делалось только в момент открытия соединения: если WS уже
     * был жив, а активный появился позже (или перезапустился), строки так и
     * висели `PENDING` до следующего переподключения - владелец видел
     * «висит одна галочка» без всякой причины. Теперь догон зовётся и отсюда,
     * и из появления партнёра; повторы режет пауза (иначе одна и та же строка
     * ушла бы собеседнику дважды: она остаётся PENDING до подтверждения).
     */
    private fun flushPendingOutgoing() {
        if (engineUp) return
        if (!partnerEng) return
        if (wsRef.get() == null) return
        val now = System.currentTimeMillis()
        if (now - lastPendingFlushAt < PENDING_FLUSH_COOLDOWN_MS) return
        lastPendingFlushAt = now
        scope.launch {
            runCatching {
                bridge.pendingOutgoing(PENDING_FLUSH_ROWS).forEach { row ->
                    publishOutgoing(row)
                }
            }.onFailure { Log.w(TAG, "pending flush failed: ${it.message}") }
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

    // р249: закрываем ТОЛЬКО своё соединение. Если мы уже ушли в другую
    // комнату, старый сокет не должен обнулять ссылку на новый.
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (wsRef.compareAndSet(webSocket, null)) scheduleReconnect()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (wsRef.compareAndSet(webSocket, null)) scheduleReconnect()
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

        /**
         * р249: порция большого файла (64 КиБ -> ~88 КиБ base64 - влезает в
         * кадр воркера 512 КиБ с запасом). Обычные 16 КиБ на гигабайтных
         * объёмах дают десятки тысяч кадров.
         */
        private const val BIG_CHUNK_BYTES = 64 * 1024

        /** р249: большому файлу пауза не нужна - он и так идёт не первым. */
        private const val BIG_CHUNK_PAUSE_MS = 10L

        /**
         * р249: предел файла, который уедет на второе устройство ПО ТРЕБОВАНИЮ
         * (человек открыл файл или прямой локальный канал не вышел). Автоматом
         * гоним только до [FILE_MIRROR_MAX_BYTES], чтобы не жечь трафик без
         * спроса.
         */
        const val FILE_MIRROR_BIG_MAX_BYTES = 256L * 1024 * 1024

        /** р230: больше этого файл через зеркало не гоняем (он остаётся на принявшем устройстве). */
        const val FILE_MIRROR_MAX_BYTES = 24L * 1024 * 1024

        /** р231: не чаще раза в минуту просим передачу роли (это перезапуск на обоих). */
        private const val CLAIM_COOLDOWN_MS = 60_000L

        /** р236: черновик длиннее этого в кадр зеркала не кладём (кадр - 512 КиБ). */
        private const val DRAFT_MAX_CHARS = 8_000

        /** р249: сколько накопленных строк тень досылает за один заход. */
        private const val PENDING_FLUSH_ROWS = 50

        /** р249: как часто повторять досылку накопленных исходящих. */
        private const val PENDING_FLUSH_COOLDOWN_MS = 20_000L

        /** р249: сколько сидим в общей комнате, разыскивая потерянного партнёра. */
        private const val PROBE_MS = 90_000L

        /** р249: как часто повторять разведку (комната не резиновая). */
        private const val PROBE_COOLDOWN_MS = 15 * 60_000L

        /**
         * р249: пока партнёра не видели дольше этого, разведка не нужна -
         * значит, второго устройства у человека просто нет.
         */
        private const val PARTNER_MEMORY_MS = 7L * 24 * 60 * 60_000L

        /** р249: как часто повторять согласование маркера. */
        private const val AUTH_COOLDOWN_MS = 30_000L

        /** р249: сколько живёт число-запрос, на которое ждём ответ. */
        private const val AUTH_TTL_MS = 60_000L

        /** р249: столько держим общую комнату, отдавая партнёру свой маркер. */
        private const val HOLD_AFTER_OFFER_MS = 20_000L

        /** р249: столько держим общую комнату после создания маркера. */
        private const val HOLD_AFTER_CREATE_MS = 120_000L

        /**
         * р249: столько ещё сидим в общей комнате, если партнёр - старая
         * сборка без маркеров (пока он рядом, от него не уходим).
         */
        private const val OLD_PARTNER_HOLD_MS = 5 * 60_000L

        /**
         * р249: версия договора о комнате. Её же шлём в hello/hb: сборка без
         * этого поля договор не понимает, и мы остаёмся с ней в общей комнате.
         */
        const val ROOM_PROTOCOL_V = 1
    }
}
