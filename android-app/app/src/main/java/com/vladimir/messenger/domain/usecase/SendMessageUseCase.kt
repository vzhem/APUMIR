package com.vladimir.messenger.domain.usecase

import com.vladimir.messenger.data.repository.ChatRepository
import com.vladimir.messenger.data.reply.DirectReplyWire
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
) {
    suspend operator fun invoke(
        chatId: String,
        text: String,
        reply: DirectReplyWire.Target? = null,
    ): Result<Unit> {
        return chatRepository.sendMessage(chatId, "", text, reply = reply).map { }
    }
}