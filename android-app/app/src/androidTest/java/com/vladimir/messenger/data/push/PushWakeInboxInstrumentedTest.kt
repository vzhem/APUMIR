package com.vladimir.messenger.data.push

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
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
                assertEquals(setOf("route", "revision", "expires_at", "due_at", "lease", "lease_until"), columns)
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
        val current = ready(store, afterExpiry)
        assertEquals(old.revision, current.revision) // the row was recreated
        assertTrue(old.lease != current.lease)
        assertEquals(PushWakeInbox.Completion.STALE, store.complete(old, true, afterExpiry + 1))
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(current, true, afterExpiry + 1))
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
