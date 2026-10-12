package com.vladimir.messenger.data.swarm

// =============================================================================
// SWARMPOLICY.KT — правила роя: кому первому и сколько можно (чистый Kotlin)
// =============================================================================
// Владелец, 2026-09-09: «всё, что роем можно сделать, - всю систему настраивать
// на ройную; сначала своим, проверенным, популярным, стабильным - потом всем
// подряд; и предел, чтобы телефон не перегружался».
//
// Здесь нет Android и базы: ярус считается по снимку знаний о соседях
// ([PeerKnowledge]), пределы - по режиму и обстановке. Всё проверяется
// обычным JVM-тестом (SwarmPolicyTest). Подробности и обоснование чисел -
// docs/CHANNEL_SWARM_DESIGN.md, раздел 9.
// =============================================================================

/** Ярус соседа: чем меньше номер, тем раньше ему уходят и отдаются данные. */
enum class PeerTier(val rank: Int, val title: String) {
    /** Мои контакты. */
    OWN(0, "свои"),

    /** Узлы с подписанной привязкой личности или зарегистрированным @именем. */
    VERIFIED(1, "проверенные"),

    /** Владельцы и админы моих сообществ и узлы с высоким рейтингом. */
    STABLE(2, "стабильные"),

    /** Все остальные, в том числе незнакомые. */
    OTHER(3, "остальные"),
}

/** Режим раздачи из настроек («Раздача»). Хранится в p2p_prefs: swarm_mode. */
enum class SwarmMode(val storedValue: String, val title: String) {
    NORMAL("normal", "Обычный"),
    ECONOMY("economy", "Экономный"),
    /** На полную: предел ядра, без скидок на мобильный интернет и заряд. */
    UNLIMITED("unlimited", "Без ограничений"),
    ;

    companion object {
        fun fromStored(value: String?): SwarmMode =
            entries.firstOrNull { it.storedValue == value } ?: NORMAL
    }
}

/**
 * Действующие пределы на весь телефон (не на канал: телефон не знает заранее,
 * сколько у него сообществ).
 */
data class SwarmLimits(
    /** Параллельных отправок ядру в веере. */
    val maxConcurrentSends: Int,
    /** Групповых пакетов с содержимым (сообщения, посты, куски фото, карточки) в минуту. */
    val maxPacketsPerMinute: Int,
    /** Служебных пакетов (просмотры, реакции) в минуту - отдельный, меньший бюджет. */
    val maxSignalsPerMinute: Int,
)

/**
 * Снимок того, что телефон знает о соседях. Собирается из базы и рейтинга
 * узлов ([SwarmPeerDirectory]); расчёт яруса от него - чистая функция.
 */
data class PeerKnowledge(
    val contacts: Set<String> = emptySet(),
    val verified: Set<String> = emptySet(),
    val privileged: Set<String> = emptySet(),
    /** Рейтинг PeerRatingStore 0..100 по узлам, о которых есть наблюдения. */
    val scores: Map<String, Int> = emptyMap(),
) {
    companion object {
        val EMPTY = PeerKnowledge()
    }
}

object SwarmPolicy {

    /** Рейтинг узла, с которого незнакомец считается «стабильным». */
    const val STABLE_SCORE = 60

    /** Мобильный интернет или «Экономия трафика»: темп вдвое ниже. */
    const val METERED_FACTOR = 0.5

    /** Заряд ниже [LOW_BATTERY_PERCENT] без зарядки: темп вдвое ниже. */
    const val LOW_BATTERY_FACTOR = 0.5
    const val LOW_BATTERY_PERCENT = 20

    /** Ниже этого не опускаемся ни в каком режиме: сеть не должна замирать. */
    const val MIN_CONCURRENT = 2
    const val MIN_PACKETS_PER_MINUTE = 30

    /** Консервативный потолок веера. Не является измеренной ёмкостью ядра или сети. */
    const val UNLIMITED_CONCURRENT = 32
    const val UNLIMITED_PACKETS_PER_MINUTE = 3000

    /** Доля бюджета на служебные пакеты: треть от содержимого. */
    const val SIGNAL_SHARE = 3

    /**
     * С какого числа подписчиков просмотры и реакции канала идут не всем, а
     * только владельцу и администраторам (рой, этап 3). Ниже порога каждый
     * читатель шлёт свой просмотр всем - на двадцати подписчиках это дёшево,
     * и счётчики сходятся у всех сразу. Выше - «каждый шлёт всем» растёт как
     * N², поэтому сигналы стекаются к владельцу и администраторам, а
     * читатели спрашивают у них сводные числа (`pcreq` → `pcnt`), открывая
     * канал. Порог совпадает с размером первой волны публикации.
     */
    const val COUNTERS_VIA_HUBS_FROM = 20

    /** Стекать ли просмотры и реакции к владельцу и администраторам при таком числе подписчиков. */
    fun countersViaHubs(memberCount: Int): Boolean = memberCount > COUNTERS_VIA_HUBS_FROM

    /**
     * С какого числа подписчиков комментарии канала идут не всем, а
     * владельцу и администраторам («сборщикам») плюс небольшой выборке
     * соседей (рой, этап 4). Читатель, открыв комментарии, просит последние
     * у сборщика (`creq` → `msg`), а сборщик передаёт новые комментарии тем,
     * кто ветку сейчас читает. Порог тот же, что у счётчиков: до него
     * «каждый шлёт всем» дёшево и комментарии видны всем сразу.
     */
    const val COMMENTS_VIA_HUBS_FROM = COUNTERS_VIA_HUBS_FROM

    /** Слать ли комментарии сборщикам (а не всем подписчикам) при таком числе подписчиков. */
    fun commentsViaHubs(memberCount: Int): Boolean = memberCount > COMMENTS_VIA_HUBS_FROM

    /**
     * С какого числа участников сообщения обычной группы идут роем (этап 5):
     * автор подписывает манифест и шлёт сообщение целиком первой волне и
     * старым телефонам, манифест - волне; дальше его разносят и раздают
     * сиды, как посты канала. До порога всё как раньше - каждый шлёт всем:
     * на трёх десятках участников это дёшево, сообщение доходит сразу, а
     * волна из двадцати (сообщение + манифест каждому) ещё не окупается.
     */
    const val GROUP_SWARM_FROM = 30

    /** Идут ли сообщения группы роем при таком числе участников. */
    fun groupSwarm(memberCount: Int): Boolean = memberCount > GROUP_SWARM_FROM

    /** Ярус узла по снимку знаний. Контакт всегда «свой», даже со слабым рейтингом. */
    fun tierOf(nodeId: String, knowledge: PeerKnowledge): PeerTier = when {
        nodeId in knowledge.contacts -> PeerTier.OWN
        nodeId in knowledge.verified -> PeerTier.VERIFIED
        nodeId in knowledge.privileged -> PeerTier.STABLE
        (knowledge.scores[nodeId] ?: 0) >= STABLE_SCORE -> PeerTier.STABLE
        else -> PeerTier.OTHER
    }

    /**
     * Порядок обхода получателей: по ярусу, внутри яруса - по рейтингу, при
     * равенстве - по идентификатору, чтобы порядок был воспроизводим.
     * Никого не выбрасывает: «потом всем подряд» - значит всем.
     */
    fun order(candidates: List<String>, knowledge: PeerKnowledge): List<String> {
        if (candidates.size <= 1) return candidates
        return candidates.sortedWith(
            compareBy<String> { tierOf(it, knowledge).rank }
                .thenByDescending { knowledge.scores[it] ?: 0 }
                .thenBy { it },
        )
    }

    /**
     * Пределы для режима и обстановки. Числа режима - единственное место, где
     * они записаны; экран настроек показывает их отсюда же.
     */
    fun limitsFor(mode: SwarmMode, metered: Boolean, lowPower: Boolean): SwarmLimits {
        val (concurrent, perMinute) = when (mode) {
            // «Без ограничений» сохраняет защитный потолок, а не бесконечную нагрузку.
            // Ёмкость очереди не доказывает пропускную способность.
            // Мобильный интернет и заряд этот режим не сбавляют -
            // человек сам так решил.
            SwarmMode.UNLIMITED -> return SwarmLimits(
                maxConcurrentSends = UNLIMITED_CONCURRENT,
                maxPacketsPerMinute = UNLIMITED_PACKETS_PER_MINUTE,
                maxSignalsPerMinute = UNLIMITED_PACKETS_PER_MINUTE / SIGNAL_SHARE,
            )
            SwarmMode.NORMAL -> 8 to 300
            SwarmMode.ECONOMY -> 4 to 120
        }
        var factor = 1.0
        if (metered) factor *= METERED_FACTOR
        if (lowPower) factor *= LOW_BATTERY_FACTOR
        val packets = (perMinute * factor).toInt().coerceAtLeast(MIN_PACKETS_PER_MINUTE)
        val sends = (concurrent * factor).toInt().coerceAtLeast(MIN_CONCURRENT)
        val signals = (packets / SIGNAL_SHARE).coerceAtLeast(MIN_PACKETS_PER_MINUTE / SIGNAL_SHARE)
        return SwarmLimits(
            maxConcurrentSends = sends,
            maxPacketsPerMinute = packets,
            maxSignalsPerMinute = signals,
        )
    }
}


/** Stage 4 — bounded, process-local contribution counters (not capacity measurement). */
object SwarmContributionStats {
    const val MAX_OBSERVED_PEERS = 256
    const val REFUSAL_WINDOW_MS = 30_000L
    const val MAX_REFUSALS_PER_WINDOW = 10
    private const val MAX_PEER_ID_CHARS = 256

    private val lock = Any()
    private var delivered = 0L
    private var refused = 0L
    private var lastClockMs: Long? = null
    // Access-order LRU: unique untrusted peer IDs cannot grow this map without bound.
    private val observedPeerDeliveries = java.util.LinkedHashMap<String, Long>(16, 0.75f, true)
    private val recentRefusals = java.util.ArrayDeque<Long>()

    fun noteDelivered() = synchronized(lock) {
        delivered = incrementSaturated(delivered)
    }

    /**
     * Record a locally observed peer delivery. At capacity, the least-recently-used peer is
     * evicted; invalid/unbounded identifiers are ignored. This is a bounded local sample only.
     */
    fun notePeer(peerId: String): Boolean = synchronized(lock) {
        if (peerId.length !in 1..MAX_PEER_ID_CHARS) return@synchronized false
        val previous = observedPeerDeliveries[peerId]
        if (previous != null) {
            observedPeerDeliveries[peerId] = incrementSaturated(previous)
            return@synchronized true
        }
        if (observedPeerDeliveries.size >= MAX_OBSERVED_PEERS) {
            val oldest = observedPeerDeliveries.entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        observedPeerDeliveries[peerId] = 1L
        true
    }

    fun deliveredCount(): Long = synchronized(lock) { delivered }
    fun refusedCount(): Long = synchronized(lock) { refused }
    fun peerCountsSnapshot(): Map<String, Long> = synchronized(lock) {
        java.util.LinkedHashMap<String, Long>(observedPeerDeliveries)
    }

    /**
     * Count a refusal and return whether it is inside the local rolling limit. Only the first
     * [MAX_REFUSALS_PER_WINDOW] events are retained in a [REFUSAL_WINDOW_MS] window, so rejected
     * excess attempts cannot grow the queue. Callers must act on `false` for this to be a guard.
     */
    fun recordRefusal(nowMs: Long): Boolean = synchronized(lock) {
        refused = incrementSaturated(refused)
        val now = advanceClock(nowMs) ?: return@synchronized false
        evictOldRefusals(now)
        if (recentRefusals.size >= MAX_REFUSALS_PER_WINDOW) return@synchronized false
        recentRefusals.addLast(now)
        true
    }

    /** Whether another refusal can be admitted under the local rolling window. */
    fun refusalRateOk(nowMs: Long): Boolean = synchronized(lock) {
        val now = advanceClock(nowMs) ?: return@synchronized false
        evictOldRefusals(now)
        recentRefusals.size < MAX_REFUSALS_PER_WINDOW
    }

    fun observedPeerCount(): Int = synchronized(lock) { observedPeerDeliveries.size }
    fun observedPeerLimitReached(): Boolean = synchronized(lock) {
        observedPeerDeliveries.size >= MAX_OBSERVED_PEERS
    }

    fun resetLocal() = synchronized(lock) {
        delivered = 0L
        refused = 0L
        observedPeerDeliveries.clear()
        recentRefusals.clear()
        lastClockMs = null
    }

    /** Reject invalid clock values and clamp clock rollback to a monotonic high-water mark. */
    private fun advanceClock(nowMs: Long): Long? {
        if (nowMs < 0L) return null
        val previous = lastClockMs
        val monotonic = if (previous == null || nowMs > previous) nowMs else previous
        lastClockMs = monotonic
        return monotonic
    }

    private fun evictOldRefusals(nowMs: Long) {
        val cutoff = (nowMs - REFUSAL_WINDOW_MS).coerceAtLeast(0L)
        while (recentRefusals.peekFirst()?.let { it < cutoff } == true) {
            recentRefusals.removeFirst()
        }
    }

    private fun incrementSaturated(value: Long): Long =
        if (value == Long.MAX_VALUE) value else value + 1L
}
