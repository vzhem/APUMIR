package com.vladimir.messenger.ui.screens.contacts

import com.vladimir.messenger.domain.model.Chat
import com.vladimir.messenger.domain.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactChatRowsTest {
    @Test
    fun matchingChatSuppliesPreviewTimeAndUnreadCount() {
        val contact = Contact(
            id = "pk_alice",
            displayName = "Алиса",
            isOnline = true,
            username = "alice",
        )
        val chat = Chat(
            id = "chat-42",
            contactId = "pk_alice",
            // The address book should win for presentation metadata.
            contactName = "Old name",
            lastMessage = "Привет!",
            lastMessageTime = 1_728_000_000_000L,
            unreadCount = 3,
            isContactOnline = false,
            isPinned = true,
            pinnedAtMs = 1_727_000_000_000L,
        )

        val row = buildContactChatRows(listOf(contact), listOf(chat)).single()

        assertEquals("Алиса", row.chat.contactName)
        assertTrue(row.chat.isContactOnline)
        assertEquals("chat-42", row.chat.id)
        assertEquals("Привет!", row.chat.lastMessage)
        assertEquals(1_728_000_000_000L, row.chat.lastMessageTime)
        assertEquals(3, row.chat.unreadCount)
        assertTrue(row.chat.isPinned)
    }

    @Test
    fun newestDuplicateChatProvidesTheContactPreview() {
        val contact = Contact(id = "pk_server", displayName = "Server")
        val stale = Chat(
            id = "old-chat",
            contactId = "pk_server",
            contactName = "Server",
            lastMessage = "старое",
            lastMessageTime = 100L,
        )
        val newest = Chat(
            id = "new-chat",
            contactId = "pk_server",
            contactName = "Server",
            lastMessage = "новое",
            lastMessageTime = 200L,
        )

        val row = buildContactChatRows(listOf(contact), listOf(stale, newest)).single()

        assertEquals("new-chat", row.chat.id)
        assertEquals("новое", row.chat.lastMessage)
        assertEquals(200L, row.chat.lastMessageTime)
    }

    @Test
    fun contactWithoutAChatStillGetsAnEmptyRowThatCanBeOpened() {
        val contact = Contact(id = "pk_new", displayName = "Новый")

        val row = buildContactChatRows(listOf(contact), emptyList()).single()

        assertEquals("pk_new", row.chat.id)
        assertEquals("pk_new", row.chat.contactId)
        assertEquals("Новый", row.chat.contactName)
        assertNull(row.chat.lastMessage)
        assertNull(row.chat.lastMessageTime)
        assertFalse(row.chat.isContactOnline)
    }

    @Test
    fun activitySortPutsOnlineThenMostRecentlySeenContactsFirst() {
        val unknown = row(id = "unknown", name = "Яна")
        val recent = row(id = "recent", name = "Борис", lastSeenAtMs = 200L)
        val online = row(id = "online", name = "Анна", online = true, lastSeenAtMs = 100L)

        val sorted = sortContactChatRows(
            listOf(unknown, recent, online),
            ContactSortOrder.LAST_ACTIVITY,
        )

        assertEquals(listOf("online", "recent", "unknown"), sorted.map { it.contact.id })
    }

    @Test
    fun alphabeticSortIgnoresCaseAndUsesIdAsAStableTieBreaker() {
        val lower = row(id = "b", name = "алексей")
        val upper = row(id = "a", name = "Алексей")
        val boris = row(id = "c", name = "Борис")

        val sorted = sortContactChatRows(
            listOf(boris, lower, upper),
            ContactSortOrder.ALPHABETICAL,
        )

        assertEquals(listOf("a", "b", "c"), sorted.map { it.contact.id })
    }

    @Test
    fun onlineFirstSortUsesAlphabeticalOrderInsideEachPresenceGroup() {
        val offlineRecent = row(id = "offline", name = "Борис", lastSeenAtMs = 500L)
        val onlineZoya = row(id = "zoya", name = "Зоя", online = true)
        val onlineAnna = row(id = "anna", name = "Анна", online = true)

        val sorted = sortContactChatRows(
            listOf(offlineRecent, onlineZoya, onlineAnna),
            ContactSortOrder.ONLINE_FIRST,
        )

        assertEquals(listOf("anna", "zoya", "offline"), sorted.map { it.contact.id })
    }

    private fun row(
        id: String,
        name: String,
        online: Boolean = false,
        lastSeenAtMs: Long? = null,
    ): ContactChatRow = ContactChatRow(
        contact = Contact(
            id = id,
            displayName = name,
            isOnline = online,
            lastSeenAtMs = lastSeenAtMs,
        ),
        chat = Chat(id = "chat-$id", contactId = id, contactName = name),
    )
}
