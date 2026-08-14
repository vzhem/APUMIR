//! # MQTT Transport — децентрализованный bootstrap + presence + relay

use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{mpsc, Mutex};
use rumqttc::{AsyncClient, Event, EventLoop, MqttOptions, QoS, Packet};

pub const MQTT_BROKERS: &[(&str, u16)] = &[
    ("broker.hivemq.com", 1883),
    ("broker.emqx.io", 1883),
    ("test.mosquitto.org", 1883),
    ("mqtt.eclipseprojects.io", 1883),
];

const PRESENCE_INTERVAL: Duration = Duration::from_secs(120);
const MQTT_EVENT_BUFFER: usize = 256;
const MQTT_RECONNECT_BACKOFF_MAX_SECS: u64 = 30;

enum MqttNotification {
    Message(MqttEvent),
    ConnectionAcknowledged,
}

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
    eventloop: Option<EventLoop>,
    event_tx: Option<mpsc::Sender<MqttNotification>>,
    event_rx: mpsc::Receiver<MqttNotification>,
    eventloop_task: Option<tokio::task::JoinHandle<()>>,
    has_connected_once: bool,
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
                    let (event_tx, event_rx) = mpsc::channel(MQTT_EVENT_BUFFER);
                    return Ok(Self {
                        client,
                        eventloop: Some(eventloop),
                        event_tx: Some(event_tx),
                        event_rx,
                        eventloop_task: None,
                        has_connected_once: false,
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
        self.client.publish(&topic, QoS::AtLeastOnce, true, payload.as_bytes())
            .await.map_err(|e| e.to_string())
    }

    pub async fn subscribe(&mut self) -> Result<(), String> {
        // One wildcard subscription for ALL p2pm topics.
        self.client.subscribe("p2pm2/#", QoS::AtLeastOnce)
            .await.map_err(|e| e.to_string())?;
        tracing::info!("MQTT: subscribed to p2pm2/# (node={})", self.node_id);

        // Clear any old retained presence for this node.
        let topic = format!("p2pm2/presence/{}", self.node_id);
        let _ = self.client.publish(&topic, QoS::AtLeastOnce, true, b"").await;

        // EventLoop должен поллиться непрерывно. Старый timeout(1s) отменял
        // eventloop.poll() именно в момент reconnect-backoff, поэтому после возврата
        // сети клиент больше не подключался. Отдельная задача не отменяет poll;
        // основной цикл получает уже готовые события через bounded channel.
        let mut eventloop = self
            .eventloop
            .take()
            .ok_or_else(|| "MQTT event loop already started".to_string())?;
        let event_tx = self
            .event_tx
            .take()
            .ok_or_else(|| "MQTT event channel unavailable".to_string())?;

        self.eventloop_task = Some(tokio::spawn(async move {
            let mut reconnect_backoff_secs = 1u64;
            loop {
                match eventloop.poll().await {
                    Ok(Event::Incoming(Packet::Publish(p))) => {
                        reconnect_backoff_secs = 1;
                        tracing::info!(
                            "MQTT IN: topic={} payload_len={} payload={:?}",
                            p.topic,
                            p.payload.len(),
                            String::from_utf8_lossy(&p.payload)
                                .chars()
                                .take(200)
                                .collect::<String>()
                        );
                        let event = MqttEvent {
                            topic: p.topic.clone(),
                            payload: String::from_utf8_lossy(&p.payload).to_string(),
                        };
                        if event_tx
                            .send(MqttNotification::Message(event))
                            .await
                            .is_err()
                        {
                            break;
                        }
                    }
                    Ok(Event::Incoming(Packet::ConnAck(_))) => {
                        reconnect_backoff_secs = 1;
                        if event_tx
                            .send(MqttNotification::ConnectionAcknowledged)
                            .await
                            .is_err()
                        {
                            break;
                        }
                    }
                    Ok(_) => {
                        reconnect_backoff_secs = 1;
                    }
                    Err(e) => {
                        tracing::warn!(
                            "MQTT error: {}; retrying in {}s",
                            e,
                            reconnect_backoff_secs
                        );
                        tokio::time::sleep(Duration::from_secs(reconnect_backoff_secs)).await;
                        reconnect_backoff_secs = reconnect_backoff_secs
                            .saturating_mul(2)
                            .min(MQTT_RECONNECT_BACKOFF_MAX_SECS);
                    }
                }
            }
        }));

        Ok(())
    }

    pub async fn send_message(&self, to_node_id: &str, payload: &str) -> Result<(), String> {
        let topic = format!("p2pm2/msg/{}", to_node_id);
        self.client.publish(&topic, QoS::AtLeastOnce, true, payload.as_bytes())
            .await.map_err(|e| e.to_string())
    }


    /// GOSSIP: Broadcast known peers to all subscribers via p2pm2/gossip/broadcast
    pub async fn publish_gossip(&self, payload: &str) -> Result<(), String> {
        self.client.publish("p2pm2/gossip/broadcast", QoS::AtLeastOnce, false, payload.as_bytes())
            .await.map_err(|e| e.to_string())
    }
    pub async fn register_as_relay(&self, public_addr: &str) -> Result<(), String> {
        let payload = format!("{}|{}", self.node_id, public_addr);
        self.client.publish("p2pm2/relay/register", QoS::AtLeastOnce, true, payload.as_bytes())
            .await.map_err(|e| e.to_string())
    }

    pub async fn poll_event(&mut self) -> Option<MqttEvent> {
        match tokio::time::timeout(Duration::from_secs(1), self.event_rx.recv()).await {
            Ok(Some(MqttNotification::Message(event))) => Some(event),
            Ok(Some(MqttNotification::ConnectionAcknowledged)) => {
                if self.has_connected_once {
                    // clean_session=true удаляет подписки на broker при разрыве,
                    // поэтому после каждого reconnect подписываемся заново.
                    match self.client.subscribe("p2pm2/#", QoS::AtLeastOnce).await {
                        Ok(_) => tracing::info!(
                            "MQTT: connection restored; re-subscribed to p2pm2/# (node={})",
                            self.node_id
                        ),
                        Err(e) => tracing::warn!(
                            "MQTT: connection restored but re-subscribe failed: {}",
                            e
                        ),
                    }
                } else {
                    self.has_connected_once = true;
                    tracing::info!("MQTT: connection acknowledged by broker");
                }
                None
            }
            Ok(None) => None,
            Err(_) => None,
        }
    }

    pub fn peers(&self) -> Arc<Mutex<Vec<PeerInfo>>> { Arc::clone(&self.peers) }
    pub fn relay_nodes(&self) -> Arc<Mutex<Vec<PeerInfo>>> { Arc::clone(&self.relay_nodes) }
}

impl Drop for MqttTransport {
    fn drop(&mut self) {
        if let Some(task) = self.eventloop_task.take() {
            task.abort();
        }
    }
}

#[derive(Debug)]
pub struct MqttEvent {
    pub topic: String,
    pub payload: String,
}

