package com.vladimir.messenger.domain.usecase

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.RustBridge
import com.vladimir.messenger.data.security.DeviceIdentityMarker
import com.vladimir.messenger.data.security.IdentitySigningKeyStore
import com.vladimir.messenger.data.security.RelayAtRestMasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

data class IdentityResult(val inviteLink: String, val fingerprint: String)

class CreateIdentityUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend operator fun invoke(name: String): Result<IdentityResult> {
        return try {
            // M8-C slice 3: Keystore-мост строго до старта движка; недоступность
            // ключа = честный RAM-only degrade первого запуска.
            val atRestKeyOk = RelayAtRestMasterKey.installIntoCore(context)
            Log.i("CreateIdentityUseCase", "Relay at-rest key installed: $atRestKeyOk")
            val relayDbPath = File(context.filesDir, "apu_relay.sqlite").absolutePath
            val isInitialized = RustBridge.initialize(name, relayDbPath = relayDbPath)
            if (!isInitialized) {
                return Result.failure(Exception("Failed to initialize Rust core"))
            }

            val publicKey = RustBridge.publicKey()
            val nodeId = RustBridge.nodeId()

            if (publicKey.isNullOrEmpty() || nodeId.isNullOrEmpty()) {
                return Result.failure(Exception("Failed to generate keys"))
            }

            // New identity receives a real signing sidecar immediately. Failure
            // disables only future signed features; legacy messaging stays usable.
            val signing = IdentitySigningKeyStore.installIntoCore(context, nodeId)
            Log.i(
                "CreateIdentityUseCase",
                "Identity signing mode: ${signing?.mode ?: "legacy-only"}"
            )

            // Device-local marker не попадает в backup. Восстановленные prefs без
            // него не должны пропускать onboarding на новой установке.
            DeviceIdentityMarker.create(context)

            // Сохраняем все данные identity включая "секрет" для восстановления
            val prefs = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("identity_created", true)
                .putString("display_name", name)
                .putString("public_key", publicKey)
                .putString("node_id", nodeId)
                // спользуем publicKey как "existing_public_key" для следующего запуска
                // Rust-движок сам восстановит по нему ключи из своего внутреннего хранилища
                .putString("existing_public_key", publicKey)
                .apply()

            val inviteLink = "p2p://invite/$publicKey"
            val fingerprint = if (publicKey.length > 16) publicKey.take(16) + "..." else publicKey

            Result.success(IdentityResult(inviteLink = inviteLink, fingerprint = fingerprint))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}