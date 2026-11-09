package com.vladimir.messenger.data.chatclear

import java.util.UUID

/**
 * Collects one authenticated peer's immutable proposal. No deletion occurs here.
 * The caller must show/obtain a separate confirmation after the snapshot was captured.
 * This in-memory coordinator is not yet suitable for offline delivery or process restart.
 */
internal class ChatClearAgreement(private val owner: String, private val peer: String, private val operation: UUID) {
    private var total: Int? = null
    private var digest: String? = null
    private val parts = mutableMapOf<Int, List<String>>()

    init { require(owner.isNotBlank() && peer.isNotBlank() && owner != peer) }

    fun accept(currentOwner: String, sender: String, authenticated: Boolean, text: String): Boolean {
        if (!authenticated || currentOwner != owner || sender != peer) return false
        val page = ChatClearAgreementWire.decode(text) ?: return false
        if (page.operation != operation) return false
        if (total != null && (page.total != total || page.digest != digest)) return false
        val previous = parts[page.index]
        if (previous != null) return previous == page.ids
        total = page.total
        digest = page.digest
        parts[page.index] = page.ids.toList()
        return true
    }

    /** The preview is available only after every page and the snapshot digest match. */
    fun preview(currentOwner: String): List<String>? {
        if (currentOwner != owner) return null
        val count = total ?: return null
        if (parts.size != count) return null
        val ids = (0 until count).flatMap { parts[it] ?: return null }
        if (ids.distinct().size != ids.size || ids != ids.sorted()) return null
        if (ChatClearAgreementWire.digest(ids) != digest) return null
        return ids.toList()
    }

    /** Must be called only after the user confirms the presented complete snapshot. */
    fun approve(currentOwner: String, confirmed: Boolean): List<String>? =
        if (confirmed) preview(currentOwner) else null
}
