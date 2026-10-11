//! Local route observations, not public reputation or a claim that a helper is dishonest.
//! A recipient being offline must not poison a helper for unrelated destinations.
use std::collections::HashMap;
use std::time::{Duration, Instant};

const MAX_ROUTES: usize = 512;
const HISTORY_TTL: Duration = Duration::from_secs(30 * 60);
const REACHABLE_WINDOW: Duration = Duration::from_secs(120);
const MAX_COOLDOWN: Duration = Duration::from_secs(60);
const MAX_SAMPLE_MS: u64 = 10_000;

#[derive(Default)]
pub struct HelperQuality { routes: HashMap<(String, String), Observation> }
struct Observation {
    updated: Instant,
    success: Option<Instant>,
    latency_ms: Option<u64>,
    failures: u8,
    retry_after: Instant,
}

impl HelperQuality {
    pub fn cleanup(&mut self, now: Instant) {
        self.routes.retain(|_, value| now.saturating_duration_since(value.updated) < HISTORY_TTL);
    }

    fn entry(&mut self, recipient: &str, helper: &str, now: Instant) -> &mut Observation {
        self.cleanup(now);
        let key = (recipient.to_string(), helper.to_string());
        if !self.routes.contains_key(&key) && self.routes.len() >= MAX_ROUTES {
            // Bounded local history. Eviction forgets preferences, never delivery state.
            if let Some(oldest) = self.routes.iter().min_by_key(|(key, value)| (value.updated, *key))
                .map(|(key, _)| key.clone()) { self.routes.remove(&oldest); }
        }
        self.routes.entry(key).or_insert(Observation {
            updated: now, success: None, latency_ms: None, failures: 0, retry_after: now,
        })
    }

    /// Only called after verification of the recipient's signature, for a leg we started.
    pub fn accepted(&mut self, recipient: &str, helper: &str, elapsed: Duration, now: Instant) {
        let sample = elapsed.as_millis().min(MAX_SAMPLE_MS as u128) as u64;
        let value = self.entry(recipient, helper, now);
        value.latency_ms = Some(value.latency_ms.map(|old| (old * 3 + sample) / 4).unwrap_or(sample));
        value.updated = now;
        value.success = Some(now);
        value.failures = 0;
        value.retry_after = now;
    }

    /// A queue/stream failure is not proof of a bad helper. Briefly demote this pair only.
    pub fn write_failed(&mut self, recipient: &str, helper: &str, now: Instant) {
        let value = self.entry(recipient, helper, now);
        value.updated = now;
        value.retry_after = value.retry_after.max(now + Duration::from_secs(10));
    }

    /// Missing receipts are ambiguous: penalise a route only when a DIFFERENT route
    /// to this recipient succeeded recently. Never propagate this to another recipient.
    pub fn timed_out(&mut self, recipient: &str, helper: &str, now: Instant) {
        self.cleanup(now);
        let reachable = self.routes.iter().any(|((r, h), value)| r == recipient && h != helper
            && value.success.map(|at| now.saturating_duration_since(at) < REACHABLE_WINDOW).unwrap_or(false));
        if !reachable { return; }
        let value = self.entry(recipient, helper, now);
        value.failures = value.failures.saturating_add(1).min(4);
        let delay = Duration::from_secs(5 * (1u64 << value.failures)).min(MAX_COOLDOWN);
        value.updated = now;
        value.retry_after = now + delay;
    }

    /// Stable sort preserves authenticated directory order for equal observations.
    /// Cooldown is a preference, NOT exclusion: a sole available helper still gets tried.
    pub fn order(&mut self, recipient: &str, helpers: &mut [String], now: Instant) {
        self.cleanup(now);
        helpers.sort_by_key(|helper| {
            let value = self.routes.get(&(recipient.to_string(), helper.clone()));
            let cooling = value.map(|v| v.retry_after > now).unwrap_or(false);
            // A completed cooldown forgives failure priority. Evidence expires in 30 min.
            let failures = if cooling { value.map(|v| v.failures).unwrap_or(0) } else { 0 };
            let latency = value.and_then(|v| v.latency_ms).unwrap_or(1500);
            (cooling, failures, latency)
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn ordered(q: &mut HelperQuality, recipient: &str, now: Instant) -> Vec<String> {
        let mut peers = vec!["a".into(), "b".into(), "c".into()];
        q.order(recipient, &mut peers, now); peers
    }

    #[test]
    fn verified_fast_pair_wins_without_poisoning_other_recipients() {
        let now = Instant::now(); let mut q = HelperQuality::default();
        q.accepted("recipient", "b", Duration::from_millis(100), now);
        assert_eq!(ordered(&mut q, "recipient", now), ["b", "a", "c"]);
        assert_eq!(ordered(&mut q, "other", now), ["a", "b", "c"]);
    }

    #[test]
    fn unknown_or_offline_recipient_does_not_create_failure_history() {
        let now = Instant::now(); let mut q = HelperQuality::default();
        q.timed_out("offline", "a", now);
        q.accepted("different", "b", Duration::from_millis(10), now);
        q.timed_out("offline", "a", now);
        assert_eq!(q.routes.len(), 1);
        assert_eq!(ordered(&mut q, "offline", now), ["a", "b", "c"]);
    }

    #[test]
    fn timeout_backoff_is_pair_scoped_capped_and_forgiven_by_success() {
        let now = Instant::now(); let mut q = HelperQuality::default();
        q.accepted("r", "a", Duration::from_millis(20), now);
        q.accepted("r", "b", Duration::from_millis(100), now);
        for _ in 0..10 { q.timed_out("r", "a", now); }
        assert_eq!(ordered(&mut q, "r", now), ["b", "c", "a"]);
        let key = ("r".to_string(), "a".to_string());
        assert_eq!(q.routes[&key].retry_after - now, MAX_COOLDOWN);
        q.accepted("r", "a", Duration::from_millis(40), now + Duration::from_secs(1));
        assert_eq!(q.routes[&key].failures, 0);
        assert_eq!(q.routes[&key].latency_ms, Some(25));
        assert_eq!(ordered(&mut q, "r", now + Duration::from_secs(1)), ["a", "b", "c"]);
    }

    #[test]
    fn recent_reachability_is_required_and_own_old_success_is_not_enough() {
        let now = Instant::now(); let mut q = HelperQuality::default();
        q.accepted("r", "a", Duration::from_millis(10), now);
        q.timed_out("r", "a", now);
        assert_eq!(q.routes[&("r".into(), "a".into())].failures, 0);
        q.accepted("r", "b", Duration::from_millis(10), now);
        q.timed_out("r", "a", now + REACHABLE_WINDOW);
        assert_eq!(q.routes[&("r".into(), "a".into())].failures, 0);
    }

    #[test]
    fn cooldown_never_removes_the_only_helper_and_expires() {
        let now = Instant::now(); let mut q = HelperQuality::default();
        q.write_failed("r", "a", now);
        let mut only = vec!["a".into()]; q.order("r", &mut only, now);
        assert_eq!(only, ["a"]);
        assert_eq!(ordered(&mut q, "r", now), ["b", "c", "a"]);
        assert_eq!(ordered(&mut q, "r", now + Duration::from_secs(10)), ["a", "b", "c"]);
        assert_eq!(q.routes[&("r".into(), "a".into())].failures, 0);
    }

    #[test]
    fn history_is_bounded_and_expires_with_monotonic_time() {
        let now = Instant::now(); let mut q = HelperQuality::default();
        for n in 0..(MAX_ROUTES + 5) {
            q.accepted(&format!("r{n}"), "a", Duration::from_millis(10), now + Duration::from_millis(n as u64));
        }
        assert_eq!(q.routes.len(), MAX_ROUTES);
        assert!(!q.routes.contains_key(&("r0".into(), "a".into())));
        q.cleanup(now + HISTORY_TTL + Duration::from_secs(1));
        assert!(q.routes.is_empty());
    }

    #[test]
    fn ewma_samples_are_clamped_and_equal_routes_keep_directory_order() {
        let now = Instant::now(); let mut q = HelperQuality::default();
        q.accepted("r", "a", Duration::MAX, now);
        assert_eq!(q.routes[&("r".into(), "a".into())].latency_ms, Some(MAX_SAMPLE_MS));
        assert_eq!(ordered(&mut q, "unknown", now), ["a", "b", "c"]);
    }
}
