package com.vladimir.messenger.data.rank

import android.util.Log
import com.vladimir.messenger.data.RustBridge
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Приём ранга собеседника из общего потока сообщений.
 *
 * Подключается в `CoreServerService` рядом с роутерами реферальных, групповых и
 * файловых пакетов — до того, как служебный конверт мог бы стать текстом в
 * истории чата. Возвращает true, если текст оказался нашим конвертом, даже
 * когда он битый или не прошёл проверку: такой пакет намеренно поглощается, его
 * место не в переписке.
 *
 * Что проверяем:
 * 1. форма и разбор конверта (строго, см. [RankWire]);
 * 2. узел в конверте совпадает с отправителем, от которого пакет реально дошёл
 *    (транспорт 1:1 аутентифицирован) — подставить чужое имя нельзя;
 * 3. пакет не о себе самом (переписка с собственным узлом);
 * 4. запись «свежее» той, что уже лежит: опоздавший старый пакет не откатывает
 *    знак VIP назад.
 *
 * Ранг ложится в общую таблицу `peer_ranks` (см. [PeerRankStore]): так знак VIP
 * и золотое кольцо видно и у тех, кого нет в адресной книге, — участников групп
 * и авторов постов каналов.
 */
@Singleton
class PeerRankRouter @Inject constructor(
    private val store: PeerRankStore,
) {

    suspend fun routeIncoming(
        senderId: String,
        text: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) {
        if (!RankWire.isRankPacket(text)) return@withContext false

        val packet = RankWire.parse(text, nowMs)
        if (packet == null) {
            Log.w(TAG, "rank packet from $senderId is malformed, dropped")
            return@withContext true
        }

        val sender = RankWire.canonicalNodeId(senderId)
        if (sender == null || sender != packet.nodeId) {
            // Заявлять чужой узел нельзя: пакет пришёл от senderId, значит и ранг
            // может сообщать только он.
            Log.w(TAG, "rank packet claims ${packet.nodeId} but came from $senderId, dropped")
            return@withContext true
        }

        val own = RankWire.canonicalNodeId(RustBridge.nodeId())
        if (own != null && own == packet.nodeId) {
            // Свой же конверт (бывает при переписке с собственным узлом).
            return@withContext true
        }

        val stored = runCatching {
            store.remember(packet.nodeId, packet.qualified, packet.updatedAtMs)
        }.onFailure { e ->
            Log.w(TAG, "rank packet from $senderId not stored: ${e.message}")
        }.getOrDefault(false)

        // Пишем в журнал и «не изменилось»: по копипасте должно быть видно, что
        // пакеты доходят, даже когда ранг тот же и обновлять нечего.
        Log.i(TAG, "peer rank from ${packet.nodeId}: ${packet.qualified}, stored=$stored")
        true
    }

    private companion object {
        const val TAG = "PeerRank"
    }
}
