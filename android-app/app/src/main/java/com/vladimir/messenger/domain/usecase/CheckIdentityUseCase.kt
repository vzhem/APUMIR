package com.vladimir.messenger.domain.usecase

import android.content.Context
import com.vladimir.messenger.data.RustBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class CheckIdentityUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend operator fun invoke(): Boolean {
        val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
        val hasIdentity = prefs.getBoolean("identity_created", false)
        if (hasIdentity) return true
        val nodeId = RustBridge.nodeId()
        val publicKey = RustBridge.publicKey()
        return !nodeId.isNullOrEmpty() && !publicKey.isNullOrEmpty()
    }
}