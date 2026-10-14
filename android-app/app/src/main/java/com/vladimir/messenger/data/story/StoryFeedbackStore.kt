package com.vladimir.messenger.data.story

import android.content.Context
import android.util.AtomicFile
import com.vladimir.messenger.data.diagnostics.OperationalCounter
import com.vladimir.messenger.data.diagnostics.OperationalDiagnostics
import com.vladimir.messenger.data.diagnostics.TransferDiagnostics
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Отклики на истории: реакции и комментарии.
 *
 * Как устроено (владелец 2026-10-14: «на историю можно было ставить реакцию и
 * писать комментарии, всё как у топовых мессенджеров», лента откликов публичная -
 * её видит каждый, кто открыл историю).
 *
 * Сервера нет, поэтому узел-агрегатор один: АВТОР истории.
 *
 *  1. Зритель ставит значок или пишет комментарий - пакет уходит автору
 *     (виды `react` и `cmt` в [StoryFeedbackWire]).
 *  2. Автор принимает отклик, кладёт в книгу ([StoryFeedbackLedger]) и ставит в
 *     очередь рассылку сводки (`state`) остальным зрителям - не чаще раза в
 *     [StoryFeedbackOutbox.BROADCAST_MIN_INTERVAL_MS] на историю, чтобы пачка
 *     значков ушла одной волной.
 *  3. Зритель, который открыл историю без откликов (был офлайн, пропустил
 *     волну), просит сводку сам (`ask`), и автор отдаёт её ему одному (`state`).
 *
 * Почему пакеты идут префиксом истории: `APUSTORY1:` поглощается до записи в
 * переписку на всех путях приёма, поэтому служебный отклик не может лечь в чат
 * сообщением. Старая версия APU такой пакет молча отбросит (см. шапку
 * [StoryFeedbackWire]).
 *
 * Чего здесь нет намеренно:
 *  - подписей откликов: модель доверия та же, что у реакций на сообщения
 *    (`ReactionWire`) и просмотров постов - маленький служебный пакет без
 *    подписи. Авторитет отправителя задан правилом приёма: если пакет пришёл не
 *    от автора истории, «кто» всегда заменяется на фактического отправителя,
 *    поэтому подписать значок или комментарий чужим именем по сети нельзя;
 *  - зеркальной доставки между устройствами одной личности: истории между ними
 *    и так не ходят (`MirrorEnvelopes.isSafe` их не содержит), отклики - тоже.
 */
object StoryFeedbackStore {

    /** Снимок книги откликов: живёт сутки вместе с историей. */
    private const val FILE_NAME = "story-feedback-v1.json"

    /** Предел снимка на диске: отклики не должны разрастаться в мегабайты. */
    private const val MAX_SNAPSHOT_BYTES = 393_216

    /** Сколько историй держим в снимке: дальше вытесняются самые старые. */
    private const val MAX_PERSISTED_STORIES = 24

    /** Запас на расхождение часов (как у истории: 5 минут в будущее). */
    private const val FUTURE_MS = 300_000L

    private val lock = Any()
    private val ledger = StoryFeedbackLedger()
    private val outbox = StoryFeedbackOutbox()

    /** storyId -> когда последний раз просили сводку (защита от шквала просьб). */
    private val asks = HashMap<String, Long>()

    /** storyId -> версия книги на момент последней рассылки сводки. */
    private val sentVersions = HashMap<String, Long>()

    /** storyId -> время последней рассылки сводки. */
    private val lastBroadcast = HashMap<String, Long>()

    /** storyId -> когда появился первый ещё не разосланный отклик. */
    private val queuedSince = HashMap<String, Long>()

    /** storyId -> кому сводку слать не надо: они свой отклик уже применили. */
    private val echoActors = HashMap<String, MutableSet<String>>()

    private var appContext: Context? = null
    private var loaded = false
    private var snapshotStatus = "not_loaded"
    private val diskWriter = Executors.newSingleThreadExecutor()

    /** Версия книги: экран подписан на неё и перерисовывает отклики. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /**
     * Мой узел, каким его запомнил репозиторий. Экрану идентификатор нужен,
     * чтобы подсветить свой значок и свои комментарии, а вызывать ядро
     * (`RustBridge.nodeId()`) из отрисовки нельзя - это JNI на главном потоке.
     */
    private val _ownId = MutableStateFlow("")
    val ownId: StateFlow<String> = _ownId.asStateFlow()

    /** Что и кому дослать: один пакет для насоса. */
    data class Outgoing(
        /** Пустая строка у широковещательной сводки - список берёт отправитель. */
        val peer: String,
        val content: String,
        val transportId: String,
    )

    fun attach(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            ensureLoaded()
        }
    }

    fun isFeedback(content: String?): Boolean = StoryFeedbackWire.isFeedback(content)

    /** Сводка одной истории для экрана: значки, счётчики и лента комментариев. */
    fun summary(storyId: String): StoryFeedbackLedger.Summary = synchronized(lock) {
        ensureLoaded()
        if (storyId.isEmpty()) {
            StoryFeedbackLedger.EMPTY_SUMMARY
        } else {
            ledger.summary(storyId, _ownId.value.ifBlank { null })
        }
    }

    fun myEmoji(storyId: String): String? = synchronized(lock) {
        ensureLoaded()
        ledger.myEmoji(storyId, _ownId.value.ifBlank { null })
    }

    /** Запомнить свой узел: вызывается репозиторием, у которого ядро под рукой. */
    fun rememberOwnId(ownId: String) {
        if (ownId.isBlank()) return
        if (_ownId.value != ownId) _ownId.value = ownId
    }

    /**
     * Входящий конверт отклика. Возвращает true, если пакет наш и его обработали
     * (тогда в переписку ему дороги нет).
     *
     * [senderId] - фактический отправитель из транспорта. Подпись контакта из
     * азбуки получателя здесь намеренно не используется: имя отклика берётся из
     * пакета, то есть то, которым человек назвался сам. Иначе автор разослал бы
     * всем зрителям свои личные подписи («Мама», «Начальник»).
     */
    fun receive(senderId: String, content: String): Boolean {
        if (!StoryFeedbackWire.isFeedback(content)) return false
        val packet = StoryFeedbackWire.parse(content)
        if (packet == null) {
            count(OperationalCounter.STORY_FEEDBACK_INVALID)
            return true
        }
        if (senderId.isBlank()) return true
        val now = System.currentTimeMillis()
        if (packet.atMs > now + FUTURE_MS || packet.atMs <= 0L) {
            count(OperationalCounter.STORY_FEEDBACK_INVALID)
            return true
        }
        // Отклик на историю, которой у нас нет, не хранится: иначе любой узел
        // мог бы заполнить книгу чужими «историями» и их значками.
        val story = StoryStore.active(now).firstOrNull { it.id == packet.storyId }
        if (story == null) {
            count(OperationalCounter.STORY_FEEDBACK_IGNORED)
            return true
        }
        val fromAuthor = senderId == story.authorId
        var applied = false
        synchronized(lock) {
            ensureLoaded()
            when (packet) {
                is StoryFeedbackWire.Packet.React -> {
                    val actor = if (fromAuthor) packet.actorId else senderId
                    applied = ledger.applyReaction(
                        story.id,
                        StoryFeedbackWire.Reaction(
                            actorId = actor,
                            actorName = packet.actorName,
                            emoji = if (packet.added) packet.emoji else "",
                            atMs = packet.atMs,
                        ),
                    )
                    if (applied) afterIncomingChange(story, actor, now)
                }

                is StoryFeedbackWire.Packet.CommentPacket -> {
                    val actor = if (fromAuthor) packet.actorId else senderId
                    applied = ledger.applyComment(
                        story.id,
                        StoryFeedbackWire.Comment(
                            id = packet.commentId,
                            actorId = actor,
                            actorName = packet.actorName,
                            body = packet.body,
                            atMs = packet.atMs,
                        ),
                    )
                    if (applied) afterIncomingChange(story, actor, now)
                }

                is StoryFeedbackWire.Packet.Ask -> {
                    // Просьба имеет смысл только у автора: зрители сводку не раздают.
                    if (story.mine && senderId != story.authorId) {
                        outbox.enqueue(
                            kind = StoryFeedbackOutbox.Kind.UNICAST,
                            storyId = story.id,
                            nowMs = now,
                            peer = senderId,
                        )
                        applied = true
                        // Толкаем насос: зритель ждёт сводку сейчас, а не на
                        // плановом цикле службы через двадцать секунд.
                        bump()
                    }
                }

                is StoryFeedbackWire.Packet.State -> {
                    // Сводку принимает только зритель и только от автора истории:
                    // иначе любой узел мог бы подделать ленту чужих откликов.
                    if (fromAuthor && !story.mine) {
                        applied = ledger.applyState(story.id, packet.reactions, packet.comments) > 0
                        if (applied) {
                            queuedSince.remove(story.id)
                            bump()
                            persist()
                        }
                    }
                }
            }
        }
        count(if (applied) OperationalCounter.STORY_FEEDBACK_RECEIVED else OperationalCounter.STORY_FEEDBACK_IGNORED)
        return true
    }

    /**
     * Поставить или снять свой значок. Возвращает true - значок поставлен,
     * false - снят, null - действие не принято (пустой значок, нет ядра).
     *
     * Сначала запись ложится локально: человек видит свой значок сразу, даже без
     * сети, а досылка уходит очередью ([StoryFeedbackOutbox.Kind.REACT]).
     */
    fun toggleReaction(
        story: StoryStore.Story,
        emoji: String,
        ownId: String,
        ownName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean? {
        val clean = StoryFeedbackWire.sanitizeEmoji(emoji) ?: return null
        if (ownId.isBlank() || story.id.isEmpty()) return null
        var added = false
        synchronized(lock) {
            ensureLoaded()
            added = ledger.myEmoji(story.id, ownId) != clean
            val applied = ledger.applyReaction(
                story.id,
                StoryFeedbackWire.Reaction(
                    actorId = ownId,
                    actorName = StoryFeedbackWire.sanitizeName(ownName),
                    emoji = if (added) clean else "",
                    atMs = nowMs,
                ),
            )
            if (!applied) return null
            if (story.mine) {
                // Своя история: значок уедет зрителям вместе со сводкой.
                queueBroadcast(story.id, nowMs)
            } else {
                outbox.enqueue(
                    kind = StoryFeedbackOutbox.Kind.REACT,
                    storyId = story.id,
                    nowMs = nowMs,
                    peer = story.authorId,
                )
            }
            bump()
            persist()
        }
        return added
    }

    /**
     * Написать комментарий. Возвращает идентификатор комментария или null, если
     * текст пустой/не принят.
     */
    fun addComment(
        story: StoryStore.Story,
        body: String,
        ownId: String,
        ownName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val clean = StoryFeedbackWire.sanitizeComment(body) ?: return null
        if (ownId.isBlank() || story.id.isEmpty()) return null
        val commentId = UUID.randomUUID().toString()
        synchronized(lock) {
            ensureLoaded()
            val applied = ledger.applyComment(
                story.id,
                StoryFeedbackWire.Comment(
                    id = commentId,
                    actorId = ownId,
                    actorName = StoryFeedbackWire.sanitizeName(ownName),
                    body = clean,
                    atMs = nowMs,
                ),
            )
            if (!applied) return null
            if (story.mine) {
                queueBroadcast(story.id, nowMs)
            } else {
                outbox.enqueue(
                    kind = StoryFeedbackOutbox.Kind.COMMENT,
                    storyId = story.id,
                    nowMs = nowMs,
                    peer = story.authorId,
                    commentId = commentId,
                )
            }
            bump()
            persist()
        }
        return commentId
    }

    /**
     * Попросить сводку у автора, если у нас её ещё нет (зритель был офлайн или
     * открыл историю первым). Возвращает true, если просьба поставлена в очередь.
     */
    fun requestSummary(
        story: StoryStore.Story,
        ownId: String,
        ownName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        ensureLoaded()
        if (ownId.isBlank()) return@synchronized false
        val summary = ledger.summary(story.id, ownId.ifBlank { null })
        val ask = StoryFeedbackOutbox.shouldAsk(
            mine = story.mine,
            nowMs = nowMs,
            lastAskMs = asks[story.id] ?: 0L,
            lastChangeMs = ledger.lastChangeAt(story.id),
            hasAnyFeedback = !summary.isEmpty,
        )
        if (!ask) return@synchronized false
        asks[story.id] = nowMs
        outbox.enqueue(
            kind = StoryFeedbackOutbox.Kind.ASK,
            storyId = story.id,
            nowMs = nowMs,
            peer = story.authorId,
        )
        bump()
        true
    }

    /** Задачи, которые пора отправить. */
    fun dueTasks(nowMs: Long, limit: Int = StoryFeedbackOutbox.MAX_TASKS_PER_PUMP): List<StoryFeedbackOutbox.Task> =
        synchronized(lock) {
            ensureLoaded()
            outbox.purge(nowMs)
            outbox.due(nowMs, limit)
        }

    /**
     * Задача отложена, но не провалена: сводка ещё не созрела (пауза между
     * волнами), поэтому счётчик попыток не растёт и задача не снимается.
     */
    fun taskDeferred(task: StoryFeedbackOutbox.Task, nowMs: Long, delayMs: Long) {
        synchronized(lock) { outbox.defer(task, nowMs, delayMs) }
    }

    /** Исход отправки: принято - задача снята, нет - повтор через минуту. */
    fun taskResult(task: StoryFeedbackOutbox.Task, accepted: Boolean, nowMs: Long) {
        synchronized(lock) {
            outbox.result(task, accepted, nowMs)
            if (accepted) count(OperationalCounter.STORY_FEEDBACK_SENT)
            else count(OperationalCounter.STORY_FEEDBACK_DEFERRED)
        }
    }

    /**
     * Текст пакета для задачи. Строится в момент отправки, поэтому в сеть уходит
     * самое свежее состояние книги, а не то, что было в момент постановки.
     */
    fun outgoing(
        task: StoryFeedbackOutbox.Task,
        ownId: String,
        ownName: String,
    ): Outgoing? = synchronized(lock) {
        ensureLoaded()
        if (ownId.isBlank()) return@synchronized null
        val name = StoryFeedbackWire.sanitizeName(ownName)
        when (task.kind) {
            StoryFeedbackOutbox.Kind.REACT -> {
                val entry = ledger.reactionOf(task.storyId, ownId) ?: return@synchronized null
                val content = StoryFeedbackWire.buildReaction(
                    storyId = task.storyId,
                    authorId = task.peer,
                    actorId = ownId,
                    actorName = name,
                    emoji = entry.emoji,
                    added = !entry.removed,
                    atMs = entry.atMs,
                ) ?: return@synchronized null
                Outgoing(task.peer, content, StoryFeedbackWire.transportId(task.storyId, "react", ownId, entry.atMs))
            }

            StoryFeedbackOutbox.Kind.COMMENT -> {
                val entry = ledger.commentById(task.storyId, task.commentId) ?: return@synchronized null
                val content = StoryFeedbackWire.buildComment(
                    storyId = task.storyId,
                    authorId = task.peer,
                    actorId = ownId,
                    actorName = name,
                    commentId = entry.id,
                    body = entry.body,
                    atMs = entry.atMs,
                ) ?: return@synchronized null
                Outgoing(task.peer, content, StoryFeedbackWire.transportId(task.storyId, "cmt", ownId, entry.atMs))
            }

            StoryFeedbackOutbox.Kind.ASK -> {
                // Время берём из задачи, а не «сейчас»: повторная волна обязана
                // уйти тем же сообщением, иначе автор ответит сводкой дважды.
                val content = StoryFeedbackWire.buildStateRequest(
                    storyId = task.storyId,
                    authorId = task.peer,
                    actorId = ownId,
                    actorName = name,
                    atMs = task.createdAtMs,
                ) ?: return@synchronized null
                Outgoing(
                    task.peer,
                    content,
                    StoryFeedbackWire.transportId(task.storyId, "ask", ownId, task.createdAtMs),
                )
            }

            StoryFeedbackOutbox.Kind.UNICAST -> {
                val state = stateContent(task.storyId, ownId) ?: return@synchronized null
                Outgoing(task.peer, state.first, state.second)
            }

            StoryFeedbackOutbox.Kind.BROADCAST -> {
                val state = stateContent(task.storyId, ownId) ?: return@synchronized null
                // Список зрителей подставляет отправитель: контакты лежат в базе.
                Outgoing("", state.first, state.second)
            }
        }
    }

    /** Кому сводка не нужна: они свой отклик уже применили у себя. */
    fun echoExclusions(storyId: String): Set<String> = synchronized(lock) {
        echoActors[storyId].orEmpty().toSet()
    }

    /** Рассылка сводки ушла: версия и пауза отмечены, эхо-список очищен. */
    fun markBroadcastSent(storyId: String, nowMs: Long) {
        synchronized(lock) {
            sentVersions[storyId] = ledger.versionOf(storyId)
            lastBroadcast[storyId] = nowMs
            queuedSince.remove(storyId)
            echoActors.remove(storyId)
            count(OperationalCounter.STORY_FEEDBACK_BROADCAST)
        }
    }

    /** Пора ли рассылать сводку этой истории (проверяет насос перед отправкой). */
    fun broadcastDue(storyId: String, nowMs: Long): Boolean = synchronized(lock) {
        ensureLoaded()
        StoryFeedbackOutbox.shouldBroadcast(
            nowMs = nowMs,
            lastBroadcastMs = lastBroadcast[storyId] ?: 0L,
            pendingVersion = ledger.versionOf(storyId),
            sentVersion = sentVersions[storyId] ?: 0L,
            queuedAtMs = queuedSince[storyId] ?: 0L,
        )
    }

    /** История отозвана или истекла: отклики и задачи снимаются вместе с ней. */
    fun forget(storyId: String) {
        synchronized(lock) {
            if (!loaded) return
            ledger.forget(storyId)
            outbox.forgetStory(storyId)
            asks.remove(storyId)
            sentVersions.remove(storyId)
            lastBroadcast.remove(storyId)
            queuedSince.remove(storyId)
            echoActors.remove(storyId)
            bump()
            persist()
        }
    }

    /** Уборка по живым историям: лента живёт сутки, книга откликов не дольше. */
    fun retain(aliveStoryIds: Set<String>, nowMs: Long): Int = synchronized(lock) {
        ensureLoaded()
        outbox.purge(nowMs)
        val dropped = ledger.retain(aliveStoryIds)
        val stale = (asks.keys + sentVersions.keys + lastBroadcast.keys + queuedSince.keys + echoActors.keys)
            .filterNot { it in aliveStoryIds }
        stale.forEach {
            asks.remove(it)
            sentVersions.remove(it)
            lastBroadcast.remove(it)
            queuedSince.remove(it)
            echoActors.remove(it)
        }
        if (dropped > 0) persist()
        dropped
    }

    /** Строка в отчёт «Логи»: сколько откликов хранится и что ещё в досылке. */
    fun report(context: Context): String = synchronized(lock) {
        appContext = context.applicationContext
        ensureLoaded()
        val stories = ledger.knownStoryIds().size
        val tasks = outbox.snapshot()
        val oldest = tasks.minOfOrNull { it.createdAtMs }?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) }
        "[отклики на истории]\n" +
            "feedback_snapshot=$snapshotStatus · stories_with_feedback=$stories · pending_tasks=${tasks.size} · " +
            "max_tasks=${StoryFeedbackOutbox.MAX_ENTRIES} · oldest_age_ms=${oldest ?: -1}\n" +
            "реакции и комментарии видны всем зрителям; рассылка сводки автором не чаще раза в " +
            "${StoryFeedbackOutbox.BROADCAST_MIN_INTERVAL_MS / 1000}с на историю, отклик живёт сутки вместе с историей.\n"
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    /** Отклик принят от зрителя: автору надо разослать сводку остальным. */
    private fun afterIncomingChange(story: StoryStore.Story, actorId: String, nowMs: Long) {
        if (story.mine) {
            echoActors.getOrPut(story.id) { LinkedHashSet() }.add(actorId)
            queueBroadcast(story.id, nowMs)
        }
        bump()
        persist()
    }

    private fun queueBroadcast(storyId: String, nowMs: Long) {
        if (queuedSince[storyId] == null) queuedSince[storyId] = nowMs
        outbox.enqueue(
            kind = StoryFeedbackOutbox.Kind.BROADCAST,
            storyId = storyId,
            nowMs = nowMs,
            notBeforeMs = nowMs + StoryFeedbackOutbox.BROADCAST_DEBOUNCE_MS,
        )
    }

    /**
     * Содержимое сводки и её транспортный идентификатор.
     *
     * Оба зависят только от версии книги откликов, а не от «сейчас»: повторная
     * волна той же сводки уходит тем же сообщением и тем же текстом, поэтому
     * ядро и получатель видят повтор, а не новый пакет.
     */
    private fun stateContent(storyId: String, ownId: String): Pair<String, String>? {
        val version = ledger.versionOf(storyId)
        if (version <= 0L) return null
        val atMs = ledger.lastChangeAt(storyId)
        if (atMs <= 0L) return null
        val content = StoryFeedbackWire.buildState(
            storyId = storyId,
            authorId = ownId,
            reactions = ledger.reactionEntries(storyId),
            comments = ledger.commentEntries(storyId),
            atMs = atMs,
        )
        if (content == null) {
            // Сводка не влезла даже после обрезки: это ошибка формата, а не сети.
            TransferDiagnostics.recordWarning(
                "story",
                "сводка откликов истории не поместилась в пакет; данные контактов не копируются",
            )
            return null
        }
        return content to StoryFeedbackWire.transportId(storyId, "state", ownId, version)
    }

    /**
     * «Что-то изменилось»: экран пересчитывает сводку, репозиторий толкает
     * насос. Это СЧЁТЧИК СОБЫТИЙ, а не версия книги откликов: событие, которое
     * книгу не меняет (например, зрителю поставлена задача отдать сводку), тоже
     * обязано дойти до насоса, а StateFlow повторяющееся значение не публикует.
     */
    private fun bump() {
        _changes.value = _changes.value + 1
    }

    private fun count(counter: OperationalCounter) {
        OperationalDiagnostics.tracker.count(counter)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = snapshotFile() ?: run { snapshotStatus = "no_context"; return }
        val rows = ArrayList<StoryFeedbackLedger.Snapshot>()
        val stored = runCatching {
            if (!file.baseFile.exists()) return@runCatching null
            require(file.baseFile.length() <= MAX_SNAPSHOT_BYTES)
            file.openRead().bufferedReader().use { it.readText() }
        }.getOrNull()
        if (stored == null) {
            snapshotStatus = if (file.baseFile.exists()) "read_failed" else "empty"
        } else {
            snapshotStatus = if (parse(stored, rows)) "ready" else "parse_failed"
        }
        ledger.restore(rows)
        // После перезапуска ничего не рассылаем «догоняющим» порядком: сводку
        // зрители попросят сами, а свои значки и комментарии останутся в очереди.
        ledger.knownStoryIds().forEach { sentVersions[it] = ledger.versionOf(it) }
    }

    private fun persist() {
        val file = snapshotFile() ?: return
        val payload = buildSnapshot()
        snapshotStatus = "ready"
        diskWriter.execute { writeSnapshot(file, payload) }
    }

    /** Снимок с обрезкой по размеру: сначала вытесняем самые старые истории. */
    private fun buildSnapshot(): String {
        var rows = ledger.rows().takeLast(MAX_PERSISTED_STORIES)
        var text = render(rows)
        while (text.toByteArray(Charsets.UTF_8).size > MAX_SNAPSHOT_BYTES && rows.size > 1) {
            rows = rows.drop(1)
            text = render(rows)
        }
        return text
    }

    private fun render(rows: List<StoryFeedbackLedger.Snapshot>): String {
        val stories = JSONArray()
        rows.forEach { row ->
            val reactions = JSONArray()
            row.reactions.forEach { item ->
                reactions.put(JSONArray().put(item.actorId).put(item.actorName).put(item.emoji).put(item.atMs))
            }
            val comments = JSONArray()
            row.comments.forEach { item ->
                comments.put(JSONArray().put(item.id).put(item.actorId).put(item.actorName).put(item.body).put(item.atMs))
            }
            stories.put(JSONObject().put("id", row.storyId).put("r", reactions).put("c", comments))
        }
        val askArray = JSONArray()
        asks.forEach { (storyId, atMs) -> askArray.put(JSONArray().put(storyId).put(atMs)) }
        return JSONObject().put("v", 1).put("stories", stories).put("asks", askArray).toString()
    }

    private fun parse(raw: String, into: MutableList<StoryFeedbackLedger.Snapshot>): Boolean = runCatching {
        val root = JSONObject(raw)
        val stories = root.optJSONArray("stories") ?: JSONArray()
        val now = System.currentTimeMillis()
        for (index in 0 until stories.length().coerceAtMost(MAX_PERSISTED_STORIES)) {
            val item = stories.getJSONObject(index)
            val storyId = item.getString("id")
            if (storyId.isEmpty() || storyId.length > StoryFeedbackWire.MAX_STORY_ID_CHARS) continue
            val reactions = ArrayList<StoryFeedbackWire.Reaction>()
            val reactionArray = item.optJSONArray("r") ?: JSONArray()
            for (row in 0 until reactionArray.length()) {
                val cell = reactionArray.getJSONArray(row)
                if (cell.length() != 4) continue
                val actorId = cell.getString(0)
                val atMs = cell.getLong(3)
                if (actorId.isEmpty() || atMs <= 0L || now - atMs >= StoryStore.LIFETIME_MS) continue
                reactions.add(
                    StoryFeedbackWire.Reaction(
                        actorId = actorId,
                        actorName = StoryFeedbackWire.sanitizeName(cell.optString(1)),
                        emoji = cell.optString(2).take(StoryFeedbackWire.MAX_EMOJI_CHARS),
                        atMs = atMs,
                    ),
                )
            }
            val comments = ArrayList<StoryFeedbackWire.Comment>()
            val commentArray = item.optJSONArray("c") ?: JSONArray()
            for (row in 0 until commentArray.length()) {
                val cell = commentArray.getJSONArray(row)
                if (cell.length() != 5) continue
                val commentId = cell.getString(0)
                val actorId = cell.getString(1)
                val atMs = cell.getLong(4)
                val body = StoryFeedbackWire.sanitizeComment(cell.optString(3)) ?: continue
                if (commentId.isEmpty() || actorId.isEmpty() || atMs <= 0L) continue
                if (now - atMs >= StoryStore.LIFETIME_MS) continue
                comments.add(
                    StoryFeedbackWire.Comment(
                        id = commentId,
                        actorId = actorId,
                        actorName = StoryFeedbackWire.sanitizeName(cell.optString(2)),
                        body = body,
                        atMs = atMs,
                    ),
                )
            }
            if (reactions.isNotEmpty() || comments.isNotEmpty()) {
                into.add(StoryFeedbackLedger.Snapshot(storyId, reactions, comments))
            }
        }
        val askArray = root.optJSONArray("asks") ?: JSONArray()
        for (index in 0 until askArray.length()) {
            val cell = askArray.optJSONArray(index) ?: continue
            if (cell.length() != 2) continue
            val storyId = cell.optString(0)
            val atMs = cell.optLong(1)
            if (storyId.isNotEmpty() && atMs > 0L && now - atMs < StoryStore.LIFETIME_MS) asks[storyId] = atMs
        }
        true
    }.getOrDefault(false)

    private fun writeSnapshot(file: AtomicFile, payload: String): Boolean {
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = file.startWrite()
            stream.write(payload.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let { file.failWrite(it) }
            snapshotStatus = "write_failed"
            false
        }
    }

    private fun snapshotFile(): AtomicFile? = appContext?.let {
        AtomicFile(File(it.filesDir, FILE_NAME))
    }
}
