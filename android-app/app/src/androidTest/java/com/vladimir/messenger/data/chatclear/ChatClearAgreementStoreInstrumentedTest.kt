package com.vladimir.messenger.data.chatclear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatClearAgreementStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.noBackupFilesDir, "clear-agreement-${UUID.randomUUID()}.sqlite")
    private var store = ChatClearAgreementStore(context, file)

    @After fun close() {
        store.close(); file.delete(); File(file.path + "-wal").delete(); File(file.path + "-shm").delete(); File(file.path + "-journal").delete()
    }

    @Test fun outOfOrderPagesSurviveRestartAndAreBoundToExpectedOwnerAndPeer() {
        val operation = UUID.randomUUID()
        val ids = (0 until 40).map { "id-%02d".format(it) }
        val pages = ChatClearAgreementWire.pages(operation, ids).map(ChatClearAgreementWire::encode)
        assertFalse(store.accept("owner", "peer", true, pages[0]))
        assertTrue(store.begin("owner", "peer", operation))
        assertFalse(store.begin("other", "peer", operation))
        assertFalse(store.accept("owner", "peer", false, pages[0]))
        assertFalse(store.accept("owner", "other", true, pages[0]))
        assertTrue(store.accept("owner", "peer", true, pages[1]))
        assertNull(store.preview("owner", "peer", operation))
        store.close()
        store = ChatClearAgreementStore(context, file)
        assertTrue(store.accept("owner", "peer", true, pages[1]))
        assertTrue(store.accept("owner", "peer", true, pages[0]))
        assertEquals(ids, store.preview("owner", "peer", operation))
        assertNull(store.preview("other", "peer", operation))
        assertNull(store.preview("owner", "other", operation))
        assertFalse(store.remove("owner", "other", operation))
        assertTrue(store.remove("owner", "peer", operation))
        assertNull(store.preview("owner", "peer", operation))
    }

    @Test fun conflictingPageAndTamperedDigestCannotReplaceSavedContent() {
        val operation = UUID.randomUUID()
        val page = ChatClearAgreementWire.pages(operation, listOf("a", "b")).single()
        assertTrue(store.begin("owner", "peer", operation))
        assertTrue(store.accept("owner", "peer", true, ChatClearAgreementWire.encode(page)))
        assertFalse(store.accept("owner", "peer", true, ChatClearAgreementWire.encode(page.copy(ids = listOf("a", "c")))))
        assertFalse(store.accept("owner", "peer", true, ChatClearAgreementWire.encode(page.copy(digest = "0".repeat(64)))))
        assertEquals(listOf("a", "b"), store.preview("owner", "peer", operation))
    }

    @Test fun completeButCorruptProposalHasNoPreview() {
        val operation = UUID.randomUUID()
        val page = ChatClearAgreementWire.pages(operation, listOf("a", "b")).single().copy(ids = listOf("a", "c"))
        assertTrue(store.begin("owner", "peer", operation))
        assertTrue(store.accept("owner", "peer", true, ChatClearAgreementWire.encode(page)))
        assertNull(store.preview("owner", "peer", operation))
    }
}
