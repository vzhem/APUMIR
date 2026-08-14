//! Topic-aware admission policy for the bounded MQTT EventLoop-to-core channel.
//!
//! Presence, discovery gossip and queue summaries are refreshable. They may be dropped with an
//! explicit metric when the channel reaches its loss-intolerant reserve. Messages, relays,
//! receipts, acknowledgements and unknown formats are never classified as droppable.

/// Slots kept free from best-effort traffic so a short burst of messages/relay/receipts can still
/// be forwarded without waiting behind refreshable discovery traffic.
pub(crate) const MQTT_LOSS_INTOLERANT_RESERVE: usize = 32;

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
pub(crate) fn should_log_best_effort_drop(total_drops: u64) -> bool {
    total_drops == 1 || total_drops.is_power_of_two()
}

#[cfg(test)]
mod tests {
    use super::*;

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
