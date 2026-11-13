package com.vladimir.messenger.data.push

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite/Keystore; index verification and receive windows are fakes. No FCM or delivery. */
@RunWith(AndroidJUnit4::class)
class PushWakeRecoveryCheckpointInstrumentedTest {
    private lateinit var context: Context
    private lateinit var checkpointFile: File
    private lateinit var journalFile: File
    private lateinit var inboxFile: File
    private lateinit var checkpointAlias: String
    private lateinit var journalAlias: String
    private val checkpoints = mutableListOf<PushWakeRecoveryCheckpointStore>()
    private val journals = mutableListOf<PushRegistrationStore>()
    private val inboxes = mutableListOf<PushWakeInbox>()
    private val scope = "a".repeat(64)
    private val newScope = "b".repeat(64)
    private val a = "1".repeat(64)
    private val b = "2".repeat(64)
    private val c = "3".repeat(64)
    private val d = "4".repeat(64)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        checkpointFile = File(context.noBackupFilesDir, "push-recovery-test-$id.sqlite")
        journalFile = File(context.noBackupFilesDir, "push-recovery-journal-test-$id.sqlite")
        inboxFile = File(context.noBackupFilesDir, "push-recovery-inbox-test-$id.sqlite")
        checkpointAlias = "apu-push-recovery-test-$id"
        journalAlias = "apu-push-recovery-journal-test-$id"
    }

    @After fun tearDown() {
        checkpoints.forEach { it.close() }
        journals.forEach { it.close() }
        inboxes.forEach { it.close() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(checkpointAlias); deleteEntry(journalAlias) }
        listOf(checkpointFile, journalFile, inboxFile).forEach { file ->
            context.deleteDatabase(file.absolutePath)
            listOf("-wal", "-shm", "-journal").forEach { File(file.absolutePath + it).delete() }
        }
    }

    private fun store(random: SecureRandom = SecureRandom()) =
        PushWakeRecoveryCheckpointStore(context, checkpointFile, checkpointAlias, random).also(checkpoints::add)
    private fun ready(result: PushWakeRecoveryProgress.Result) = (result as PushWakeRecoveryProgress.Result.Ready).snapshot
    private fun load(store: PushWakeRecoveryCheckpointStore, which: String = scope, revision: Long = 1L) =
        ready(store.load(which, revision) { true })
    private fun advance(store: PushWakeRecoveryCheckpointStore, before: PushWakeRecoveryProgress.Snapshot, route: String, failed: Boolean = false) =
        ready(store.advance(before, route, failed) { true })

    @Test fun encryptedProgressAndFailureBitSurviveReopen() {
        val first = store()
        val start = load(first)
        val saved = advance(first, start, b, true)
        first.close()
        val fields = SQLiteDatabase.openDatabase(checkpointFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT token, payload FROM recovery_progress", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0) to cursor.getBlob(1)
            }
        }
        val encrypted = fields.second.toString(Charsets.ISO_8859_1)
        assertEquals(saved.token, fields.first)
        assertFalse(encrypted.contains(scope))
        assertFalse(encrypted.contains(b))
        assertFalse(encrypted.contains("APUWREC1"))
        assertTrue(checkpointFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        val reopened = store()
        val state = load(reopened)
        assertEquals(b, state.after)
        assertTrue(state.hadFailure)
        assertEquals(1, state.visited)
        assertEquals(saved.token, state.token)
        assertFalse(state.toString().contains(scope))
        val later = advance(reopened, state, c)
        assertTrue(later.hadFailure)
        assertEquals(2, later.visited)
    }

    @Test fun scopeChangesResetCursorAndInvalidateOlderWritersAcrossInstances() {
        val first = store()
        val old = advance(first, load(first), a, true)
        val second = store()
        val changed = load(second, newScope)
        assertEquals(null, changed.after)
        assertFalse(changed.hadFailure)
        assertFalse(changed.token == old.token)
        assertEquals(PushWakeRecoveryProgress.Result.Stale, first.advance(old, b, false) { true })
        assertEquals(PushWakeRecoveryProgress.Result.Stale, first.reset(old) { true })
        assertEquals(changed.token, load(second, newScope).token)
        val returned = load(second, scope)
        assertEquals(null, returned.after)
        assertFalse(returned.hadFailure)
        assertFalse(returned.token == old.token)
        assertEquals(PushWakeRecoveryProgress.Result.Stale, first.advance(old, b, false) { true })
    }

    @Test fun progressIsMonotonicAndOnlyTheCurrentSnapshotMayReset() {
        val checkpoint = store()
        val start = load(checkpoint)
        val first = advance(checkpoint, start, b, true)
        assertEquals(PushWakeRecoveryProgress.Result.Unavailable, checkpoint.advance(first, a, false) { true })
        assertEquals(PushWakeRecoveryProgress.Result.Unavailable, checkpoint.advance(first, b, false) { true })
        assertEquals(PushWakeRecoveryProgress.Result.Unavailable, checkpoint.advance(first, "invalid", false) { true })
        assertEquals(PushWakeRecoveryProgress.Result.Stale, checkpoint.reset(start) { true })
        val forged = PushWakeRecoveryProgress.Snapshot(scope, first.startRevision, b, false, first.visited, first.token)
        assertEquals(PushWakeRecoveryProgress.Result.Stale, checkpoint.advance(forged, c, false) { true })
        val forgedCount = PushWakeRecoveryProgress.Snapshot(scope, first.startRevision, b, true, first.visited + 1, first.token)
        assertEquals(PushWakeRecoveryProgress.Result.Stale, checkpoint.advance(forgedCount, c, false) { true })
        val next = advance(checkpoint, first, c)
        assertTrue(next.hadFailure)
        val reset = ready(checkpoint.reset(next) { true })
        assertEquals(null, reset.after)
        assertFalse(reset.hadFailure)
        assertFalse(reset.token == next.token)
        assertEquals(PushWakeRecoveryProgress.Result.Stale, checkpoint.advance(next, d, false) { true })
    }

    @Test fun revisionIsAuthenticatedAndNewerWakeDoesNotInterruptAnUnfinishedPass() {
        val checkpoint = store()
        val first = advance(checkpoint, load(checkpoint), a)
        val continued = load(checkpoint, revision = 3L)
        assertEquals(first.token, continued.token)
        assertEquals(1L, continued.startRevision)
        assertEquals(a, continued.after)
        val forged = PushWakeRecoveryProgress.Snapshot(scope, 2L, a, false, first.visited, first.token)
        assertEquals(PushWakeRecoveryProgress.Result.Stale, checkpoint.advance(forged, b, false) { true })
        assertEquals(PushWakeRecoveryProgress.Result.Unavailable, checkpoint.load(scope, 0) { true })
        val reset = ready(checkpoint.reset(continued) { true })
        val next = load(checkpoint, revision = 3L)
        assertEquals(3L, next.startRevision)
        assertEquals(null, next.after)
        assertFalse(next.token == reset.token)
        assertEquals(PushWakeRecoveryProgress.Result.Stale, checkpoint.load(scope, 2L) { true })
        assertEquals(PushWakeRecoveryProgress.Result.Stale, checkpoint.advance(reset, b, false) { true })
        checkpoint.close()
        val reopened = store()
        assertEquals(3L, load(reopened, revision = 3L).startRevision)
        val max = load(reopened, newScope, Long.MAX_VALUE)
        val advanced = advance(reopened, max, b)
        reopened.close()
        assertEquals(Long.MAX_VALUE, load(store(), newScope, Long.MAX_VALUE).startRevision)
        assertEquals(b, advanced.after)
    }

    @Test fun currentCheckAfterWriteRollsBackAdvancesResetsAndScopeChanges() {
        val checkpoint = store()
        val start = load(checkpoint)
        var checks = 0
        assertEquals(PushWakeRecoveryProgress.Result.Stale, checkpoint.advance(start, a, false) { ++checks < 3 })
        assertEquals(3, checks)
        assertEquals(start.token, load(checkpoint).token)
        val saved = advance(checkpoint, start, a, true)
        checks = 0
        assertEquals(PushWakeRecoveryProgress.Result.Stale, checkpoint.reset(saved) { ++checks < 3 })
        assertEquals(3, checks)
        assertEquals(saved.token, load(checkpoint).token)
        checks = 0
        assertEquals(PushWakeRecoveryProgress.Result.Stale, checkpoint.load(newScope, 1L) { ++checks < 3 })
        assertEquals(3, checks)
        assertEquals(saved.token, load(checkpoint).token)
        assertTrue(load(checkpoint).hadFailure)
    }

    @Test fun concurrentWritersHaveExactlyOneWinnerAndStaleTokensStayStale() {
        val stores = listOf(store(), store())
        val initial = load(stores[0])
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..8).map { i -> pool.submit(Callable {
                assertTrue(gate.await(10, TimeUnit.SECONDS))
                stores[i % 2].advance(initial, "%064x".format(i), false) { true }
            }) }
            gate.countDown()
            val results = tasks.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is PushWakeRecoveryProgress.Result.Ready })
            assertEquals(7, results.count { it == PushWakeRecoveryProgress.Result.Stale })
            val winner = (results.single { it is PushWakeRecoveryProgress.Result.Ready } as PushWakeRecoveryProgress.Result.Ready).snapshot
            assertEquals(winner.token, load(stores[1]).token)
            assertEquals(PushWakeRecoveryProgress.Result.Stale, stores[0].reset(initial) { true })
        } finally {
            gate.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun malformedCiphertextTokenAndMissingKeyNeverSilentlyResetProgress() {
        val first = store()
        advance(first, load(first), b, true)
        first.close()
        SQLiteDatabase.openDatabase(checkpointFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE recovery_progress SET token=?", arrayOf("f".repeat(64)))
        }
        val badToken = store()
        assertEquals(PushWakeRecoveryProgress.Result.Unavailable, badToken.load(scope, 1L) { true })
        assertEquals(PushWakeRecoveryProgress.Result.Unavailable, badToken.load(newScope, 1L) { true })
        badToken.close()
        context.deleteDatabase(checkpointFile.absolutePath)
        val second = store()
        advance(second, load(second), b, true)
        second.close()
        SQLiteDatabase.openDatabase(checkpointFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE recovery_progress SET payload=zeroblob(64)")
        }
        val badCiphertext = store()
        assertEquals(PushWakeRecoveryProgress.Result.Unavailable, badCiphertext.load(scope, 1L) { true })
        badCiphertext.close()
        context.deleteDatabase(checkpointFile.absolutePath)
        val third = store()
        advance(third, load(third), b, true)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(checkpointAlias) }
        assertEquals(PushWakeRecoveryProgress.Result.Unavailable, third.load(scope, 1L) { true })
        assertEquals(PushWakeRecoveryProgress.Result.Unavailable, third.load(newScope, 1L) { true })
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(checkpointAlias))
    }

    @Test fun malformedTokenAndOversizedOrWrongTypePayloadFailClosed() {
        val changes = listOf(
            "UPDATE recovery_progress SET token = token || char(0) || 'x'",
            "UPDATE recovery_progress SET token = 'bad'",
            "UPDATE recovery_progress SET payload = 'not a blob'",
            "UPDATE recovery_progress SET payload = zeroblob(29)",
            "UPDATE recovery_progress SET payload = zeroblob(321)",
            "UPDATE recovery_progress SET payload = zeroblob(1048576)",
        )
        for (sql in changes) {
            val first = store()
            advance(first, load(first), b, true)
            first.close()
            SQLiteDatabase.openDatabase(checkpointFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(sql) }
            val bad = store()
            assertEquals(PushWakeRecoveryProgress.Result.Unavailable, bad.load(scope, 1L) { true })
            assertEquals(PushWakeRecoveryProgress.Result.Unavailable, bad.load(newScope, 1L) { true })
            bad.close()
            context.deleteDatabase(checkpointFile.absolutePath)
        }
    }

    @Test fun missingOrRepeatedEntropyCannotAdvanceCheckpoint() {
        val zeros = object : SecureRandom() { override fun nextBytes(bytes: ByteArray) { bytes.fill(0) } }
        val noEntropy = store(zeros)
        assertEquals(PushWakeRecoveryProgress.Result.Unavailable, noEntropy.load(scope, 1L) { true })
        noEntropy.close()
        val repeated = object : SecureRandom() { override fun nextBytes(bytes: ByteArray) { bytes.fill(1) } }
        val fixed = store(repeated)
        val start = load(fixed)
        assertEquals(PushWakeRecoveryProgress.Result.Unavailable, fixed.advance(start, a, false) { true })
        assertEquals(start.token, load(fixed).token)
        assertEquals(null, load(fixed).after)
    }

    @Test fun cancellationAfterCheckpointCommitKeepsTheWakeAndResumesTheNextRoute() = runBlocking {
        val checkpoint = store()
        val queue = PushWakeInbox(context, inboxFile).also(inboxes::add)
        var now = 1_700_000_000_000L
        val seen = mutableListOf<String>()
        var derivedScope = ""
        var returned = false
        fun page(after: String?, limit: Int): PushRegistrationStore.RecoveryPage {
            assertEquals(1, limit)
            return when (after) {
                null -> PushRegistrationStore.RecoveryPage.Ready(listOf(a), a)
                a -> PushRegistrationStore.RecoveryPage.Ready(listOf(b), null)
                else -> PushRegistrationStore.RecoveryPage.Ready(emptyList(), null)
            }
        }
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, queue.ensurePeriodicRecovery(now))
        val job = launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]!!
            val progress = object : PushWakeRecoveryProgress by checkpoint {
                override fun advance(expected: PushWakeRecoveryProgress.Snapshot, after: String, failed: Boolean,
                    current: () -> Boolean): PushWakeRecoveryProgress.Result =
                    checkpoint.advance(expected, after, failed, current).also { self.cancel() }
            }
            val scan = PushWakeRecoveryScan(progress, ::page, { PushWakeDrain.Inspection.ALLOW }, {
                seen += it
                PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
            }, { true }, { error("unexpected diagnostic") }, 1)
            PushWakeDrain(queue, { now }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
                batch.items.map { item ->
                    val signal = checkNotNull(item.recoverySignal)
                    derivedScope = PushWakeRecoveryScan.checkpointScope(scope, signal.incarnation)
                    item.result(scan.run(scope, signal))
                }
            }, { error("unexpected diagnostic") }).run()
            returned = true
        }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(listOf(a), seen)
        assertEquals(a, load(checkpoint, derivedScope).after)
        assertEquals(now + PushWakeInbox.LEASE_MS, (queue.pending(now) as PushWakeInbox.PendingResult.Pending).readyAtMs)
        checkpoint.close(); queue.close()
        val reopened = store()
        val queueAgain = PushWakeInbox(context, inboxFile).also(inboxes::add)
        now += PushWakeInbox.LEASE_MS
        val scanAgain = PushWakeRecoveryScan(reopened, ::page, { PushWakeDrain.Inspection.ALLOW }, {
            seen += it
            PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
        }, { true }, { error("unexpected diagnostic") }, 1)
        val result = PushWakeDrain(queueAgain, { now }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
            batch.items.map { item ->
                val signal = checkNotNull(item.recoverySignal)
                derivedScope = PushWakeRecoveryScan.checkpointScope(scope, signal.incarnation)
                item.result(scanAgain.run(scope, signal))
            }
        }, { error("unexpected diagnostic") }).run()
        assertEquals(PushWakeDrain.Result.Empty, result)
        assertEquals(listOf(a, b), seen)
        assertEquals(null, load(reopened, derivedScope).after)
    }

    @Test fun cancellationAfterFinalResetReplaysPassAndKeepsWake() = runBlocking {
        val checkpoint = store()
        val queue = PushWakeInbox(context, inboxFile).also(inboxes::add)
        var now = 1_700_000_000_000L
        val seen = mutableListOf<String>()
        var derivedScope = ""
        var returned = false
        fun page(after: String?, limit: Int): PushRegistrationStore.RecoveryPage {
            assertEquals(1, limit)
            return if (after == null) PushRegistrationStore.RecoveryPage.Ready(listOf(a), null)
            else PushRegistrationStore.RecoveryPage.Ready(emptyList(), null)
        }
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, queue.ensurePeriodicRecovery(now))
        val job = launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]!!
            val progress = object : PushWakeRecoveryProgress by checkpoint {
                override fun reset(expected: PushWakeRecoveryProgress.Snapshot,
                    current: () -> Boolean): PushWakeRecoveryProgress.Result =
                    checkpoint.reset(expected, current).also { self.cancel() }
            }
            val scan = PushWakeRecoveryScan(progress, ::page, { PushWakeDrain.Inspection.ALLOW }, {
                seen += it
                PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
            }, { true }, { error("unexpected diagnostic") }, 1)
            PushWakeDrain(queue, { now }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
                batch.items.map { item ->
                    val signal = checkNotNull(item.recoverySignal)
                    derivedScope = PushWakeRecoveryScan.checkpointScope(scope, signal.incarnation)
                    item.result(scan.run(scope, signal))
                }
            }, { error("unexpected diagnostic") }).run()
            returned = true
        }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(listOf(a), seen)
        assertEquals(null, load(checkpoint, derivedScope).after)
        assertEquals(now + PushWakeInbox.LEASE_MS, (queue.pending(now) as PushWakeInbox.PendingResult.Pending).readyAtMs)
        checkpoint.close(); queue.close()
        val reopened = store()
        val queueAgain = PushWakeInbox(context, inboxFile).also(inboxes::add)
        now += PushWakeInbox.LEASE_MS
        val scanAgain = PushWakeRecoveryScan(reopened, ::page, { PushWakeDrain.Inspection.ALLOW }, {
            seen += it
            PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
        }, { true }, { error("unexpected diagnostic") }, 1)
        val result = PushWakeDrain(queueAgain, { now }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
            batch.items.map { item ->
                val signal = checkNotNull(item.recoverySignal)
                derivedScope = PushWakeRecoveryScan.checkpointScope(scope, signal.incarnation)
                item.result(scanAgain.run(scope, signal))
            }
        }, { error("unexpected diagnostic") }).run()
        assertEquals(PushWakeDrain.Result.Empty, result)
        assertEquals(listOf(a, a), seen) // The final reset committed before cancellation.
    }

    @Test fun newerRecoveryBetweenLeasesNeedsAFullFreshPassAcrossReopen() = runBlocking {
        var checkpoint = store()
        var queue = PushWakeInbox(context, inboxFile).also(inboxes::add)
        var now = 1_700_000_000_000L
        val seen = mutableListOf<String>()
        val revisions = mutableListOf<Long>()
        val incarnations = mutableListOf<String>()
        fun page(after: String?, limit: Int): PushRegistrationStore.RecoveryPage {
            assertEquals(1, limit)
            val remaining = listOf(a, b, c).filter { after == null || it > after }
            return PushRegistrationStore.RecoveryPage.Ready(remaining.take(1), if (remaining.size > 1) remaining.first() else null)
        }
        suspend fun pass(): PushWakeDrain.Result {
            val scan = PushWakeRecoveryScan(checkpoint, ::page, { PushWakeDrain.Inspection.ALLOW }, {
                seen += it
                PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
            }, { true }, { error("unexpected diagnostic") }, maxRoutes = 1)
            return PushWakeDrain(queue, { now }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
                batch.items.map { item ->
                    val signal = checkNotNull(item.recoverySignal)
                    revisions += signal.revision
                    incarnations += signal.incarnation
                    item.result(scan.run(scope, signal))
                }
            }, { error("unexpected diagnostic") }).run()
        }
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, queue.enqueueRecovery(now))
        assertTrue(pass() is PushWakeDrain.Result.Pending)
        assertEquals(listOf(a), seen)
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, queue.enqueueRecovery(now + 1))
        checkpoint.close(); queue.close()
        checkpoint = store(); queue = PushWakeInbox(context, inboxFile).also(inboxes::add)
        repeat(4) { now += PushWakeInbox.RETRY_MS; assertTrue(pass() is PushWakeDrain.Result.Pending) }
        now += PushWakeInbox.RETRY_MS
        assertEquals(PushWakeDrain.Result.Empty, pass())
        assertEquals(listOf(a, b, c, a, b, c), seen)
        assertEquals(listOf(1L, 2L, 2L, 2L, 2L, 2L), revisions)
        assertEquals(1, incarnations.distinct().size)
        assertEquals(PushWakeInbox.PendingResult.Empty, queue.pending(now))
    }

    @Test fun recreatedInboxCannotResumeAnOldMarkersPartialCheckpoint() = runBlocking {
        val checkpoint = store()
        var queue = PushWakeInbox(context, inboxFile).also(inboxes::add)
        var now = 1_700_000_000_000L
        val seen = mutableListOf<String>()
        val incarnations = mutableListOf<String>()
        fun page(after: String?, limit: Int): PushRegistrationStore.RecoveryPage {
            assertEquals(1, limit)
            return if (after == null) PushRegistrationStore.RecoveryPage.Ready(listOf(a), a)
            else PushRegistrationStore.RecoveryPage.Ready(listOf(b), null)
        }
        suspend fun pass(): PushWakeDrain.Result {
            val scan = PushWakeRecoveryScan(checkpoint, ::page, { PushWakeDrain.Inspection.ALLOW }, {
                seen += it
                PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
            }, { true }, { error("unexpected diagnostic") }, maxRoutes = 1)
            return PushWakeDrain(queue, { now }, { PushWakeDrain.Inspection.ALLOW }, { batch ->
                batch.items.map { item ->
                    val signal = checkNotNull(item.recoverySignal)
                    assertEquals(1L, signal.revision)
                    incarnations += signal.incarnation
                    item.result(scan.run(scope, signal))
                }
            }, { error("unexpected diagnostic") }).run()
        }
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, queue.enqueueRecovery(now))
        assertTrue(pass() is PushWakeDrain.Result.Pending)
        queue.close()
        assertTrue(context.deleteDatabase(inboxFile.absolutePath))
        queue = PushWakeInbox(context, inboxFile).also(inboxes::add)
        now++
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, queue.enqueueRecovery(now))
        assertTrue(pass() is PushWakeDrain.Result.Pending)
        assertEquals(listOf(a, a), seen)
        assertEquals(2, incarnations.distinct().size)
        now += PushWakeInbox.RETRY_MS
        assertEquals(PushWakeDrain.Result.Empty, pass())
        assertEquals(listOf(a, a, b), seen)
    }

    @Test fun realIndexInboxAndCheckpointResumeAcrossReopenWithoutLosingFailedRoute() = runBlocking {
        val localId = "pk_" + "a".repeat(32)
        val localBinding = byteArrayOf(1, 0)
        fun peerId(i: Int) = "pk_" + "%032x".format(i)
        fun journal() = PushRegistrationStore(context, journalFile, journalAlias, bindingNodeId = { binding ->
            when (binding[0].toInt()) {
                1 -> localId
                2 -> peerId(binding[1].toInt())
                else -> error("invalid test binding")
            }
        }).also(journals::add)
        fun inbox() = PushWakeInbox(context, inboxFile).also(inboxes::add)
        var index = journal()
        listOf(a, b, c, d).forEachIndexed { i, route ->
            val participants = PushRegistrationStore.Participants(localId, peerId(i + 1), localBinding, byteArrayOf(2, (i + 1).toByte()))
            val reserved = (index.reserve(participants, 1000L + i) as PushRegistrationStore.Result.Ready).state
            assertTrue(index.registered(participants, reserved.sequence, "f".repeat(64), 2000L + i, route) is PushRegistrationStore.Result.Ready)
        }
        var checkpoint = store()
        var queue = inbox()
        var now = 1_700_000_000_000L
        val seen = mutableListOf<String>()
        var derivedScope = ""
        var failFirst = true
        fun scan() = PushWakeRecoveryScan(checkpoint, index::recoveryPage, { PushWakeDrain.Inspection.ALLOW }, { route ->
            seen += route
            if (route == a && failFirst) PushWakeDrain.SyncOutcome.RETRY else PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
        }, { true }, { error("unexpected diagnostic") }, maxRoutes = 2)
        suspend fun pass(): PushWakeDrain.Result = PushWakeDrain(queue, { now }, { route ->
            assertEquals(null, route)
            PushWakeDrain.Inspection.ALLOW
        }, { batch -> batch.items.map { item ->
            val signal = checkNotNull(item.recoverySignal)
            derivedScope = PushWakeRecoveryScan.checkpointScope(scope, signal.incarnation)
            item.result(scan().run(scope, signal))
        } }, { error("unexpected diagnostic") }).run()
        assertEquals(PushWakeInbox.EnqueueResult.RECOVERY_QUEUED, queue.ensurePeriodicRecovery(now))
        assertTrue(pass() is PushWakeDrain.Result.Pending)
        assertEquals(listOf(a, b), seen)
        assertEquals(b, load(checkpoint, derivedScope).after)
        assertTrue(load(checkpoint, derivedScope).hadFailure)
        assertEquals(2, load(checkpoint, derivedScope).visited)
        checkpoint.close(); index.close(); queue.close()
        checkpoint = store(); index = journal(); queue = inbox()
        assertEquals(2, load(checkpoint, derivedScope).visited)
        now += PushWakeInbox.RETRY_MS
        assertTrue(pass() is PushWakeDrain.Result.Pending)
        assertEquals(listOf(a, b, c, d), seen)
        assertEquals(null, load(checkpoint, derivedScope).after)
        assertEquals(0, load(checkpoint, derivedScope).visited)
        failFirst = false
        now += PushWakeInbox.RETRY_MS
        assertTrue(pass() is PushWakeDrain.Result.Pending)
        now += PushWakeInbox.RETRY_MS
        assertEquals(PushWakeDrain.Result.Empty, pass())
        assertEquals(listOf(a, b, c, d, a, b, c, d), seen)
        assertEquals(PushWakeInbox.PendingResult.Empty, queue.pending(now))
    }
}
