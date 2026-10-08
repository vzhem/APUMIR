package com.vladimir.messenger.data.rank

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.local.dao.ChatDao
import com.vladimir.messenger.data.local.dao.ContactDao
import com.vladimir.messenger.data.local.dao.GroupDao
import com.vladimir.messenger.data.referral.ReferralRankStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Отправка своего ранга собеседникам (решение владельца 2026-10-07).
 *
 * Ранг растёт от ПОСТОЯННЫХ приглашений, но собеседник об этом не знает: ранг
 * не виден ни из переписки, ни из транспорта. Поэтому узел раз в неделю (или
 * сразу, когда ранг изменился) рассылает короткий конверт [RankWire] тем, с кем
 * у него есть чат. Приёмник поглощает конверт до истории чата — на той стороне
 * появляется только знак VIP у имени.
 *
 * Почему рассылка, а не ответ на сообщение: ранг мог измениться, пока человек
 * никому не писал, а собеседник должен увидеть знак при первой же возможности.
 * Офлайн-доставку берёт на себя ядро (durable-очередь), поэтому «отправить всем»
 * не требует, чтобы все были в сети.
 *
 * Кому уходит ранг: контактам и участникам своих групп и каналов. В группе и
 * канале ранг берётся не из переписки — там собеседник может быть незнакомым
 * узлом, и единственный источник сведений о нём — его собственный конверт.
 * Ранг уходит в обёртке «печатает…» (см. [RankWire.WRAPPER]), поэтому телефоны
 * на старых сборках не покажут служебную строку и не разбудят уведомление.
 *
 * Частоту ограничивает [RankBroadcastPrefs]: без этого каждая перерисовка
 * главного экрана слала бы пакет каждому контакту.
 */
@Singleton
class PeerRankBroadcaster @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactDao: ContactDao,
    private val chatDao: ChatDao,
    /** Участники групп и каналов: им ранг нужен не меньше, чем контактам. */
    private val groupDao: GroupDao,
) {

    /**
     * Разослать свой ранг, если это нужно: ранг изменился с прошлой рассылки или
     * прошла неделя с неё. Вызывать можно часто — решение о самой отправке здесь.
     *
     * @return сколько пакетов реально ушло транспортом; 0 — рассылка не нужна
     *   или её не удалось начать.
     */
    suspend fun broadcastIfNeeded(nowMs: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val own = RankWire.canonicalNodeId(RustBridge.nodeId())
            if (own == null) {
                Log.i(TAG, "broadcast skipped: own node id unavailable")
                return@withContext 0
            }
            val qualified = ReferralRankStore.qualifiedDirectCount(app)
            if (!RankBroadcastPrefs.shouldBroadcast(app, qualified, nowMs)) {
                return@withContext 0
            }

            val envelope = RankWire.build(own, qualified, nowMs)
            if (envelope == null) {
                Log.w(TAG, "broadcast skipped: envelope not built")
                return@withContext 0
            }

            // Кому уходит ранг: контакты (у них есть личная переписка, и знак
            // виден в списке чатов) и участники моих групп и каналов (владелец
            // 2026-10-07: «VIP должно быть видно везде, и в группах, и в
            // каналах»). Один человек получает один пакет: узлы в наборе.
            val targets = linkedMapOf<String, String>() // узел -> адрес переписки

            val contactIds = runCatching { contactDao.allIds() }.getOrDefault(emptyList())
            for (contactId in contactIds) {
                val canonical = RankWire.canonicalNodeId(contactId) ?: continue
                if (canonical == own) continue
                // Чат нужен ядру как адрес переписки: без него адресовать
                // сообщение нечем.
                val chatId = runCatching { chatDao.getChatByContactId(contactId)?.id }
                    .getOrNull() ?: continue
                targets[canonical] = chatId
            }

            val groups = runCatching {
                groupDao.getGroups() + groupDao.getChannels()
            }.getOrDefault(emptyList())
            for (group in groups) {
                val members = runCatching { groupDao.getMembers(group.id) }
                    .getOrDefault(emptyList())
                for (member in members) {
                    if (member.isBanned) continue
                    val canonical = RankWire.canonicalNodeId(member.nodeId) ?: continue
                    if (canonical == own) continue
                    // Контакт уже получил адрес личного чата: не переписываем.
                    targets.putIfAbsent(canonical, group.id)
                }
            }

            var sent = 0
            for ((target, chatId) in targets) {
                val ok = runCatching {
                    RustBridge.sendMessage(
                        UUID.randomUUID().toString(),
                        chatId,
                        target,
                        envelope,
                    )
                }.getOrDefault(false)
                if (ok) sent += 1
            }

            // Отметку ставим даже при нуле доставок: ядро кладёт пакеты в
            // офлайн-очередь, поэтому «сейчас никого нет в сети» не повод
            // долбить рассылкой на каждой перерисовке экрана.
            RankBroadcastPrefs.markBroadcast(app, qualified, nowMs)
            Log.i(
                TAG,
                "rank broadcast: $sent of ${targets.size} peers " +
                    "(${contactIds.size} contacts, ${groups.size} groups), rank=$qualified",
            )
            sent
        }

    private companion object {
        const val TAG = "PeerRank"
    }
}

/**
 * Память о последней рассылке ранга: чтобы не слать один и тот же ранг по кругу.
 *
 * Хранится в обычных настройках приложения: это не секрет и не история, а
 * служебная отметка «когда и с каким рангом рассылали».
 */
internal object RankBroadcastPrefs {

    private const val PREFS = "apu_peer_rank_broadcast"
    private const val KEY_QUALIFIED = "last_qualified"
    private const val KEY_AT_MS = "last_at_ms"

    /** Раз в неделю повторяем даже без изменений: собеседник мог переустановить APU. */
    private const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    fun shouldBroadcast(context: Context, qualified: Int, nowMs: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastQualified = prefs.getInt(KEY_QUALIFIED, -1)
        val lastAt = prefs.getLong(KEY_AT_MS, 0L)
        if (lastQualified != qualified) return true
        return nowMs - lastAt >= REFRESH_INTERVAL_MS
    }

    fun markBroadcast(context: Context, qualified: Int, nowMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_QUALIFIED, qualified)
            .putLong(KEY_AT_MS, nowMs)
            .apply()
    }
}
