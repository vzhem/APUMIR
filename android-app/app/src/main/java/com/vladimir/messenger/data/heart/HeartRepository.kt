package com.vladimir.messenger.data.heart

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ProfileHeartDao
import com.vladimir.messenger.data.local.entity.ProfileHeartEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Сердечки профилям: показатель того, скольким людям человек понравился.
 *
 * Один человек может поставить сердечко другому ровно один раз - это
 * обеспечивается парой «чей профиль + кто поставил» в первичном ключе, а не
 * счётчиком. Повторное нажатие снимает сердечко.
 *
 * Кто голосовал, берётся из адреса отправителя пакета, а не из его тела:
 * иначе один узел прислал бы пачку голосов от вымышленных имён.
 */
@Singleton
class HeartRepository @Inject constructor(
    private val heartDao: ProfileHeartDao,
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
                RustBridge.sendMessage(UUID.randomUUID().toString(), ownerId, ownerId, envelope)
            }
        }.onFailure { Log.w(TAG, "heart send failed: ${it.message}") }
        added
    }

    /**
     * Входящее сердечко.
     *
     * @return true, если это был пакет сердечка - тогда служба не сохраняет
     *         его как текст переписки.
     */
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
        withContext(Dispatchers.IO) {
            runCatching {
                if (packet.added) {
                    heartDao.put(
                        ProfileHeartEntity(
                            ownerId = packet.ownerId,
                            voterId = senderId,
                            atMs = packet.atMs,
                        )
                    )
                } else {
                    heartDao.remove(packet.ownerId, senderId)
                }
            }.onFailure { Log.w(TAG, "heart apply failed: ${it.message}") }
        }
        return true
    }

    private companion object {
        const val TAG = "HeartRepository"
    }
}
