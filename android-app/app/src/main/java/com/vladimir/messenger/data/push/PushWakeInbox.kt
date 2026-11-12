package com.vladimir.messenger.data.push

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CancellationException

/**
 * Bounded, device-local inbox for *signals*, not messages or delivery receipts.
 * It stores only opaque route hashes (which are also present in the registration
 * index); no FCM token, node id, epoch, capability or chat content is written.
 *
 * A future FCM listener must first check the local route index before enqueueing,
 * and its worker must revalidate current identity/pin/journal and rederive the
 * route before using a claim. [enqueueRecovery] is reserved for the provider's
 * onDeletedMessages callback and requests a bounded generic sync. Neither an
 * index hit nor this queue authenticates a message. This inbox does not start
 * Firebase, schedule work, send network traffic, or enable push.
 *
 * SQLite transactions and a process-wide lock prevent races among instances.
 * Each claim has both a monotonic lease number and a fresh random nonce: an old
 * completion cannot consume a newly claimed row if the database is recreated
 * or an earlier, unleased snapshot is restored and the counter repeats, except
 * for a negligible random collision. Restoring a snapshot that contains the
 * SAME live claim is not detected. Nor does this detect database deletion or
 * protect identity/pin/restore from ABA. Rows expire after an hour; overflow
 * coalesces a generic recovery marker instead of silently dropping a contact.
 */
internal class PushWakeInbox(
    context: Context,
    private val databaseFile: File = File(context.applicationContext.noBackupFilesDir, "push_wake_inbox_v1.sqlite"),
    private val maxRoutes: Int = 128,
    private val fillLeaseNonce: (ByteArray) -> Unit = { LEASE_ENTROPY.nextBytes(it) },
) : PushWakeQueue {
    private val app = context.applicationContext
    private var helper: SQLiteOpenHelper? = null

    init { require(maxRoutes in 1..2048) }

    enum class EnqueueResult { QUEUED, RECOVERY_QUEUED, INVALID, UNAVAILABLE }
    enum class Completion { COMPLETED, NEWER_QUEUED, RETRY_QUEUED, STALE, INVALID, UNAVAILABLE }

    sealed interface ClaimResult {
        object Empty : ClaimResult
        object Unavailable : ClaimResult
        class Ready(val claim: Claim) : ClaimResult {
            override fun toString(): String = "PushWakeClaimReady(redacted)"
        }
    }

    sealed interface PendingResult {
        object Empty : PendingResult
        object Unavailable : PendingResult
        /** Earliest due time; an abandoned lease is eligible at its deadline. */
        class Pending(val readyAtMs: Long) : PendingResult
    }

    /** A null [route] denotes a provider-overflow/full-sync marker. */
    class Claim internal constructor(
        val route: String?,
        internal val revision: Long,
        internal val lease: Long,
        internal val leaseNonce: String,
    ) {
        override fun toString(): String = "PushWakeClaim(redacted)"
    }

    /** Only canonical route hashes; the caller must check the route-index hint first. */
    fun enqueueRoute(route: String, nowMs: Long): EnqueueResult {
        if (!ROUTE.matches(route) || !validNow(nowMs)) return EnqueueResult.INVALID
        return enqueue(route, nowMs)
    }

    /** Called for FCM onDeletedMessages, not for arbitrary untrusted payloads. */
    fun enqueueRecovery(nowMs: Long): EnqueueResult {
        if (!validNow(nowMs)) return EnqueueResult.INVALID
        return enqueue(RECOVERY, nowMs)
    }

    private fun enqueue(route: String, nowMs: Long): EnqueueResult = synchronized(LOCK) {
        try {
            val db = db()
            db.beginTransaction()
            try {
                normalize(db, nowMs)
                val existing = revision(db, route)
                val target = when {
                    existing == Long.MAX_VALUE && route != RECOVERY -> RECOVERY
                    existing == Long.MAX_VALUE -> return@synchronized EnqueueResult.UNAVAILABLE
                    existing != null -> route
                    route != RECOVERY && countRoutes(db) >= maxRoutes -> RECOVERY
                    else -> route
                }
                val targetRevision = if (target == route) existing else revision(db, target)
                if (targetRevision == Long.MAX_VALUE) return@synchronized EnqueueResult.UNAVAILABLE
                if (targetRevision == null) {
                    val values = ContentValues().apply {
                        put("route", target)
                        put("revision", 1L)
                        put("expires_at", nowMs + TTL_MS)
                        put("due_at", nowMs)
                        putNull("lease")
                        putNull("lease_nonce")
                        put("lease_until", 0L)
                    }
                    check(db.insertOrThrow("pending", null, values) != -1L)
                } else {
                    // Preserve an existing retry deadline and any in-flight
                    // lease. A late completion cannot consume the new revision.
                    val values = ContentValues().apply {
                        put("revision", targetRevision + 1L)
                        put("expires_at", nowMs + TTL_MS)
                    }
                    check(db.update("pending", values, "route=?", arrayOf(target)) == 1)
                }
                db.setTransactionSuccessful()
                if (target == RECOVERY) EnqueueResult.RECOVERY_QUEUED else EnqueueResult.QUEUED
            } finally { db.endTransaction() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diag("enqueue", error)
            EnqueueResult.UNAVAILABLE
        }
    }

    /**
     * Lease one due route/recovery marker. The caller must complete it or let
     * the lease expire; an abandoned claim is available again after [LEASE_MS].
     */
    override fun claim(nowMs: Long): ClaimResult = synchronized(LOCK) {
        if (!validNow(nowMs)) return@synchronized ClaimResult.Unavailable
        try {
            val db = db()
            db.beginTransaction()
            try {
                normalize(db, nowMs)
                val candidate = db.rawQuery(
                    "SELECT route, revision FROM pending WHERE lease IS NULL AND due_at<=? " +
                        "ORDER BY CASE WHEN route=? THEN 0 ELSE 1 END, due_at, route LIMIT 1",
                    arrayOf(nowMs.toString(), RECOVERY),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else cursor.getString(0) to cursor.getLong(1)
                }
                if (candidate == null) {
                    db.setTransactionSuccessful()
                    return@synchronized ClaimResult.Empty
                }
                val (route, revision) = candidate
                check((route == RECOVERY || ROUTE.matches(route)) && revision > 0)
                val lease = nextLease(db)
                val nonce = freshLeaseNonce()
                val values = ContentValues().apply {
                    put("lease", lease)
                    put("lease_nonce", nonce)
                    put("lease_until", nowMs + LEASE_MS)
                }
                check(db.update("pending", values, "route=? AND lease IS NULL", arrayOf(route)) == 1)
                db.setTransactionSuccessful()
                ClaimResult.Ready(Claim(route.takeUnless { it == RECOVERY }, revision, lease, nonce))
            } finally { db.endTransaction() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diag("claim", error)
            ClaimResult.Unavailable
        }
    }

    /**
     * Complete one lease after the worker's bounded sync attempt. A newer wake
     * for the same route survives. A failed attempt is delayed, never hot-looped.
     * Completion of an expired/replaced lease cannot alter the current lease.
     */
    override fun complete(claim: Claim, success: Boolean, nowMs: Long): Completion = synchronized(LOCK) {
        val route = claim.route ?: RECOVERY
        if (!validNow(nowMs) || (route != RECOVERY && !ROUTE.matches(route)) ||
            claim.revision <= 0 || claim.lease <= 0 || !validLeaseNonce(claim.leaseNonce)
        ) return@synchronized Completion.INVALID
        try {
            val db = db()
            db.beginTransaction()
            try {
                normalize(db, nowMs)
                val current = db.rawQuery("SELECT revision, lease, lease_nonce FROM pending WHERE route=?", arrayOf(route)).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.isNull(1)) null else {
                        check(!cursor.isNull(2)) { "push wake lease nonce missing" }
                        val nonce = cursor.getString(2)
                        check(validLeaseNonce(nonce)) { "push wake lease nonce invalid" }
                        Triple(cursor.getLong(0), cursor.getLong(1), nonce)
                    }
                }
                if (current == null || current.second != claim.lease || current.third != claim.leaseNonce) {
                    db.setTransactionSuccessful()
                    return@synchronized Completion.STALE
                }
                val where = "route=? AND lease=? AND lease_nonce=?"
                val args = arrayOf(route, claim.lease.toString(), claim.leaseNonce)
                val result = if (success && current.first == claim.revision) {
                    check(db.delete("pending", where, args) == 1)
                    Completion.COMPLETED
                } else {
                    val values = ContentValues().apply {
                        putNull("lease")
                        putNull("lease_nonce")
                        put("lease_until", 0L)
                        put("due_at", if (success) nowMs else nowMs + RETRY_MS)
                    }
                    check(db.update("pending", values, where, args) == 1)
                    if (success) Completion.NEWER_QUEUED else Completion.RETRY_QUEUED
                }
                db.setTransactionSuccessful()
                result
            } finally { db.endTransaction() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diag("complete", error)
            Completion.UNAVAILABLE
        }
    }

    /** Allows startup/worker recovery to distinguish an empty inbox from a delayed lease. */
    override fun pending(nowMs: Long): PendingResult = synchronized(LOCK) {
        if (!validNow(nowMs)) return@synchronized PendingResult.Unavailable
        try {
            val db = db()
            db.beginTransaction()
            try {
                normalize(db, nowMs)
                val ready = db.rawQuery(
                    "SELECT min(CASE WHEN lease IS NULL OR due_at>lease_until THEN due_at ELSE lease_until END) FROM pending",
                    null,
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    if (cursor.isNull(0)) null else cursor.getLong(0)
                }
                db.setTransactionSuccessful()
                if (ready == null) PendingResult.Empty else PendingResult.Pending(ready.coerceAtLeast(nowMs))
            } finally { db.endTransaction() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diag("pending", error)
            PendingResult.Unavailable
        }
    }

    fun close() = synchronized(LOCK) { helper?.close(); helper = null }

    private fun db(): SQLiteDatabase {
        if (helper == null) {
            // Keep the original file path so existing pending wakes migrate.
            helper = object : SQLiteOpenHelper(app, databaseFile.absolutePath, null, 2) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE meta (id INTEGER PRIMARY KEY CHECK(id=1), last_lease INTEGER NOT NULL CHECK(last_lease>=0))")
                    db.execSQL("INSERT INTO meta (id, last_lease) VALUES (1, 0)")
                    db.execSQL(
                        "CREATE TABLE pending (route TEXT PRIMARY KEY NOT NULL, " +
                            "revision INTEGER NOT NULL CHECK(revision>0), expires_at INTEGER NOT NULL, " +
                            "due_at INTEGER NOT NULL, lease INTEGER, lease_until INTEGER NOT NULL, lease_nonce TEXT, " +
                            "CHECK(route='*' OR (length(route)=64 AND route NOT GLOB '*[^0-9a-f]*')), " +
                            "CHECK(lease IS NULL OR lease>0))"
                    )
                    db.execSQL("CREATE INDEX pending_due ON pending(due_at)")
                }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    check(oldVersion == 1 && newVersion == 2) { "unsupported push wake inbox migration" }
                    db.execSQL("ALTER TABLE pending ADD COLUMN lease_nonce TEXT")
                    // The old process cannot complete after an app upgrade. Its
                    // claims have no nonce, so preserve and release them for retry.
                    db.execSQL("UPDATE pending SET lease=NULL, lease_until=0 WHERE lease IS NOT NULL")
                }
            }
        }
        return helper!!.writableDatabase
    }

    private fun normalize(db: SQLiteDatabase, nowMs: Long) {
        db.execSQL("DELETE FROM pending WHERE expires_at<=?", arrayOf(nowMs))
        // A backwards wall-clock change must not hide a row or lease for years.
        db.execSQL("UPDATE pending SET expires_at=? WHERE expires_at>?", arrayOf(nowMs + TTL_MS, nowMs + TTL_MS))
        db.execSQL("UPDATE pending SET due_at=? WHERE due_at>?", arrayOf(nowMs + RETRY_MS, nowMs + RETRY_MS))
        db.execSQL(
            "UPDATE pending SET lease=NULL, lease_nonce=NULL, lease_until=0 WHERE lease IS NOT NULL AND (lease_until<=? OR lease_until>?)",
            arrayOf(nowMs, nowMs + LEASE_MS),
        )
    }

    private fun nextLease(db: SQLiteDatabase): Long {
        val before = db.rawQuery("SELECT last_lease FROM meta WHERE id=1", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        check(before in 0 until Long.MAX_VALUE) { "push wake lease sequence exhausted" }
        val next = before + 1L
        val values = ContentValues().apply { put("last_lease", next) }
        check(db.update("meta", values, "id=1 AND last_lease=?", arrayOf(before.toString())) == 1)
        return next
    }

    private fun freshLeaseNonce(): String {
        val bytes = ByteArray(32)
        try {
            fillLeaseNonce(bytes)
            check(bytes.any { it != 0.toByte() }) { "push wake lease entropy unavailable" }
            val chars = CharArray(bytes.size * 2)
            for (index in bytes.indices) {
                val value = bytes[index].toInt() and 0xff
                chars[index * 2] = HEX[value ushr 4]
                chars[index * 2 + 1] = HEX[value and 0x0f]
            }
            return String(chars)
        } finally { bytes.fill(0) }
    }

    private fun revision(db: SQLiteDatabase, route: String): Long? =
        db.rawQuery("SELECT revision FROM pending WHERE route=?", arrayOf(route)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).also { check(it > 0) } else null
        }

    private fun countRoutes(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT count(*) FROM pending WHERE route<>?", arrayOf(RECOVERY)).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun diag(operation: String, error: Exception) {
        // No routes, message bodies, tokens or exception text in diagnostics.
        Log.w("PushWakeInbox", "Wake inbox $operation unavailable: ${error.javaClass.simpleName}")
    }

    private fun validNow(nowMs: Long): Boolean = nowMs in 1..(Long.MAX_VALUE - TTL_MS)
    private fun validLeaseNonce(value: String): Boolean = LEASE_NONCE.matches(value) && value.any { it != '0' }

    companion object {
        const val TTL_MS = 60L * 60_000L
        const val LEASE_MS = 60_000L
        const val RETRY_MS = 15_000L
        private const val RECOVERY = "*"
        private const val HEX = "0123456789abcdef"
        private val ROUTE = Regex("^[0-9a-f]{64}$")
        private val LEASE_NONCE = Regex("^[0-9a-f]{64}$")
        private val LEASE_ENTROPY = SecureRandom()
        private val LOCK = Any()
    }
}
