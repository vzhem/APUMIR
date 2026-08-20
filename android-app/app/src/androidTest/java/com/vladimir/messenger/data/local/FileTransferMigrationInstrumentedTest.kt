package com.vladimir.messenger.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileTransferMigrationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "file_transfer_migration_test.db"

    @Test
    fun migrationFiveToSixIsAdditiveAndChunkCascadeWorks() {
        context.deleteDatabase(databaseName)
        try {
            open(5, object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE legacy_sentinel (id INTEGER NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                    db.execSQL("INSERT INTO legacy_sentinel (id, value) VALUES (1, 'preserved')")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).close()

            val migrated = open(6, object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    assertEquals(5, oldVersion)
                    assertEquals(6, newVersion)
                    AppDatabase.MIGRATION_5_6.migrate(db)
                }
            })
            val db = migrated.writableDatabase
            db.execSQL("PRAGMA foreign_keys=ON")
            db.query("SELECT value FROM legacy_sentinel WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("preserved", cursor.getString(0))
            }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='file_transfers'").use {
                assertTrue(it.moveToFirst())
            }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='file_transfer_chunks'").use {
                assertTrue(it.moveToFirst())
            }

            db.execSQL(
                """
                INSERT INTO file_transfers (
                    transferId, messageId, chatId, peerNodeId, direction, displayName, mediaType,
                    totalBytes, chunkSize, chunkCount, fileSha256, state, completedChunks,
                    transferredBytes, createdAtMs, expiresAtMs, updatedAtMs, errorCode
                ) VALUES (
                    't1', 'm1', 'c1', 'pk_0123456789abcdef0123456789abcdef', 'OUTGOING',
                    'a.bin', 'application/octet-stream', 1, 131072, 1,
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'OFFERED', 0, 0, 1, 2, 1, NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO file_transfer_chunks (
                    transferId, chunkIndex, state, ciphertextBytes, chunkSha256, updatedAtMs
                ) VALUES ('t1', 0, 'STORED', 17, NULL, 1)
                """.trimIndent()
            )
            db.execSQL("DELETE FROM file_transfers WHERE transferId = 't1'")
            db.query("SELECT COUNT(*) FROM file_transfer_chunks WHERE transferId = 't1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun open(
        version: Int,
        callback: SupportSQLiteOpenHelper.Callback,
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(callback)
            .build()
    ).also { it.writableDatabase.version = version }
}
