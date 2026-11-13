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
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound journal of the last FCM registration token this installation
 * saw, stored only as an encrypted blob under `noBackupFilesDir`.
 *
 * Purpose is narrow and local: when the provider rotates a token, the runtime
 * must notice *that* it rotated (fingerprint change) and re-register the new
 * token with the mailbox Worker. It must not treat an unchanged token as a
 * successful registration.
 *
 * What this class does NOT do: it does not contact Firebase, does not prove the
 * token is accepted by Google, does not prove the route is registered and does
 * not affect delivery. A wipe or restore destroys the row and forces a fresh
 * registration, which is intended. The token itself is exportable only to this
 * class; callers get a fingerprint, never the plaintext token.
 */
internal class FcmTokenJournal(
    context: Context,
    private val databaseFile: File =
        File(context.applicationContext.noBackupFilesDir, "push_fcm_token_v1.sqlite"),
    private val keyAlias: String = "apu_push_fcm_token_v1",
) {
    private val app = context.applicationContext
    private var helper: SQLiteOpenHelper? = null

    sealed interface State {
        /** Nothing was recorded yet: a registration attempt is needed. */
        object Missing : State
        /** The stored token fingerprint matches the current one. */
        object Unchanged : State
        /** A different token was stored before; re-registration is required. */
        object Rotated : State
        /** The journal cannot be read; do not silently overwrite it. */
        object Unavailable : State
    }

    /**
     * Compare and remember in one transaction. [token] is never logged, stored
     * in plaintext, or returned. A corrupted journal, a lost Keystore key or a
     * failed decryption is [State.Unavailable]: the caller must fail closed and
     * must not conclude that the token is fresh.
     */
    fun record(token: String, nowMs: Long = System.currentTimeMillis()): State =
        synchronized(LOCK) {
            if (!TOKEN.matches(token)) return@synchronized State.Unavailable
            val fingerprint = PushWakeCrypto.tokenFingerprint(token) ?: return@synchronized State.Unavailable
            val plaintext = token.toByteArray(Charsets.UTF_8)
            try {
                val db = db()
                db.beginTransaction()
                try {
                    val previous = readFingerprint(db) ?: ""
                    val state = when {
                        previous.isEmpty() -> State.Missing
                        previous == fingerprint -> State.Unchanged
                        else -> State.Rotated
                    }
                    val blob = encrypt(fingerprint, plaintext, allowCreate = previous.isEmpty())
                    if (previous.isEmpty()) {
                        check(db.insertOrThrow(TABLE, null, ContentValues().apply { put(BLOB, blob) }) != -1L)
                    } else {
                        check(db.update(TABLE, ContentValues().apply { put(BLOB, blob) }, "id=1", null) == 1)
                    }
                    db.setTransactionSuccessful()
                    state
                } finally { db.endTransaction() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Never print the token, fingerprint, key alias or exception text.
                Log.w(TAG, "FCM token journal unavailable: ${error.javaClass.simpleName}")
                State.Unavailable
            } finally {
                plaintext.fill(0)
            }
        }

    /** Remove the record after a revoke, so the next token is treated as new. */
    fun forget(): Boolean = synchronized(LOCK) {
        try {
            val db = db()
            db.beginTransaction()
            try {
                val removed = db.delete(TABLE, null, null) >= 0
                db.setTransactionSuccessful()
                removed
            } finally { db.endTransaction() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "FCM token journal purge failed: ${error.javaClass.simpleName}")
            false
        }
    }

    fun close() = synchronized(LOCK) { helper?.close(); helper = null }

    private fun readFingerprint(db: SQLiteDatabase): String? =
        db.rawQuery("SELECT length($BLOB), CASE WHEN length($BLOB) BETWEEN 40 AND 4096 THEN $BLOB ELSE NULL END FROM $TABLE WHERE id=1", null)
            .use { cursor ->
                if (!cursor.moveToFirst()) return null
                check(cursor.getInt(0) in 40..4096)
                decrypt(cursor.getBlob(1))
            }

    private fun db(): SQLiteDatabase {
        if (helper == null) {
            helper = object : SQLiteOpenHelper(app, databaseFile.absolutePath, null, 1) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE $TABLE (id INTEGER PRIMARY KEY NOT NULL CHECK (id = 1), $BLOB BLOB NOT NULL)")
                }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    check(oldVersion == newVersion) { "unsupported FCM token journal migration" }
            }
        }
        return helper!!.writableDatabase
    }

    private fun aad(fingerprint: String) = "apu-push-fcm-token-v1\u0000$fingerprint".toByteArray(Charsets.US_ASCII)

    private fun encrypt(fingerprint: String, plaintext: ByteArray, allowCreate: Boolean): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(allowCreate))
        cipher.updateAAD(aad(fingerprint))
        val iv = cipher.iv
        check(iv.size == 12)
        val body = cipher.doFinal(plaintext)
        return byteArrayOf(1) + iv + body
    }

    private fun decrypt(blob: ByteArray): String {
        check(blob.size in 30..4096 && blob[0] == 1.toByte())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(false), GCMParameterSpec(128, blob.copyOfRange(1, 13)))
        val body = cipher.doFinal(blob.copyOfRange(13, blob.size))
        val fingerprint = body.toString(Charsets.US_ASCII)
        body.fill(0)
        check(HASH.matches(fingerprint) && fingerprint.any { it != '0' })
        return fingerprint
    }

    private fun secretKey(allowCreate: Boolean): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(allowCreate) { "FCM token journal key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val TABLE = "fcm_token"
        const val BLOB = "payload"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG = "FcmTokenJournal"
        val LOCK = Any()
        val HASH = Regex("^[0-9a-f]{64}$")
        val TOKEN = Regex("^[A-Za-z0-9_:.-]{20,2048}$")
    }
}
