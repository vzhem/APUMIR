package com.vladimir.messenger.data.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The report may name a known failure, never quote one.
 *
 * `SERVICE_NOT_AVAILABLE` tells the owner "Google Play services are unreachable,
 * the network is fine". Quoting the exception text instead would carry project
 * identifiers and, on a bad day, a token fragment into a file the owner copies
 * into a chat.
 */
class PushTokenErrorCodeTest {

    @Test fun aKnownCodeIsNamed() {
        assertEquals(
            "SERVICE_NOT_AVAILABLE",
            PushTokenErrorCode.classify(IllegalStateException("SERVICE_NOT_AVAILABLE")),
        )
    }

    @Test fun theCodeIsFoundThroughTheWrappersFirebaseAdds() {
        val wrapped = RuntimeException(
            "wrapped",
            IllegalStateException("outer", RuntimeException("FIS_AUTH_ERROR")),
        )
        assertEquals("FIS_AUTH_ERROR", PushTokenErrorCode.classify(wrapped))
    }

    @Test fun aCodeWithExtraTextIsNotAccepted() {
        // Иначе всё, что угодно, могло бы проехать вместе с суффиксом.
        assertEquals(
            "unknown",
            PushTokenErrorCode.classify(
                IllegalStateException("SERVICE_NOT_AVAILABLE: see https://example.invalid/p?id=1"),
            ),
        )
    }

    @Test fun anUnknownMessageNeverReachesTheReport() {
        val secretish = IllegalStateException("token=abc123 project=apu-42")
        val code = PushTokenErrorCode.classify(secretish)
        assertEquals("unknown", code)
        assertFalse(code.contains("abc123"))
        assertFalse(code.contains("project"))
    }

    @Test fun noExceptionAtAllIsStillAValue() {
        assertEquals("unknown", PushTokenErrorCode.classify(null))
    }
}
