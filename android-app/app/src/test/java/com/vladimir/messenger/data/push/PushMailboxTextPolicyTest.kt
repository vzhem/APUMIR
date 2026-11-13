package com.vladimir.messenger.data.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushMailboxTextPolicyTest {
    @Test fun boundedHumanTextPreservesUnicodePunctuationAndEmoji() {
        listOf(
            "Привет, как дела?",
            "Hello!\nВторая строка.\tЕщё текст — хорошо…",
            "123 + 456 = 579",
            "Семья 👨‍👩‍👧, ❤️ и 😀",
            "café e\u0301 ١٢٣",
            // Bare domains are accepted: this policy is not a URL detector.
            "www.example.org",
            "x".repeat(PushMailboxTextPolicy.MAX_CODE_UNITS),
        ).forEach { assertTrue("expected supported text", PushMailboxTextPolicy.accepts(it)) }
    }

    @Test fun controlsMediaCardsAndReservedFamiliesAreNeverPlainText() {
        listOf(
            "APUDEL1|chat|id|1", "ack|message-id", " APUTYP1|1", "APUDMR1|reply",
            "APUQUIET1:quiet", "APUSOS1:SOS", "APUTXT1|red||text", "apu-file1|chunk",
            "APUFWD1|p|id|name\n↩ Переслано из «Имя»", "↩ Переслано из «Имя»\nТекст",
            "APULOC1|1|2|-|\n📍 https://example.test", "APUGIFREF1|abc", "APUSTK1|ask",
            "APUSTORY1:story", "APUPOLL1|open", "текст\nAPUIMG1:base64",
            "подпись\nAPUIMGS1:1", "слова\nAPUIMGP1:0/1/2:chunk",
            "файл\nAPUFILE1:hash:1:type:name", "APUUNKNOWN2", "before apu-marker after",
            "Контакт «Иван»\napu://a/person", "Приглашение в группу «А»\nhttps://example.test/i",
        ).forEach { assertFalse("unsupported format must fail closed", PushMailboxTextPolicy.accepts(it)) }
    }

    @Test fun separatorsAndNonTextControlsAreRejectedEvenInsideAWordOrLaterLine() {
        listOf(
            "", " \n\t", "x".repeat(PushMailboxTextPolicy.MAX_CODE_UNITS + 1),
            "a:b", "a|b", "line\nack|id", "https://example.test", "a\u0000b", "a\u0001b",
            "a\rb", "a\u007fb", "a\u200fb", "a\ud800", "a\udc00",
        ).forEach { assertFalse("ambiguous or malformed body must be rejected", PushMailboxTextPolicy.accepts(it)) }
    }
}
