package com.vladimir.messenger.data.security

import java.util.UUID

/** Only replaceable control, never chat/ref/rank/delete/scheduled messages. */
object EphemeralControl {
    fun isReplaceable(text: String): Boolean {
        // Periodic game snapshots are disposable; never grow the durable chat backlog.
        if (text.startsWith("APUTYP1|APUGAME1|")) return true
        if (text == "APUTYP1|0" || text == "APUTYP1|1") return true
        if (text.startsWith("APUGRP1|typ|")) {
            val parts = text.split('|', limit = 6)
            return parts.size == 5 && parts[4] in setOf("0", "1")
        }
        val parts = text.split('|', limit = 3)
        return parts.size >= 2 && parts[0] in setOf("APUGIF1", "APUSTK1") &&
            parts[1] in setOf("ask", "have", "want", "thumb", "thmb")
    }

    /** Stable for retry of the same caller ID/recipient; private body is not hashed/exported. */
    fun transportId(id: String, peer: String, text: String): String = if (isReplaceable(text))
        "eph1-" + UUID.nameUUIDFromBytes(("apu-control:$peer:$id").toByteArray(Charsets.UTF_8)) else id
}
