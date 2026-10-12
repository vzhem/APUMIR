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


/** Stage 4 — bounded contribution statistics (not measurement of capacity). */
object SwarmContributionStats {
    // Only counts already-completed local deliveries / receipts / refusals.
    // No claim of global uniqueness, honesty, or measured capacity.
    private val delivered = java.util.concurrent.atomic.AtomicLong(0)
    private val refused = java.util.concurrent.atomic.AtomicLong(0)
    private val observedPeerDeliveries = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun noteDelivered() { delivered.incrementAndGet() }
    fun noteRefused() { refused.incrementAndGet() }
    fun notePeer(peerId: String) { observedPeerDeliveries.merge(peerId, 1L, Long::plus) }

    fun deliveredCount(): Long = delivered.get()
    fun refusedCount(): Long = refused.get()
    fun peerCountsSnapshot(): Map<String, Long> = observedPeerDeliveries.toMap()
    fun resetLocal() {
        delivered.set(0); refused.set(0); observedPeerDeliveries.clear()
    }
    // Stage 4 anti-abuse / rate guard (bounded; not proof of global honesty)
    private val recentRefusals = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    private val refusalWindowMs = 30000L
    private val maxRefusalsPerWindow = 10
    fun recordRefusal(nowMs: Long): Boolean {
        refused.incrementAndGet()
        recentRefusals.add(nowMs)
        val cutoff = nowMs - refusalWindowMs
        while (recentRefusals.peek() != null && recentRefusals.peek()!! < cutoff) recentRefusals.poll()
        return recentRefusals.size <= maxRefusalsPerWindow
    }
    fun refusalRateOk(nowMs: Long): Boolean {
        val cutoff = nowMs - refusalWindowMs
        while (recentRefusals.peek() != null && recentRefusals.peek()!! < cutoff) recentRefusals.poll()
        return recentRefusals.size <= maxRefusalsPerWindow
    }
    fun observedPeerCount(): Int = observedPeerDeliveries.size
    fun observedPeerLimitReached(): Boolean = observedPeerDeliveries.size >= 256 // bounded guard only, not capacity claim

}
