package com.vladimir.messenger.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vladimir.messenger.data.push.PushServiceLocator
import com.vladimir.messenger.data.push.PushSignalSink
import com.vladimir.messenger.worker.PushTokenWorker
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
                // The sink accepts only the exact data-only `{v:"1"}` shape and
                // writes one durable generic marker; it never reads a message,
                // shows a notification or acknowledges anything.
                // `notification` cannot arrive as data, but the SDK surfaces it
                // separately; a payload that carried one is not data-only, so it
                // is reported as such rather than assumed absent.
                val notificationBlock = message.notification != null
                when (PushSignalSink.of(PushServiceLocator.shared(context).dispatcher)
                        .onMessage(data.ifEmpty { null }, hasNotificationBlock = notificationBlock)) {
                    PushSignalSink.Result.QUEUED_WITHOUT_SCHEDULER,
                    PushSignalSink.Result.UNAVAILABLE -> Log.w(TAG, "signal stored but not scheduled")
                    PushSignalSink.Result.IGNORED, PushSignalSink.Result.QUEUED -> Unit
                }
            } catch (_: Exception) {
                // Never log the payload, the route or exception text.
                Log.w(TAG, "signal handling failed")
            }
        }
    }

    override fun onDeletedMessages() {
        val context = applicationContext
        if (!PushServiceLocator.enabled(context)) return
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
