package com.vladimir.messenger.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One-time recovery for the acceptance harness that migrated outside Room and left its old hash. */
@RunWith(AndroidJUnit4::class)
class FileTransferMigrationIdentityRecoveryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun staleRoomIdentityIsRepairedByIdempotentRoomOwnedMigration() {
        val raw = openAtVersionSix()
        val stale = raw.writableDatabase
        assertEquals(6, stale.version)
        assertEquals(OLD_V5_IDENTITY, identityHash(stale))
        val before = legacyState(stale)
        assertEquals(0, scalarInt(stale, "SELECT COUNT(*) FROM file_transfers"))
        assertEquals(0, scalarInt(stale, "SELECT COUNT(*) FROM file_transfer_chunks"))

        // Re-arm the additive idempotent migration. Room, not this test, will own the upgrade,
        // perform full schema validation and atomically write the generated v6 identity hash.
        stale.execSQL("PRAGMA user_version = 5")
        assertEquals(5, stale.version)
        raw.close()

        val room = Room.databaseBuilder(context, AppDatabase::class.java, "messenger_database")
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .build()
        val repaired = room.openHelper.writableDatabase
        assertEquals(6, repaired.version)
        assertEquals(EXPECTED_V6_IDENTITY, identityHash(repaired))
        assertEquals(before, legacyState(repaired))
        assertEquals(0, scalarInt(repaired, "SELECT COUNT(*) FROM file_transfers"))
        assertEquals(0, scalarInt(repaired, "SELECT COUNT(*) FROM file_transfer_chunks"))
        room.close()
    }

    private fun openAtVersionSix(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("messenger_database")
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        throw AssertionError("Recovery requires the existing production database")
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        throw AssertionError("Recovery preflight must see exact stale v6 state")
                    }
                })
                .build()
        )

    private fun identityHash(db: SupportSQLiteDatabase): String =
        db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun legacyState(db: SupportSQLiteDatabase): LegacyState = LegacyState(
        chats = tableState(db, "chats"),
        messages = tableState(db, "messages"),
        contacts = tableState(db, "contacts"),
        proxies = tableState(db, "mtproto_proxies"),
    )

    private fun tableState(db: SupportSQLiteDatabase, table: String): TableState {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        db.query("SELECT id FROM `$table` ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0).toByteArray(Charsets.UTF_8)
                digest.update(id.size.toString().toByteArray(Charsets.US_ASCII))
                digest.update(byteArrayOf(0))
                digest.update(id)
                count++
            }
        }
        return TableState(count, digest.digest().toHex())
    }

    private fun scalarInt(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class LegacyState(
        val chats: TableState,
        val messages: TableState,
        val contacts: TableState,
        val proxies: TableState,
    )

    private data class TableState(val count: Int, val idSetSha256: String)

    companion object {
        private const val OLD_V5_IDENTITY = "451378b646892da8c31c499c2f93fff5"
        private const val EXPECTED_V6_IDENTITY = "1600df8ca0acdcfc6be40910e2e5eee0"
    }
}
