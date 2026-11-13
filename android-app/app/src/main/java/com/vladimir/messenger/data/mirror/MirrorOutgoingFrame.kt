package com.vladimir.messenger.data.mirror

import android.content.Context
import org.json.JSONObject

/**
 * Opening a legacy MessageSealer envelope alone does not establish that a
 * paired device sent it. Check the same room-key/legacy-binding trust as
 * reply2 before an outgoing frame can create a chat or start a send.
 *
 * The outer event kind is not bound by this check, and valid frames can be
 * replayed. Older peers whose legacy binding differs may be unable to send
 * through this receiver. Neither this check nor the Room preflight is a
 * network, identity-change or deletion barrier.
 */
internal object MirrorOutgoingFrame {
    fun open(context: Context, nodeId: String, wire: String, openPayload: (String) -> JSONObject?): JSONObject? =
        checked(wire, openPayload) { MirrorReplyTrust.pairedAfterOpen(context, nodeId, it) }

    /** Trust is evaluated only after successful open, for the same wire. */
    fun <T : Any> checked(wire: String, openPayload: (String) -> T?, pairedAfterOpen: (String) -> Boolean): T? {
        val body = openPayload(wire) ?: return null
        return body.takeIf { pairedAfterOpen(wire) }
    }
}
