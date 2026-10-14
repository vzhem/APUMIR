package com.vladimir.messenger.data.schedule

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate hidden inbox: never queried by chats, search, unread counts, backup or mirrors.
 * Payloads are AES-GCM encrypted with a device Keystore key, in noBackupFilesDir.
 * A committed row is the prerequisite for a stored receipt. Completed IDs remain tombstones.
 */
object DeferredInboxStore {
    private val storage = DeferredInboxStorage()
    fun owner(context: Context): String = storage.owner(context)
    fun entryKey(context: Context, sender: String, messageId: String): String = storage.entryKey(context, sender, messageId)
    fun put(context: Context, sender: String, letter: DeferredWire.Letter): Boolean = storage.put(context, sender, letter)
    fun pending(context: Context): List<Pair<String, Long>> = storage.pending(context)
    fun get(context: Context, key: String): DeferredInboxStorage.Entry? = storage.get(context, key)
    fun owns(context: Context, messageId: String): Boolean = storage.owns(context, messageId)
    fun complete(context: Context, key: String) = storage.complete(context, key)
    fun cancel(context: Context, sender: String, request: DeferredWire.Receipt, localIntent: Boolean = false): Boolean = storage.cancel(context, sender, request, localIntent)
    fun cancellation(context: Context, key: String): DeferredInboxStorage.Cleanup? = storage.cancellation(context, key)
    fun completeCancellation(context: Context, key: String) = storage.completeCancellation(context, key)
    fun isCancelled(context: Context, sender: String, id: String): Boolean = storage.isCancelled(context, sender, id)
}

/** Independently constructible for real SQLite/Keystore device regression tests. */
class DeferredInboxStorage(
    private val keyAlias: String = "apu_deferred_inbox_v1",
    private val databaseFile: File? = null,
) {
    data class Entry(val key: String, val owner: String, val sender: String, val letter: DeferredWire.Letter)
    data class Cleanup(val sender: String, val messageId: String)
    private val lock = Any()
    private var helper: SQLiteOpenHelper? = null

    private fun db(context: Context): SQLiteDatabase {
        if (helper == null) {
            helper = object : SQLiteOpenHelper(
                context.applicationContext,
                (databaseFile ?: File(context.noBackupFilesDir, "deferred_inbox.sqlite")).absolutePath, null, 1,
            ) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE inbox (entry_key TEXT PRIMARY KEY, owner TEXT NOT NULL, sender TEXT NOT NULL, message_id TEXT NOT NULL, show_at INTEGER NOT NULL, digest TEXT NOT NULL, payload BLOB, done INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE INDEX inbox_pending ON inbox(owner, done, show_at)")
                }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }
        }
        return helper!!.writableDatabase
    }

    fun owner(context: Context): String = context.applicationContext
        .getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE).getString("node_id", "").orEmpty()

    fun entryKey(context: Context, sender: String, messageId: String): String =
        DeferredWire.digest("${owner(context)}|$sender|$messageId")

    /** True for an identical committed duplicate, false for collision or quota exhaustion. */
    fun put(context: Context, sender: String, letter: DeferredWire.Letter): Boolean = synchronized(lock) {
        val owner = owner(context)
        check(owner.startsWith("pk_")) { "Identity not ready" }
        val wire = DeferredWire.encode(letter)
        val key = entryKey(context, sender, letter.id)
        val digest = DeferredWire.digest(wire)
        val db = db(context)
        db.query("inbox", arrayOf("digest", "done"), "entry_key=?", arrayOf(key), null, null, null).use {
            if (it.moveToFirst()) return@synchronized it.getString(0) == digest && it.getInt(1) < 2
        }
        // Bounded per peer and globally, reject without ACK rather than evict unshown messages.
        db.rawQuery("SELECT COUNT(*) FROM inbox WHERE owner=? AND done=0", arrayOf(owner)).use {
            it.moveToFirst()
            if (it.getInt(0) >= 2048) return@synchronized false
        }
        db.rawQuery("SELECT COUNT(*) FROM inbox WHERE owner=? AND sender=? AND done=0", arrayOf(owner, sender)).use {
            it.moveToFirst()
            if (it.getInt(0) >= 128) return@synchronized false
        }
        val values = ContentValues().apply {
            put("entry_key", key); put("owner", owner); put("sender", sender)
            put("message_id", letter.id); put("show_at", letter.showAtMs); put("digest", digest)
            put("payload", encrypt(wire, key))
        }
        db.insertOrThrow("inbox", null, values)
        true
    }

    fun pending(context: Context): List<Pair<String, Long>> = synchronized(lock) {
        db(context).query("inbox", arrayOf("entry_key", "show_at"), "owner=? AND done IN (0,2)",
            arrayOf(owner(context)), null, null, "show_at ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getLong(1)) }
        }
    }

    fun get(context: Context, key: String): Entry? = synchronized(lock) {
        db(context).query("inbox", arrayOf("owner", "sender", "payload"),
            "entry_key=? AND owner=? AND done=0", arrayOf(key, owner(context)), null, null, null).use {
            if (!it.moveToFirst()) return@synchronized null
            val letter = DeferredWire.parse(decrypt(it.getBlob(2), key)) ?: error("Invalid hidden letter")
            Entry(key, it.getString(0), it.getString(1), letter)
        }
    }

    fun owns(context: Context, messageId: String): Boolean = synchronized(lock) {
        db(context).rawQuery("SELECT 1 FROM inbox WHERE owner=? AND message_id=? LIMIT 1",
            arrayOf(owner(context), messageId)).use { it.moveToFirst() }
    }

    fun complete(context: Context, key: String) = synchronized(lock) {
        db(context).update("inbox", ContentValues().apply { put("done", 1); putNull("payload") },
            "entry_key=? AND owner=? AND done=0", arrayOf(key, owner(context)))
        Unit
    }

    /** Sender-scoped durable tombstone also handles cancellation arriving BEFORE the data. */
    fun cancel(context: Context, sender: String, request: DeferredWire.Receipt, localIntent: Boolean = false): Boolean = synchronized(lock) {
        val owner = owner(context)
        check(owner.startsWith("pk_"))
        val db = db(context)
        val entryKey = entryKey(context, sender, request.id)
        val exists = db.query("inbox", arrayOf("digest"), "entry_key=?", arrayOf(entryKey), null, null, null).use {
            if (it.moveToFirst()) {
                if (it.getString(0) != request.digest) return@synchronized false
                true
            } else false
        }
        if (!exists && !localIntent) {
            db.rawQuery("SELECT COUNT(*) FROM inbox WHERE owner=? AND done>=2", arrayOf(owner)).use {
                it.moveToFirst(); if (it.getInt(0) >= 16384) return@synchronized false
            }
            db.rawQuery("SELECT COUNT(*) FROM inbox WHERE owner=? AND sender=? AND done>=2", arrayOf(owner, sender)).use {
                it.moveToFirst(); if (it.getInt(0) >= 1024) return@synchronized false
            }
        }
        if (!exists) {
            db.insertOrThrow("inbox", null, ContentValues().apply {
                put("entry_key", entryKey); put("owner", owner); put("sender", sender)
                put("message_id", request.id); put("digest", request.digest); put("show_at", 0L)
                put("done", 2); putNull("payload")
            })
        } else {
            db.update("inbox", ContentValues().apply { put("done", 2); put("show_at", 0L); putNull("payload") },
                "entry_key=?", arrayOf(entryKey))
        }
        true
    }

    fun isCancelled(context: Context, sender: String, id: String): Boolean = synchronized(lock) {
        if (!id.startsWith("deferred_")) return@synchronized false
        db(context).rawQuery("SELECT 1 FROM inbox WHERE owner=? AND sender=? AND message_id=? AND done>=2 LIMIT 1",
            arrayOf(owner(context), sender, DeferredWire.headId(id))).use { it.moveToFirst() }
    }

    fun cancellation(context: Context, key: String): Cleanup? = synchronized(lock) {
        db(context).query("inbox", arrayOf("sender", "message_id"), "entry_key=? AND owner=? AND done=2",
            arrayOf(key, owner(context)), null, null, null).use {
            if (!it.moveToFirst()) null else Cleanup(it.getString(0), it.getString(1))
        }
    }

    fun completeCancellation(context: Context, key: String) = synchronized(lock) {
        db(context).update("inbox", ContentValues().apply { put("done", 3) },
            "entry_key=? AND owner=? AND done=2", arrayOf(key, owner(context)))
        Unit
    }

    fun close() = synchronized(lock) {
        helper?.close()
        helper = null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun encrypt(wire: String, aad: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.iv + cipher.doFinal(wire.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(blob: ByteArray, aad: String): String {
        check(blob.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(blob.copyOfRange(12, blob.size)).toString(Charsets.UTF_8)
    }
}
