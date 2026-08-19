package com.vladimir.messenger.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Strict transport codec for signed referral tokens.
 *
 * This class only decodes bounded URL-safe bytes. Trust is granted separately by
 * Rust `verifyReferralInviteToken`; successfully parsing a URL never attributes a referral.
 */
object ReferralInviteLink {
    const val OFFICIAL_HOST = "apumir.app"
    const val TOKEN_PARAMETER = "r"
    const val MAX_TOKEN_BYTES = 512
    private const val MAX_LINK_CHARS = 1_024
    private val tokenText = Regex("^[A-Za-z0-9_-]+$")
    private val invitePath = Regex("^/i(?:/[A-Za-z0-9_-]{1,64})?/?$")

    data class Parsed(
        val token: ByteArray,
        val original: String,
    )

    fun create(token: ByteArray): String {
        require(token.isNotEmpty() && token.size <= MAX_TOKEN_BYTES) { "Invalid referral token size" }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(token)
        return "https://$OFFICIAL_HOST/i?$TOKEN_PARAMETER=$encoded"
    }

    fun parse(raw: String?): Parsed? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_LINK_CHARS) return null
        return try {
            val uri = URI(text)
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                !uri.host.equals(OFFICIAL_HOST, ignoreCase = true) ||
                uri.userInfo != null ||
                (uri.port != -1 && uri.port != 443) ||
                uri.fragment != null ||
                !invitePath.matches(uri.path.orEmpty())
            ) return null

            val values = parseQuery(uri.rawQuery)[TOKEN_PARAMETER] ?: return null
            if (values.size != 1) return null
            val encoded = values.single()
            if (encoded.isEmpty() || encoded.contains('=') || !tokenText.matches(encoded)) return null
            val token = Base64.getUrlDecoder().decode(encoded)
            if (token.isEmpty() || token.size > MAX_TOKEN_BYTES) return null
            Parsed(token.copyOf(), text)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseQuery(raw: String?): Map<String, List<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&')
            .filter { it.isNotEmpty() }
            .map {
                decode(it.substringBefore('=', it)) to decode(it.substringAfter('=', ""))
            }
            .groupBy({ it.first }, { it.second })
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
