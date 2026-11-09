package com.vladimir.messenger.data.chatclear

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatClearAgreementControlTest {
    @Test fun requestAndReceiptHaveCanonicalBoundedEncoding() {
        val operation = UUID.randomUUID()
        val request = ChatClearAgreementControl.request(operation)
        val receipt = ChatClearAgreementControl.Receipt(operation, 3, "a".repeat(64))
        val encoded = ChatClearAgreementControl.receipt(receipt)
        assertEquals(operation, ChatClearAgreementControl.parseRequest(request))
        assertEquals(receipt, ChatClearAgreementControl.parseReceipt(encoded))
        assertEquals(3, request.split('|').size)
        assertEquals(3, encoded.split('|').size)
        assertNull(ChatClearAgreementControl.parseRequest(request.uppercase()))
        assertNull(ChatClearAgreementControl.parseRequest(request + "x"))
        assertNull(ChatClearAgreementControl.parseReceipt(encoded.replace(".3.", ".03.")))
        assertNull(ChatClearAgreementControl.parseReceipt(encoded.uppercase()))
        assertNull(ChatClearAgreementControl.parseReceipt(encoded + "x"))
        assertNull(ChatClearAgreementControl.parseReceipt("x".repeat(10000)))
    }
}
