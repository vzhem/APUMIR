package com.vladimir.messenger.domain.usecase

import android.content.Context
import com.vladimir.messenger.data.RustBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class IdentityResult(val inviteLink: String, val fingerprint: String)

class CreateIdentityUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend operator fun invoke(name: String): Result<IdentityResult> {
        return try {
            val isInitialized = RustBridge.initialize(name)
            if (!isInitialized) {
                return Result.failure(Exception("Failed to initialize Rust core"))
            }

            val publicKey = RustBridge.publicKey()
            val nodeId = RustBridge.nodeId()

            if (publicKey.isNullOrEmpty() || nodeId.isNullOrEmpty()) {
                return Result.failure(Exception("Failed to generate keys"))
            }

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