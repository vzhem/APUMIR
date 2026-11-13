package com.vladimir.messenger.worker

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import com.vladimir.messenger.service.DisposableNotificationTestProfile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

/** Real WorkManager test scheduler; the local queue and notification effects are probes. */
@RunWith(AndroidJUnit4::class)
class NotificationRecoveryWorkManagerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var manager: WorkManager
    private lateinit var driver: TestDriver
    private lateinit var backend: NotificationRecoveryWorkManager
    private lateinit var gate: NotificationRecoveryScheduleGate
    private val executor = Executors.newFixedThreadPool(4)
    private val release = CountDownLatch(1)
    private val pending = AtomicInteger()
    private val failures = AtomicInteger()
    private var installedTestManager = false

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DisposableNotificationTestProfile.requireEmptyEmulator(context)
        NotificationRecoveryProbes.clear()
        val config = Configuration.Builder().setExecutor(executor).setWorkerFactory(NotificationRecoveryProbeFactory()).build()
        installedTestManager = true
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        manager = WorkManager.getInstance(context)
        driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        backend = NotificationRecoveryWorkManager(manager, NotificationRecoveryProbeWorker::class.java)
        gate = NotificationRecoveryScheduleGate(
            enqueue = { id, failed ->
                NotificationRecoveryOperationObserver.watch(backend.oneOff(id).result, failed)
                true
            },
            recordFailure = { failures.incrementAndGet() },
        )
    }

    @After @Suppress("RestrictedApi") fun tearDown() {
        release.countDown()
        try {
            if (::manager.isInitialized) {
                manager.cancelAllWorkByTag(NotificationRecoveryWorkManager.ONE_OFF_TAG).result.get(15, TimeUnit.SECONDS)
                manager.cancelUniqueWork(NotificationRecoveryWorkManager.PERIODIC_NAME).result.get(15, TimeUnit.SECONDS)
                executor.shutdown()
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
                WorkManagerTestInitHelper.closeWorkDatabase()
            }
        } finally {
            if (installedTestManager) WorkManagerImpl.setDelegate(null)
            executor.shutdownNow()
            if (installedTestManager) NotificationRecoveryProbes.clear()
        }
    }

    private fun workCount(count: Int): List<WorkInfo> = runBlocking {
        withTimeout(15_000) { manager.getWorkInfosByTagFlow(NotificationRecoveryWorkManager.ONE_OFF_TAG).first { it.size >= count } }
    }
    private fun finished(id: UUID): WorkInfo = runBlocking {
        withTimeout(15_000) { manager.getWorkInfoByIdFlow(id).filterNotNull().first { it.state.isFinished } }
    }

    @Test fun arrivalAfterFinalCheckStartsIndependentSuccessorBeforeOlderFailure() {
        val finalCheck = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val runs = AtomicInteger()
        NotificationRecoveryProbes.action = { id ->
            gate.workerStarted(id)
            when (runs.incrementAndGet()) {
                1 -> {
                    assertEquals(0, pending.get())
                    gate.workerFinished(id, retrying = false)
                    finalCheck.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    ListenableWorker.Result.failure() // must not poison the independent successor
                }
                2 -> {
                    pending.set(0)
                    gate.workerFinished(id, retrying = false)
                    recovered.countDown()
                    ListenableWorker.Result.success()
                }
                else -> error("unexpected worker")
            }
        }
        assertTrue(gate.request())
        val first = workCount(1).single()
        assertEquals(NetworkType.NOT_REQUIRED, first.constraints.requiredNetworkType)
        assertTrue(finalCheck.await(15, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.RUNNING, manager.getWorkInfoById(first.id).get(15, TimeUnit.SECONDS)!!.state)
        pending.set(1) // committed after the first worker's final check
        assertTrue(gate.request())
        val successor = workCount(2).single { it.id != first.id }
        assertTrue(recovered.await(15, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.SUCCEEDED, finished(successor.id).state)
        assertEquals(WorkInfo.State.RUNNING, manager.getWorkInfoById(first.id).get(15, TimeUnit.SECONDS)!!.state)
        release.countDown()
        assertEquals(WorkInfo.State.FAILED, finished(first.id).state)
        assertEquals(0, pending.get())
        assertEquals(0, failures.get())
        assertNull(NotificationRecoveryProbes.error.get())
    }

    @Test fun arrivalBeforeFinishCreatesOneSuccessorEvenIfTheOlderWorkerFails() {
        val firstStarted = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val runs = AtomicInteger()
        NotificationRecoveryProbes.action = { id ->
            gate.workerStarted(id)
            when (runs.incrementAndGet()) {
                1 -> {
                    firstStarted.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    gate.workerFinished(id, retrying = false)
                    ListenableWorker.Result.failure()
                }
                2 -> {
                    pending.set(0)
                    gate.workerFinished(id, retrying = false)
                    recovered.countDown()
                    ListenableWorker.Result.success()
                }
                else -> error("unexpected worker")
            }
        }
        gate.request()
        val first = workCount(1).single().id
        assertTrue(firstStarted.await(15, TimeUnit.SECONDS))
        pending.set(1)
        repeat(20) { assertTrue(gate.request()) }
        assertEquals(1, workCount(1).size)
        release.countDown()
        val second = workCount(2).single { it.id != first }.id
        assertTrue(recovered.await(15, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.FAILED, finished(first).state)
        assertEquals(WorkInfo.State.SUCCEEDED, finished(second).state)
        assertEquals(0, pending.get())
        assertEquals(0, failures.get())
        assertNull(NotificationRecoveryProbes.error.get())
    }

    @Test fun asynchronousEnqueueFailuresReleaseTheGateAndAllowRealWorkManagerRetry() {
        var failedCompleter: CallbackToFutureAdapter.Completer<Operation.State.SUCCESS>? = null
        val failed = CallbackToFutureAdapter.getFuture<Operation.State.SUCCESS> { completer ->
            failedCompleter = completer
            "synthetic notification enqueue failure"
        }
        var cancelledCompleter: CallbackToFutureAdapter.Completer<Operation.State.SUCCESS>? = null
        val cancelled = CallbackToFutureAdapter.getFuture<Operation.State.SUCCESS> { completer ->
            cancelledCompleter = completer
            "synthetic notification enqueue cancellation"
        }
        val attempts = AtomicInteger()
        val testGate = NotificationRecoveryScheduleGate(
            enqueue = { id, onFailure ->
                when (attempts.incrementAndGet()) {
                    1 -> throw IllegalStateException("synthetic synchronous failure")
                    2 -> NotificationRecoveryOperationObserver.watch(cancelled, onFailure)
                    3 -> NotificationRecoveryOperationObserver.watch(failed, onFailure)
                    else -> NotificationRecoveryOperationObserver.watch(backend.oneOff(id).result, onFailure)
                }
                true
            },
            recordFailure = { failures.incrementAndGet() },
        )
        pending.set(1) // probe for work already persisted before enqueue
        assertFalse(testGate.request())
        assertEquals(1, failures.get())
        assertTrue(testGate.request())
        assertTrue(cancelledCompleter != null) // retain until the future is cancelled
        assertTrue(cancelled.cancel(false))
        assertEquals(2, failures.get())
        assertTrue(testGate.request())
        assertTrue(requireNotNull(failedCompleter).setException(IllegalStateException("synthetic asynchronous failure")))
        assertEquals(3, failures.get())
        assertEquals(1, pending.get())
        assertTrue(manager.getWorkInfosByTag(NotificationRecoveryWorkManager.ONE_OFF_TAG).get(15, TimeUnit.SECONDS).isEmpty())

        val recovered = CountDownLatch(1)
        NotificationRecoveryProbes.action = { id ->
            testGate.workerStarted(id)
            pending.set(0)
            testGate.workerFinished(id, retrying = false)
            recovered.countDown()
            ListenableWorker.Result.success()
        }
        assertTrue(testGate.request())
        val actual = workCount(1).single().id
        assertTrue(recovered.await(15, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.SUCCEEDED, finished(actual).state)
        assertEquals(0, pending.get())
        assertEquals(3, failures.get())
        assertNull(NotificationRecoveryProbes.error.get())
    }

    @Test fun independentPeriodicPassRepairsACommitWithoutAnEnqueueDespiteAStaleTicket() {
        // Unconstrained periodic work may run immediately for its first tick.
        val initial = CountDownLatch(1)
        NotificationRecoveryProbes.action = { _ -> initial.countDown(); ListenableWorker.Result.success() }
        backend.periodic().result.get(15, TimeUnit.SECONDS)
        assertTrue(initial.await(15, TimeUnit.SECONDS))
        backend.periodic().result.get(15, TimeUnit.SECONDS)
        val periodic = manager.getWorkInfosForUniqueWork(NotificationRecoveryWorkManager.PERIODIC_NAME)
            .get(15, TimeUnit.SECONDS).single()
        assertEquals(NetworkType.NOT_REQUIRED, periodic.constraints.requiredNetworkType)
        runBlocking {
            withTimeout(15_000) {
                manager.getWorkInfoByIdFlow(periodic.id).filterNotNull().first { it.state == WorkInfo.State.ENQUEUED }
            }
        }
        // A lost scheduler ticket must not be needed by this separate worker.
        val stale = NotificationRecoveryScheduleGate(enqueue = { _, _ -> true }, recordFailure = {})
        assertTrue(stale.request())
        pending.set(1) // simulate a crash after the local commit and before enqueue
        val recovered = CountDownLatch(1)
        NotificationRecoveryProbes.action = { id ->
            check(id == periodic.id)
            stale.workerStarted(id) // untracked periodic work cannot steal the ticket
            pending.set(0)
            stale.workerFinished(id, retrying = false)
            recovered.countDown()
            ListenableWorker.Result.success()
        }
        driver.setPeriodDelayMet(periodic.id)
        assertTrue(recovered.await(15, TimeUnit.SECONDS))
        assertEquals(0, pending.get())
        assertEquals(0, failures.get())
        assertNull(NotificationRecoveryProbes.error.get())
    }
}

private object NotificationRecoveryProbes {
    @Volatile var action: ((UUID) -> ListenableWorker.Result)? = null
    val error = AtomicReference<Throwable?>()
    fun run(parameters: WorkerParameters): ListenableWorker.Result = try {
        check(parameters.inputData.keyValueMap.isEmpty()) // no identity, message or peer in WorkManager
        requireNotNull(action).invoke(parameters.id)
    } catch (failure: Throwable) {
        error.compareAndSet(null, failure)
        ListenableWorker.Result.failure()
    }
    fun clear() { action = null; error.set(null) }
}

class NotificationRecoveryProbeWorker(context: Context, private val params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = NotificationRecoveryProbes.run(params)
}

private class NotificationRecoveryProbeFactory : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == NotificationRecoveryProbeWorker::class.java.name) {
            NotificationRecoveryProbeWorker(appContext, workerParameters)
        } else null
}
