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
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import uniffi.p2p_core.fileExchangeBindingNodeId

/**
 * Experimental device-bound epoch journal. It is not connected to the transport
 * or FCM and cannot authenticate an envelope on its own. Callers MUST obtain
 * [priorPin] before processing that envelope and verify the open succeeded.
 * Every later network side effect must still revalidate identity and pin.
 *
 * Entries are encrypted using a non-exportable Android Keystore key and the
 * SQLite file is under noBackupFilesDir. Tampering, unreadable keys, quota and
 * unexpected identity changes fail closed rather than silently resetting state.
 * Erasing app data, restoring an old filesystem image, uninstalling, or losing
 * this journal also loses anti-rollback protection; re-pairing is still needed.
 * On its own an empty/new journal cannot distinguish a fresh install from a
 * deleted database; callers must establish trust again instead of assuming safety.
 * The process-local lock and SQLite transaction make concurrent writes within
 * the app atomic, but do not coordinate independent Android processes/devices.
 */
internal class PushEpochStore(
    context: Context,
    private val databaseFile: File = File(context.applicationContext.noBackupFilesDir, "push_epoch_v1.sqlite"),
    private val keyAlias: String = "apu_push_epoch_v1",
    private val maxEntries: Int = 2048,
    private val bindingNodeId: (ByteArray) -> String = ::fileExchangeBindingNodeId,
) {
    private val app = context.applicationContext
    private var helper: SQLiteOpenHelper? = null

    init { require(maxEntries in 1..2048) }

    sealed interface WriteResult {
        object Applied : WriteResult
        object Duplicate : WriteResult
        object Rejected : WriteResult
        object Full : WriteResult
        object Unavailable : WriteResult
    }

    sealed interface ReadResult {
        object Missing : ReadResult
        object Unavailable : ReadResult
        class Found(val state: PushEpochProtocol.State) : ReadResult {
            override fun toString(): String = "PushEpochFound(redacted)"
        }
    }

    fun accept(
        localId: String,
        peerId: String,
        packet: PushEpochProtocol.Announcement,
        sealedAndOpened: Boolean,
        openedSenderBinding: ByteArray?,
        priorPin: ByteArray?,
        localBinding: ByteArray?,
    ): WriteResult = synchronized(LOCK) {
        if (!bindingsMatch(localId, peerId, sealedAndOpened, openedSenderBinding, priorPin, localBinding)) {
            return@synchronized WriteResult.Rejected
        }
        // Reject malformed/misaddressed packets before creating a database or key.
        if (PushEpochProtocol.accept(packet, true, openedSenderBinding, priorPin, localBinding, null)
            !is PushEpochProtocol.Verdict.Applied
        ) return@synchronized WriteResult.Rejected
        try {
            val record = recordKey(localId, peerId)
            val db = db()
            db.beginTransaction()
            try {
                val previous = readStored(db, record)
                val verdict = PushEpochProtocol.accept(packet, true, openedSenderBinding, priorPin, localBinding, previous)
                val result = when (verdict) {
                    PushEpochProtocol.Verdict.Rejected -> WriteResult.Rejected
                    PushEpochProtocol.Verdict.Duplicate -> WriteResult.Duplicate
                    is PushEpochProtocol.Verdict.Applied -> {
                        if (previous == null && count(db) >= maxEntries) {
                            WriteResult.Full
                        } else {
                            val wire = when (packet.action) {
                                PushEpochProtocol.Action.OFFER -> PushEpochProtocol.encodeOffer(
                                    packet.generation, packet.ownerBindingHash, packet.targetBindingHash, packet.epochHex!!,
                                )
                                PushEpochProtocol.Action.REVOKE -> PushEpochProtocol.encodeRevoke(
                                    packet.generation, packet.ownerBindingHash, packet.targetBindingHash,
                                )
                            } ?: error("invalid epoch record")
                            val encrypted = encrypt(record, wire, allowCreate = count(db) == 0)
                            val values = ContentValues().apply { put("record_key", record); put("payload", encrypted) }
                            check(db.insertWithOnConflict("epochs", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                            WriteResult.Applied
                        }
                    }
                }
                db.setTransactionSuccessful()
                result
            } finally { db.endTransaction() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Push epoch storage failed: ${error.javaClass.simpleName}")
            WriteResult.Unavailable
        }
    }

    /** A missing record is distinct from an unreadable/corrupt one. */
    fun read(localId: String, peerId: String, priorPin: ByteArray?, localBinding: ByteArray?): ReadResult =
        synchronized(LOCK) {
            if (!bindingsMatch(localId, peerId, true, priorPin, priorPin, localBinding)) {
                return@synchronized ReadResult.Unavailable
            }
            try {
                val state = readStored(db(), recordKey(localId, peerId)) ?: return@synchronized ReadResult.Missing
                if (state.ownerBindingHash != PushEpochProtocol.bindingHash(priorPin!!) ||
                    state.targetBindingHash != PushEpochProtocol.bindingHash(localBinding!!)
                ) ReadResult.Unavailable else ReadResult.Found(state)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Push epoch storage read failed: ${error.javaClass.simpleName}")
                ReadResult.Unavailable
            }
        }

    fun close() = synchronized(LOCK) { helper?.close(); helper = null }

    private fun bindingsMatch(
        localId: String, peerId: String, opened: Boolean, sender: ByteArray?, pin: ByteArray?, local: ByteArray?,
    ): Boolean {
        if (!opened || !NODE.matches(localId) || !NODE.matches(peerId) || localId == peerId ||
            sender == null || pin == null || local == null || sender.size !in 1..512 || pin.size !in 1..512 ||
            local.size !in 1..512 || !MessageDigest.isEqual(sender, pin)
        ) return false
        return try {
            bindingNodeId(pin) == peerId && bindingNodeId(local) == localId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Push binding verification failed: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun db(): SQLiteDatabase {
        if (helper == null) {
            helper = object : SQLiteOpenHelper(app, databaseFile.absolutePath, null, 1) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE epochs (record_key TEXT PRIMARY KEY NOT NULL, payload BLOB NOT NULL)")
                }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    error("unsupported push epoch migration")
                }
            }
        }
        return helper!!.writableDatabase
    }

    private fun readStored(db: SQLiteDatabase, record: String): PushEpochProtocol.State? =
        db.rawQuery(
            "SELECT length(payload), CASE WHEN length(payload) BETWEEN 30 AND 512 THEN payload ELSE NULL END " +
                "FROM epochs WHERE record_key=?", arrayOf(record),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            check(cursor.getInt(0) in 30..512)
            val plaintext = decrypt(record, cursor.getBlob(1))
            val parsed = PushEpochProtocol.parse(plaintext) ?: error("invalid epoch record")
            PushEpochProtocol.State.from(parsed)
        }

    private fun count(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM epochs", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun secretKey(allowCreate: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(allowCreate) { "push epoch key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    private fun encrypt(record: String, wire: String, allowCreate: Boolean): ByteArray {
        val plaintext = wire.toByteArray(Charsets.UTF_8)
        try {
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

    private fun recordKey(localId: String, peerId: String): String {
        val data = "apu-push-epoch-record-v1\u0000$localId\u0000$peerId".toByteArray(Charsets.US_ASCII)
        return MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun aad(record: String) = "apu-push-epoch-store-v1\u0000$record".toByteArray(Charsets.US_ASCII)

    private companion object {
        const val TAG = "PushEpochStore"
        val LOCK = Any()
        val NODE = Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")
    }
}
