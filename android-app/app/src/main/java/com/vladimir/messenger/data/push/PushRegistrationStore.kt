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
import uniffi.p2p_core.fileExchangeBindingNodeId

/**
 * Device-bound journal for locally issued wake epochs and registration sequence
 * numbers. This is a preparation component, not a Firebase integration.
 *
 * The caller must supply a peer binding already read from the established pin
 * store, not one learned from the packet being handled. Every network effect
 * must re-check the current pin and local identity. Local process/SQLite
 * serialization does not lock identity replacement or other devices. A wiped,
 * restored, or rolled-back journal cannot prove freshness and requires recovery.
 *
 * No token or capability is stored here. Epochs and device ids are encrypted
 * with a non-exportable Keystore key and are excluded from application backup.
 * JVM strings and copies outside this class cannot be reliably zeroized.
 */
internal class PushRegistrationStore(
    context: Context,
    private val databaseFile: File = File(context.applicationContext.noBackupFilesDir, "push_registration_v1.sqlite"),
    private val keyAlias: String = "apu_push_registration_v1",
    private val maxEntries: Int = 2048,
    private val bindingNodeId: (ByteArray) -> String = ::fileExchangeBindingNodeId,
    private val random: SecureRandom = SecureRandom(),
) {
    private val app = context.applicationContext
    private var helper: SQLiteOpenHelper? = null

    init { require(maxEntries in 1..2048) }

    class Participants(
        val localId: String,
        val peerId: String,
        val localBinding: ByteArray,
        val pinnedPeerBinding: ByteArray,
    ) {
        override fun toString(): String = "PushRegistrationParticipants(redacted)"
    }

    sealed interface Result {
        object Missing : Result
        object Rejected : Result
        object Full : Result
        object Unavailable : Result
        class Ready(val state: PushRegistrationState.State) : Result {
            override fun toString(): String = "PushRegistrationReady(redacted)"
        }
    }

    fun read(participants: Participants): Result = change(participants, false, 0, write = false) { it }

    /** Persist a strictly increasing sequence before a register/revoke attempt. */
    fun reserve(participants: Participants, nowMs: Long): Result =
        change(participants, true, nowMs) { it.reserve() }

    fun registered(participants: Participants, sequence: Long, tokenFingerprint: String, nowMs: Long): Result =
        change(participants, false, 0) { it.registered(sequence, tokenFingerprint, nowMs) }

    fun beginRevocation(participants: Participants): Result =
        change(participants, false, 0) { it.beginRevocation() }

    fun revoked(participants: Participants, sequence: Long): Result =
        change(participants, false, 0) { it.revoked(sequence) }

    fun announced(participants: Participants, generation: Long): Result =
        change(participants, false, 0) { it.announced(generation) }

    fun rotate(participants: Participants): Result =
        change(participants, false, 0) { it.rotate(randomId()) }

    fun close() = synchronized(LOCK) { helper?.close(); helper = null }

    private fun change(
        participants: Participants,
        create: Boolean,
        nowMs: Long,
        write: Boolean = true,
        transition: (PushRegistrationState.State) -> PushRegistrationState.State?,
    ): Result = synchronized(LOCK) {
        if (participants.localBinding.size !in 1..512 || participants.pinnedPeerBinding.size !in 1..512 ||
            participants.localId.length !in 35..67 || participants.peerId.length !in 35..67
        ) return@synchronized Result.Rejected
        val local = participants.localBinding.copyOf()
        val peer = participants.pinnedPeerBinding.copyOf()
        try {
            if (!validParticipants(participants.localId, participants.peerId, local, peer)) return@synchronized Result.Rejected
            val localHash = PushEpochProtocol.bindingHash(local)
            val peerHash = PushEpochProtocol.bindingHash(peer)
            val record = recordKey(participants.localId, participants.peerId)
            val db = db()
            db.beginTransaction()
            try {
                val existing = readStored(db, record)
                val before = if (existing == null) {
                    if (!create) return@synchronized Result.Missing
                    if (nowMs !in 1..PushRegistrationState.MAX_NUMBER) return@synchronized Result.Rejected
                    if (count(db) >= maxEntries) return@synchronized Result.Full
                    PushRegistrationState.fresh(localHash, peerHash, nowMs, randomId(), randomId())
                        ?: return@synchronized Result.Rejected
                } else existing
                // Changed identity or pin requires explicit recovery, never overwrite.
                if (before.ownerHash != localHash || before.peerHash != peerHash) return@synchronized Result.Rejected
                val after = transition(before) ?: return@synchronized Result.Rejected
                if (write) {
                    val encrypted = encrypt(record, after.encode(), allowCreate = count(db) == 0)
                    val values = ContentValues().apply { put("record_key", record); put("payload", encrypted) }
                    check(db.insertWithOnConflict("registrations", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                }
                db.setTransactionSuccessful()
                Result.Ready(after)
            } finally { db.endTransaction() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Never print node ids, epoch, device id, tokens, or exception messages.
            Log.w(TAG, "Push registration storage unavailable: ${error.javaClass.simpleName}")
            Result.Unavailable
        } finally {
            local.fill(0)
            peer.fill(0)
        }
    }

    private fun validParticipants(localId: String, peerId: String, local: ByteArray, peer: ByteArray): Boolean {
        if (!NODE.matches(localId) || !NODE.matches(peerId) || localId == peerId ||
            local.size !in 1..512 || peer.size !in 1..512 || local.contentEquals(peer)
        ) return false
        return try {
            bindingNodeId(local) == localId && bindingNodeId(peer) == peerId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Push binding verification unavailable: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun db(): SQLiteDatabase {
        if (helper == null) {
            helper = object : SQLiteOpenHelper(app, databaseFile.absolutePath, null, 1) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE registrations (record_key TEXT PRIMARY KEY NOT NULL, payload BLOB NOT NULL)")
                }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    error("unsupported push registration migration")
                }
            }
        }
        return helper!!.writableDatabase
    }

    private fun readStored(db: SQLiteDatabase, record: String): PushRegistrationState.State? =
        db.rawQuery(
            "SELECT length(payload), CASE WHEN length(payload) BETWEEN 30 AND 512 THEN payload ELSE NULL END " +
                "FROM registrations WHERE record_key=?", arrayOf(record),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            check(cursor.getInt(0) in 30..512)
            PushRegistrationState.decode(decrypt(record, cursor.getBlob(1))) ?: error("invalid registration state")
        }

    private fun count(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM registrations", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun secretKey(allowCreate: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(allowCreate) { "push registration key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    private fun encrypt(record: String, wire: String, allowCreate: Boolean): ByteArray {
        val plaintext = wire.toByteArray(Charsets.UTF_8)
        try {
            check(plaintext.size <= 384)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(allowCreate))
            cipher.updateAAD(aad(record))
            val iv = cipher.iv
            check(iv.size == 12)
            return byteArrayOf(1) + iv + cipher.doFinal(plaintext)
        } finally { plaintext.fill(0) }
    }

    private fun decrypt(record: String, blob: ByteArray): String {
        check(blob.size in 30..512 && blob[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(false), GCMParameterSpec(128, blob.copyOfRange(1, 13)))
        cipher.updateAAD(aad(record))
        val plaintext = cipher.doFinal(blob.copyOfRange(13, blob.size))
        return try { plaintext.toString(Charsets.UTF_8) } finally { plaintext.fill(0) }
    }

    private fun randomId(): String {
        repeat(4) {
            val value = ByteArray(16)
            try {
                random.nextBytes(value)
                if (value.any { byte -> byte != 0.toByte() }) {
                    return value.joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
                }
            } finally { value.fill(0) }
        }
        error("push entropy unavailable")
    }

    private fun recordKey(localId: String, peerId: String): String = PushEpochProtocol.bindingHash(
        "apu-push-registration-record-v1\u0000$localId\u0000$peerId".toByteArray(Charsets.US_ASCII),
    )

    private fun aad(record: String) = "apu-push-registration-store-v1\u0000$record".toByteArray(Charsets.US_ASCII)

    private companion object {
        const val TAG = "PushRegistrationStore"
        val LOCK = Any()
        val NODE = Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")
    }
}
