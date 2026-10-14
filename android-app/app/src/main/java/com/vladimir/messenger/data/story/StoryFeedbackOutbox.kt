package com.vladimir.messenger.data.story

/**
 * Очередь откликов на истории: что и кому дослать.
 *
 * Устройство одно, а откликов много, поэтому очередь держит НЕ тексты пакетов,
 * а намерения: «разослать сводку истории X», «отдать сводку истории X узлу Y»,
 * «отправить мой значок на историю X». Текст строится в момент отправки из книги
 * откликов - значит, в сеть всегда уходит самое свежее состояние, а десять
 * быстрых нажатий не превращаются в десять пакетов.
 *
 * Схлопывание (один ключ - одна запись):
 *  - сводка истории: нужна только последняя;
 *  - сводка одному узлу: то же;
 *  - мой значок: нужен только последний (снял-поставил-снял);
 *  - мой комментарий: НЕ схлопывается - два разных комментария это два пакета.
 *
 * Класс чистый Kotlin: его компилирует и проверяет `scripts/ci/check-chat-history.sh`.
 */
class StoryFeedbackOutbox(private val maxEntries: Int = MAX_ENTRIES) {

    enum class Kind {
        /** Зритель -> автор истории: мой значок. */
        REACT,

        /** Зритель -> автор истории: мой комментарий. */
        COMMENT,

        /** Зритель -> автор истории: «покажи сводку, я открыл историю без откликов». */
        ASK,

        /** Автор -> остальные зрители: сводка (публичные счётчики и лента). */
        BROADCAST,

        /** Автор -> один зритель, который попросил сводку. */
        UNICAST,
    }

    data class Task(
        val kind: Kind,
        val key: String,
        val storyId: String,
        /**
         * Узел-получатель. У [Kind.REACT], [Kind.COMMENT] и [Kind.ASK] это автор
         * истории, у [Kind.UNICAST] - зритель, попросивший сводку, а у
         * [Kind.BROADCAST] строка пустая: список зрителей берётся при отправке.
         */
        val peer: String,
        /** Идентификатор комментария у [Kind.COMMENT], иначе пустая строка. */
        val commentId: String,
        val createdAtMs: Long,
        /** Не раньше этого времени: пауза между волнами рассылки сводки. */
        val notBeforeMs: Long,
        val attempts: Int,
        /**
         * Поколение намерения: растёт при каждой перезаписи ключа. По нему
         * исход отправки отличает «моя задача» от «её уже заменили новым
         * откликом» и не откладывает свежее намерение на минуту.
         */
        val generation: Long = 0L,
    )

    private val rows = LinkedHashMap<String, Task>()

    fun snapshot(): List<Task> = rows.values.toList()

    fun size(): Int = rows.size

    fun contains(key: String): Boolean = rows.containsKey(key)

    /**
     * Поставить задачу. Тот же ключ перезаписывает прежнюю запись (свежее
     * намерение важнее старого), поэтому порядок и число пакетов ограничены.
     */
    fun enqueue(
        kind: Kind,
        storyId: String,
        nowMs: Long,
        peer: String = "",
        commentId: String = "",
        notBeforeMs: Long = nowMs,
    ): Boolean {
        if (storyId.isEmpty() || storyId.length > StoryFeedbackWire.MAX_STORY_ID_CHARS) return false
        if (peer.length > StoryFeedbackWire.MAX_NODE_CHARS) return false
        val key = key(kind, storyId, peer, commentId)
        val previous = rows[key]
        val task = Task(
            kind = kind,
            key = key,
            storyId = storyId,
            peer = if (kind == Kind.BROADCAST) "" else peer,
            commentId = if (kind == Kind.COMMENT) commentId else "",
            createdAtMs = previous?.createdAtMs ?: nowMs,
            // Прежняя задача уже могла стоять в паузе: новую не откладываем заново,
            // иначе частые отклики отодвигали бы рассылку бесконечно.
            notBeforeMs = minOf(notBeforeMs, previous?.notBeforeMs ?: notBeforeMs),
            attempts = 0,
            generation = (previous?.generation ?: -1L) + 1L,
        )
        rows.remove(key)
        rows[key] = task
        while (rows.size > maxEntries) {
            // Первыми вытесняются самые старые широковещательные сводки: они
            // восстанавливаются следующей волной, а чужой комментарий - нет.
            val victim = rows.values
                .filter { it.kind == Kind.BROADCAST || it.kind == Kind.UNICAST }
                .minByOrNull { it.createdAtMs }
                ?: rows.values.minByOrNull { it.createdAtMs }
                ?: break
            rows.remove(victim.key)
        }
        return true
    }

    /** Что пора отправить: не больше [limit] задач, чья пауза уже прошла. */
    fun due(nowMs: Long, limit: Int): List<Task> =
        rows.values.filter { it.notBeforeMs <= nowMs }
            .sortedWith(compareBy<Task> { priority(it.kind) }.thenBy { it.notBeforeMs })
            .take(limit.coerceAtLeast(0))

    /**
     * Исход отправки. Принято - задача снята (сводка придёт следующей волной,
     * если появятся новые отклики). Не принято - повтор через [RETRY_MS].
     *
     * Исход ЧУЖОЙ по поколению задачи игнорируется: пока пакет был в пути,
     * намерение могли перезаписать новым откликом, и откладывать его на минуту
     * значило бы задержать свежий значок у соседей.
     */
    fun result(task: Task, accepted: Boolean, nowMs: Long) {
        val current = rows[task.key] ?: return
        if (current.generation != task.generation) return
        if (accepted) {
            rows.remove(task.key)
            return
        }
        rows[task.key] = current.copy(
            attempts = current.attempts + 1,
            notBeforeMs = nowMs + RETRY_MS,
        )
    }

    /**
     * Отложить задачу, НЕ считая это попыткой: сводка ещё не созрела (пауза
     * между волнами), а не сеть отказала. Ждать минуту в этом случае значило
     * бы, что активная история рассылается раз в минуту, а не раз в несколько
     * секунд.
     */
    fun defer(task: Task, nowMs: Long, delayMs: Long) {
        val current = rows[task.key] ?: return
        if (current.generation != task.generation) return
        rows[task.key] = current.copy(notBeforeMs = maxOf(current.notBeforeMs, nowMs + delayMs))
    }

    fun remove(key: String) {
        rows.remove(key)
    }

    /** История отозвана или истекла: её задачи больше не нужны. */
    fun forgetStory(storyId: String): Int {
        val before = rows.size
        rows.values.filter { it.storyId == storyId }.forEach { rows.remove(it.key) }
        return before - rows.size
    }

    /** Уборка просроченного: отклик не живёт дольше самой истории. */
    fun purge(nowMs: Long): Int {
        val before = rows.size
        rows.values.filter {
            nowMs - it.createdAtMs >= TTL_MS || it.createdAtMs > nowMs + FUTURE_MS
        }.forEach { rows.remove(it.key) }
        return before - rows.size
    }

    private fun priority(kind: Kind): Int = when (kind) {
        // Своё действие человека важнее рассылки сводки соседям.
        Kind.REACT, Kind.COMMENT -> 0
        Kind.ASK, Kind.UNICAST -> 1
        Kind.BROADCAST -> 2
    }

    companion object {
        const val MAX_ENTRIES = 64
        const val RETRY_MS = 60_000L
        const val TTL_MS = 24L * 60 * 60 * 1000

        /** Сколько пакетов отдаём за один цикл насоса: не забивать очередь ядра. */
        const val MAX_TASKS_PER_PUMP = 24

        /** Пауза слипания: отклики, пришедшие пачкой, уходят одной сводкой. */
        const val BROADCAST_DEBOUNCE_MS = 1_500L

        /** Как часто одна история может рассылаться всем зрителям. */
        const val BROADCAST_MIN_INTERVAL_MS = 5_000L

        /** Как часто один зритель может просить сводку у автора. */
        const val ASK_COOLDOWN_MS = 5L * 60 * 1000

        /** Запас на расхождение часов: пакеты из будущего не держим. */
        const val FUTURE_MS = 300_000L

        /**
         * Предел попыток одного веера сводки. Если рассылка не сходится (узел
         * offline, полоса SIGNAL занята), версия отмечается отправленной и
         * остаток зрителей добирает сводку сам - просьбой `ask` при открытии
         * истории. Без предела веер повторялся бы сутки.
         */
        const val MAX_BROADCAST_ATTEMPTS = 5

        /**
         * Когда зрителю пора попросить сводку у автора.
         *
         * Просьба - служебный пакет, её нельзя слать на каждое открытие истории:
         * иначе телефон, который листает ленту, засыпал бы автора запросами.
         */
        fun shouldAsk(
            mine: Boolean,
            nowMs: Long,
            lastAskMs: Long,
            lastChangeMs: Long,
            hasAnyFeedback: Boolean,
        ): Boolean {
            if (mine) return false
            if (lastAskMs > 0L && nowMs - lastAskMs < ASK_COOLDOWN_MS) return false
            // Отклики уже есть и они свежие - сводка приехала, спрашивать нечего.
            if (hasAnyFeedback && lastChangeMs > 0L && nowMs - lastChangeMs < ASK_COOLDOWN_MS) return false
            return true
        }

        /**
         * Когда автору пора разослать сводку всем зрителям.
         *
         * [pendingVersion] - версия книги откликов сейчас, [sentVersion] - версия на
         * момент последней рассылки. Пауза [BROADCAST_MIN_INTERVAL_MS] нужна, чтобы
         * пачка из десяти значков ушла одной волной, а не десятью.
         */
        fun shouldBroadcast(
            nowMs: Long,
            lastBroadcastMs: Long,
            pendingVersion: Long,
            sentVersion: Long,
            queuedAtMs: Long,
        ): Boolean {
            // Ничего нового с последней рассылки - пакет не нужен.
            if (pendingVersion <= sentVersion) return false
            // Одна история не рассылается чаще раза в [BROADCAST_MIN_INTERVAL_MS]:
            // пачка значков обязана уйти одной волной, а не десятком пакетов.
            if (lastBroadcastMs > 0L && nowMs - lastBroadcastMs < BROADCAST_MIN_INTERVAL_MS) return false
            // Окно слипания: первый отклик пачки ждёт [BROADCAST_DEBOUNCE_MS], чтобы
            // следующие ушли тем же пакетом.
            return queuedAtMs <= 0L || nowMs - queuedAtMs >= BROADCAST_DEBOUNCE_MS
        }

        /** Кого исключить из рассылки сводки: того, кто сам только что отреагировал. */
        fun broadcastRecipients(contacts: List<String>, excludeActorId: String?): List<String> =
            contacts.map { it.trim() }
                .filter { it.isNotEmpty() && it != excludeActorId }
                .distinct()

        fun key(kind: Kind, storyId: String, peer: String, commentId: String): String = when (kind) {
            Kind.REACT -> "react|$storyId"
            Kind.COMMENT -> "cmt|$storyId|$commentId"
            Kind.ASK -> "ask|$storyId"
            Kind.UNICAST -> "state|$storyId|$peer"
            Kind.BROADCAST -> "broadcast|$storyId"
        }
    }
}
