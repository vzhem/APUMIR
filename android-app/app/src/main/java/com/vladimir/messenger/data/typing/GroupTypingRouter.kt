package com.vladimir.messenger.data.typing

import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.channel.PostCounterRepository
import com.vladimir.messenger.data.group.GroupWire
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.mirror.MirrorHub
import com.vladimir.messenger.data.swarm.SwarmBudget
import com.vladimir.messenger.data.swarm.SwarmLane
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * р249: «печатает…» в группах и темах канала.
 *
 * Личный индикатор (р235) уходит узлу-собеседнику пакетом `APUTYP1`; для
 * группы он не годился - там получателей много, а пакет старые версии
 * приложения показали бы текстом в чате. Поэтому групповой индикатор едет
 * обычным групповым конвертом (`APUGRP1|typ|...`): неизвестный вид старые
 * телефоны молча пропустят, а не выведут мусором в переписку.
 *
 * Правила те же, что у реакций (р228/р229):
 *  - тень своей сессии не имеет - конверт несёт активное устройство;
 *  - активное, отправив конверт участникам, показывает его и партнёрскому
 *    устройству личности (индикатор одинаков на обоих телефонах);
 *  - сигнал служебный: рассылка идёт по бюджету `SwarmLane.SIGNAL`, и когда
 *    бюджет исчерпан, индикатор молча не доезжает - это лучше, чем задерживать
 *    сообщения и посты ради «печатает…».
 */
@Singleton
class GroupTypingRouter @Inject constructor(
    private val groupDao: GroupDao,
    private val counters: PostCounterRepository,
    private val swarmBudget: SwarmBudget,
) {

    /**
     * Сказать группе, что я набираю текст (или что перестал).
     *
     * @param topicId тема, в которой печатают; пусто - общий чат группы.
     */
    suspend fun publish(groupId: String, topicId: String, typing: Boolean) {
        if (groupId.isBlank()) return
        val envelope = GroupWire.buildTyping(groupId, topicId, typing)
        withContext(Dispatchers.IO) {
            val me = RustBridge.nodeId().orEmpty()
            // р228: тень отдаёт действие активному - у неё нет своей сессии.
            if (MirrorHub.deliverGroupEnvelope(groupId, envelope)) return@withContext
            // р229: своё действие активного - и партнёрскому устройству.
            MirrorHub.publishEnvelope(
                senderId = me,
                chatId = groupId,
                messageId = "",
                text = envelope,
            )
            val group = groupDao.getGroupById(groupId) ?: return@withContext
            if (groupDao.getMember(groupId, me) == null) return@withContext
            val recipients = runCatching { counters.signalTargets(group, me) }
                .getOrDefault(emptyList())
            var sent = 0
            for (peer in recipients) {
                if (!swarmBudget.tryAcquire(SwarmLane.SIGNAL)) break
                RustBridge.sendMessage(UUID.randomUUID().toString(), groupId, peer, envelope)
                sent++
            }
            if (sent < recipients.size) {
                android.util.Log.i(
                    TAG,
                    "typing fanout capped: $sent/${recipients.size} (signal budget)",
                )
            }
        }
    }

    private companion object {
        const val TAG = "GroupTyping"
    }
}
