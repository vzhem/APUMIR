package com.vladimir.messenger.data.push

/**
 * Fixed vocabulary for "why did the provider token never arrive".
 *
 * The owner's report from phone B showed `гейт=открыт · приём=on · токен=нет ·
 * фаза=failed` — everything looked healthy except that no token was ever
 * obtained, and the report could not say why. The failure was logged as the
 * constant string "token request failed" precisely because an exception message
 * from Firebase can contain project identifiers, so it must never be copied.
 *
 * This maps only *known Firebase error codes* (public constants such as
 * `SERVICE_NOT_AVAILABLE`) to short names. Anything else becomes `unknown`, so a
 * message can never smuggle an identifier, a token or a URL into a report.
 */
internal object PushTokenErrorCode {
    /** Codes Firebase itself raises for token acquisition; public, not secret. */
    private val NAMES = setOf(
        "SERVICE_NOT_AVAILABLE",
        "AUTHENTICATION_FAILED",
        "TOO_MANY_REGISTRATIONS",
        "FIS_AUTH_ERROR",
        "MISSING_INSTANCEID_SERVICE",
        "INVALID_SENDER_ID",
        "THIRD_PARTY_AUTH_ERROR",
        "UNREGISTERED_ON_API_CONSOLE",
        "DEVICE_MESSAGE_RATE_TOO_HIGH",
        "UNAVAILABLE",
    )

    /**
     * Classifies the failure by walking the cause chain, because Firebase wraps
     * its code in one or two `ExecutionException`s. Only an exact match counts:
     * `"SERVICE_NOT_AVAILABLE"` is taken, `"SERVICE_NOT_AVAILABLE: see <url>"`
     * is not, so no suffix can leak through.
     */
    fun classify(error: Throwable?): String {
        var current = error
        var depth = 0
        while (current != null && depth < 6) {
            val name = current.message?.trim()
            if (name != null && name in NAMES) return name
            current = current.cause
            depth++
        }
        return "unknown"
    }
}
