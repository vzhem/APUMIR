package com.vladimir.messenger.data.channel

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.local.dao.PostViewDao
import com.vladimir.messenger.data.local.entity.PostViewEntity
import com.vladimir.messenger.data.swarm.SwarmBudget
import com.vladimir.messenger.data.swarm.SwarmLane
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Просмотры постов канала.
 *
 * Считаем не открытия, а РАЗНЫХ читателей: пара «пост + читатель» в первичном
 * ключе. Перечитывание поста и повтор пакета по сети счётчик не двигают.
 *
 * Кто прочитал, берём из отправителя пакета, а не из его тела - иначе один
 * узел накрутил бы себе просмотры от вымышленных имён.
 *
 * На большом канале (рой, этап 3) просмотр уходит не всем подписчикам, а
 * владельцу и администраторам; сводные числа читатели получают от них
 * ([PostCounterRepository]).
 */
@Singleton
class PostViewRepository @Inject constructor(
    private val postViewDao: PostViewDao,
    private val groupDao: GroupDao,
    private val swarmBudget: SwarmBudget,
    private val counters: PostCounterRepository,
) {
    /** Счётчики по всем постам: ключ - тема поста. */
    fun observeCounts(): Flow<Map<String, Int>> =
        postViewDao.observeAll().map { rows ->
            rows.groupingBy { it.topicId }.eachCount()
        }

    /**
     * Отметить пост прочитанным и сообщить об этом остальным.
     *
     * Свой просмотр записываем всегда, рассылаем - только первый раз: дальше
     * это лишний трафик, счётчик у собеседников уже поднят.
     */
    suspend fun markViewed(channelId: String, topicId: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val me = RustBridge.nodeId().orEmpty()
                if (me.isBlank() || topicId.isBlank()) return@runCatching

                // Уже отмечали? Тогда пакет не шлём: счётчик у всех поднят.
                val already = postViewDao.hasView(topicId, me) > 0
                postViewDao.put(
                    PostViewEntity(topicId = topicId, viewerId = me, atMs = System.currentTimeMillis())
                )
                if (already) return@runCatching

                val envelope = PostViewWire.build(topicId, System.currentTimeMillis())
                    ?: return@runCatching
                val group = groupDao.getGroupById(channelId) ?: return@runCatching
                // Рассылаем участникам канала: у каждого свой счётчик, и он
                // должен сойтись с нашим. Просмотр - служебный пакет: сначала
                // своим и проверенным, а когда служебный бюджет телефона
                // исчерпан - остальным не шлём (счётчик у них чуть ниже, зато
                // посты и сообщения не ждут за просмотрами). На большом канале
                // получатели - только владелец и администраторы: у них
                // счётчик сходится, остальные спросят сводку.
                val recipients = counters.signalTargets(group, me)
                var sent = 0
                for (peer in recipients) {
                    if (!swarmBudget.tryAcquire(SwarmLane.SIGNAL)) break
                    RustBridge.sendMessage(UUID.randomUUID().toString(), channelId, peer, envelope)
                    sent++
                }
                if (sent < recipients.size) {
                    Log.i(TAG, "view fanout capped: $sent/${recipients.size} (signal budget)")
                }
            }.onFailure { Log.w(TAG, "view mark failed: ${it.message}") }
        }
    }

    /**
     * Входящий просмотр.
     *
     * @return true, если это был пакет просмотра - тогда служба не сохраняет
     *         его как текст переписки.
     */
    suspend fun routeIncoming(senderId: String, text: String): Boolean {
        if (!PostViewWire.isViewPacket(text)) return false
        val packet = PostViewWire.parse(text)
        if (packet == null) {
            Log.w(TAG, "view packet from $senderId is malformed, dropped")
            return true
        }
        if (senderId.isBlank()) return true
        withContext(Dispatchers.IO) {
            runCatching {
                postViewDao.put(
                    PostViewEntity(
                        topicId = packet.topicId,
                        viewerId = senderId,
                        atMs = packet.atMs,
                    )
                )
            }.onFailure { Log.w(TAG, "view apply failed: ${it.message}") }
        }
        return true
    }

    private companion object {
        const val TAG = "PostViews"
    }
}
