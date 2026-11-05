package com.vladimir.messenger.data.repository

import com.vladimir.messenger.data.local.dao.MessageDao
import com.vladimir.messenger.domain.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Bounded Room access for personal-chat history. */
@Singleton
class DirectChatHistoryStore @Inject constructor(
    private val messageDao: MessageDao,
) {
    fun observeRecent(chatId: String, limit: Int): Flow<List<Message>> =
        messageDao.observeRecentMessages(chatId, limit)
            .map { rows -> rows.asReversed().map { it.toDomainMessage() } }
            .flowOn(Dispatchers.Default)

    suspend fun older(
        chatId: String,
        beforeTimestamp: Long,
        beforeId: String,
        limit: Int,
    ): List<Message> = withContext(Dispatchers.Default) {
        messageDao.getOlderMessages(chatId, beforeTimestamp, beforeId, limit)
            .asReversed()
            .map { it.toDomainMessage() }
    }
}
