//! Bounded MQTT path selection. Independent brokers do not exchange topics;
//! devices communicate through MQTT only when they have a reachable broker in common.
//!
//! Preserve the old WSS-only and custom/proxied/pairing paths. For ordinary TCP
//! fallback, prefer the public broker previously used as the secondary: replacing
//! a duplicate connection to that same broker with WSS adds a common path without
//! adding public TCP exposure to WSS-only users or dropping a distinct TCP broker.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SecondaryPath {
    Wss,
    PublicTcp,
}

pub(crate) fn prefer_shared_public(
    candidates: &mut Vec<(String, u16)>,
    shared: (&str, u16),
    has_custom: bool,
    has_proxy: bool,
    ephemeral: bool,
) {
    if has_custom || has_proxy || ephemeral { return; }
    if let Some(index) = candidates.iter().position(|(host, port)| {
        host.eq_ignore_ascii_case(shared.0) && *port == shared.1
    }) {
        let shared_broker = candidates.remove(index);
        candidates.insert(0, shared_broker);
    }
}

pub(crate) fn secondary_path(
    primary_wss: bool,
    primary_is_shared_public: bool,
    has_custom: bool,
    has_proxy: bool,
    ephemeral: bool,
) -> SecondaryPath {
    if primary_wss || (primary_is_shared_public && !has_custom && !has_proxy && !ephemeral) {
        SecondaryPath::Wss
    } else {
        SecondaryPath::PublicTcp
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn candidates() -> Vec<(String, u16)> {
        vec![("first".into(), 1883), ("shared".into(), 1883), ("last".into(), 1883)]
    }

    #[test]
    fn ordinary_tcp_prefers_the_previous_shared_public_broker() {
        let mut list = candidates();
        prefer_shared_public(&mut list, ("shared", 1883), false, false, false);
        assert_eq!(list.iter().map(|v| v.0.as_str()).collect::<Vec<_>>(),
            ["shared", "first", "last"]);
        assert_eq!(secondary_path(false, true, false, false, false), SecondaryPath::Wss);
        // If that broker did not answer, keep the old distinct public secondary.
        assert_eq!(secondary_path(false, false, false, false, false), SecondaryPath::PublicTcp);
    }

    #[test]
    fn wss_primary_does_not_gain_an_unrequested_public_tcp_connection() {
        for has_custom in [false, true] {
            for has_proxy in [false, true] {
                for ephemeral in [false, true] {
                    assert_eq!(secondary_path(true, false, has_custom, has_proxy, ephemeral), SecondaryPath::Wss);
                }
            }
        }
    }

    #[test]
    fn custom_proxy_and_ephemeral_settings_keep_the_old_tcp_order_and_secondary() {
        for (custom, proxy, ephemeral) in [(true, false, false), (false, true, false), (false, false, true)] {
            let mut list = candidates();
            prefer_shared_public(&mut list, ("shared", 1883), custom, proxy, ephemeral);
            assert_eq!(list, candidates());
            assert_eq!(secondary_path(false, true, custom, proxy, ephemeral), SecondaryPath::PublicTcp);
        }
    }

    #[test]
    fn current_and_previous_sessions_share_candidates_but_not_guaranteed_connections() {
        // New ordinary TCP sessions: [old secondary public, WSS]. New/old WSS: [WSS, WSS].
        // Old TCP: [some TCP, old secondary public]. Both pairs have a candidate in common.
        let new_tcp = ["shared", "wss"];
        let old_tcp = ["first", "shared"];
        let old_wss = ["wss", "wss"];
        assert!(new_tcp.iter().any(|b| old_tcp.contains(b)));
        assert!(new_tcp.iter().any(|b| old_wss.contains(b)));
        // Configured candidates are not proof that both clients can actually reach one.
        assert!(!["wss"].iter().any(|b| ["shared"].contains(b)));
    }
}
