package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake progress, index and route effects: not Android, Firebase, network or delivery. */
class PushWakeRecoveryScanTest {
    private val scope = "a".repeat(64)
    private val signal = PushWakeDrain.RecoverySignal("e".repeat(64), 1L)
    private val a = "1".repeat(64)
    private val b = "2".repeat(64)
    private val c = "3".repeat(64)
    private val d = "4".repeat(64)

    private class MemoryProgress : PushWakeRecoveryProgress {
        var state: PushWakeRecoveryProgress.Snapshot? = null
        var counter = 0L
        var onLoad: () -> Unit = {}
        var onAdvance: () -> Unit = {}
        var onReset: () -> Unit = {}
        var unavailable: String? = null
        private fun next(scope: String, revision: Long, after: String?, failed: Boolean, visited: Int) =
            PushWakeRecoveryProgress.Snapshot(scope, revision, after, failed, visited, "%064x".format(++counter))
        override fun load(scope: String, revision: Long, current: () -> Boolean): PushWakeRecoveryProgress.Result {
            if (unavailable == "load") return PushWakeRecoveryProgress.Result.Unavailable
            if (!current()) return PushWakeRecoveryProgress.Result.Stale
            if (revision <= 0) return PushWakeRecoveryProgress.Result.Unavailable
            val previous = state
            if (previous != null && previous.scope == scope && revision < previous.startRevision) return PushWakeRecoveryProgress.Result.Stale
            val snapshot = previous?.takeIf { it.scope == scope && (it.after != null || revision == it.startRevision) }
                ?: next(scope, revision, null, false, 0).also { state = it }
            onLoad()
            return PushWakeRecoveryProgress.Result.Ready(snapshot)
        }
        override fun advance(expected: PushWakeRecoveryProgress.Snapshot, after: String, failed: Boolean,
            current: () -> Boolean): PushWakeRecoveryProgress.Result {
            if (unavailable == "advance") return PushWakeRecoveryProgress.Result.Unavailable
            if (!current() || state?.token != expected.token) return PushWakeRecoveryProgress.Result.Stale
            val next = next(expected.scope, expected.startRevision, after, expected.hadFailure || failed, expected.visited + 1)
            state = next
            onAdvance()
            return PushWakeRecoveryProgress.Result.Ready(next)
        }
        override fun reset(expected: PushWakeRecoveryProgress.Snapshot, current: () -> Boolean): PushWakeRecoveryProgress.Result {
            if (unavailable == "reset") return PushWakeRecoveryProgress.Result.Unavailable
            if (!current() || state?.token != expected.token) return PushWakeRecoveryProgress.Result.Stale
            val next = next(expected.scope, expected.startRevision, null, false, 0)
            state = next
            onReset()
            return PushWakeRecoveryProgress.Result.Ready(next)
        }
        fun touch() { state = state!!.let { next(it.scope, it.startRevision, it.after, it.hadFailure, it.visited) } }
    }

    private class Fixture(vararg initial: String, val limit: Int = 2) {
        val progress = MemoryProgress()
        val routes = initial.toMutableList()
        val inspected = mutableListOf<String>()
        val synced = mutableListOf<String>()
        val rejected = mutableSetOf<String>()
        val retryInspection = mutableSetOf<String>()
        val retrySync = mutableSetOf<String>()
        var live = true
        var failures = 0
        var pages = 0
        fun page(after: String?, limit: Int): PushRegistrationStore.RecoveryPage {
            pages++
            assertEquals(1, limit)
            val remaining = routes.sorted().filter { after == null || it > after }.take(2)
            return PushRegistrationStore.RecoveryPage.Ready(remaining.take(1), if (remaining.size > 1) remaining[0] else null)
        }
        fun scan(maxScan: Int = PushWakeRecoveryProgress.MAX_SCAN_ROUTES) = PushWakeRecoveryScan(progress, ::page, { route ->
            inspected += route
            when (route) {
                in rejected -> PushWakeDrain.Inspection.DROP
                in retryInspection -> PushWakeDrain.Inspection.RETRY
                else -> PushWakeDrain.Inspection.ALLOW
            }
        }, { route ->
            synced += route
            if (route in retrySync) PushWakeDrain.SyncOutcome.RETRY else PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
        }, { live }, { failures++ }, limit, maxScan)
    }

    @Test fun boundedPassResumesAndOnlyCompletesAfterTheLastReceiveWindow() = runBlocking {
        val f = Fixture(a, b, c, d)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, signal))
        assertEquals(listOf(a, b), f.synced)
        assertEquals(b, f.progress.state!!.after)
        assertFalse(f.progress.state!!.hadFailure)
        // A new scan object uses the saved cursor instead of restarting.
        assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, f.scan().run(scope, signal))
        assertEquals(listOf(a, b, c, d), f.synced)
        assertEquals(null, f.progress.state!!.after)
        assertFalse(f.progress.state!!.hadFailure)
        assertEquals(0, f.failures)
    }

    @Test fun failedRouteDoesNotStarveLaterRoutesAndForcesAFullRescan() = runBlocking {
        val f = Fixture(a, b, c)
        f.retrySync += a
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, signal))
        assertEquals(listOf(a, b), f.synced)
        assertEquals(b, f.progress.state!!.after)
        assertTrue(f.progress.state!!.hadFailure)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, signal))
        assertEquals(listOf(a, b, c), f.synced)
        assertEquals(null, f.progress.state!!.after)
        assertFalse(f.progress.state!!.hadFailure)
        f.retrySync.clear()
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, signal))
        assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, f.scan().run(scope, signal))
        assertEquals(listOf(a, b, c, a, b, c), f.synced)
    }

    @Test fun droppedHintIsNotSyncedAndInspectionRetryStillPreventsSuccess() = runBlocking {
        val f = Fixture(a, b, c, limit = 3)
        f.rejected += a
        f.retryInspection += b
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, signal))
        assertEquals(listOf(a, b, c), f.inspected)
        assertEquals(listOf(c), f.synced)
        assertEquals(null, f.progress.state!!.after)
        f.retryInspection.clear()
        assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, f.scan().run(scope, signal))
        assertEquals(listOf(c, b, c), f.synced)
    }

    @Test fun unavailableProgressNeverPretendsTheScanCompleted() = runBlocking {
        for (stage in listOf("load", "advance", "reset")) {
            val f = Fixture(a, limit = 1)
            f.progress.unavailable = stage
            assertEquals(stage, PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, signal))
            assertEquals(stage, if (stage == "load") emptyList<String>() else listOf(a), f.synced)
            f.progress.unavailable = null
            assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, f.scan().run(scope, signal))
            // A failed advance replays; a failed reset completes from the cursor.
            assertEquals(if (stage == "advance") listOf(a, a) else listOf(a), f.synced)
        }
    }

    @Test fun crashBeforeCheckpointReplaysOnlyTheUncheckpointedRoute() = runBlocking {
        val f = Fixture(a, b, c, limit = 3)
        var attempts = 0
        val progress = object : PushWakeRecoveryProgress by f.progress {
            override fun advance(expected: PushWakeRecoveryProgress.Snapshot, after: String, failed: Boolean,
                current: () -> Boolean): PushWakeRecoveryProgress.Result {
                if (++attempts == 2) error("private checkpoint failure")
                return f.progress.advance(expected, after, failed, current)
            }
        }
        val scan = PushWakeRecoveryScan(progress, f::page, { PushWakeDrain.Inspection.ALLOW }, {
            f.synced += it
            PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
        }, { f.live }, { f.failures++ }, 3)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, scan.run(scope, signal))
        assertEquals(a, f.progress.state!!.after)
        assertEquals(listOf(a, b), f.synced)
        assertEquals(1, f.failures)
        assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, scan.run(scope, signal))
        assertEquals(listOf(a, b, b, c), f.synced)
    }

    @Test fun invalidPagesAndUnavailableIndexCannotStartEffects() = runBlocking {
        val cases = listOf(
            PushRegistrationStore.RecoveryPage.Invalid,
            PushRegistrationStore.RecoveryPage.Unavailable,
            PushRegistrationStore.RecoveryPage.Ready(listOf(a, b), a),
            PushRegistrationStore.RecoveryPage.Ready(emptyList(), a),
            PushRegistrationStore.RecoveryPage.Ready(listOf(a), b),
            PushRegistrationStore.RecoveryPage.Ready(listOf("bad"), null),
        )
        for (page in cases) {
            val progress = MemoryProgress()
            var effects = 0
            val scanner = PushWakeRecoveryScan(progress, { _, _ -> page }, { effects++; PushWakeDrain.Inspection.ALLOW },
                { effects++; PushWakeDrain.SyncOutcome.COMPLETED_WINDOW }, { true }, { error("unexpected diagnostics") })
            assertEquals(PushWakeDrain.SyncOutcome.RETRY, scanner.run(scope, signal))
            assertEquals(0, effects)
            assertEquals(null, progress.state!!.after)
        }
        val progress = MemoryProgress()
        val start = (progress.load(PushWakeRecoveryScan.checkpointScope(scope, signal.incarnation), signal.revision) { true } as PushWakeRecoveryProgress.Result.Ready).snapshot
        progress.advance(start, b, false) { true }
        val scanner = PushWakeRecoveryScan(progress, { _, _ -> PushRegistrationStore.RecoveryPage.Ready(listOf(b), null) },
            { error("repeated cursor must not be inspected") }, { error("repeated cursor must not be synced") }, { true }, {})
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, scanner.run(scope, signal))
        assertEquals(b, progress.state!!.after)
    }

    @Test fun malformedOrInconsistentProgressCannotAuthorizeCompletion() = runBlocking {
        val f = Fixture(a, limit = 1)
        for (kind in listOf("loadScope", "loadToken", "loadFailure", "loadVisited", "loadScopeWithCursor", "loadTokenWithCursor",
            "loadRevisionZero", "loadRevisionFuture", "advancedCursor", "advancedFailure", "advancedToken", "advancedVisited",
            "advancedRevision", "resetCursor", "resetVisited", "resetRevision")) {
            val delegate = MemoryProgress()
            var resetCalls = 0
            val progress = object : PushWakeRecoveryProgress by delegate {
                override fun load(scope: String, revision: Long, current: () -> Boolean): PushWakeRecoveryProgress.Result {
                    val result = delegate.load(scope, revision, current) as PushWakeRecoveryProgress.Result.Ready
                    val s = result.snapshot
                    val bad = when (kind) {
                        "loadScope" -> PushWakeRecoveryProgress.Snapshot("f".repeat(64), s.startRevision, s.after, s.hadFailure, s.visited, s.token)
                        "loadToken" -> PushWakeRecoveryProgress.Snapshot(s.scope, s.startRevision, s.after, s.hadFailure, s.visited, "0".repeat(64))
                        "loadFailure" -> PushWakeRecoveryProgress.Snapshot(s.scope, s.startRevision, null, true, 0, s.token)
                        "loadVisited" -> PushWakeRecoveryProgress.Snapshot(s.scope, s.startRevision, null, false, 1, s.token)
                        "loadScopeWithCursor" -> PushWakeRecoveryProgress.Snapshot("f".repeat(64), s.startRevision, a, false, 1, s.token)
                        "loadTokenWithCursor" -> PushWakeRecoveryProgress.Snapshot(s.scope, s.startRevision, a, false, 1, "0".repeat(64))
                        "loadRevisionZero" -> PushWakeRecoveryProgress.Snapshot(s.scope, 0, s.after, s.hadFailure, s.visited, s.token)
                        "loadRevisionFuture" -> PushWakeRecoveryProgress.Snapshot(s.scope, 2, s.after, s.hadFailure, s.visited, s.token)
                        else -> s
                    }
                    return PushWakeRecoveryProgress.Result.Ready(bad)
                }
                override fun advance(expected: PushWakeRecoveryProgress.Snapshot, after: String, failed: Boolean,
                    current: () -> Boolean): PushWakeRecoveryProgress.Result {
                    val result = delegate.advance(expected, after, failed, current) as PushWakeRecoveryProgress.Result.Ready
                    val s = result.snapshot
                    val bad = when (kind) {
                        "advancedCursor" -> PushWakeRecoveryProgress.Snapshot(s.scope, s.startRevision, b, s.hadFailure, s.visited, s.token)
                        "advancedFailure" -> PushWakeRecoveryProgress.Snapshot(s.scope, s.startRevision, s.after, !s.hadFailure, s.visited, s.token)
                        "advancedToken" -> PushWakeRecoveryProgress.Snapshot(s.scope, s.startRevision, s.after, s.hadFailure, s.visited, expected.token)
                        "advancedVisited" -> PushWakeRecoveryProgress.Snapshot(s.scope, s.startRevision, s.after, s.hadFailure, s.visited + 1, s.token)
                        "advancedRevision" -> PushWakeRecoveryProgress.Snapshot(s.scope, s.startRevision + 1, s.after, s.hadFailure, s.visited, s.token)
                        else -> s
                    }
                    return PushWakeRecoveryProgress.Result.Ready(bad)
                }
                override fun reset(expected: PushWakeRecoveryProgress.Snapshot, current: () -> Boolean): PushWakeRecoveryProgress.Result {
                    resetCalls++
                    val result = delegate.reset(expected, current) as PushWakeRecoveryProgress.Result.Ready
                    val s = result.snapshot
                    val bad = when (kind) {
                        "resetCursor" -> PushWakeRecoveryProgress.Snapshot(s.scope, s.startRevision, a, false, 1, s.token)
                        "resetVisited" -> PushWakeRecoveryProgress.Snapshot(s.scope, s.startRevision, null, false, 1, s.token)
                        "resetRevision" -> PushWakeRecoveryProgress.Snapshot(s.scope, s.startRevision + 1, null, false, 0, s.token)
                        else -> s
                    }
                    return PushWakeRecoveryProgress.Result.Ready(bad)
                }
            }
            val scan = PushWakeRecoveryScan(progress, f::page, { PushWakeDrain.Inspection.ALLOW },
                { PushWakeDrain.SyncOutcome.COMPLETED_WINDOW }, { true }, {})
            assertEquals(kind, PushWakeDrain.SyncOutcome.RETRY, scan.run(scope, signal))
            // An inconsistent advance must stop immediately, not merely fail a
            // later reset because the fake store rejects the forged snapshot.
            assertEquals(kind, if (kind.startsWith("reset")) 1 else 0, resetCalls)
        }
    }

    @Test fun newerRevisionsFinishTheOldPassBeforeStartingAFullNewPass() = runBlocking {
        val f = Fixture(a, b, c, d, limit = 1)
        for (revision in 1L..4L) {
            val newer = PushWakeDrain.RecoverySignal(signal.incarnation, revision)
            assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, newer))
        }
        // A new wake on every lease did not starve routes behind the cursor;
        // but no newer wake may be consumed by the earlier partial scan.
        assertEquals(listOf(a, b, c, d), f.synced)
        assertEquals(null, f.progress.state!!.after)
        val latest = PushWakeDrain.RecoverySignal(signal.incarnation, 4L)
        repeat(3) { assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, latest)) }
        assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, f.scan().run(scope, latest))
        assertEquals(listOf(a, b, c, d, a, b, c, d), f.synced)
        assertEquals(4L, f.progress.state!!.startRevision)
    }

    @Test fun recreatedMarkerNeverInheritsAnEarlierPartialPass() = runBlocking {
        val f = Fixture(a, b, limit = 1)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, signal))
        val old = f.progress.state!!
        val recreated = PushWakeDrain.RecoverySignal("f".repeat(64), signal.revision)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, recreated))
        assertEquals(listOf(a, a), f.synced)
        assertEquals(PushWakeRecoveryProgress.Result.Stale, f.progress.advance(old, b, false) { true })
        assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, f.scan().run(scope, recreated))
        assertEquals(listOf(a, a, b), f.synced)
    }

    @Test fun invalidOrOlderMarkerCannotCompleteOrStartEffects() = runBlocking {
        val f = Fixture(a, b, limit = 1)
        val invalid = listOf(null, PushWakeDrain.RecoverySignal("bad", 1),
            PushWakeDrain.RecoverySignal("0".repeat(64), 1), PushWakeDrain.RecoverySignal(signal.incarnation, 0))
        for (value in invalid) assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, value))
        assertTrue(f.synced.isEmpty())
        assertEquals(null, f.progress.state)
        val newer = PushWakeDrain.RecoverySignal(signal.incarnation, 3)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, newer))
        assertEquals(listOf(a), f.synced)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, signal))
        assertEquals(listOf(a), f.synced)
        assertEquals(3L, f.progress.state!!.startRevision)
        assertFalse(signal.toString().contains(signal.incarnation))
    }

    @Test fun changedScopeRestartsAndInvalidatesAnOlderWriter() = runBlocking {
        val f = Fixture(a, b, c, limit = 1)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, signal))
        val old = f.progress.state!!
        val newScope = "b".repeat(64)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(newScope, signal))
        assertEquals(listOf(a, a), f.synced)
        assertEquals(PushWakeRecoveryProgress.Result.Stale, f.progress.advance(old, b, false) { true })
        assertEquals(PushWakeRecoveryProgress.Result.Stale, f.progress.reset(old) { true })
        assertEquals(PushWakeRecoveryScan.checkpointScope(newScope, signal.incarnation), f.progress.state!!.scope)
        assertEquals(a, f.progress.state!!.after)
    }

    @Test fun anotherWriterCannotBeOverwrittenAfterTheEffect() = runBlocking {
        val f = Fixture(a, limit = 1)
        var calls = 0
        val scan = PushWakeRecoveryScan(f.progress, f::page, { PushWakeDrain.Inspection.ALLOW }, {
            calls++
            if (calls == 1) f.progress.touch()
            PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
        }, { true }, {})
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, scan.run(scope, signal))
        assertEquals(null, f.progress.state!!.after)
        assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, scan.run(scope, signal))
        assertEquals(2, calls)
    }

    @Test fun changedCurrentIdentityPreventsFurtherEffectsAndCheckpointing() = runBlocking {
        for (stage in listOf("initial", "page", "inspect", "sync")) {
            val f = Fixture(a, limit = 1)
            if (stage == "initial") f.live = false
            val scan = PushWakeRecoveryScan(f.progress, { after, limit ->
                f.page(after, limit).also { if (stage == "page") f.live = false }
            }, {
                f.inspected += it
                if (stage == "inspect") f.live = false
                PushWakeDrain.Inspection.ALLOW
            }, {
                f.synced += it
                if (stage == "sync") f.live = false
                PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
            }, { f.live }, {})
            assertEquals(stage, PushWakeDrain.SyncOutcome.RETRY, scan.run(scope, signal))
            assertEquals(null, f.progress.state?.after)
            assertEquals(if (stage == "inspect" || stage == "sync") listOf(a) else emptyList<String>(), f.inspected)
            assertEquals(if (stage == "sync") listOf(a) else emptyList<String>(), f.synced)
        }
    }

    @Test fun cancelledCallbacksNeverTurnIntoCompletedRecovery() = runBlocking {
        for (stage in listOf("before", "load", "page", "inspect", "sync", "advance", "reset", "diagnostics")) {
            val f = Fixture(a, limit = 1)
            var returned = false
            val job = launch(start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]!!
                f.progress.onLoad = { if (stage == "load") self.cancel() }
                f.progress.onAdvance = { if (stage == "advance") self.cancel() }
                f.progress.onReset = { if (stage == "reset") self.cancel() }
                val scan = PushWakeRecoveryScan(f.progress, { after, limit ->
                    f.page(after, limit).also { if (stage == "page") self.cancel() }
                }, {
                    f.inspected += it
                    if (stage == "inspect") self.cancel()
                    if (stage == "diagnostics") error("private inspection data")
                    PushWakeDrain.Inspection.ALLOW
                }, {
                    f.synced += it
                    if (stage == "sync") self.cancel()
                    PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
                }, { true }, {
                    f.failures++
                    self.cancel()
                    error("private diagnostic data")
                })
                if (stage == "before") self.cancel()
                scan.run(scope, signal)
                returned = true
            }
            job.start()
            job.join()
            assertTrue(stage, job.isCancelled)
            assertFalse(stage, returned)
            if (stage in setOf("before", "load", "page")) assertTrue(stage, f.inspected.isEmpty())
            if (stage in setOf("before", "load", "page", "inspect", "diagnostics")) assertTrue(stage, f.synced.isEmpty())
            assertEquals(stage, if (stage == "advance") a else null, f.progress.state?.after)
        }
    }

    @Test fun cancellationRacingCurrentOrFailedProgressCannotReturnRetry() = runBlocking {
        for (stage in listOf("current", "load", "advance", "reset")) {
            val f = Fixture(a, limit = 1)
            var returned = false
            val job = launch(start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]!!
                val progress = object : PushWakeRecoveryProgress by f.progress {
                    override fun load(scope: String, revision: Long, current: () -> Boolean): PushWakeRecoveryProgress.Result {
                        if (stage == "load") { self.cancel(); return PushWakeRecoveryProgress.Result.Unavailable }
                        return f.progress.load(scope, revision, current)
                    }
                    override fun advance(expected: PushWakeRecoveryProgress.Snapshot, after: String, failed: Boolean,
                        current: () -> Boolean): PushWakeRecoveryProgress.Result {
                        if (stage == "advance") { self.cancel(); return PushWakeRecoveryProgress.Result.Stale }
                        return f.progress.advance(expected, after, failed, current)
                    }
                    override fun reset(expected: PushWakeRecoveryProgress.Snapshot, current: () -> Boolean): PushWakeRecoveryProgress.Result {
                        if (stage == "reset") { self.cancel(); return PushWakeRecoveryProgress.Result.Unavailable }
                        return f.progress.reset(expected, current)
                    }
                }
                val scan = PushWakeRecoveryScan(progress, f::page, { PushWakeDrain.Inspection.ALLOW },
                    { PushWakeDrain.SyncOutcome.COMPLETED_WINDOW }, {
                        if (stage == "current") { self.cancel(); false } else true
                    }, { error("unexpected diagnostics") })
                scan.run(scope, signal)
                returned = true
            }
            job.start()
            job.join()
            assertTrue(stage, job.isCancelled)
            assertFalse(stage, returned)
            assertEquals(stage, if (stage == "reset") a else null, f.progress.state?.after)
        }
    }

    @Test fun thrownCancellationPropagatesInsteadOfRecordingOrAdvancing() = runBlocking {
        for (stage in listOf("page", "inspect", "sync", "diagnostics")) {
            val f = Fixture(a, limit = 1)
            var diagnostic = 0
            val scanner = PushWakeRecoveryScan(f.progress, { after, limit ->
                if (stage == "page") throw CancellationException("stop")
                f.page(after, limit)
            }, {
                if (stage == "inspect") throw CancellationException("stop")
                if (stage == "diagnostics") error("private failure")
                PushWakeDrain.Inspection.ALLOW
            }, {
                if (stage == "sync") throw CancellationException("stop")
                PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
            }, { true }, {
                diagnostic++
                throw CancellationException("stop")
            })
            var cancelled = false
            try { scanner.run(scope, signal) } catch (_: CancellationException) { cancelled = true }
            assertTrue(stage, cancelled)
            assertEquals(null, f.progress.state!!.after)
            assertEquals(if (stage == "diagnostics") 1 else 0, diagnostic)
        }
    }

    @Test fun ordinaryFailuresDoNotStarveOtherRoutesEvenIfDiagnosticsIsBroken() = runBlocking {
        val f = Fixture(a, b, c, limit = 3)
        var diagnostics = 0
        val scan = PushWakeRecoveryScan(f.progress, f::page, {
            f.inspected += it
            if (it == a) error("private inspection data")
            PushWakeDrain.Inspection.ALLOW
        }, {
            f.synced += it
            if (it == b) error("private network data")
            PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
        }, { true }, { diagnostics++; error("private diagnostic data") }, 3)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, scan.run(scope, signal))
        assertEquals(listOf(a, b, c), f.inspected)
        assertEquals(listOf(b, c), f.synced)
        assertEquals(2, diagnostics)
        assertEquals(null, f.progress.state!!.after)
    }

    @Test fun totalScanBudgetSurvivesContinuationAndRestartsWithoutFalseSuccess() = runBlocking {
        val f = Fixture(a, b, c, limit = 1)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan(maxScan = 2).run(scope, signal))
        assertEquals(1, f.progress.state!!.visited)
        // Simulate a failed reset after the second route was checkpointed.
        f.progress.unavailable = "reset"
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan(maxScan = 2).run(scope, signal))
        assertEquals(listOf(a, b), f.synced)
        assertEquals(2, f.progress.state!!.visited)
        val pagesAtLimit = f.pages
        f.progress.unavailable = null
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan(maxScan = 2).run(scope, signal))
        assertEquals(pagesAtLimit, f.pages)
        assertEquals(0, f.progress.state!!.visited)
        assertEquals(null, f.progress.state!!.after)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan(maxScan = 2).run(scope, signal))
        assertEquals(listOf(a, b, a), f.synced)

        // The final item at the exact budget may finish when no more exists.
        val exact = Fixture(a, b, limit = 2)
        assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, exact.scan(maxScan = 2).run(scope, signal))
        assertEquals(listOf(a, b), exact.synced)
        assertEquals(0, exact.progress.state!!.visited)
    }

    @Test fun maximumStableRegistrationIndexFitsTheDefaultScanBudget() = runBlocking {
        val routes = (1..PushRegistrationStore.MAX_ENTRIES).map { "%064x".format(it) }
        val f = Fixture(*routes.toTypedArray(), limit = PushWakeRecoveryScan.MAX_ROUTES_PER_PASS)
        var attempts = 0
        var result: PushWakeDrain.SyncOutcome
        do {
            result = f.scan().run(scope, signal)
            attempts++
        } while (result == PushWakeDrain.SyncOutcome.RETRY && attempts <= routes.size)
        assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, result)
        assertEquals(routes, f.synced)
        assertEquals(PushRegistrationStore.MAX_ENTRIES / PushWakeRecoveryScan.MAX_ROUTES_PER_PASS, attempts)
        assertEquals(0, f.progress.state!!.visited)
    }

    @Test fun insertionBeforeCursorIsPickedUpByTheNextFullPass() = runBlocking {
        val f = Fixture(b, c, limit = 1)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, signal))
        f.routes += a
        assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, f.scan().run(scope, signal))
        assertEquals(listOf(b, c), f.synced)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run(scope, signal))
        assertEquals(listOf(b, c, a), f.synced)
    }

    @Test fun emptyIndexCompletesOnlyAfterDurableResetAndInvalidScopeNeverReads() = runBlocking {
        val f = Fixture(limit = 1)
        assertEquals(PushWakeDrain.SyncOutcome.RETRY, f.scan().run("invalid", signal))
        assertEquals(0, f.pages)
        assertEquals(null, f.progress.state)
        assertEquals(PushWakeDrain.SyncOutcome.COMPLETED_WINDOW, f.scan().run(scope, signal))
        assertEquals(1, f.pages)
        assertEquals(null, f.progress.state!!.after)
        assertFalse(f.progress.state!!.toString().contains(scope))
    }
}
