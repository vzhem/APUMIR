package com.vladimir.messenger.data.file

import android.util.Base64
import com.vladimir.messenger.data.local.dao.FileExchangePeerDao
import com.vladimir.messenger.data.local.entity.FileExchangePeerEntity
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.p2p_core.fileExchangeBindingNodeId
import uniffi.p2p_core.fileExchangeBindingPublicKey
import uniffi.p2p_core.verifyFileExchangeBinding

/** Strict TOFU pin: a different key for the same legacy contact never overwrites silently. */
@Singleton
class FileExchangePeerStore @Inject constructor(
    private val dao: FileExchangePeerDao,
) {
    suspend fun pinFirstSeen(binding: ByteArray, nowMs: Long = System.currentTimeMillis()): PinResult {
        require(binding.isNotEmpty() && binding.size <= MAX_BINDING_BYTES)
        check(verifyFileExchangeBinding(binding)) { "Invalid file exchange signature" }
        val nodeId = fileExchangeBindingNodeId(binding)
        val publicKey = fileExchangeBindingPublicKey(binding)
        check(publicKey.size == 32)
        val hash = sha256(binding)
        val existing = dao.get(nodeId)
        if (existing != null) {
            check(existing.bindingSha256 == hash &&
                MessageDigest.isEqual(Base64.decode(existing.bindingBase64, Base64.NO_WRAP), binding)) {
                "File exchange key changed; explicit contact verification required"
            }
            return PinResult(nodeId, hash, existing.trustState, newlyPinned = false)
        }
        val encoded = Base64.encodeToString(binding, Base64.NO_WRAP)
        check(encoded.length <= MAX_ENCODED_BINDING_CHARS)
        val entity = FileExchangePeerEntity(
            nodeId = nodeId,
            bindingBase64 = encoded,
            bindingSha256 = hash,
            x25519PublicHex = publicKey.toHex(),
            trustState = "TOFU",
            firstSeenAtMs = nowMs,
            updatedAtMs = nowMs,
        )
        val inserted = dao.insertFirstSeen(entity)
        if (inserted == -1L) {
            val raced = dao.get(nodeId) ?: error("File exchange pin race lost without row")
            check(raced.bindingSha256 == hash &&
                MessageDigest.isEqual(Base64.decode(raced.bindingBase64, Base64.NO_WRAP), binding)) {
                "Conflicting file exchange pin race"
            }
            return PinResult(nodeId, hash, raced.trustState, newlyPinned = false)
        }
        return PinResult(nodeId, hash, entity.trustState, newlyPinned = true)
    }

    /**
     * Забыть закреплённый ключ собеседника, чтобы принять новый.
     *
     * Зачем: ключ живёт до переустановки приложения. После переустановки у
     * собеседника новый ключ, а у нас закреплён старый - `pinFirstSeen` такой
     * HELLO отвергает, и обмен ключами заклинивает НАВСЕГДА. Снаружи это
     * выглядит как «в одну сторону доходит, обратно нет»: тот, у кого ключ
     * собеседника уже есть, шифровать может, а обратная сторона - нет.
     *
     * Отличить переустановку от подмены автоматически нельзя, поэтому сброс
     * никогда не происходит сам: его запрашивает пользователь, заново
     * отсканировав QR собеседника. Это осознанное подтверждение личности.
     */
    suspend fun forgetPin(nodeId: String): Boolean {
        require(nodeId.matches(Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")))
        return dao.deleteForContact(nodeId) > 0
    }

    suspend fun bindingFor(nodeId: String): ByteArray? {
        require(nodeId.matches(Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")))
        val entity = dao.get(nodeId) ?: return null
        val binding = Base64.decode(entity.bindingBase64, Base64.NO_WRAP)
        check(binding.size <= MAX_BINDING_BYTES && verifyFileExchangeBinding(binding))
        check(fileExchangeBindingNodeId(binding) == nodeId)
        check(sha256(binding) == entity.bindingSha256)
        return binding
    }

    /** Все закреплённые привязки — для разогрева кэша шифрования. */
    suspend fun allBindings(): List<Pair<String, ByteArray>> = dao.getAll().mapNotNull { entity ->
        runCatching {
            val binding = Base64.decode(entity.bindingBase64, Base64.NO_WRAP)
            check(binding.size <= MAX_BINDING_BYTES && verifyFileExchangeBinding(binding))
            check(fileExchangeBindingNodeId(binding) == entity.nodeId)
            entity.nodeId to binding
        }.getOrNull()
    }

    data class PinResult(
        val nodeId: String,
        val bindingSha256: String,
        val trustState: String,
        val newlyPinned: Boolean,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val MAX_BINDING_BYTES = 512
        private const val MAX_ENCODED_BINDING_CHARS = 700
    }
}
