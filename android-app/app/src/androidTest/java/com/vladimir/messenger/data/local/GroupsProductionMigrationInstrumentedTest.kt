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

/**
 * Приёмка миграции 7 -> 8 на настоящей базе телефона.
 *
 * GroupsMigrationInstrumentedTest проверяет SQL миграции на пустой копии базы и
 * при этом не проходит через RoomOpenHelper. Этот тест закрывает ровно то, что
 * остаётся непроверенным: Room сам открывает messenger_database, сам вызывает
 * MIGRATION_7_8 и MIGRATION_8_9, сам сверяет получившуюся схему со своей (типы столбцов,
 * NOT NULL, DEFAULT, индексы) и сам обновляет identity hash в
 * room_master_table. Расхождение там даёт "Migration didn't properly handle"
 * и падение приложения при старте.
 *
 * fallbackToDestructiveMigration намеренно НЕ подключён: если миграция неверна,
 * тест обязан упасть, а не молча пересоздать базу.
 */
@RunWith(AndroidJUnit4::class)
class GroupsProductionMigrationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun existingDatabaseMigratesToEightWithoutChangingLegacyRows() {
        assertTrue(
            "messenger_database not found: open the installed build once, then rerun",
            context.getDatabasePath("messenger_database").isFile,
        )

        val preflight = openAtVersion(7)
        val before: LegacyState
        try {
            val db = preflight.writableDatabase
            assertEquals("expected a version 7 database", 7, db.version)
            before = legacyState(db)
        } finally {
            preflight.close()
        }

        // Room owns the upgrade: it runs MIGRATION_7_8 and MIGRATION_8_9,
        // validates the resulting schema against the entities and rewrites the
        // identity hash.
        val room = Room.databaseBuilder(context, AppDatabase::class.java, "messenger_database")
            .addMigrations(
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
            )
            .build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(10, db.version)

            // Ни одна старая строка не переписана и не потеряна.
            assertEquals(before, legacyState(db))

            // Старые сообщения не получили тему и не стали закреплёнными.
            assertEquals(0, scalarInt(db, "SELECT COUNT(*) FROM messages WHERE topicId IS NOT NULL"))
            assertEquals(0, scalarInt(db, "SELECT COUNT(*) FROM messages WHERE isPinned <> 0"))

            // Новые таблицы существуют и пусты.
            listOf(
                "groups",
                "group_members",
                "group_topics",
                "group_join_requests",
                "group_invites",
                "group_message_stats",
                "directory",
            ).forEach { table ->
                assertEquals("table $table must exist and be empty", 0, scalarInt(db, "SELECT COUNT(*) FROM $table"))
            }
        } finally {
            room.close()
        }
    }

    /** Читает базу как v7 и не даёт ни создать, ни мигрировать её. */
    private fun openAtVersion(version: Int): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("messenger_database")
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
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

                        override fun onDowngrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            throw AssertionError(
                                "database is at version " + oldVersion +
                                    ", expected 7: the migration already ran on this phone",
                            )
                        }
                    },
                )
                .build(),
        )

    private fun legacyState(db: SupportSQLiteDatabase): LegacyState = LegacyState(
        chats = idState(db, "chats"),
        messages = idState(db, "messages"),
        contacts = idState(db, "contacts"),
        proxies = idState(db, "mtproto_proxies"),
        messagePayload = payloadState(db),
    )

    private fun idState(db: SupportSQLiteDatabase, table: String): TableState {
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

    /** Отдельно текст и время сообщений: ids могли бы совпасть при потерянном теле. */
    private fun payloadState(db: SupportSQLiteDatabase): TableState {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        db.query("SELECT id, content, timestamp FROM messages ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                digest.update(cursor.getString(0).toByteArray(Charsets.UTF_8))
                digest.update(byteArrayOf(0))
                digest.update(cursor.getString(1).toByteArray(Charsets.UTF_8))
                digest.update(byteArrayOf(0))
                digest.update(cursor.getLong(2).toString().toByteArray(Charsets.US_ASCII))
                digest.update(byteArrayOf(0))
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
        val messagePayload: TableState,
    )

    private data class TableState(val count: Int, val idSetSha256: String)
}
