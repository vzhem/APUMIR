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
 * Production wiring is [of]; the two callbacks are seams so this mapping can be
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

    private fun deleted(now: Long): Result = map(onRecovery(now))

    companion object {
        /** Production wiring: the shared dispatcher owns the durable inbox. */
        fun of(dispatcher: PushWakeDispatcher): PushSignalSink = PushSignalSink(
            onRecovery = { now -> dispatcher.onDeletedMessages(now) },
            onResume = { now -> dispatcher.resume(now) },
        )
    }

    private fun map(result: PushWakeIntake.Result): Result = when (result) {
        PushWakeIntake.Result.QUEUED -> Result.QUEUED
        PushWakeIntake.Result.QUEUED_WITHOUT_SCHEDULER -> Result.QUEUED_WITHOUT_SCHEDULER
        PushWakeIntake.Result.IGNORED -> Result.IGNORED
        PushWakeIntake.Result.UNAVAILABLE -> Result.UNAVAILABLE
    }
}
