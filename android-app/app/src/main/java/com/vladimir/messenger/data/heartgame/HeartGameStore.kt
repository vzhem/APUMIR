package com.vladimir.messenger.data.heartgame

import android.content.Context
import android.os.SystemClock
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.rank.RankProgressPolicy
import com.vladimir.messenger.data.rank.RankWire
import com.vladimir.messenger.data.referral.ReferralRankStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object HeartGameStore {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    val policy = HeartGamePolicy()
    private val lock = Mutex()
    private val scoreLock = Any()
    private const val SCORE_KEY = "score"
    private const val REFERRAL_BONUS_COUNT_KEY = "referral_bonus_count_v1"

    fun ownId(): String? = RankWire.canonicalNodeId(RustBridge.nodeId())
    suspend fun ownKeyId(context: Context): String? = withContext(Dispatchers.IO) {
        com.vladimir.messenger.data.security.IdentitySigningKeyStore.existingVerifiedBinding(context)?.let(HeartGameWire::keyId)
    }
    private fun prefs(context: Context, id: String) =
        context.applicationContext.getSharedPreferences("heart-game-v1-$id", Context.MODE_PRIVATE)

    fun enabled(context: Context, id: String) = prefs(context, id).getBoolean("enabled", false)

    /**
     * Общий счёт игры: пойманные сердечки плюс бонусы за подтверждённые
     * приглашения. Старые аккаунты лениво получают по 1 000 за каждое уже
     * подтверждённое приглашение; отдельная отметка делает это ровно один раз.
     */
    fun score(context: Context, id: String): Long = synchronized(scoreLock) {
        val earnedInvites = ReferralRankStore.earnedDirectCount(context)
        synchronizeReferralBonusesLocked(context, id, earnedInvites)
        rawScore(context, id)
    }

    /**
     * Досинхронизировать бонус приглашений сразу после его зачисления. Метод
     * идемпотентен: повторный пакет или повторный запуск не добавит второй
     * бонус. Если приложение упало между счётчиком приглашений и игрой,
     * следующий вызов [score] исправит состояние автоматически.
     */
    fun synchronizeReferralBonuses(
        context: Context,
        id: String?,
        earnedInvites: Int,
    ): Boolean {
        if (id.isNullOrBlank()) return false
        return synchronized(scoreLock) {
            synchronizeReferralBonusesLocked(context, id, earnedInvites)
        }
    }

    private fun synchronizeReferralBonusesLocked(
        context: Context,
        id: String,
        earnedInvites: Int,
    ): Boolean {
        val store = prefs(context, id)
        val target = earnedInvites.coerceIn(0, ReferralRankStore.MAX_SUPPORTED_COUNT)
        val credited = store.getInt(REFERRAL_BONUS_COUNT_KEY, 0).coerceAtLeast(0)
        if (target <= credited) return false
        val current = rawScore(context, id)
        val added = RankProgressPolicy.referralBonusHearts(target - credited)
        val next = (current + added).coerceAtMost(HeartGamePolicy.MAX_SCORE)
        val saved = store.edit()
            .putLong(SCORE_KEY, next)
            .putInt(REFERRAL_BONUS_COUNT_KEY, target)
            .commit()
        if (saved) changes.value++
        return saved
    }

    private fun rawScore(context: Context, id: String): Long =
        prefs(context, id).getLong(SCORE_KEY, 0L).coerceIn(0, HeartGamePolicy.MAX_SCORE)
    suspend fun setEnabled(context: Context, id: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            policy.cancel()
            prefs(context, id).edit().putBoolean("enabled", enabled).commit().also { if (it) changes.value++ }
        }
    }
    suspend fun catchHeart(context: Context, id: String, ticket: Long): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (ownId() != id || !enabled(context, id) || !policy.claim(ticket, SystemClock.elapsedRealtime())) return@withLock false
            val current = score(context, id)
            if (current >= HeartGamePolicy.MAX_SCORE) return@withLock false
            prefs(context, id).edit().putLong(SCORE_KEY, current + 1).commit().also { if (it) changes.value++ }
        }
    }
    // Bounded to 500 signed rows, names are part of the signed self-report.
    private val boards = mutableMapOf<String, List<HeartGameWire.Entry>>()
    suspend fun board(context: Context, id: String): List<HeartGameWire.Entry> = withContext(Dispatchers.IO) {
        lock.withLock { load(context, id).filter { it.updated >= System.currentTimeMillis() - HeartGameWire.RETENTION_MS } }
    }
    private fun load(context: Context, id: String): List<HeartGameWire.Entry> = boards.getOrPut(id) {
        if (boards.size >= 2) boards.clear()
        val raw = prefs(context, id).getString("board", "").orEmpty()
        if (raw.length > 600_000) emptyList() else raw.lineSequence().take(500).mapNotNull {
            HeartGameWire.verify(it, System.currentTimeMillis(), { bytes -> uniffi.p2p_core.verifyIdentitySigningBinding(bytes) })
        }.toList()
    }
    /**
     * Своё место в таблице лидеров.
     *
     * Раньше место считалось только при наличии подписанной привязки личности
     * ([ownKeyId]): без неё оба счётчика на экране печатали «место №—», хотя
     * игра включена и сердечки на месте. Привязка - про подпись чужих
     * достижений, а не про порядок в таблице, поэтому место теперь считается и
     * без неё: свой результат сравнивается по счёту с уже известными строками.
     *
     * При равенстве счёта мы не приписываем себе более высокое место: считаем
     * только тех, у кого счёта строго больше. Ошибка возможна лишь на «ничьей»
     * и только в одну строку - зато число правдивое, а не прочерк.
     *
     * @return место, начиная с 1, или null, если строку нельзя посчитать вовсе.
     */
    suspend fun ownPlace(
        context: Context,
        id: String,
        name: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Int? = withContext(Dispatchers.IO) {
        val rows = runCatching { board(context, id) }.getOrElse { return@withContext null }
        val ownKey = runCatching { ownKeyId(context) }.getOrNull()
        val ownScore = runCatching { score(context, id) }.getOrElse { return@withContext null }
        val own = ownKey?.let { HeartGameWire.Entry(id, name, ownScore, nowMs, "", it) }
        HeartGameWire.placeOf(rows, ownScore, own)
    }

    suspend fun merge(context: Context, id: String, incoming: List<HeartGameWire.Entry>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = System.currentTimeMillis()
            val map = load(context, id).filter { it.updated >= now - HeartGameWire.RETENTION_MS }.associateBy { it.keyId }.toMutableMap()
            val ownKey = ownKeyId(context)
            incoming.filter { it.keyId != ownKey }.forEach { if (HeartGameWire.newer(map[it.keyId], it)) map[it.keyId] = it }
            val next = HeartGameWire.ranked(map.values).take(500)
            if (next != boards[id] && prefs(context, id).edit().putString("board", next.joinToString("\n") { it.record }).commit()) {
                boards[id] = next
                changes.value++
            }
        }
    }
}
