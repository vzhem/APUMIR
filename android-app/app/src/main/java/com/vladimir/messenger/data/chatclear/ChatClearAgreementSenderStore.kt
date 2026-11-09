package com.vladimir.messenger.data.chatclear

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID

/** Durable, immutable responder snapshots. No network or deletion side effects. */
internal class ChatClearAgreementSenderStore(context: Context, file: File = File(context.noBackupFilesDir, "chat_clear_agreement_sender.sqlite")) {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, file.absolutePath, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE proposals (operation TEXT PRIMARY KEY NOT NULL, owner TEXT NOT NULL, peer TEXT NOT NULL, digest TEXT NOT NULL)")
            db.execSQL("CREATE TABLE pages (operation TEXT NOT NULL, page_index INTEGER NOT NULL, packet TEXT NOT NULL, acknowledged INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(operation, page_index))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    /** An identical retry reuses the original snapshot; an operation cannot be rebound. */
    @Synchronized fun stage(owner: String, peer: String, operation: UUID, ids: List<String>): Boolean {
        require(owner.isNotBlank() && peer.isNotBlank() && owner != peer && owner.length <= 512 && peer.length <= 512)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val key = operation.toString()
            val existing = db.rawQuery("SELECT owner, peer FROM proposals WHERE operation=?", arrayOf(key)).use {
                if (it.moveToFirst()) it.getString(0) == owner && it.getString(1) == peer else null
            }
            if (existing != null) return existing
            val pages = ChatClearAgreementWire.pages(operation, ids)
            val proposals = db.rawQuery("SELECT COUNT(*) FROM proposals", null).use { it.moveToFirst(); it.getLong(0) }
            val pageCount = db.rawQuery("SELECT COUNT(*) FROM pages", null).use { it.moveToFirst(); it.getLong(0) }
            if (proposals >= MAX_PROPOSALS || pageCount + pages.size > MAX_PAGES) return false
            db.insertOrThrow("proposals", null, ContentValues().apply { put("operation", key); put("owner", owner); put("peer", peer); put("digest", pages.first().digest) })
            for (page in pages) db.insertOrThrow("pages", null, ContentValues().apply {
                put("operation", key); put("page_index", page.index); put("packet", ChatClearAgreementWire.encode(page))
            })
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    /** Caller must authenticate the incoming request before staging and sending these packets. */
    @Synchronized fun pending(owner: String, peer: String, operation: UUID): List<String> {
        val db = helper.readableDatabase
        val key = operation.toString()
        val packets = mutableListOf<String>()
        db.rawQuery("SELECT pages.packet FROM pages JOIN proposals ON pages.operation=proposals.operation WHERE proposals.operation=? AND proposals.owner=? AND proposals.peer=? AND pages.acknowledged=0 ORDER BY pages.page_index", arrayOf(key, owner, peer)).use {
            while (it.moveToNext()) packets.add(it.getString(0))
        }
        return packets
    }

    /** Only the expected authenticated peer can acknowledge an existing page of this exact snapshot. */
    @Synchronized fun acknowledge(owner: String, peer: String, authenticated: Boolean, text: String): Boolean {
        if (!authenticated || owner.isBlank() || peer.isBlank()) return false
        val receipt = ChatClearAgreementControl.parseReceipt(text) ?: return false
        val db = helper.writableDatabase
        val key = receipt.operation.toString()
        db.beginTransaction()
        try {
            val match = db.rawQuery("SELECT 1 FROM proposals JOIN pages ON proposals.operation=pages.operation WHERE proposals.operation=? AND proposals.owner=? AND proposals.peer=? AND proposals.digest=? AND pages.page_index=?", arrayOf(key, owner, peer, receipt.digest, receipt.index.toString())).use { it.moveToFirst() }
            if (!match) return false
            db.update("pages", ContentValues().apply { put("acknowledged", 1) }, "operation=? AND page_index=?", arrayOf(key, receipt.index.toString()))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized fun remove(owner: String, peer: String, operation: UUID): Boolean {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val key = operation.toString()
            if (db.delete("proposals", "operation=? AND owner=? AND peer=?", arrayOf(key, owner, peer)) != 1) return false
            db.delete("pages", "operation=?", arrayOf(key))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized fun close() = helper.close()

    companion object {
        private const val MAX_PROPOSALS = 64
        private const val MAX_PAGES = 8192
    }
}
