package com.vladimir.messenger.data.push

import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.logging.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Bounded traversal of the local registration index for a route-free recovery
 * signal. This does not itself start WorkManager, FCM, network, or notifications.
 * A runtime may use it for the null item of [PushWakeDrain.SyncBatch], but only
 * once [syncRoute] performs a real, bounded, authenticated receive window and
 * durably handles its messages and notification work. Merely scheduling a
 * window is not success. Every callback must respect cancellation and must not
 * detach effects; callers must also impose an actual overall time limit.
 *
 * A scan is scoped to the caller's lifecycle and to the exact claimed marker's
 * random incarnation. A newer wake revision does not interrupt a partial pass:
 * finish its remaining routes, then retry from the beginning for the newer
 * revision. Only a full pass begun at the claimed revision can consume it;
 * inbox completion also checks for a revision changed during the final call.
 * A newly created marker never inherits an old marker's unfinished cursor.
 *
 * The cursor, count and failure bit are durably advanced after each route, so a
 * crash before an advance replays that route. A crash after the final reset but
 * before wake completion may replay the entire pass. A failed route does not starve
 * later routes: its failure is carried through checkpoints and the whole scan is
 * retried from the beginning after reaching the end. `COMPLETED_WINDOW` is
 * returned only when the complete pass had no failures. If a registration is
 * inserted behind the cursor while scanning, a later periodic full pass must
 * visit it. A persisted total-route budget forces a retry from the beginning
 * even if registrations keep changing. Database deletion/rollback and platform
 * scheduling delays are not detected or bounded here.
 *
 * Index entries are hints, not permission. [inspect] must re-check opt-in,
 * identity, active journal, existing pin and derived route. [syncRoute] must
 * revalidate and coordinate those with each actual effect; preflight alone is
 * not ABA-safe. [current], the lifecycle scope and the marker identity must come
 * from the current installation, not from FCM. Identity/restore/pin/registration/opt-out mutations must be
 * coordinated with the effects by the enabled runtime. Concurrent or replayed
 * passes may perform a route twice, so processing must be idempotent. Neither a
 * completed pass nor a provider response proves message delivery.
 */
internal class PushWakeRecoveryScan(
    private val progress: PushWakeRecoveryProgress,
    private val page: (String?, Int) -> PushRegistrationStore.RecoveryPage,
    private val inspect: suspend (String) -> PushWakeDrain.Inspection,
    private val syncRoute: suspend (String) -> PushWakeDrain.SyncOutcome,
    private val current: () -> Boolean,
    private val recordFailure: () -> Unit,
    private val maxRoutes: Int = MAX_ROUTES_PER_PASS,
    private val maxScanRoutes: Int = PushWakeRecoveryProgress.MAX_SCAN_ROUTES,
) {
    init {
        require(maxRoutes in 1..MAX_ROUTES_PER_PASS)
        require(maxScanRoutes in 1..PushWakeRecoveryProgress.MAX_SCAN_ROUTES)
    }

    suspend fun run(lifecycleScope: String, signal: PushWakeDrain.RecoverySignal?): PushWakeDrain.SyncOutcome = try {
        currentCoroutineContext().ensureActive()
        if (!isHash(lifecycleScope) || signal == null || !signal.valid()) PushWakeDrain.SyncOutcome.RETRY
        else scan(checkpointScope(lifecycleScope, signal.incarnation), signal.revision)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // No routes, identity, callback data, or exception details in diagnostics.
        diag()
        PushWakeDrain.SyncOutcome.RETRY
    }

    private suspend fun scan(scope: String, revision: Long): PushWakeDrain.SyncOutcome {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (!live()) return PushWakeDrain.SyncOutcome.RETRY
        val loaded = progress.load(scope, revision, current)
        context.ensureActive()
        var snapshot = when (loaded) {
            is PushWakeRecoveryProgress.Result.Ready -> loaded.snapshot
            PushWakeRecoveryProgress.Result.Stale, PushWakeRecoveryProgress.Result.Unavailable ->
                return PushWakeDrain.SyncOutcome.RETRY
        }
        if (!live() || !valid(snapshot, scope) || snapshot.startRevision > revision ||
            snapshot.after == null && snapshot.startRevision != revision
        ) return PushWakeDrain.SyncOutcome.RETRY
        repeat(maxRoutes) {
            context.ensureActive()
            if (!live()) return PushWakeDrain.SyncOutcome.RETRY
            if (snapshot.visited >= maxScanRoutes) return finish(snapshot, scope, revision, forceRetry = true)
            // A one-item page permits forward progress even with one bad route
            // and makes each durable checkpoint correspond to one effect.
            val result = page(snapshot.after, 1)
            context.ensureActive()
            if (!live()) return PushWakeDrain.SyncOutcome.RETRY
            val ready = when (result) {
                is PushRegistrationStore.RecoveryPage.Ready -> result
                PushRegistrationStore.RecoveryPage.Invalid, PushRegistrationStore.RecoveryPage.Unavailable ->
                    return PushWakeDrain.SyncOutcome.RETRY
            }
            if (ready.routes.size > 1) return PushWakeDrain.SyncOutcome.RETRY
            val route = ready.routes.singleOrNull()
            if (route == null) {
                if (ready.next != null) return PushWakeDrain.SyncOutcome.RETRY
                return finish(snapshot, scope, revision)
            }
            if (!isHash(route) || snapshot.after != null && route <= snapshot.after!! ||
                ready.next != null && ready.next != route
            ) return PushWakeDrain.SyncOutcome.RETRY

            val inspection = try {
                inspect(route)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                diag()
                PushWakeDrain.Inspection.RETRY
            }
            context.ensureActive()
            if (!live()) return PushWakeDrain.SyncOutcome.RETRY
            val failed = when (inspection) {
                PushWakeDrain.Inspection.DROP -> false
                PushWakeDrain.Inspection.RETRY -> true
                PushWakeDrain.Inspection.ALLOW -> {
                    val outcome = try {
                        syncRoute(route)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        diag()
                        PushWakeDrain.SyncOutcome.RETRY
                    }
                    context.ensureActive()
                    if (!live()) return PushWakeDrain.SyncOutcome.RETRY
                    outcome != PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
                }
            }
            context.ensureActive()
            if (!live()) return PushWakeDrain.SyncOutcome.RETRY
            val previous = snapshot
            val saved = progress.advance(previous, route, failed, current)
            context.ensureActive()
            val advanced = when (saved) {
                is PushWakeRecoveryProgress.Result.Ready -> saved.snapshot
                PushWakeRecoveryProgress.Result.Stale, PushWakeRecoveryProgress.Result.Unavailable ->
                    return PushWakeDrain.SyncOutcome.RETRY
            }
            if (!live() || !valid(advanced, scope) || advanced.token == previous.token ||
                advanced.startRevision != previous.startRevision || advanced.after != route ||
                advanced.hadFailure != (previous.hadFailure || failed) ||
                advanced.visited != previous.visited + 1
            ) return PushWakeDrain.SyncOutcome.RETRY
            snapshot = advanced
            if (ready.next == null) return finish(snapshot, scope, revision)
            if (snapshot.visited >= maxScanRoutes) return finish(snapshot, scope, revision, forceRetry = true)
        }
        return PushWakeDrain.SyncOutcome.RETRY
    }

    private suspend fun finish(
        snapshot: PushWakeRecoveryProgress.Snapshot, scope: String, revision: Long, forceRetry: Boolean = false,
    ): PushWakeDrain.SyncOutcome {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (!live()) return PushWakeDrain.SyncOutcome.RETRY
        val result = progress.reset(snapshot, current)
        context.ensureActive()
        val reset = when (result) {
            is PushWakeRecoveryProgress.Result.Ready -> result.snapshot
            PushWakeRecoveryProgress.Result.Stale, PushWakeRecoveryProgress.Result.Unavailable ->
                return PushWakeDrain.SyncOutcome.RETRY
        }
        if (!live() || !valid(reset, scope) || reset.token == snapshot.token ||
            reset.startRevision != snapshot.startRevision || reset.after != null || reset.hadFailure || reset.visited != 0
        ) return PushWakeDrain.SyncOutcome.RETRY
        return if (forceRetry || snapshot.hadFailure || snapshot.startRevision != revision) {
            PushWakeDrain.SyncOutcome.RETRY
        } else PushWakeDrain.SyncOutcome.COMPLETED_WINDOW
    }

    private suspend fun live(): Boolean {
        val context = currentCoroutineContext()
        context.ensureActive()
        val result = current()
        context.ensureActive()
        return result
    }

    private suspend fun diag() {
        currentCoroutineContext().ensureActive()
        try {
            recordFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            Logger.getLogger("PushWakeRecoveryScan").warning("Recovery diagnostics unavailable")
        }
        currentCoroutineContext().ensureActive()
    }

    private fun valid(snapshot: PushWakeRecoveryProgress.Snapshot, scope: String): Boolean =
        snapshot.scope == scope && snapshot.startRevision > 0 && (snapshot.after == null || isHash(snapshot.after)) &&
            isHash(snapshot.token) && snapshot.token.any { it != '0' } &&
            (if (snapshot.after == null) snapshot.visited == 0 && !snapshot.hadFailure
            else snapshot.visited in 1..PushWakeRecoveryProgress.MAX_SCAN_ROUTES)

    private fun isHash(value: String): Boolean = HASH.matches(value)

    companion object {
        const val MAX_ROUTES_PER_PASS = 16
        private val HASH = Regex("^[0-9a-f]{64}$")

        /** Domain-separated fixed-length scope; neither input comes from FCM. */
        internal fun checkpointScope(lifecycleScope: String, incarnation: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("apu-push-recovery-scope-v1\u0000$lifecycleScope\u0000$incarnation".toByteArray(Charsets.US_ASCII))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    }
}
