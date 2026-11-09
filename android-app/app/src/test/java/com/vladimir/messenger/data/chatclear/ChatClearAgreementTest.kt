package com.vladimir.messenger.data.chatclear

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClearAgreementTest {
    @Test fun peerSnapshotRequiresEveryAuthenticatedPageAndExplicitApproval() {
        val operation = UUID.randomUUID()
        val ids = (0 until 70).map { "id-%03d".format(it) }
        val pages = ChatClearAgreementWire.pages(operation, ids.reversed())
        val agreement = ChatClearAgreement("owner", "friend", operation)
        val encoded = pages.map(ChatClearAgreementWire::encode)
        assertFalse(agreement.accept("owner", "other", true, encoded[0]))
        assertFalse(agreement.accept("owner", "friend", false, encoded[0]))
        assertFalse(agreement.accept("other-owner", "friend", true, encoded[0]))
        assertTrue(agreement.accept("owner", "friend", true, encoded[2]))
        assertTrue(agreement.accept("owner", "friend", true, encoded[0]))
        assertNull(agreement.preview("owner"))
        assertTrue(agreement.accept("owner", "friend", true, encoded[0])) // retry
        assertTrue(agreement.accept("owner", "friend", true, encoded[1]))
        assertEquals(ids, agreement.preview("owner"))
        assertNull(agreement.approve("owner", false))
        assertNull(agreement.approve("other-owner", true))
        assertEquals(ids, agreement.approve("owner", true))
        assertFalse("new-after-snapshot" in agreement.approve("owner", true)!!)
    }

    @Test fun malformedConflictingOrIncompleteOffersNeverBecomeApproved() {
        val operation = UUID.randomUUID()
        val pages = ChatClearAgreementWire.pages(operation, (0 until 33).map { "id-%02d".format(it) })
        val agreement = ChatClearAgreement("owner", "friend", operation)
        val first = ChatClearAgreementWire.encode(pages.first())
        assertTrue(agreement.accept("owner", "friend", true, first))
        val conflicting = pages.first().copy(ids = pages.first().ids.reversed())
        assertFalse(agreement.accept("owner", "friend", true, ChatClearAgreementWire.encode(conflicting)))
        assertFalse(agreement.accept("owner", "friend", true, first.replace(".0.2.", ".00.2.")))
        val wrongDigest = pages.last().copy(digest = "0".repeat(64))
        assertFalse(agreement.accept("owner", "friend", true, ChatClearAgreementWire.encode(wrongDigest)))
        assertNull(agreement.approve("owner", true))
        assertTrue(agreement.accept("owner", "friend", true, ChatClearAgreementWire.encode(pages.last())))
        assertTrue(agreement.approve("owner", true) != null)
    }

    @Test fun completeTamperedSnapshotFailsDigestAndOrdering() {
        val operation = UUID.randomUUID()
        val page = ChatClearAgreementWire.pages(operation, listOf("a", "b")).single()
        val altered = page.copy(ids = listOf("a", "c"))
        val agreement = ChatClearAgreement("owner", "friend", operation)
        assertTrue(agreement.accept("owner", "friend", true, ChatClearAgreementWire.encode(altered)))
        assertNull(agreement.preview("owner"))
    }

    @Test fun excessiveSnapshotIsRejectedBeforeItCanAllocatePages() {
        var rejected = false
        try { ChatClearAgreementWire.pages(UUID.randomUUID(), List(ChatClearAgreementWire.MAX_PAGES * ChatClearWire.MAX_IDS + 1) { "same" }) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun emptyAndUnicodeOffersHaveStrictCanonicalWireFormat() {
        val operation = UUID.randomUUID()
        val empty = ChatClearAgreementWire.pages(operation, emptyList()).single()
        assertEquals(empty, ChatClearAgreementWire.decode(ChatClearAgreementWire.encode(empty)))
        val agreement = ChatClearAgreement("owner", "friend", operation)
        assertTrue(agreement.accept("owner", "friend", true, ChatClearAgreementWire.encode(empty)))
        assertEquals(emptyList<String>(), agreement.approve("owner", true))
        val unicode = ChatClearAgreementWire.pages(operation, listOf("x|,тест", "other")).single()
        val text = ChatClearAgreementWire.encode(unicode)
        assertEquals(3, text.split('|').size) // legacy delete routers discard it
        assertEquals(unicode, ChatClearAgreementWire.decode(text))
        assertNull(ChatClearAgreementWire.decode(text + "="))
        assertNull(ChatClearAgreementWire.decode("x".repeat(8193)))
    }
}
