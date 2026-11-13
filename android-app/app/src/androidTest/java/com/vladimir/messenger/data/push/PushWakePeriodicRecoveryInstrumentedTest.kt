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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite, but fake provider, scheduler, admission and sync. No message delivery. */
@RunWith(AndroidJUnit4::class)
class PushWakePeriodicRecoveryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var dbFile: File
    private val stores = mutableListOf<PushWakeInbox>()
    private val route = "12".repeat(32)
    private val now = 1_700_000_000_000L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.noBackupFilesDir, "push-periodic-test-${UUID.randomUUID()}.sqlite")
    }

    @After fun tearDown() {
        stores.forEach { it.close() }
        context.deleteDatabase(dbFile.absolutePath)
        listOf("-wal", "-shm", "-journal").forEach { File(dbFile.absolutePath + it).delete() }
    }

    private fun inbox() = PushWakeInbox(context, dbFile).also(stores::add)
    private fun ready(store: PushWakeInbox, at: Long) = (store.claim(at) as PushWakeInbox.ClaimResult.Ready).claim
    private fun due(store: PushWakeInbox, at: Long) = (store.pending(at) as PushWakeInbox.PendingResult.Pending).readyAtMs

    @Test fun emptyPeriodicTickCreatesOneGenericMarkerAcrossRestart() {
        val first = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.INVALID, first.ensurePeriodicRecovery(0))
        assertFalse(dbFile.exists())
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, first.ensurePeriodicRecovery(now))
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, first.ensurePeriodicRecovery(now + 1))
        first.close()

        val reopened = inbox()
        val claim = ready(reopened, now + 2)
        assertNull(claim.route)
        assertEquals(1L, claim.revision)
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, reopened.ensurePeriodicRecovery(now + 3))
        assertEquals(now + 2 + PushWakeInbox.LEASE_MS, due(reopened, now + 3))
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(claim, true, now + 4))
        assertEquals(PushWakeInbox.PendingResult.Empty, reopened.pending(now + 4))

        // A later independent tick starts a new scan; a stale completion cannot
        // consume its new lease even though the marker revision starts at one.
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, reopened.ensurePeriodicRecovery(now + 5))
        val later = ready(reopened, now + 5)
        assertNull(later.route)
        assertEquals(1L, later.revision)
        assertEquals(PushWakeInbox.Completion.STALE, reopened.complete(claim, true, now + 6))
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(later, true, now + 6))
        assertEquals(PushWakeInbox.PendingResult.Empty, reopened.pending(now + 6))
    }

    @Test fun periodicTicksPreserveRetryBackoffAcrossReopen() {
        val first = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, first.ensurePeriodicRecovery(now))
        val claim = ready(first, now)
        assertEquals(PushWakeInbox.Completion.RETRY_QUEUED, first.complete(claim, false, now + 1))
        val retryAt = now + 1 + PushWakeInbox.RETRY_MS
        repeat(10) { assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, first.ensurePeriodicRecovery(now + 2 + it)) }
        assertEquals(retryAt, due(first, now + 12))
        first.close()

        val reopened = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, reopened.ensurePeriodicRecovery(retryAt - 1))
        assertEquals(retryAt, due(reopened, retryAt - 1))
        assertEquals(PushWakeInbox.ClaimResult.Empty, reopened.claim(retryAt - 1))
        val retry = ready(reopened, retryAt)
        assertNull(retry.route)
        assertEquals(claim.revision, retry.revision)
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(retry, true, retryAt + 1))
    }

    @Test fun providerEventsDuringPeriodicLeaseAdvanceRecoveryRevision() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.ensurePeriodicRecovery(now))
        val first = ready(store, now)
        var scheduled = 0
        var failures = 0
        val intake = PushWakeIntake(
            lookup = { PushWakeIntake.Hint.UNAVAILABLE },
            enqueueRoute = store::enqueueRoute,
            enqueueRecovery = store::enqueueRecovery,
            pending = store::pending,
            schedule = { scheduled++; true },
            recordFailure = { failures++ },
        )
        assertEquals(PushWakeIntake.Result.QUEUED, intake.onDeletedMessages(now + 1))
        assertEquals(PushWakeIntake.Result.QUEUED, intake.onData(mapOf("v" to "1", "route" to route), now + 2))
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.ensurePeriodicRecovery(now + 3))
        assertEquals(PushWakeInbox.Completion.NEWER_QUEUED, store.complete(first, true, now + 4))
        val newer = ready(store, now + 4)
        assertNull(newer.route)
        assertEquals(first.revision + 2, newer.revision)
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(newer, true, now + 5))
        assertEquals(PushWakeInbox.PendingResult.Empty, store.pending(now + 5))
        assertEquals(2, scheduled) // fake scheduler; no actual provider was involved
        assertEquals(0, failures)
    }

    @Test fun periodicNormalizationCommitsExpiredRouteDuringARecoveryLease() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(route, now))
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.ensurePeriodicRecovery(now + 10_000))
        val beforeExpiry = now + PushWakeInbox.TTL_MS - 1
        val first = ready(store, beforeExpiry)
        assertNull(first.route)
        val afterExpiry = now + PushWakeInbox.TTL_MS
        // normalize coalesces the expired route into this marker; the periodic
        // call must commit that change even though the marker already exists.
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.ensurePeriodicRecovery(afterExpiry))
        store.close()
        // Inspect before any other queue call can re-run normalize(): returning
        // success while rolling back the expiry conversion would be incorrect.
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT route, revision FROM pending", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("*", cursor.getString(0))
                assertEquals(first.revision + 1, cursor.getLong(1))
                assertFalse(cursor.moveToNext())
            }
        }
        assertEquals(PushWakeInbox.Completion.NEWER_QUEUED, store.complete(first, true, afterExpiry + 1))
        val next = ready(store, afterExpiry + 1)
        assertNull(next.route)
        assertEquals(first.revision + 1, next.revision)
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(next, true, afterExpiry + 2))
        assertEquals(PushWakeInbox.PendingResult.Empty, store.pending(afterExpiry + 2))
    }

    @Test fun periodicNormalizationRenewsAnExpiredMarkerWithoutErasingItsLease() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.ensurePeriodicRecovery(now))
        val beforeExpiry = now + PushWakeInbox.TTL_MS - 1
        val first = ready(store, beforeExpiry)
        assertNull(first.route)
        val afterExpiry = now + PushWakeInbox.TTL_MS
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.ensurePeriodicRecovery(afterExpiry))
        assertEquals(beforeExpiry + PushWakeInbox.LEASE_MS, due(store, afterExpiry))
        assertEquals(PushWakeInbox.Completion.NEWER_QUEUED, store.complete(first, true, afterExpiry + 1))
        val renewed = ready(store, afterExpiry + 1)
        assertNull(renewed.route)
        assertEquals(first.revision + 1, renewed.revision)
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(renewed, true, afterExpiry + 2))
        assertEquals(PushWakeInbox.PendingResult.Empty, store.pending(afterExpiry + 2))
    }

    @Test fun periodicNormalizationPreservesBackoffWhileCoalescingExpiredRoute() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(route, now))
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.ensurePeriodicRecovery(now + 10_000))
        val beforeExpiry = now + PushWakeInbox.TTL_MS - 1_000
        val first = ready(store, beforeExpiry)
        assertEquals(PushWakeInbox.Completion.RETRY_QUEUED, store.complete(first, false, beforeExpiry + 1))
        val retryAt = beforeExpiry + 1 + PushWakeInbox.RETRY_MS
        val afterExpiry = now + PushWakeInbox.TTL_MS
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.ensurePeriodicRecovery(afterExpiry))
        assertEquals(retryAt, due(store, afterExpiry))
        store.close()
        val reopened = inbox()
        assertEquals(PushWakeInbox.ClaimResult.Empty, reopened.claim(retryAt - 1))
        val retry = ready(reopened, retryAt)
        assertNull(retry.route)
        assertEquals(first.revision + 1, retry.revision)
        assertEquals(PushWakeInbox.Completion.COMPLETED, reopened.complete(retry, true, retryAt + 1))
        assertEquals(PushWakeInbox.PendingResult.Empty, reopened.pending(retryAt + 1))
    }

    @Test fun failedPeriodicNormalizationRollsBackBeforeRetry() {
        val store = inbox()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, store.enqueueRoute(route, now))
        store.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER deny_periodic_recovery BEFORE INSERT ON pending " +
                "WHEN NEW.route='*' BEGIN SELECT RAISE(ABORT, 'injected'); END")
        }
        val afterExpiry = now + PushWakeInbox.TTL_MS
        assertEquals(PushWakeInbox.EnqueueResult.UNAVAILABLE, store.ensurePeriodicRecovery(afterExpiry))
        store.close()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("SELECT route FROM pending", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(route, cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
            db.execSQL("DROP TRIGGER deny_periodic_recovery")
        }
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.ensurePeriodicRecovery(afterExpiry + 1))
        val recovery = ready(store, afterExpiry + 1)
        assertNull(recovery.route)
        assertEquals(PushWakeInbox.Completion.COMPLETED, store.complete(recovery, true, afterExpiry + 2))
        assertEquals(PushWakeInbox.PendingResult.Empty, store.pending(afterExpiry + 2))
    }

    @Test fun concurrentPeriodicTicksCoalesceWithoutHidingProviderEvent() {
        val first = inbox()
        val second = inbox()
        val pool = Executors.newFixedThreadPool(8)
        try {
            fun concurrentTicks(includeProvider: Boolean) {
                val start = CountDownLatch(1)
                val jobs = (0 until 24).map { index ->
                    pool.submit<PushWakeInbox.EnqueueResult> {
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        (if (index % 2 == 0) first else second).ensurePeriodicRecovery(now + 1)
                    }
                } + if (includeProvider) listOf(pool.submit<PushWakeInbox.EnqueueResult> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    second.enqueueRecovery(now + 1)
                }) else emptyList()
                start.countDown()
                jobs.forEach { assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, it.get(10, TimeUnit.SECONDS)) }
            }
            concurrentTicks(includeProvider = false)
            val claim = ready(first, now + 1)
            assertNull(claim.route)
            assertEquals(1L, claim.revision)
            concurrentTicks(includeProvider = true)
            assertEquals(PushWakeInbox.Completion.NEWER_QUEUED, first.complete(claim, true, now + 2))
            val newer = ready(second, now + 2)
            assertNull(newer.route)
            assertEquals(2L, newer.revision)
            assertEquals(PushWakeInbox.Completion.COMPLETED, second.complete(newer, true, now + 3))
            assertEquals(PushWakeInbox.PendingResult.Empty, first.pending(now + 3))
        } finally { pool.shutdownNow() }
    }

    @Test fun periodicPassRepairsFailedCallbackWithSqliteAndFakeSync() = runBlocking {
        var current = inbox()
        var scheduled = 0
        var failures = 0
        val gate = PushWakeScheduleGate({ _ -> scheduled++; true }, { failures++ })
        val dispatcher = PushWakeDispatcher(
            gate,
            lookup = { PushWakeIntake.Hint.UNAVAILABLE },
            enqueueRoute = { _, _ -> error("unavailable index must not write the route") },
            enqueueRecovery = { PushWakeInbox.EnqueueResult.UNAVAILABLE }, // simulate lost write
            pending = { at -> current.pending(at) },
            recordFailure = { failures++ },
        )
        val stale = dispatcher.workerStarted()
        assertEquals(PushWakeIntake.Result.UNAVAILABLE,
            dispatcher.onData(mapOf("v" to "1", "route" to route), now))
        current.close()
        current = inbox() // model reopening after the unrecorded callback
        var scans = 0
        val drain = PushWakeDrain(current, { now + 1 }, { value ->
            assertNull(value)
            PushWakeDrain.Inspection.ALLOW
        }, { batch ->
            scans++ // fake completed receive window, not an actual mailbox scan
            assertEquals(1, batch.items.size)
            assertNull(batch.items.single().route)
            batch.items.map { it.result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW) }
        }) { failures++ }
        val loop = PushWakeRunLoop(dispatcher, drain::run, { now + 1 }) { failures++ }
        val periodic = PushWakePeriodicRecovery(
            admission = { PushWakePeriodicRecovery.Admission.ALLOW },
            ensureMarker = current::ensurePeriodicRecovery,
            runDrain = loop::run,
            nowMs = { now + 1 },
            recordFailure = { failures++ },
        )
        assertEquals(PushWakePeriodicRecovery.Result.TASK_SUCCESS, periodic.run())
        assertEquals(PushWakeInbox.PendingResult.Empty, current.pending(now + 1))
        assertEquals(1, scans)
        assertEquals(0, scheduled) // direct drain bypasses stale process-local gate
        assertEquals(0, failures)
        dispatcher.workerStopped(stale)
    }

    @Test fun periodicTickRacingLeasedScanNeedsLaterTickForUnrecordedCallback() = runBlocking {
        withTimeout(10_000) {
            val store = inbox()
            val clock = AtomicLong(now)
            val scans = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var scheduled = 0
            var failures = 0
            val gate = PushWakeScheduleGate({ _ -> scheduled++; true }, { failures++ })
            val dispatcher = PushWakeDispatcher(
                gate,
                lookup = { PushWakeIntake.Hint.UNAVAILABLE },
                enqueueRoute = store::enqueueRoute,
                enqueueRecovery = { PushWakeInbox.EnqueueResult.UNAVAILABLE },
                pending = store::pending,
                recordFailure = { failures++ },
            )
            val drain = PushWakeDrain(store, clock::get, { PushWakeDrain.Inspection.ALLOW }, { batch ->
                assertEquals(1, batch.items.size)
                assertNull(batch.items.single().route)
                if (scans.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                batch.items.map { it.result(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW) }
            }) { failures++ }
            val loop = PushWakeRunLoop(dispatcher, drain::run, clock::get) { failures++ }
            val periodic = PushWakePeriodicRecovery(
                admission = { PushWakePeriodicRecovery.Admission.ALLOW },
                ensureMarker = store::ensurePeriodicRecovery,
                runDrain = loop::run,
                nowMs = clock::get,
                recordFailure = { failures++ },
            )
            assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, store.ensurePeriodicRecovery(now))
            val running = async(Dispatchers.IO) { loop.run() }
            try {
                entered.await()
                clock.set(now + 1)
                assertEquals(PushWakeIntake.Result.UNAVAILABLE,
                    dispatcher.onData(mapOf("v" to "1", "route" to route), clock.get()))
                assertEquals(PushWakePeriodicRecovery.Result.RETRY, periodic.run())
                assertEquals(1, scans.get()) // current lease is not duplicated
                clock.set(now + 2)
                release.complete(Unit)
                assertEquals(PushWakeRunLoop.Result.SUCCESS, running.await())
                assertEquals(PushWakeInbox.PendingResult.Empty, store.pending(clock.get()))
                clock.set(now + 3)
                assertEquals(PushWakePeriodicRecovery.Result.TASK_SUCCESS, periodic.run())
                assertEquals(2, scans.get()) // only a later tick repairs this race
                assertEquals(PushWakeInbox.PendingResult.Empty, store.pending(clock.get()))
                assertEquals(0, scheduled)
                assertEquals(0, failures)
            } finally { release.complete(Unit) }
        }
    }
}
