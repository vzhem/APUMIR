package com.vladimir.messenger.util

// =============================================================================
// CONTACTCARDSENDER.KT — «поделись человеком, как ссылкой»
// =============================================================================
// Раунд 175. У «Поделиться контактом» появился второй путь: не наружу через
// системное меню, а ВНУТРЬ APU - выбранному абоненту. Ему уходит обычное
// сообщение с apu://-ссылкой контакта: тап по ней открывает «Добавить контакт»
// с предзаполненным узлом - тот же механизм, что у ссылок из «Контактов».
// =============================================================================

import com.vladimir.messenger.data.repository.ChatRepository

object ContactCardSender {

    /**
     * Отправить [toContactId] ссылку-приглашение контакта [sharedNodeId].
     *
     * @return true - сообщение встало в переписку и ушло в ядро.
     */
    suspend fun send(
        chatRepository: ChatRepository,
        toContactId: String,
        toName: String,
        sharedNodeId: String,
        sharedName: String,
        sharedUsername: String,
    ): Boolean = try {
        val link = ContactShareLink.build(sharedNodeId, sharedName, sharedUsername)
        val chat = chatRepository.getOrCreateChat(toContactId, toName)
        val text = "Контакт «$sharedName» в APU. Открой ссылку, чтобы добавить:\n$link"
        chatRepository.sendMessage(chat.id, toContactId, text).isSuccess
    } catch (_: Exception) {
        false
    }
}
