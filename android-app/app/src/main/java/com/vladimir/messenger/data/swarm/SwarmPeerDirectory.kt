package com.vladimir.messenger.data.swarm

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.FileExchangePeerDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.NicknameDao
import com.vladimir.messenger.data.peer.PeerRatingStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Снимок знаний о соседях для [SwarmPolicy]: кто «свой», кто «проверенный»,
 * кто «стабильный». Собирается из того, что телефон уже знает - по сети
 * ничего нового не ходит.
 *
 * Снимок кэшируется на [TTL_MS]: веер зовёт порядок на каждый пакет (пост с
 * шестью фото - 19 вееров подряд), а четыре запроса к базе на каждый - лишняя
 * работа. Смена контактов/ролей подхватится в течение минуты, что для
 * порядка обхода несущественно.
 */
@Singleton
class SwarmPeerDirectory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactDao: ContactDao,
    private val nicknameDao: NicknameDao,
    private val fileExchangePeerDao: FileExchangePeerDao,
    private val groupDao: GroupDao,
) {
    private val mutex = Mutex()
    private var cached: PeerKnowledge = PeerKnowledge.EMPTY
    private var cachedAtMs = 0L

    /** Текущий снимок (из кэша или свежий). Никогда не бросает: в худшем случае - прежний или пустой. */
    suspend fun knowledge(nowMs: Long = System.currentTimeMillis()): PeerKnowledge {
        return mutex.withLock {
            if (cachedAtMs != 0L && nowMs - cachedAtMs < TTL_MS) return@withLock cached
            cached = runCatching { collect(nowMs) }
                .onFailure { Log.w(TAG, "peer knowledge unavailable: ${it.message}") }
                .getOrDefault(cached)
            cachedAtMs = nowMs
            cached
        }
    }

    /** Ярус одного узла - для отладки и экрана «Узлы сети». */
    suspend fun tierOf(nodeId: String): PeerTier = SwarmPolicy.tierOf(nodeId, knowledge())

    /** Порядок обхода получателей по ярусу и рейтингу. */
    suspend fun order(candidates: List<String>): List<String> =
        SwarmPolicy.order(candidates, knowledge())

    /** Забыть кэш: после добавления контакта или смены роли, если нужно сразу. */
    suspend fun invalidate() {
        mutex.withLock { cachedAtMs = 0L }
    }

    private suspend fun collect(nowMs: Long): PeerKnowledge {
        val contacts = contactDao.allIds().filter { it.isNotBlank() }.toHashSet()
        val verified = HashSet<String>()
        fileExchangePeerDao.getAll().mapTo(verified) { it.nodeId }
        nicknameDao.allOwnerIds().filterTo(verified) { it.isNotBlank() }
        // Владельцы и админы сообществ, где я состою: они держат ленту, их
        // пакеты нужны всем - им первым, у них первых.
        val privileged = HashSet<String>()
        groupDao.getAllAdminIds().filterTo(privileged) { it.isNotBlank() }
        val scores = HashMap<String, Int>()
        PeerRatingStore.ranked(context, nowMs).forEach { scores[it.peerId] = it.score(nowMs) }
        return PeerKnowledge(
            contacts = contacts,
            verified = verified,
            privileged = privileged,
            scores = scores,
        )
    }

    companion object {
        private const val TAG = "SwarmPeerDirectory"
        const val TTL_MS = 60_000L
    }
}
