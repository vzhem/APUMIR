package com.vladimir.messenger.data.push

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real WorkManager test scheduler and device-local SQLite. The workers' trust
 * inspection and message sync are fakes: no FCM, provider network or delivery.
 */
@RunWith(AndroidJUnit4::class)
class PushWakeWorkManagerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var inbox: PushWakeInbox
    private lateinit var manager: WorkManager
    private lateinit var driver: TestDriver
    private lateinit var backend: PushWakeWorkManager
    private lateinit var gate: PushWakeScheduleGate
    private lateinit var dispatcher: PushWakeDispatcher
    private val executor = Executors.newFixedThreadPool(4)
    private val releaseFirst = CountDownLatch(1)
    private val clock = AtomicLong(1_700_000_000_000L)
    private val failures = AtomicInteger()
    private val payload = mapOf("v" to "1", "route" to "12".repeat(32))

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.noBackupFilesDir, "push-wake-work-test-${UUID.randomUUID()}.sqlite")
        inbox = PushWakeInbox(context, dbFile)
        PushWakeWorkProbes.clear()
        val config = Configuration.Builder().setExecutor(executor).setWorkerFactory(PushWakeProbeFactory()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        manager = WorkManager.getInstance(context)
        driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        backend = PushWakeWorkManager(manager, PushWakeDrainProbeWorker::class.java, PushWakeRecoveryProbeWorker::class.java)
        gate = PushWakeScheduleGate(backend::enqueue) { failures.incrementAndGet() }
        dispatcher = PushWakeDispatcher(gate, { PushWakeIntake.Hint.FOUND }, inbox::enqueueRoute,
            inbox::enqueueRecovery, inbox::pending, { failures.incrementAndGet() })
    }

    @After @Suppress("RestrictedApi") fun tearDown() {
        releaseFirst.countDown()
        try {
            if (::manager.isInitialized) {
                manager.cancelAllWorkByTag(PushWakeWorkManager.DRAIN_TAG).result.get(15, TimeUnit.SECONDS)
                manager.cancelUniqueWork(PushWakeWorkManager.RECOVERY_NAME).result.get(15, TimeUnit.SECONDS)
                executor.shutdown()
                assertTrue("test workers did not stop", executor.awaitTermination(15, TimeUnit.SECONDS))
                WorkManagerTestInitHelper.closeWorkDatabase()
            }
        } finally {
            // Do not leave a test WorkManager installed for other suites.
            WorkManagerImpl.setDelegate(null)
            executor.shutdownNow()
            if (::inbox.isInitialized) inbox.close()
            if (::context.isInitialized && ::dbFile.isInitialized) {
                context.deleteDatabase(dbFile.absolutePath)
                listOf("-wal", "-shm", "-journal").forEach { File(dbFile.absolutePath + it).delete() }
            }
            PushWakeWorkProbes.clear()
        }
    }

    private fun loop(
        target: PushWakeDispatcher = dispatcher,
        outcome: PushWakeDrain.SyncOutcome = PushWakeDrain.SyncOutcome.COMPLETED_WINDOW,
    ): PushWakeRunLoop {
        val drain = PushWakeDrain(inbox, clock::get, { PushWakeDrain.Inspection.ALLOW }, { batch ->
            batch.items.map { it.result(outcome) }
        }) { failures.incrementAndGet() }
        return PushWakeRunLoop(target, drain::run, clock::get) { failures.incrementAndGet() }
    }

    private fun workCount(tag: String, count: Int): List<WorkInfo> = runBlocking {
        withTimeout(15_000) { manager.getWorkInfosByTagFlow(tag).first { it.size >= count } }
    }

    private fun finished(id: UUID): WorkInfo = runBlocking {
        withTimeout(15_000) { manager.getWorkInfoByIdFlow(id).filterNotNull().first { it.state.isFinished } }
    }

    @Test fun independentSuccessorRunsBeforeOlderWorkerReturnsAndSurvivesItsFailure() {
        val firstChecked = CountDownLatch(1)
        val secondDrained = CountDownLatch(1)
        val runs = AtomicInteger()
        PushWakeWorkProbes.drain = {
            when (runs.incrementAndGet()) {
                1 -> {
                    check(runBlocking { loop().run() } == PushWakeRunLoop.Result.SUCCESS)
                    firstChecked.countDown() // final inbox check has already completed
                    check(releaseFirst.await(15, TimeUnit.SECONDS))
                    ListenableWorker.Result.failure() // later failure must not poison successor
                }
                2 -> {
                    check(runBlocking { loop().run() } == PushWakeRunLoop.Result.SUCCESS)
                    secondDrained.countDown()
                    ListenableWorker.Result.success()
                }
                else -> error("unexpected drain worker")
            }
        }
        assertEquals(PushWakeIntake.Result.QUEUED, dispatcher.onData(payload, clock.get()))
        val first = workCount(PushWakeWorkManager.DRAIN_TAG, 1).single().id
        driver.setAllConstraintsMet(first)
        assertTrue(firstChecked.await(15, TimeUnit.SECONDS))
        assertEquals(PushWakeInbox.PendingResult.Empty, inbox.pending(clock.get()))
        assertEquals(WorkInfo.State.RUNNING, requireNotNull(manager.getWorkInfoById(first).get(15, TimeUnit.SECONDS)).state)

        assertEquals(PushWakeIntake.Result.QUEUED, dispatcher.onData(payload, clock.incrementAndGet()))
        val second = workCount(PushWakeWorkManager.DRAIN_TAG, 2).single { it.id != first }.id
        driver.setAllConstraintsMet(second)
        assertTrue(secondDrained.await(15, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.SUCCEEDED, finished(second).state)
        assertEquals(WorkInfo.State.RUNNING, requireNotNull(manager.getWorkInfoById(first).get(15, TimeUnit.SECONDS)).state)
        releaseFirst.countDown()
        assertEquals(WorkInfo.State.FAILED, finished(first).state)
        assertEquals(WorkInfo.State.SUCCEEDED, requireNotNull(manager.getWorkInfoById(second).get(15, TimeUnit.SECONDS)).state)
        assertEquals(PushWakeInbox.PendingResult.Empty, inbox.pending(clock.get()))
        assertNull(PushWakeWorkProbes.error.get())
        assertEquals(0, failures.get())
    }

    @Test fun enqueueFailuresReleaseTheGateAndPreserveThePersistedSignal() {
        var failureCompleter: CallbackToFutureAdapter.Completer<Operation.State.SUCCESS>? = null
        val failed = CallbackToFutureAdapter.getFuture<Operation.State.SUCCESS> { completer ->
            failureCompleter = completer
            "fake enqueue failure"
        }
        var cancellationCompleter: CallbackToFutureAdapter.Completer<Operation.State.SUCCESS>? = null
        val cancelled = CallbackToFutureAdapter.getFuture<Operation.State.SUCCESS> { completer ->
            cancellationCompleter = completer
            "fake enqueue cancellation"
        }
        val attempts = AtomicInteger()
        val failingBackend = PushWakeWorkManager(manager, PushWakeDrainProbeWorker::class.java,
            PushWakeRecoveryProbeWorker::class.java, { request ->
                when (attempts.incrementAndGet()) {
                    1 -> throw IllegalStateException("synthetic synchronous failure")
                    2 -> cancelled
                    3 -> failed
                    else -> manager.enqueue(request).result
                }
            })
        val failingGate = PushWakeScheduleGate(failingBackend::enqueue) { failures.incrementAndGet() }
        val target = PushWakeDispatcher(failingGate, { PushWakeIntake.Hint.FOUND }, inbox::enqueueRoute,
            inbox::enqueueRecovery, inbox::pending, { failures.incrementAndGet() })

        assertEquals(PushWakeIntake.Result.QUEUED_WITHOUT_SCHEDULER, target.onData(payload, clock.get()))
        assertEquals(1, failures.get())
        assertTrue(inbox.pending(clock.get()) is PushWakeInbox.PendingResult.Pending)
        assertEquals(PushWakeIntake.Result.QUEUED, target.resume(clock.get()))
        assertTrue(cancellationCompleter != null) // retain the completer until cancellation
        assertTrue(cancelled.cancel(false))
        assertEquals(2, failures.get())
        assertEquals(PushWakeIntake.Result.QUEUED, target.resume(clock.get()))
        assertTrue(requireNotNull(failureCompleter).setException(IllegalStateException("synthetic asynchronous failure")))
        assertEquals(3, failures.get())
        assertTrue(manager.getWorkInfosByTag(PushWakeWorkManager.DRAIN_TAG).get(15, TimeUnit.SECONDS).isEmpty())

        val drained = CountDownLatch(1)
        PushWakeWorkProbes.drain = {
            check(runBlocking { loop(target).run() } == PushWakeRunLoop.Result.SUCCESS)
            drained.countDown()
            ListenableWorker.Result.success()
        }
        assertEquals(PushWakeIntake.Result.QUEUED, target.resume(clock.get()))
        val id = workCount(PushWakeWorkManager.DRAIN_TAG, 1).single().id
        driver.setAllConstraintsMet(id)
        assertTrue(drained.await(15, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.SUCCEEDED, finished(id).state)
        assertEquals(PushWakeInbox.PendingResult.Empty, inbox.pending(clock.get()))
        assertEquals(4, attempts.get())
        assertEquals(3, failures.get())
        assertNull(PushWakeWorkProbes.error.get())
    }

    @Test fun retryResultKeepsTheSignalUntilIndependentPeriodicRecovery() {
        backend.recovery().result.get(15, TimeUnit.SECONDS)
        val recoveryId = manager.getWorkInfosForUniqueWork(PushWakeWorkManager.RECOVERY_NAME)
            .get(15, TimeUnit.SECONDS).single().id
        PushWakeWorkProbes.drain = {
            check(runBlocking { loop(outcome = PushWakeDrain.SyncOutcome.RETRY).run() } == PushWakeRunLoop.Result.RETRY)
            ListenableWorker.Result.retry()
        }
        assertEquals(PushWakeIntake.Result.QUEUED, dispatcher.onData(payload, clock.get()))
        val id = workCount(PushWakeWorkManager.DRAIN_TAG, 1).single().id
        driver.setAllConstraintsMet(id)
        val retry = runBlocking {
            withTimeout(15_000) {
                manager.getWorkInfoByIdFlow(id).filterNotNull()
                    .first { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
            }
        }
        assertTrue(retry.runAttemptCount > 0)
        val pending = inbox.pending(clock.get()) as PushWakeInbox.PendingResult.Pending
        assertEquals(clock.get() + PushWakeInbox.RETRY_MS, pending.readyAtMs)

        clock.addAndGet(PushWakeInbox.RETRY_MS)
        val recovered = CountDownLatch(1)
        PushWakeWorkProbes.recovery = {
            check(runBlocking { loop().run() } == PushWakeRunLoop.Result.SUCCESS)
            recovered.countDown()
            ListenableWorker.Result.success()
        }
        driver.setPeriodDelayMet(recoveryId)
        driver.setAllConstraintsMet(recoveryId)
        assertTrue(recovered.await(15, TimeUnit.SECONDS))
        assertEquals(PushWakeInbox.PendingResult.Empty, inbox.pending(clock.get()))
        assertEquals(0, failures.get())
        assertNull(PushWakeWorkProbes.error.get())
    }

    @Test fun periodicRecoveryDirectlyDrainsPersistentInboxDespiteStaleTicket() {
        backend.recovery().result.get(15, TimeUnit.SECONDS)
        backend.recovery().result.get(15, TimeUnit.SECONDS)
        val jobs = manager.getWorkInfosForUniqueWork(PushWakeWorkManager.RECOVERY_NAME).get(15, TimeUnit.SECONDS)
        assertEquals(1, jobs.size)
        val recoveryId = jobs.single().id
        assertEquals(PushWakeInbox.EnqueueResult.QUEUED, inbox.enqueueRoute(payload.getValue("route"), clock.get()))
        inbox.close() // simulate a crash after SQLite commit and before scheduling
        gate.workerStarted() // a stale in-memory ticket must not block recovery
        assertEquals(PushWakeIntake.Result.QUEUED, dispatcher.resume(clock.get()))
        assertTrue(manager.getWorkInfosByTag(PushWakeWorkManager.DRAIN_TAG).get(15, TimeUnit.SECONDS).isEmpty())
        val recovered = CountDownLatch(1)
        PushWakeWorkProbes.recovery = {
            check(runBlocking { loop().run() } == PushWakeRunLoop.Result.SUCCESS)
            recovered.countDown()
            ListenableWorker.Result.success()
        }
        driver.setPeriodDelayMet(recoveryId)
        driver.setAllConstraintsMet(recoveryId)
        assertTrue(recovered.await(15, TimeUnit.SECONDS))
        assertEquals(PushWakeInbox.PendingResult.Empty, inbox.pending(clock.get()))

        val redrained = CountDownLatch(1)
        PushWakeWorkProbes.drain = {
            check(runBlocking { loop().run() } == PushWakeRunLoop.Result.SUCCESS)
            redrained.countDown()
            ListenableWorker.Result.success()
        }
        assertEquals(PushWakeIntake.Result.QUEUED, dispatcher.onData(payload, clock.incrementAndGet()))
        val next = workCount(PushWakeWorkManager.DRAIN_TAG, 1).single().id
        driver.setAllConstraintsMet(next)
        assertTrue(redrained.await(15, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.SUCCEEDED, finished(next).state)
        assertEquals(PushWakeInbox.PendingResult.Empty, inbox.pending(clock.get()))
        assertNull(PushWakeWorkProbes.error.get())
        assertEquals(0, failures.get())
    }

}

private object PushWakeWorkProbes {
    @Volatile var drain: (() -> ListenableWorker.Result)? = null
    @Volatile var recovery: (() -> ListenableWorker.Result)? = null
    val error = AtomicReference<Throwable?>()

    fun run(parameters: WorkerParameters, action: (() -> ListenableWorker.Result)?): ListenableWorker.Result = try {
        check(parameters.inputData.keyValueMap.isEmpty()) // never persist routes, tokens or identities in WorkManager
        requireNotNull(action).invoke()
    } catch (failure: Throwable) {
        error.compareAndSet(null, failure)
        ListenableWorker.Result.failure()
    }

    fun clear() { drain = null; recovery = null; error.set(null) }
}

class PushWakeDrainProbeWorker(context: Context, private val params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = PushWakeWorkProbes.run(params, PushWakeWorkProbes.drain)
}

class PushWakeRecoveryProbeWorker(context: Context, private val params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = PushWakeWorkProbes.run(params, PushWakeWorkProbes.recovery)
}

private class PushWakeProbeFactory : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            PushWakeDrainProbeWorker::class.java.name -> PushWakeDrainProbeWorker(appContext, workerParameters)
            PushWakeRecoveryProbeWorker::class.java.name -> PushWakeRecoveryProbeWorker(appContext, workerParameters)
            else -> null
        }
}
