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
    has_live_authenticated_peer: bool,
}

#[derive(Default)]
pub struct SwarmFirstPolicy {
    state: Mutex<Snapshot>,
}

impl SwarmFirstPolicy {
    pub fn new(now: i64) -> Self {
        Self { state: Mutex::new(Snapshot { search_since_ms: now,
            sampled_at_ms: None, has_live_authenticated_peer: false }) }
    }

    /// Network changed: even a previously healthy QUIC socket cannot prove
    /// this new path works. Give direct discovery another bounded opportunity.
    pub fn reset(&self, now: i64) {
        *self.state.lock().unwrap() = Snapshot {
            search_since_ms: now,
            sampled_at_ms: None,
            has_live_authenticated_peer: false,
        };
    }

    /// Called by the direct discovery worker *after* asking the pool whether
    /// at least one peer that answered a bound signed PEX frame is still open.
    pub fn report(&self, now: i64, authenticated_quic_open: bool) {
        let mut s = self.state.lock().unwrap();
        if now < s.search_since_ms || s.sampled_at_ms.map(|previous| now < previous).unwrap_or(false) {
            return;
        }
        s.sampled_at_ms = Some(now);
        s.has_live_authenticated_peer = authenticated_quic_open;
    }

    pub fn has_live_peer(&self, now: i64) -> bool {
        let s = self.state.lock().unwrap();
        s.has_live_authenticated_peer && s.sampled_at_ms
            .map(|sampled| now >= sampled && now.saturating_sub(sampled) <= DIRECT_SAMPLE_TTL_MS)
            .unwrap_or(false)
    }

    /// MQTT subscriptions, STUN, call/media broker, etc. The caller must
    /// *never* infer health from contact count or a cached address. Only lack
    /// of an authenticated route after the search window permits fallback.
    pub fn transport_fallback_allowed(&self, now: i64) -> bool {
        let s = self.state.lock().unwrap();
        let live = s.has_live_authenticated_peer && s.sampled_at_ms
            .map(|sampled| now >= sampled && now.saturating_sub(sampled) <= DIRECT_SAMPLE_TTL_MS)
            .unwrap_or(false);
        !live && now >= s.search_since_ms.saturating_add(DIRECT_SEARCH_MS)
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
    fn direct_signed_live_session_closes_fallback_until_lost_or_stale() {
        let policy = SwarmFirstPolicy::new(1_000_000);
        policy.report(1_002_000, true);
        assert!(policy.has_live_peer(1_017_000));
        assert!(!policy.transport_fallback_allowed(1_017_000));
        // A discovery task that stops reporting cannot hide total isolation.
        assert!(!policy.has_live_peer(1_017_001));
        assert!(policy.transport_fallback_allowed(1_020_000));
        policy.report(1_025_000, true);
        assert!(!policy.transport_fallback_allowed(1_035_000));
        policy.report(1_030_000, false);
        assert!(policy.transport_fallback_allowed(1_030_000));
    }

    #[test]
    fn network_change_requires_a_new_probe_and_bounded_direct_search() {
        let policy = SwarmFirstPolicy::new(1_000_000);
        policy.report(1_010_000, true);
        policy.reset(1_015_000);
        assert!(!policy.has_live_peer(1_015_000));
        assert!(!policy.transport_fallback_allowed(1_034_999));
        policy.report(1_016_000, false);
        assert!(policy.transport_fallback_allowed(1_035_000));
        policy.report(1_020_000, true);
        assert!(!policy.transport_fallback_allowed(1_035_000));
        policy.report(1_019_000, false); // delayed/out-of-order probe
        assert!(policy.has_live_peer(1_020_000));
    }
}
