package com.vladimir.messenger.service

import com.vladimir.messenger.data.local.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryReceiptCoordinatorTest {
    private fun row(
        mine: Boolean = false,
        sender: String = "pk_sender",
        chat: String = "chat",
        status: String = "DELIVERED",
    ) = MessageEntity(id = "m1", chatId = chat, senderId = sender, content = "text", timestamp = 1, isFromMe = mine, status = status)

    @Test fun textAckRequiresInsertedOrMatchingIncomingRow() = runBlocking {
        var lookups = 0
        assertTrue(storedIncomingText("m1", "pk_sender", "chat", true) { lookups++; null })
        assertEquals(0, lookups)
        assertTrue(storedIncomingText("m1", "pk_sender", "chat", false) { row() })
        assertFalse(storedIncomingText("m1", "pk_sender", "chat", false) { null })
        assertFalse(storedIncomingText("m1", "pk_sender", "chat", false) { row(mine = true) })
        assertFalse(storedIncomingText("m1", "pk_sender", "chat", false) { row(sender = "pk_other") })
        assertFalse(storedIncomingText("m1", "pk_sender", "chat", false) { row(chat = "other") })
    }

    @Test fun aServiceFrameIsAcknowledgedWithoutAChatRow() = runBlocking {
        var lookups = 0
        fun matched(result: Boolean): () -> Boolean = { result }
        // Пакет истории: строки в переписке не будет никогда, зато без
        // подтверждения его копия жила бы неделю в резерве переписки.
        assertTrue(storedIncomingText("eph1-abc", "pk_sender", "chat", false, serviceFrame = true) { lookups++; null })
        assertEquals(0, lookups)
        val flags = mutableListOf<Boolean>()
        assertTrue(acknowledgeStoredIncomingText("eph1-abc", "pk_sender", "chat", false, serviceFrame = true, load = { null }, openedByExpectedSender = matched(true), sendAck = flags::add))
        assertEquals(listOf(true), flags)
        // Обычный кадр без строки по-прежнему молчит: правило ужесточено только
        // для служебных.
        assertFalse(acknowledgeStoredIncomingText("eph1-abc", "pk_sender", "chat", false, load = { null }, openedByExpectedSender = matched(true), sendAck = flags::add))
    }

    @Test fun onlyATransportIdOfAServicePacketCountsAsAFrameWithoutRow() {
        val story = "APUSTORY1:{\"id\":\"s1\",\"kind\":\"publish\",\"text\":\"hi\"}"
        assertTrue(serviceFrameWithoutRow("eph1-00000000-0000-0000-0000-000000000000", story))
        assertTrue(serviceFrameWithoutRow("eph1-00000000-0000-0000-0000-000000000000", "APUTYP1|1"))
        // Обычный UUID истории (старый клиент) - как прежде, без подтверждения.
        assertFalse(serviceFrameWithoutRow("39513698-1234-4abc-8def-000000000000", story))
        // Отметку транспорта может надеть кто угодно: служебность подтверждает тело.
        assertFalse(serviceFrameWithoutRow("eph1-00000000-0000-0000-0000-000000000000", "привет, как дела"))
        assertFalse(serviceFrameWithoutRow("eph1-not-a-uuid", story))
    }

    @Test fun meshReceiptNeedsStoredTextAndOpenedSenderMatchButLegacyAckRemains() = runBlocking {
        val flags = mutableListOf<Boolean>()
        var checks = 0
        fun matched(result: Boolean): () -> Boolean = { checks++; result }
        assertFalse(acknowledgeStoredIncomingText("m1", "pk_sender", "chat", false, load = { null }, openedByExpectedSender = matched(true), sendAck = flags::add))
        assertFalse(acknowledgeStoredIncomingText("m1", "pk_sender", "chat", false, load = { row(sender = "pk_other") }, openedByExpectedSender = matched(true), sendAck = flags::add))
        assertEquals(0, checks)
        assertTrue(acknowledgeStoredIncomingText("m1", "pk_sender", "chat", true, load = { null }, openedByExpectedSender = matched(false), sendAck = flags::add))
        assertTrue(acknowledgeStoredIncomingText("m1", "pk_sender", "chat", false, load = { row() }, openedByExpectedSender = matched(true), sendAck = flags::add))
        assertEquals(listOf(false, true), flags)
        assertEquals(2, checks)
    }

    @Test fun selfEchoNeverAcknowledgesAnAckReplySidecarOrUnopenedSealedPacket() {
        assertFalse(canAcknowledgeSelfEcho(true, null))
        assertFalse(canAcknowledgeSelfEcho(true, "ack|message"))
        assertFalse(canAcknowledgeSelfEcho(false, "  ack|  "))
        assertFalse(canAcknowledgeSelfEcho(true, "APUDMR1|anything"))
        assertTrue(canAcknowledgeSelfEcho(true, "ordinary text"))
        assertTrue(canAcknowledgeSelfEcho(false, "ordinary text"))
    }

    @Test fun cfDuplicateRequiresMatchingStoredSenderAndOpenedSignerWhenSealed() {
        var checks = 0
        fun matched(value: Boolean): () -> Boolean = { checks++; value }
        assertFalse(canAcknowledgeCfDuplicate(true, "peer", "peer", true, matched(true)))
        assertFalse(canAcknowledgeCfDuplicate(false, "other", "peer", true, matched(true)))
        assertEquals(0, checks)
        assertFalse(canAcknowledgeCfDuplicate(false, "peer", "peer", true, matched(false)))
        assertTrue(canAcknowledgeCfDuplicate(false, "peer", "peer", true, matched(true)))
        assertEquals(2, checks)
        // Old unsealed messages remain compatible, but are not authenticated.
        assertTrue(canAcknowledgeCfDuplicate(false, "peer", "peer", false, matched(false)))
        assertEquals(2, checks)
    }

    @Test fun cfDuplicateClassificationNeverAcknowledgesCurrentOrPreviouslyStoredAck() {
        fun classify(stored: String, incoming: String, outgoing: Boolean = false,
                     sender: String = "pk_sender", sealed: Boolean = true, signerMatches: Boolean = true) =
            classifyCfDuplicate(outgoing, sender, stored, "pk_sender", incoming, sealed) { signerMatches }
        assertEquals(CfDuplicateAction.AcknowledgeText, classify("stored text", "repeat text"))
        assertEquals(CfDuplicateAction.AcknowledgeText, classify("stored text", "repeat text", sealed = false, signerMatches = false))
        assertEquals(CfDuplicateAction.Ignore, classify("stored text", "repeat text", outgoing = true))
        assertEquals(CfDuplicateAction.Ignore, classify("stored text", "repeat text", sender = "pk_other"))
        assertEquals(CfDuplicateAction.Ignore, classify("stored text", "repeat text", signerMatches = false))
        assertEquals(CfDuplicateAction.ApplyAck("one"), classify("stored text", "ack|one"))
        assertEquals(CfDuplicateAction.ApplyAck("two"), classify("ack|two", "repeat text"))
        assertEquals(CfDuplicateAction.ApplyAck("new"), classify("ack|old", "ack|new"))
        assertEquals(CfDuplicateAction.ApplyAck(null), classify("stored text", "ack|"))
        assertEquals(CfDuplicateAction.Ignore, classify("ack|one", "repeat text", signerMatches = false))
    }

    @Test fun nativeCleanupFollowsCommitAndDuplicateAckRepairsCrashWindow() = runBlocking {
        val released = mutableListOf<String>()
        var lookups = 0
        val changed = applyOutgoingDeliveryAck("m1", { true }, { lookups++; null }, released::add)
        assertTrue(changed)
        assertEquals(0, lookups)
        // Room already has DELIVERED after a process died before native cleanup.
        assertFalse(applyOutgoingDeliveryAck("m1", { false }, { row(mine = true) }, released::add))
        assertFalse(applyOutgoingDeliveryAck("m1", { false }, { row(mine = true, status = "READ") }, released::add))
        assertEquals(listOf("m1", "m1", "m1"), released)
    }

    @Test fun uncommittedForeignOrFailedDatabaseNeverReleasesCustody() = runBlocking {
        val released = mutableListOf<String>()
        assertFalse(applyOutgoingDeliveryAck("m1", { false }, { null }, released::add))
        assertFalse(applyOutgoingDeliveryAck("m1", { false }, { row() }, released::add))
        assertFalse(applyOutgoingDeliveryAck("m1", { false }, { row(mine = true, status = "SENT") }, released::add))
        val error = runCatching {
            applyOutgoingDeliveryAck("m1", { throw IllegalStateException("database") }, { row(mine = true) }, released::add)
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(released.isEmpty())
    }
}
