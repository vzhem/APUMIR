package com.vladimir.messenger.data.push

/**
 * Plaintext INSIDE a successfully authenticated mailbox envelope. It binds the
 * original Room message id and both node ids, so the outside mailbox id cannot
 * relabel a message or be used as a cross-transport deduplication key.
 *
 * This codec is not encryption, authentication, persistence or a router. A
 * caller must obtain the sender id from an independently pinned binding, the
 * recipient id from the live local identity, authenticate the whole envelope,
 * and coordinate identity/restore changes with the eventual database commit.
 * The returned content must not be passed blindly to a generic control router.
 * Strings contain plaintext and cannot be reliably erased on the JVM.
 */
internal object PushMailboxMessageCodec {
    // Existing clients that understand typing packets filter this reserved
    // family rather than displaying the wrapper if it escapes onto a legacy
    // transport. Older-client compatibility is not guaranteed.
    const val PREFIX = "APUTYP1|APUPUSH1|msg|"
    const val MAX_PLAINTEXT_BYTES = 64 * 1024
    private const val MAX_NUMBER = 9_007_199_254_740_991L
    private val MESSAGE_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val NODE = Regex("^pk_[0-9a-f]{32}([0-9a-f]{32})?$")

    class Message internal constructor(
        val id: String,
        val sender: String,
        val recipient: String,
        val sentAtMs: Long,
        val content: String,
    ) {
        override fun toString(): String = "PushMailboxMessage(redacted)"
    }

    /** Create only canonical text suitable for sealing; never send it unsealed. */
    fun encode(id: String, sender: String, recipient: String, sentAtMs: Long, content: String): String? {
        if (!validPeers(sender, recipient) || !validId(id) || sentAtMs !in 1..MAX_NUMBER || content.isEmpty()) return null
        val contentBytes = utf8Size(content) ?: return null
        val header = "$PREFIX$id|$sender|$recipient|$sentAtMs|$contentBytes|"
        if (header.length + contentBytes > MAX_PLAINTEXT_BYTES) return null
        return header + content
    }

    /**
     * [expectedSender] comes from the previously pinned peer, not the envelope;
     * [expectedRecipient] comes from the current installation, not the server.
     */
    fun parse(text: String, expectedSender: String, expectedRecipient: String): Message? {
        if (text.length > MAX_PLAINTEXT_BYTES || !text.startsWith(PREFIX) ||
            !validPeers(expectedSender, expectedRecipient)
        ) return null
        var start = PREFIX.length
        fun field(): String? {
            val end = text.indexOf('|', start)
            if (end < 0) return null
            val result = text.substring(start, end)
            start = end + 1
            return result
        }
        val id = field() ?: return null
        val sender = field() ?: return null
        val recipient = field() ?: return null
        val timeText = field() ?: return null
        val lengthText = field() ?: return null
        if (!validId(id) || sender != expectedSender || recipient != expectedRecipient) return null
        val sentAtMs = number(timeText) ?: return null
        val declaredLength = number(lengthText) ?: return null
        if (declaredLength > MAX_PLAINTEXT_BYTES) return null
        val content = text.substring(start)
        val actualLength = utf8Size(content) ?: return null
        if (content.isEmpty() || actualLength.toLong() != declaredLength ||
            start + actualLength > MAX_PLAINTEXT_BYTES
        ) return null
        return Message(id, sender, recipient, sentAtMs, content)
    }

    private fun validId(value: String): Boolean = MESSAGE_ID.matches(value) && value.any { it in '1'..'9' || it in 'a'..'f' }
    internal fun validPeers(sender: String, recipient: String): Boolean =
        sender != recipient && NODE.matches(sender) && NODE.matches(recipient)

    private fun number(value: String): Long? {
        if (value.isEmpty() || value.length > 16 || value[0] !in '1'..'9' || value.any { it !in '0'..'9' }) return null
        return value.toLongOrNull()?.takeIf { it in 1..MAX_NUMBER }
    }

    /** Strict UTF-16 -> UTF-8 size without allocating an additional plaintext buffer. */
    private fun utf8Size(value: String): Int? {
        if (value.length > MAX_PLAINTEXT_BYTES) return null
        var size = 0
        var i = 0
        while (i < value.length) {
            val code = value[i].code
            size += when {
                code < 0x80 -> 1
                code < 0x800 -> 2
                code in 0xd800..0xdbff -> {
                    if (i + 1 >= value.length || value[i + 1].code !in 0xdc00..0xdfff) return null
                    i++
                    4
                }
                code in 0xdc00..0xdfff -> return null
                else -> 3
            }
            if (size > MAX_PLAINTEXT_BYTES) return null
            i++
        }
        return size
    }
}
