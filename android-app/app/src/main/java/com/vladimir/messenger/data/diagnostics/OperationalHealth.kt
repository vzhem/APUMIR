package com.vladimir.messenger.data.diagnostics

/** Fixed labels only. No IDs, exception strings or user content in snapshots. */
enum class OperationalCounter {
    MESSAGE_PERSISTED, MESSAGE_ATTEMPTS, MESSAGE_RETRIES, MESSAGE_TRANSPORT_ACCEPTED,
    MESSAGE_DEFERRED, MESSAGE_ACK, MESSAGE_ACK_UNTRACKED, CORRELATION_EVICTED,
    DELETE_REQUESTED, DELETE_ACK_MATCHED, DELETE_RETRY,
    SCHEDULE_CANCEL_LOCAL_BEFORE_SEND, SCHEDULE_CANCEL_REQUESTED, SCHEDULE_CANCEL_ATTEMPTS,
    SCHEDULE_CANCEL_TRANSPORT_ACCEPTED, SCHEDULE_CANCEL_DEFERRED, SCHEDULE_CANCEL_ACK, SCHEDULE_CANCEL_COMPLETED,
    FILE_RETRY, FILE_WAIT_ATTEMPTS, FILE_TIMEOUT, FILE_COMPLETE_IN, FILE_COMPLETE_OUT,
    FILE_CONFIRMED_BYTES_IN, FILE_CONFIRMED_BYTES_OUT, FILE_ROUTE_CHANGES, QUOTA_REJECTED,
    EXPIRED_FILE_ROWS, EXPIRED_FILE_COPIES,
    SERVICE_CREATED, SERVICE_START_COMMAND, SERVICE_DESTROYED, CORE_READY,
    FOREGROUND_ENTER, FOREGROUND_LEAVE, SEAL_DEFERRED,
    STORY_ENQUEUED, STORY_QUEUE_FULL, STORY_NO_AUDIENCE, STORY_PACKET_ATTEMPTS, STORY_PACKET_ACCEPTED,
    STORY_PACKET_DEFERRED, STORY_RECEIVED, STORY_PHOTO_PENDING, STORY_INVALID,
    STORY_EXPIRED, STORY_CLOCK_SKEW, STORY_PRESENCE_WAKE, STORY_REVOKE_APPLIED, STORY_TRANSPORT_ROUND_COMPLETE,
    // Отклики на истории (реакции и комментарии): приём, рассылка сводки, досылка.
    STORY_FEEDBACK_RECEIVED, STORY_FEEDBACK_INVALID, STORY_FEEDBACK_IGNORED,
    STORY_FEEDBACK_BROADCAST, STORY_FEEDBACK_SENT, STORY_FEEDBACK_DEFERRED,
    // Android 12+ отказ во входе в foreground и Android 15 истечение бюджета
    // сервиса: без этих счётчиков такие отказы невидимы в диагностике
    // (docs/AUDIT_2026-10-26.md, P0-1).
    FOREGROUND_START_REFUSED, FOREGROUND_TIMEOUT,
}
enum class OperationalTiming { MESSAGE_ACK_UPTIME, MESSAGE_FIRST_TRANSPORT_UPTIME, CORE_READY_UPTIME, RESUME_READY_UPTIME, FILE_COMPLETE_IN_WALL, FILE_COMPLETE_OUT_WALL }
enum class KeyModeReason { NOT_PROBED, READ_PREFS, ABSENT, LOOKUP_KEY, MISSING_WRAP_KEY, KEYSTORE_ERROR, UNWRAP, UNWRAP_FAILED, CHECK_BINDING, BINDING_INVALID, INCOMPLETE_RECORD, READY }
enum class TransferRoute { LAN, UDP, QUIC, BROKER, BINARY }

data class LatencySummary(val count: Long, val p50UpperMs: Long?, val p95UpperMs: Long?, val maxMs: Long)
class BoundedLatency {
    private val limits = longArrayOf(10, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000, 60000, 300000, 3600000, Long.MAX_VALUE)
    private val buckets = LongArray(limits.size)
    private var count = 0L
    private var maximum = 0L
    fun add(ms: Long) { val value = ms.coerceAtLeast(0); buckets[limits.indexOfFirst { value <= it }]++; count++; maximum = maxOf(maximum, value) }
    fun snapshot(): LatencySummary {
        fun percentile(percent: Long): Long? {
            if (count == 0L) return null
            val rank = (count * percent + 99) / 100
            var seen = 0L
            for (i in limits.indices) { seen += buckets[i]; if (seen >= rank) return limits[i].takeIf { it != Long.MAX_VALUE } }
            return null // Open-ended bucket, not a fake exact percentile.
        }
        return LatencySummary(count, percentile(50), percentile(95), maximum)
    }
}

data class OperationalSnapshot(
    val counters: Map<OperationalCounter, Long> = emptyMap(),
    val timings: Map<OperationalTiming, LatencySummary> = emptyMap(),
    val routeResults: Map<TransferRoute, Pair<Long, Long>> = emptyMap(),
    val pendingMessages: Int = 0,
    val oldestPendingUptimeMs: Long? = null,
    val foregroundUptimeMs: Long = 0,
    val backgroundUptimeMs: Long = 0,
    val lastFileGoodputInBps: Long? = null,
    val lastFileGoodputOutBps: Long? = null,
) {
    fun render(): String = buildString {
        appendLine("[операционные метрики]")
        appendLine("scope=current_process; bounded correlation=256; транспортный успех не равен ACK получателя.")
        counters.forEach { (key, value) -> appendLine("${key.name.lowercase()}=$value") }
        // Владелец 2026-10-15: строка «story_packet_attempts=323 ·
        // story_packet_deferred=262» читалась как «262 истории не ушли». Эти
        // счётчики считают ПРЯМУЮ ногу (как delete_retry и schedule_cancel):
        // accepted — отдано напрямую, deferred — НЕ отдано напрямую, а сам
        // пакет уже лежит в очереди ядра и уходит брокером или роем. Поэтому
        // рядом с числами печатаем, что они значат.
        val storyAttempts = counters[OperationalCounter.STORY_PACKET_ATTEMPTS] ?: 0L
        if (storyAttempts > 0L) {
            appendLine(
                "истории: попыток=$storyAttempts · " +
                    "отдано напрямую=${counters[OperationalCounter.STORY_PACKET_ACCEPTED] ?: 0L} · " +
                    "не отдано напрямую=${counters[OperationalCounter.STORY_PACKET_DEFERRED] ?: 0L} " +
                    "(это ПРЯМАЯ нога: пакет остаётся в очереди ядра и уходит брокером/роем, а не потерян)",
            )
        }
        timings.forEach { (key, value) -> appendLine("latency=${key.name.lowercase()} · n=${value.count} · p50_upper_ms=${value.p50UpperMs ?: -1} · p95_upper_ms=${value.p95UpperMs ?: -1} · max_ms=${value.maxMs}") }
        appendLine("p50/p95 — верхние границы фиксированных корзин; -1 = нет данных или открытая корзина >1ч.")
        routeResults.forEach { (route, counts) -> appendLine("file_route=${route.name.lowercase()} · attempts=${counts.first} · failed=${counts.second}") }
        appendLine("message_pending_tracked=$pendingMessages · oldest_pending_uptime_ms=${oldestPendingUptimeMs ?: -1}")
        appendLine("foreground_uptime_ms=$foregroundUptimeMs · background_uptime_ms=$backgroundUptimeMs (сон исключён)")
        appendLine("file_last_confirmed_goodput_in_bps=${lastFileGoodputInBps ?: -1} · out_bps=${lastFileGoodputOutBps ?: -1}")
        appendLine("Goodput: подтверждённые полезные байты / время от создания передачи, включая ожидание; wall clock может меняться. Это не скорость канала.")
    }
}

class OperationalHealthTracker(private val clock: () -> Long) {
    private data class Message(val at: Long, var attempts: Int = 0, var accepted: Boolean = false)
    private val messages = LinkedHashMap<String, Message>()
    private val counters = linkedMapOf<OperationalCounter, Long>()
    private val timings = linkedMapOf<OperationalTiming, BoundedLatency>()
    private val routes = linkedMapOf<TransferRoute, Pair<Long, Long>>()
    private val peerRoutes = LinkedHashMap<String, TransferRoute>()
    private val created = clock()
    private var foregroundAt: Long? = null
    private var foregroundTotal = 0L
    private var coreStarted: Long? = null
    private var resumeAt: Long? = null
    private var goodputIn: Long? = null
    private var goodputOut: Long? = null

    @Synchronized fun count(key: OperationalCounter, amount: Long = 1) { if (amount > 0) counters[key] = (counters[key] ?: 0) + amount }
    @Synchronized fun timing(key: OperationalTiming, ms: Long) { timings.getOrPut(key) { BoundedLatency() }.add(ms) }
    @Synchronized fun persisted(id: String) {
        if (id.isBlank() || id.length > 256 || messages.containsKey(id)) return
        if (messages.size == 256) { messages.remove(messages.keys.first()); count(OperationalCounter.CORRELATION_EVICTED) }
        messages[id] = Message(clock()); count(OperationalCounter.MESSAGE_PERSISTED)
    }
    @Synchronized fun attempt(id: String) {
        val message = messages[id] ?: return
        if (message.attempts++ > 0) count(OperationalCounter.MESSAGE_RETRIES)
        count(OperationalCounter.MESSAGE_ATTEMPTS)
    }
    @Synchronized fun transportResult(id: String, accepted: Boolean) {
        val message = messages[id] ?: return
        if (!accepted) { count(OperationalCounter.MESSAGE_DEFERRED); return }
        count(OperationalCounter.MESSAGE_TRANSPORT_ACCEPTED)
        if (!message.accepted) { message.accepted = true; timing(OperationalTiming.MESSAGE_FIRST_TRANSPORT_UPTIME, clock() - message.at) }
    }
    @Synchronized fun ack(id: String) {
        count(OperationalCounter.MESSAGE_ACK)
        val message = messages.remove(id)
        if (message == null) count(OperationalCounter.MESSAGE_ACK_UNTRACKED)
        else timing(OperationalTiming.MESSAGE_ACK_UPTIME, clock() - message.at)
    }
    @Synchronized fun routeResult(peer: String, route: TransferRoute, accepted: Boolean) {
        val old = routes[route] ?: (0L to 0L)
        routes[route] = old.first + 1 to old.second + if (accepted) 0L else 1L
        if (!accepted || peer.length !in 1..256) return
        if (peerRoutes[peer]?.let { it != route } == true) count(OperationalCounter.FILE_ROUTE_CHANGES)
        if (peerRoutes.size >= 256 && peer !in peerRoutes) peerRoutes.remove(peerRoutes.keys.first())
        peerRoutes[peer] = route
    }
    @Synchronized fun coreStarting() { coreStarted = clock(); count(OperationalCounter.SERVICE_CREATED) }
    @Synchronized fun coreReady() {
        coreStarted?.let { timing(OperationalTiming.CORE_READY_UPTIME, clock() - it); coreStarted = null; count(OperationalCounter.CORE_READY) }
        resumeAt?.let { timing(OperationalTiming.RESUME_READY_UPTIME, clock() - it); resumeAt = null }
    }
    @Synchronized fun visible(value: Boolean) {
        val now = clock()
        if (value && foregroundAt == null) { foregroundAt = now; resumeAt = now; count(OperationalCounter.FOREGROUND_ENTER) }
        if (!value && foregroundAt != null) { foregroundTotal += (now - foregroundAt!!).coerceAtLeast(0); foregroundAt = null; resumeAt = null; count(OperationalCounter.FOREGROUND_LEAVE) }
    }
    @Synchronized fun fileComplete(incoming: Boolean, bytes: Long, elapsedWallMs: Long) {
        count(if (incoming) OperationalCounter.FILE_COMPLETE_IN else OperationalCounter.FILE_COMPLETE_OUT)
        count(if (incoming) OperationalCounter.FILE_CONFIRMED_BYTES_IN else OperationalCounter.FILE_CONFIRMED_BYTES_OUT, bytes)
        if (elapsedWallMs <= 0 || bytes < 0) return
        timing(if (incoming) OperationalTiming.FILE_COMPLETE_IN_WALL else OperationalTiming.FILE_COMPLETE_OUT_WALL, elapsedWallMs)
        val bps = (bytes.toDouble() * 1000 / elapsedWallMs).toLong()
        if (incoming) goodputIn = bps else goodputOut = bps
    }
    @Synchronized fun snapshot(): OperationalSnapshot {
        val now = clock()
        val foreground = foregroundTotal + (foregroundAt?.let { (now - it).coerceAtLeast(0) } ?: 0)
        return OperationalSnapshot(counters.toMap(), timings.mapValues { it.value.snapshot() }, routes.toMap(), messages.size,
            messages.values.minOfOrNull { it.at }?.let { (now - it).coerceAtLeast(0) }, foreground,
            (now - created - foreground).coerceAtLeast(0), goodputIn, goodputOut)
    }
}

/** Upper bound from successful timed acquisitions; not BatteryStats or measured energy. */
class WakeHoldTracker {
    private var start: Long? = null
    private var until = 0L
    private var total = 0L
    private var acquisitions = 0L
    @Synchronized fun acquire(nowElapsedMs: Long, timeoutMs: Long) {
        release(nowElapsedMs)
        start = nowElapsedMs
        until = if (timeoutMs > Long.MAX_VALUE - nowElapsedMs) Long.MAX_VALUE else nowElapsedMs + timeoutMs.coerceAtLeast(0)
        acquisitions++
    }
    @Synchronized fun release(nowElapsedMs: Long) {
        start?.let { total += (minOf(nowElapsedMs, until) - it).coerceAtLeast(0) }
        start = null
    }
    @Synchronized fun snapshot(nowElapsedMs: Long): Pair<Long, Long> = acquisitions to
        (total + (start?.let { (minOf(nowElapsedMs, until) - it).coerceAtLeast(0) } ?: 0))
}
