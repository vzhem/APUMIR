package com.vladimir.messenger.data.push

import java.util.concurrent.CancellationException

/**
 * Local-only coordinator for the future remote-push runtime. It owns three
 * decisions and nothing else:
 *
 *  1. whether the runtime may run at all ([gate]);
 *  2. whether a freshly obtained provider token must be registered, after
 *     comparing it with the encrypted token journal;
 *  3. whether a provider signal may schedule a bounded mailbox check.
 *
 * It performs no Firebase call, no network request, no WorkManager scheduling,
 * no notification and no message write. Every collaborator is injected so the
 * coordinator can be tested on a JVM without Android, Google or Cloudflare.
 *
 * Trust rules encoded here:
 *  - A token is never a reason to deliver anything; it only authorizes a
 *    registration operation for a route the caller already derived from a
 *    pinned peer.
 *  - A provider signal is unauthenticated. It may ask for a mailbox check, but
 *    it may not carry text, sender, chat id, route, pin or unread count.
 *  - A rotation must be re-registered before the old token is considered
 *    replaced; failure to store the new token is reported as [TokenOutcome.Failed]
 *    rather than silently treated as success.
 */
internal class PushRuntimeCoordinator(
    private val gate: Gate,
    private val journal: TokenJournal,
    private val register: suspend (String) -> Boolean,
    private val enqueueCheck: (Long) -> Boolean,
    private val recordFailure: () -> Unit = {},
) {
    /** Injectable view of the immutable build-time configuration. */
    interface Gate {
        /** False for every released build; no transport may be built. */
        fun enabled(): Boolean
    }

    /** Encrypted token journal seam; never returns the token itself. */
    interface TokenJournal {
        fun record(token: String, nowMs: Long): FcmTokenJournal.State
        fun forget(): Boolean
    }

    sealed interface TokenOutcome {
        /** Token accepted by the journal and registration was requested. */
        data class Registered(val rotated: Boolean) : TokenOutcome
        /** The same token is already recorded; no network call is needed. */
        object AlreadyCurrent : TokenOutcome
        /** The gate is closed: no token is stored and no request is made. */
        object Disabled : TokenOutcome
        /** Malformed, corrupt journal or a failed registration; retry later. */
        object Failed : TokenOutcome
    }

    sealed interface SignalOutcome {
        /** A bounded mailbox check was requested; not delivery. */
        object CheckRequested : SignalOutcome
        object Ignored : SignalOutcome
        object Rejected : SignalOutcome
        object Disabled : SignalOutcome
        object Failed : SignalOutcome
    }

    /**
     * Called with the token Google returned, exactly once per provider
     * callback. [nowMs] is the caller's clock; the journal must be read and
     * written in one transaction, so a lost race cannot be mistaken for
     * "unchanged".
     */
    suspend fun onToken(token: String, nowMs: Long): TokenOutcome {
        if (!gate.enabled()) return TokenOutcome.Disabled
        val state = try {
            journal.record(token, nowMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diag()
            return TokenOutcome.Failed
        }
        return when (state) {
            FcmTokenJournal.State.Unchanged -> TokenOutcome.AlreadyCurrent
            FcmTokenJournal.State.Unavailable -> TokenOutcome.Failed
            // Missing and Rotated both require a registration attempt. A failed
            // attempt leaves the journal at the new token, so a retry sees
            // Unchanged and must be redriven by the caller's pending state.
            FcmTokenJournal.State.Missing, FcmTokenJournal.State.Rotated -> {
                val registered = try {
                    register(token)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    diag()
                    false
                }
                if (!registered) {
                    // Never pretend a rotated token replaced the old one.
                    journal.forget()
                    TokenOutcome.Failed
                } else {
                    TokenOutcome.Registered(state == FcmTokenJournal.State.Rotated)
                }
            }
        }
    }

    /**
     * [data] is the raw provider map and [hasNotificationBlock] says whether a
     * provider-rendered notification was attached. Both come from the
     * untrusted provider callback. Nothing here may display or acknowledge.
     */
    fun onSignal(data: Map<String, String>?, hasNotificationBlock: Boolean, nowMs: Long): SignalOutcome {
        if (!gate.enabled()) return SignalOutcome.Disabled
        return when (PushSignalPolicy.inspect(data, hasNotificationBlock)) {
            PushSignalPolicy.Decision.LookInMailbox -> enqueue(nowMs)
            PushSignalPolicy.Decision.CheckMailboxAfterGap -> enqueue(nowMs)
            PushSignalPolicy.Decision.Ignore -> SignalOutcome.Ignored
            PushSignalPolicy.Decision.Reject -> SignalOutcome.Rejected
        }
    }

    /** The provider dropped signals: a bounded mailbox check is still required. */
    fun onDeletedMessages(nowMs: Long): SignalOutcome =
        if (!gate.enabled()) SignalOutcome.Disabled else enqueue(nowMs)

    /** Out of the app (logout, profile reset): stop accepting this device's signals. */
    fun forgetToken(): Boolean = try {
        journal.forget()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        diag()
        false
    }

    private fun enqueue(nowMs: Long): SignalOutcome = try {
        if (enqueueCheck(nowMs)) SignalOutcome.CheckRequested else SignalOutcome.Failed
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        diag()
        SignalOutcome.Failed
    }

    private fun diag() {
        try {
            recordFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A broken counter must not turn a failure into success.
        }
    }
}
