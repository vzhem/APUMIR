package com.vladimir.messenger.ui.screens.contacts

import com.vladimir.messenger.R
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

/** Sort orders offered by the Contacts address book. */
enum class ContactSortOrder(
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val descRes: Int,
) {
    /** Most recently observed contacts first; contacts online now always lead. */
    LAST_ACTIVITY(R.string.cs_sort_last_title, R.string.cs_sort_last_desc),
    /** Localized name order, useful for finding a known person in a long book. */
    ALPHABETICAL(R.string.cs_sort_alpha_title, R.string.cs_sort_alpha_desc),
    /** Handy when the next action is to write or call somebody now. */
    ONLINE_FIRST(R.string.cs_sort_online_title, R.string.cs_sort_online_desc);

    companion object {
        fun fromStored(value: String?): ContactSortOrder =
            entries.firstOrNull { it.name == value } ?: LAST_ACTIVITY
    }
}

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

/**
 * Sorts a ready contact/chat list without changing the address book itself.
 *
 * A contact might not have a recorded presence yet after upgrading. Those
 * entries stay deterministic (by name) instead of jumping randomly to the
 * start of a list. Every comparator finishes with the contact id so Compose
 * receives a stable order even for equal names.
 */
internal fun sortContactChatRows(
    rows: List<ContactChatRow>,
    order: ContactSortOrder,
): List<ContactChatRow> = when (order) {
    ContactSortOrder.LAST_ACTIVITY -> rows.sortedWith { left, right ->
        compareOnline(left, right)
            .takeUnless { it == 0 }
            ?: compareDescending(lastActivity(left), lastActivity(right))
                .takeUnless { it == 0 }
            ?: compareByName(left, right)
    }
    ContactSortOrder.ALPHABETICAL -> rows.sortedWith(::compareByName)
    ContactSortOrder.ONLINE_FIRST -> rows.sortedWith { left, right ->
        compareOnline(left, right)
            .takeUnless { it == 0 }
            ?: compareByName(left, right)
    }
}

private fun compareOnline(left: ContactChatRow, right: ContactChatRow): Int = when {
    left.contact.isOnline == right.contact.isOnline -> 0
    left.contact.isOnline -> -1
    else -> 1
}

private fun lastActivity(row: ContactChatRow): Long =
    row.contact.lastSeenAtMs?.takeIf { it > 0L } ?: Long.MIN_VALUE

private fun compareDescending(left: Long, right: Long): Int = right.compareTo(left)

private val RussianNameCollator = java.text.Collator.getInstance(java.util.Locale("ru")).apply {
    // «Елена» и «елена» стоят рядом; «Ё» сортируется по русским правилам,
    // а не по своему Unicode-коду перед «А».
    strength = java.text.Collator.PRIMARY
}

private fun compareByName(left: ContactChatRow, right: ContactChatRow): Int {
    val leftName = left.contact.displayName.trim()
    val rightName = right.contact.displayName.trim()
    // Collator mutable, а сортировка выполняется на рабочем dispatcher.
    val nameOrder = synchronized(RussianNameCollator) {
        RussianNameCollator.compare(leftName, rightName)
    }
    return if (nameOrder != 0) nameOrder else left.contact.id.compareTo(right.contact.id)
}
