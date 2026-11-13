package com.vladimir.messenger.data.push

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound checkpoint for [PushWakeRecoveryScan]. Scope, initial wake
 * revision, cursor, failure bit, visited count and compare-and-set token are
 * authenticated together using Android Keystore;
 * only a random token is stored outside the ciphertext. The database lives in
 * noBackupFilesDir by default. Missing keys, malformed data and failed writes
 * fail closed rather than silently advancing a scan. Deleting or restoring the
 * whole database cannot be detected; a later periodic scan is still required.
 *
 * SQLite serializes transitions across instances/processes; the random token
 * prevents an old snapshot from replacing a newer one even after a reset or a
 * scope change, except for a negligible entropy collision. It does not lock
 * identity, restore, pin or network operations. The caller must coordinate
 * those separately, and [current] must not block or re-enter this store.
 * No route, scope, key, cursor or exception text is logged.
 */
internal class PushWakeRecoveryCheckpointStore(
    context: Context,
    private val databaseFile: File = File(context.applicationContext.noBackupFilesDir, "push_wake_recovery_v1.sqlite"),
    private val keyAlias: String = "apu_push_wake_recovery_v1",
    private val random: SecureRandom = SecureRandom(),
) : PushWakeRecoveryProgress {
    private val app = context.applicationContext
    private var helper: SQLiteOpenHelper? = null

    override fun load(scope: String, revision: Long, current: () -> Boolean): PushWakeRecoveryProgress.Result {
        if (!isHash(scope) || revision <= 0) return PushWakeRecoveryProgress.Result.Unavailable
        return transact(current) { db ->
            val old = readStored(db)
            if (old != null && old.scope == scope) {
                if (revision < old.startRevision) return@transact null
                // Do not restart an unfinished pass when a new wake arrives:
                // finish its remaining routes, then rescan the newer revision.
                if (old.after != null || revision == old.startRevision) return@transact old
            }
            // A different marker/lifecycle, or a newer revision before any
            // route in a pass, starts from the beginning and fences old writers.
            val fresh = snapshot(scope, revision, null, false, 0, old?.token)
            write(db, old, fresh)
            fresh
        }
    }

    override fun advance(
        expected: PushWakeRecoveryProgress.Snapshot,
        after: String,
        failed: Boolean,
        current: () -> Boolean,
    ): PushWakeRecoveryProgress.Result {
        if (!valid(expected) || !isHash(after) || expected.visited >= PushWakeRecoveryProgress.MAX_SCAN_ROUTES ||
            expected.after != null && after <= expected.after
        ) return PushWakeRecoveryProgress.Result.Unavailable
        return change(expected, current) { old ->
            snapshot(old.scope, old.startRevision, after, old.hadFailure || failed, old.visited + 1, old.token)
        }
    }

    override fun reset(
        expected: PushWakeRecoveryProgress.Snapshot,
        current: () -> Boolean,
    ): PushWakeRecoveryProgress.Result {
        if (!valid(expected)) return PushWakeRecoveryProgress.Result.Unavailable
        return change(expected, current) { old -> snapshot(old.scope, old.startRevision, null, false, 0, old.token) }
    }

    private fun change(
        expected: PushWakeRecoveryProgress.Snapshot,
        current: () -> Boolean,
        transition: (PushWakeRecoveryProgress.Snapshot) -> PushWakeRecoveryProgress.Snapshot,
    ): PushWakeRecoveryProgress.Result = transact(current) { db ->
        val old = readStored(db) ?: return@transact null
        if (!same(old, expected)) return@transact null
        val next = transition(old)
        write(db, old, next)
        next
    }

    /** A null result means the caller's expected state is stale. */
    private fun transact(
        current: () -> Boolean,
        operation: (SQLiteDatabase) -> PushWakeRecoveryProgress.Snapshot?,
    ): PushWakeRecoveryProgress.Result = synchronized(LOCK) {
        try {
            if (!current()) return@synchronized PushWakeRecoveryProgress.Result.Stale
            val db = db()
            db.beginTransaction()
            try {
                if (!current()) return@synchronized PushWakeRecoveryProgress.Result.Stale
                val state = operation(db) ?: return@synchronized PushWakeRecoveryProgress.Result.Stale
                // A failed check rolls back even an already issued SQLite write.
                if (!current()) return@synchronized PushWakeRecoveryProgress.Result.Stale
                db.setTransactionSuccessful()
                PushWakeRecoveryProgress.Result.Ready(state)
            } finally { db.endTransaction() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Recovery checkpoint unavailable: ${error.javaClass.simpleName}")
            PushWakeRecoveryProgress.Result.Unavailable
        }
    }

    fun close() = synchronized(LOCK) { helper?.close(); helper = null }

    private fun db(): SQLiteDatabase {
        if (helper == null) {
            helper = object : SQLiteOpenHelper(app, databaseFile.absolutePath, null, 1) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE recovery_progress (id INTEGER PRIMARY KEY NOT NULL CHECK(id=1), token TEXT NOT NULL, payload BLOB NOT NULL)")
                }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    error("unsupported push recovery checkpoint migration")
                }
            }
        }
        return helper!!.writableDatabase
    }

    private fun readStored(db: SQLiteDatabase): PushWakeRecoveryProgress.Snapshot? = db.rawQuery(
        "SELECT typeof(token), length(token), length(CAST(token AS BLOB)), " +
            "CASE WHEN typeof(token)='text' AND length(token)=64 AND length(CAST(token AS BLOB))=64 THEN token ELSE NULL END, " +
            "typeof(payload), length(payload), " +
            "CASE WHEN typeof(payload)='blob' AND length(payload) BETWEEN 30 AND 320 THEN payload ELSE NULL END " +
            "FROM recovery_progress WHERE id=1", null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        check(cursor.getString(0) == "text" && cursor.getInt(1) == 64 && cursor.getInt(2) == 64)
        val token = cursor.getString(3) ?: error("invalid recovery token")
        check(isHash(token) && token.any { it != '0' })
        check(cursor.getString(4) == "blob" && cursor.getInt(5) in 30..320)
        val blob = cursor.getBlob(6) ?: error("invalid recovery payload")
        val state = try { decode(decrypt(blob)) } finally { blob.fill(0) }
        check(valid(state) && state.token == token)
        state
    }

    private fun write(db: SQLiteDatabase, old: PushWakeRecoveryProgress.Snapshot?, next: PushWakeRecoveryProgress.Snapshot) {
        check(valid(next) && (old == null || old.token != next.token))
        val ciphertext = encrypt(encode(next), allowCreate = old == null)
        try {
            val values = ContentValues().apply { put("token", next.token); put("payload", ciphertext) }
            if (old == null) {
                values.put("id", 1)
                check(db.insertOrThrow("recovery_progress", null, values) != -1L)
            } else {
                check(db.update("recovery_progress", values, "id=1 AND token=?", arrayOf(old.token)) == 1)
            }
        } finally { ciphertext.fill(0) }
    }

    private fun snapshot(
        scope: String, revision: Long, after: String?, failed: Boolean, visited: Int, previous: String?,
    ): PushWakeRecoveryProgress.Snapshot {
        repeat(4) {
            val bytes = ByteArray(32)
            try {
                random.nextBytes(bytes)
                val token = bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
                if (token.any { it != '0' } && token != previous) {
                    return PushWakeRecoveryProgress.Snapshot(scope, revision, after, failed, visited, token)
                }
            } finally { bytes.fill(0) }
        }
        error("push recovery entropy unavailable")
    }

    private fun same(a: PushWakeRecoveryProgress.Snapshot, b: PushWakeRecoveryProgress.Snapshot): Boolean =
        a.token == b.token && a.scope == b.scope && a.startRevision == b.startRevision &&
            a.after == b.after && a.hadFailure == b.hadFailure && a.visited == b.visited

    private fun valid(value: PushWakeRecoveryProgress.Snapshot): Boolean =
        isHash(value.scope) && value.startRevision > 0 && isHash(value.token) && value.token.any { it != '0' } &&
            (value.after == null || isHash(value.after)) &&
            (if (value.after == null) value.visited == 0 && !value.hadFailure
            else value.visited in 1..PushWakeRecoveryProgress.MAX_SCAN_ROUTES)

    private fun isHash(value: String): Boolean = HASH.matches(value)

    private fun encode(value: PushWakeRecoveryProgress.Snapshot): ByteArray =
        "APUWREC1|${value.scope}|${value.startRevision}|${value.after ?: "-"}|${if (value.hadFailure) 1 else 0}|${value.visited}|${value.token}"
            .toByteArray(Charsets.US_ASCII)

    private fun decode(bytes: ByteArray): PushWakeRecoveryProgress.Snapshot = try {
        check(bytes.all { it.toInt() in 32..126 })
        val parts = bytes.toString(Charsets.US_ASCII).split('|')
        check(parts.size == 7 && parts[0] == "APUWREC1" && REVISION.matches(parts[2]) &&
            parts[4] in listOf("0", "1") && COUNT.matches(parts[5]))
        val revision = parts[2].toLongOrNull() ?: error("invalid recovery revision")
        PushWakeRecoveryProgress.Snapshot(parts[1], revision, parts[3].takeUnless { it == "-" }, parts[4] == "1", parts[5].toInt(), parts[6])
    } finally { bytes.fill(0) }

    private fun secretKey(allowCreate: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(allowCreate) { "push recovery key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    private fun encrypt(plaintext: ByteArray, allowCreate: Boolean): ByteArray = try {
        check(plaintext.size in 1..291)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(allowCreate))
        cipher.updateAAD(AAD)
        check(cipher.iv.size == 12)
        byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
    } finally { plaintext.fill(0) }

    private fun decrypt(blob: ByteArray): ByteArray {
        check(blob.size in 30..320 && blob[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(false), GCMParameterSpec(128, blob.copyOfRange(1, 13)))
        cipher.updateAAD(AAD)
        return cipher.doFinal(blob.copyOfRange(13, blob.size))
    }

    private companion object {
        const val TAG = "PushRecoveryCheckpoint"
        val LOCK = Any()
        val HASH = Regex("^[0-9a-f]{64}$")
        val REVISION = Regex("^[1-9][0-9]{0,18}$")
        val COUNT = Regex("^(0|[1-9][0-9]{0,3})$")
        val AAD = "apu-push-wake-recovery-v1\u0000".toByteArray(Charsets.US_ASCII)
    }
}
