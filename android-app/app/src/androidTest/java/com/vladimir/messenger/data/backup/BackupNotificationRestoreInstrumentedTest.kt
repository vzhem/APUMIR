package com.vladimir.messenger.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladimir.messenger.data.local.APP_DATABASE_VERSION
import com.vladimir.messenger.data.security.DeviceIdentityMarker
import com.vladimir.messenger.service.DisposableNotificationTestProfile
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Full staged restore on a disposable emulator with isolated app-private paths and prefs. */
@RunWith(AndroidJUnit4::class)
class BackupNotificationRestoreInstrumentedTest {
    private lateinit var real: Context
    private lateinit var root: File
    private lateinit var sandbox: SandboxContext

    @Before fun setUp() {
        real = ApplicationProvider.getApplicationContext()
        // ProfileBackup can also reset an auto-backup WorkManager name on
        // replacement. Never run it against a real user's installation.
        DisposableNotificationTestProfile.requireEmptyEmulator(real)
        root = File(real.cacheDir, "restore-notification-${UUID.randomUUID()}").apply { check(mkdirs()) }
        sandbox = SandboxContext(real, root)
        assertTrue(sandbox.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).edit()
            .putString("node_id", OWNER).commit())
    }

    @After fun tearDown() {
        if (::sandbox.isInitialized) sandbox.clearTestPreferences()
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun replaceConsumesWalBeforeMovingDatabaseAndNeverRearmsHistory() {
        val live = sandbox.getDatabasePath("messenger_database")
        createLive(live, listOf(Triple("local", "local work", 1)))
        withStagedWal("v2-replace") { original ->
            assertTrue(ProfileBackup.applyStagedIfAny(sandbox))
            assertEquals(
                mapOf("base" to ("base updated in wal" to 0), "wal" to ("wal history" to 0)),
                readRows(live),
            )
            assertOriginalUnchanged(original)
            // Replacement keeps the previous database as a safety copy.
            assertTrue(File(sandbox.noBackupFilesDir, "restore_safety").listFiles()?.any { it.isDirectory } == true)
        }
    }

    @Test fun populatedMergeImportsWalHistoryButKeepsLiveNotificationWork() {
        val live = sandbox.getDatabasePath("messenger_database")
        createLive(live, listOf(Triple("base", "live wins", 1), Triple("local", "local work", 1)))
        withStagedWal("v2-merge") { original ->
            assertTrue(ProfileBackup.applyStagedIfAny(sandbox))
            assertEquals(
                mapOf("base" to ("live wins" to 1), "local" to ("local work" to 1), "wal" to ("wal history" to 0)),
                readRows(live),
            )
            assertOriginalUnchanged(original)
        }
    }

    @Test fun firstInstallMergeCopiesCheckpointedWalHistoryWithoutPendingWork() {
        val live = sandbox.getDatabasePath("messenger_database")
        assertFalse(live.exists())
        withStagedWal("v2-merge") { original ->
            assertTrue(ProfileBackup.applyStagedIfAny(sandbox))
            assertEquals(
                mapOf("base" to ("base updated in wal" to 0), "wal" to ("wal history" to 0)),
                readRows(live),
            )
            assertOriginalUnchanged(original)
        }
    }

    @Test fun sanitizerFailureStopsBeforeAnyLiveDatabaseOrIdentityMutation() {
        val live = sandbox.getDatabasePath("messenger_database")
        createLive(live, listOf(Triple("local", "local work", 1)))
        assertFalse(DeviceIdentityMarker.isPresent(sandbox))
        withStagedWal("v2-replace", refuseSanitizer = true) { original ->
            assertFalse(ProfileBackup.applyStagedIfAny(sandbox))
            assertEquals(mapOf("local" to ("local work" to 1)), readRows(live))
            assertFalse(DeviceIdentityMarker.isPresent(sandbox))
            assertOriginalUnchanged(original)
        }
    }

    /** Copy a genuine base+WAL while the source is open; one row and one update exist only in WAL. */
    private fun withStagedWal(mode: String, refuseSanitizer: Boolean = false, test: (SQLiteDatabase) -> Unit) {
        val original = File(root, "source.sqlite")
        val staging = File(sandbox.noBackupFilesDir, "restore_pending")
        val stagedDb = File(staging, BackupLayout.DB)
        check(stagedDb.parentFile!!.mkdirs())
        SQLiteDatabase.openOrCreateDatabase(original, null).use { db ->
            assertTrue(db.enableWriteAheadLogging())
            createMessages(db)
            db.execSQL("INSERT INTO messages VALUES ('base', 'base before wal', 1)")
            if (refuseSanitizer) {
                db.execSQL("CREATE TRIGGER refuse_notification_clear BEFORE UPDATE OF notificationPending ON messages " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic sanitizer failure'); END")
            }
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0)); assertEquals(0, cursor.getInt(1)); assertEquals(0, cursor.getInt(2))
            }
            db.execSQL("UPDATE messages SET content = 'base updated in wal' WHERE id = 'base'")
            db.execSQL("INSERT INTO messages VALUES ('wal', 'wal history', 1)")
            val wal = File(original.path + "-wal")
            assertTrue("fixture must contain committed WAL frames", wal.isFile && wal.length() > 0L)
            original.copyTo(stagedDb, overwrite = true)
            wal.copyTo(File(staging, BackupLayout.DB_WAL), overwrite = true)
            File(staging, BackupLayout.MANIFEST).writeText(
                BackupManifest(dbVersion = APP_DATABASE_VERSION, appVersionName = "test", appVersionCode = 1,
                    createdAtMs = 1L, nodeId = OWNER, displayName = "Test", nickname = "",
                    includesReceived = false).encode(),
            )
            File(staging, ".ready").writeText(mode)
            test(db)
        }
    }

    private fun createMessages(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, content TEXT NOT NULL, notificationPending INTEGER NOT NULL DEFAULT 0)")
    }

    private fun createLive(file: File, rows: List<Triple<String, String, Int>>) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createMessages(db)
            rows.forEach { (id, content, pending) ->
                db.execSQL("INSERT INTO messages VALUES (?, ?, ?)", arrayOf(id, content, pending))
            }
        }
    }

    private fun readRows(file: File): Map<String, Pair<String, Int>> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(::readRows)

    private fun readRows(db: SQLiteDatabase): Map<String, Pair<String, Int>> =
        db.rawQuery("SELECT id, content, notificationPending FROM messages ORDER BY id", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1) to cursor.getInt(2)) }
        }

    private fun assertOriginalUnchanged(original: SQLiteDatabase) {
        assertEquals(mapOf("base" to ("base updated in wal" to 1), "wal" to ("wal history" to 1)), readRows(original))
    }

    /** All database/files/prefs in the restore path are independent of the target app profile. */
    private class SandboxContext(private val real: Context, private val root: File) : ContextWrapper(real) {
        private val prefix = "restore_test_${UUID.randomUUID()}_"
        private val preferenceNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile!!.mkdirs() }
        override fun deleteDatabase(name: String): Boolean {
            val file = getDatabasePath(name)
            return listOf("", "-wal", "-shm", "-journal").map { File(file.path + it) }
                .all { !it.exists() || it.delete() }
        }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val safeName = prefix + name
            preferenceNames += safeName
            return real.getSharedPreferences(safeName, mode)
        }
        fun clearTestPreferences() { preferenceNames.forEach(real::deleteSharedPreferences) }
    }

    private companion object { const val OWNER = "pk_restore_fixture" }
}
