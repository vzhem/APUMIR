package com.vladimir.messenger.data.push

import android.content.Context
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.worker.PushTokenWorker
import java.util.concurrent.CancellationException
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Triggers an incremental push route registration, epoch announcement and
 * presence-audience refresh when a new peer exchange binding is pinned (1:1
 * contact, group member or channel subscriber).
 *
 * Without this hook a peer pinned after startup had to wait until the next
 * process restart or provider token rotation to receive a registered push route
 * and our epoch offer. A short delayed unique enqueue coalesces a burst of
 * simultaneous `File HELLO` pins (for example when joining a group or channel)
 * into a single WorkManager pass.
 *
 * In unit or instrumented tests that never call [install] this object is a
 * complete no-op.
 */
internal object PushPeerBootstrap {

    private const val AUDIENCE_DEBOUNCE_MS = 2_000L
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var pendingHelloSweep: Boolean = false
    private var audienceJob: Job? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** Call only after the pin transaction has committed and its lock is released. */
    fun onPeerPinned() {
        val app = appContext ?: return
        try {
            if (PushServiceLocator.enabled(app)) {
                PushTokenWorker.enqueueIncremental(app)
            }
            scheduleAudienceRefresh(app)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log a peer identifier or exception text from a push hook.
            Logger.getLogger("PushPeerBootstrap").fine("Incremental push registration deferred")
        }
    }

    /** Coalesces group/channel roster and pin changes into one presence refresh. */
    fun scheduleAudienceRefresh(context: Context, triggerHelloSweep: Boolean = false) {
        val app = context.applicationContext
        if (triggerHelloSweep) pendingHelloSweep = true
        synchronized(lock) {
            audienceJob?.cancel()
            audienceJob = scope.launch {
                delay(AUDIENCE_DEBOUNCE_MS)
                try {
                    if (pendingHelloSweep) {
                        pendingHelloSweep = false
                        PushServiceLocator.fileRouterOrNull(app)?.scheduleHelloSweep()
                    }
                    val directory = PushServiceLocator.swarmDirectoryOrNull(app) ?: return@launch
                    directory.invalidate()
                    RustBridge.setPresenceAudience(directory.audienceIds())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Logger.getLogger("PushPeerBootstrap").fine("Presence audience refresh deferred")
                }
            }
        }
    }
}
