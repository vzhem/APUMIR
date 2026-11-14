package com.vladimir.messenger.data.push

import android.content.Context
import java.util.concurrent.CancellationException
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The single call site through which ordinary sending announces this device's
 * own epoch to a correspondent.
 *
 * Push is a *second* path, not a replacement: the message has already been
 * written and handed to the existing transport when this runs, so every outcome
 * here is best effort. Nothing in this file changes a message status, retries a
 * send, or reports delivery — it only makes the reply path possible.
 *
 * While the gate is closed (every shipped build) it returns immediately, without
 * touching a journal, a database or the network.
 */
internal object PushEpochPublish {

    /**
     * Offers our current registered epoch to [peerId] if, and only if, this peer
     * has a live pin and an accepted registration. Otherwise it does nothing: a
     * pin is never created here, and an unregistered epoch cannot be offered.
     */
    suspend fun noteCorrespondent(context: Context, peerId: String) {
        if (peerId.isBlank()) return
        val app = context.applicationContext
        if (!PushServiceLocator.enabled(app)) return
        try {
            val shared = PushServiceLocator.shared(app)
            val delivery = shared.epochs ?: return
            shared.resolver.resolveForRegistration(peerId)?.use { pass ->
                val state = pass.registration ?: return@use
                if (state.phase != PushRegistrationState.Phase.ACTIVE || state.registeredAtMs <= 0) return@use
                when (delivery.offer(state, peerId, pass.localBinding, pass.pinnedPeerBinding, pass.localSecret)) {
                    PushEpochDelivery.Outcome.Handled,
                    PushEpochDelivery.Outcome.Stored -> Unit
                    else -> diag("Epoch offer not accepted")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log the peer, the epoch or exception text.
            diag("Epoch offer failed")
        }
    }

    /**
     * After a successful (re)registration, tell every peer that has a live pin
     * about the epoch we just registered. A rotation is useless until the peer
     * learns the new one; without this a stale epoch would be silently used.
     *
     * A peer whose epoch we do not have yet will refuse the mailbox write, which
     * is reported as deferred, not as delivered.
     */
    suspend fun noteAllRegistered(context: Context) {
        val app = context.applicationContext
        if (!PushServiceLocator.enabled(app)) return
        try {
            val (outcome, peers) = PushServiceLocator.targets(app)()
            if (outcome != PushTargets.Outcome.COMPLETED) return
            for (peer in peers) noteCorrespondent(app, peer.peerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag("Epoch announcement pass failed")
        }
    }

    /**
     * Non-suspending variant for transport wrappers: it only starts the offer on
     * a background scope when the gate is open, so a caller on a hot path never
     * waits for the network and never sees its failure.
     */
    fun noteCorrespondentIfEnabled(context: Context, peerId: String) {
        if (peerId.isBlank()) return
        val app = context.applicationContext
        if (!PushServiceLocator.enabled(app)) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            noteCorrespondent(app, peerId)
        }
    }

    private fun diag(message: String) {
        Logger.getLogger("PushEpochPublish").fine(message)
    }
}
