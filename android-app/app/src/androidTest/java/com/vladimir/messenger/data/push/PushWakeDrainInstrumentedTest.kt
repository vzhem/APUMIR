package com.vladimir.messenger.data.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite and coroutines with fake inspection/sync, not Firebase or WorkManager. */
@RunWith(AndroidJUnit4::class)
class PushWakeDrainInstrumentedTest {
    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var inbox: PushWakeInbox
    private val a = "12".repeat(32)
    private val b = "34".repeat(32)
    private val c = "56".repeat(32)
    private val clock = AtomicLong(1_700_000_000_000L)
    private var failures = 0

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.noBackupFilesDir, "push-wake-drain-test-${UUID.randomUUID()}.sqlite")
        inbox = PushWakeInbox(context, dbFile)
    }

    @After fun tearDown() {
        inbox.close()
        context.deleteDatabase(dbFile.absolutePath)
        listOf("-wal", "-shm", "-journal").forEach { File(dbFile.absolutePath + it).delete() }
    }

    private fun drain(inspect: suspend (String?) -> PushWakeDrain.Inspection, sync: suspend () -> Boolean) =
        PushWakeDrain(inbox, clock::get, inspect, sync) { failures++ }

    @Test fun mixedSignalsAreInspectedAndAllowedRoutesShareOneGenericSync() = runBlocking {
        val now = clock.get()
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, inbox.enqueueRoute(a, now))
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, inbox.enqueueRoute(b, now))
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, inbox.enqueueRoute(c, now))
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, inbox.enqueueRecovery(now))
        val inspected = mutableListOf<String?>()
        var syncs = 0
        val worker = drain({ route ->
            inspected += route
            when (route) {
                b -> PushWakeDrain.Inspection.DROP
                c -> PushWakeDrain.Inspection.RETRY
                else -> PushWakeDrain.Inspection.ALLOW
            }
        }, { syncs++; true })
        val result = worker.run() as PushWakeDrain.Result.Pending
        assertEquals(now + PushWakeInbox.RETRY_MS, result.readyAtMs)
        assertEquals(listOf(null, a, b, c), inspected)
        assertEquals(1, syncs)
        assertEquals(0, failures)
        clock.set(result.readyAtMs)
        assertEquals(PushWakeDrain.Result.Empty, drain({ PushWakeDrain.Inspection.ALLOW }, { syncs++; true }).run())
        assertEquals(2, syncs)
    }

    @Test fun wakesArrivingWhileSyncRunsSurviveAndAreRedriven() = runBlocking {
        withTimeout(10_000) {
            val now = clock.get()
            inbox.enqueueRoute(a, now)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var syncs = 0
            val first = drain({ PushWakeDrain.Inspection.ALLOW }, {
                syncs++
                entered.complete(Unit)
                release.await()
                true
            })
            val running = async(Dispatchers.IO) { first.run() }
            try {
                entered.await()
                inbox.enqueueRoute(a, now + 1)
                inbox.enqueueRoute(b, now + 1)
                clock.set(now + 2)
                release.complete(Unit)
                val pending = running.await() as PushWakeDrain.Result.Pending
                assertEquals(now + 2, pending.readyAtMs)
                assertEquals(PushWakeDrain.Result.Empty, drain({ PushWakeDrain.Inspection.ALLOW }, { syncs++; true }).run())
                assertEquals(2, syncs)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun inspectAndSyncFailuresRetryWithoutBlockingOtherRoutes() = runBlocking {
        val now = clock.get()
        inbox.enqueueRoute(a, now)
        inbox.enqueueRoute(b, now)
        var syncs = 0
        val result = drain({ route ->
            if (route == a) error("must not log route or exception text")
            PushWakeDrain.Inspection.ALLOW
        }, { syncs++; error("unavailable") }).run() as PushWakeDrain.Result.Pending
        assertEquals(1, syncs)
        assertEquals(2, failures)
        assertEquals(now + PushWakeInbox.RETRY_MS, result.readyAtMs)
        clock.set(result.readyAtMs)
        assertEquals(PushWakeDrain.Result.Empty, drain({ PushWakeDrain.Inspection.ALLOW }, { syncs++; true }).run())
        assertEquals(2, syncs)
    }

    @Test fun cancellationKeepsLeaseAndRecoveryCanRetryAfterDeadline() = runBlocking {
        withTimeout(10_000) {
            val now = clock.get()
            inbox.enqueueRoute(a, now)
            val entered = CompletableDeferred<Unit>()
            val worker = drain({ PushWakeDrain.Inspection.ALLOW }, { entered.complete(Unit); awaitCancellation() })
            val job = launch(Dispatchers.IO) { worker.run() }
            entered.await()
            job.cancelAndJoin()
            assertEquals(now + PushWakeInbox.LEASE_MS, (inbox.pending(now) as PushWakeInbox.PendingResult.Pending).readyAtMs)
            clock.set(now + PushWakeInbox.LEASE_MS)
            assertEquals(PushWakeDrain.Result.Empty, drain({ PushWakeDrain.Inspection.ALLOW }, { true }).run())
            assertEquals(0, failures)
        }
    }

    @Test fun eachPassIsBoundedAndUnprocessedRoutesRemainPending() = runBlocking {
        val now = clock.get()
        repeat(PushWakeDrain.MAX_BATCH + 1) { index ->
            val route = "%064x".format(index + 1)
            assertEquals(PushWakeInbox.EnqueueResult.QUEUED, inbox.enqueueRoute(route, now))
        }
        var inspections = 0
        var syncs = 0
        val worker = drain({ inspections++; PushWakeDrain.Inspection.ALLOW }, { syncs++; true })
        assertTrue(worker.run() is PushWakeDrain.Result.Pending)
        assertEquals(PushWakeDrain.MAX_BATCH, inspections)
        assertEquals(1, syncs)
        assertEquals(PushWakeDrain.Result.Empty, worker.run())
        assertEquals(PushWakeDrain.MAX_BATCH + 1, inspections)
        assertEquals(2, syncs)
    }
}
