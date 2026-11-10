package com.vladimir.messenger.data.push

import java.security.MessageDigest
import java.util.concurrent.CancellationException
import uniffi.p2p_core.derivePushWakeMaterial
import uniffi.p2p_core.signPushWakeRegistration

/**
 * Small adapter from the authenticated Rust push primitives to the strict HTTP
 * types. No networking or token registration happens here. The caller must pass
 * the previously established peer pin and revalidate pin and local identity
 * immediately before and after every asynchronous network effect.
 *
 * Strings created for the existing HTTP API cannot be zeroized; no returned
 * value is safe to log, save in a backup or pass to a different recipient.
 */
internal class PushWakeCrypto(
    private val derive: (ByteArray, ByteArray, ByteArray, ByteArray, Boolean) -> ByteArray = ::derivePushWakeMaterial,
    private val sign: (ByteArray, ByteArray, ByteArray, ULong, ULong, ByteArray, Boolean) -> ByteArray = ::signPushWakeRegistration,
    private val recordFailure: () -> Unit,
) {
    fun derive(
        localBinding: ByteArray,
        pinnedPeerBinding: ByteArray,
        localSecret: ByteArray,
        epochHex: String,
        localIsSender: Boolean,
    ): Material? {
        if (localBinding.size !in 1..512 || pinnedPeerBinding.size !in 1..512 ||
            localSecret.size != 32 || localSecret.all { it == 0.toByte() } ||
            localBinding.contentEquals(pinnedPeerBinding)
        ) return null
        val epoch = decodeHex(epochHex, 16) ?: return null
        if (epoch.all { it == 0.toByte() }) { epoch.fill(0); return null }
        val local = localBinding.copyOf()
        val peer = pinnedPeerBinding.copyOf()
        val secret = localSecret.copyOf()
        var output: ByteArray? = null
        return try {
            output = derive(local, peer, secret, epoch, localIsSender)
            if (output.size != 64 || !hasNonZero(output, 0, 32) || !hasNonZero(output, 32, 64)) return null
            Material(
                owner = output.copyOfRange(0, 32), capability = output.copyOfRange(32, 64),
                localHash = PushEpochProtocol.bindingHash(local), peerHash = PushEpochProtocol.bindingHash(peer),
                epochHash = sha256(epoch), localIsSender = localIsSender, signer = sign, recordFailure = recordFailure,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No exception text: FFI errors could contain sensitive inputs.
            recordFailure()
            null
        } finally {
            output?.fill(0)
            epoch.fill(0)
            local.fill(0)
            peer.fill(0)
            secret.fill(0)
        }
    }

    /** Owns capability bytes until closed. Do not share across concurrent calls. */
    class Material internal constructor(
        private val owner: ByteArray,
        private val capability: ByteArray,
        private val localHash: String,
        private val peerHash: String,
        private val epochHash: ByteArray,
        private val localIsSender: Boolean,
        private val signer: (ByteArray, ByteArray, ByteArray, ULong, ULong, ByteArray, Boolean) -> ByteArray,
        private val recordFailure: () -> Unit,
    ) : AutoCloseable {
        private var closed = false

        /** A route is a hash, not the capability and not proof of delivery. */
        fun routeHex(): String? {
            if (closed) return null
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(ROUTE_DOMAIN)
            digest.update(owner)
            digest.update(capability)
            return hex(digest.digest())
        }

        fun wakeRequest(): PushWakeRequest? = if (!closed && localIsSender) {
            PushWakeRequest(hex(owner), hex(capability))
        } else null

        /**
         * Sign only the matching, durably reserved operation for this receiver.
         * Revoke must use an empty token. This doesn't prove server acceptance.
         */
        fun registrationRequest(
            localBinding: ByteArray,
            state: PushRegistrationState.State,
            issuedAtSeconds: Long,
            token: String,
        ): PushRegistrationRequest? {
            if (closed || localIsSender || localBinding.size !in 1..512 ||
                PushEpochProtocol.bindingHash(localBinding) != localHash ||
                state.ownerHash != localHash || state.peerHash != peerHash ||
                state.sequence !in 1..PushRegistrationState.MAX_NUMBER ||
                issuedAtSeconds !in 1..PushRegistrationState.MAX_NUMBER
            ) return null
            val revoke = when {
                state.phase == PushRegistrationState.Phase.ACTIVE && state.reservation == PushRegistrationState.Reservation.REGISTER -> false
                state.phase == PushRegistrationState.Phase.REVOKING && state.reservation == PushRegistrationState.Reservation.REVOKE -> true
                else -> return null
            }
            if ((revoke && token.isNotEmpty()) || (!revoke && !TOKEN.matches(token))) return null
            val epoch = decodeHex(state.epochHex, 16) ?: return null
            val device = decodeHex(state.deviceHex, 16) ?: run { epoch.fill(0); return null }
            val tokenBytes = token.toByteArray(Charsets.UTF_8)
            val binding = localBinding.copyOf()
            val capabilityCopy = capability.copyOf()
            var output: ByteArray? = null
            return try {
                val currentEpochHash = sha256(epoch)
                val epochMatches = try { MessageDigest.isEqual(epochHash, currentEpochHash) } finally { currentEpochHash.fill(0) }
                if (!epochMatches) return null
                output = signer(binding, capabilityCopy, device, state.sequence.toULong(), issuedAtSeconds.toULong(), tokenBytes, revoke)
                if (output.size != 96 || !MessageDigest.isEqual(owner, output.copyOfRange(0, 32)) ||
                    !hasNonZero(output, 32, 96)
                ) return null
                PushRegistrationRequest(
                    action = if (revoke) "revoke" else "register", owner = hex(owner), capability = hex(capability),
                    device = state.deviceHex, sequence = state.sequence, issuedAt = issuedAtSeconds,
                    token = token, signature = hex(output.copyOfRange(32, 96)),
                ).takeIf { it.isValid() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Report only a generic count; never log token, capability or FFI exception text.
                recordFailure()
                null
            } finally {
                output?.fill(0)
                epoch.fill(0)
                device.fill(0)
                tokenBytes.fill(0)
                binding.fill(0)
                capabilityCopy.fill(0)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            owner.fill(0)
            capability.fill(0)
            epochHash.fill(0)
        }

        override fun toString(): String = "PushWakeMaterial(redacted)"
    }

    companion object {
        private val ROUTE_DOMAIN = "apu-push-route-v1\u0000".toByteArray(Charsets.US_ASCII)
        private val TOKEN_DOMAIN = "apu-push-token-fingerprint-v1\u0000".toByteArray(Charsets.US_ASCII)
        private val TOKEN = Regex("^[A-Za-z0-9_:.-]{20,2048}$")
        private val HEX = Regex("^[0-9a-f]+$")

        /** A local encrypted-journal fingerprint, not the token itself. */
        fun tokenFingerprint(token: String): String? {
            if (!TOKEN.matches(token)) return null
            val bytes = token.toByteArray(Charsets.UTF_8)
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(TOKEN_DOMAIN)
                hex(digest.digest(bytes))
            } finally { bytes.fill(0) }
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun hasNonZero(bytes: ByteArray, start: Int, end: Int): Boolean =
            (start until end).any { bytes[it] != 0.toByte() }
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun decodeHex(text: String, length: Int): ByteArray? {
            if (text.length != length * 2 || !HEX.matches(text)) return null
            return ByteArray(length) { index -> text.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }
    }
}
