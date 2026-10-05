package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.GroupPollEntity
import com.vladimir.messenger.data.local.entity.GroupPollVoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * Опросы групп, тем и каналов (р250).
 *
 * Выборки сделаны по группе целиком: лента темы, лента канала и карточка
 * поста достают опросы одним потоком, а не по одному на сообщение.
 *
 * Грабли Room из `docs/AI_HANDOFF.md` учтены: никаких проекций `COUNT(*)` в
 * свой data-класс и никаких выборок `List<Long>` - подсчёт голосов идёт в
 * Kotlin, строк в опросе всё равно считанные десятки.
 */
@Dao
interface GroupPollDao {

    // ── Опросы ──────────────────────────────────────────────────────────────

    /**
     * IGNORE, а не REPLACE: повторная доставка того же опроса (ретрансляция,
     * досылка новичку) не должна перетирать уже принятый опрос.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPoll(poll: GroupPollEntity): Long

    @Query("SELECT * FROM group_polls WHERE pollId = :pollId")
    suspend fun getPoll(pollId: String): GroupPollEntity?

    @Query("SELECT * FROM group_polls WHERE groupId = :groupId")
    fun observeGroupPolls(groupId: String): Flow<List<GroupPollEntity>>

    /** Закрыть опрос: голосовать нельзя, итоги остаются. */
    @Query("UPDATE group_polls SET closed = 1 WHERE pollId = :pollId")
    suspend fun closePoll(pollId: String): Int

    @Query("DELETE FROM group_polls WHERE groupId = :groupId")
    suspend fun deleteGroupPolls(groupId: String): Int

    @Query("DELETE FROM group_polls WHERE messageId = :messageId")
    suspend fun deleteByMessage(messageId: String): Int

    // ── Голоса ──────────────────────────────────────────────────────────────

    /** Передумал - голос переезжает на новый вариант, а не добавляется вторым. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putVote(vote: GroupPollVoteEntity)

    /**
     * Все голоса опросов этой группы: соединяем с опросами, чтобы лента
     * подписывалась на один поток, а не по потоку на опрос.
     */
    @Query(
        "SELECT group_poll_votes.* FROM group_poll_votes " +
            "INNER JOIN group_polls ON group_polls.pollId = group_poll_votes.pollId " +
            "WHERE group_polls.groupId = :groupId"
    )
    fun observeGroupVotes(groupId: String): Flow<List<GroupPollVoteEntity>>
}
