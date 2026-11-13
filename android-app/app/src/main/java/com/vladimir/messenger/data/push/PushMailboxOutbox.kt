package com.vladimir.messenger.data.push

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import uniffi.p2p_core.fileExchangeBindingNodeId

/**
 * Device-local journal for the exact encrypted wire, outside ID and immutable
 * cutoff. It never sends, registers a token, acknowledges a message or enables
 * push. A caller may use Ready only after this transaction committed and must
 * use its exact triple for every network retry. A stored/handled HTTP result is
 * NOT a delivery receipt and must not retire this record.
 *
 * The caller must supply a previously authenticated epoch and independently
 * established peer pin, coordinate profile/restore/pin/message changes with
 * any eventual network effect, and invoke this off the UI thread. [current]
 * is called inside the SQLite transaction: it must be fast and must not do IO,
 * acquire another blocking lock or call this journal recursively. It only
 * rechecks a snapshot; it is not a lifecycle lock and cannot prevent ABA.
 * No network operation is performed while the SQLite lock is held. The journal
 * is device-bound and excluded from backup; wiping or rolling it back loses
 * its anti-replay history and requires re-establishing the push route.
 *
 * Terminal markers deliberately remain after expiry or retirement, so the same
 * original ID cannot silently acquire a fresh cutoff. They count towards the
 * bounded record quota; exhausting the quota fails closed rather than evicting
 * anti-replay history. A future production retention/reset policy is required.
 */
internal class PushMailboxOutbox(
    context: Context,
    private val sealer: PushMailboxMessageSealer,
    private val recordFailure: () -> Unit,
    private val databaseFile: File = File(context.applicationContext.noBackupFilesDir, "push_mailbox_outbox_v1.sqlite"),
    private val keyAlias: String = "apu_push_mailbox_outbox_v1",
    private val maxRecords: Int = 50_000,
    private val maxActiveBytes: Long = 16L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val bindingNodeId: (ByteArray) -> String = ::fileExchangeBindingNodeId,
) {
    private val app = context.applicationContext
    private var helper: SQLiteOpenHelper? = null

    init {
        require(maxRecords in 1..100_000)
        require(maxActiveBytes in 1024L..64L * 1024 * 1024)
    }

    /** Inputs contain private text/keys; do not log or share their mutable arrays. */
    class Draft(
        val id: String,
        val sender: String,
        val recipient: String,
        val sentAtMs: Long,
        val content: String,
        val localBinding: ByteArray,
        val pinnedPeer: ByteArray,
        val localSecret: ByteArray,
        val epoch: PushEpochProtocol.State,
        val expiresAt: Long,
    ) {
        override fun toString(): String = "PushMailboxOutboxDraft(redacted)"
    }

    /** The three public-to-the-transport fields are immutable; none is safe to log. */
    class Entry internal constructor(
        internal val recordKey: String,
        internal val scopeToken: String,
        val id: String,
        val expiresAt: Long,
        val envelope: String,
    ) {
        override fun toString(): String = "PushMailboxOutboxEntry(redacted)"
    }

    sealed interface Result {
        /** The exact triple is durable; this is not evidence of a network send. */
        class Ready(val entry: Entry, val created: Boolean) : Result {
            override fun toString(): String = "PushMailboxOutboxReady(redacted)"
        }
        object Terminal : Result
        object Conflict : Result
        object Rejected : Result
        object Full : Result
        object Unavailable : Result
    }

    enum class RetireResult { RETIRED, ALREADY_TERMINAL, STALE, UNAVAILABLE }
    sealed interface SweepResult {
        class Completed(val retired: Int) : SweepResult
        object Unavailable : SweepResult
    }

    /**
     * Concurrent candidates for the same message may be sealed differently,
     * but only the first committed candidate wins; all other callers receive
     * that same committed triple. Once a record exists, a newly supplied
     * cutoff is ignored, never used to extend the existing lifetime.
     */
    fun prepare(draft: Draft, current: () -> Boolean): Result {
        val binding = draft.localBinding
        val pin = draft.pinnedPeer
        val suppliedSecret = draft.localSecret
        if (binding.size !in 1..512 || pin.size !in 1..512 || suppliedSecret.size != 32) return Result.Rejected
        val epoch = draft.epoch.epochHex ?: return Result.Rejected
        if (draft.epoch.action != PushEpochProtocol.Action.OFFER || !PushMailboxTextPolicy.accepts(draft.content)) {
            return Result.Rejected
        }
        val text = PushMailboxMessageCodec.encode(draft.id, draft.sender, draft.recipient, draft.sentAtMs, draft.content)
            ?: return Result.Rejected
        val local = binding.copyOf()
        val peer = pin.copyOf()
        val secret = suppliedSecret.copyOf()
        val expectedLocal = local.copyOf()
        val expectedPeer = peer.copyOf()
        val expectedSecret = secret.copyOf()
        fun live(): Boolean = current() && MessageDigest.isEqual(binding, expectedLocal) &&
            MessageDigest.isEqual(pin, expectedPeer) && MessageDigest.isEqual(suppliedSecret, expectedSecret)
        return try {
            if (!live() || secret.all { it == 0.toByte() } || MessageDigest.isEqual(local, peer)) return Result.Rejected
            if (bindingNodeId(local) != draft.sender || bindingNodeId(peer) != draft.recipient ||
                !MessageDigest.isEqual(local, expectedLocal) || !MessageDigest.isEqual(peer, expectedPeer)
            ) return Result.Rejected
            val localHash = PushEpochProtocol.bindingHash(expectedLocal)
            val peerHash = PushEpochProtocol.bindingHash(expectedPeer)
            if (draft.epoch.ownerBindingHash != peerHash || draft.epoch.targetBindingHash != localHash) return Result.Rejected
            val scope = Scope(localHash, peerHash, draft.epoch.generation, hashAscii(epoch), hashUtf8(text))
            val record = hashAscii("apu-mailbox-outbox-record-v1\u0000${draft.sender}\u0000${draft.id}")
            val existing = lookup(record, scope, ::live)
            if (existing != null) return existing
            if (!validCutoff(draft.expiresAt, clock()) || !live()) return Result.Rejected
            // Potentially blocking FFI/crypto runs outside the SQLite/process lock.
            val wire = sealer.seal(draft.id, draft.sender, draft.recipient, draft.sentAtMs, draft.content,
                local, peer, secret, ::live) ?: return Result.Unavailable
            val id = PushMailboxProtocol.putId(draft.expiresAt, wire) ?: return Result.Rejected
            if (!live()) return Result.Rejected
            persist(record, scope, draft.expiresAt, id, wire, ::live)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Count only; do not expose text, bindings, ciphertext or exception details.
            recordFailure()
            Result.Unavailable
        } finally {
            local.fill(0); peer.fill(0); secret.fill(0)
            expectedLocal.fill(0); expectedPeer.fill(0); expectedSecret.fill(0)
        }
    }

    /**
     * Retire only after a durable authenticated delivery/cancellation decision.
     * Worker acceptance, wake acceptance and a mailbox ACK response do not meet
     * that condition. A stale Entry cannot retire a different stored triple.
     */
    fun retire(entry: Entry, current: () -> Boolean): RetireResult = synchronized(LOCK) {
        try {
            val db = db()
            db.beginTransaction()
            try {
                val stored = readStored(db, entry.recordKey) ?: return@synchronized RetireResult.STALE
                if (!current()) return@synchronized RetireResult.STALE
                if (entry.scopeToken != scopeToken(stored.scope)) return@synchronized RetireResult.STALE
                if (stored.terminal) {
                    // A terminal marker has no ciphertext, and cannot be reused.
                    db.setTransactionSuccessful()
                    return@synchronized RetireResult.ALREADY_TERMINAL
                }
                if (entry.expiresAt != stored.expiresAt || entry.id != stored.id ||
                    entry.envelope != stored.envelope
                ) return@synchronized RetireResult.STALE
                writeStored(db, entry.recordKey, stored.terminal(), allowCreateKey = false)
                if (!current()) return@synchronized RetireResult.STALE
                db.setTransactionSuccessful()
                RetireResult.RETIRED
            } finally { db.endTransaction() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordFailure()
            RetireResult.UNAVAILABLE
        }
    }

    /** Bounded cleanup shrinks expired ciphertext but keeps anti-replay markers. */
    fun retireExpired(limit: Int = 64): SweepResult = synchronized(LOCK) {
        if (limit !in 1..64) return@synchronized SweepResult.Unavailable
        try {
            val now = clock()
            if (!PushMailboxProtocol.validTimestamp(now)) return@synchronized SweepResult.Unavailable
            val db = db()
            db.beginTransaction()
            try {
                val records = db.rawQuery(
                    "SELECT record_key FROM mailbox_outbox WHERE terminal=0 AND expires_at<=? ORDER BY expires_at, record_key LIMIT ?",
                    arrayOf(now.toString(), limit.toString()),
                ).use { cursor ->
                    val keys = ArrayList<String>()
                    while (cursor.moveToNext()) keys.add(cursor.getString(0))
                    keys
                }
                for (record in records) {
                    val old = readStored(db, record) ?: error("missing outbox record")
                    check(!old.terminal && old.expiresAt <= now)
                    writeStored(db, record, old.terminal(), allowCreateKey = false)
                }
                db.setTransactionSuccessful()
                SweepResult.Completed(records.size)
            } finally { db.endTransaction() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordFailure()
            SweepResult.Unavailable
        }
    }

    fun close() = synchronized(LOCK) { helper?.close(); helper = null }

    /** null means genuinely absent, not an unreadable or expired record. */
    private fun lookup(record: String, scope: Scope, live: () -> Boolean): Result? = synchronized(LOCK) {
        val db = db()
        db.beginTransaction()
        try {
            val old = readStored(db, record)
            if (!live()) return@synchronized Result.Rejected
            if (old == null) {
                // Avoid expensive FFI sealing if a quota is already exhausted.
                // Persist rechecks capacity transactionally after sealing.
                val full = count(db) >= maxRecords || activeBytes(db) >= maxActiveBytes
                if (!live()) return@synchronized Result.Rejected
                db.setTransactionSuccessful()
                return@synchronized if (full) Result.Full else null
            }
            val result = existing(db, record, scope, old)
            if (!live()) return@synchronized Result.Rejected
            db.setTransactionSuccessful()
            result
        } finally { db.endTransaction() }
    }

    private fun persist(record: String, scope: Scope, expiresAt: Long, id: String, wire: String, live: () -> Boolean): Result =
        synchronized(LOCK) {
            val db = db()
            db.beginTransaction()
            try {
                val old = readStored(db, record)
                if (!live()) return@synchronized Result.Rejected
                if (old != null) {
                    val result = existing(db, record, scope, old)
                    if (!live()) return@synchronized Result.Rejected
                    db.setTransactionSuccessful()
                    return@synchronized result
                }
                if (!validCutoff(expiresAt, clock())) return@synchronized Result.Rejected
                val count = count(db)
                if (count >= maxRecords) return@synchronized Result.Full
                val saved = Stored(scope, expiresAt, false, id, wire)
                val encrypted = encrypt(record, saved.encode(), allowCreateKey = count == 0)
                try {
                    if (activeBytes(db) + encrypted.size > maxActiveBytes) return@synchronized Result.Full
                    val values = values(record, saved, encrypted)
                    check(db.insertOrThrow("mailbox_outbox", null, values) != -1L)
                    if (!live() || !validCutoff(expiresAt, clock())) return@synchronized Result.Rejected
                    db.setTransactionSuccessful()
                    Result.Ready(entry(record, saved), created = true)
                } finally { encrypted.fill(0) }
            } finally { db.endTransaction() }
        }

    private fun existing(db: SQLiteDatabase, record: String, scope: Scope, old: Stored): Result {
        if (old.scope != scope) return Result.Conflict
        if (old.terminal) return Result.Terminal
        val now = clock()
        if (!PushMailboxProtocol.validTimestamp(now)) return Result.Unavailable
        if (old.expiresAt <= now) {
            writeStored(db, record, old.terminal(), allowCreateKey = false)
            return Result.Terminal
        }
        // A rolled-back wall clock may put a previously valid expiry more than
        // seven days away. Do not extend it or mark the message terminal.
        if (!validCutoff(old.expiresAt, now)) return Result.Unavailable
        return Result.Ready(entry(record, old), created = false)
    }

    private fun entry(record: String, stored: Stored) = Entry(
        record, scopeToken(stored.scope), stored.id!!, stored.expiresAt, stored.envelope!!,
    )

    private fun scopeToken(scope: Scope): String = hashAscii(
        "apu-mailbox-outbox-scope-v1\u0000${scope.local}\u0000${scope.peer}\u0000${scope.generation}\u0000${scope.epoch}\u0000${scope.message}",
    )

    private fun db(): SQLiteDatabase {
        if (helper == null) {
            helper = object : SQLiteOpenHelper(app, databaseFile.absolutePath, null, 1) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE mailbox_outbox (record_key TEXT PRIMARY KEY NOT NULL, expires_at INTEGER NOT NULL, terminal INTEGER NOT NULL, payload BLOB NOT NULL)")
                    db.execSQL("CREATE INDEX mailbox_outbox_expiry ON mailbox_outbox(terminal, expires_at)")
                }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    error("unsupported push outbox migration")
                }
            }
        }
        return helper!!.writableDatabase
    }

    private fun readStored(db: SQLiteDatabase, record: String): Stored? {
        check(HASH.matches(record))
        return db.rawQuery(
            "SELECT expires_at, terminal, length(payload), CASE WHEN length(payload) BETWEEN 30 AND $MAX_BLOB_BYTES THEN payload ELSE NULL END FROM mailbox_outbox WHERE record_key=?",
            arrayOf(record),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val expiresAt = cursor.getLong(0)
            val terminal = cursor.getInt(1)
            check(terminal in 0..1 && cursor.getInt(2) in 30..MAX_BLOB_BYTES)
            val plaintext = decrypt(record, cursor.getBlob(3))
            val stored = Stored.decode(plaintext) ?: error("invalid push outbox record")
            check(stored.expiresAt == expiresAt && stored.terminal == (terminal == 1))
            stored
        }
    }

    private fun writeStored(db: SQLiteDatabase, record: String, stored: Stored, allowCreateKey: Boolean) {
        val encrypted = encrypt(record, stored.encode(), allowCreateKey)
        try {
            check(db.update("mailbox_outbox", values(record, stored, encrypted), "record_key=?", arrayOf(record)) == 1)
        } finally { encrypted.fill(0) }
    }

    private fun values(record: String, stored: Stored, encrypted: ByteArray) = ContentValues().apply {
        put("record_key", record); put("expires_at", stored.expiresAt)
        put("terminal", if (stored.terminal) 1 else 0); put("payload", encrypted)
    }

    private fun count(db: SQLiteDatabase): Int = db.rawQuery("SELECT COUNT(*) FROM mailbox_outbox", null)
        .use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    private fun activeBytes(db: SQLiteDatabase): Long = db.rawQuery(
        "SELECT COALESCE(SUM(length(payload)), 0) FROM mailbox_outbox WHERE terminal=0", null,
    ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun secretKey(allowCreate: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(allowCreate) { "push outbox key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    private fun encrypt(record: String, plaintext: String, allowCreateKey: Boolean): ByteArray {
        val bytes = plaintext.toByteArray(Charsets.US_ASCII)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(allowCreateKey))
            cipher.updateAAD(aad(record))
            val nonce = cipher.iv
            check(nonce.size == 12)
            (byteArrayOf(1) + nonce + cipher.doFinal(bytes)).also { check(it.size in 30..MAX_BLOB_BYTES) }
        } finally { bytes.fill(0) }
    }

    private fun decrypt(record: String, blob: ByteArray): String {
        try {
            check(blob.size in 30..MAX_BLOB_BYTES && blob[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(false), GCMParameterSpec(128, blob.copyOfRange(1, 13)))
            cipher.updateAAD(aad(record))
            val bytes = cipher.doFinal(blob, 13, blob.size - 13)
            return try {
                check(bytes.all { it.toInt() in 32..126 })
                bytes.toString(Charsets.US_ASCII)
            } finally { bytes.fill(0) }
        } finally { blob.fill(0) }
    }

    private fun aad(record: String) = "apu-mailbox-outbox-v1\u0000$record".toByteArray(Charsets.US_ASCII)

    private data class Scope(val local: String, val peer: String, val generation: Long, val epoch: String, val message: String)

    private class Stored(val scope: Scope, val expiresAt: Long, val terminal: Boolean, val id: String?, val envelope: String?) {
        fun terminal() = Stored(scope, expiresAt, true, null, null)
        fun encode(): String = listOf("APUMBOX1", scope.local, scope.peer, scope.generation, scope.epoch,
            scope.message, expiresAt, id ?: "-", envelope ?: "-").joinToString("|")

        companion object {
            fun decode(value: String): Stored? {
                if (value.length > MAX_BLOB_BYTES) return null
                val fields = value.split('|', limit = 9)
                if (fields.size != 9 || fields[0] != "APUMBOX1" || !HASH.matches(fields[1]) ||
                    !HASH.matches(fields[2]) || fields[1] == fields[2] || !HASH.matches(fields[4]) || !HASH.matches(fields[5])
                ) return null
                val generation = number(fields[3]) ?: return null
                val expiry = number(fields[6]) ?: return null
                val terminal = fields[7] == "-" && fields[8] == "-"
                if (!terminal && PushMailboxProtocol.putId(expiry, fields[8]) != fields[7]) return null
                return Stored(Scope(fields[1], fields[2], generation, fields[4], fields[5]), expiry, terminal,
                    fields[7].takeUnless { terminal }, fields[8].takeUnless { terminal })
            }
        }
    }

    private companion object {
        val LOCK = Any()
        val HASH = Regex("^[0-9a-f]{64}$")
        const val MAX_BLOB_BYTES = PushMailboxProtocol.MAX_ENVELOPE_CHARS + 1024
        const val MAX_TTL_MS = 7L * 24 * 60 * 60 * 1000
        fun number(value: String): Long? = value.takeIf {
            it.length in 1..16 && it[0] in '1'..'9' && it.all { c -> c in '0'..'9' }
        }?.toLongOrNull()?.takeIf(PushMailboxProtocol::validTimestamp)
        fun validCutoff(expiry: Long, now: Long): Boolean = PushMailboxProtocol.validTimestamp(now) &&
            PushMailboxProtocol.validTimestamp(expiry) && expiry > now && expiry - now <= MAX_TTL_MS
        fun hashAscii(value: String): String = digest(value.toByteArray(Charsets.US_ASCII))
        fun hashUtf8(value: String): String = digest(value.toByteArray(Charsets.UTF_8))
        fun digest(bytes: ByteArray): String = try {
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            try { hash.joinToString("") { "%02x".format(it.toInt() and 255) } } finally { hash.fill(0) }
        } finally { bytes.fill(0) }
    }
}
