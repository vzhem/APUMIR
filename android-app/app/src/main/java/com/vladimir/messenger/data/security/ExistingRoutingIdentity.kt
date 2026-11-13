package com.vladimir.messenger.data.security

import com.vladimir.messenger.util.NodeIds

/**
 * Recover the legacy routing ID without silently generating a different one.
 *
 * The old FFI CryptoManager's private-key argument is an opaque non-empty
 * placeholder, not the Ed25519 signing seed or file-encryption key. Earlier
 * Android versions deliberately saved the public routing ID in that slot.
 * If only that placeholder is missing, recreating it preserves the route;
 * actual cryptographic sidecars are still loaded and verified independently.
 * Conflicting or malformed saved IDs are never guessed or overwritten.
 */
internal object ExistingRoutingIdentity {
    data class Resolved(val nodeId: String, val legacyPrivateKey: String, val usedPlaceholder: Boolean)

    fun resolve(
        nodeId: String?,
        publicKey: String?,
        existingPublicKey: String?,
        existingPrivateKey: String?,
    ): Resolved? {
        val candidates = listOf(nodeId, publicKey, existingPublicKey)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        if (candidates.isEmpty() || candidates.any { !NodeIds.isNodeId(it) }) return null
        val unique = candidates.distinct()
        if (unique.size != 1) return null
        val id = unique.single()
        val privateKey = existingPrivateKey?.takeIf { it.isNotBlank() }
        return Resolved(id, privateKey ?: id, usedPlaceholder = privateKey == null)
    }

    fun matchesRunning(expected: String?, actual: String?): Boolean =
        expected.isNullOrBlank() || expected == actual

    /** An absent sidecar means legacy-only; a different installed sidecar is unsafe. */
    fun matchesInstalled(expected: String?, installed: String?): Boolean =
        expected.isNullOrBlank() || installed == null || expected == installed
}
