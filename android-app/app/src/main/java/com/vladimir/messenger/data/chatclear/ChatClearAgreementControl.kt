package com.vladimir.messenger.data.chatclear

import java.util.UUID

/** Non-destructive control frames for requesting and acknowledging a snapshot proposal.
 * Authentication and binding to an existing request are required separately by the caller.
 */
internal object ChatClearAgreementControl {
    private const val REQUEST = "APUDEL1|clearrequest3|"
    private const val RECEIPT = "APUDEL1|clearreceipt3|"
    private val hash = Regex("[0-9a-f]{64}")

    data class Receipt(val operation: UUID, val index: Int, val digest: String)

    fun request(operation: UUID): String = REQUEST + operation

    fun parseRequest(text: String): UUID? {
        if (!text.startsWith(REQUEST)) return null
        return parseUuid(text.removePrefix(REQUEST))
    }

    fun receipt(value: Receipt): String {
        require(value.index in 0 until ChatClearAgreementWire.MAX_PAGES && hash.matches(value.digest))
        return "$RECEIPT${value.operation}.${value.index}.${value.digest}"
    }

    fun parseReceipt(text: String): Receipt? {
        if (text.length > 160 || !text.startsWith(RECEIPT)) return null
        val parts = text.removePrefix(RECEIPT).split('.')
        if (parts.size != 3) return null
        val operation = parseUuid(parts[0]) ?: return null
        val index = parts[1].toIntOrNull() ?: return null
        if (index.toString() != parts[1] || index !in 0 until ChatClearAgreementWire.MAX_PAGES || !hash.matches(parts[2])) return null
        return Receipt(operation, index, parts[2])
    }

    private fun parseUuid(raw: String): UUID? = ChatClearWire.decodeAck("APUDEL1|clearack2|$raw")
}
