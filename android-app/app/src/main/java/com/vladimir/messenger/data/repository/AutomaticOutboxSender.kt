package com.vladimir.messenger.data.repository

import kotlinx.coroutines.CancellationException

/**
 * A clear tombstone is committed before Room cleanup, which may be delayed or
 * fail. Suppress automatic retries during that window without reporting a
 * network attempt or blocking other rows in the same chat.
 *
 * This is a preflight, not an atomic barrier: a tombstone can still arrive
 * after the check or while the native send is in progress.
 */
internal class AutomaticOutboxSender(
    private val chatContact: suspend (String) -> String?,
    private val isCleared: (String, String) -> Boolean,
    private val transport: suspend (String, String, String, String) -> Boolean,
    private val recordFailure: (Exception) -> Unit,
) {
    suspend fun send(messageId: String, chatId: String, peer: String, content: String): Boolean? {
        try {
            if (peer.isNotBlank() && isCleared(peer, messageId)) return null
            // A clear is recorded against the chat's contact, which may differ
            // from the saved recipient after a history move or contact change.
            // If the chat disappeared, its clear scope cannot be checked safely.
            val contact = chatContact(chatId) ?: return null
            if (contact.isNotBlank() && contact != peer && isCleared(contact, messageId)) return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordFailure(error)
            // No network attempt took place. Let the scan visit later rows and
            // retry this one in a future pass instead of blocking its chat.
            return null
        }
        // Transport errors belong to the caller's normal network handling.
        return transport(messageId, chatId, peer, content)
    }
}
