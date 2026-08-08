package com.vladimir.messenger.domain.usecase

import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.domain.model.Chat
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetChatsUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
) {
    operator fun invoke(): Flow<List<Chat>> = chatRepository.observeChats()
}