package com.vladimir.messenger.data.notification

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.backup.BackupNotificationState
import com.vladimir.messenger.data.backup.BackupRestoreMerge
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.data.local.entity.GroupEntity
import com.vladimir.messenger.data.local.entity.MessageEntity
import com.vladimir.messenger.data.mirror.MirrorAccountSnapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationArrivalInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "notification_arrival_test.db"
    private val files = mutableListOf<File>()

    @After fun cleanup() {
        context.deleteDatabase(databaseName)
        files.forEach { file -> listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() } }
    }

    private fun row(id: String, timestamp: Long, pending: Boolean = true, recipient: String = "") =
        MessageEntity(id, "chat", "friend", "body-$id", timestamp,
            recipientId = recipient, notificationPending = pending)

    private fun temporary(name: String): File = File(context.cacheDir, "notification-test-$name.sqlite").also {
        files += it
        listOf("", "-wal", "-shm", "-journal").forEach { suffix -> File(it.path + suffix).delete() }
    }

    @Test fun migrationFortyToFortyOnePreservesHistoryAndRoomValidatesSchema() = runBlocking {
        context.deleteDatabase(databaseName)
        val original = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        original.messageDao().insertMessage(row("legacy", 123L))
        original.close()

        // Construct a genuine v40-shaped database from the current schema. API 35
        // supports DROP COLUMN; production migrations only use ADD COLUMN.
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP INDEX index_messages_notification_pending")
            db.execSQL("DROP INDEX index_messages_outbox_status_order")
            db.execSQL("ALTER TABLE messages DROP COLUMN notificationPending")
            db.version = 40
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_40_41, AppDatabase.MIGRATION_41_42)
            .build()
        try {
            val sql = migrated.openHelper.writableDatabase // Room validates the complete migrated schema
            assertEquals(42, sql.version)
            val legacy = migrated.messageDao().getMessageById("legacy")!!
            assertEquals("body-legacy", legacy.content)
            assertEquals(123L, legacy.timestamp)
            assertFalse(legacy.notificationPending)
            assertTrue(migrated.messageDao().observePendingNotifications("me").first().isEmpty())
            sql.query("PRAGMA index_info(`index_messages_notification_pending`)").use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertEquals(listOf("isFromMe", "notificationPending"), names)
            }
        } finally {
            migrated.close()
        }
    }

    @Test fun migrationFortyOneToFortyTwoPreservesRowsAndIndexesSentRetries() = runBlocking {
        context.deleteDatabase(databaseName)
        val original = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        original.messageDao().insertMessage(row("sent", 123L).copy(isFromMe = true, status = "SENT"))
        original.messageDao().insertMessage(row("delivered", 124L).copy(isFromMe = true, status = "DELIVERED"))
        original.close()
        // Return the fresh schema to its genuine v41 index layout.
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP INDEX index_messages_outbox_status_order")
            db.version = 41
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_41_42).build()
        try {
            val sql = migrated.openHelper.writableDatabase
            assertEquals(42, sql.version)
            assertEquals("body-sent", migrated.messageDao().getMessageById("sent")!!.content)
            assertEquals("DELIVERED", migrated.messageDao().getMessageById("delivered")!!.status)
            sql.query("PRAGMA index_info(`index_messages_outbox_status_order`)").use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertEquals(listOf("isFromMe", "status", "timestamp", "id"), names)
            }
            sql.query("EXPLAIN QUERY PLAN SELECT id FROM messages WHERE isFromMe = 1 AND status = 'SENT' ORDER BY timestamp, id").use { cursor ->
                val details = mutableListOf<String>()
                while (cursor.moveToNext()) details += cursor.getString(cursor.getColumnIndexOrThrow("detail"))
                assertTrue(details.any { it.contains("index_messages_outbox_status_order") })
            }
        } finally { migrated.close() }
    }

    @Test fun delayedArrivalsAreClaimedOnceAndSurviveRestartWithoutRearmingDuplicates() = runBlocking {
        context.deleteDatabase(databaseName)
        var db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        try {
            val dao = db.messageDao()
            dao.insertMessageIgnore(row("old-sender-clock", 1L, recipient = "me"))
            dao.insertMessageIgnore(row("future-sender-clock", Long.MAX_VALUE))
            dao.insertMessageIgnore(row("history", 10L, pending = false))
            dao.insertMessageIgnore(row("other-recipient", 20L, recipient = "someone-else"))
            dao.insertMessageIgnore(row("outgoing", 30L).copy(isFromMe = true))
            assertEquals(listOf("old-sender-clock", "future-sender-clock"),
                dao.observePendingNotifications("me").first().map { it.id })
            val claims = coroutineScope {
                (1..4).map { async(Dispatchers.IO) { dao.claimPendingNotification("old-sender-clock") } }.awaitAll()
            }
            assertEquals(1, claims.sum())
            assertEquals(-1L, dao.insertMessageIgnore(row("old-sender-clock", 999L)))
            assertFalse(dao.getMessageById("old-sender-clock")!!.notificationPending)
        } finally {
            db.close()
        }
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        try {
            val dao = db.messageDao()
            assertEquals(listOf("future-sender-clock"), dao.observePendingNotifications("me").first().map { it.id })
            assertEquals(1, dao.claimPendingNotification("future-sender-clock"))
            assertEquals(0, dao.claimPendingNotification("future-sender-clock"))
            assertTrue(dao.observePendingNotifications("me").first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun boundedQueueContinuesAfterFirstBatchIsClaimed() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.messageDao()
            repeat(515) { dao.insertMessageIgnore(row("message-$it", it.toLong())) }
            val first = dao.observePendingNotifications("me").first()
            assertEquals(512, first.size)
            first.forEach { assertEquals(1, dao.claimPendingNotification(it.id)) }
            val remainder = dao.observePendingNotifications("me").first()
            assertEquals(listOf("message-512", "message-513", "message-514"), remainder.map { it.id })
        } finally {
            db.close()
        }
    }

    @Test fun mirrorSnapshotsNeverExportOrApplyLocalNotificationWork() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val group = GroupEntity(id = "group", title = "Group", ownerId = "owner")
            source.groupDao().insertGroup(group)
            target.groupDao().insertGroup(group)
            source.messageDao().insertMessage(row("group-message", 5L).copy(chatId = "group"))
            val packet = MirrorAccountSnapshot.exportRows(source).single {
                it.optString("table") == "messages" && it.getJSONObject("values").optString("id") == "group-message"
            }
            val values = packet.getJSONObject("values")
            assertFalse(values.has("notificationPending"))
            values.put("notificationPending", 1) // even a peer sending the field cannot arm a history import
            assertEquals(1, MirrorAccountSnapshot.applyRows(target, listOf(packet)))
            assertFalse(target.messageDao().getMessageById("group-message")!!.notificationPending)
            target.openHelper.writableDatabase.execSQL("UPDATE messages SET notificationPending = 1 WHERE id = 'group-message'")
            values.put("notificationPending", 0)
            assertEquals(1, MirrorAccountSnapshot.applyRows(target, listOf(packet)))
            assertTrue(target.messageDao().getMessageById("group-message")!!.notificationPending)
        } finally {
            source.close()
            target.close()
        }
    }

    @Test fun backupMergeDoesNotImportPendingWorkOrClearLiveWork() {
        val live = temporary("live")
        val copy = temporary("copy")
        fun create(file: File, id: String) {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, content TEXT, notificationPending INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("INSERT INTO messages VALUES (?, 'preserved', 1)", arrayOf(id))
            }
        }
        create(live, "live")
        create(copy, "history")
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO messages VALUES ('live', 'older copy', 0)")
        }
        val first = BackupRestoreMerge.merge(live, copy)
        assertTrue(first.reason, first.merged)
        assertEquals(1, first.insertedRows)
        val repeated = BackupRestoreMerge.merge(live, copy)
        assertTrue(repeated.reason, repeated.merged)
        assertEquals(0, repeated.insertedRows)
        SQLiteDatabase.openDatabase(live.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id, content, notificationPending FROM messages ORDER BY id", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("history", c.getString(0)); assertEquals("preserved", c.getString(1)); assertEquals(0, c.getInt(2))
                assertTrue(c.moveToNext())
                assertEquals("live", c.getString(0)); assertEquals("preserved", c.getString(1)); assertEquals(1, c.getInt(2))
            }
        }
    }

    @Test fun backupMergeRollsBackEveryTableWhenAnInsertFails() {
        val live = temporary("rollback-live")
        val copy = temporary("rollback-copy")
        listOf(live, copy).forEach { file ->
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                // Insertion order makes the successful table precede the failing one.
                db.execSQL("CREATE TABLE first_table (id TEXT PRIMARY KEY)")
                db.execSQL("CREATE TABLE last_table (id TEXT PRIMARY KEY)")
                if (file == copy) {
                    db.execSQL("INSERT INTO first_table VALUES ('history')")
                    db.execSQL("INSERT INTO last_table VALUES ('rejected')")
                } else {
                    db.execSQL("INSERT INTO first_table VALUES ('live')")
                    db.execSQL("CREATE TRIGGER refuse_restore BEFORE INSERT ON last_table BEGIN SELECT RAISE(ABORT, 'blocked'); END")
                }
            }
        }
        val outcome = BackupRestoreMerge.merge(live, copy)
        assertFalse(outcome.merged)
        assertFalse(outcome.changed)
        assertTrue(outcome.reason.isNotBlank())
        SQLiteDatabase.openDatabase(live.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id FROM first_table ORDER BY id", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("live", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test fun stagedBackupSanitizerReadsWalAndLeavesTheOriginalUntouched() {
        val original = temporary("source-wal")
        val staged = temporary("staged-wal")
        SQLiteDatabase.openOrCreateDatabase(original, null).use { db ->
            assertTrue(db.enableWriteAheadLogging())
            db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, content TEXT, notificationPending INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO messages VALUES ('msg', 'history', 1)")
            original.copyTo(staged, overwrite = true)
            val wal = File(original.path + "-wal")
            assertTrue(wal.isFile && wal.length() > 0)
            wal.copyTo(File(staged.path + "-wal"), overwrite = true)
            BackupNotificationState.clearFromStaged(staged)
            db.rawQuery("SELECT notificationPending FROM messages", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
            }
        }
        SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT content, notificationPending FROM messages", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("history", c.getString(0)); assertEquals(0, c.getInt(1))
            }
        }
        val legacy = temporary("legacy")
        SQLiteDatabase.openOrCreateDatabase(legacy, null).use { db ->
            db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, content TEXT)")
            db.execSQL("INSERT INTO messages VALUES ('old', 'kept')")
        }
        BackupNotificationState.clearFromStaged(legacy)
        SQLiteDatabase.openDatabase(legacy.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT content FROM messages", null).use { c -> assertTrue(c.moveToFirst()); assertEquals("kept", c.getString(0)) }
        }
    }
}
