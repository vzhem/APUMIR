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

/**
 * Миграция 7 -> 8 (группы и темы).
 *
 * Хостовый гейт этого не покрывает: KSP проверяет согласованность сущностей и
 * DAO на этапе компиляции, а сам SQL миграции исполняется только при открытии
 * существующей базы v7. Ошибка здесь опасна тем, что в AppModule включён
 * fallbackToDestructiveMigration(), поэтому тест проверяет миграцию на
 * отдельной базе и до реального обновления на телефоне.
 *
 * Главное требование — аддитивность: существующие сообщения личных чатов не
 * переписываются и не теряются.
 */
@RunWith(AndroidJUnit4::class)
class GroupsMigrationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "groups_migration_test.db"

    @Test
    fun migrationSevenToEightKeepsMessagesAndAddsGroupTables() {
        context.deleteDatabase(databaseName)
        try {
            // 1. База версии 7: таблица messages в прежнем виде и одно сообщение.
            open(7, object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE `messages` (
                            `id` TEXT NOT NULL,
                            `chatId` TEXT NOT NULL,
                            `senderId` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `status` TEXT NOT NULL,
                            `isFromMe` INTEGER NOT NULL,
                            `replyToId` TEXT,
                            `channel` TEXT NOT NULL,
                            `recipientId` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        "INSERT INTO messages (id, chatId, senderId, content, timestamp, " +
                            "status, isFromMe, replyToId, channel, recipientId) " +
                            "VALUES ('m_old', 'c1', 'pk_sender', 'сохранённое сообщение', 1000, " +
                            "'SENT', 0, NULL, 'LAN', 'pk_me')"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).close()

            // 2. Открываем как v9 и прогоняем обе миграции: 7 -> 8 (группы)
            // и 8 -> 9 (каналы).
            val migrated = open(9, object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    assertEquals(7, oldVersion)
                    assertEquals(9, newVersion)
                    AppDatabase.MIGRATION_7_8.migrate(db)
                    AppDatabase.MIGRATION_8_9.migrate(db)
                }
            })
            val db = migrated.writableDatabase
            db.execSQL("PRAGMA foreign_keys=ON")
            db.query("PRAGMA foreign_keys").use { cursor ->
                assertTrue("PRAGMA foreign_keys не применилась к соединению", cursor.moveToFirst())
                assertEquals(
                    "внешние ключи выключены, каскад не сработает и тест соврёт",
                    1,
                    cursor.getInt(0),
                )
            }

            // 3. Старое сообщение цело, новые столбцы для него пусты.
            db.query("SELECT content, topicId, isPinned, pinnedAtMs, pinnedBy FROM messages WHERE id = 'm_old'")
                .use { cursor ->
                    assertTrue("старое сообщение потерялось", cursor.moveToFirst())
                    assertEquals("сохранённое сообщение", cursor.getString(0))
                    assertTrue("topicId у старого сообщения должен быть NULL", cursor.isNull(1))
                    assertEquals("isPinned по умолчанию обязан быть 0", 0, cursor.getInt(2))
                    assertTrue(cursor.isNull(3))
                    assertTrue(cursor.isNull(4))
                }

            // 4. Все шесть новых таблиц появились.
            listOf(
                "groups",
                "group_members",
                "group_topics",
                "group_join_requests",
                "group_invites",
                "group_message_stats",
            ).forEach { table ->
                db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'")
                    .use { cursor -> assertTrue("нет таблицы $table", cursor.moveToFirst()) }
            }

            // 5. Индексы созданы под именами, которые ожидает Room.
            listOf(
                "index_group_members_groupId",
                "index_group_members_nodeId",
                "index_group_topics_groupId",
                "index_group_join_requests_groupId",
                "index_group_join_requests_status",
                "index_group_invites_groupId",
                "index_group_invites_slug",
                "index_group_message_stats_groupId",
            ).forEach { index ->
                db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='$index'")
                    .use { cursor -> assertTrue("нет индекса $index", cursor.moveToFirst()) }
            }

            // 6. Группа, участник и тема пишутся; cascade по группе работает.
            db.execSQL(
                "INSERT INTO groups (id, title, about, ownerId, ownerName, isPublic, topicsEnabled, " +
                    "createdAtMs, memberCount, inviteSlug, memberPermissions, isLeft, " +
                    "lastMessagePreview, lastMessageAtMs, unreadCount) " +
                    "VALUES ('g1', 'Тест', '', 'pk_me', 'Я', 0, 1, 1, 1, 'Abcdefghijkmnopq', 31, 0, NULL, NULL, 0)"
            )
            db.execSQL(
                "INSERT INTO group_members (groupId, nodeId, displayName, role, joinedAtMs, " +
                    "permissions, customTitle, isBanned) " +
                    "VALUES ('g1', 'pk_me', 'Я', 'OWNER', 1, 255, '', 0)"
            )
            db.execSQL(
                "INSERT INTO group_topics (id, groupId, name, ownerId, ownerName, iconEmoji, " +
                    "createdAtMs, messageCount, unreadCount, lastMessagePreview, lastMessageAtMs, " +
                    "isClosed, isGeneral) " +
                    "VALUES ('t1', 'g1', 'General', 'pk_me', 'Я', '', 1, 0, 0, NULL, NULL, 0, 1)"
            )
            db.query("SELECT COUNT(*) FROM group_members WHERE groupId = 'g1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("участник группы не записался", 1, cursor.getInt(0))
            }

            // 7. Сообщение темы сохраняется в общую таблицу messages с topicId.
            db.execSQL(
                "INSERT INTO messages (id, chatId, senderId, content, timestamp, status, isFromMe, " +
                    "replyToId, channel, recipientId, topicId, isPinned, pinnedAtMs, pinnedBy) " +
                    "VALUES ('m_new', 'g1', 'pk_me', 'в теме', 2000, 'SENT', 1, NULL, 'GROUP', '', 't1', 1, 2000, 'pk_me')"
            )
            db.query("SELECT COUNT(*) FROM messages WHERE topicId = 't1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("сообщение темы не сохранилось с topicId", 1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM messages WHERE topicId IS NULL").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("личное сообщение не должно было получить тему", 1, cursor.getInt(0))
            }

            // 8. Удаление группы чистит участников и темы (FOREIGN KEY ... ON DELETE CASCADE).
            db.execSQL("DELETE FROM groups WHERE id = 'g1'")
            db.query("SELECT COUNT(*) FROM group_members WHERE groupId = 'g1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ON DELETE CASCADE не снял участников", 0, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM group_topics WHERE groupId = 'g1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ON DELETE CASCADE не снял темы", 0, cursor.getInt(0))
            }

            // 9. Итог: сообщений осталось два — старое личное и новое групповое.
            db.query("SELECT COUNT(*) FROM messages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ожидались старое личное и новое групповое сообщения", 2, cursor.getInt(0))
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
