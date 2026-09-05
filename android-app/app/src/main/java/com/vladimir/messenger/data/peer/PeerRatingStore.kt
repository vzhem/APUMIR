package com.vladimir.messenger.data.peer

// =============================================================================
// PEERRATINGSTORE.KT — рейтинг узлов сети: кто из них надёжный ретранслятор
// =============================================================================
// Сеть APU не имеет центрального сервера, поэтому «сервером» по очереди
// работает любой телефон. Одни всегда в сети и отдают файлы быстро, другие
// появляются на минуту в сутки. Рейтинг отвечает на один вопрос: КОМУ отдать
// данные первым, чтобы они дошли.
//
// Считается локально, из того, что телефон видел сам. Никаких обещаний от
// чужой стороны: узел не может объявить себя хорошим - он может только вести
// себя хорошо, и это будет замечено.
// =============================================================================

import android.content.Context
import org.json.JSONObject

/**
 * Наблюдения об одном узле.
 *
 * Все величины накопительные и хранятся в обычных настройках: их немного
 * (сотни узлов), а переживать перезапуск они обязаны - иначе рейтинг
 * обнулялся бы при каждом старте и всегда был бы «никакой».
 */
data class PeerStats(
    val peerId: String,
    /** Сколько раз узел давал о себе знать. */
    val sightings: Long = 0,
    /** Сколько кругов проверки он при этом пропустил. */
    val misses: Long = 0,
    /** Когда его видели в последний раз. */
    val lastSeenMs: Long = 0,
    /** Байты, переданные с ним напрямую, и время на это. */
    val transferredBytes: Long = 0,
    val transferMillis: Long = 0,
    /** Удачные и неудачные попытки доставки. */
    val delivered: Long = 0,
    val failed: Long = 0,
    /**
     * Виден ли снаружи по прямому адресу.
     *
     * Это самый ценный признак: к такому узлу может подключиться кто угодно,
     * поэтому он годится в ретрансляторы для всей сети, а не только для тех,
     * кто с ним в одной домашней сети.
     */
    val hasPublicAddress: Boolean = false,
    /** Последний известный адрес - его показываем в подробностях. */
    val lastAddress: String = "",
) {

    /** Средняя скорость обмена, байт в секунду. Ноль - обмена не было. */
    val bytesPerSecond: Long
        get() = if (transferMillis <= 0) 0 else transferredBytes * 1000 / transferMillis

    /** Доля кругов, в которых узел был на месте: 0..1. */
    val availability: Double
        get() {
            val total = sightings + misses
            return if (total <= 0) 0.0 else sightings.toDouble() / total
        }

    /** Доля удачных доставок: 0..1. Если попыток не было - считаем нейтрально. */
    val reliability: Double
        get() {
            val total = delivered + failed
            return if (total <= 0) 0.5 else delivered.toDouble() / total
        }

    /**
     * Итоговый рейтинг 0..100.
     *
     * Веса подобраны под задачу «через кого слать в первую очередь»:
     *  - доступность (35) - самое важное: недоступный узел бесполезен,
     *    каким бы быстрым он ни был;
     *  - прямой адрес (25) - к такому узлу дотянутся все, а не только соседи
     *    по домашней сети; именно из таких получаются ретрансляторы;
     *  - скорость (20) - на ней экономится время передачи файлов;
     *  - надёжность доставки (15) - обещал и donёс;
     *  - свежесть (5) - узел, которого не видели неделю, не должен обгонять
     *    того, кто в сети прямо сейчас.
     */
    fun score(nowMs: Long): Int {
        val availabilityPart = availability * 35.0
        val publicPart = if (hasPublicAddress) 25.0 else 0.0
        // 2 МБ/с и выше считаем отличной скоростью: дальше разницы уже не
        // чувствуется, а редкий быстрый обмен не должен перевешивать всё.
        val speedPart = if (bytesPerSecond <= 0) {
            0.0
        } else {
            (bytesPerSecond.toDouble() / FAST_BYTES_PER_SECOND).coerceAtMost(1.0) * 20.0
        }
        val reliabilityPart = reliability * 15.0
        val ageMs = (nowMs - lastSeenMs).coerceAtLeast(0)
        val freshPart = when {
            lastSeenMs <= 0 -> 0.0
            ageMs <= FRESH_MS -> 5.0
            ageMs >= STALE_MS -> 0.0
            else -> 5.0 * (1.0 - (ageMs - FRESH_MS).toDouble() / (STALE_MS - FRESH_MS))
        }
        val total = availabilityPart + publicPart + speedPart + reliabilityPart + freshPart
        return total.coerceIn(0.0, 100.0).toInt()
    }

    /** Словесная оценка: цифра без объяснения владельцу ничего не говорит. */
    fun tier(nowMs: Long): String = when {
        score(nowMs) >= 75 -> "Отличный"
        score(nowMs) >= 50 -> "Хороший"
        score(nowMs) >= 25 -> "Средний"
        else -> "Слабый"
    }

    companion object {
        const val FAST_BYTES_PER_SECOND = 2L * 1024 * 1024
        const val FRESH_MS = 5L * 60 * 1000
        const val STALE_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * Хранилище наблюдений и источник упорядоченного списка узлов.
 *
 * Намеренно без Android-зависимостей внутри расчётов: разбор и подсчёт
 * рейтинга проверяются обычными JVM-тестами.
 */
object PeerRatingStore {

    private const val PREFS = "apu_peer_ratings"
    private const val KEY_DATA = "peer_stats_v1"
    /** Больше не храним: список узлов не должен разрастаться без предела. */
    private const val MAX_PEERS = 500

    private val lock = Any()

    /** Все наблюдения, отсортированные по рейтингу: лучший первым. */
    fun ranked(context: Context, nowMs: Long = System.currentTimeMillis()): List<PeerStats> =
        load(context).values.sortedWith(
            compareByDescending<PeerStats> { it.score(nowMs) }.thenBy { it.peerId }
        )

    /** Наблюдения об одном узле. */
    fun statsFor(context: Context, peerId: String): PeerStats? = load(context)[peerId]

    /**
     * Узлы, которым стоит отдавать данные первыми.
     *
     * Порядок именно такой: сначала пробуем лучших, потом остальных. Полностью
     * исключать слабых нельзя - иногда получатель доступен только через них.
     */
    fun preferredOrder(
        context: Context,
        candidates: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> {
        if (candidates.size <= 1) return candidates
        val known = load(context)
        return candidates.sortedWith(
            compareByDescending<String> { known[it]?.score(nowMs) ?: 0 }.thenBy { it }
        )
    }

    /** Узел дал о себе знать. */
    fun recordSighting(
        context: Context,
        peerId: String,
        address: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        update(context, peerId) { old ->
            val clean = address?.trim().orEmpty()
            old.copy(
                sightings = old.sightings + 1,
                lastSeenMs = nowMs,
                lastAddress = if (clean.isNotBlank()) clean else old.lastAddress,
                // Прямой адрес - свойство узла: если он однажды был виден
                // напрямую, признак сохраняем, иначе он мигал бы от круга к
                // кругу вместе с качеством связи.
                hasPublicAddress = old.hasPublicAddress || isPublicAddress(clean),
            )
        }
    }

    /**
     * Узел принял данные напрямую.
     *
     * Это доказательство достижимости: до него дотянулись без ретранслятора,
     * значит он годится в опорные узлы для остальных.
     */
    fun recordDirectReach(context: Context, peerId: String) {
        update(context, peerId) { old -> old.copy(hasPublicAddress = true) }
    }

    /** Узел пропустил круг проверки: считаем это против его доступности. */
    fun recordMiss(context: Context, peerId: String) {
        update(context, peerId) { old -> old.copy(misses = old.misses + 1) }
    }

    /** Обмен данными: сколько байт и за сколько времени. */
    fun recordTransfer(context: Context, peerId: String, bytes: Long, millis: Long) {
        if (bytes <= 0 || millis <= 0) return
        update(context, peerId) { old ->
            old.copy(
                transferredBytes = old.transferredBytes + bytes,
                transferMillis = old.transferMillis + millis,
            )
        }
    }

    /** Итог попытки доставки через этот узел. */
    fun recordDelivery(context: Context, peerId: String, success: Boolean) {
        update(context, peerId) { old ->
            if (success) old.copy(delivered = old.delivered + 1) else old.copy(failed = old.failed + 1)
        }
    }

    /**
     * Похож ли адрес на доступный извне.
     *
     * Домашние и служебные диапазоны исключаем: узел за домашним роутером
     * виден только соседям по этой же сети, и ретранслятора для всей сети из
     * него не выйдет.
     */
    fun isPublicAddress(address: String): Boolean {
        val host = address.substringBeforeLast(':', address).trim().trim('[', ']')
        if (host.isBlank()) return false
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets[0] to octets[1]
        return when {
            a == 10 -> false
            a == 127 -> false
            a == 0 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 169 && b == 254 -> false
            a == 100 && b in 64..127 -> false
            a >= 224 -> false
            else -> true
        }
    }

    /** Забыть всё: пункт «сбросить» в подробностях. */
    fun clear(context: Context) {
        synchronized(lock) {
            prefs(context).edit().remove(KEY_DATA).apply()
        }
    }

    private fun update(context: Context, peerId: String, change: (PeerStats) -> PeerStats) {
        if (peerId.isBlank()) return
        synchronized(lock) {
            val all = load(context).toMutableMap()
            val old = all[peerId] ?: PeerStats(peerId)
            all[peerId] = change(old)
            save(context, all)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): Map<String, PeerStats> {
        val raw = prefs(context).getString(KEY_DATA, null) ?: return emptyMap()
        return runCatching { decode(raw) }.getOrElse { emptyMap() }
    }

    private fun save(context: Context, all: Map<String, PeerStats>) {
        // Держим только лучших: список чужих узлов теоретически безграничен,
        // а настройки - не база данных.
        val trimmed = if (all.size <= MAX_PEERS) {
            all
        } else {
            val now = System.currentTimeMillis()
            all.entries
                .sortedByDescending { it.value.score(now) }
                .take(MAX_PEERS)
                .associate { it.key to it.value }
        }
        prefs(context).edit().putString(KEY_DATA, encode(trimmed)).apply()
    }

    /** Разбор хранимого вида. Вынесен отдельно, чтобы проверяться тестом. */
    fun decode(raw: String): Map<String, PeerStats> {
        val root = JSONObject(raw)
        val result = LinkedHashMap<String, PeerStats>()
        for (key in root.keys()) {
            val o = root.optJSONObject(key) ?: continue
            result[key] = PeerStats(
                peerId = key,
                sightings = o.optLong("s"),
                misses = o.optLong("m"),
                lastSeenMs = o.optLong("t"),
                transferredBytes = o.optLong("b"),
                transferMillis = o.optLong("d"),
                delivered = o.optLong("ok"),
                failed = o.optLong("no"),
                hasPublicAddress = o.optBoolean("pub"),
                lastAddress = o.optString("addr", ""),
            )
        }
        return result
    }

    /** Сборка хранимого вида. */
    fun encode(all: Map<String, PeerStats>): String {
        val root = JSONObject()
        for ((key, v) in all) {
            root.put(
                key,
                JSONObject()
                    .put("s", v.sightings)
                    .put("m", v.misses)
                    .put("t", v.lastSeenMs)
                    .put("b", v.transferredBytes)
                    .put("d", v.transferMillis)
                    .put("ok", v.delivered)
                    .put("no", v.failed)
                    .put("pub", v.hasPublicAddress)
                    .put("addr", v.lastAddress),
            )
        }
        return root.toString()
    }
}
