package com.vladimir.messenger.data.chatclear

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID

/** Persistent, owner- and peer-bound proposal pages. No method performs deletion. */
internal class ChatClearAgreementStore(context: Context, file: File = File(context.noBackupFilesDir, "chat_clear_agreements.sqlite")) {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, file.absolutePath, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE offers (operation TEXT PRIMARY KEY NOT NULL, owner TEXT NOT NULL, peer TEXT NOT NULL, total INTEGER, digest TEXT)")
            db.execSQL("CREATE TABLE offer_pages (operation TEXT NOT NULL, page_index INTEGER NOT NULL, packet TEXT NOT NULL, PRIMARY KEY(operation, page_index))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    /** Register an expected operation before accepting any pages. */
    @Synchronized fun begin(owner: String, peer: String, operation: UUID): Boolean {
        require(owner.isNotBlank() && peer.isNotBlank() && owner != peer && owner.length <= 512 && peer.length <= 512)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val key = operation.toString()
            val existing = db.rawQuery("SELECT owner, peer FROM offers WHERE operation=?", arrayOf(key)).use {
                if (it.moveToFirst()) it.getString(0) == owner && it.getString(1) == peer else null
            }
            if (existing != null) return existing
            val count = db.rawQuery("SELECT COUNT(*) FROM offers", null).use { it.moveToFirst(); it.getLong(0) }
            if (count >= MAX_OFFERS) return false
            db.insertOrThrow("offers", null, ContentValues().apply { put("operation", key); put("owner", owner); put("peer", peer) })
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    /** Only return success after this exact authenticated page is durably committed. */
    @Synchronized fun accept(owner: String, sender: String, authenticated: Boolean, packet: String): Boolean {
        if (!authenticated || owner.isBlank() || sender.isBlank()) return false
        val page = ChatClearAgreementWire.decode(packet) ?: return false
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val key = page.operation.toString()
            val meta = db.rawQuery("SELECT owner, peer, total, digest FROM offers WHERE operation=?", arrayOf(key)).use {
                if (!it.moveToFirst()) return false
                if (it.getString(0) != owner || it.getString(1) != sender) return false
                if (it.isNull(2)) null else it.getInt(2) to it.getString(3)
            }
            if (meta != null && (meta.first != page.total || meta.second != page.digest)) return false
            val previous = db.rawQuery("SELECT packet FROM offer_pages WHERE operation=? AND page_index=?", arrayOf(key, page.index.toString())).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (previous != null) return previous == packet
            val count = db.rawQuery("SELECT COUNT(*) FROM offer_pages", null).use { it.moveToFirst(); it.getLong(0) }
            if (count >= MAX_TOTAL_PAGES) return false
            if (meta == null) db.update("offers", ContentValues().apply { put("total", page.total); put("digest", page.digest) }, "operation=?", arrayOf(key))
            db.insertOrThrow("offer_pages", null, ContentValues().apply { put("operation", key); put("page_index", page.index); put("packet", packet) })
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized fun preview(owner: String, peer: String, operation: UUID): List<String>? {
        if (owner.isBlank() || peer.isBlank()) return null
        val db = helper.readableDatabase
        val key = operation.toString()
        val matching = db.rawQuery("SELECT 1 FROM offers WHERE operation=? AND owner=? AND peer=?", arrayOf(key, owner, peer)).use { it.moveToFirst() }
        if (!matching) return null
        val agreement = ChatClearAgreement(owner, peer, operation)
        db.query("offer_pages", arrayOf("packet"), "operation=?", arrayOf(key), null, null, "page_index ASC").use {
            while (it.moveToNext()) if (!agreement.accept(owner, peer, true, it.getString(0))) return null
        }
        return agreement.preview(owner)
    }

    /** Explicit cancellation or completed delivery may remove the saved proposal. */
    @Synchronized fun remove(owner: String, peer: String, operation: UUID): Boolean {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val key = operation.toString()
            val removed = db.delete("offers", "operation=? AND owner=? AND peer=?", arrayOf(key, owner, peer))
            if (removed == 0) return false
            db.delete("offer_pages", "operation=?", arrayOf(key))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized fun close() = helper.close()

    companion object {
        private const val MAX_OFFERS = 64
        private const val MAX_TOTAL_PAGES = 8192
    }
}
