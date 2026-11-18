package com.vladimir.messenger.data.push

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Remembers when this device last had a wake *accepted* for a correspondent, so
 * a conversation cannot turn into one provider call per message.
 *
 * The stored key is a salted SHA-256 of the peer id, never the peer id itself:
 * the file lives outside application backup and must not become a list of who
 * this person talks to. The value is only a timestamp; nothing here can be used
 * to address, wake or read anything.
 *
 * What it does NOT do: it does not prove a wake was delivered, does not block the
 * user from sending, and is not consulted for receiving. A wiped or restored file
 * means the next wake is simply allowed.
 */
internal class PushWakeBudget(
    context: Context,
    private val databaseFile: File =
        File(context.applicationContext.noBackupFilesDir, "push_wake_budget_v1.sqlite"),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext
    private var helper: SQLiteOpenHelper? = null

    /** A peer this device has no record of, as far as a budget is concerned. */
    sealed interface Last {
        /** No accepted wake is recorded: sending one is allowed. */
        object Never : Last
        class At(val atMs: Long) : Last
        /** The journal cannot be read: treat as "no record" rather than block. */
        object Unavailable : Last
    }

    /**
     * Read the last accepted wake for [peerId]. Never throws; a failure reports
     * [Last.Unavailable] and the caller decides, which is "allow a wake" - a
     * broken budget must not silence the feature.
     */
    fun last(peerId: String): Last = synchronized(LOCK) {
        val key = key(peerId) ?: return@synchronized Last.Unavailable
        try {
            db().rawQuery("SELECT at_ms FROM $TABLE WHERE peer_key = ?", arrayOf(key)).use { cursor ->
                if (!cursor.moveToFirst()) return Last.Never
                val value = cursor.getLong(0)
                return if (value > 0) Last.At(value) else Last.Never
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Wake budget unreadable: ${error.javaClass.simpleName}")
            Last.Unavailable
        }
    }

    /**
     * Record that a wake was accepted at [atMs]. Called only after the Worker
     * answered `202`: a refused or unreachable service must not consume the
     * budget, otherwise one bad minute would silence the feature for the rest of
     * the interval.
     */
    fun record(peerId: String, atMs: Long = nowMs()): Boolean = synchronized(LOCK) {
        val key = key(peerId) ?: return@synchronized false
        if (atMs <= 0) return@synchronized false
        try {
            val values = ContentValues().apply {
                put("peer_key", key)
                put("at_ms", atMs)
            }
            db().insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Wake budget write failed: ${error.javaClass.simpleName}")
            false
        }
    }

    fun close() = synchronized(LOCK) { helper?.close(); helper = null }

    private fun key(peerId: String): String? {
        if (peerId.isBlank() || peerId.length > MAX_PEER_CHARS) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DOMAIN)
            digest.update(peerId.toByteArray(Charsets.UTF_8).also { })
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun db(): SQLiteDatabase {
        if (helper == null) {
            helper = object : SQLiteOpenHelper(app, databaseFile.absolutePath, null, 1) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE $TABLE (peer_key TEXT PRIMARY KEY NOT NULL, at_ms INTEGER NOT NULL)",
                    )
                }

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    check(oldVersion == newVersion) { "unsupported wake budget migration" }
            }
        }
        return helper!!.writableDatabase
    }

    private companion object {
        const val TABLE = "wake_budget"
        const val TAG = "PushWakeBudget"
        const val MAX_PEER_CHARS = 512
        val LOCK = Any()
        val DOMAIN = "apu-push-wake-budget-v1\u0000".toByteArray(Charsets.US_ASCII)
    }
}
