//! Reapplying an unchanged path policy also reapplies the saved broker. Only
//! an actual endpoint change may replace existing MQTT sessions.

use super::P2PCore;
use crate::network::multi_broker::parse_broker_endpoint;

impl P2PCore {
    pub(super) fn set_own_broker_with(
        &mut self,
        text: String,
        notify_changed: impl FnOnce(),
    ) -> bool {
        let next = if text.trim().is_empty() {
            None
        } else {
            let Some(endpoint) = parse_broker_endpoint(&text) else {
                tracing::warn!("MQTT OWN BROKER: invalid address; previous setting kept");
                return false;
            };
            Some(endpoint)
        };
        let previous = self.own_broker_endpoint();
        let changed = match (&previous, &next) {
            (None, None) => false,
            (Some((old_host, old_port)), Some((host, port))) =>
                old_port != port || !old_host.eq_ignore_ascii_case(host),
            _ => true,
        };
        self.config.own_broker = next.as_ref().map(|(host, port)| format!("{}:{}", host, port));
        // Populate even when the equivalent config came from the environment
        // before start(); the running MQTT task reads this shared slot.
        *self.own_broker_shared.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = next;
        if changed {
            tracing::info!("MQTT OWN BROKER: endpoint changed; session replacement requested");
            notify_changed();
        } else {
            tracing::debug!("MQTT OWN BROKER: endpoint unchanged; sessions kept");
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine::core::EngineConfig;
    use std::cell::Cell;

    #[test]
    fn only_real_changes_notify_without_losing_invalid_input_or_slot() {
        let mut core = P2PCore::new(EngineConfig::default());
        let signals = Cell::new(0);
        let apply = |core: &mut P2PCore, text: &str| {
            core.set_own_broker_with(text.to_string(), || signals.set(signals.get() + 1))
        };
        assert!(apply(&mut core, "mqtt://example.org:1883"));
        assert!(apply(&mut core, "EXAMPLE.ORG")); // same host and default port
        assert!(apply(&mut core, "tcp://example.org:1883/path"));
        assert!(!apply(&mut core, "https://not-mqtt.example"));
        assert_eq!(signals.get(), 1);
        let original = Some(("example.org".to_string(), 1883));
        assert_eq!(core.own_broker_endpoint(), original);
        assert_eq!(*core.own_broker_shared.lock().unwrap(), original);
        assert!(apply(&mut core, "example.org:1884"));
        assert_eq!(*core.own_broker_shared.lock().unwrap(), Some(("example.org".into(), 1884)));
        assert!(apply(&mut core, ""));
        assert!(apply(&mut core, "   "));
        assert_eq!(signals.get(), 3);
        assert!(core.config.own_broker.is_none());
        assert!(core.own_broker_shared.lock().unwrap().is_none());
    }

    #[test]
    fn equivalent_ipv6_endpoint_does_not_notify_again() {
        let mut core = P2PCore::new(EngineConfig::default());
        let signals = Cell::new(0);
        let apply = |core: &mut P2PCore, text: &str| {
            core.set_own_broker_with(text.to_string(), || signals.set(signals.get() + 1))
        };
        assert!(apply(&mut core, "mqtt://[2001:db8::1]:1883"));
        assert!(apply(&mut core, "[2001:DB8::1]:1883"));
        assert_eq!(signals.get(), 1);
        assert_eq!(core.own_broker_endpoint(), Some(("[2001:DB8::1]".into(), 1883)));
        assert!(apply(&mut core, "[2001:db8::1]:1884"));
        assert_eq!(signals.get(), 2);
    }

    #[test]
    fn equivalent_preconfigured_endpoint_still_populates_the_shared_slot() {
        let mut config = EngineConfig::default();
        config.own_broker = Some("mqtt://EXAMPLE.ORG:1883".into());
        let mut core = P2PCore::new(config);
        let signals = Cell::new(0);
        assert!(core.set_own_broker_with("example.org".into(), || signals.set(signals.get() + 1)));
        assert_eq!(signals.get(), 0);
        assert_eq!(*core.own_broker_shared.lock().unwrap(), Some(("example.org".into(), 1883)));
    }
}
