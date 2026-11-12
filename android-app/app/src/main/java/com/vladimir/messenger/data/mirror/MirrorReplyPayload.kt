package com.vladimir.messenger.data.mirror

import com.vladimir.messenger.data.reply.DirectReplyWire

/**
 * The outer mirror event kind is not bound to its ciphertext. A broker can
 * relabel reply2 as env, so a protected reply must not occupy env's legacy
 * text field: old receivers would otherwise apply it without the new checks.
 */
internal object MirrorReplyPayload {
    data class Fields(val kind: String, val legacyText: String, val replyText: String, val verifiedSource: Boolean)

    fun forSend(text: String, verifiedSource: Boolean): Fields? =
        if (DirectReplyWire.isPacket(text)) {
            if (!verifiedSource) null else Fields("reply2", "", text, true)
        } else {
            Fields("env", text, "", false)
        }

    fun forReceive(kind: String, legacyText: String, replyText: String): String? = when (kind) {
        "env" -> legacyText
        "reply2" -> replyText.takeIf { legacyText.isEmpty() && DirectReplyWire.isPacket(it) }
        else -> null
    }
}
