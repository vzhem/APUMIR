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
class MessageIndexesMigrationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "message_indexes_migration_test.db"

    @Test
    fun migrationTwentyTwoToTwentyThreeAddsBothIndexesAndPreservesRows() {
        context.deleteDatabase(databaseName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(22) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE messages (" +
                                    "id TEXT NOT NULL PRIMARY KEY, " +
                                    "chatId TEXT NOT NULL, " +
                                    "isFromMe INTEGER NOT NULL, " +
                                    "timestamp INTEGER NOT NULL, " +
                                    "content TEXT NOT NULL)"
                            )
                            db.execSQL(
                                "INSERT INTO messages (id, chatId, isFromMe, timestamp, content) " +
                                    "VALUES ('msg-1', 'chat-1', 0, 1720000000000, 'preserved')"
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            assertEquals(22, db.version)
            AppDatabase.MIGRATION_22_23.migrate(db)

            db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND " +
                    "name IN ('index_messages_chatId_timestamp', 'index_messages_isFromMe_timestamp')"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            db.query("SELECT chatId, content FROM messages WHERE id = 'msg-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("chat-1", cursor.getString(0))
                assertEquals("preserved", cursor.getString(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }
}
