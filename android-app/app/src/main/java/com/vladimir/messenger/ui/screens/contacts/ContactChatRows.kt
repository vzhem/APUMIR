package com.vladimir.messenger.ui.screens.contacts

import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.domain.model.Contact

/**
 * A row on the Contacts screen. Contact information is owned by `contacts`,
 * while the preview, timestamp and unread count are owned by its matching
 * personal chat. Keeping both values together prevents Contacts from drawing
 * an empty synthetic chat when the real conversation already has messages.
 */
data class ContactChatRow(
    val contact: Contact,
    val chat: Chat,
)

/**
 * Joins address-book entries with their personal chats for the Contacts list.
 *
 * Contacts are the authority for a person's visible name and online state;
 * chats are the authority for message preview, timestamp, unread count and
 * conversation settings. A contact that has not been opened yet receives an
 * empty chat row so the screen still renders and can create the chat on tap.
 *
 * Earlier versions could leave duplicate chats for one contact. Selecting the
 * newest one keeps an old duplicate from hiding the actual latest preview.
 */
internal fun buildContactChatRows(
    contacts: List<Contact>,
    chats: List<Chat>,
): List<ContactChatRow> {
    val chatsByContactId = chats
        .filter { it.contactId.isNotBlank() }
        .groupBy { it.contactId }
        .mapValues { (_, sameContactChats) ->
            sameContactChats.maxByOrNull { it.lastMessageTime ?: Long.MIN_VALUE }
        }

    return contacts.map { contact ->
        val actualChat = chatsByContactId[contact.id]
        ContactChatRow(
            contact = contact,
            chat = actualChat?.copy(
                contactId = contact.id,
                contactName = contact.displayName,
                isContactOnline = contact.isOnline,
            ) ?: Chat(
                id = contact.id,
                contactId = contact.id,
                contactName = contact.displayName,
                isContactOnline = contact.isOnline,
            ),
        )
    }
}
