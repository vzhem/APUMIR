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

/** Распознанная карточка контакта внутри сообщения-приглашения. */
data class ContactInviteCard(
    val name: String,
    val nickname: String?,
    val inviteLink: String,
)

object ContactCardSender {

    /**
     * Раунд 176: вытащить из сообщения карточку контакта. Такие же карточки
     * рисуются и у сообщений, отправленных прежними сборками - текст
     * не менялся.
     */
    fun parseCard(content: String): ContactInviteCard? {
        // Раунд 181: отправитель пишет по-русски («Контакт «Имя» в APU»),
        // а парсер ждал английское «Contact» - карточка не узнавалась,
        // получатель видел сырой текст с некликабельной ссылкой.
        if (!content.startsWith("Контакт \u00ab") && !content.startsWith("Contact \u00ab")) return null
        val name = content.substringAfter("\u00ab", "").substringBefore("\u00bb").trim()
        if (name.isEmpty() || (!content.contains(" в APU") && !content.contains(" in APU"))) return null
        val link = content.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith(ApuLink.SCHEME + "://") || it.startsWith("p2pmessenger://") }
            ?: return null
        val nick = runCatching { ApuLink.parse(link)?.nickname }.getOrNull()
        return ContactInviteCard(name = name, nickname = nick, inviteLink = link)
    }

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
