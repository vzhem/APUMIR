//! Bounded exact-duplicate policy for the future multi-broker MQTT overlay.
//!
//! A logical publish can arrive once from each independent broker. This helper
//! remembers only a SHA-256 digest of `(topic, payload)` for a short window, so
//! duplicate broker copies do not reach expensive parsing or the UI twice.

use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};

use sha2::{Digest, Sha256};

pub(crate) const MQTT_DUPLICATE_WINDOW: Duration = Duration::from_secs(30);
pub(crate) const MAX_MQTT_DUPLICATE_KEYS: usize = 4_096;

type DuplicateKey = [u8; 32];

pub(crate) struct MqttDuplicateFilter {
    entries: HashMap<DuplicateKey, Instant>,
    order: VecDeque<(DuplicateKey, Instant)>,
    window: Duration,
    capacity: usize,
}

impl MqttDuplicateFilter {
    pub(crate) fn new() -> Self {
        Self::with_limits(MQTT_DUPLICATE_WINDOW, MAX_MQTT_DUPLICATE_KEYS)
    }

    fn with_limits(window: Duration, capacity: usize) -> Self {
        Self {
            entries: HashMap::with_capacity(capacity),
            order: VecDeque::with_capacity(capacity),
            window,
            capacity: capacity.max(1),
        }
    }

    /// Returns `true` for the first copy and `false` for an exact duplicate
    /// observed inside the bounded time window.
    pub(crate) fn should_accept(&mut self, topic: &str, payload: &[u8], now: Instant) -> bool {
        self.remove_expired(now);

        let key = duplicate_key(topic, payload);
        if self.entries.contains_key(&key) {
            return false;
        }

        while self.entries.len() >= self.capacity {
            self.remove_oldest();
        }

        self.entries.insert(key, now);
        self.order.push_back((key, now));
        true
    }

    #[cfg(test)]
    fn len(&self) -> usize {
        self.entries.len()
    }

    fn remove_expired(&mut self, now: Instant) {
        while let Some((key, inserted_at)) = self.order.front().copied() {
            if now.saturating_duration_since(inserted_at) < self.window {
                break;
            }

            self.order.pop_front();
            if self.entries.get(&key) == Some(&inserted_at) {
                self.entries.remove(&key);
            }
        }
    }

    fn remove_oldest(&mut self) {
        while let Some((key, inserted_at)) = self.order.pop_front() {
            if self.entries.get(&key) == Some(&inserted_at) {
                self.entries.remove(&key);
                return;
            }
        }
    }
}

impl Default for MqttDuplicateFilter {
    fn default() -> Self {
        Self::new()
    }
}

fn duplicate_key(topic: &str, payload: &[u8]) -> DuplicateKey {
    let mut hasher = Sha256::new();
    hasher.update(topic.as_bytes());
    // MQTT topic names cannot contain U+0000, so this separator is unambiguous.
    hasher.update([0_u8]);
    hasher.update(payload);
    let digest = hasher.finalize();
    let mut key = [0_u8; 32];
    key.copy_from_slice(&digest);
    key
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_copy_is_accepted_and_immediate_duplicate_is_dropped() {
        let now = Instant::now();
        let mut filter = MqttDuplicateFilter::new();

        assert!(filter.should_accept("p2pm2/msg/pk_a", b"relay|m1", now));
        assert!(!filter.should_accept("p2pm2/msg/pk_a", b"relay|m1", now));
        assert_eq!(filter.len(), 1);
    }

    #[test]
    fn different_topic_or_payload_is_not_a_duplicate() {
        let now = Instant::now();
        let mut filter = MqttDuplicateFilter::new();

        assert!(filter.should_accept("p2pm2/msg/pk_a", b"relay|m1", now));
        assert!(filter.should_accept("p2pm2/msg/pk_b", b"relay|m1", now));
        assert!(filter.should_accept("p2pm2/msg/pk_a", b"relay|m2", now));
        assert_eq!(filter.len(), 3);
    }

    #[test]
    fn duplicate_is_accepted_again_after_window_expires() {
        let now = Instant::now();
        let mut filter = MqttDuplicateFilter::with_limits(Duration::from_secs(30), 4);

        assert!(filter.should_accept("p2pm2/msg/pk_a", b"relay|m1", now));
        assert!(!filter.should_accept(
            "p2pm2/msg/pk_a",
            b"relay|m1",
            now + Duration::from_secs(29),
        ));
        assert!(filter.should_accept(
            "p2pm2/msg/pk_a",
            b"relay|m1",
            now + Duration::from_secs(30),
        ));
        assert_eq!(filter.len(), 1);
    }

    #[test]
    fn capacity_evicts_oldest_key_deterministically() {
        let now = Instant::now();
        let mut filter = MqttDuplicateFilter::with_limits(Duration::from_secs(30), 2);

        assert!(filter.should_accept("topic", b"one", now));
        assert!(filter.should_accept("topic", b"two", now + Duration::from_secs(1)));
        assert!(filter.should_accept("topic", b"three", now + Duration::from_secs(2)));
        assert_eq!(filter.len(), 2);

        // `one` was the oldest and is accepted again. Its insertion evicts `two`;
        // `three` remains in the cache and is still recognized as a duplicate.
        assert!(filter.should_accept("topic", b"one", now + Duration::from_secs(3)));
        assert!(!filter.should_accept(
            "topic",
            b"three",
            now + Duration::from_secs(3),
        ));
        assert_eq!(filter.len(), 2);
    }
}
