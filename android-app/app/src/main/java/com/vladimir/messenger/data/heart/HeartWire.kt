package com.vladimir.messenger.data.heart

/**
 * Конверты рейтинга профиля:
 *  - положительный («сердечко профилю»): `APUHEART1|<чей профиль>|<1 поставил / 0 снял>|<когда>`
 *  - анти-рейтинг (жалоба/понижение репутации): `APUANTI1|<чей профиль>|<1 поставил / 0 снял>|<когда>`
 *
 * Кто поставил оценку, в конверте НЕ передаётся: отправитель известен транспорту, и
 * доверять имени из тела нельзя — иначе один человек накрутил бы чужой рейтинг или
 * анти-рейтинг, прислав пачку голосов от вымышленных узлов.
 */
object HeartWire {
    const val PREFIX = "APUHEART1"
    const val ANTI_PREFIX = "APUANTI1"
    const val ANTI_DB_PREFIX = "anti|"

    /**
     * Сколько жалоб, пришедших ПОДРЯД ЗА КОРОТКОЕ ВРЕМЯ, считаются всплеском.
     *
     * Одиночные отметки 👎 у разных людей в разное время - ещё не «плохой
     * профиль»: так друзья могут шутить. Предупреждение и понижение приоритета
     * включаются, только когда рейтинг просел быстро (см. [ANTI_BURST_WINDOW_MS]).
     */
    const val ANTI_BURST_THRESHOLD = 3

    /** Окно всплеска: сколько времени жалобы считаются пришедшими одновременно. */
    const val ANTI_BURST_WINDOW_MS = 6 * 60 * 60 * 1000L

    /**
     * Сколько действует предупреждение и пониженный приоритет после последней
     * жалобы всплеска. Это временная мера: дальше узел снова оценивается по
     * своему поведению, даже если жалобы никто не снимал.
     */
    const val ANTI_BURST_TTL_MS = 24 * 60 * 60 * 1000L

    /** Прощаем чужим часам небольшой разбег - иначе «из будущего» голос терялся бы. */
    private const val CLOCK_SKEW_MS = 5 * 60 * 1000L

    private const val MAX_ENVELOPE_CHARS = 512

    fun isHeartPacket(text: String?): Boolean =
        text != null &&
            text.length <= MAX_ENVELOPE_CHARS &&
            (text.startsWith("$PREFIX|") || text.startsWith("$ANTI_PREFIX|"))

    fun isAntiRatingPacket(text: String?): Boolean =
        text != null && text.length <= MAX_ENVELOPE_CHARS && text.startsWith("$ANTI_PREFIX|")

    fun antiStorageKey(ownerId: String): String = "$ANTI_DB_PREFIX${ownerId.trim()}"

    fun ownerFromAntiStorageKey(storageKey: String): String? =
        if (storageKey.startsWith(ANTI_DB_PREFIX)) {
            storageKey.removePrefix(ANTI_DB_PREFIX).takeIf { it.isNotBlank() }
        } else {
            null
        }

    /** Сколько жалоб пришло за окно [windowMs] до момента [nowMs]. */
    fun antiVotesInWindow(
        voteTimesMs: Collection<Long>,
        nowMs: Long,
        windowMs: Long = ANTI_BURST_WINDOW_MS,
    ): Int = voteTimesMs.count { time -> time > nowMs - windowMs && time <= nowMs + CLOCK_SKEW_MS }

    /**
     * До какого времени действует предупреждение о всплеске жалоб.
     *
     * Возвращает 0, если жалобы приходят по одной (всплеска нет): в этом случае
     * ни плашки, ни понижения приоритета нет. Если всплеск есть, срок считается
     * от последней жалобы всплеска, поэтому после него предупреждение само
     * проходит по времени.
     */
    fun antiWarningUntilMs(
        voteTimesMs: Collection<Long>,
        nowMs: Long,
        windowMs: Long = ANTI_BURST_WINDOW_MS,
        ttlMs: Long = ANTI_BURST_TTL_MS,
    ): Long {
        val recent = antiVotesInWindow(voteTimesMs, nowMs, windowMs)
        if (recent < ANTI_BURST_THRESHOLD) return 0L
        val last = voteTimesMs.filter { it > nowMs - windowMs && it <= nowMs + CLOCK_SKEW_MS }
            .maxOrNull() ?: return 0L
        val until = last + ttlMs
        return if (until > nowMs) until else 0L
    }

    /** Показывать ли предупреждающий значок и понижать ли приоритет узла сейчас. */
    fun isAntiWarningActive(voteTimesMs: Collection<Long>, nowMs: Long): Boolean =
        antiWarningUntilMs(voteTimesMs, nowMs) > nowMs

    fun build(ownerId: String, added: Boolean, atMs: Long, isAnti: Boolean = false): String? {
        if (ownerId.isBlank() || ownerId.contains('|')) return null
        val prefix = if (isAnti) ANTI_PREFIX else PREFIX
        return "$prefix|$ownerId|${if (added) 1 else 0}|$atMs"
    }

    fun buildAntiRating(ownerId: String, added: Boolean, atMs: Long): String? =
        build(ownerId = ownerId, added = added, atMs = atMs, isAnti = true)

    data class Packet(
        val ownerId: String,
        val added: Boolean,
        val atMs: Long,
        val isAnti: Boolean = false,
    )

    fun parse(text: String): Packet? {
        if (!isHeartPacket(text)) return null
        val parts = text.split('|')
        if (parts.size != 4) return null
        val isAnti = when (parts[0]) {
            PREFIX -> false
            ANTI_PREFIX -> true
            else -> return null
        }
        val ownerId = parts[1]
        val added = when (parts[2]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val atMs = parts[3].toLongOrNull() ?: return null
        if (ownerId.isBlank()) return null
        return Packet(ownerId = ownerId, added = added, atMs = atMs, isAnti = isAnti)
    }
}
