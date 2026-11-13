package com.vladimir.messenger.service

import android.content.Context
import android.os.Build
import com.vladimir.messenger.data.security.DeviceIdentityMarker

/** Tests which change system notification settings must run only on a disposable emulator. */
internal object DisposableNotificationTestProfile {
    fun requireEmptyEmulator(context: Context) {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val product = Build.PRODUCT.lowercase()
        check(fingerprint.startsWith("generic") || "emulator" in fingerprint ||
            "sdk_gphone" in fingerprint || product.startsWith("sdk")) {
            "Notification permission tests require a disposable emulator, never a user's phone"
        }
        val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        check(!prefs.getBoolean("identity_created", false) && !DeviceIdentityMarker.isPresent(context) &&
            listOf("node_id", "public_key", "existing_public_key", "existing_private_key")
                .all { prefs.getString(it, null).isNullOrBlank() }) {
            "Notification tests require an empty disposable profile"
        }
    }
}
