package com.vladimir.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vladimir.messenger.data.local.entity.DirectPollEntity
import com.vladimir.messenger.data.local.entity.DirectPollVoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * Опросы личных чатов. Выборка — по чату целиком, как у групповых опросов:
 * лента чата подписывается на один поток, а не на поток на сообщение.
 *
 * Подсчёт голосов — в Kotlin (`DirectPollTally`), без `COUNT(*)`: строк в
 * личном опросе максимум две.
 */
@Dao
interface DirectPollDao {

    /** IGNORE: повторная доставка того же опроса не перетирает принятый. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPoll(poll: DirectPollEntity): Long

    @Query("SELECT * FROM direct_polls WHERE pollId = :pollId")
    suspend fun getPoll(pollId: String): DirectPollEntity?

    @Query("SELECT * FROM direct_polls WHERE chatId = :chatId")
    fun observeChatPolls(chatId: String): Flow<List<DirectPollEntity>>

    @Query("UPDATE direct_polls SET closed = 1 WHERE pollId = :pollId")
    suspend fun closePoll(pollId: String): Int

    @Query("DELETE FROM direct_polls WHERE chatId = :chatId")
    suspend fun deleteChatPolls(chatId: String): Int

    @Query("DELETE FROM direct_polls WHERE messageId = :messageId")
    suspend fun deleteByMessage(messageId: String): Int

    /** Передумал — голос переезжает на новый вариант, а не добавляется вторым. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putVote(vote: DirectPollVoteEntity)

    @Query("SELECT * FROM direct_poll_votes WHERE pollId = :pollId")
    suspend fun votesFor(pollId: String): List<DirectPollVoteEntity>

    @Query(
        "SELECT direct_poll_votes.* FROM direct_poll_votes " +
            "INNER JOIN direct_polls ON direct_polls.pollId = direct_poll_votes.pollId " +
            "WHERE direct_polls.chatId = :chatId",
    )
    fun observeChatVotes(chatId: String): Flow<List<DirectPollVoteEntity>>
    @Query("DELETE FROM direct_poll_votes WHERE pollId IN (SELECT pollId FROM direct_polls WHERE chatId = :chatId AND messageId IN (:ids))")
    suspend fun deleteVotesForChatMessages(chatId: String, ids: List<String>): Int

    @Query("DELETE FROM direct_polls WHERE chatId = :chatId AND messageId IN (:ids)")
    suspend fun deleteForChatMessages(chatId: String, ids: List<String>): Int
}
