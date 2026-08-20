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

/** Data-preserving acceptance for an untouched existing v5 application database. */
@RunWith(AndroidJUnit4::class)
class FileTransferProductionMigrationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun existingDatabaseMigratesToSevenWithoutChangingLegacyRows() {
        assertTrue(context.getDatabasePath("messenger_database").isFile)
        val v5 = openAtVersion(5)
        val before = legacyState(v5.writableDatabase)
        assertEquals(5, v5.writableDatabase.version)
        v5.close()

        // Room owns the upgrade so it validates schema and updates room_master_table identity hash.
        val room = Room.databaseBuilder(context, AppDatabase::class.java, "messenger_database")
            .addMigrations(AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7)
            .build()
        val db = room.openHelper.writableDatabase
        assertEquals(7, db.version)
        assertEquals(before, legacyState(db))
        assertEquals(0, scalarInt(db, "SELECT COUNT(*) FROM file_transfers"))
        assertEquals(0, scalarInt(db, "SELECT COUNT(*) FROM file_transfer_chunks"))
        assertEquals(0, scalarInt(db, "SELECT COUNT(*) FROM file_exchange_peers"))
        room.close()
    }

    private fun openAtVersion(version: Int): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("messenger_database")
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        throw AssertionError("Production migration gate requires an existing database")
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        throw AssertionError("Read-only preflight must not migrate")
                    }
                })
                .build()
        )

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
}
