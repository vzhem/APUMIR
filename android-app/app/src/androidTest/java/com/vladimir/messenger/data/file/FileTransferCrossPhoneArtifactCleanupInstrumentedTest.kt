package com.vladimir.messenger.data.file

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vladimir.messenger.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Removes only synthetic chat rows that the first harness service may have consumed. */
@RunWith(AndroidJUnit4::class)
class FileTransferCrossPhoneArtifactCleanupInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun removesOnlySyntheticFileHarnessMessagesAndRepairsChatSummary() {
        val expectedSender = InstrumentationRegistry.getArguments().getString("expected_sender")
            ?: error("Missing expected sender")
        val room = Room.databaseBuilder(context, AppDatabase::class.java, "messenger_database")
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .build()
        try {
            val db = room.openHelper.writableDatabase
            val rows = mutableListOf<SyntheticRow>()
            db.query(
                """
                SELECT id, chatId, senderId, content
                FROM messages
                WHERE content LIKE 'APUFILETEST1|%'
                  AND (id LIKE 'offer-file-%' OR id LIKE 'chunk-file-%'
                       OR id LIKE 'offer-photo-%' OR id LIKE 'chunk-photo-%')
                """.trimIndent()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = SyntheticRow(
                        id = cursor.getString(0),
                        chatId = cursor.getString(1),
                        senderId = cursor.getString(2),
                        content = cursor.getString(3),
                    )
                    assertEquals(expectedSender, row.senderId)
                    assertTrue(row.content.startsWith("APUFILETEST1|"))
                    rows += row
                }
            }
            assertTrue(rows.size <= 4)
            db.beginTransaction()
            try {
                rows.groupBy { it.chatId }.forEach { (chatId, chatRows) ->
                    chatRows.forEach { row ->
                        db.execSQL("DELETE FROM messages WHERE id = ?", arrayOf(row.id))
                    }
                    db.execSQL(
                        """
                        UPDATE chats
                        SET lastMessage = (
                                SELECT content FROM messages
                                WHERE chatId = ? ORDER BY timestamp DESC LIMIT 1
                            ),
                            lastMessageTime = (
                                SELECT timestamp FROM messages
                                WHERE chatId = ? ORDER BY timestamp DESC LIMIT 1
                            ),
                            unreadCount = MAX(0, unreadCount - ?)
                        WHERE id = ?
                        """.trimIndent(),
                        arrayOf(chatId, chatId, chatRows.size, chatId),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            assertEquals(
                0,
                db.query("SELECT COUNT(*) FROM messages WHERE content LIKE 'APUFILETEST1|%'").use {
                    assertTrue(it.moveToFirst())
                    it.getInt(0)
                },
            )
        } finally {
            room.close()
        }
    }

    private data class SyntheticRow(
        val id: String,
        val chatId: String,
        val senderId: String,
        val content: String,
    )
}
