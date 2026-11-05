package com.vladimir.messenger.domain.usecase

import com.vladimir.messenger.data.repository.DirectChatHistoryStore
import com.vladimir.messenger.domain.model.Message
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetMessagesUseCase @Inject constructor(
    private val history: DirectChatHistoryStore,
) {
    operator fun invoke(chatId: String, limit: Int = 100): Flow<List<Message>> =
        history.observeRecent(chatId, limit)

    suspend fun older(
        chatId: String,
        beforeTimestamp: Long,
        beforeId: String,
        limit: Int,
    ): List<Message> = history.older(chatId, beforeTimestamp, beforeId, limit)
}
