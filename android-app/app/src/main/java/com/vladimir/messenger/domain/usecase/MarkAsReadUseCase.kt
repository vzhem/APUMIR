package com.vladimir.messenger.domain.usecase

import com.vladimir.messenger.data.repository.ChatRepository
import javax.inject.Inject

class MarkAsReadUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
) {
    suspend operator fun invoke(chatId: String) {
        chatRepository.markAsRead(chatId)
    }
}