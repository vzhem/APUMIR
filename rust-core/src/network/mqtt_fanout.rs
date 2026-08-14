//! Pure bounded fanout and retained-target policy for the r4.4 dual-broker overlay.
//!
//! This module performs no network I/O. It gives the later transport integration a fixed two-slot
//! broker set and a bounded digest-only ledger for retained publications that must be cleared on
//! every broker where they were queued. Activating the second session and cross-broker receive
//! dedup remains one atomic integration step after this policy compiles for Android.

use std::collections::{HashMap, VecDeque};

use sha2::{Digest, Sha256};

pub(crate) const MAX_MQTT_PUBLISH_FANOUT: usize = 2;
pub(crate) const MAX_RETAINED_BROKER_TARGETS: usize = 4_096;

const PRIMARY_BIT: u8 = 0b01;
const SECONDARY_BIT: u8 = 0b10;
const KNOWN_BROKER_BITS: u8 = PRIMARY_BIT | SECONDARY_BIT;

type RetainedTargetKey = [u8; 32];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum MqttBrokerId {
    Primary,
    Secondary,
}

impl MqttBrokerId {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::Primary => "hivemq",
            Self::Secondary => "emqx",
        }
    }

    const fn bit(self) -> u8 {
        match self {
            Self::Primary => PRIMARY_BIT,
            Self::Secondary => SECONDARY_BIT,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct MqttBrokerSet {
    bits: u8,
}

impl MqttBrokerSet {
    pub(crate) const EMPTY: Self = Self { bits: 0 };
    pub(crate) const PRIMARY: Self = Self { bits: PRIMARY_BIT };
    pub(crate) const SECONDARY: Self = Self {
        bits: SECONDARY_BIT,
    };

    pub(crate) const fn from_active_sessions(
        primary_connected: bool,
        secondary_connected: bool,
    ) -> Self {
        let mut bits = 0u8;
        if primary_connected {
            bits |= PRIMARY_BIT;
        }
        if secondary_connected {
            bits |= SECONDARY_BIT;
        }
        Self { bits }
    }

    pub(crate) const fn contains(self, broker: MqttBrokerId) -> bool {
        self.bits & broker.bit() != 0
    }

    pub(crate) const fn is_empty(self) -> bool {
        self.bits == 0
    }

    pub(crate) const fn len(self) -> usize {
        (self.bits & KNOWN_BROKER_BITS).count_ones() as usize
    }

    pub(crate) const fn union(self, other: Self) -> Self {
        Self {
            bits: (self.bits | other.bits) & KNOWN_BROKER_BITS,
        }
    }

    pub(crate) const fn intersect(self, other: Self) -> Self {
        Self {
            bits: (self.bits & other.bits) & KNOWN_BROKER_BITS,
        }
    }

    /// Stable primary-first fixed array; no per-message allocation and never more than two slots.
    pub(crate) const fn ordered(self) -> [Option<MqttBrokerId>; MAX_MQTT_PUBLISH_FANOUT] {
        let primary = if self.contains(MqttBrokerId::Primary) {
            Some(MqttBrokerId::Primary)
        } else {
            None
        };
        let secondary = if self.contains(MqttBrokerId::Secondary) {
            Some(MqttBrokerId::Secondary)
        } else {
            None
        };
        [primary, secondary]
    }
}

impl Default for MqttBrokerSet {
    fn default() -> Self {
        Self::EMPTY
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct MqttFanoutOutcome {
    pub attempted: MqttBrokerSet,
    pub queued: MqttBrokerSet,
}

impl MqttFanoutOutcome {
    pub(crate) fn new(attempted: MqttBrokerSet, queued: MqttBrokerSet) -> Self {
        Self {
            attempted,
            queued: queued.intersect(attempted),
        }
    }

    pub(crate) const fn queued_any(self) -> bool {
        !self.queued.is_empty()
    }

    pub(crate) const fn all_attempted_queued(self) -> bool {
        !self.attempted.is_empty() && self.attempted.bits == self.queued.bits
    }
}

/// Bounded digest-only mapping from logical retained publication ID to broker targets.
///
/// For mesh receipts the logical ID is `msg_id`. The ledger stores only SHA-256 IDs and a two-bit
/// broker mask. Re-recording the same ID unions targets, while capacity evicts the oldest ID.
pub(crate) struct RetainedBrokerTargetLedger {
    entries: HashMap<RetainedTargetKey, MqttBrokerSet>,
    order: VecDeque<RetainedTargetKey>,
    capacity: usize,
}

impl RetainedBrokerTargetLedger {
    pub(crate) fn new() -> Self {
        Self::with_capacity(MAX_RETAINED_BROKER_TARGETS)
    }

    fn with_capacity(capacity: usize) -> Self {
        let capacity = capacity.max(1);
        Self {
            entries: HashMap::with_capacity(capacity),
            order: VecDeque::with_capacity(capacity),
            capacity,
        }
    }

    pub(crate) fn record(&mut self, logical_id: &str, brokers: MqttBrokerSet) {
        if brokers.is_empty() {
            return;
        }

        let key = retained_target_key(logical_id);
        if let Some(existing) = self.entries.get_mut(&key) {
            *existing = existing.union(brokers);
            return;
        }

        while self.entries.len() >= self.capacity {
            self.remove_oldest();
        }

        self.entries.insert(key, brokers);
        self.order.push_back(key);
    }

    pub(crate) fn targets(&self, logical_id: &str) -> MqttBrokerSet {
        self.entries
            .get(&retained_target_key(logical_id))
            .copied()
            .unwrap_or_default()
    }

    pub(crate) fn remove(&mut self, logical_id: &str) -> MqttBrokerSet {
        let key = retained_target_key(logical_id);
        self.order.retain(|queued_key| queued_key != &key);
        self.entries.remove(&key).unwrap_or_default()
    }

    #[cfg(test)]
    fn len(&self) -> usize {
        self.entries.len()
    }

    fn remove_oldest(&mut self) {
        while let Some(key) = self.order.pop_front() {
            if self.entries.remove(&key).is_some() {
                return;
            }
        }
    }
}

impl Default for RetainedBrokerTargetLedger {
    fn default() -> Self {
        Self::new()
    }
}

fn retained_target_key(logical_id: &str) -> RetainedTargetKey {
    let digest = Sha256::digest(logical_id.as_bytes());
    let mut key = [0u8; 32];
    key.copy_from_slice(&digest);
    key
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn active_session_plan_is_stable_and_never_exceeds_two() {
        let none = MqttBrokerSet::from_active_sessions(false, false);
        let primary = MqttBrokerSet::from_active_sessions(true, false);
        let secondary = MqttBrokerSet::from_active_sessions(false, true);
        let both = MqttBrokerSet::from_active_sessions(true, true);

        assert!(none.is_empty());
        assert_eq!(none.len(), 0);
        assert_eq!(primary.ordered(), [Some(MqttBrokerId::Primary), None]);
        assert_eq!(secondary.ordered(), [None, Some(MqttBrokerId::Secondary)]);
        assert_eq!(
            both.ordered(),
            [
                Some(MqttBrokerId::Primary),
                Some(MqttBrokerId::Secondary)
            ]
        );
        assert_eq!(both.len(), MAX_MQTT_PUBLISH_FANOUT);
        assert_eq!(MqttBrokerId::Primary.as_str(), "hivemq");
        assert_eq!(MqttBrokerId::Secondary.as_str(), "emqx");
    }

    #[test]
    fn fanout_outcome_requires_at_least_one_real_target() {
        let both = MqttBrokerSet::from_active_sessions(true, true);
        let partial = MqttFanoutOutcome::new(both, MqttBrokerSet::PRIMARY);
        assert!(partial.queued_any());
        assert!(!partial.all_attempted_queued());
        assert_eq!(partial.attempted.len(), 2);
        assert_eq!(partial.queued.len(), 1);

        let complete = MqttFanoutOutcome::new(both, both);
        assert!(complete.queued_any());
        assert!(complete.all_attempted_queued());

        let none = MqttFanoutOutcome::new(MqttBrokerSet::EMPTY, MqttBrokerSet::PRIMARY);
        assert!(!none.queued_any());
        assert!(!none.all_attempted_queued());
        assert!(none.queued.is_empty());
    }

    #[test]
    fn retained_targets_union_and_remove_without_storing_plaintext_id() {
        let mut ledger = RetainedBrokerTargetLedger::new();
        ledger.record("message-one", MqttBrokerSet::PRIMARY);
        ledger.record("message-one", MqttBrokerSet::SECONDARY);

        let targets = ledger.targets("message-one");
        assert_eq!(targets.len(), 2);
        assert!(targets.contains(MqttBrokerId::Primary));
        assert!(targets.contains(MqttBrokerId::Secondary));
        assert_eq!(ledger.len(), 1);

        assert_eq!(ledger.remove("message-one"), targets);
        assert!(ledger.targets("message-one").is_empty());
        assert_eq!(ledger.len(), 0);
    }

    #[test]
    fn retained_target_capacity_evicts_oldest_deterministically() {
        let mut ledger = RetainedBrokerTargetLedger::with_capacity(2);
        ledger.record("oldest", MqttBrokerSet::PRIMARY);
        ledger.record("middle", MqttBrokerSet::SECONDARY);
        ledger.record(
            "newest",
            MqttBrokerSet::from_active_sessions(true, true),
        );

        assert!(ledger.targets("oldest").is_empty());
        assert_eq!(ledger.targets("middle"), MqttBrokerSet::SECONDARY);
        assert_eq!(ledger.targets("newest").len(), 2);
        assert_eq!(ledger.len(), 2);
    }

    #[test]
    fn remove_then_rerecord_does_not_leave_stale_eviction_order() {
        let mut ledger = RetainedBrokerTargetLedger::with_capacity(2);
        ledger.record("first", MqttBrokerSet::PRIMARY);
        ledger.record("second", MqttBrokerSet::SECONDARY);
        assert_eq!(ledger.remove("first"), MqttBrokerSet::PRIMARY);

        ledger.record("first", MqttBrokerSet::PRIMARY);
        ledger.record("third", MqttBrokerSet::SECONDARY);

        assert!(ledger.targets("second").is_empty());
        assert_eq!(ledger.targets("first"), MqttBrokerSet::PRIMARY);
        assert_eq!(ledger.targets("third"), MqttBrokerSet::SECONDARY);
        assert_eq!(ledger.len(), 2);
    }
}
