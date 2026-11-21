//! Bounded outgoing-activity piggyback gate for idle network minimization.
//!
//! When the device is idle (no genuine user-initiated outgoing correspondence),
//! background discovery, STUN refreshes, mDNS re-browsing, personal presence
//! fan-out, and auxiliary gossip remain dormant so the radio and CPU can sleep.
//!
//! As soon as the user sends a correspondence message, retries an outgoing
//! message, sends a file chunk, or explicitly triggers manual discovery, an
//! active piggyback window of [`PIGGYBACK_WINDOW_MS`] (15 seconds) opens and
//! wakes sleeping tasks immediately so auxiliary exchanges ride the already-hot
//! radio tail.

use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use std::time::Duration;
use tokio::sync::Notify;

/// Process-wide radio & wakeup counters emitted into logcat (`APU_POWER: ...`)
/// so the «Логи» report attributes energy/radio cost without changing `lib.udl`.
pub struct NativePowerCounters {
    pub piggyback_windows: AtomicU64,
    pub stun_probes: AtomicU64,
    pub mdns_scans: AtomicU64,
    pub mdns_publishes: AtomicU64,
    pub pex_rounds: AtomicU64,
    pub direct_presence_rounds: AtomicU64,
    pub mqtt_presence_publishes: AtomicU64,
    pub mqtt_gossip_publishes: AtomicU64,
    pub mqtt_relay_publishes: AtomicU64,
    pub mqtt_inbound_events: AtomicU64,
    pub quic_connects_out: AtomicU64,
    pub quic_accepts_in: AtomicU64,
    pub quic_frames_out: AtomicU64,
    pub quic_frames_in: AtomicU64,
    pub quic_idle_closed: AtomicU64,
}

impl NativePowerCounters {
    pub const fn new() -> Self {
        Self {
            piggyback_windows: AtomicU64::new(0),
            stun_probes: AtomicU64::new(0),
            mdns_scans: AtomicU64::new(0),
            mdns_publishes: AtomicU64::new(0),
            pex_rounds: AtomicU64::new(0),
            direct_presence_rounds: AtomicU64::new(0),
            mqtt_presence_publishes: AtomicU64::new(0),
            mqtt_gossip_publishes: AtomicU64::new(0),
            mqtt_relay_publishes: AtomicU64::new(0),
            mqtt_inbound_events: AtomicU64::new(0),
            quic_connects_out: AtomicU64::new(0),
            quic_accepts_in: AtomicU64::new(0),
            quic_frames_out: AtomicU64::new(0),
            quic_frames_in: AtomicU64::new(0),
            quic_idle_closed: AtomicU64::new(0),
        }
    }

    pub fn summary_line(&self, active_window: bool) -> String {
        format!(
            "APU_POWER: window_active={} windows={} stun={} mdns_scan={} mdns_pub={} pex={} ppres={} mqtt_pres={} mqtt_gossip={} mqtt_relay_out={} mqtt_in={} quic_conn_out={} quic_conn_in={} quic_out={} quic_in={} quic_idle_closed={}",
            active_window,
            self.piggyback_windows.load(Ordering::Relaxed),
            self.stun_probes.load(Ordering::Relaxed),
            self.mdns_scans.load(Ordering::Relaxed),
            self.mdns_publishes.load(Ordering::Relaxed),
            self.pex_rounds.load(Ordering::Relaxed),
            self.direct_presence_rounds.load(Ordering::Relaxed),
            self.mqtt_presence_publishes.load(Ordering::Relaxed),
            self.mqtt_gossip_publishes.load(Ordering::Relaxed),
            self.mqtt_relay_publishes.load(Ordering::Relaxed),
            self.mqtt_inbound_events.load(Ordering::Relaxed),
            self.quic_connects_out.load(Ordering::Relaxed),
            self.quic_accepts_in.load(Ordering::Relaxed),
            self.quic_frames_out.load(Ordering::Relaxed),
            self.quic_frames_in.load(Ordering::Relaxed),
            self.quic_idle_closed.load(Ordering::Relaxed),
        )
    }
}

pub static POWER_COUNTERS: NativePowerCounters = NativePowerCounters::new();

/// Duration of the outgoing piggyback window after a genuine user send (15 s).
pub const PIGGYBACK_WINDOW_MS: i64 = 15_000;

/// Quiet STUN refresh interval when completely idle (30 minutes).
pub const IDLE_STUN_REFRESH_SECS: u64 = 30 * 60;

/// Minimum STUN age before refreshing inside an outgoing piggyback window (2 minutes).
pub const PIGGYBACK_STUN_MIN_AGE_MS: i64 = 2 * 60 * 1_000;

/// Quiet mDNS browse/re-publish interval when completely idle (20 minutes).
pub const IDLE_MDNS_REFRESH_SECS: u64 = 20 * 60;

/// Auxiliary/service `chat_id` scopes that carry metadata, directory sync, or
/// transport signals rather than user-initiated correspondence.
const AUXILIARY_CHAT_SCOPES: &[&str] = &[
    "nicknames",
    "avatars",
    "birthdays",
    "emoji-status",
    "capabilities",
    "whois",
    "direct",
    "pairing",
    "addrbook-backup",
    "stickers",
    "gifs",
    "heart-game",
    "peer-rank",
];

/// Returns `true` when an outgoing `send_message_inner` call represents genuine
/// user correspondence (including sealed `APU1|` / `APUS1|` envelopes) rather
/// than an auxiliary/ephemeral/control frame.
pub fn is_user_correspondence_send(
    message_id: &str,
    chat_id: &str,
    payload: &[u8],
) -> bool {
    let trimmed = chat_id.trim();
    if trimmed.is_empty()
        || AUXILIARY_CHAT_SCOPES.contains(&trimmed)
        || message_id.starts_with("peer-rank:")
        || message_id.starts_with("push1-")
        || message_id.starts_with("fh")
    {
        return false;
    }
    crate::network::relay_queue::message_kind(message_id, trimmed, payload)
        == crate::network::relay_queue::RelayPayloadKind::Correspondence
}

#[derive(Debug, Default)]
pub struct OutgoingActivityGate {
    window_until_ms: AtomicI64,
    last_stun_ok_ms: AtomicI64,
    notify: Notify,
}

impl OutgoingActivityGate {
    pub fn new() -> Self {
        Self {
            window_until_ms: AtomicI64::new(0),
            last_stun_ok_ms: AtomicI64::new(0),
            notify: Notify::new(),
        }
    }

    /// Open or extend the 15-second outgoing piggyback window and wake all
    /// dormant background tasks waiting on [`Self::sleep_or_wake`].
    pub fn note_user_outgoing(&self, now_ms: i64) {
        let next_until = now_ms.saturating_add(PIGGYBACK_WINDOW_MS);
        let mut prev = self.window_until_ms.load(Ordering::Relaxed);
        while next_until > prev {
            match self.window_until_ms.compare_exchange_weak(
                prev,
                next_until,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => {
                    if prev <= now_ms {
                        POWER_COUNTERS.piggyback_windows.fetch_add(1, Ordering::Relaxed);
                        tracing::info!(
                            "{}",
                            POWER_COUNTERS.summary_line(true)
                        );
                    }
                    break;
                }
                Err(actual) => prev = actual,
            }
        }
        self.notify.notify_waiters();
    }

    /// Wake sleeping tasks on real network change without claiming a user send,
    /// resetting the cached STUN freshness timestamp so STUN re-probes once.
    pub fn note_network_reset(&self) {
        self.last_stun_ok_ms.store(0, Ordering::Release);
        self.notify.notify_waiters();
    }

    /// Whether we are currently inside the 15-second outgoing piggyback window.
    pub fn is_active(&self, now_ms: i64) -> bool {
        self.window_until_ms.load(Ordering::Acquire) > now_ms
    }

    /// Remaining milliseconds in the current piggyback window, or `0` if idle.
    pub fn remaining_ms(&self, now_ms: i64) -> u64 {
        let until = self.window_until_ms.load(Ordering::Acquire);
        if until > now_ms {
            (until - now_ms) as u64
        } else {
            0
        }
    }

    /// Record a successful STUN reflection timestamp.
    pub fn note_stun_reflected(&self, now_ms: i64) {
        self.last_stun_ok_ms.store(now_ms, Ordering::Release);
    }

    /// Whether STUN has never succeeded in this network epoch or is stale enough
    /// (`>= 2 min`) to warrant a refresh inside an outgoing piggyback window.
    pub fn stun_needs_piggyback_refresh(&self, now_ms: i64) -> bool {
        let last = self.last_stun_ok_ms.load(Ordering::Acquire);
        last <= 0 || now_ms.saturating_sub(last) >= PIGGYBACK_STUN_MIN_AGE_MS
    }

    /// Sleep up to `duration`, returning early (`true`) if outgoing activity or
    /// a network reset wakes the gate.
    pub async fn sleep_or_wake(&self, duration: Duration) -> bool {
        let notified = self.notify.notified();
        tokio::select! {
            _ = tokio::time::sleep(duration) => false,
            _ = notified => true,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sealed_apu1_chat_envelope_is_user_correspondence_while_service_frames_are_not() {
        assert!(is_user_correspondence_send(
            "msg-1",
            "chat-alice",
            b"APU1|sealed-ciphertext",
        ));
        assert!(is_user_correspondence_send(
            "msg-2",
            "group-123",
            b"APUS1|sealed-sender-ciphertext",
        ));
        assert!(!is_user_correspondence_send(
            "nick-1",
            "nicknames",
            b"APU1|sealed-nick",
        ));
        assert!(!is_user_correspondence_send(
            "av-1",
            "avatars",
            b"APU1|sealed-avatar",
        ));
        assert!(!is_user_correspondence_send(
            "bday-1",
            "birthdays",
            b"APU1|sealed-bday",
        ));
        assert!(!is_user_correspondence_send(
            "typing-1",
            "chat-alice",
            b"typing|1",
        ));
        assert!(!is_user_correspondence_send(
            "ppres-1",
            "direct",
            b"ppres|pk_a|Alice|1.2.3.4:7777|1|1000",
        ));
        assert!(!is_user_correspondence_send(
            "heart-1",
            "chat-alice",
            b"heart|score|42",
        ));
        assert!(!is_user_correspondence_send(
            "eph1-00000000-0000-4000-8000-000000000001",
            "chat-alice",
            b"APU1|sealed-heart-or-story",
        ));
        assert!(!is_user_correspondence_send(
            "peer-rank:00000000-0000-4000-8000-000000000002",
            "chat-alice",
            b"APU1|sealed-rank-broadcast",
        ));
    }

    #[test]
    fn piggyback_window_opens_for_15_seconds_and_monotonic_extends() {
        let gate = OutgoingActivityGate::new();
        assert!(!gate.is_active(1_000));
        assert_eq!(gate.remaining_ms(1_000), 0);

        gate.note_user_outgoing(1_000);
        assert!(gate.is_active(1_000));
        assert!(gate.is_active(15_999));
        assert!(!gate.is_active(16_000));
        assert_eq!(gate.remaining_ms(6_000), 10_000);

        // Earlier timestamp never shrinks an open window.
        gate.note_user_outgoing(500);
        assert!(gate.is_active(15_999));
    }

    #[tokio::test]
    async fn sleep_or_wake_returns_immediately_when_user_outgoing_fires() {
        let gate = std::sync::Arc::new(OutgoingActivityGate::new());
        let gate_waiter = std::sync::Arc::clone(&gate);
        let waiter = tokio::spawn(async move {
            gate_waiter.sleep_or_wake(Duration::from_secs(60)).await
        });
        tokio::task::yield_now().await;
        gate.note_user_outgoing(10_000);
        let woken = tokio::time::timeout(Duration::from_secs(2), waiter)
            .await
            .expect("must wake promptly")
            .expect("task must succeed");
        assert!(woken);
    }

    #[test]
    fn stun_piggyback_refresh_respects_two_minute_freshness_and_network_reset() {
        let gate = OutgoingActivityGate::new();
        assert!(gate.stun_needs_piggyback_refresh(100_000));
        gate.note_stun_reflected(100_000);
        assert!(!gate.stun_needs_piggyback_refresh(100_000 + 60_000));
        assert!(gate.stun_needs_piggyback_refresh(100_000 + PIGGYBACK_STUN_MIN_AGE_MS));
        gate.note_stun_reflected(300_000);
        assert!(!gate.stun_needs_piggyback_refresh(310_000));
        gate.note_network_reset();
        assert!(gate.stun_needs_piggyback_refresh(310_000));
    }
}
