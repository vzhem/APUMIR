package com.vladimir.messenger.data.chatclear

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.ChatEntity
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatClearSnapshotInstrumentedTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    @After fun close() = db.close()

    @Test fun concurrentMessageAndTransferAreOutsideTheSameSnapshot() = runBlocking {
        db.chatDao().insertChat(ChatEntity("chat", "friend", "Friend"))
        db.messageDao().insertMessage(MessageEntity("old", "chat", "friend", "old", 1))
        val initialRead = CompletableDeferred<Unit>()
        val writerStarted = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.IO) {
            initialRead.await()
            writerStarted.complete(Unit)
            db.messageDao().insertMessage(MessageEntity("new", "chat", "friend", "new", 2))
            db.fileTransferDao().insertTransferIgnore(FileTransferEntity(
                "a".repeat(32), "new-file", "chat", "friend", "INCOMING", "file.txt", "text/plain", 1L, 1, 1,
                "hash", "OFFERED", createdAtMs = 2, expiresAtMs = 10, updatedAtMs = 2,
            ))
        }
        val snapshot = ChatClearSnapshot(db) { initialRead.complete(Unit); writerStarted.await() }.capture("chat")
        writer.join()
        assertTrue("old" in snapshot.ids)
        assertFalse("new" in snapshot.ids)
        assertFalse("new-file" in snapshot.ids)
        assertFalse("file-${"a".repeat(32)}" in snapshot.ids)
        assertTrue(db.messageDao().getMessageById("new") != null)
        assertTrue(db.fileTransferDao().getTransfer("a".repeat(32)) != null)
    }

    @Test fun capturesExistingFileMessageAndTransferAliasButNotOtherChats() = runBlocking {
        db.chatDao().insertChat(ChatEntity("chat", "friend", "Friend"))
        db.chatDao().insertChat(ChatEntity("other", "other-peer", "Other"))
        db.messageDao().insertMessage(MessageEntity("other-message", "other", "other-peer", "other", 1))
        db.fileTransferDao().insertTransferIgnore(FileTransferEntity(
            "b".repeat(32), "file-message", "chat", "friend", "OUTGOING", "file.txt", "text/plain", 1L, 1, 1,
            "hash", "PREPARED", createdAtMs = 1, expiresAtMs = 10, updatedAtMs = 1,
        ))
        val snapshot = ChatClearSnapshot(db).capture("chat")
        assertTrue("file-message" in snapshot.ids)
        assertTrue("file-${"b".repeat(32)}" in snapshot.ids)
        assertFalse("other-message" in snapshot.ids)
    }
}
