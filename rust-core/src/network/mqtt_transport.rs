//! # MQTT Transport — децентрализованный bootstrap + presence + relay

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use rumqttc::{AsyncClient, Event, EventLoop, MqttOptions, Packet, QoS};
use tokio::sync::{mpsc, oneshot, Mutex};

use crate::network::mqtt_backpressure::{await_mqtt_request, MqttRequestError};
use crate::network::mqtt_liveness::{
    assess_mqtt_liveness, mqtt_restart_reason, MqttLivenessAssessment, MqttLivenessProbe,
    MqttRestartReason,
};
use crate::network::mqtt_overflow::{
    classify_mqtt_ingress, mqtt_ingress_disposition, should_log_best_effort_drop,
    MqttIngressDisposition, MQTT_LOSS_INTOLERANT_RESERVE,
};

pub const MQTT_BROKERS: &[(&str, u16)] = &[
    ("broker.hivemq.com", 1883),
    ("broker.emqx.io", 1883),
    ("test.mosquitto.org", 1883),
    ("mqtt.eclipseprojects.io", 1883),
];

const PRESENCE_INTERVAL: Duration = Duration::from_secs(120);
const MQTT_EVENT_BUFFER: usize = 256;
const MQTT_RECONNECT_BACKOFF_MAX_SECS: u64 = 30;
const MQTT_REQUEST_ENQUEUE_TIMEOUT: Duration = Duration::from_secs(5);
const MQTT_LIVENESS_WATCHDOG_INTERVAL: Duration = Duration::from_secs(15);
const MQTT_LIVENESS_STALL_AFTER: Duration = Duration::from_secs(90);
const MQTT_LIVENESS_WARNING_REPEAT: Duration = Duration::from_secs(60);
const MQTT_LIVENESS_HEARTBEAT_INTERVAL: Duration = Duration::from_secs(120);
const MAX_MESH_TOPIC_SEGMENT_BYTES: usize = 128;
const MAX_MESH_MESSAGE_ID_BYTES: usize = 256;

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

fn mesh_receipt_topic(origin_node_id: &str, msg_id: &str) -> Result<String, String> {
    let safe_origin = !origin_node_id.is_empty()
        && origin_node_id.len() <= MAX_MESH_TOPIC_SEGMENT_BYTES
        && origin_node_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-');
    if !safe_origin {
        return Err("invalid receipt origin topic segment".to_string());
    }
    if msg_id.is_empty() || msg_id.len() > MAX_MESH_MESSAGE_ID_BYTES {
        return Err("invalid receipt message id length".to_string());
    }

    // Фиксированный SHA-256 suffix не позволяет msg_id с '/', '+' или '#'
    // создавать произвольные MQTT topics.
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(msg_id.as_bytes());
    let receipt_key = digest
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    Ok(format!(
        "p2pm2/msg/{}/receipt/{}",
        origin_node_id, receipt_key
    ))
}

pub struct MqttTransport {
    client: AsyncClient,
    eventloop: Option<EventLoop>,
    event_tx: Option<mpsc::Sender<MqttNotification>>,
    event_rx: mpsc::Receiver<MqttNotification>,
    eventloop_task: Option<tokio::task::JoinHandle<()>>,
    eventloop_watchdog_task: Option<tokio::task::JoinHandle<()>>,
    liveness: Arc<MqttLivenessProbe>,
    shutdown_requested: Arc<AtomicBool>,
    notification_channel_closed: bool,
    has_connected_once: bool,
    node_id: String,
    peers: Arc<Mutex<Vec<PeerInfo>>>,
    relay_nodes: Arc<Mutex<Vec<PeerInfo>>>,
}

impl MqttTransport {
    pub async fn connect(node_id: &str, _display_name: &str) -> Result<Self, String> {
        let client_id = format!("p2pm_{}", &node_id[..16.min(node_id.len())]);
        let (host, port) = MQTT_BROKERS
            .first()
            .copied()
            .ok_or_else(|| "No MQTT brokers configured".to_string())?;

        // r4.2 keeps the verified single-broker behavior. Creating AsyncClient only
        // creates a bounded local request channel; it is not network success.
        tracing::info!("MQTT: initializing primary session {}:{}", host, port);
        let mut opts = MqttOptions::new(&client_id, host, port);
        opts.set_keep_alive(Duration::from_secs(60));
        opts.set_clean_session(true);

        let (client, eventloop) = AsyncClient::new(opts, 100);
        let (event_tx, event_rx) = mpsc::channel(MQTT_EVENT_BUFFER);

        tracing::info!("MQTT: primary session created; awaiting broker ConnAck");
        Ok(Self {
            client,
            eventloop: Some(eventloop),
            event_tx: Some(event_tx),
            event_rx,
            eventloop_task: None,
            eventloop_watchdog_task: None,
            liveness: Arc::new(MqttLivenessProbe::new()),
            shutdown_requested: Arc::new(AtomicBool::new(false)),
            notification_channel_closed: false,
            has_connected_once: false,
            node_id: node_id.to_string(),
            peers: Arc::new(Mutex::new(Vec::new())),
            relay_nodes: Arc::new(Mutex::new(Vec::new())),
        })
    }

    async fn enqueue_publish(
        &self,
        operation: &'static str,
        topic: &str,
        qos: QoS,
        retain: bool,
        payload: Vec<u8>,
    ) -> Result<(), String> {
        let result = await_mqtt_request(
            operation,
            MQTT_REQUEST_ENQUEUE_TIMEOUT,
            self.client.publish(topic, qos, retain, payload),
        )
        .await;
        self.record_request_result(&result);
        result.map_err(|error| error.to_string())
    }

    async fn enqueue_subscribe(
        &self,
        operation: &'static str,
        topic: &str,
        qos: QoS,
    ) -> Result<(), String> {
        let result = await_mqtt_request(
            operation,
            MQTT_REQUEST_ENQUEUE_TIMEOUT,
            self.client.subscribe(topic, qos),
        )
        .await;
        self.record_request_result(&result);
        result.map_err(|error| error.to_string())
    }

    fn record_request_result<T>(&self, result: &Result<T, MqttRequestError>) {
        if let Err(error) = result {
            if error.is_timeout() {
                self.liveness.mark_request_timeout();
                tracing::warn!("MQTT REQUEST TIMEOUT: {}", error);
            } else {
                self.liveness.mark_request_error();
                tracing::warn!("MQTT REQUEST ERROR: {}", error);
            }
        }
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
        self.enqueue_publish(
            "presence publish",
            &topic,
            QoS::AtLeastOnce,
            true,
            payload.into_bytes(),
        )
        .await
    }

    pub async fn subscribe(&mut self) -> Result<(), String> {
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
        let (initial_connack_tx, initial_connack_rx) = oneshot::channel::<()>();
        let (eventloop_exit_tx, mut eventloop_exit_rx) = oneshot::channel::<&'static str>();
        let eventloop_liveness = Arc::clone(&self.liveness);
        let watchdog_liveness = Arc::clone(&self.liveness);
        let watchdog_shutdown_requested = Arc::clone(&self.shutdown_requested);

        self.eventloop_task = Some(tokio::spawn(async move {
            let mut reconnect_backoff_secs = 1u64;
            let mut initial_connack_tx = Some(initial_connack_tx);
            let exit_reason = loop {
                eventloop_liveness.mark_poll_started();
                let poll_result = eventloop.poll().await;
                eventloop_liveness.mark_poll_completed();

                match poll_result {
                    Ok(Event::Incoming(Packet::Publish(p))) => {
                        reconnect_backoff_secs = 1;
                        eventloop_liveness.mark_incoming_publish();
                        tracing::info!(
                            "MQTT IN: topic={} payload_len={} payload={:?}",
                            p.topic,
                            p.payload.len(),
                            String::from_utf8_lossy(&p.payload)
                                .chars()
                                .take(200)
                                .collect::<String>()
                        );
                        let ingress_kind = classify_mqtt_ingress(&p.topic, &p.payload);
                        let remaining_capacity = event_tx.capacity();
                        if mqtt_ingress_disposition(
                            ingress_kind,
                            remaining_capacity,
                            MQTT_LOSS_INTOLERANT_RESERVE,
                        ) == MqttIngressDisposition::DropBestEffort
                        {
                            let total_drops = eventloop_liveness.mark_best_effort_drop();
                            if should_log_best_effort_drop(total_drops) {
                                tracing::warn!(
                                    "MQTT OVERFLOW DROP: kind={} total={} remaining_capacity={} reserve={}",
                                    ingress_kind.as_str(),
                                    total_drops,
                                    remaining_capacity,
                                    MQTT_LOSS_INTOLERANT_RESERVE
                                );
                            }
                            continue;
                        }

                        let event = MqttEvent {
                            topic: p.topic.clone(),
                            payload: String::from_utf8_lossy(&p.payload).to_string(),
                        };
                        eventloop_liveness.mark_forwarding();
                        if ingress_kind.is_best_effort() {
                            match event_tx.try_send(MqttNotification::Message(event)) {
                                Ok(()) => eventloop_liveness.mark_notification_forwarded(),
                                Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {
                                    let remaining_capacity = event_tx.capacity();
                                    let total_drops = eventloop_liveness.mark_best_effort_drop();
                                    if should_log_best_effort_drop(total_drops) {
                                        tracing::warn!(
                                            "MQTT OVERFLOW DROP: kind={} total={} remaining_capacity={} reserve={}",
                                            ingress_kind.as_str(),
                                            total_drops,
                                            remaining_capacity,
                                            MQTT_LOSS_INTOLERANT_RESERVE
                                        );
                                    }
                                }
                                Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => {
                                    break "core notification channel closed while forwarding publish";
                                }
                            }
                        } else {
                            if event_tx
                                .send(MqttNotification::Message(event))
                                .await
                                .is_err()
                            {
                                break "core notification channel closed while forwarding publish";
                            }
                            eventloop_liveness.mark_notification_forwarded();
                        }
                    }
                    Ok(Event::Incoming(Packet::ConnAck(_))) => {
                        reconnect_backoff_secs = 1;
                        eventloop_liveness.mark_connack();
                        eventloop_liveness.mark_forwarding();
                        if event_tx
                            .send(MqttNotification::ConnectionAcknowledged)
                            .await
                            .is_err()
                        {
                            break "core notification channel closed while forwarding ConnAck";
                        }
                        eventloop_liveness.mark_notification_forwarded();
                        if let Some(sender) = initial_connack_tx.take() {
                            let _ = sender.send(());
                        }
                    }
                    Ok(_) => {
                        reconnect_backoff_secs = 1;
                    }
                    Err(e) => {
                        eventloop_liveness.mark_poll_error();
                        eventloop_liveness.mark_backoff();
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
            };

            eventloop_liveness.mark_stopped();
            tracing::error!("MQTT: event loop stopped: {}", exit_reason);
            let _ = eventloop_exit_tx.send(exit_reason);
        }));

        self.eventloop_watchdog_task = Some(tokio::spawn(async move {
            let mut ticker = tokio::time::interval(MQTT_LIVENESS_WATCHDOG_INTERVAL);
            ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            ticker.tick().await;

            let mut last_heartbeat = Instant::now();
            let mut last_stall_warning: Option<Instant> = None;

            loop {
                tokio::select! {
                    exit_result = &mut eventloop_exit_rx => {
                        let snapshot = watchdog_liveness.snapshot();
                        match exit_result {
                            Ok(reason) => tracing::error!(
                                "MQTT LIVENESS: EventLoop task exited: reason={} phase={} polls={}/{} incoming={} connacks={} forwarded={} best_effort_drops={} poll_errors={} request_timeouts={} request_errors={}",
                                reason,
                                snapshot.phase.as_str(),
                                snapshot.polls_completed,
                                snapshot.polls_started,
                                snapshot.incoming_publishes,
                                snapshot.connacks,
                                snapshot.notifications_forwarded,
                                snapshot.best_effort_drops,
                                snapshot.poll_errors,
                                snapshot.request_timeouts,
                                snapshot.request_errors
                            ),
                            Err(_) if watchdog_shutdown_requested.load(Ordering::Acquire) => {
                                tracing::info!("MQTT LIVENESS: EventLoop task closed during requested shutdown")
                            }
                            Err(_) => tracing::error!(
                                "MQTT LIVENESS: EventLoop task ended without completion signal; possible panic/abort; phase={} phase_age_ms={} progress_age_ms={} polls={}/{} incoming={} connacks={} forwarded={} best_effort_drops={} poll_errors={} request_timeouts={} request_errors={}",
                                snapshot.phase.as_str(),
                                snapshot.phase_age_ms(),
                                snapshot.progress_age_ms(),
                                snapshot.polls_completed,
                                snapshot.polls_started,
                                snapshot.incoming_publishes,
                                snapshot.connacks,
                                snapshot.notifications_forwarded,
                                snapshot.best_effort_drops,
                                snapshot.poll_errors,
                                snapshot.request_timeouts,
                                snapshot.request_errors
                            ),
                        }
                        break;
                    }
                    _ = ticker.tick() => {
                        let snapshot = watchdog_liveness.snapshot();
                        match assess_mqtt_liveness(snapshot, MQTT_LIVENESS_STALL_AFTER) {
                            MqttLivenessAssessment::Healthy => {
                                last_stall_warning = None;
                            }
                            MqttLivenessAssessment::Stalled { phase, stalled_for_ms } => {
                                let should_warn = last_stall_warning
                                    .map(|last| last.elapsed() >= MQTT_LIVENESS_WARNING_REPEAT)
                                    .unwrap_or(true);
                                if should_warn {
                                    tracing::warn!(
                                        "MQTT LIVENESS STALLED: phase={} phase_age_ms={} progress_age_ms={} polls={}/{} incoming={} connacks={} forwarded={} best_effort_drops={} poll_errors={} request_timeouts={} request_errors={}",
                                        phase.as_str(),
                                        stalled_for_ms,
                                        snapshot.progress_age_ms(),
                                        snapshot.polls_completed,
                                        snapshot.polls_started,
                                        snapshot.incoming_publishes,
                                        snapshot.connacks,
                                        snapshot.notifications_forwarded,
                                        snapshot.best_effort_drops,
                                        snapshot.poll_errors,
                                        snapshot.request_timeouts,
                                        snapshot.request_errors
                                    );
                                    last_stall_warning = Some(Instant::now());
                                }
                            }
                            MqttLivenessAssessment::Stopped => {
                                tracing::error!(
                                    "MQTT LIVENESS: probe reports stopped before task completion signal"
                                );
                            }
                        }

                        if last_heartbeat.elapsed() >= MQTT_LIVENESS_HEARTBEAT_INTERVAL {
                            tracing::info!(
                                "MQTT LIVENESS HEARTBEAT: phase={} phase_age_ms={} progress_age_ms={} polls={}/{} incoming={} connacks={} forwarded={} best_effort_drops={} poll_errors={} request_timeouts={} request_errors={}",
                                snapshot.phase.as_str(),
                                snapshot.phase_age_ms(),
                                snapshot.progress_age_ms(),
                                snapshot.polls_completed,
                                snapshot.polls_started,
                                snapshot.incoming_publishes,
                                snapshot.connacks,
                                snapshot.notifications_forwarded,
                                snapshot.best_effort_drops,
                                snapshot.poll_errors,
                                snapshot.request_timeouts,
                                snapshot.request_errors
                            );
                            last_heartbeat = Instant::now();
                        }
                    }
                }
            }
        }));

        tracing::info!("MQTT: event loop started; awaiting initial broker ConnAck");
        initial_connack_rx
            .await
            .map_err(|_| "MQTT event loop stopped before initial ConnAck".to_string())?;

        // Queue the wildcard subscription only after the broker has acknowledged
        // the connection. The EventLoop task continues polling and sends it.
        self.enqueue_subscribe(
            "initial wildcard subscribe",
            "p2pm2/#",
            QoS::AtLeastOnce,
        )
        .await?;
        tracing::info!(
            "MQTT: subscription requested after ConnAck for p2pm2/# (node={})",
            self.node_id
        );

        // Clear any old retained presence before run_mqtt_transport publishes the
        // current presence. Both requests preserve their order in the client queue.
        let topic = format!("p2pm2/presence/{}", self.node_id);
        if let Err(error) = self
            .enqueue_publish(
                "retained presence clear",
                &topic,
                QoS::AtLeastOnce,
                true,
                Vec::new(),
            )
            .await
        {
            tracing::warn!("MQTT: retained presence clear request failed: {}", error);
        }

        Ok(())
    }

    /// M3(c.2-r2): receipt получает отдельный retained topic, поэтому обычный ACK
    /// или gossip-summary больше не может перезаписать его до возвращения origin.
    pub async fn send_mesh_receipt(
        &self,
        origin_node_id: &str,
        msg_id: &str,
        payload: &str,
    ) -> Result<(), String> {
        let topic = mesh_receipt_topic(origin_node_id, msg_id)?;
        self.enqueue_publish(
            "mesh receipt publish",
            &topic,
            QoS::AtLeastOnce,
            true,
            payload.as_bytes().to_vec(),
        )
        .await
    }

    /// Только локальный origin имеет право очистить свой retained receipt.
    pub fn is_own_mesh_receipt_topic(&self, topic: &str, msg_id: &str) -> bool {
        mesh_receipt_topic(&self.node_id, msg_id)
            .map(|expected| expected == topic)
            .unwrap_or(false)
    }

    pub async fn clear_own_mesh_receipt(&self, msg_id: &str) -> Result<(), String> {
        let topic = mesh_receipt_topic(&self.node_id, msg_id)?;
        self.enqueue_publish(
            "mesh receipt clear",
            &topic,
            QoS::AtLeastOnce,
            true,
            Vec::new(),
        )
        .await
    }

    /// M3(c.2-r3): relay, пересылаемый уже появившемуся recipient, должен быть live-only.
    /// Retained relay переживал receipt cleanup и повторно доставлялся после reconnect.
    pub async fn send_mesh_relay(&self, to_node_id: &str, payload: &str) -> Result<(), String> {
        let topic = format!("p2pm2/msg/{}", to_node_id);
        self.enqueue_publish(
            "mesh relay publish",
            &topic,
            QoS::AtLeastOnce,
            false,
            payload.as_bytes().to_vec(),
        )
        .await
    }

    pub async fn send_message(&self, to_node_id: &str, payload: &str) -> Result<(), String> {
        let topic = format!("p2pm2/msg/{}", to_node_id);
        self.enqueue_publish(
            "message publish",
            &topic,
            QoS::AtLeastOnce,
            true,
            payload.as_bytes().to_vec(),
        )
        .await
    }

    /// GOSSIP: Broadcast known peers to all subscribers via p2pm2/gossip/broadcast
    pub async fn publish_gossip(&self, payload: &str) -> Result<(), String> {
        self.enqueue_publish(
            "gossip publish",
            "p2pm2/gossip/broadcast",
            QoS::AtLeastOnce,
            false,
            payload.as_bytes().to_vec(),
        )
        .await
    }

    pub async fn register_as_relay(&self, public_addr: &str) -> Result<(), String> {
        let payload = format!("{}|{}", self.node_id, public_addr);
        self.enqueue_publish(
            "relay registration publish",
            "p2pm2/relay/register",
            QoS::AtLeastOnce,
            true,
            payload.into_bytes(),
        )
        .await
    }

    pub fn restart_reason(&self) -> Option<MqttRestartReason> {
        if self.shutdown_requested.load(Ordering::Acquire) {
            return None;
        }

        let eventloop_task_finished = self
            .eventloop_task
            .as_ref()
            .map(|task| task.is_finished())
            .unwrap_or(true);
        mqtt_restart_reason(
            self.notification_channel_closed,
            eventloop_task_finished,
            self.liveness.snapshot(),
            MQTT_LIVENESS_STALL_AFTER,
        )
    }

    pub async fn poll_event(&mut self) -> Option<MqttEvent> {
        match tokio::time::timeout(Duration::from_secs(1), self.event_rx.recv()).await {
            Ok(Some(MqttNotification::Message(event))) => Some(event),
            Ok(Some(MqttNotification::ConnectionAcknowledged)) => {
                if self.has_connected_once {
                    // clean_session=true удаляет подписки на broker при разрыве,
                    // поэтому после каждого reconnect подписываемся заново.
                    match self
                        .enqueue_subscribe(
                            "reconnect wildcard subscribe",
                            "p2pm2/#",
                            QoS::AtLeastOnce,
                        )
                        .await
                    {
                        Ok(_) => tracing::info!(
                            "MQTT: connection restored; subscription requested for p2pm2/# (node={})",
                            self.node_id
                        ),
                        Err(e) => tracing::warn!(
                            "MQTT: connection restored but subscription request failed: {}",
                            e
                        ),
                    }
                } else {
                    self.has_connected_once = true;
                    tracing::info!("MQTT: connection acknowledged by broker");
                }
                None
            }
            Ok(None) => {
                if !self.notification_channel_closed {
                    tracing::error!("MQTT LIVENESS: core notification channel closed");
                    self.notification_channel_closed = true;
                }
                None
            }
            Err(_) => None,
        }
    }

    pub fn peers(&self) -> Arc<Mutex<Vec<PeerInfo>>> { Arc::clone(&self.peers) }
    pub fn relay_nodes(&self) -> Arc<Mutex<Vec<PeerInfo>>> { Arc::clone(&self.relay_nodes) }
}

impl Drop for MqttTransport {
    fn drop(&mut self) {
        // Stop diagnostics first so a normal transport shutdown is not reported as a panic/abort.
        self.shutdown_requested.store(true, Ordering::Release);
        if let Some(task) = self.eventloop_watchdog_task.take() {
            task.abort();
        }
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn receipt_topic_is_unique_and_does_not_embed_message_id() {
        let first = mesh_receipt_topic("pk_origin", "message/with+#wildcards").unwrap();
        let same = mesh_receipt_topic("pk_origin", "message/with+#wildcards").unwrap();
        let second = mesh_receipt_topic("pk_origin", "another-message").unwrap();

        assert_eq!(first, same);
        assert_ne!(first, second);
        assert!(first.starts_with("p2pm2/msg/pk_origin/receipt/"));
        assert!(!first.contains("message/with+#wildcards"));
        assert_eq!(first.rsplit('/').next().unwrap().len(), 64);
    }

    #[test]
    fn receipt_topic_rejects_unsafe_origin_and_unbounded_message_id() {
        assert!(mesh_receipt_topic("pk_origin/other", "m1").is_err());
        assert!(mesh_receipt_topic("pk_origin+", "m1").is_err());
        assert!(mesh_receipt_topic("pk_origin", "").is_err());
        assert!(mesh_receipt_topic("pk_origin", &"m".repeat(257)).is_err());
    }
}

