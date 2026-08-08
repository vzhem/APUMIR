package com.vladimir.messenger.domain.usecase

import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.domain.model.Message
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetMessagesUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
) {
    operator fun invoke(chatId: String): Flow<List<Message>> =
        chatRepository.observeMessages(chatId)
}