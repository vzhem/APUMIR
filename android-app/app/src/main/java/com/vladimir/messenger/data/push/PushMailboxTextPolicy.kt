package com.vladimir.messenger.data.push

/**
 * Deliberately small v1 mailbox body language, not a general message classifier.
 * The existing app uses text fields for controls, attachments, location, invites,
 * forwarding and other cards. `ChatPreviews.isServicePacket` is not an allowlist.
 *
 * Permit a conservative subset of human text (letters, marks, numbers, common
 * punctuation, symbols/emoji, spaces, tabs and newlines). Reject all occurrences
 * of the application's APU marker family, wire separators, URL schemes and the
 * legacy forwarding arrow. This intentionally rejects some ordinary text too.
 * Unknown future formats require review before this policy can be used with them.
 *
 * Passing this check does NOT prove a Room row originated as plain text: sender
 * selection must separately exclude attachments, replies, styling, scheduled
 * messages, groups and other metadata, and revalidate the live row. Nor does it
 * authenticate a peer, prevent deleted-message resurrection or enable push.
 */
internal object PushMailboxTextPolicy {
    const val MAX_CODE_UNITS = 4096

    fun accepts(text: String): Boolean {
        if (text.isBlank() || text.length > MAX_CODE_UNITS || text.contains("APU", ignoreCase = true)) return false
        var offset = 0
        while (offset < text.length) {
            val ch = text[offset]
            if (Character.isHighSurrogate(ch)) {
                if (offset + 1 == text.length || !Character.isLowSurrogate(text[offset + 1])) return false
            } else if (Character.isLowSurrogate(ch)) return false
            val code = Character.codePointAt(text, offset)
            if (!allowed(code)) return false
            offset += Character.charCount(code)
        }
        return true
    }

    private fun allowed(code: Int): Boolean {
        if (code == ':'.code || code == '|'.code || code == 0x21a9) return false
        if (code == ' '.code || code == '\n'.code || code == '\t'.code || code == 0x200d) return true
        if (Character.isLetterOrDigit(code)) return true
        return when (Character.getType(code)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(), Character.MATH_SYMBOL.toInt(), Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(), Character.OTHER_SYMBOL.toInt() -> true
            else -> false
        }
    }
}
