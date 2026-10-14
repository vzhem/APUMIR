package com.vladimir.messenger.data.diagnostics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Private fixtures only. Never opens the installed application's actual relay store. */
@RunWith(AndroidJUnit4::class)
class RelayStorageInspectorInstrumentedTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val root = File(app.cacheDir, "relay-audit-test-${UUID.randomUUID()}")
    private val file get() = File(root, "apu_relay.sqlite")
    private val now = 1_700_000_000_000L

    @Before fun setUp() { root.mkdirs() }
    @After fun tearDown() { root.deleteRecursively() }

    private fun open(): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null).apply {
        enableWriteAheadLogging()
        execSQL("CREATE TABLE relay_messages(msg_id TEXT PRIMARY KEY,recipient TEXT,origin_sender TEXT,e2e_payload BLOB,created_at_ms INTEGER,expires_at_ms INTEGER)")
        execSQL("CREATE TABLE relay_records_enc(msg_id TEXT PRIMARY KEY,recipient TEXT,envelope BLOB,expires_at_ms INTEGER)")
        execSQL("CREATE TABLE relay_quarantine(msg_id TEXT PRIMARY KEY,recipient TEXT,envelope BLOB,expires_at_ms INTEGER)")
        execSQL("CREATE TABLE relay_tombstones(msg_id TEXT PRIMARY KEY,removed_at_ms INTEGER)")
    }

    private fun inspect(budget: Long = RelayStorageInspector.QUERY_BUDGET_MS) =
        RelayStorageInspector.inspect(root, "private-owner", now, 200L * 1024 * 1024, budget)

    @Test fun missingStoreIsNotCreatedAndNoMigrationRuns() {
        val snapshot = inspect()
        assertTrue(snapshot.stores.all { it.status == "missing" })
        assertFalse(file.exists())
        assertFalse(File(root, "apu_relay.sqlite.relay.sqlite").exists())
    }

    @Test fun metadataInWalIsVisibleAndInspectionDoesNotDeleteOrCheckpoint() {
        open().use { db ->
            db.execSQL("INSERT INTO relay_messages VALUES ('private-id','private-peer','private-owner',?, ?, ?)",
                arrayOf("private-body".toByteArray(), now - 1000, now + 1000))
            db.execSQL("INSERT INTO relay_records_enc VALUES ('private-enc','private-owner',?, ?)",
                arrayOf("private-envelope".toByteArray(), now - 1))
            val walBefore = File(file.path + "-wal").length()
            val snapshot = inspect()
            val current = snapshot.stores.first()
            assertEquals("ready", current.status)
            assertTrue(current.walBytes > 0)
            assertEquals(1L, current.tables.first { it.kind == RelayTableKind.LEGACY }.totalRows)
            assertEquals(1, current.tables.first { it.kind == RelayTableKind.ENCRYPTED }.expired)
            assertEquals(walBefore, File(file.path + "-wal").length())
            db.rawQuery("SELECT COUNT(*) FROM relay_messages", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            assertFalse(snapshot.render().contains("private-"))
            assertFalse(snapshot.render().contains(root.path))
        }
    }

    @Test fun deletedLargeRecordLeavesReusablePagesNotLivePayload() {
        open().use { db ->
            db.execSQL("INSERT INTO relay_records_enc VALUES ('large','peer',zeroblob(2097152), ?)", arrayOf(now + 1000))
            db.execSQL("DELETE FROM relay_records_enc WHERE msg_id='large'")
            db.execSQL("INSERT INTO relay_records_enc VALUES ('small','peer',zeroblob(8), ?)", arrayOf(now + 1000))
            val current = inspect().stores.first()
            assertTrue(current.freePages!! > 0)
            val encrypted = current.tables.first { it.kind == RelayTableKind.ENCRYPTED }
            assertEquals(1L, encrypted.totalRows)
            assertEquals(8L, encrypted.bytes)
            assertEquals(1, encrypted.sampled)
        }
    }

    @Test fun tableSamplingIsBoundedButTotalCountIsSeparate() {
        open().use { db ->
            db.beginTransaction()
            try {
                repeat(600) { db.execSQL("INSERT INTO relay_records_enc VALUES (?, 'peer', zeroblob(8), ?)", arrayOf("id-$it", now + 1000)) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            val encrypted = inspect().stores.first().tables.first { it.kind == RelayTableKind.ENCRYPTED }
            assertEquals(600L, encrypted.totalRows)
            assertEquals(RelayStorageInspector.SAMPLE_ROWS_PER_TABLE, encrypted.sampled)
            assertTrue(encrypted.limited)
        }
    }

    @Test fun exhaustedBudgetIsUnknownNotAnEmptyQueueAndKeepsRecords() {
        open().use { db ->
            db.execSQL("INSERT INTO relay_records_enc VALUES ('kept','peer',zeroblob(8), ?)", arrayOf(now + 1000))
            val current = inspect(0L).stores.first()
            assertEquals("timeout", current.status)
            assertNull(current.pages)
            db.rawQuery("SELECT COUNT(*) FROM relay_records_enc", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        }
    }
}
