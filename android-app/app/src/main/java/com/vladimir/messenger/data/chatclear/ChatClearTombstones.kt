package com.vladimir.messenger.data.chatclear

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** Durable identity- and peer-scoped suppression of late copies. Not wired into message ingestion yet. */
internal class ChatClearTombstones(context: Context, file: File = File(context.noBackupFilesDir, "chat_clear.sqlite")) {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, file.absolutePath, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE cleared (owner TEXT NOT NULL, peer TEXT NOT NULL, message_id TEXT NOT NULL, PRIMARY KEY(owner, peer, message_id))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    /** No eviction: fail without acknowledging when the safety quota is exhausted. */
    @Synchronized fun remember(owner: String, peer: String, ids: List<String>): Boolean {
        require(owner.isNotBlank() && peer.isNotBlank() && owner.length <= 512 && peer.length <= 512)
        require(ids.isNotEmpty() && ids.size <= ChatClearWire.MAX_IDS && ids.distinct().size == ids.size)
        ChatClearWire.encode(ChatClearWire.Batch(java.util.UUID.randomUUID(), ids))
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val count = db.rawQuery("SELECT COUNT(*) FROM cleared WHERE owner=? AND peer=?", arrayOf(owner, peer)).use {
                it.moveToFirst(); it.getLong(0)
            }
            val total = db.rawQuery("SELECT COUNT(*) FROM cleared", null).use { it.moveToFirst(); it.getLong(0) }
            var added = 0
            for (id in ids) {
                val inserted = db.insertWithOnConflict("cleared", null, ContentValues().apply {
                    put("owner", owner); put("peer", peer); put("message_id", id)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (inserted != -1L) added++
            }
            if (count + added > MAX_PER_PEER || total + added > MAX_TOTAL) return false
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized fun contains(owner: String, peer: String, id: String): Boolean =
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM cleared WHERE owner=? AND peer=? AND message_id=? LIMIT 1", arrayOf(owner, peer, id),
        ).use { it.moveToFirst() }

    @Synchronized fun close() = helper.close()

    companion object {
        private const val MAX_PER_PEER = 100_000
        private const val MAX_TOTAL = 1_000_000
    }
}
