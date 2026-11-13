//! A foreground return is a recovery hint, not evidence that the network
//! changed. Do not reset direct sessions, the address book, or a healthy broker.

use super::{MqttOutboundCommand, P2PCore, PresenceMode, PresenceScope};

impl P2PCore {
    /// Real network changes invalidate both address families. Foreground
    /// recovery deliberately does not call this: an idle return is not proof
    /// that the route changed.
    pub(super) fn invalidate_own_addresses(&self) {
        // Keep the same lock order as note_own_public_endpoint.
        let mut primary = self.public_addr.lock().unwrap();
        let mut alternate = self.public_addr_alt.lock().unwrap();
        *primary = None;
        *alternate = None;
    }

    pub fn on_foreground_recovery(&self) -> bool {
        self.schedule_discovery(MqttOutboundCommand::AnnounceAfterIdle)
    }

    pub(super) fn schedule_discovery(&self, command: MqttOutboundCommand) -> bool {
        if !self.state.is_running() {
            tracing::warn!("Discovery: recovery hint ignored; engine is not running");
            return false;
        }
        self.peer_exchange.wake();
        crate::network::mqtt_transport::wake_reconnect();
        let direct_available = self.direct.lock().unwrap().is_some();
        if !self.swarm_first.transport_fallback_allowed(crate::storage::models::now_ms()) {
            tracing::info!("Discovery: direct swarm first; MQTT announcement deferred");
            return direct_available;
        }
        match self.mqtt_outbound_tx.as_ref() {
            Some(sender) => match sender.try_send(command) {
                Ok(()) => true,
                Err(error) => {
                    tracing::warn!("Discovery: MQTT recovery hint not queued: {}", error);
                    direct_available
                }
            },
            None => direct_available,
        }
    }
}

/// Automatic returns must respect the existing shared-beacon budget. Unlike a
/// deliberate manual announcement, a return after sleep must not refresh an
/// old public endpoint as if it had just been observed.
pub(super) fn allow_resume_beacon(peer_count: usize, scope: &PresenceScope, now_ms: i64) -> bool {
    match PresenceMode::for_peers(peer_count) {
        PresenceMode::Broadcast => true,
        PresenceMode::Scoped if scope.beacon_due(now_ms) => {
            scope.mark_beacon(now_ms);
            true
        }
        PresenceMode::Scoped => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::network::presence_scope::{BEACON_INTERVAL_MS, SCALING_MIN_PEERS};

    #[test]
    fn return_hints_discovery_without_resetting_routes_or_search_policy() {
        let mut core = P2PCore::new(super::super::EngineConfig::default());
        assert!(!core.on_foreground_recovery());
        core.state = super::super::EngineState::Running;
        let now = crate::storage::models::now_ms();
        core.swarm_first.reset(now - 30_000);
        core.swarm_first.report(now, vec!["pk_peer".to_string()]);
        let own = "203.0.113.1:7777".parse().unwrap();
        let alternate = "[2001:db8::1]:7777".parse().unwrap();
        *core.public_addr.lock().unwrap() = Some(own);
        *core.public_addr_alt.lock().unwrap() = Some(alternate);
        let (sender, mut receiver) = tokio::sync::mpsc::channel(2);
        core.mqtt_outbound_tx = Some(sender);
        assert!(core.on_foreground_recovery());
        assert!(matches!(receiver.try_recv(), Ok(MqttOutboundCommand::AnnounceAfterIdle)));
        assert_eq!(*core.public_addr.lock().unwrap(), Some(own));
        assert_eq!(*core.public_addr_alt.lock().unwrap(), Some(alternate));
        assert!(core.swarm_first.has_live_peer(now));
        assert!(core.swarm_first.transport_fallback_allowed(now));
        assert!(core.trigger_gossip_discovery());
        assert!(matches!(receiver.try_recv(), Ok(MqttOutboundCommand::AnnounceNow)));
    }

    #[test]
    fn actual_network_transitions_forget_both_own_address_families() {
        let core = P2PCore::new(super::super::EngineConfig::default());
        let primary = "203.0.113.1:7777".parse().unwrap();
        let alternate = "[2001:db8::1]:7777".parse().unwrap();
        for available in [false, true] {
            *core.public_addr.lock().unwrap() = Some(primary);
            *core.public_addr_alt.lock().unwrap() = Some(alternate);
            if available { core.on_network_available(); } else { core.on_network_lost(); }
            assert_eq!(*core.public_addr.lock().unwrap(), None);
            assert_eq!(*core.public_addr_alt.lock().unwrap(), None);
        }
    }

    #[test]
    fn automatic_resume_respects_the_scoped_beacon_interval() {
        let scope = PresenceScope::new();
        let now = 1_000_000;
        scope.mark_beacon(now);
        assert!(allow_resume_beacon(1, &scope, now + 1));
        assert!(!allow_resume_beacon(SCALING_MIN_PEERS, &scope, now + 1));
        assert!(allow_resume_beacon(SCALING_MIN_PEERS, &scope, now + BEACON_INTERVAL_MS));
        assert!(!allow_resume_beacon(SCALING_MIN_PEERS, &scope, now + BEACON_INTERVAL_MS + 1));
    }
}
