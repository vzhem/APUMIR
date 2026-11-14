package com.vladimir.messenger.data.push

/**
 * Pure mapping from an untrusted provider callback to the durable inbox, with
 * no Firebase, no WorkManager and no journals of its own.
 *
 * The provider signal is the *narrowest* shape the app accepts: data-only
 * `{v:"1"}` and nothing else. A route is deliberately not accepted from the
 * provider, because the service could be operated by anyone holding the
 * registration token and a route would then be an attacker-chosen hint. Instead
 * every accepted signal (and every `onDeletedMessages` gap) becomes the same
 * generic recovery marker, which asks the worker for a bounded full pass over
 * the encrypted registration index; the worker re-derives each route from the
 * live pin, identity and secret before reading anything.
 *
 * A signal that names a route takes the routed branch: the intake checks the
 * local index and queues that route, which is the only shape the Worker sends.
 * A signal without a usable route keeps the older, more conservative behaviour
 * (a generic bounded recovery marker), so nothing regresses if the service
 * shape changes again.
 *
 * Production wiring is [of]; the callbacks are seams so this mapping can be
 * tested without WorkManager or journals.
 *
 * A rejection or an unsupported version writes nothing at all. [Result]
 * describes only what happened locally: `QUEUED` is not a received message, not
 * a delivery receipt and never a reason to show a notification.
 */
internal class PushSignalSink(
    private val onRecovery: (Long) -> PushWakeIntake.Result,
    private val onResume: (Long) -> PushWakeIntake.Result,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Routed intake; defaults to the conservative marker for older callers. */
    private val onRoute: (String, Long) -> PushWakeIntake.Result = { _, now -> onRecovery(now) },
) {
    enum class Result { IGNORED, QUEUED, QUEUED_WITHOUT_SCHEDULER, UNAVAILABLE }

    /** @param data the provider map, `hasNotificationBlock` from the payload shape. */
    fun onMessage(data: Map<String, String>?, hasNotificationBlock: Boolean): Result {
        val now = nowMs()
        return when (PushSignalPolicy.inspect(data, hasNotificationBlock)) {
            // Only the exact `{v:"1"}` data-only shape reaches this branch.
            PushSignalPolicy.Decision.LookInMailbox,
            PushSignalPolicy.Decision.CheckMailboxAfterGap -> deleted(now)
            PushSignalPolicy.Decision.Ignore,
            PushSignalPolicy.Decision.Reject -> Result.IGNORED
        }
    }

    /** Google dropped signals for this device: the same generic marker. */
    fun onDeletedMessages(): Result = deleted(nowMs())

    /** Retry a scheduler registration that failed after a process restart. */
    fun resume(): Result = map(onResume(nowMs()))

    /**
     * A validated `{v:"1", route}` signal. The route is used only as a lookup
     * key: a hit queues that route, a miss is dropped, an unreadable index falls
     * back to the generic marker. It is never treated as an identifier of a
     * person, a chat or a message.
     */
    fun onRoutedSignal(data: Map<String, String>, hasNotificationBlock: Boolean): Result {
        val now = nowMs()
        return when (PushSignalPolicy.inspect(data, hasNotificationBlock)) {
            PushSignalPolicy.Decision.LookInMailbox -> {
                val route = PushSignalPolicy.route(data)
                if (route == null) map(onRecovery(now)) else map(onRoute(route, now))
            }
            PushSignalPolicy.Decision.CheckMailboxAfterGap -> deleted(now)
            PushSignalPolicy.Decision.Ignore,
            PushSignalPolicy.Decision.Reject -> Result.IGNORED
        }
    }

    private fun deleted(now: Long): Result = map(onRecovery(now))

    companion object {
        /** Production wiring: the shared dispatcher owns the durable inbox. */
        fun of(dispatcher: PushWakeDispatcher): PushSignalSink = PushSignalSink(
            onRecovery = { now -> dispatcher.onDeletedMessages(now) },
            onResume = { now -> dispatcher.resume(now) },
            nowMs = System::currentTimeMillis,
            onRoute = { route, now -> dispatcher.onData(mapOf("v" to "1", "route" to route), now) },
        )
    }

    private fun map(result: PushWakeIntake.Result): Result = when (result) {
        PushWakeIntake.Result.QUEUED -> Result.QUEUED
        PushWakeIntake.Result.QUEUED_WITHOUT_SCHEDULER -> Result.QUEUED_WITHOUT_SCHEDULER
        PushWakeIntake.Result.IGNORED -> Result.IGNORED
        PushWakeIntake.Result.UNAVAILABLE -> Result.UNAVAILABLE
    }
}
