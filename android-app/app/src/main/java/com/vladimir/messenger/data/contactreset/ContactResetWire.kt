package com.vladimir.messenger.data.contactreset

import java.util.Base64

/**
 * One-time address-book recovery hello.
 *
 * It deliberately rides inside the existing APUGRP1 namespace: old clients
 * already drop unknown group kinds instead of storing them as chat text. The
 * payload is public control data (short-lived signed token + public binding),
 * never a private message.
 */
object ContactResetWire {
    const val EPOCH = 1
    const val PREFIX = "APUGRP1|dir1|"
    const val MAX_WIRE_CHARS = 4096
    private const val MAX_NAME_CHARS = 160
    private const val MAX_TOKEN_BYTES = 512
    private const val MAX_BINDING_BYTES = 512
    private val NODE = Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")

    data class Hello(
        val epoch: Int,
        val nodeId: String,
        val displayName: String,
        val token: ByteArray,
        val binding: ByteArray,
    )

    fun isPacket(text: String?): Boolean =
        text != null && text.length <= MAX_WIRE_CHARS && text.startsWith(PREFIX)

    fun build(epoch: Int, nodeId: String, displayName: String, token: ByteArray, binding: ByteArray): String {
        require(epoch == EPOCH)
        require(NODE.matches(nodeId))
        require(displayName.length <= MAX_NAME_CHARS)
        require(token.size in 1..MAX_TOKEN_BYTES)
        require(binding.size in 1..MAX_BINDING_BYTES)
        val enc = Base64.getUrlEncoder().withoutPadding()
        return PREFIX + listOf(
            epoch.toString(),
            nodeId,
            enc.encodeToString(displayName.toByteArray(Charsets.UTF_8)),
            enc.encodeToString(token),
            enc.encodeToString(binding),
        ).joinToString("|")
    }

    fun parse(text: String?): Hello? {
        if (!isPacket(text)) return null
        val parts = text!!.split('|')
        if (parts.size != 7) return null
        return runCatching {
            val epoch = parts[2].toInt()
            val nodeId = parts[3]
            require(epoch == EPOCH && NODE.matches(nodeId))
            val dec = Base64.getUrlDecoder()
            val name = String(dec.decode(parts[4]), Charsets.UTF_8)
            val token = dec.decode(parts[5])
            val binding = dec.decode(parts[6])
            require(name.length <= MAX_NAME_CHARS)
            require(token.size in 1..MAX_TOKEN_BYTES)
            require(binding.size in 1..MAX_BINDING_BYTES)
            Hello(epoch, nodeId, name, token, binding)
        }.getOrNull()
    }
}
