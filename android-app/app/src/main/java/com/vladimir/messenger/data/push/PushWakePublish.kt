package com.vladimir.messenger.data.push

import android.content.Context
import com.vladimir.messenger.data.diagnostics.PushWakeOutcome
import java.util.concurrent.CancellationException
import java.util.logging.Logger

/**
 * The one call site that asks the service to wake a correspondent.
 *
 * Before this existed the app could receive a signal, register a route and store
 * ciphertext, but nothing ever asked the service to wake the other side - so a
 * sleeping phone was never woken by us, and a two-phone test could not have
 * passed for any reason other than luck.
 *
 * This runs *after* the ordinary send has been attempted, and every outcome is
 * best effort: it cannot change a message status, retry a send, or report
 * delivery. `ACCEPTED` (HTTP 202) means the Worker took the request. It does not
 * mean the peer woke, looked, or that a message exists.
 *
 * A wake needs a live pin for that exact peer and the *peer's* epoch: the
 * receiver chooses its epoch and announces it, and only with that epoch do both
 * sides derive the same route. Using our own registration epoch here was a real
 * defect: the two phones computed different capabilities, so every wake addressed
 * a route nobody had registered and disappeared without a trace.
 *
 * A wake is attempted only when the peer's epoch is known and no wake for that
 * peer was attempted in the last minute (in-process throttle) or accepted within
 * the local budget. With the gate closed (a shipped build that is not a test
 * build) this returns immediately without touching storage or network.
 */
internal object PushWakePublish {

    /** Why nothing was sent, or that the Worker accepted the request. */
    sealed interface Outcome {
        /** The Worker accepted the request; this is not delivery. */
        object Accepted : Outcome
        /** Sent recently enough: the budget suppressed this one. */
        object WithinBudget : Outcome
        /**
         * No trust pass for this pair: no pin, a changed identity or an unusable
         * exchange secret. Nothing may be derived for this correspondent.
         */
        object NotRegistered : Outcome
        /**
         * The peer never announced an epoch to us, so there is no route to wake.
         *
         * Kept apart from [NotRegistered] because the fix is on the other side of
         * the pair: this device is ready, the correspondent has not spoken yet.
         */
        object NoPeerEpoch : Outcome
        /** The service refused or could not be reached. */
        object Unavailable : Outcome
        /** The gate is closed, the peer is blank, or push is not configured. */
        object Skipped : Outcome
    }

    /**
     * Ask the service to wake [peerId]. Safe to call on every send and on every
     * retry pass: it never throws and never blocks a caller for long.
     *
     * Two independent limits. The local budget is consumed only by an accepted
     * request, so a refused service is retried later. The in-process throttle
     * above it exists because retries can call this every few seconds: without it
     * a single stuck message would turn into an unbounded stream of storage reads
     * and counted attempts that all say the same thing.
     */
    suspend fun wake(context: Context, peerId: String): Outcome {
        if (peerId.isBlank()) return Outcome.Skipped
        val app = context.applicationContext
        if (!PushServiceLocator.enabled(app)) return Outcome.Skipped
        PushDiagnostics.install(app)
        install(app)
        val now = System.currentTimeMillis()
        // Собеседник ещё не прислал эпоху: пробовать снова бессмысленно, пока он
        // не ответит на просьбу. Раньше это повторялось каждые 15 секунд и
        // давало тысячи пустых попыток (владелец 2026-11-18).
        val epochWaitUntil = noEpochUntilMs[peerId]
        if (epochWaitUntil != null && epochWaitUntil > now) return Outcome.WithinBudget
        val previous = lastAttemptAtMs[peerId]
        if (previous != null && now >= previous && now - previous < MIN_ATTEMPT_INTERVAL_MS) {
            return Outcome.WithinBudget
        }
        lastAttemptAtMs[peerId] = now
        val attempted = attempt(app, peerId)
        val outcome = attempted.outcome
        // Всегда, а не только при отказе: иначе подпись от прошлой попытки
        // читалась бы рядом с «принято».
        PushDiagnostics.noteWakeDetail(attempted.detail)
        PushDiagnostics.noteWake(
            when (outcome) {
                Outcome.Accepted -> PushWakeOutcome.ACCEPTED
                Outcome.WithinBudget -> PushWakeOutcome.WITHIN_BUDGET
                Outcome.NotRegistered -> PushWakeOutcome.NOT_REGISTERED
                Outcome.NoPeerEpoch -> PushWakeOutcome.NO_PEER_EPOCH
                Outcome.Unavailable -> PushWakeOutcome.UNAVAILABLE
                Outcome.Skipped -> PushWakeOutcome.SKIPPED
            },
        )
        if (outcome == Outcome.NoPeerEpoch && attempted.detail == "epoch_missing") {
            // Нечего будить: адреса нет. Один раз просим эпоху и уходим в редкий
            // фон — вместо пустого повтора каждые 15 секунд.
            noEpochUntilMs[peerId] = now + NO_EPOCH_RETRY_MS
            runCatching { PushEpochPublish.requestEpoch(app, peerId) }
                .onFailure { diag("Epoch request failed") }
            PushDiagnostics.noteEpochRequest()
            // Подпись в отчёте: «просили и не дождались» — это не то же самое,
            // что «собеседник ещё не писал».
            PushDiagnostics.noteWakeDetail("epoch_missing_requested")
        } else if (outcome != Outcome.NoPeerEpoch) {
            noEpochUntilMs.remove(peerId)
        }
        if (outcome == Outcome.Accepted) {
            PushDiagnostics.noteWakeAccepted()
            // A 202 proves the endpoint answers; the startup /health probe can be
            // hours old and would otherwise keep reporting an outage.
            PushDiagnostics.noteEndpoint(true)
        }
        return outcome
    }

    /** Fixed name describing why trust did not resolve for this peer. */
    private suspend fun trustTag(app: android.content.Context, peerId: String): String = try {
        PushServiceLocator.shared(app).resolver.availability(peerId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The detail is a hint, never the diagnosis itself.
        diag("Trust detail unavailable")
        "unreadable"
    }

    /**
     * What one attempt concluded, plus the fixed-name reason for it.
     *
     * [detail] is a name from the same vocabulary as [trustView] - never a peer,
     * a binding or a packet - so the report can tell "the correspondent has not
     * spoken yet" from "we have its epoch but cannot read it".
     */
    private class Attempt(val outcome: Outcome, val detail: String = NO_DETAIL)

    /** The bounded attempt itself; [wake] only measures and records it. */
    private suspend fun attempt(app: android.content.Context, peerId: String): Attempt {
        return try {
            val endpoint = PushServiceLocator.endpoint(app) ?: return Attempt(Outcome.Skipped)
            val shared = PushServiceLocator.shared(app)
            val http = PushHttpClient(endpoint, recordFailure = { diag("Wake transport failure") })
            val budget = budget(app)
            val resolver = shared.resolver
            // Wake needs trust (pin, own binding, exchange secret), not our own
            // registration: that record says how *we* are reached, and using its
            // epoch here addresses a route the correspondent never registered.
            val pass = resolver.resolveForRegistration(peerId)
                // `not_registered` alone named three different fixes: a peer
                // without a pinned key, an unprepared local exchange secret, an
                // unreadable store.
                ?: return Attempt(Outcome.NotRegistered, trustTag(app, peerId))
            pass.use {
                val epoch = when (val lookup = epochLookup(shared, pass)) {
                    is EpochLookup.Found -> lookup.epoch
                    // The four ways a peer epoch can be unusable are different
                    // repairs, and until now all of them were one word.
                    else -> return Attempt(Outcome.NoPeerEpoch, lookup.tag)
                }
                val material = shared.crypto.derive(
                    pass.localBinding,
                    pass.pinnedPeerBinding,
                    pass.localSecret,
                    epoch,
                    localIsSender = true,
                ) ?: return Attempt(Outcome.Unavailable, "no_material")
                try {
                    val last = when (val recorded = budget.last(peerId)) {
                        is PushWakeBudget.Last.At -> recorded.atMs
                        PushWakeBudget.Last.Never, PushWakeBudget.Last.Unavailable -> 0L
                    }
                    val decision = signal.decide(PushWakeSignal.Candidate(material, last))
                    when (decision) {
                        is PushWakeSignal.Decision.Send -> {
                            val result = http.wake(decision.request)
                            if (PushWakeSignal.wasAccepted(result)) {
                                // Only an acceptance consumes the budget: a refused
                                // or unreachable service must not silence the peer
                                // for the whole interval.
                                budget.record(peerId)
                                Attempt(Outcome.Accepted)
                            } else {
                                Attempt(Outcome.Unavailable)
                            }
                        }
                        PushWakeSignal.Decision.TooSoon -> Attempt(Outcome.WithinBudget)
                        PushWakeSignal.Decision.NotRegistered -> Attempt(Outcome.NoPeerEpoch, "signal_not_registered")
                    }
                } finally {
                    material.close()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log the peer, the capability or exception text.
            diag("Wake attempt failed")
            Attempt(Outcome.Unavailable)
        }
    }

    private val signal = PushWakeSignal()

    /**
     * The epoch the correspondent chose for this pair.
     *
     * A revoke is not an address: only an offer carries an epoch, and a stored
     * revoke means the peer asked us to stop using the previous one. Every miss
     * carries its own name, because "never announced" is fixed by the other
     * phone, while "stored but unreadable" is fixed here.
     */
    private fun epochLookup(shared: PushServiceLocator.Shared, pass: PushTrustResolver.Pass): EpochLookup {
        val stored = shared.journals.epochs.read(
            pass.localId,
            pass.peerId,
            pass.pinnedPeerBinding,
            pass.localBinding,
        )
        val state = when (stored) {
            PushEpochStore.ReadResult.Missing -> return EpochLookup.Missing
            PushEpochStore.ReadResult.Unavailable -> return EpochLookup.Unreadable
            is PushEpochStore.ReadResult.Found -> stored.state
        }
        if (state.action != PushEpochProtocol.Action.OFFER) return EpochLookup.Revoked
        val epoch = state.epochHex ?: return EpochLookup.Malformed
        if (epoch.length != 32) return EpochLookup.Malformed
        return EpochLookup.Found(epoch)
    }

    private sealed interface EpochLookup {
        class Found(val epoch: String) : EpochLookup
        /** The correspondent never announced an epoch to us. */
        object Missing : EpochLookup
        /** A record exists but no longer matches this pair's bindings. */
        object Unreadable : EpochLookup
        /** The correspondent revoked the epoch it gave us. */
        object Revoked : EpochLookup
        /** An offer we cannot address: no epoch, or the wrong length. */
        object Malformed : EpochLookup

        val tag: String
            get() = when (this) {
                is Found -> "ready_epoch"
                Missing -> "epoch_missing"
                Unreadable -> "epoch_unreadable"
                Revoked -> "epoch_revoked"
                Malformed -> "epoch_malformed"
            }
    }

    /**
     * Process-wide application context, so retry pumps can wake a peer without
     * every call site threading a Context through a god file. Null until
     * [PushServiceLocator.onStartup] installs an enabled build.
     */
    @Volatile
    private var installed: android.content.Context? = null

    fun install(context: Context) {
        installed = context.applicationContext
    }

    /** Wake through the installed context; a no-op before startup ran. */
    suspend fun wakeInstalled(peerId: String): Outcome {
        val app = installed ?: return Outcome.Skipped
        return wake(app, peerId)
    }

    /** In-memory throttle; never persisted, so a restart retries immediately. */
    private val lastAttemptAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * До какого времени пробовать бессмысленно: у собеседника нет эпохи.
     *
     * Десять минут — компромисс: эпоха появляется только после того, как на том
     * телефоне запустится сборка, умеющая их присылать, а это событие не
     * секундное. Просьба уходит отдельно, один раз, поэтому пауза ничего не
     * теряет: как только эпоха придёт, следующая попытка (в том числе из
     * отправки сообщения) пойдёт сразу.
     */
    private val noEpochUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val NO_EPOCH_RETRY_MS = 10 * 60_000L
    /**
     * Между попытками — 15 секунд.
     *
     * Было 60: первая попытка сразу после отправки часто заставала собеседника
     * в сне, а следующая начиналась только через минуту. Ядро всё равно не
     * шлёт пробуждение чаще, чем нужно, а лавину держит бюджет
     * ([PushWakeSignal.MIN_INTERVAL_MS]).
     */
    private const val MIN_ATTEMPT_INTERVAL_MS = 15_000L

    /** «Причины нет»: отчёт печатает подпись только когда она не пустая. */
    private const val NO_DETAIL = "-"

    private fun budget(app: Context): PushWakeBudget = synchronized(LOCK) {
        instance ?: PushWakeBudget(app).also { instance = it }
    }

    private val LOCK = Any()
    private var instance: PushWakeBudget? = null

    private fun diag(message: String) {
        try {
            PushServiceLocator.log(message)
        } catch (_: Exception) {
            Logger.getLogger("PushWakePublish").warning(message)
        }
    }
}
