package com.vladimir.messenger.data.chatclear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatClearFileJournalInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = File(context.noBackupFilesDir, "clear-files-test-${UUID.randomUUID()}.sqlite")
    private var journal = ChatClearFileJournal(context, file)

    @After fun close() {
        journal.close()
        file.delete(); File(file.path + "-wal").delete(); File(file.path + "-shm").delete(); File(file.path + "-journal").delete()
    }

    private fun transfer(id: String, message: String, chat: String = "chat") = FileTransferEntity(
        id, message, chat, "peer", "INCOMING", "file.txt", "text/plain", 1L, 1, 1,
        "hash", "COMPLETE", createdAtMs = 1, expiresAtMs = 10, updatedAtMs = 1,
    )

    @Test fun intentsSurviveRestartAndRemainOwnerScoped() {
        val a = transfer("a".repeat(32), "first")
        val b = transfer("b".repeat(32), "second")
        assertTrue(journal.remember("owner", "peer", "chat", listOf(a, b)))
        assertTrue(journal.remember("owner", "peer", "chat", listOf(a)))
        assertTrue(journal.remember("other-owner", "other-peer", "chat", listOf(a)))
        journal.close()
        journal = ChatClearFileJournal(context, file)
        val first = journal.page("owner", null, 1)
        assertEquals(listOf(ChatClearFileJournal.Entry("peer", "chat", a.transferId, "first")), first)
        assertEquals(listOf(ChatClearFileJournal.Entry("peer", "chat", b.transferId, "second")), journal.page("owner", first.last().transferId, 1))
        assertEquals("other-peer", journal.page("other-owner", null).single().peer)
    }

    @Test fun conflictingTransferIdentityDoesNotOverwriteOrPartiallyInsert() {
        val a = transfer("a".repeat(32), "first")
        val b = transfer("b".repeat(32), "second")
        assertTrue(journal.remember("owner", "peer", "chat", listOf(a)))
        assertFalse(journal.remember("owner", "peer", "chat", listOf(b, a.copy(messageId = "different"))))
        assertEquals(listOf(ChatClearFileJournal.Entry("peer", "chat", a.transferId, "first")), journal.page("owner", null))
    }
}
