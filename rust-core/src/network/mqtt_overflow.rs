//! Topic-aware admission policy for the bounded MQTT EventLoop-to-core channel.
//!
//! Presence, discovery gossip and queue summaries are refreshable. They may be dropped with an
//! explicit metric when the channel reaches its loss-intolerant reserve. Messages, relays,
//! receipts, acknowledgements and unknown formats are never classified as droppable.

use std::collections::VecDeque;
use std::sync::Mutex;

use tokio::sync::Notify;

/// Slots kept free from best-effort traffic. Loss-intolerant publishes use their own core-owned
/// inbox; this reserve keeps reconnect/control notifications out of refreshable FIFO saturation.
pub(crate) const MQTT_LOSS_INTOLERANT_RESERVE: usize = 32;

/// Separate bounded handoff owned by the core and shared by every replacement MQTT session.
pub(crate) const MQTT_LOSS_INTOLERANT_INBOX_CAPACITY: usize = 256;

/// A single-producer FIFO that survives transport replacement through `Arc` ownership.
///
/// The producer checks `wait_for_capacity` before polling the next broker packet, then calls
/// `push_owned` for a loss-intolerant event. Therefore every packet already returned by
/// `EventLoop::poll` has an owned location before another packet can be acknowledged. `push_owned`
/// deliberately never drops: a violated producer invariant remains observable as depth > capacity
/// instead of silently losing a relay or receipt.
pub(crate) struct LossIntolerantInbox<T> {
    entries: Mutex<VecDeque<T>>,
    capacity: usize,
    capacity_available: Notify,
}

impl<T> LossIntolerantInbox<T> {
    pub(crate) fn new(capacity: usize) -> Self {
        let capacity = capacity.max(1);
        Self {
            entries: Mutex::new(VecDeque::with_capacity(capacity)),
            capacity,
            capacity_available: Notify::new(),
        }
    }

    pub(crate) fn push_owned(&self, item: T) -> usize {
        let mut entries = self
            .entries
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        entries.push_back(item);
        entries.len()
    }

    pub(crate) fn pop(&self) -> Option<(T, usize)> {
        let (item, remaining) = {
            let mut entries = self
                .entries
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            let item = entries.pop_front()?;
            (item, entries.len())
        };
        self.capacity_available.notify_one();
        Some((item, remaining))
    }

    pub(crate) fn len(&self) -> usize {
        self.entries
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .len()
    }

    pub(crate) fn capacity(&self) -> usize {
        self.capacity
    }

    pub(crate) fn is_full(&self) -> bool {
        self.len() >= self.capacity
    }

    pub(crate) async fn wait_for_capacity(&self) {
        loop {
            let notified = self.capacity_available.notified();
            if !self.is_full() {
                return;
            }
            notified.await;
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum MqttIngressKind {
    LossIntolerant,
    Presence,
    Gossip,
    GossipSummary,
    Ping,
    RelayRegistration,
    RetainedClear,
}

impl MqttIngressKind {
    pub(crate) fn is_best_effort(self) -> bool {
        !matches!(self, Self::LossIntolerant)
    }

    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::LossIntolerant => "loss_intolerant",
            Self::Presence => "presence",
            Self::Gossip => "gossip",
            Self::GossipSummary => "gossip_summary",
            Self::Ping => "ping",
            Self::RelayRegistration => "relay_registration",
            Self::RetainedClear => "retained_clear",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum MqttIngressDisposition {
    Enqueue,
    DropBestEffort,
}

pub(crate) fn classify_mqtt_ingress(topic: &str, payload: &[u8]) -> MqttIngressKind {
    if topic.starts_with("p2pm2/presence/") {
        return if payload.is_empty() {
            MqttIngressKind::RetainedClear
        } else {
            MqttIngressKind::Presence
        };
    }
    if topic == "p2pm2/gossip/broadcast" || topic.starts_with("p2pm2/gossip/broadcast/") {
        return MqttIngressKind::Gossip;
    }
    if topic.starts_with("p2pm2/ping/") {
        return MqttIngressKind::Ping;
    }
    if topic == "p2pm2/relay/register" || topic.starts_with("p2pm2/relay/register/") {
        return MqttIngressKind::RelayRegistration;
    }
    if topic.starts_with("p2pm2/msg/") {
        if payload.is_empty() {
            return MqttIngressKind::RetainedClear;
        }
        if payload == b"gsumm" || payload.starts_with(b"gsumm|") {
            return MqttIngressKind::GossipSummary;
        }
        return MqttIngressKind::LossIntolerant;
    }

    // Fail closed: a new or malformed topic must not become silently lossy merely because this
    // classifier has not learned its semantics yet.
    MqttIngressKind::LossIntolerant
}

pub(crate) fn mqtt_ingress_disposition(
    kind: MqttIngressKind,
    remaining_capacity: usize,
    loss_intolerant_reserve: usize,
) -> MqttIngressDisposition {
    if kind.is_best_effort() && remaining_capacity <= loss_intolerant_reserve {
        MqttIngressDisposition::DropBestEffort
    } else {
        MqttIngressDisposition::Enqueue
    }
}

/// Log the first drop and powers of two. The metric remains exact while a public-broker burst
/// cannot turn overflow observability into a second log flood.
pub(crate) fn should_log_bounded_counter(total: u64) -> bool {
    total == 1 || total.is_power_of_two()
}

pub(crate) fn should_log_best_effort_drop(total_drops: u64) -> bool {
    should_log_bounded_counter(total_drops)
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::time::Duration;

    use super::*;

    #[test]
    fn loss_intolerant_inbox_is_fifo_and_bounded_by_producer_gate() {
        let inbox = LossIntolerantInbox::new(2);
        assert_eq!(inbox.capacity(), 2);
        assert_eq!(inbox.push_owned("relay"), 1);
        assert_eq!(inbox.push_owned("receipt"), 2);
        assert!(inbox.is_full());

        assert_eq!(inbox.pop(), Some(("relay", 1)));
        assert!(!inbox.is_full());
        assert_eq!(inbox.pop(), Some(("receipt", 0)));
        assert_eq!(inbox.pop(), None);
    }

    #[test]
    fn shared_inbox_survives_one_session_owner_drop() {
        let core_owner = Arc::new(LossIntolerantInbox::new(2));
        let session_owner = Arc::clone(&core_owner);
        session_owner.push_owned("receipt");
        drop(session_owner);

        assert_eq!(core_owner.pop(), Some(("receipt", 0)));
    }

    #[tokio::test]
    async fn full_inbox_waits_until_core_drains_capacity() {
        let inbox = LossIntolerantInbox::new(1);
        inbox.push_owned("relay");

        assert!(tokio::time::timeout(
            Duration::from_millis(10),
            inbox.wait_for_capacity()
        )
        .await
        .is_err());

        assert_eq!(inbox.pop(), Some(("relay", 0)));
        tokio::time::timeout(Duration::from_millis(10), inbox.wait_for_capacity())
            .await
            .expect("drain must notify the producer");
    }

    #[test]
    fn zero_capacity_is_normalized_to_one_owned_slot() {
        let inbox = LossIntolerantInbox::new(0);
        assert_eq!(inbox.capacity(), 1);
        assert_eq!(inbox.push_owned("message"), 1);
        assert!(inbox.is_full());
    }

    #[test]
    fn refreshable_topics_are_best_effort() {
        let cases: &[(&str, &[u8], MqttIngressKind)] = &[
            ("p2pm2/presence/pk_a", b"pk_a|Anna||client", MqttIngressKind::Presence),
            ("p2pm2/presence/pk_a", b"", MqttIngressKind::RetainedClear),
            ("p2pm2/gossip/broadcast", b"gossip|pk_a|id", MqttIngressKind::Gossip),
            ("p2pm2/ping/pk_a", b"ping", MqttIngressKind::Ping),
            ("p2pm2/relay/register", b"pk_a|addr", MqttIngressKind::RelayRegistration),
            ("p2pm2/msg/pk_a", b"gsumm", MqttIngressKind::GossipSummary),
            ("p2pm2/msg/pk_a", b"gsumm|m1=pk_b", MqttIngressKind::GossipSummary),
            ("p2pm2/msg/pk_a/receipt/hash", b"", MqttIngressKind::RetainedClear),
        ];

        for (topic, payload, expected) in cases {
            let actual = classify_mqtt_ingress(topic, payload);
            assert_eq!(actual, *expected, "topic={topic}");
            assert!(actual.is_best_effort(), "topic={topic}");
        }
    }

    #[test]
    fn delivery_topics_and_unknown_formats_are_loss_intolerant() {
        let cases: &[(&str, &[u8])] = &[
            ("p2pm2/msg/pk_a", b"relay|m1|pk_a|pk_b"),
            ("p2pm2/msg/pk_a/receipt/hash", b"receipt|m1|pk_a|1"),
            ("p2pm2/msg/pk_a", b"ack|m1"),
            ("p2pm2/msg/pk_a", b"pk_b|m1|chat|pk_a|ciphertext"),
            ("p2pm2/future/critical", b"new-format"),
            ("malformed", b"data"),
        ];

        for (topic, payload) in cases {
            assert_eq!(
                classify_mqtt_ingress(topic, payload),
                MqttIngressKind::LossIntolerant,
                "topic={topic}"
            );
        }
    }

    #[test]
    fn reserve_drops_only_best_effort_at_or_below_boundary() {
        assert_eq!(
            mqtt_ingress_disposition(MqttIngressKind::Presence, 33, 32),
            MqttIngressDisposition::Enqueue
        );
        assert_eq!(
            mqtt_ingress_disposition(MqttIngressKind::Presence, 32, 32),
            MqttIngressDisposition::DropBestEffort
        );
        assert_eq!(
            mqtt_ingress_disposition(MqttIngressKind::GossipSummary, 0, 32),
            MqttIngressDisposition::DropBestEffort
        );
        assert_eq!(
            mqtt_ingress_disposition(MqttIngressKind::LossIntolerant, 0, 32),
            MqttIngressDisposition::Enqueue
        );
    }

    #[test]
    fn drop_log_schedule_is_bounded_and_deterministic() {
        let logged: Vec<u64> = (1..=17)
            .filter(|count| should_log_best_effort_drop(*count))
            .collect();
        assert_eq!(logged, vec![1, 2, 4, 8, 16]);
        assert!(!should_log_best_effort_drop(0));
    }
}
