package com.vladimir.messenger.data.push

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real device-local SQLite; no Firebase, scheduler, network or message router. */
@RunWith(AndroidJUnit4::class)
class PushWakeInboxInstrumentedTest {
    private lateinit var context: Context
    private lateinit var dbFile: File
    private val stores = mutableListOf<PushWakeInbox>()
    private val a = "12".repeat(32)
    private val b = "34".repeat(32)
    private val c = "56".repeat(32)
    private val now = 1_700_000_000_000L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.noBackupFilesDir, "push-wake-inbox-test-${UUID.randomUUID()}.sqlite")
    }

    @After fun tearDown() {
        stores.forEach { it.close() }
        context.deleteDatabase(dbFile.absolutePath)
        listOf("-wal", "-shm", "-journal").forEach { File(dbFile.absolutePath + it).delete() }
    }

    private fun inbox(maxRoutes: Int = 128) = PushWakeInbox(context, dbFile, maxRoutes).also(stores::add)
    private fun ready(store: PushWakeInbox, at: Long) = (store.claim(at) as PushWakeInbox.ClaimResult.Ready).claim
    private fun due(store: PushWakeInbox, at: Long) = (store.pending(at) as PushWakeInbox.PendingResult.Pending).readyAtMs

    @Test fun routeAndOverflowRecoverySurviveReopenWithoutPrivateFields() {
        val first = inbox(2)
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, first.enqueueRoute(a, now))
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, first.enqueueRoute(b, now + 1))
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, first.enqueueRoute(c, now + 2))
        first.close()
        assertTrue(dbFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA table_info(pending)", null).use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(1)
                assertEquals(setOf("route", "revision", "expires_at", "due_at", "lease", "lease_until", "lease_nonce", "recovery_nonce"), columns)
            }
        }
        val reopened = inbox(2)
        val recovery = ready(reopened, now + 2)
        assertNull(recovery.route)
        assertFalse(recovery.toString().contains(a))
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(recovery, true, now + 2))
        val firstRoute = ready(reopened, now + 2)
        assertEquals(a, firstRoute.route)
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(firstRoute, true, now + 2))
        val secondRoute = ready(reopened, now + 2)
        assertEquals(b, secondRoute.route)
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(secondRoute, true, now + 2))
        assertEquals(PushWakeInbox.PendingResult.Empty, reopened.pending(now + 2))
    }

    @Test fun newWakeDuringLeaseCannotBeErasedByTheOldCompletion() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(a, now))
        val old = ready(store, now)
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(a, now + 1))
        assertEquals(PushWakeInbox.Completion.NEWER_QUEUED, store.complete(old, true, now + 2))
        val newer = ready(store, now + 2)
        assertEquals(a, newer.route)
        assertEquals(PushWakeInbox.Completion.STALE, store.complete(old, true, now + 3))
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(newer, true, now + 3))
        assertEquals(PushWakeInbox.PendingResult.Empty, store.pending(now + 3))
    }

    @Test fun abandonedLeaseRecoversAndLateOwnerCannotDeleteReplacement() {
        val first = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, first.enqueueRoute(a, now))
        val old = ready(first, now)
        first.close()
        val reopened = inbox()
        assertEquals(now + PushWakeInbox.LEASE_MS, due(reopened, now + 1))
        assertEquals(PushWakeInbox.ClaimResult.Empty, reopened.claim(now + PushWakeInbox.LEASE_MS - 1))
        val replacement = ready(reopened, now + PushWakeInbox.LEASE_MS)
        assertTrue(replacement.lease > old.lease)
        assertEquals(PushWakeInbox.Completion.STALE, reopened.complete(old, true, now + PushWakeInbox.LEASE_MS + 1))
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(replacement, true, now + PushWakeInbox.LEASE_MS + 1))
        assertEquals(PushWakeInbox.PendingResult.Empty, reopened.pending(now + PushWakeInbox.LEASE_MS + 1))
    }

    @Test fun expiryAndReinsertionCannotReuseAnOldLease() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(a, now))
        val old = ready(store, now)
        val afterExpiry = now + PushWakeInbox.TTL_MS
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(a, afterExpiry))
        val recovery = ready(store, afterExpiry)
        assertNull(recovery.route) // the expired signal also requests a full recovery
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(recovery, true, afterExpiry))
        val current = ready(store, afterExpiry)
        assertEquals(old.revision, current.revision) // the row was recreated
        assertTrue(old.lease != current.lease)
        assertEquals(PushWakeInbox.Completion.STALE, store.complete(old, true, afterExpiry + 1))
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(current, true, afterExpiry + 1))
    }

    @Test fun expiredRoutesCoalesceIntoDurableRecoveryRatherThanDisappearing() {
        val store = inbox(2)
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(a, now))
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(b, now + 1))
        val afterExpiry = now + PushWakeInbox.TTL_MS + 1
        assertEquals(afterExpiry, due(store, afterExpiry))
        store.close()

        val reopened = inbox(2)
        val recovery = ready(reopened, afterExpiry)
        assertNull(recovery.route)
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(recovery, true, afterExpiry + 1))
        assertEquals(PushWakeInbox.PendingResult.Empty, reopened.pending(afterExpiry + 1))
    }

    @Test fun recoveryMarkerSurvivesMultipleExpiryPeriodsAndReopen() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.enqueueRecovery(now))
        val afterLongOutage = now + PushWakeInbox.TTL_MS * 5
        assertEquals(afterLongOutage, due(store, afterLongOutage))
        store.close()

        val reopened = inbox()
        val later = afterLongOutage + PushWakeInbox.TTL_MS * 3
        assertEquals(later, due(reopened, later))
        reopened.close()
        val recovery = ready(reopened, later)
        assertNull(recovery.route)
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(recovery, true, later + 1))
        assertEquals(PushWakeInbox.PendingResult.Empty, reopened.pending(later + 1))
    }

    @Test fun expiringRouteCannotBeErasedByAnInflightRecoveryCompletion() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(a, now))
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.enqueueRecovery(now + 10))
        val beforeExpiry = now + PushWakeInbox.TTL_MS - 10_000
        val old = ready(store, beforeExpiry)
        assertNull(old.route)
        val afterExpiry = now + PushWakeInbox.TTL_MS
        assertEquals(beforeExpiry + PushWakeInbox.LEASE_MS, due(store, afterExpiry))
        assertEquals(PushWakeInbox.Completion.NEWER_QUEUED, store.complete(old, true, afterExpiry + 1))
        val recovery = ready(store, afterExpiry + 1)
        assertNull(recovery.route)
        assertTrue(recovery.revision > old.revision)
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(recovery, true, afterExpiry + 2))
        assertEquals(PushWakeInbox.PendingResult.Empty, store.pending(afterExpiry + 2))
    }

    @Test fun expiredRecoveryLeaseRequiresANewRecoveryPass() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.enqueueRecovery(now))
        val beforeExpiry = now + PushWakeInbox.TTL_MS - 1
        val old = ready(store, beforeExpiry)
        assertNull(old.route)
        val afterExpiry = now + PushWakeInbox.TTL_MS
        assertEquals(PushWakeInbox.Completion.NEWER_QUEUED, store.complete(old, true, afterExpiry))
        val recovery = ready(store, afterExpiry)
        assertNull(recovery.route)
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(recovery, true, afterExpiry + 1))
        assertEquals(PushWakeInbox.PendingResult.Empty, store.pending(afterExpiry + 1))
    }

    @Test fun recoveryAndExpiredRouteDeletionRollBackTogetherOnSqlFailure() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(a, now))
        store.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER deny_expiry_delete BEFORE DELETE ON pending WHEN OLD.route <> '*' " +
                "BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        }
        val afterExpiry = now + PushWakeInbox.TTL_MS
        assertEquals(PushWakeInbox.PendingResult.Unavailable, store.pending(afterExpiry))
        store.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("SELECT count(*) FROM pending WHERE route=?", arrayOf(a)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.rawQuery("SELECT count(*) FROM pending WHERE route='*'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.execSQL("DROP TRIGGER deny_expiry_delete")
        }
        val recovery = ready(store, afterExpiry)
        assertNull(recovery.route)
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(recovery, true, afterExpiry + 1))
        assertEquals(PushWakeInbox.PendingResult.Empty, store.pending(afterExpiry + 1))
    }

    @Test fun exhaustedRecoveryRevisionDoesNotDiscardExpiredRoutes() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(a, now))
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.enqueueRecovery(now + 10))
        store.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE pending SET revision=? WHERE route='*'", arrayOf(Long.MAX_VALUE))
        }
        val afterExpiry = now + PushWakeInbox.TTL_MS
        assertEquals(PushWakeInbox.PendingResult.Unavailable, store.pending(afterExpiry))
        store.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("SELECT count(*) FROM pending WHERE route=?", arrayOf(a)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.execSQL("UPDATE pending SET revision=1 WHERE route='*'")
        }
        val recovery = ready(store, afterExpiry)
        assertNull(recovery.route)
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(recovery, true, afterExpiry + 1))
        assertEquals(PushWakeInbox.PendingResult.Empty, store.pending(afterExpiry + 1))
    }

    @Test fun recreatedDatabaseRejectsAnOldClaimWithMatchingCounters() {
        val first = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, first.enqueueRoute(a, now))
        val old = ready(first, now)
        first.close()
        assertTrue(SQLiteDatabase.deleteDatabase(dbFile))

        val recreated = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, recreated.enqueueRoute(a, now + 1))
        val current = ready(recreated, now + 1)
        assertEquals(old.revision, current.revision)
        assertEquals(old.lease, current.lease) // both files began their counters at one
        assertTrue(old.leaseNonce != current.leaseNonce)
        assertFalse(current.toString().contains(current.leaseNonce))
        assertEquals(PushWakeInbox.Completion.STALE, recreated.complete(old, true, now + 2))
        assertEquals(PushWakeInbox.Completion.STALE, recreated.complete(old, false, now + 2))
        assertTrue(recreated.pending(now + 2) is PushWakeInbox.PendingResult.Pending)
        assertEquals(PushWakeInbox.Completion.COMPLETED, recreated.complete(current, true, now + 3))
        assertEquals(PushWakeInbox.PendingResult.Empty, recreated.pending(now + 3))
    }

    @Test fun restoredPreLeaseSnapshotRejectsOldClaimEvenWhenCountersRepeat() {
        val snapshot = File(dbFile.absolutePath + ".snapshot")
        try {
            val first = inbox()
            assertEquals(PushWakeInbox.EnqueueResult.QUEUED, first.enqueueRoute(a, now))
            first.close()
            dbFile.copyTo(snapshot, overwrite = true) // no connection is open
            val old = ready(first, now + 1)
            first.close()
            snapshot.copyTo(dbFile, overwrite = true)

            val restored = inbox()
            val current = ready(restored, now + 2)
            assertEquals(old.revision, current.revision)
            assertEquals(old.lease, current.lease)
            assertTrue(old.leaseNonce != current.leaseNonce)
            assertEquals(PushWakeInbox.Completion.STALE, restored.complete(old, true, now + 3))
            assertEquals(PushWakeInbox.Completion.COMPLETED, restored.complete(current, true, now + 4))
        } finally { snapshot.delete() }
    }

    @Test fun markerIncarnationSurvivesRetryAndRevisionButNotARecreatedMarker() {
        val first = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, first.enqueueRecovery(now))
        val original = ready(first, now)
        val incarnation = checkNotNull(original.recoveryNonce)
        assertEquals(64, incarnation.length)
        assertTrue(incarnation.any { it != '0' })
        assertFalse(original.toString().contains(incarnation))
        val wrongNonce = (if (incarnation == "f".repeat(64)) "e" else "f").repeat(64)
        val wrongMarker = PushWakeInbox.Claim(null, original.revision, original.lease, original.leaseNonce, wrongNonce)
        assertEquals(PushWakeInbox.Completion.STALE, first.complete(wrongMarker, true, now))
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, first.enqueueRecovery(now + 1))
        assertEquals(PushWakeInbox.Completion.NEWER_QUEUED, first.complete(original, true, now + 2))
        val newer = ready(first, now + 2)
        assertEquals(incarnation, newer.recoveryNonce)
        assertEquals(2L, newer.revision)
        assertTrue(original.leaseNonce != newer.leaseNonce)
        assertEquals(PushWakeInbox.Completion.RETRY_QUEUED, first.complete(newer, false, now + 2))
        first.close()
        val reopened = inbox()
        val retry = ready(reopened, now + 2 + PushWakeInbox.RETRY_MS)
        assertEquals(incarnation, retry.recoveryNonce)
        assertEquals(2L, retry.revision)
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(retry, true, now + 3 + PushWakeInbox.RETRY_MS))
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, reopened.enqueueRecovery(now + 4 + PushWakeInbox.RETRY_MS))
        val recreated = ready(reopened, now + 4 + PushWakeInbox.RETRY_MS)
        assertEquals(1L, recreated.revision)
        assertTrue(incarnation != recreated.recoveryNonce)
        assertEquals(PushWakeInbox.Completion.STALE, reopened.complete(original, true, now + 5 + PushWakeInbox.RETRY_MS))
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(recreated, true, now + 5 + PushWakeInbox.RETRY_MS))
    }

    @Test fun versionTwoMigrationPreservesMarkerAndFencesLegacyLeases() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE meta (id INTEGER PRIMARY KEY, last_lease INTEGER NOT NULL)")
            db.execSQL("INSERT INTO meta VALUES (1,7)")
            db.execSQL("CREATE TABLE pending (route TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL, " +
                "expires_at INTEGER NOT NULL, due_at INTEGER NOT NULL, lease INTEGER, lease_until INTEGER NOT NULL, lease_nonce TEXT)")
            db.execSQL("CREATE INDEX pending_due ON pending(due_at)")
            db.execSQL("INSERT INTO pending VALUES (?,?,?,?,?,?,?)",
                arrayOf("*", 3L, now + PushWakeInbox.TTL_MS, now, 7L, now + PushWakeInbox.LEASE_MS, "ab".repeat(32)))
            db.execSQL("INSERT INTO pending VALUES (?,?,?,?,?,?,?)",
                arrayOf(a, 2L, now + PushWakeInbox.TTL_MS, now, 6L, now + PushWakeInbox.LEASE_MS, "cd".repeat(32)))
            db.version = 2
        }
        val migrated = inbox()
        val marker = ready(migrated, now + 1)
        assertNull(marker.route)
        assertEquals(3L, marker.revision)
        assertEquals(8L, marker.lease)
        val incarnation = checkNotNull(marker.recoveryNonce)
        assertEquals(64, incarnation.length)
        assertEquals(PushWakeInbox.Completion.COMPLETED, migrated.complete(marker, true, now + 2))
        val route = ready(migrated, now + 2)
        assertEquals(a, route.route)
        assertNull(route.recoveryNonce)
        assertEquals(9L, route.lease)
        assertEquals(PushWakeInbox.Completion.COMPLETED, migrated.complete(route, true, now + 3))
        assertEquals(PushWakeInbox.PendingResult.Empty, migrated.pending(now + 3))
    }

    @Test fun invalidMarkerEntropyOrCorruptIncarnationCannotConsumeWakes() {
        val unavailable = PushWakeInbox(context, dbFile, fillRecoveryNonce = { it.fill(0) }).also(stores::add)
        assertEquals(PushWakeInbox.EnqueueResult.UNAVAILABLE, unavailable.enqueueRecovery(now))
        assertEquals(PushWakeInbox.PendingResult.Empty, unavailable.pending(now))
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, unavailable.enqueueRoute(a, now))
        // Expiry must not delete the route if creating the replacement marker fails.
        assertEquals(PushWakeInbox.PendingResult.Unavailable, unavailable.pending(now + PushWakeInbox.TTL_MS))
        unavailable.close()
        val recovered = inbox()
        val marker = ready(recovered, now + PushWakeInbox.TTL_MS)
        assertNull(marker.route)
        recovered.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE pending SET recovery_nonce=recovery_nonce || char(0) || 'x' WHERE route='*'")
        }
        val corrupted = inbox()
        assertEquals(PushWakeInbox.Completion.UNAVAILABLE, corrupted.complete(marker, true, now + PushWakeInbox.TTL_MS + 1))
        assertTrue(corrupted.pending(now + PushWakeInbox.TTL_MS + 1) is PushWakeInbox.PendingResult.Pending)
        assertEquals(PushWakeInbox.ClaimResult.Unavailable, corrupted.claim(now + PushWakeInbox.TTL_MS + PushWakeInbox.LEASE_MS))
    }

    @Test fun versionOneMigrationPreservesWakesAndReleasesLegacyLeases() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE meta (id INTEGER PRIMARY KEY CHECK(id=1), last_lease INTEGER NOT NULL CHECK(last_lease>=0))")
            db.execSQL("INSERT INTO meta (id, last_lease) VALUES (1, 7)")
            db.execSQL(
                "CREATE TABLE pending (route TEXT PRIMARY KEY NOT NULL, " +
                    "revision INTEGER NOT NULL CHECK(revision>0), expires_at INTEGER NOT NULL, " +
                    "due_at INTEGER NOT NULL, lease INTEGER, lease_until INTEGER NOT NULL, " +
                    "CHECK(route='*' OR (length(route)=64 AND route NOT GLOB '*[^0-9a-f]*')), " +
                    "CHECK(lease IS NULL OR lease>0))"
            )
            db.execSQL("CREATE INDEX pending_due ON pending(due_at)")
            db.execSQL("INSERT INTO pending(route,revision,expires_at,due_at,lease,lease_until) VALUES (?,?,?,?,?,?)",
                arrayOf(a, 3L, now + PushWakeInbox.TTL_MS, now, 7L, now + PushWakeInbox.LEASE_MS))
            db.execSQL("INSERT INTO pending(route,revision,expires_at,due_at,lease,lease_until) VALUES (?,?,?,?,?,?)",
                arrayOf(b, 2L, now + PushWakeInbox.TTL_MS, now + PushWakeInbox.RETRY_MS, null, 0L))
            db.version = 1
        }
        val migrated = inbox()
        val current = ready(migrated, now + 1)
        assertEquals(a, current.route)
        assertEquals(3L, current.revision)
        assertEquals(8L, current.lease)
        assertEquals(64, current.leaseNonce.length)
        assertEquals(PushWakeInbox.Completion.COMPLETED, migrated.complete(current, true, now + 2))
        assertEquals(now + PushWakeInbox.RETRY_MS, due(migrated, now + 2))
        migrated.close()
        val reopened = inbox()
        val delayed = ready(reopened, now + PushWakeInbox.RETRY_MS)
        assertEquals(b, delayed.route)
        assertEquals(2L, delayed.revision)
        assertEquals(9L, delayed.lease)
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(delayed, true, now + PushWakeInbox.RETRY_MS + 1))
        assertEquals(PushWakeInbox.PendingResult.Empty, reopened.pending(now + PushWakeInbox.RETRY_MS + 1))
    }

    @Test fun failedLeaseEntropyRollsBackTheClaimAndPreservesTheWake() {
        val unavailable = PushWakeInbox(context, dbFile, fillLeaseNonce = { throw IllegalStateException("unavailable") })
            .also(stores::add)
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, unavailable.enqueueRoute(a, now))
        assertEquals(PushWakeInbox.ClaimResult.Unavailable, unavailable.claim(now))
        assertTrue(unavailable.pending(now) is PushWakeInbox.PendingResult.Pending)
        unavailable.close()
        val cancelled = PushWakeInbox(context, dbFile, fillLeaseNonce = { throw CancellationException("cancel") })
            .also(stores::add)
        var propagated = false
        try { cancelled.claim(now) } catch (_: CancellationException) { propagated = true }
        assertTrue(propagated)
        assertTrue(cancelled.pending(now) is PushWakeInbox.PendingResult.Pending)
        cancelled.close()
        val zeroEntropy = PushWakeInbox(context, dbFile, fillLeaseNonce = { it.fill(0) }).also(stores::add)
        assertEquals(PushWakeInbox.ClaimResult.Unavailable, zeroEntropy.claim(now))
        assertTrue(zeroEntropy.pending(now) is PushWakeInbox.PendingResult.Pending)
        zeroEntropy.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT last_lease FROM meta WHERE id=1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        }
        val recovered = inbox()
        val claim = ready(recovered, now + 1)
        assertEquals(1L, claim.lease)
        assertEquals(PushWakeInbox.Completion.COMPLETED, recovered.complete(claim, true, now + 2))
    }

    @Test fun wrongOrCorruptedLeaseNonceCannotConsumeARealClaim() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(a, now))
        val actual = ready(store, now)
        val wrong = PushWakeInbox.Claim(a, actual.revision, actual.lease, "fe".repeat(32))
        assertEquals(PushWakeInbox.Completion.STALE, store.complete(wrong, true, now + 1))
        listOf("not-a-valid-nonce", "00".repeat(32), "AB".repeat(32)).forEach { invalid ->
            val malformed = PushWakeInbox.Claim(a, actual.revision, actual.lease, invalid)
            assertEquals(PushWakeInbox.Completion.INVALID, store.complete(malformed, true, now + 1))
        }
        store.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE pending SET lease_nonce=? WHERE route=?", arrayOf("malformed", a))
        }
        assertEquals(PushWakeInbox.Completion.UNAVAILABLE, store.complete(actual, true, now + 1))
        // Once the lease expires, a fresh nonce is issued rather than trusting
        // the corrupted value; the pending wake is still available.
        val retry = ready(store, now + PushWakeInbox.LEASE_MS)
        assertEquals(PushWakeInbox.Completion.STALE, store.complete(actual, true, now + PushWakeInbox.LEASE_MS + 1))
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(retry, true, now + PushWakeInbox.LEASE_MS + 1))
    }

    @Test fun failedWorkRespectsBackoffAndDoesNotLoseANewerWake() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(a, now))
        val claim = ready(store, now)
        assertEquals(PushWakeInbox.Completion.RETRY_QUEUED, store.complete(claim, false, now + 1))
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(a, now + 2))
        val next = now + 1 + PushWakeInbox.RETRY_MS
        assertEquals(next, due(store, now + 2))
        assertEquals(PushWakeInbox.ClaimResult.Empty, store.claim(next - 1))
        val retry = ready(store, next)
        assertTrue(retry.revision > claim.revision)
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(retry, true, next))
    }

    @Test fun recoveryEventsCoalesceWithoutBeingLostDuringAnActiveSync() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.enqueueRecovery(now))
        val old = ready(store, now)
        assertNull(old.route)
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.enqueueRecovery(now + 1))
        assertEquals(PushWakeInbox.Completion.NEWER_QUEUED, store.complete(old, true, now + 2))
        val newer = ready(store, now + 2)
        assertNull(newer.route)
        assertEquals(PushWakeInbox.Completion.STALE, store.complete(old, true, now + 3))
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(newer, true, now + 3))
    }

    @Test fun rollbackOfWallClockCannotLeaveAnAbandonedLeaseStuckForDays() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(a, now))
        val old = ready(store, now)
        val earlier = now - TimeUnit.DAYS.toMillis(1)
        assertEquals(earlier + PushWakeInbox.RETRY_MS, due(store, earlier))
        val current = ready(store, earlier + PushWakeInbox.RETRY_MS)
        assertEquals(PushWakeInbox.Completion.STALE, store.complete(old, true, earlier + PushWakeInbox.RETRY_MS))
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(current, true, earlier + PushWakeInbox.RETRY_MS))
    }

    @Test fun concurrentInstancesCoalesceAndStillPreserveAnInflightWake() {
        val one = inbox()
        val two = inbox()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..32).map { index ->
                pool.submit<PushWakeInbox.EnqueueResult> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    (if (index % 2 == 0) one else two).enqueueRoute(a, now)
                }
            }
            start.countDown()
            results.forEach { assertEquals(PushWakeInbox.EnqueueResult.QUEUED, it.get(10, TimeUnit.SECONDS)) }
            val first = ready(one, now)
            assertEquals(32L, first.revision)
            assertEquals(PushWakeInbox.EnqueueResult.QUEUED, two.enqueueRoute(a, now + 1))
            assertEquals(PushWakeInbox.Completion.NEWER_QUEUED, one.complete(first, true, now + 2))
            val next = ready(two, now + 2)
            assertEquals(33L, next.revision)
            assertEquals(PushWakeInbox.Completion.COMPLETED, two.complete(next, true, now + 2))
        } finally { pool.shutdownNow() }
    }

    @Test fun dispatcherRedrivesNewSignalsAndRestartUsingRealInboxWithFakeScheduler() {
        val store = inbox()
        val failures = mutableListOf<() -> Unit>()
        var reported = 0
        fun dispatcher(): PushWakeDispatcher {
            val gate = PushWakeScheduleGate({ failed -> failures += failed; true }, { reported++ })
            return PushWakeDispatcher(gate, { PushWakeIntake.Hint.FOUND }, store::enqueueRoute,
                store::enqueueRecovery, store::pending, { reported++ })
        }
        val first = dispatcher()
        val payload = mapOf("v" to "1", "route" to a)
        assertEquals(PushWakeIntake.Result.QUEUED, first.onData(payload, now))
        val worker = first.workerStarted()
        val old = ready(store, now)
        assertEquals(PushWakeIntake.Result.QUEUED, first.onData(payload, now + 1))
        assertEquals(1, failures.size) // the running worker owns the earlier request
        assertEquals(PushWakeInbox.Completion.NEWER_QUEUED, store.complete(old, true, now + 2))
        assertEquals(PushWakeScheduleGate.Finish.RETRY, first.workerFinished(worker, false, now + 2))
        val retry = first.workerStarted()
        val newer = ready(store, now + 2)
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(newer, true, now + 3))
        assertEquals(PushWakeScheduleGate.Finish.SUCCESS, first.workerFinished(retry, false, now + 3))

        // The old job may still be returning: the backend must accept a successor.
        assertEquals(PushWakeIntake.Result.QUEUED, first.onData(payload, now + 4))
        assertEquals(2, failures.size)
        failures.last().invoke() // asynchronous scheduling failure does not delete SQLite
        assertEquals(1, reported)
        store.close()
        val restarted = dispatcher() // fresh process-local gate, same durable inbox
        assertEquals(PushWakeIntake.Result.QUEUED, restarted.resume(now + 5))
        assertEquals(3, failures.size)
        assertTrue(store.pending(now + 5) is PushWakeInbox.PendingResult.Pending)
    }

    @Test fun unavailableAdmissionPersistsOnlyGenericRecoveryAcrossReopen() {
        val store = inbox()
        val payload = mapOf("v" to "1", "route" to a)
        var scheduled = 0
        var reported = 0
        val intake = PushWakeIntake(
            lookup = { PushWakeIntake.Hint.UNAVAILABLE },
            enqueueRoute = { _, _ -> error("an unavailable hint must not store its route") },
            enqueueRecovery = store::enqueueRecovery,
            pending = store::pending,
            schedule = { scheduled++; false },
            recordFailure = { reported++ },
        )
        assertEquals(PushWakeIntake.Result.QUEUED_WITHOUT_SCHEDULER, intake.onData(payload, now))
        assertEquals(1, scheduled)
        store.close()
        val reopened = inbox()
        val first = ready(reopened, now + 1)
        assertNull(first.route) // no provider route, identity or message was persisted
        // Another unavailable callback during the lease must advance the
        // marker; a late completion cannot erase the newer recovery request.
        assertEquals(PushWakeIntake.Result.QUEUED_WITHOUT_SCHEDULER, intake.onData(payload, now + 2))
        assertEquals(PushWakeInbox.Completion.NEWER_QUEUED, reopened.complete(first, true, now + 2))
        val newer = ready(reopened, now + 2)
        assertNull(newer.route)
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(newer, true, now + 2))
        assertEquals(PushWakeInbox.PendingResult.Empty, reopened.pending(now + 2))

        // Simulate the route write failing independently while a recovery write
        // to real SQLite remains available. A crash after saving still leaves a
        // marker for startup/periodic recovery; the scheduler here is a fake.
        val failedRouteWrite = PushWakeIntake(
            lookup = { PushWakeIntake.Hint.FOUND },
            enqueueRoute = { _, _ -> PushWakeInbox.EnqueueResult.UNAVAILABLE },
            enqueueRecovery = reopened::enqueueRecovery,
            pending = reopened::pending,
            schedule = { scheduled++; true },
            recordFailure = { reported++ },
        )
        assertEquals(PushWakeIntake.Result.QUEUED, failedRouteWrite.onData(payload, now + 3))
        reopened.close()
        val afterCrash = inbox()
        val second = ready(afterCrash, now + 4)
        assertNull(second.route)
        assertEquals(PushWakeInbox.Completion.COMPLETED, afterCrash.complete(second, true, now + 4))
        assertEquals(PushWakeInbox.PendingResult.Empty, afterCrash.pending(now + 4))
        assertEquals(3, scheduled)
        assertEquals(0, reported)
    }

    @Test fun malformedRoutesBadClockAndExhaustedLeaseSequenceFailClosed() {
        val store = inbox()
        listOf("", "*", "ab".repeat(32).uppercase(), a + "0", a.drop(1), "g".repeat(64), a + "\n").forEach {
            assertEquals(PushWakeInbox.EnqueueResult.INVALID, store.enqueueRoute(it, now))
        }
        assertEquals(PushWakeInbox.EnqueueResult.INVALID, store.enqueueRoute(a, 0))
        assertEquals(PushWakeInbox.EnqueueResult.INVALID, store.enqueueRecovery(Long.MAX_VALUE))
        assertFalse(dbFile.exists())
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(a, now))
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE meta SET last_lease=? WHERE id=1", arrayOf(Long.MAX_VALUE))
        }
        assertEquals(PushWakeInbox.ClaimResult.Unavailable, store.claim(now))
        assertNotNull((store.pending(now) as PushWakeInbox.PendingResult.Pending).readyAtMs)
    }
}
