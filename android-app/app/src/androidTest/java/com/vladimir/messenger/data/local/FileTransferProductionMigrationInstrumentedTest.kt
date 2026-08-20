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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Data-preserving acceptance for an existing v5 application database. */
@RunWith(AndroidJUnit4::class)
class FileTransferProductionMigrationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun existingDatabaseMigratesToSixWithoutChangingLegacyRows() {
        assertTrue(context.getDatabasePath("messenger_database").isFile)
        var before: LegacyState? = null
        var after: LegacyState? = null
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("messenger_database")
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        throw AssertionError("Production migration gate requires an existing database")
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        assertEquals(5, oldVersion)
                        assertEquals(6, newVersion)
                        before = legacyState(db)
                        AppDatabase.MIGRATION_5_6.migrate(db)
                        after = legacyState(db)
                        assertEquals(before, after)
                    }
                })
                .build()
        )
        helper.writableDatabase
        helper.close()
        assertNotNull(before)
        assertEquals(before, after)

        // Opening through generated Room code validates the complete v6 schema.
        val room = Room.databaseBuilder(context, AppDatabase::class.java, "messenger_database")
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .build()
        val db = room.openHelper.writableDatabase
        assertEquals(6, db.version)
        assertEquals(0, scalarInt(db, "SELECT COUNT(*) FROM file_transfers"))
        assertEquals(0, scalarInt(db, "SELECT COUNT(*) FROM file_transfer_chunks"))
        assertEquals(before, legacyState(db))
        room.close()
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
}
