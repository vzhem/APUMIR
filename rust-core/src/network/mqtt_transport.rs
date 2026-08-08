//! # MQTT Transport — децентрализованный bootstrap + presence + relay

use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Mutex;
use rumqttc::{AsyncClient, Event, EventLoop, MqttOptions, QoS, Packet};

pub const MQTT_BROKERS: &[(&str, u16)] = &[
    ("broker.hivemq.com", 1883),
    ("broker.emqx.io", 1883),
    ("test.mosquitto.org", 1883),
    ("mqtt.eclipseprojects.io", 1883),
];

const PRESENCE_INTERVAL: Duration = Duration::from_secs(30);

#[derive(Debug, Clone)]
pub struct PeerInfo {
    pub node_id: String,
    pub display_name: String,
    pub public_addr: Option<String>,
    pub is_relay: bool,
    pub rating: f32,
    pub last_seen: i64,
}

pub struct MqttTransport {
    client: AsyncClient,
    eventloop: EventLoop,
    node_id: String,
    peers: Arc<Mutex<Vec<PeerInfo>>>,
    relay_nodes: Arc<Mutex<Vec<PeerInfo>>>,
}

impl MqttTransport {
    pub async fn connect(node_id: &str, _display_name: &str) -> Result<Self, String> {
        let client_id = format!("p2pm_{}", &node_id[..16.min(node_id.len())]);

        for (host, port) in MQTT_BROKERS {
            tracing::info!("MQTT: trying {}:{}", host, port);
            let mut opts = MqttOptions::new(&client_id, *host, *port);
            opts.set_keep_alive(Duration::from_secs(60));
            opts.set_clean_session(true);

            let (client, eventloop) = AsyncClient::new(opts, 100);

            match client.publish(
                format!("p2pm2/ping/{}", node_id),
                QoS::AtMostOnce, false, b"ping",
            ).await {
                Ok(_) => {
                    tracing::info!("MQTT: connected to {}:{}", host, port);
                    return Ok(Self {
                        client, eventloop,
                        node_id: node_id.to_string(),
                        peers: Arc::new(Mutex::new(Vec::new())),
                        relay_nodes: Arc::new(Mutex::new(Vec::new())),
                    });
                }
                Err(e) => {
                    tracing::warn!("MQTT: {}:{} failed: {}", host, port, e);
                }
            }
        }
        Err("All MQTT brokers failed".to_string())
    }

    pub async fn publish_presence(
        &self, display_name: &str, public_addr: Option<&str>, is_relay: bool,
    ) -> Result<(), String> {
        let payload = format!("{}|{}|{}|{}",
            self.node_id, display_name,
            public_addr.unwrap_or(""),
            if is_relay { "relay" } else { "client" }
        );
        let topic = format!("p2pm2/presence/{}", self.node_id);
        self.client.publish(&topic, QoS::AtLeastOnce, false, payload.as_bytes())
            .await.map_err(|e| e.to_string())
    }

    pub async fn subscribe(&self) -> Result<(), String> {
        // One wildcard subscription for ALL p2pm topics
        self.client.subscribe("p2pm2/#", QoS::AtLeastOnce)
            .await.map_err(|e| e.to_string())?;
        tracing::info!("MQTT: subscribed to p2pm2/# (node={})", self.node_id);
        // Clear any old retained presence for this node
        let topic = format!("p2pm2/presence/{}", self.node_id);
        let _ = self.client.publish(&topic, QoS::AtLeastOnce, true, b"").await;
        Ok(())
    }

    pub async fn send_message(&self, to_node_id: &str, payload: &str) -> Result<(), String> {
        let topic = format!("p2pm2/msg/{}", to_node_id);
        self.client.publish(&topic, QoS::AtLeastOnce, false, payload.as_bytes())
            .await.map_err(|e| e.to_string())
    }

    pub async fn register_as_relay(&self, public_addr: &str) -> Result<(), String> {
        let payload = format!("{}|{}", self.node_id, public_addr);
        self.client.publish("p2pm2/relay/register", QoS::AtLeastOnce, true, payload.as_bytes())
            .await.map_err(|e| e.to_string())
    }

    pub async fn poll_event(&mut self) -> Option<MqttEvent> {
        match tokio::time::timeout(Duration::from_secs(1), self.eventloop.poll()).await {
            Ok(Ok(Event::Incoming(Packet::Publish(p)))) => {
                tracing::info!("MQTT IN: topic={}", p.topic);
                Some(MqttEvent {
                    topic: p.topic.clone(),
                    payload: String::from_utf8_lossy(&p.payload).to_string(),
                })
            }
            Ok(Ok(_)) => None,
            Ok(Err(e)) => { tracing::warn!("MQTT error: {}", e); None }
            Err(_) => None,
        }
    }

    pub fn peers(&self) -> Arc<Mutex<Vec<PeerInfo>>> { Arc::clone(&self.peers) }
    pub fn relay_nodes(&self) -> Arc<Mutex<Vec<PeerInfo>>> { Arc::clone(&self.relay_nodes) }
}

#[derive(Debug)]
pub struct MqttEvent {
    pub topic: String,
    pub payload: String,
}