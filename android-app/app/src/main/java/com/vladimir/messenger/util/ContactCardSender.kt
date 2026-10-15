package com.vladimir.messenger.util

// =============================================================================
// CONTACTCARDSENDER.KT — «поделись человеком, как ссылкой»
// =============================================================================
// Раунд 175. У «Поделиться контактом» появился второй путь: не наружу через
// системное меню, а ВНУТРЬ APU — выбранным абонентам. Каждому уходит обычное
// сообщение с apu://-ссылкой контакта: тап по ней открывает «Добавить контакт»
// с предзаполненным узлом - тот же механизм, что у ссылок из «Контактов».
// =============================================================================

import com.vladimir.messenger.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException

/** Получатель карточки контакта при адресной рассылке. */
data class ContactCardRecipient(
    val contactId: String,
    val displayName: String,
)

/** Сводка одной рассылки: сообщения, принятые каждым личным чатом. */
data class ContactCardSendResult(
    val sent: Int,
    val failed: Int,
)

/** Распознанная карточка контакта внутри сообщения-приглашения. */
data class ContactInviteCard(
    val name: String,
    val nickname: String?,
    val inviteLink: String,
)

object ContactCardSender {
    /** Ограждение на рассылку — такое же, как у других адресных приглашений. */
    const val MAX_RECIPIENTS = 100

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
    ): Boolean = sendToMany(
        chatRepository = chatRepository,
        recipients = listOf(ContactCardRecipient(toContactId, toName)),
        sharedNodeId = sharedNodeId,
        sharedName = sharedName,
        sharedUsername = sharedUsername,
    ).sent == 1

    /** Отправить одну карточку каждому выбранному контакту; результаты собраны без спама тостами. */
    suspend fun sendToMany(
        chatRepository: ChatRepository,
        recipients: Collection<ContactCardRecipient>,
        sharedNodeId: String,
        sharedName: String,
        sharedUsername: String,
    ): ContactCardSendResult {
        val targets = recipients.asSequence()
            .filter { it.contactId.isNotBlank() }
            .distinctBy { it.contactId }
            .take(MAX_RECIPIENTS)
            .toList()
        if (targets.isEmpty()) return ContactCardSendResult(sent = 0, failed = 0)

        val text = try {
            val link = ContactShareLink.build(sharedNodeId, sharedName, sharedUsername)
            "Контакт «$sharedName» в APU. Открой ссылку, чтобы добавить:\n$link"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ContactCardSendResult(sent = 0, failed = targets.size)
        }

        var sent = 0
        for (target in targets) {
            val delivered = try {
                val chat = chatRepository.getOrCreateChat(target.contactId, target.displayName)
                chatRepository.sendMessage(chat.id, target.contactId, text).isSuccess
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (delivered) sent++
        }
        return ContactCardSendResult(sent = sent, failed = targets.size - sent)
    }
}
