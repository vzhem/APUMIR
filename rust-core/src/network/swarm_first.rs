//! An external transport is a *fallback*, not a measure of how many contacts
//! happen to be in the address book. Only a recently sampled, authenticated
//! APUX1 peer with an actually open QUIC connection counts as a live swarm.
//!
//! The policy does not assert that a specific datum exists in the swarm.
//! Callers retrieving data must first complete their own bounded swarm query;
//! an unanswered query is not interchangeable with a merely queued send.

use std::sync::Mutex;

/// Time for the first mDNS / saved-address / APUX1 round after startup or a
/// network switch. No STUN, MQTT or one-shot fallback in this interval.
pub const DIRECT_SEARCH_MS: i64 = 20_000;
/// The QUIC pool can time out independently of an old PEX record. A stalled
/// discovery worker must not keep all fallback services suppressed forever.
pub const DIRECT_SAMPLE_TTL_MS: i64 = 15_000;

#[derive(Default)]
struct Snapshot {
    search_since_ms: i64,
    sampled_at_ms: Option<i64>,
    live_peer_ids: Vec<String>,
}

#[derive(Default)]
pub struct SwarmFirstPolicy {
    state: Mutex<Snapshot>,
}

impl SwarmFirstPolicy {
    pub fn new(now: i64) -> Self {
        Self { state: Mutex::new(Snapshot { search_since_ms: now,
            sampled_at_ms: None, live_peer_ids: Vec::new() }) }
    }

    /// Network changed: even a previously healthy QUIC socket cannot prove
    /// this new path works. Give direct discovery another bounded opportunity.
    pub fn reset(&self, now: i64) {
        *self.state.lock().unwrap() = Snapshot {
            search_since_ms: now,
            sampled_at_ms: None,
            live_peer_ids: Vec::new(),
        };
    }

    /// Called by the direct discovery worker *after* asking the pool whether
    /// at least one peer that answered a bound signed PEX frame is still open.
    pub fn report(&self, now: i64, authenticated_quic_ids: Vec<String>) {
        let mut s = self.state.lock().unwrap();
        if now < s.search_since_ms || s.sampled_at_ms.map(|previous| now < previous).unwrap_or(false) {
            return;
        }
        s.sampled_at_ms = Some(now);
        s.live_peer_ids = authenticated_quic_ids;
    }

    pub fn live_peer_ids(&self, now: i64) -> Vec<String> {
        let s = self.state.lock().unwrap();
        if s.sampled_at_ms.map(|sampled|
            now >= sampled && now.saturating_sub(sampled) <= DIRECT_SAMPLE_TTL_MS
        ).unwrap_or(false) { s.live_peer_ids.clone() } else { Vec::new() }
    }

    pub fn has_live_peer(&self, now: i64) -> bool {
        !self.live_peer_ids(now).is_empty()
    }

    /// MQTT subscriptions, WSS, STUN and other external services may start
    /// after the bounded direct-search window. A live direct peer is an
    /// optimization, not a proof that it can deliver every message or that it
    /// represents the intended recipient. It must therefore never suppress the
    /// fallback for the rest of the session.
    pub fn transport_fallback_allowed(&self, now: i64) -> bool {
        let s = self.state.lock().unwrap();
        now >= s.search_since_ms.saturating_add(DIRECT_SEARCH_MS)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn no_address_or_contact_count_can_skip_the_initial_swarm_attempt() {
        let policy = SwarmFirstPolicy::new(1_000_000);
        assert!(!policy.has_live_peer(1_000_000));
        assert!(!policy.transport_fallback_allowed(1_019_999));
        assert!(policy.transport_fallback_allowed(1_020_000));
    }

    #[test]
    fn direct_signed_live_session_does_not_suppress_fallback_after_search() {
        let policy = SwarmFirstPolicy::new(1_000_000);
        policy.report(1_002_000, vec!["pk_a".into()]);
        assert_eq!(policy.live_peer_ids(1_017_000), vec!["pk_a".to_string()]);
        assert!(policy.has_live_peer(1_017_000));
        assert!(!policy.transport_fallback_allowed(1_017_000));
        // A direct peer gets the bounded optimization window, but it cannot
        // suppress MQTT/WSS: it may be a different contact or lack the needed
        // message.
        assert!(policy.transport_fallback_allowed(1_020_000));
        policy.report(1_025_000, vec!["pk_a".into()]);
        assert!(policy.transport_fallback_allowed(1_035_000));
        // A discovery task that stops reporting still cannot close fallback.
        assert!(policy.live_peer_ids(1_040_001).is_empty());
        assert!(policy.transport_fallback_allowed(1_040_001));
    }

    #[test]
    fn network_change_requires_a_new_probe_and_bounded_direct_search() {
        let policy = SwarmFirstPolicy::new(1_000_000);
        policy.report(1_010_000, vec!["pk_a".into()]);
        policy.reset(1_015_000);
        assert!(!policy.has_live_peer(1_015_000));
        assert!(!policy.transport_fallback_allowed(1_034_999));
        policy.report(1_016_000, Vec::new());
        assert!(policy.transport_fallback_allowed(1_035_000));
        policy.report(1_020_000, vec!["pk_a".into()]);
        // A fresh direct result is useful for routing, but does not close the
        // external fallback after the bounded search window.
        assert!(policy.transport_fallback_allowed(1_035_000));
        policy.report(1_019_000, Vec::new()); // delayed/out-of-order probe
        assert!(policy.has_live_peer(1_020_000));
        assert!(policy.transport_fallback_allowed(1_035_000));
    }
}
