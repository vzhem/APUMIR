package com.vladimir.messenger.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор строки прямого канала: карточка «Сеть сообщений» должна говорить
 * словами, по какому стеку идёт канал и нужен ли обходной путь. Тест чистый
 * (без Android), поэтому его гоняет scripts/ci/check-chat-history.sh на runner.
 */
class DirectChannelTextTest {

    @Test
    fun dualStackWithIpv6PublicPathNeedsNoRelay() {
        val state = requireNotNull(
            DirectChannelText.parse("DIRECT: bind=ipv6 public=ipv6 lan_v6=yes relay=not-needed"),
        )
        assertTrue(state.up)
        assertFalse(state.relayNeeded)
        val human = DirectChannelText.humanize(
            "DIRECT: bind=ipv6 public=ipv6 lan_v6=yes relay=not-needed",
        )
        assertTrue(human.contains("IPv6 и IPv4"))
        assertTrue(human.contains("обходной путь не нужен"))
    }

    @Test
    fun ipv4OnlyNetworkIsSaidPlainly() {
        val human = DirectChannelText.humanize(
            "DIRECT: bind=ipv4 public=ipv4 lan_v6=no relay=fallback",
        )
        assertTrue(human.contains("только IPv4"))
        assertTrue(human.contains("IPv6 нет"))
        assertTrue(human.contains("обходным путём"))
    }

    @Test
    fun dualStackSocketWithIpv4ReflectionExplainsMismatch() {
        // Сокет уже dual-stack, но в интернет выходим по IPv4 (CGNAT/NAT64):
        // человек должен видеть именно это, а не «IPv6 есть» без оговорок.
        val human = DirectChannelText.humanize(
            "DIRECT: bind=ipv6 public=ipv4 lan_v6=yes relay=not-needed",
        )
        assertTrue(human.contains("по IPv4"))
    }

    @Test
    fun downChannelAndUnknownFormat() {
        val down = DirectChannelText.humanize("DIRECT: bind=down public=none lan_v6=no relay=fallback")
        assertTrue(down.contains("не поднят"))
        // Не наша строка — экран покажет только MQTT, без выдумок.
        assertEquals("", DirectChannelText.humanize(""))
        assertEquals("", DirectChannelText.humanize("MQTT: tcp broker"))
        assertNull(DirectChannelText.parse("DIRECT: public=ipv4"))
    }

    @Test
    fun disabledIpv6IsSaidAsASettingNotAsAMissingNetwork() {
        // «В сети нет IPv6» и «IPv6 выключен человеком» — разные вещи: во
        // втором случае совет «возьмите сеть с IPv6» бессмысленный, потому
        // что дело в настройке. Ядро говорит это полем `ipv6=off`.
        val human = DirectChannelText.humanize(
            "DIRECT: bind=ipv6 public=ipv4 lan_v6=yes relay=not-needed ipv6=off",
        )
        assertTrue(human.contains("IPv6 выключен в настройках"))
        assertFalse(human.contains("в этой сети IPv6 нет"))
        val state = requireNotNull(DirectChannelText.parse("DIRECT: bind=ipv4 lan_v6=no ipv6=off"))
        assertFalse(state.ipv6Enabled)
        // Строка прежнего ядра (без поля) — IPv6 считается включённым.
        assertTrue(requireNotNull(DirectChannelText.parse("DIRECT: bind=ipv6 lan_v6=no")).ipv6Enabled)
    }

    @Test
    fun unknownFieldsDoNotBreakParsing() {
        // Ядро может добавить поле: старые строки обязаны читаться.
        val state = requireNotNull(
            DirectChannelText.parse("DIRECT: bind=ipv6 public=ipv6 lan_v6=yes relay=not-needed mtu=1200"),
        )
        assertEquals("ipv6", state.bind)
        assertEquals("not-needed", state.relay)
    }
}
