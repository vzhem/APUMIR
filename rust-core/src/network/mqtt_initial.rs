//! Initial MQTT readiness is the first broker that has both acknowledged
//! CONNECT and accepted a wildcard subscription request. Independent brokers
//! need not answer at the same speed. This only handles control notifications;
//! the core-owned loss-intolerant inbox is never consumed during startup.

use super::*;

impl MqttTransport {
    #[cfg(feature = "mqtt-dual-broker")]
    pub(super) fn has_healthy_secondary(&self) -> bool {
        self.secondary_active()
            && matches!(
                assess_mqtt_liveness(self.secondary_liveness.snapshot(), MQTT_LIVENESS_STALL_AFTER),
                MqttLivenessAssessment::Healthy,
            )
    }

    pub(super) async fn await_initial_subscription(&mut self) -> Result<(), String> {
        let mut initial_drops = 0u64;
        let result = tokio::time::timeout(MQTT_START_CONNACK_TIMEOUT, async {
            loop {
                match self.event_rx.recv().await {
                    Some(MqttNotification::ConnectionAcknowledged(token)) => {
                        if !self.primary_connection.is_current(token) { continue; }
                        match self.enqueue_subscribe(
                            "initial wildcard subscribe", "p2pm2/#", QoS::AtLeastOnce,
                        ).await {
                            Ok(()) if self.primary_connection.mark_ready(token) => {
                                primary_connack_seen();
                                tracing::info!("MQTT: initial primary subscription requested");
                                return Ok(());
                            }
                            Ok(()) => tracing::info!("MQTT: ignored stale initial primary subscription"),
                            Err(error) => tracing::warn!("MQTT: initial primary subscription failed: {}", error),
                        }
                    }
                    #[cfg(feature = "mqtt-dual-broker")]
                    Some(MqttNotification::SecondaryConnectionAcknowledged(token)) => {
                        if !self.secondary_connection.is_current(token) { continue; }
                        match self.enqueue_secondary_subscribe().await {
                            Ok(()) if self.secondary_connection.mark_ready(token) => {
                                tracing::info!("MQTT: initial secondary subscription requested");
                                return Ok(());
                            }
                            Ok(()) => tracing::info!("MQTT: ignored stale initial secondary subscription"),
                            Err(error) => tracing::warn!("MQTT: initial secondary subscription failed: {}", error),
                        }
                    }
                    Some(MqttNotification::Message(event)) => {
                        // Only refreshable, best-effort messages use this
                        // channel. Keep them bounded and deliver them later;
                        // loss-intolerant events stay in the shared inbox.
                        if self.initial_messages.len() < MQTT_EVENT_BUFFER {
                            self.initial_messages.push_back(event);
                        } else {
                            initial_drops = initial_drops.saturating_add(1);
                            if should_log_best_effort_drop(initial_drops) {
                                tracing::warn!("MQTT: initial best-effort buffer full; dropped={}", initial_drops);
                            }
                        }
                    }
                    None => return Err("MQTT event loops stopped before initial ConnAck".to_string()),
                }
            }
        }).await;
        match result {
            Ok(result) => result,
            Err(_) => {
                // Only timeout of the whole session counts as an own-broker
                // failure. A working secondary is not a failed session.
                if MQTT_CHOSEN_OWN.swap(false, Ordering::Relaxed) {
                    MQTT_OWN_FAILED_AT_MS.store(unix_ms(), Ordering::Relaxed);
                    Err("наш брокер не ответил ConnAck за 20 с - пробуем публичных".to_string())
                } else {
                    Err("MQTT initial ConnAck/subscription timed out".to_string())
                }
            }
        }
    }
}

pub(super) fn primary_connack_seen() {
    if MQTT_CHOSEN_OWN.swap(false, Ordering::Relaxed) {
        MQTT_OWN_FAILED_AT_MS.store(0, Ordering::Relaxed);
    }
}
