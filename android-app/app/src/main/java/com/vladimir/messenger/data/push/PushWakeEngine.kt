package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException
import java.util.logging.Logger

/**
 * Production assembly of one bounded wake pass: the durable inbox
 * ([PushWakeDrain]), the trust inspector that re-derives a route from the live
 * pin ([PushWakeInspector]), the re-deriving full pass for a generic marker
 * ([PushFullScan]) and the real receive window ([PushMailboxReceiver]).
 *
 * This is the object the WorkManager workers call. It still refuses to do
 * anything unless the caller has already checked
 * [PushRuntimeConfig.gateOpen] *and* an approved endpoint exists; with the
 * shipped empty host list [create] returns null and the worker exits without a
 * single journal or network access.
 *
 * Honest semantics preserved from the components:
 *  - `COMPLETED_WINDOW` requires a window that actually finished; every other
 *    case keeps the lease for recovery;
 *  - a completed window does not mean a message arrived, and a stored row is not
 *    a notification the user saw;
 *  - cancellation propagates and leaves claims leased for the next pass.
 */
internal class PushWakeEngine(
    private val dispatcher: PushWakeDispatcher,
    private val inbox: PushWakeQueue,
    /** Durable generic marker; [PushWakeQueue] is read/claim-only by design. */
    private val enqueueRecovery: (Long) -> PushWakeInbox.EnqueueResult,
    private val inspector: PushWakeInspector,
    private val sync: PushWakeSync,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val recordFailure: () -> Unit = {},
) {
    /** One bounded pass; map [PushWakeRunLoop.Result.RETRY] to a durable retry. */
    suspend fun runOnce(): PushWakeRunLoop.Result {
        val drain = PushWakeDrain(
            inbox = inbox,
            nowMs = nowMs,
            inspect = { route -> inspector.inspect(route) },
            sync = { batch -> sync.sync(batch) },
            recordFailure = recordFailure,
        )
        return PushWakeRunLoop(
            dispatcher = dispatcher,
            drain = { drain.run() },
            nowMs = nowMs,
            recordFailure = recordFailure,
        ).run()
    }

    /**
     * Periodic repair: ensure a durable generic marker, then drain directly even
     * if the in-memory gate believes another worker is scheduled.
     */
    suspend fun runRecovery(admission: suspend () -> PushWakePeriodicRecovery.Admission): PushWakePeriodicRecovery.Result =
        PushWakePeriodicRecovery(
            admission = admission,
            ensureMarker = { now -> enqueueRecovery(now) },
            runDrain = { runOnce() },
            nowMs = nowMs,
            recordFailure = recordFailure,
        ).run()

    companion object {
        /**
         * Builds the engine for an approved endpoint, or returns null while the
         * feature is disabled in this build. Never throws: a diagnostics
         * failure of the locator must not crash a worker.
         */
        fun create(context: android.content.Context): PushWakeEngine? {
            return try {
                val app = context.applicationContext
                if (!PushServiceLocator.enabled(app)) {
                    null
                } else {
                    val shared = PushServiceLocator.shared(app)
                    val receiver = PushServiceLocator.receiver(app) ?: return null
                    val registrations = shared.journals.registrations
                    val sync = PushWakeSync(
                        gate = { PushServiceLocator.enabled(app) },
                        inspect = { route -> shared.inspector.inspect(route) },
                        receive = { route ->
                            // A null route is the generic marker: the full scan below
                            // handles it, so this branch is never taken for it.
                            val peer = route?.let { hint ->
                                when (val found = registrations.findRoute(hint)) {
                                    is PushRegistrationStore.RouteResult.Found -> found.peerId
                                    else -> null
                                }
                            }
                            if (route == null || peer == null) {
                                PushReceiveWindow.Outcome.RETRY
                            } else {
                                receiver.receive(route, peer)
                            }
                        },
                        fullScan = {
                            PushServiceLocator.fullScan(app)?.run() == PushFullScan.Outcome.COMPLETED
                        },
                        recordFailure = { PushServiceLocator.log("Wake sync failure") },
                    )
                    PushWakeEngine(
                        dispatcher = shared.dispatcher,
                        inbox = shared.journals.inbox,
                        inspector = shared.inspector,
                        enqueueRecovery = { now -> shared.journals.inbox.enqueueRecovery(now) },
                        sync = sync,
                        recordFailure = {
                            Logger.getLogger("PushWakeEngine").warning("Wake pass diagnostics")
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Logger.getLogger("PushWakeEngine").warning("Wake engine construction failed")
                null
            }
        }
    }
}
