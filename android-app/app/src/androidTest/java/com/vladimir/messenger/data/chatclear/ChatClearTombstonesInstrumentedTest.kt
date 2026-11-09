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
class ChatClearTombstonesInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = File(context.noBackupFilesDir, "clear-test-${UUID.randomUUID()}.sqlite")
    private var store = ChatClearTombstones(context, file)

    @After fun cleanup() {
        store.close()
        file.delete()
        File(file.path + "-journal").delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
    }

    @Test fun survivesRestartAndScopesByOwnerAndPeer() {
        assertTrue(store.remember("owner", "peer", listOf("first", "second")))
        assertTrue(store.remember("owner", "peer", listOf("first", "second")))
        store.close()
        store = ChatClearTombstones(context, file)
        assertTrue(store.contains("owner", "peer", "first"))
        assertTrue(store.contains("owner", "peer", "second"))
        assertFalse(store.contains("owner", "other", "first"))
        assertFalse(store.contains("other", "peer", "first"))
        assertFalse(store.contains("owner", "peer", "new"))
    }

    @Test fun pagesAreStableAndOwnerScoped() {
        assertTrue(store.remember("owner", "a", listOf("1", "2", "3")))
        assertTrue(store.remember("owner", "b", listOf("4")))
        assertTrue(store.remember("other", "a", listOf("hidden")))
        val first = store.page("owner", null, 2)
        assertEquals(listOf(ChatClearTombstones.Cursor("a", "1"), ChatClearTombstones.Cursor("a", "2")), first)
        val second = store.page("owner", first.last(), 2)
        assertEquals(listOf(ChatClearTombstones.Cursor("a", "3"), ChatClearTombstones.Cursor("b", "4")), second)
        assertTrue(store.page("owner", second.last(), 2).isEmpty())
    }
}
