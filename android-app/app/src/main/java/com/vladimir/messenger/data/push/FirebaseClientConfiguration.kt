package com.vladimir.messenger.data.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.vladimir.messenger.BuildConfig

/**
 * Local Firebase SDK configuration only. CONFIGURED is not a token, provider
 * connection, wake registration, or message delivery. No network is started.
 */
internal object FirebaseClientConfiguration {
    enum class Status { NOT_INCLUDED, CONFIGURED, UNAVAILABLE }

    fun inspect(context: Context): Status {
        if (!BuildConfig.APU_FIREBASE_CLIENT_CONFIGURED) return Status.NOT_INCLUDED
        return try {
            val app = FirebaseApp.getApps(context.applicationContext)
                .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                ?: return Status.UNAVAILABLE
            val options = app.options
            if (options.applicationId.isNotBlank() && !options.apiKey.isNullOrBlank() &&
                !options.projectId.isNullOrBlank() && !options.gcmSenderId.isNullOrBlank()
            ) Status.CONFIGURED else Status.UNAVAILABLE
        } catch (error: Exception) {
            // Never print Firebase identifiers, config values, or exception text.
            Log.w("FirebaseClientConfig", "Configuration unavailable: ${error.javaClass.simpleName}")
            Status.UNAVAILABLE
        }
    }
}
