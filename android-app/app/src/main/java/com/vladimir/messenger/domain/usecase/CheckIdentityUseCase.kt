package com.vladimir.messenger.domain.usecase

import android.content.Context
import com.vladimir.messenger.data.security.DeviceIdentityMarker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class CheckIdentityUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend operator fun invoke(): Boolean {
        val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        val hasIdentity = prefs.getBoolean("identity_created", false)
        val hasDeviceMarker = DeviceIdentityMarker.isPresent(context)

        if (hasIdentity && !hasDeviceMarker) {
            // An older backup can still be offered by Android/OEM even after the
            // new APK disables future backups. Never trust restored identity/chat
            // state without a marker created in this installation.
            DeviceIdentityMarker.discardIfRestored(context)
            return false
        }
        return hasIdentity && hasDeviceMarker
    }
}
