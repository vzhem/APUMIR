package com.vladimir.messenger.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Планирование слияния базы: живой файл главнее, из копии добавляется только
 * недостающее. Сам SQLite здесь не нужен - [BackupRestoreMerge.plan] чистая.
 */
class BackupRestoreMergeTest {
    private fun column(name: String, type: String = "TEXT", notNull: Boolean = false, hasDefault: Boolean = false) =
        BackupRestoreMerge.Column(name = name, type = type, notNull = notNull, hasDefault = hasDefault)

    private fun table(name: String, vararg columns: BackupRestoreMerge.Column) =
        BackupRestoreMerge.Table(name = name, columns = columns.toList())

    private fun inserts(steps: List<BackupRestoreMerge.Step>) =
        steps.filterIsInstance<BackupRestoreMerge.Step.Insert>().associateBy { it.table }

    @Test
    fun `common columns are inserted and live table is never rewound`() {
        val live = listOf(
            table("messages", column("id"), column("chatId"), column("text"), column("createdAt")),
        )
        val copy = listOf(
            table("messages", column("id"), column("chatId"), column("text"), column("createdAt")),
        )

        val step = inserts(BackupRestoreMerge.plan(live, copy)).getValue("messages")

        assertEquals(
            "INSERT OR IGNORE INTO \"messages\" (\"id\", \"chatId\", \"text\", \"createdAt\") " +
                "SELECT copy.\"id\", copy.\"chatId\", copy.\"text\", copy.\"createdAt\" FROM copy.\"messages\"",
            step.sql,
        )
        assertTrue("строка копии не должна перезаписывать живую", step.sql.startsWith("INSERT OR IGNORE"))
    }

    @Test
    fun `column with its own default is left to sqlite`() {
        val live = listOf(
            table("messages", column("id"), column("state", notNull = true, hasDefault = true)),
        )
        val copy = listOf(table("messages", column("id")))

        val step = inserts(BackupRestoreMerge.plan(live, copy)).getValue("messages")

        assertEquals(
            "INSERT OR IGNORE INTO \"messages\" (\"id\") SELECT copy.\"id\" FROM copy.\"messages\"",
            step.sql,
        )
    }

    @Test
    fun `new not-null column without default gets a value of its type`() {
        val live = listOf(
            table(
                "messages",
                column("id"),
                column("pinned", "INTEGER", notNull = true),
                column("note", "TEXT", notNull = true),
            ),
        )
        val copy = listOf(table("messages", column("id")))

        val step = inserts(BackupRestoreMerge.plan(live, copy)).getValue("messages")

        assertEquals(
            "INSERT OR IGNORE INTO \"messages\" (\"id\", \"pinned\", \"note\") " +
                "SELECT copy.\"id\", 0, '' FROM copy.\"messages\"",
            step.sql,
        )
    }

    @Test
    fun `nullable column missing in copy is filled with null`() {
        val live = listOf(table("messages", column("id"), column("quoteId")))
        val copy = listOf(table("messages", column("id")))

        val step = inserts(BackupRestoreMerge.plan(live, copy)).getValue("messages")

        assertEquals(
            "INSERT OR IGNORE INTO \"messages\" (\"id\", \"quoteId\") " +
                "SELECT copy.\"id\", NULL FROM copy.\"messages\"",
            step.sql,
        )
    }

    @Test
    fun `table missing in copy is skipped, not recreated empty`() {
        val live = listOf(table("chats", column("id")), table("drafts", column("id")))
        val copy = listOf(table("chats", column("id")))

        val steps = BackupRestoreMerge.plan(live, copy)

        assertEquals(setOf("chats"), inserts(steps).keys)
        val skipped = steps.filterIsInstance<BackupRestoreMerge.Step.Skip>().single()
        assertEquals("drafts", skipped.table)
    }

    @Test
    fun `service tables are never merged`() {
        val live = listOf(
            table("android_metadata", column("locale")),
            table("room_master_table", column("id")),
            table("chats", column("id")),
        )
        val copy = listOf(
            table("android_metadata", column("locale")),
            table("room_master_table", column("id")),
            table("chats", column("id")),
        )

        assertEquals(setOf("chats"), inserts(BackupRestoreMerge.plan(live, copy)).keys)
    }

    @Test
    fun `table without common columns is skipped instead of duplicating rows`() {
        val live = listOf(table("legacy", column("aOld")))
        val copy = listOf(table("legacy", column("bNew")))

        val steps = BackupRestoreMerge.plan(live, copy)

        assertTrue(inserts(steps).isEmpty())
        assertEquals("legacy", steps.filterIsInstance<BackupRestoreMerge.Step.Skip>().single().table)
    }

    @Test
    fun `quotes in identifiers are escaped`() {
        val live = listOf(table("we\"ird", column("i\"d")))
        val copy = listOf(table("we\"ird", column("i\"d")))

        val sql = inserts(BackupRestoreMerge.plan(live, copy)).getValue("we\"ird").sql

        assertTrue(sql, sql.contains("\"we\"\"ird\""))
        assertTrue(sql, sql.contains("\"i\"\"d\""))
    }

    @Test
    fun `summary never contains chat text`() {
        val outcome = BackupRestoreMerge.Outcome(
            merged = true,
            insertedRows = 7,
            perTable = listOf("messages" to 7),
        )

        assertEquals("дополнено таблиц=1 строк=7", outcome.summary())
        assertTrue(outcome.changed)
    }
}
