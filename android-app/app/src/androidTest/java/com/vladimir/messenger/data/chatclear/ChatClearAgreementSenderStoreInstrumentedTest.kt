package com.vladimir.messenger.data.chatclear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatClearAgreementSenderStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.noBackupFilesDir, "agreement-sender-${UUID.randomUUID()}.sqlite")
    private var store = ChatClearAgreementSenderStore(context, file)

    @After fun close() {
        store.close()
        listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
    }

    @Test fun snapshotAndPageReceiptsSurviveRestartWithoutIncludingLaterMessages() {
        val operation = UUID.randomUUID()
        val ids = (0 until 40).map { "id-%02d".format(it) }
        assertTrue(store.stage("owner", "peer", operation, ids))
        val packets = store.pending("owner", "peer", operation)
        assertEquals(2, packets.size)
        val page = ChatClearAgreementWire.decode(packets[0])!!
        val ack = ChatClearAgreementControl.receipt(ChatClearAgreementControl.Receipt(operation, page.index, page.digest))
        assertFalse(store.acknowledge("owner", "peer", false, ack))
        assertFalse(store.acknowledge("owner", "other", true, ack))
        assertFalse(store.acknowledge("other", "peer", true, ack))
        assertFalse(store.acknowledge("owner", "peer", true, ChatClearAgreementControl.receipt(ChatClearAgreementControl.Receipt(operation, page.index, "0".repeat(64)))))
        assertTrue(store.acknowledge("owner", "peer", true, ack))
        store.close()
        store = ChatClearAgreementSenderStore(context, file)
        assertTrue(store.stage("owner", "peer", operation, ids + "new"))
        assertFalse(store.stage("other", "peer", operation, ids))
        assertEquals(listOf(packets[1]), store.pending("owner", "peer", operation))
        assertTrue(store.acknowledge("owner", "peer", true, ack))
        assertFalse(store.remove("owner", "other", operation))
        assertTrue(store.remove("owner", "peer", operation))
        assertTrue(store.pending("owner", "peer", operation).isEmpty())
    }
}
