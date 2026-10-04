package com.vladimir.messenger.data.heart

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ProfileHeartDao
import com.vladimir.messenger.data.local.entity.ProfileHeartEntity
import com.vladimir.messenger.data.mirror.MirrorHub
import com.vladimir.messenger.data.peer.PeerRatingStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Анти-рейтинг профиля в карточке: сколько всего жалоб, сколько пришло за
 * последние часы и до какого времени действует предупреждение.
 *
 * Предупреждение не зависит от простого числа жалоб: одиночные отметки 👎
 * (например, шутка друга) рейтинг не роняют. Всплеск - это несколько жалоб,
 * пришедших за короткое окно (см. [HeartWire.ANTI_BURST_WINDOW_MS]), и он
 * действует ограниченное время.
 */
data class AntiRatingState(
    val total: Int = 0,
    val recent: Int = 0,
    val warningUntilMs: Long = 0,
) {
    val warning: Boolean get() = warningUntilMs > System.currentTimeMillis()
}

/**
 * Сердечки и анти-рейтинг профилям: показатель репутации человека в сети.
 *
 * Один человек может поставить сердечко или анти-рейтинг другому ровно один раз —
 * это обеспечивается парой «чей профиль + кто поставил» в первичном ключе, а не
 * счётчиком. Повторное нажатие снимает оценку.
 *
 * Анти-рейтинг хранится в той же таблице `profile_hearts` под ключом
 * `anti|<ownerId>` (см. [HeartWire.antiStorageKey]), без миграции схемы Room,
 * и при накоплении голосов понижает приоритет узла в рое ([PeerRatingStore]).
 *
 * Кто голосовал, берётся из адреса отправителя пакета, а не из его тела:
 * иначе один узел прислал бы пачку голосов от вымышленных имён.
 */
@Singleton
class HeartRepository @Inject constructor(
    private val heartDao: ProfileHeartDao,
    @ApplicationContext private val appContext: Context,
) {
    /** Сколько сердечек у профиля - для показа в карточке. */
    fun observeCount(ownerId: String): Flow<Int> = heartDao.observeCount(ownerId)

    suspend fun countOf(ownerId: String): Int = withContext(Dispatchers.IO) {
        runCatching { heartDao.countOf(ownerId) }.getOrDefault(0)
    }

    /** Ставил ли я сердечко этому профилю. */
    suspend fun isMine(ownerId: String): Boolean = withContext(Dispatchers.IO) {
        val me = RustBridge.nodeId().orEmpty()
        if (me.isBlank()) return@withContext false
        runCatching { heartDao.hasVote(ownerId, me) > 0 }.getOrDefault(false)
    }

    /** Сколько анти-рейтингов (жалоб) у профиля. */
    fun observeAntiCount(ownerId: String): Flow<Int> =
        heartDao.observeCount(HeartWire.antiStorageKey(ownerId)).flowOn(Dispatchers.IO)

    suspend fun antiCountOf(ownerId: String): Int = withContext(Dispatchers.IO) {
        runCatching { heartDao.countOf(HeartWire.antiStorageKey(ownerId)) }.getOrDefault(0)
    }

    /** Ставил ли я анти-рейтинг этому профилю. */
    suspend fun isMyAntiRating(ownerId: String): Boolean = withContext(Dispatchers.IO) {
        val me = RustBridge.nodeId().orEmpty()
        if (me.isBlank()) return@withContext false
        runCatching { heartDao.hasVote(HeartWire.antiStorageKey(ownerId), me) > 0 }.getOrDefault(false)
    }

    /** Карта анти-рейтинга по всем профилям (`ownerId -> count`) для ленты групп и каналов. */
    fun observeAntiCountsMap(): Flow<Map<String, Int>> =
        heartDao.observeAllAntiCounts()
            .map { rows ->
                rows.mapNotNull { row ->
                    val peerId = HeartWire.ownerFromAntiStorageKey(row.ownerId) ?: return@mapNotNull null
                    if (row.count > 0) peerId to row.count else null
                }.toMap()
            }
            .flowOn(Dispatchers.IO)

    /** Кому этот телефон поставил анти-рейтинг (`Set<ownerId>`). */
    fun observeMyAntiTargets(voterId: String): Flow<Set<String>> =
        heartDao.observeMyAntiVotes(voterId)
            .map { keys -> keys.mapNotNull { HeartWire.ownerFromAntiStorageKey(it) }.toSet() }
            .flowOn(Dispatchers.IO)

    /**
     * Состояние анти-рейтинга одного профиля для карточки.
     *
     * Пересчитывается раз в минуту: временное предупреждение должно исчезнуть
     * само, без нового пакета из сети.
     */
    fun observeAntiState(ownerId: String): Flow<AntiRatingState> {
        val key = HeartWire.antiStorageKey(ownerId)
        return combine(heartDao.observeVoteTimes(key), minuteTicker()) { times, _ ->
            antiStateOf(times, System.currentTimeMillis())
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Профили, у которых прямо сейчас действует предупреждение о всплеске жалоб
     * (`peerId -> до какого времени`). Для плашек в ленте сообщений и постов.
     */
    fun observeAntiWarnings(): Flow<Map<String, Long>> =
        combine(heartDao.observeAllAntiVotes(), minuteTicker()) { votes, _ ->
            antiWarningsOf(votes, System.currentTimeMillis())
        }.flowOn(Dispatchers.IO)

    /** Разовая проверка состояния: нужна при открытии карточки профиля. */
    suspend fun antiStateOf(ownerId: String): AntiRatingState = withContext(Dispatchers.IO) {
        val times = runCatching { heartDao.voteTimesOf(HeartWire.antiStorageKey(ownerId)) }
            .getOrDefault(emptyList())
        antiStateOf(times, System.currentTimeMillis())
    }

    /** Раз в минуту - чтобы истёкшее предупреждение пропало на открытом экране. */
    private fun minuteTicker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000L)
        }
    }

    private fun antiStateOf(times: List<Long>, nowMs: Long): AntiRatingState {
        val until = HeartWire.antiWarningUntilMs(times, nowMs)
        return AntiRatingState(
            total = times.size,
            recent = HeartWire.antiVotesInWindow(times, nowMs),
            warningUntilMs = if (until > nowMs) until else 0L,
        )
    }

    private fun antiWarningsOf(
        votes: List<ProfileHeartEntity>,
        nowMs: Long,
    ): Map<String, Long> {
        val byPeer = LinkedHashMap<String, MutableList<Long>>()
        for (vote in votes) {
            val peerId = HeartWire.ownerFromAntiStorageKey(vote.ownerId) ?: continue
            byPeer.getOrPut(peerId) { mutableListOf() }.add(vote.atMs)
        }
        val active = LinkedHashMap<String, Long>()
        for ((peerId, times) in byPeer) {
            val until = HeartWire.antiWarningUntilMs(times, nowMs)
            if (until > nowMs) active[peerId] = until
        }
        return active
    }

    /** Пересчитать хранимый штраф роя по всем жалобам профиля. */
    private suspend fun rememberAntiRating(ownerId: String, storageKey: String) {
        val times = runCatching { heartDao.voteTimesOf(storageKey) }.getOrDefault(emptyList())
        PeerRatingStore.recordAntiRating(appContext, ownerId, times)
    }

    /**
     * Поставить или снять сердечко.
     *
     * Сначала записываем у себя, чтобы значок откликнулся сразу, и только
     * потом сообщаем владельцу профиля: без сети сердечко всё равно видно.
     *
     * @return новое состояние: true - сердечко стоит.
     */
    suspend fun toggle(ownerId: String): Boolean = withContext(Dispatchers.IO) {
        val me = RustBridge.nodeId().orEmpty()
        if (me.isBlank() || ownerId.isBlank() || ownerId == me) {
            // Себе сердечко не ставится: это ровно та накрутка, от которой
            // рейтинг и защищаем.
            return@withContext false
        }
        val had = runCatching { heartDao.hasVote(ownerId, me) > 0 }.getOrDefault(false)
        val now = System.currentTimeMillis()
        runCatching {
            if (had) {
                heartDao.remove(ownerId, me)
            } else {
                heartDao.put(ProfileHeartEntity(ownerId = ownerId, voterId = me, atMs = now))
            }
        }.onFailure { Log.w(TAG, "heart toggle failed: ${it.message}") }

        val added = !had
        // Владельцу профиля - чтобы счётчик поднялся и у него.
        runCatching {
            HeartWire.build(ownerId, added, now)?.let { envelope ->
                // р228: тень отдаёт сердечко активному - своей сессии у неё нет.
                if (!MirrorHub.deliverAction(peerId = ownerId, groupId = "", chatId = ownerId, text = envelope)) {
                    RustBridge.sendMessage(UUID.randomUUID().toString(), ownerId, ownerId, envelope)
                    // р229: своё сердечко - и партнёрскому устройству личности.
                    MirrorHub.publishOwnAction(peerId = ownerId, groupId = "", chatId = ownerId, text = envelope)
                }
            }
        }.onFailure { Log.w(TAG, "heart send failed: ${it.message}") }
        added
    }

    /**
     * Поставить или снять анти-рейтинг профилю.
     *
     * @param extraPeerIds дополнительные получатели (например, участники группы/канала),
     *                     чтобы анти-рейтинг нарушителя сразу увидели все участники сообщества.
     * @return новое состояние: true - анти-рейтинг стоит.
     */
    suspend fun toggleAntiRating(
        ownerId: String,
        extraPeerIds: Collection<String> = emptyList(),
    ): Boolean = withContext(Dispatchers.IO) {
        val me = RustBridge.nodeId().orEmpty()
        if (me.isBlank() || ownerId.isBlank() || ownerId == me) {
            return@withContext false
        }
        val antiKey = HeartWire.antiStorageKey(ownerId)
        val had = runCatching { heartDao.hasVote(antiKey, me) > 0 }.getOrDefault(false)
        setAntiRatingInternal(ownerId = ownerId, me = me, added = !had, extraPeerIds = extraPeerIds)
    }

    /**
     * Явно выставить анти-рейтинг профилю (например, из диалога модерации при удалении сообщения).
     */
    suspend fun addAntiRating(
        ownerId: String,
        extraPeerIds: Collection<String> = emptyList(),
    ): Boolean = withContext(Dispatchers.IO) {
        val me = RustBridge.nodeId().orEmpty()
        if (me.isBlank() || ownerId.isBlank() || ownerId == me) {
            return@withContext false
        }
        setAntiRatingInternal(ownerId = ownerId, me = me, added = true, extraPeerIds = extraPeerIds)
    }

    private suspend fun setAntiRatingInternal(
        ownerId: String,
        me: String,
        added: Boolean,
        extraPeerIds: Collection<String>,
    ): Boolean {
        val antiKey = HeartWire.antiStorageKey(ownerId)
        val now = System.currentTimeMillis()
        runCatching {
            if (added) {
                heartDao.put(ProfileHeartEntity(ownerId = antiKey, voterId = me, atMs = now))
            } else {
                heartDao.remove(antiKey, me)
            }
            rememberAntiRating(ownerId, antiKey)
        }.onFailure { Log.w(TAG, "anti-rating save failed: ${it.message}") }

        runCatching {
            HeartWire.buildAntiRating(ownerId, added, now)?.let { envelope ->
                if (!MirrorHub.deliverAction(peerId = ownerId, groupId = "", chatId = ownerId, text = envelope)) {
                    val targets = LinkedHashSet<String>()
                    targets.add(ownerId)
                    for (peer in extraPeerIds) {
                        if (peer.isNotBlank() && peer != me) {
                            targets.add(peer)
                        }
                    }
                    for (peer in targets) {
                        runCatching {
                            RustBridge.sendMessage(UUID.randomUUID().toString(), peer, peer, envelope)
                        }
                    }
                    MirrorHub.publishOwnAction(peerId = ownerId, groupId = "", chatId = ownerId, text = envelope)
                }
            }
        }.onFailure { Log.w(TAG, "anti-rating send failed: ${it.message}") }
        return added
    }

    /**
     * р228: сердечко или анти-рейтинг поставлены на ПАРТНЁРСКОМ устройстве —
     * активный пишет их у себя от СВОЕГО имени. В сеть ничего не уходит.
     */
    suspend fun applyMirrorOutgoing(text: String): Boolean {
        if (!HeartWire.isHeartPacket(text)) return false
        val packet = HeartWire.parse(text) ?: return true
        val me = RustBridge.nodeId().orEmpty()
        if (me.isBlank() || packet.ownerId.isBlank()) return true
        val storageKey = if (packet.isAnti) HeartWire.antiStorageKey(packet.ownerId) else packet.ownerId
        withContext(Dispatchers.IO) {
            runCatching {
                if (packet.added) {
                    heartDao.put(
                        ProfileHeartEntity(ownerId = storageKey, voterId = me, atMs = packet.atMs)
                    )
                } else {
                    heartDao.remove(storageKey, me)
                }
                if (packet.isAnti) {
                    rememberAntiRating(packet.ownerId, storageKey)
                }
            }.onFailure { Log.w(TAG, "mirror heart apply failed: ${it.message}") }
        }
        return true
    }

    suspend fun routeIncoming(senderId: String, text: String): Boolean {
        if (!HeartWire.isHeartPacket(text)) return false
        val packet = HeartWire.parse(text)
        if (packet == null) {
            Log.w(TAG, "heart packet from $senderId is malformed, dropped")
            return true
        }
        if (senderId.isBlank() || senderId == packet.ownerId) {
            // Голос за самого себя отбрасываем: накрутка.
            return true
        }
        val storageKey = if (packet.isAnti) HeartWire.antiStorageKey(packet.ownerId) else packet.ownerId
        withContext(Dispatchers.IO) {
            runCatching {
                if (packet.added) {
                    heartDao.put(
                        ProfileHeartEntity(
                            ownerId = storageKey,
                            voterId = senderId,
                            atMs = packet.atMs,
                        )
                    )
                } else {
                    heartDao.remove(storageKey, senderId)
                }
                if (packet.isAnti) {
                    rememberAntiRating(packet.ownerId, storageKey)
                }
            }.onFailure { Log.w(TAG, "heart apply failed: ${it.message}") }
        }
        return true
    }

    private companion object {
        const val TAG = "HeartRepository"
    }
}
