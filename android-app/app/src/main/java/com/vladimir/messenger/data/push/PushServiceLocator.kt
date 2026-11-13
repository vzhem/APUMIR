package com.vladimir.messenger.data.push

import android.content.Context
import com.vladimir.messenger.data.chatclear.ChatClearRegistry
import com.vladimir.messenger.data.file.FileExchangePeerStore
import com.vladimir.messenger.data.local.AppDatabase
import com.vladimir.messenger.worker.PushWakeRecoveryWorker
import com.vladimir.messenger.worker.PushWakeWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.CancellationException
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException as CoroutineCancellation

/**
 * Process-wide construction point for the remote-push runtime. It exists so the
 * Firebase service, both WorkManager workers and any future UI toggle share the
 * *same* journals, the same process-local scheduling gate and one bounded run
 * loop. Hilt already provides the database and the pin store, so a small entry
 * point is enough; no new module is added to the app graph.
 *
 * Nothing here opens the gate. [enabled] reads only immutable build facts
 * ([PushRuntimeConfig] via [PushFirebaseSignals]); an endpoint additionally needs
 * a reviewed host, which the shipped code does not have. With the gate closed
 * every factory returns null and the service/workers exit before touching a
 * journal, the network or the scheduler.
 *
 * Known limits, unchanged by this wiring:
 *  - identity, pin and restore changes are not serialized against a running
 *    receive window; the re-checks notice a change and abort, they do not
 *    prevent one;
 *  - the local exchange secret is copied for one window and wiped, but the
 *    Keystore provider and the Rust FFI may retain copies;
 *  - a completed window is not a delivered message, and a stored row is not a
 *    notification the user saw.
 */
internal object PushServiceLocator {

    /** Only collaborators that already exist in the Hilt graph. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun database(): AppDatabase
        fun pins(): FileExchangePeerStore
    }

    private val LOCK = Any()
    private var sharedInstance: Shared? = null
    private var receiverInstance: PushMailboxReceiver? = null
    private var fullScanInstance: PushFullScan? = null

    /** Everything that must be unique per process. */
    class Shared internal constructor(
        val journals: PushJournals.Entries,
        val dispatcher: PushWakeDispatcher,
        val inspector: PushWakeInspector,
        val resolver: PushTrustResolver,
        val workManager: PushWakeWorkManager,
        val crypto: PushWakeCrypto,
        internal val pins: FileExchangePeerStore,
        internal val database: AppDatabase,
    )

    private fun deps(context: Context): Deps =
        EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)

    /** A closed gate is not an outage: it is the state of every shipped build. */
    fun enabled(context: Context): Boolean = PushFirebaseSignals.enabled(context)

    fun endpoint(context: Context): PushEndpoint? = PushFirebaseSignals.endpoint(context)

    fun shared(context: Context): Shared = synchronized(LOCK) {
        val app = context.applicationContext
        sharedInstance ?: run {
            val journals = PushJournals.of(app)
            val pins = deps(app).pins()
            val crypto = PushWakeCrypto(recordFailure = { log("Push crypto failure") })
            val resolver = PushTrustResolver.of(app, pins, journals.registrations, crypto)
            val schedulingGate = PushWakeScheduleGate(
                enqueue = { onFailure -> enqueueDrain(app, onFailure) },
                recordFailure = { diag("Wake scheduling failed") },
            )
            val dispatcher = PushWakeDispatcher(
                gate = schedulingGate,
                lookup = { route ->
                    when (journals.registrations.findRoute(route)) {
                        is PushRegistrationStore.RouteResult.Found -> PushWakeIntake.Hint.FOUND
                        PushRegistrationStore.RouteResult.Missing -> PushWakeIntake.Hint.MISSING
                        PushRegistrationStore.RouteResult.Unavailable -> PushWakeIntake.Hint.UNAVAILABLE
                    }
                },
                enqueueRoute = { route, now -> journals.inbox.enqueueRoute(route, now) },
                enqueueRecovery = { now -> journals.inbox.enqueueRecovery(now) },
                pending = { now -> journals.inbox.pending(now) },
                recordFailure = { diag("Wake intake failure") },
            )
            val inspector = PushWakeInspector(
                journal = journals.registrations,
                pins = pins,
                localIdentity = { localIdentity(app) },
                // The generic marker is only a request for a bounded scan; it is
                // never authorization to touch a chat.
                recoveryAllowed = { PushWakeDrain.Inspection.ALLOW },
                crypto = crypto,
                recordFailure = { log("Wake inspection failure") },
            )
            Shared(
                journals = journals,
                dispatcher = dispatcher,
                inspector = inspector,
                resolver = resolver,
                workManager = PushWakeWorkManager(
                    androidx.work.WorkManager.getInstance(app),
                    PushWakeWorker::class.java,
                    PushWakeRecoveryWorker::class.java,
                ),
                crypto = crypto,
                pins = pins,
                database = deps(app).database(),
            ).also { sharedInstance = it }
        }
    }

    /** The real receive window, or null while no endpoint is approved. */
    fun receiver(context: Context): PushMailboxReceiver? = synchronized(LOCK) {
        val app = context.applicationContext
        receiverInstance ?: run {
            val endpoint = endpoint(app) ?: return null
            val shared = shared(app)
            val client = PushMailboxHttpClient(endpoint, recordFailure = { log("Mailbox transport failure") })
            val incoming = PushMailboxIncoming(
                pins = shared.pins,
                opener = PushMailboxMessageOpener(recordFailure = { log("Envelope open failure") }),
                store = PushMailboxRoomStore(shared.database, { sender, id -> cleared(app, sender, id) }),
            )
            PushMailboxReceiver(
                resolver = shared.resolver,
                client = client,
                incoming = incoming,
                recordFailure = { log("Receive window failure") },
            ).also { receiverInstance = it }
        }
    }

    /** Re-deriving full pass for a generic marker, or null while disabled. */
    fun fullScan(context: Context): PushFullScan? = synchronized(LOCK) {
        val app = context.applicationContext
        fullScanInstance ?: run {
            if (endpoint(app) == null) return null
            val shared = shared(app)
            val receiver = receiver(app) ?: return null
            PushFullScan(
                page = { after, limit -> shared.journals.registrations.recoveryPage(after, limit) },
                find = { hint -> shared.journals.registrations.findRoute(hint) },
                trust = { localId, peerId -> shared.resolver.snapshot(localId, peerId) },
                derive = { _, peerId, _ -> derivedRoute(shared, peerId) },
                receive = { route -> receiveRoute(shared, receiver, route) },
                recordFailure = { log("Full scan failure") },
            ).also { fullScanInstance = it }
        }
    }

    /** Enumerator of live peers, shared by registration and revocation. */
    fun targets(context: Context): () -> Pair<PushTargets.Outcome, List<PushTargets.Peer>> {
        val registrations = shared(context).journals.registrations
        val enumerator = PushTargets(
            page = { after, limit -> registrations.recoveryPage(after, limit) },
            find = { hint -> registrations.findRoute(hint) },
        )
        return { enumerator.enumerate() }
    }

    /** The real registrar, or null while disabled. */
    fun registrar(context: Context): PushTokenRegistrar? {
        return try {
            val app = context.applicationContext
            if (!enabled(app)) {
                null
            } else {
                val endpoint = endpoint(app) ?: return null
                val shared = shared(app)
                val http = PushHttpClient(endpoint, recordFailure = { log("Registration transport failure") })
                val runner = PushRegistrationRunner(
                    resolver = shared.resolver,
                    flow = { participants -> PushRegistrationFlow.of(shared.journals.registrations, http) },
                    recordFailure = { log("Registration failure") },
                )
                PushTokenRegistrar(
                    enabled = { enabled(app) },
                    token = { PushFirebaseSignals.currentToken(app) },
                    coordinator = { register, _ ->
                        PushRuntimeCoordinator(
                            gate = object : PushRuntimeCoordinator.Gate {
                                override fun enabled() = enabled(app)
                            },
                            journal = object : PushRuntimeCoordinator.TokenJournal {
                                override fun record(token: String, nowMs: Long) = shared.journals.tokens.record(token, nowMs)
                                override fun forget(): Boolean = shared.journals.tokens.forget()
                            },
                            register = register,
                            enqueueCheck = { now ->
                                shared.journals.inbox.enqueueRecovery(now) == PushWakeInbox.EnqueueResult.RECOVERY_QUEUED
                            },
                            recordFailure = { log("Token coordinator failure") },
                        )
                    },
                    peers = targets(app),
                    register = { peerId, token -> runner.register(peerId, token) },
                    revoke = { peerId -> runner.revoke(peerId) },
                    forgetToken = { shared.journals.tokens.forget() },
                    recordFailure = { log("Registration attempt failure") },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag("Registration wiring failed")
            null
        }
    }

    /** Derive the receiver route from the live pin/registration, then wipe. */
    private suspend fun derivedRoute(shared: Shared, peerId: String): String? {
        val pass = shared.resolver.resolve(peerId) ?: return null
        return try {
            val state = pass.registration ?: return null
            val material = pass.derive(state.epochHex) ?: return null
            try {
                material.routeHex()
            } finally {
                material.close()
            }
        } finally {
            pass.close()
        }
    }

    private suspend fun receiveRoute(
        shared: Shared,
        receiver: PushMailboxReceiver,
        route: String,
    ): PushReceiveWindow.Outcome {
        val hint = shared.journals.registrations.findRoute(route)
        if (hint !is PushRegistrationStore.RouteResult.Found) return PushReceiveWindow.Outcome.RETRY
        return receiver.receive(route, hint.peerId)
    }

    /** Live local profile for one pass; null means "not ready", never "trusted". */
    private fun localIdentity(context: Context): PushWakeInspector.LocalIdentity? = try {
        val nodeId = com.vladimir.messenger.data.schedule.DeferredInboxStore.owner(context)
        val binding = com.vladimir.messenger.data.file.FileExchangeKeyStore.publicBinding(context)
        val mode = com.vladimir.messenger.data.file.FileExchangeKeyStore.mode(context)
        if (nodeId.isBlank() || binding == null ||
            mode != com.vladimir.messenger.data.file.FileExchangeKeyStore.Mode.READY
        ) {
            null
        } else {
            com.vladimir.messenger.data.file.FileExchangeKeyStore.withExistingSecret(context) { secret ->
                PushWakeInspector.LocalIdentity(nodeId, binding, secret)
            }
        }
    } catch (cancelled: CoroutineCancellation) {
        throw cancelled
    } catch (_: Exception) {
        diag("Local identity unavailable")
        null
    }

    /**
     * Suppression lookup for the Room boundary. It is deliberately synchronous
     * and fail-closed: an unreadable tombstone store must not let a late copy of
     * a deleted message back in.
     */
    private fun cleared(context: Context, sender: String, id: String): Boolean = try {
        ChatClearRegistry.isCleared(context, sender, id)
    } catch (_: Exception) {
        // Fail closed, but never silently: an unreadable store blocks a late copy.
        diag("Clear-tombstone lookup failed")
        true
    }

    /**
     * Enqueues the drain worker. The failure callback belongs to the scheduling
     * gate: the worker manager invokes it exactly when the request itself fails,
     * including asynchronously, so the accepted path never calls it again.
     * `false` means the request was not accepted and must be retried.
     */
    private fun enqueueDrain(context: Context, onFailure: () -> Unit): Boolean {
        return try {
            shared(context).workManager.enqueue(onFailure)
        } catch (cancelled: CoroutineCancellation) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag("Wake request not scheduled")
            onFailure()
            false
        }
    }

    /**
     * Called from [com.vladimir.messenger.MessengerApplication.onCreate] once the
     * profile is known. It never enables anything: with the gate closed it is a
     * no-op. With an open gate it re-installs the periodic repair job and asks
     * for a registration attempt, which is the only way a token that rotated
     * while the process was dead is noticed at startup.
     */
    fun onStartup(context: Context) {
        val app = context.applicationContext
        if (!enabled(app)) return
        try {
            com.vladimir.messenger.worker.PushWakeRecoveryWorker.install(app)
            com.vladimir.messenger.worker.PushTokenWorker.enqueue(app)
            // A signal may have arrived while the process was dead: the durable
            // inbox is drained by the worker, not by this call.
            shared(app).dispatcher.resume(System.currentTimeMillis())
        } catch (_: Exception) {
            diag("Push startup wiring failed")
        }
    }

    /** Single place that turns any unexpected failure into one generic log line. */
    internal fun log(message: String) {
        Logger.getLogger("PushService").warning(message)
    }

    /**
     * Same sink as [log]; named `diag` so a swallowed failure stays visible to
     * `scripts/ci/check-silent-catch.py` at the call site itself.
     */
    private fun diag(message: String) = log(message)
}
