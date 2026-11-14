package com.vladimir.messenger.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vladimir.messenger.data.push.PushServiceLocator
import com.vladimir.messenger.data.push.PushSignalPolicy
import com.vladimir.messenger.data.push.PushSignalSink
import com.vladimir.messenger.worker.PushTokenWorker
import com.vladimir.messenger.worker.RelayWakeWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The only entry point through which Google can reach this app.
 *
 * It is a pure *signal* receiver. It never renders a notification, never reads a
 * message, never writes to Room and never reports delivery. A provider callback
 * is unauthenticated: anyone holding the registration token can post it.
 *
 * What a valid callback does:
 *  - an exact data-only `{v:"1"}` message -> one durable generic marker in the
 *    encrypted wake inbox plus a WorkManager request; the worker then re-derives
 *    every route from the live pin and registration before reading anything.
 *    A `route` field is not accepted from the provider: the caller can be anyone
 *    holding the token, so a provider-chosen hint must never be trusted;
 *  - `onDeletedMessages` -> the same generic marker, because Google dropped
 *    signals while the device was unreachable;
 *  - `onNewToken` -> a durable token journal write and one WorkManager task that
 *    performs the signed registration.
 *
 * What a valid callback also does: brings the ordinary transport back for a
 * bounded window, so the app can actually fetch what was sent while it was
 * asleep. A wake that only reads the ciphertext mailbox would wake the phone and
 * still leave the message unreceived, because personal text travels through the
 * normal Rust transport, not through the mailbox. The window is the existing
 * bounded [RelayWakeWorker] pass - it never starts a foreground service, never
 * sends anything, and yields to a lifecycle start.
 *
 * With PUSH disabled (every shipped build: no verified client configuration, no
 * test-build flag, no approved host) every callback returns immediately, before
 * any journal, network or scheduler access.
 */
class ApuFirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        if (!PushServiceLocator.enabled(applicationContext)) {
            Log.i(TAG, "token ignored: remote push is disabled in this build")
            return
        }
        // The token itself is deliberately NOT passed to WorkManager input data
        // or logged; the worker asks the SDK again and compares fingerprints.
        PushTokenWorker.enqueue(applicationContext)
        // A token that arrives proves the SDK is configured, not that this
        // process will accept a message; both are needed for one signal.
        scope.launch { PushServiceLocator.preparingReceive(applicationContext) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val context = applicationContext
        if (!PushServiceLocator.enabled(context)) {
            Log.i(TAG, "signal ignored: remote push is disabled in this build")
            return
        }
        val data = message.data
        scope.launch {
            try {
                // Reception must be confirmed before a callback is trusted to be
                // the only one: an install that never enabled auto-init receives
                // nothing at all, so a missing setting is a defect, not quiet.
                if (!PushServiceLocator.preparingReceive(context)) {
                    Log.w(TAG, "signal arrives without confirmed reception")
                }
                // The sink accepts only the exact data-only `{v:"1"}` shape and
                // writes one durable generic marker; it never reads a message,
                // shows a notification or acknowledges anything.
                // `notification` cannot arrive as data, but the SDK surfaces it
                // separately; a payload that carried one is not data-only, so it
                // is reported as such rather than assumed absent.
                val notificationBlock = message.notification != null
                val sink = PushSignalSink.of(PushServiceLocator.shared(context).dispatcher)
                val result = if (PushSignalPolicy.route(data) != null) {
                    // The service names the route it woke, so the intake can
                    // check the local index instead of asking for a full scan.
                    // A route hit stays a hint: the worker re-derives it.
                    sink.onRoutedSignal(data, hasNotificationBlock = notificationBlock)
                } else {
                    sink.onMessage(data.ifEmpty { null }, hasNotificationBlock = notificationBlock)
                }
                com.vladimir.messenger.data.push.PushDiagnostics.noteSignal(
                    accepted = result == PushSignalSink.Result.QUEUED ||
                        result == PushSignalSink.Result.QUEUED_WITHOUT_SCHEDULER,
                )
                when (result) {
                    PushSignalSink.Result.QUEUED_WITHOUT_SCHEDULER,
                    PushSignalSink.Result.UNAVAILABLE -> Log.w(TAG, "signal stored but not scheduled")
                    PushSignalSink.Result.IGNORED, PushSignalSink.Result.QUEUED -> Unit
                }
            } catch (_: Exception) {
                // Never log the payload, the route or exception text.
                com.vladimir.messenger.data.push.PushDiagnostics.noteError("signal handling")
                Log.w(TAG, "signal handling failed")
            }
            // Independent of the mailbox pass: the message this wake is about
            // travels through the ordinary transport, so the transport has to be
            // up for anything to arrive. Best effort, bounded, never a promise of
            // delivery.
            try {
                RelayWakeWorker.enqueueOnce(context)
            } catch (_: Exception) {
                Log.w(TAG, "bounded relay wake not scheduled")
            }
        }
    }

    override fun onDeletedMessages() {
        val context = applicationContext
        if (!PushServiceLocator.enabled(context)) return
        com.vladimir.messenger.data.push.PushDiagnostics.noteSignal(accepted = true)
        val sink = PushSignalSink.of(PushServiceLocator.shared(context).dispatcher)
        scope.launch {
            try {
                sink.onDeletedMessages()
            } catch (_: Exception) {
                Log.w(TAG, "gap handling failed")
            }
        }
    }

    private companion object {
        const val TAG = "ApuPushService"
    }
}
