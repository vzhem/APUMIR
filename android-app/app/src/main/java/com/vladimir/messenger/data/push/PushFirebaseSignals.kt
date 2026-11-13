package com.vladimir.messenger.data.push

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.vladimir.messenger.BuildConfig

/**
 * The only place that talks to Google Play Services and Firebase Messaging.
 *
 * Everything it returns is a *local* fact: a token string, "no Play Services",
 * "the SDK is not configured in this build", or a failed Task. A token proves
 * only that Google issued one to this installation; it does not prove the
 * mailbox route is registered, that a signal will be delivered, or that a
 * message exists. Errors are reduced to a category so that a token, project
 * data or a stack trace never reaches a log.
 *
 * The SDK is never started unless a caller has already passed
 * [PushRuntimeConfig.gateOpen]; with the gate closed (every shipped build) this
 * object is not used at all and the app remains a build with
 * `firebase_messaging_auto_init_enabled=false`.
 */
internal object PushFirebaseSignals {
    sealed interface Outcome {
        /** Google issued a token to this installation. */
        class Token(val value: String) : Outcome {
            override fun toString(): String = "PushFirebaseToken(redacted)"
        }
        /** No usable Google Play Services: no token can be requested. */
        object NoPlayServices : Outcome
        /** This build has no verified client configuration or no default app. */
        object NotConfigured : Outcome
        /** The request failed; a retry needs the caller's own backoff. */
        object Failed : Outcome
    }

    /** Build-time gate only; never influenced by a payload or a server answer. */
    fun enabled(context: Context): Boolean = PushRuntimeConfig.gateOpen(
        firebaseConfigured = FirebaseClientConfiguration.inspect(context) ==
            FirebaseClientConfiguration.Status.CONFIGURED,
        testedBuild = BuildConfig.APU_PUSH_TESTED,
    )

    /** Reviewed endpoint, or null while the shipped host list stays empty. */
    fun endpoint(context: Context): PushEndpoint? = PushRuntimeConfig.endpoint(
        firebaseConfigured = FirebaseClientConfiguration.inspect(context) ==
            FirebaseClientConfiguration.Status.CONFIGURED,
        testedBuild = BuildConfig.APU_PUSH_TESTED,
        url = BuildConfig.APU_PUSH_ENDPOINT.takeIf { it.isNotEmpty() },
    )

    suspend fun currentToken(context: Context): Outcome {
        return try {
            val app = context.applicationContext
            when {
                FirebaseClientConfiguration.inspect(app) != FirebaseClientConfiguration.Status.CONFIGURED ->
                    Outcome.NotConfigured
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS ->
                    Outcome.NoPlayServices
                FirebaseApp.getApps(app).none { it.name == FirebaseApp.DEFAULT_APP_NAME } ->
                    Outcome.NotConfigured
                else -> {
                    val token = com.google.android.gms.tasks.Tasks.await(FirebaseMessaging.getInstance().token)
                    if (token.isNullOrBlank() || token.length > MAX_TOKEN_CHARS) {
                        Outcome.Failed
                    } else {
                        Outcome.Token(token)
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log the token, identifiers or the exception text.
            Log.w(TAG, "token request failed")
            Outcome.Failed
        }
    }

    /** Ask the SDK to rotate/drop the registration; no mailbox call is made here. */
    suspend fun deleteToken(): Boolean = try {
        com.google.android.gms.tasks.Tasks.await(FirebaseMessaging.getInstance().deleteToken())
        true
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Log.w(TAG, "token deletion failed")
        false
    }

    private const val MAX_TOKEN_CHARS = 2048
    private const val TAG = "PushFirebaseSignals"
}
