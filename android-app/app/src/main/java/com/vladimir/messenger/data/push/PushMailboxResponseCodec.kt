package com.vladimir.messenger.data.push

/**
 * Strict bounded subset of JSON emitted by the mailbox Worker. Its values are
 * ASCII hex/Base64, integers and booleans, so escaped strings and unknown fields
 * are rejected rather than interpreted. Duplicate keys, oversized/deep objects
 * and ambiguous cursors fail closed. This does not authenticate a response.
 */
internal object PushMailboxResponseCodec {
    class Put(val state: String, val expiresAt: Long, val wakeQueued: Boolean) {
        override fun toString(): String = "PushMailboxPutResponse(redacted)"
    }

    class Page(val items: List<PushMailboxItem>, val next: String?) {
        override fun toString(): String = "PushMailboxPage(redacted)"
    }

    fun put(bytes: ByteArray): Put? {
        val root = parse(bytes) ?: return null
        if (root.keys != setOf("state", "expiresAt", "wakeQueued")) return null
        val state = root["state"] as? String ?: return null
        val expires = root["expiresAt"] as? Long ?: return null
        val queued = root["wakeQueued"] as? Boolean ?: return null
        if (state !in setOf("stored", "handled") || !PushMailboxProtocol.validTimestamp(expires) ||
            state == "handled" && queued
        ) return null
        return Put(state, expires, queued)
    }

    fun page(bytes: ByteArray): Page? {
        val root = parse(bytes) ?: return null
        if (root.keys != setOf("items", "next")) return null
        val array = root["items"] as? List<*> ?: return null
        if (array.size > PushMailboxProtocol.MAX_PAGE_ITEMS) return null
        val result = ArrayList<PushMailboxItem>(array.size)
        val seen = HashSet<String>()
        for (value in array) {
            val item = value as? Map<*, *> ?: return null
            if (item.keys != setOf("id", "envelope", "expiresAt", "receipt")) return null
            val id = item["id"] as? String ?: return null
            val envelope = item["envelope"] as? String ?: return null
            val expires = item["expiresAt"] as? Long ?: return null
            val receipt = item["receipt"] as? String ?: return null
            if (!PushMailboxProtocol.validId(id) || !seen.add(id) || !PushMailboxProtocol.validId(receipt) ||
                !PushMailboxProtocol.validTimestamp(expires) || !PushMailboxProtocol.validEnvelope(envelope)
            ) return null
            result.add(PushMailboxItem(id, envelope, expires, receipt))
        }
        val next = root["next"]
        if (result.isEmpty()) {
            if (next != null) return null
        } else if (next !is String || next != result.last().id) return null
        return Page(result, next as? String)
    }

    private fun parse(bytes: ByteArray): Map<*, *>? {
        if (bytes.isEmpty() || bytes.size > PushMailboxProtocol.MAX_RESPONSE_BYTES || bytes.any { it.toInt() and 0x80 != 0 }) return null
        return try {
            Reader(bytes.toString(Charsets.US_ASCII)).root()
        } catch (_: InvalidJson) {
            // Invalid attacker-controlled responses are ordinary rejections.
            null
        }
    }

    private class InvalidJson : RuntimeException(null, null, false, false)

    private class Reader(private val input: String) {
        private var pos = 0

        fun root(): Map<*, *> {
            val value = value(0) as? Map<*, *> ?: throw InvalidJson()
            space()
            if (pos != input.length) throw InvalidJson()
            return value
        }

        private fun value(depth: Int): Any? {
            space()
            if (depth > 3 || pos >= input.length) throw InvalidJson()
            return when (input[pos]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                in '0'..'9' -> numberValue()
                else -> throw InvalidJson()
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            if (depth > 3) throw InvalidJson()
            pos++
            val result = LinkedHashMap<String, Any?>()
            space()
            if (take('}')) return result
            while (true) {
                space()
                if (pos >= input.length || input[pos] != '"') throw InvalidJson()
                val key = stringValue()
                if (key.length > 32 || result.containsKey(key) || result.size >= 4) throw InvalidJson()
                space()
                if (!take(':')) throw InvalidJson()
                result[key] = value(depth)
                space()
                if (take('}')) return result
                if (!take(',')) throw InvalidJson()
            }
        }

        private fun arrayValue(depth: Int): List<Any?> {
            if (depth > 3) throw InvalidJson()
            pos++
            val result = ArrayList<Any?>()
            space()
            if (take(']')) return result
            while (true) {
                if (result.size >= PushMailboxProtocol.MAX_PAGE_ITEMS) throw InvalidJson()
                result.add(value(depth))
                space()
                if (take(']')) return result
                if (!take(',')) throw InvalidJson()
            }
        }

        private fun stringValue(): String {
            if (!take('"')) throw InvalidJson()
            val start = pos
            while (pos < input.length && input[pos] != '"') {
                if (input[pos] < ' ' || input[pos] > '~' || input[pos] == '\\' || pos - start >= PushMailboxProtocol.MAX_ENVELOPE_CHARS) {
                    throw InvalidJson()
                }
                pos++
            }
            if (pos >= input.length) throw InvalidJson()
            val result = input.substring(start, pos)
            pos++
            return result
        }

        private fun numberValue(): Long {
            val start = pos
            if (input[pos] == '0') {
                pos++
                if (pos < input.length && input[pos] in '0'..'9') throw InvalidJson()
            } else {
                while (pos < input.length && input[pos] in '0'..'9') {
                    if (pos - start >= 16) throw InvalidJson()
                    pos++
                }
            }
            return input.substring(start, pos).toLongOrNull() ?: throw InvalidJson()
        }

        private fun literal(expected: String, value: Any?): Any? {
            if (!input.regionMatches(pos, expected, 0, expected.length)) throw InvalidJson()
            pos += expected.length
            return value
        }

        private fun space() {
            while (pos < input.length && input[pos] in charArrayOf(' ', '\t', '\r', '\n')) pos++
        }

        private fun take(ch: Char): Boolean =
            if (pos < input.length && input[pos] == ch) { pos++; true } else false
    }
}
