package com.vladimir.messenger.data.chatclear

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.vladimir.messenger.data.file.FileTransferWire
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import java.io.File

/** Durable retry evidence for application-private files that can be recreated by in-flight work. */
internal class ChatClearFileJournal(context: Context, private val file: File = File(context.noBackupFilesDir, "chat_clear_files.sqlite")) {
    data class Entry(val peer: String, val chat: String, val transferId: String, val messageId: String)

    private val helper = object : SQLiteOpenHelper(context.applicationContext, file.absolutePath, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE cleared_files (owner TEXT NOT NULL, peer TEXT NOT NULL, chat TEXT NOT NULL, transfer_id TEXT NOT NULL, message_id TEXT NOT NULL, PRIMARY KEY(owner, transfer_id))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Synchronized fun remember(owner: String, peer: String, chat: String, rows: List<FileTransferEntity>): Boolean {
        require(owner.isNotBlank() && peer.isNotBlank() && chat.isNotBlank())
        require(owner.length <= 512 && peer.length <= 512 && chat.length <= 512 && rows.size <= MAX_TOTAL)
        rows.forEach {
            require(it.chatId == chat && it.messageId.isNotBlank() && it.messageId.length <= 512)
            FileTransferWire.requireValidTransferId(it.transferId)
        }
        if (rows.isEmpty()) return true
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val total = db.rawQuery("SELECT COUNT(*) FROM cleared_files", null).use { it.moveToFirst(); it.getLong(0) }
            var added = 0
            for (row in rows) {
                val inserted = db.insertWithOnConflict("cleared_files", null, ContentValues().apply {
                    put("owner", owner); put("peer", peer); put("chat", chat)
                    put("transfer_id", row.transferId); put("message_id", row.messageId)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (inserted != -1L) added++ else {
                    val matches = db.rawQuery(
                        "SELECT 1 FROM cleared_files WHERE owner=? AND transfer_id=? AND peer=? AND chat=? AND message_id=?",
                        arrayOf(owner, row.transferId, peer, chat, row.messageId),
                    ).use { it.moveToFirst() }
                    if (!matches) return false
                }
            }
            if (total + added > MAX_TOTAL) return false
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized fun page(owner: String, after: String?, limit: Int = 32): List<Entry> {
        require(owner.isNotBlank() && limit in 1..32)
        val where = if (after == null) "owner=?" else "owner=? AND transfer_id>?"
        val args = if (after == null) arrayOf(owner) else arrayOf(owner, after)
        return helper.readableDatabase.query("cleared_files", arrayOf("peer", "chat", "transfer_id", "message_id"),
            where, args, null, null, "transfer_id ASC", limit.toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))) }
        }
    }

    fun exists(): Boolean = file.exists()

    @Synchronized fun close() = helper.close()

    companion object {
        private const val MAX_TOTAL = 1_000_000
    }
}
