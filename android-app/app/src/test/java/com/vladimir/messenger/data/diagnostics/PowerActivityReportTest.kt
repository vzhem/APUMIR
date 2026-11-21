package com.vladimir.messenger.data.diagnostics

import com.vladimir.messenger.service.OutgoingPiggybackPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerActivityReportTest {

    @Test
    fun `parses latest APU_POWER line from logcat and renders radio and operation breakdown`() {
        val logcat = listOf(
            "11-21 12:00:00.000 1234 1250 I p2p_core: APU_POWER: window_active=false windows=1 stun=1 mdns_scan=1 mdns_pub=1 pex=2 ppres=0 mqtt_pres=3 mqtt_gossip=0 mqtt_relay_out=0 mqtt_in=5 quic_conn_out=1 quic_conn_in=0 quic_out=2 quic_in=1 quic_idle_closed=1",
            "11-21 12:05:00.000 1234 1250 I p2p_core: APU_POWER: window_active=true windows=2 stun=2 mdns_scan=2 mdns_pub=2 pex=4 ppres=3 mqtt_pres=8 mqtt_gossip=1 mqtt_relay_out=2 mqtt_in=19 quic_conn_out=3 quic_conn_in=1 quic_out=11 quic_in=7 quic_idle_closed=2",
        )
        val parsed = PowerActivitySnapshot.parseLatest(logcat)
        assertTrue(parsed.nativePresent)
        assertTrue(parsed.nativeWindowActive)
        assertEquals(2L, parsed.nativeWindows)
        assertEquals(2L, parsed.stunProbes)
        assertEquals(4L, parsed.pexRounds)
        assertEquals(3L, parsed.directPresenceRounds)
        assertEquals(8L, parsed.mqttPresencePublishes)
        assertEquals(1L, parsed.mqttGossipPublishes)
        assertEquals(2L, parsed.mqttRelayPublishes)
        assertEquals(19L, parsed.mqttInboundEvents)
        assertEquals(3L, parsed.quicConnectsOut)
        assertEquals(1L, parsed.quicAcceptsIn)
        assertEquals(11L, parsed.quicFramesOut)
        assertEquals(7L, parsed.quicFramesIn)
        assertEquals(2L, parsed.quicIdleClosed)

        val policy = OutgoingPiggybackPolicy()
        policy.noteUserOutgoing(10_000L)
        val ops = listOf(
            OperationHealth(
                operation = DiagnosticOperation.CORE_EVENTS,
                completed = 25,
                failed = 0,
                cancelled = 0,
                lastMs = 4,
                maxMs = 30,
                inFlight = 0,
                oldestInFlightMs = null,
                lastFinishedAgoMs = 100,
                totalMs = 210,
            ),
            OperationHealth(
                operation = DiagnosticOperation.PIGGYBACK_FLUSH,
                completed = 2,
                failed = 0,
                cancelled = 0,
                lastMs = 45,
                maxMs = 55,
                inFlight = 0,
                oldestInFlightMs = null,
                lastFinishedAgoMs = 500,
                totalMs = 95,
            ),
        )
        val rendered = parsed.render(
            piggyback = policy.snapshot(15_000L),
            operations = ops,
            chargeCounterUah = 3_850_000L,
            currentNowUa = -145_000L,
            rxBytesUid = 128_000L,
            txBytesUid = 64_000L,
        )
        assertTrue(rendered.contains("[расход энергии по узлам]"))
        assertTrue(rendered.contains("battery_charge_uah=3850000 · battery_current_now_ua=-145000"))
        assertTrue(rendered.contains("app_uid_rx_bytes=128000 · app_uid_tx_bytes=64000"))
        assertTrue(rendered.contains("piggyback_window_15s: active=true · remaining_ms=10000 · opened=1 · flushes=1"))
        assertTrue(rendered.contains("STUN=2 · mDNS_поиск=2 · mDNS_анонс=2 · PEX=4 · прямой_пульс=3"))
        assertTrue(rendered.contains("QUIC_закрыто_по_простою=2"))
        assertTrue(rendered.contains("события_ядра=210мс"))
        assertTrue(rendered.contains("попутное_окно_15с=95мс"))
    }

    @Test
    fun `empty logcat produces honest fallback message without fabricating counters`() {
        val parsed = PowerActivitySnapshot.parseLatest(emptyList())
        assertFalse(parsed.nativePresent)
        val rendered = parsed.render(
            piggyback = OutgoingPiggybackPolicy().snapshot(5_000L),
            operations = emptyList(),
        )
        assertTrue(rendered.contains("сводка APU_POWER ещё не попала в выборку logcat"))
    }
}
