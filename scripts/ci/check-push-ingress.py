#!/usr/bin/env python3
"""Guard both disabled push-control receive seams before TOFU or other effects.

These are source-order contracts, not an Android router or FCM integration test.
The separate Kotlin and emulator tests exercise the classifier and envelope.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/service/CoreServerService.kt"
HELPER = ROOT / "android-app/app/src/main/java/com/vladimir/messenger/service/OpenedMessageBinding.kt"


def section(source, start, end):
    return source.split(start, 1)[1].split(end, 1)[0]


class PushIngressContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SERVICE.read_text(encoding="utf-8")

    def assert_order(self, text, *parts):
        positions = [text.index(part) for part in parts]
        self.assertEqual(positions, sorted(positions), f"wrong order: {parts}")

    def test_core_quarantines_raw_and_opened_before_effects(self):
        core = section(self.source, '"message_received" -> {', '"peer_discovered" -> {')
        self.assert_order(core, "val rawText = event.text", "if (PushControlIngress.isReserved(rawText)) return",
                          "if (!NodeIds.isNodeId(senderId))", "DevicePairingWire.isPairingText(rawText)")
        # Pairing and raw ACK branches cannot consume an encrypted envelope.
        self.assertIn("if (!SealedWire.isSealed(rawText) &&\n                    com.vladimir.messenger.data.pairing.DevicePairingWire.isPairingText(rawText)", core)
        self.assertIn("if (!SealedWire.isSealed(rawText) && DeliveryAckWire.isPacket(rawText))", core)
        self.assert_order(core, "val preopened = if (sealed) MessageSealer.open",
                          "if (PushControlIngress.isReserved(preopened)) return",
                          "MirrorHub.nodeIdCached()", "MirrorHub.noteSelfPacket()",
                          ".opened(applicationContext, senderId)",
                          "rememberOpenedMessageBinding(fileTransferRouter, senderId, rawText)",
                          "deferredDelivery.routeIncoming")
        self.assertNotIn("event.text?.take", core)

    def test_cf_opens_before_duplicate_ack_or_tofu(self):
        cf = section(self.source, "cloudflareRelay?.onMessageReceived =", "// Раунд 214")
        message = section(cf, "is RelayEnvelope.Parsed.Message -> {", "is RelayEnvelope.Parsed.Other -> {")
        self.assert_order(message, "val cfContent = if (SealedWire.isSealed(parsed.content)) MessageSealer.open",
                          "if (PushControlIngress.isReserved(cfContent)) return@launch",
                          "chatRepository.getMessageById(messageId)", "RustBridge.sendDeliveryAck(messageId, senderId)",
                          ".opened(applicationContext, senderId)",
                          "rememberOpenedMessageBinding(fileTransferRouter, senderId, parsed.content)",
                          "chatRepository.getChatByContactId(senderId)")
        self.assertNotIn("payload.take(", cf)

    def test_cf_legacy_raw_or_sealed_controls_precede_chat_creation(self):
        cf = section(self.source, "is RelayEnvelope.Parsed.Other -> {", "// Раунд 214")
        self.assert_order(cf, "PushControlIngress.isReserved(if (SealedWire.isSealed(parsed.raw))",
                          "MessageSealer.open(applicationContext, parsed.raw) else parsed.raw)) return@launch",
                          "chatRepository.getChatByContactId(senderId)", "chatRepository.getOrCreateChat(senderId, contactName)")
        self.assertNotIn("parsed.raw.take(", cf)

    def test_pin_learning_is_preserved_for_ordinary_opened_messages(self):
        helper = HELPER.read_text(encoding="utf-8")
        self.assertIn("router.rememberOpenedMessageBinding(senderId, wire)", helper)
        self.assertIn("TransferDiagnostics.notePeerKeyChanged()", helper)
        self.assertEqual(self.source.count("rememberOpenedMessageBinding(fileTransferRouter,"), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
