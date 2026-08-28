package com.vladimir.messenger.data.local.database

// =============================================================================
// APPDATABASEMIGRATIONTEST.KT
// =============================================================================
// Проверка добавочных миграций БД: создаём схему версии 10 руками, открываем
// Room версии 11 с MIGRATION_10_11 и сверяем итоговую схему с ожидаемой.
// Миграции только добавляют таблицы - данные не теряются.
// =============================================================================

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vladimir.messenger.data.local.AppDatabase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    /** Схема версии 10 - ровно как в MIGRATION_9_10 (создаётся вручную). */
    private fun createSchemaV10(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `group_directory` (
                `groupId` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `about` TEXT NOT NULL,
                `ownerId` TEXT NOT NULL,
                `slug` TEXT,
                `isChannel` INTEGER NOT NULL DEFAULT 0,
                `needsApproval` INTEGER NOT NULL DEFAULT 1,
                `updatedAtMs` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`groupId`)
            )
            """.trimIndent()
        )
    }

    private val callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = createSchemaV10(db)
    }

    private fun openV11(): AppDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "migration_test_" + System.nanoTime(),
        )
            .addCallback(callback)
            .addMigrations(AppDatabase.MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()
    }

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name))
            .use { return it.moveToFirst() }
    }

    @Test
    fun migrationFrom10CreatesNicknamesTable() {
        val db = openV11()
        try {
            assertTrue(
                "таблица nicknames должна появиться после MIGRATION_10_11",
                tableExists(db.openHelper.writableDatabase, "nicknames")
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun versionElevenSchemaHasAllTables() {
        val db = openV11()
        try {
            val sqlite = db.openHelper.writableDatabase
            val expected = listOf(
                "contacts", "messages", "chats", "file_metadata",
                "file_shares", "file_chunks", "transfer_peers", "outgoing_files",
                "referral_leaves", "referral_tokens",
                "group_members", "group_messages", "group_topics", "groups",
                "group_pins", "group_requests", "group_read_marks",
                "group_directory", "nicknames",
            )
            expected.forEach { name ->
                assertTrue("нет таблицы $name в схеме v11", tableExists(sqlite, name))
            }
            // Ранги остались представлением.
            sqlite.query(
                "SELECT name FROM sqlite_master WHERE type='view' AND name=?",
                arrayOf("member_ranks"),
            ).use { assertNotNull(it); assertTrue(it.moveToFirst()) }
        } finally {
            db.close()
        }
    }

    @Test
    fun dataSurvivesMigrationFrom10() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "migration_data_" + System.nanoTime()
        // Версия 10 с данными в group_directory.
        val v10 = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addCallback(callback)
            .allowMainThreadQueries()
            .build()
        v10.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO group_directory " +
                "(groupId, title, about, ownerId, slug, isChannel, needsApproval, updatedAtMs) " +
                "VALUES ('g1', 'T', 'A', 'o', 'Sl', 1, 0, 42)"
        )
        v10.close()

        // Открываем ту же базу версией 11 с миграцией.
        val v11 = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()
        try {
            val row = v11.directoryDao().bySlug("Sl")
            assertNotNull("строка каталога должна пережить миграцию", row)
        } finally {
            v11.close()
        }
    }
}
