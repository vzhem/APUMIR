//! # MQTT Transport — децентрализованный bootstrap + presence + relay

use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(feature = "mqtt-dual-broker")]
use std::sync::atomic::AtomicU64;
use std::sync::Arc;
#[cfg(feature = "mqtt-dual-broker")]
use std::sync::Mutex as StdMutex;
use std::time::{Duration, Instant};

use rumqttc::{AsyncClient, Event, EventLoop, MqttOptions, Packet, QoS};
use tokio::sync::{mpsc, oneshot, Mutex, OwnedSemaphorePermit};

use crate::network::mqtt_backpressure::{await_mqtt_request, MqttRequestError};
#[cfg(feature = "mqtt-dual-broker")]
use crate::network::mqtt_dedup::MqttDuplicateFilter;
#[cfg(feature = "mqtt-dual-broker")]
use crate::network::mqtt_fanout::{
    MqttBrokerId, MqttBrokerSet, MqttFanoutOutcome, RetainedBrokerTargetLedger,
};
use crate::network::mqtt_liveness::{
    assess_mqtt_liveness, mqtt_restart_reason, MqttLivenessAssessment, MqttLivenessProbe,
    MqttRestartReason,
};
use crate::network::mqtt_overflow::{
    classify_mqtt_ingress, mqtt_ingress_disposition, should_log_best_effort_drop,
    should_log_bounded_counter, LossIntolerantInbox, MqttIngressDisposition,
    MQTT_LOSS_INTOLERANT_INBOX_CAPACITY, MQTT_LOSS_INTOLERANT_RESERVE,
};

pub const MQTT_BROKERS: &[(&str, u16)] = &[
    ("broker.hivemq.com", 1883),
    ("broker.emqx.io", 1883),
];

const PRESENCE_INTERVAL: Duration = Duration::from_secs(120);
const MQTT_EVENT_BUFFER: usize = 256;
const MQTT_CLIENT_REQUEST_BUFFER: usize = 100;
const MQTT_RECONNECT_BACKOFF_MAX_SECS: u64 = 30;
#[cfg(feature = "mqtt-dual-broker")]
const SECONDARY_BROKER_HOST: &str = "broker.emqx.io";
#[cfg(feature = "mqtt-dual-broker")]
const SECONDARY_BROKER_PORT: u16 = 1883;
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
    #[cfg(feature = "mqtt-dual-broker")]
    SecondaryConnectionAcknowledged,
}

pub(crate) type MqttLossIntolerantInbox = LossIntolerantInbox<MqttEvent>;

pub(crate) struct MqttSharedRuntimeState {
    loss_intolerant_inbox: Arc<MqttLossIntolerantInbox>,
    #[cfg(feature = "mqtt-dual-broker")]
    duplicate_filter: StdMutex<MqttDuplicateFilter>,
    #[cfg(feature = "mqtt-dual-broker")]
    duplicate_drops: AtomicU64,
    #[cfg(feature = "mqtt-dual-broker")]
    retained_targets: StdMutex<RetainedBrokerTargetLedger>,
}

impl MqttSharedRuntimeState {
    pub(crate) fn new(loss_intolerant_capacity: usize) -> Self {
        Self {
            loss_intolerant_inbox: Arc::new(MqttLossIntolerantInbox::new(
                loss_intolerant_capacity,
            )),
            #[cfg(feature = "mqtt-dual-broker")]
            duplicate_filter: StdMutex::new(MqttDuplicateFilter::new()),
            #[cfg(feature = "mqtt-dual-broker")]
            duplicate_drops: AtomicU64::new(0),
            #[cfg(feature = "mqtt-dual-broker")]
            retained_targets: StdMutex::new(RetainedBrokerTargetLedger::new()),
        }
    }

    #[cfg(feature = "mqtt-dual-broker")]
    fn should_accept(&self, topic: &str, payload: &[u8]) -> bool {
        let mut filter = self
            .duplicate_filter
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        filter.should_accept(topic, payload, Instant::now())
    }

    #[cfg(feature = "mqtt-dual-broker")]
    fn mark_duplicate_drop(&self) -> u64 {
        let mut current = self.duplicate_drops.load(Ordering::Relaxed);
        loop {
            if current == u64::MAX {
                return current;
            }
            match self.duplicate_drops.compare_exchange_weak(
                current,
                current + 1,
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => return current + 1,
                Err(observed) => current = observed,
            }
        }
    }
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

fn mesh_receipt_key(msg_id: &str) -> Result<String, String> {
    if msg_id.is_empty() || msg_id.len() > MAX_MESH_MESSAGE_ID_BYTES {
        return Err("invalid receipt message id length".to_string());
    }
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(msg_id.as_bytes());
    Ok(digest.iter().map(|byte| format!("{byte:02x}")).collect())
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
    // Фиксированный SHA-256 suffix не позволяет msg_id с '/', '+' или '#'
    // создавать произвольные MQTT topics.
    let receipt_key = mesh_receipt_key(msg_id)?;
    Ok(format!(
        "p2pm2/msg/{}/receipt/{}",
        origin_node_id, receipt_key
    ))
}

/// Non-retained cleanup fanout для online custody nodes. Origin всё равно
/// получает authoritative retained receipt через [mesh_receipt_topic].
fn mesh_cleanup_receipt_topic(msg_id: &str) -> Result<String, String> {
    Ok(format!("p2pm2/receipt/cleanup/{}", mesh_receipt_key(msg_id)?))
}

pub struct MqttTransport {
    client: AsyncClient,
    eventloop: Option<EventLoop>,
    event_tx: Option<mpsc::Sender<MqttNotification>>,
    event_rx: mpsc::Receiver<MqttNotification>,
    shared_state: Arc<MqttSharedRuntimeState>,
    eventloop_task: Option<tokio::task::JoinHandle<()>>,
    eventloop_watchdog_task: Option<tokio::task::JoinHandle<()>>,
    liveness: Arc<MqttLivenessProbe>,
    shutdown_requested: Arc<AtomicBool>,
    notification_channel_closed: bool,
    has_connected_once: bool,
    #[cfg(feature = "mqtt-dual-broker")]
    primary_ready: Arc<AtomicBool>,
    #[cfg(feature = "mqtt-dual-broker")]
    secondary_client: AsyncClient,
    #[cfg(feature = "mqtt-dual-broker")]
    secondary_eventloop: Option<EventLoop>,
    #[cfg(feature = "mqtt-dual-broker")]
    secondary_eventloop_task: Option<tokio::task::JoinHandle<()>>,
    #[cfg(feature = "mqtt-dual-broker")]
    secondary_liveness: Arc<MqttLivenessProbe>,
    #[cfg(feature = "mqtt-dual-broker")]
    secondary_ready: Arc<AtomicBool>,
    node_id: String,
    peers: Arc<Mutex<Vec<PeerInfo>>>,
    relay_nodes: Arc<Mutex<Vec<PeerInfo>>>,
}

async fn forward_incoming_publish(
    broker_id: &'static str,
    topic: String,
    payload: &[u8],
    inbox_permit: Option<OwnedSemaphorePermit>,
    event_tx: &mpsc::Sender<MqttNotification>,
    shared_state: &MqttSharedRuntimeState,
    liveness: &MqttLivenessProbe,
) -> Result<(), &'static str> {
    liveness.mark_incoming_publish();

    #[cfg(feature = "mqtt-dual-broker")]
    if !shared_state.should_accept(&topic, payload) {
        let total = shared_state.mark_duplicate_drop();
        if should_log_bounded_counter(total) {
            tracing::info!(
                "MQTT CROSS-BROKER DUPLICATE DROPPED: broker={} mqtt_cross_broker_duplicate_dropped={}",
                broker_id,
                total
            );
        }
        return Ok(());
    }

    tracing::info!(
        "MQTT IN: broker={} topic={} payload_len={} payload={:?}",
        broker_id,
        topic,
        payload.len(),
        String::from_utf8_lossy(payload)
            .chars()
            .take(200)
            .collect::<String>()
    );
    let ingress_kind = classify_mqtt_ingress(&topic, payload);
    let remaining_capacity = event_tx.capacity();
    if mqtt_ingress_disposition(
        ingress_kind,
        remaining_capacity,
        MQTT_LOSS_INTOLERANT_RESERVE,
    ) == MqttIngressDisposition::DropBestEffort
    {
        let total_drops = liveness.mark_best_effort_drop();
        if should_log_best_effort_drop(total_drops) {
            tracing::warn!(
                "MQTT OVERFLOW DROP: broker={} kind={} total={} remaining_capacity={} reserve={}",
                broker_id,
                ingress_kind.as_str(),
                total_drops,
                remaining_capacity,
                MQTT_LOSS_INTOLERANT_RESERVE
            );
        }
        return Ok(());
    }

    let event = MqttEvent {
        topic,
        payload: String::from_utf8_lossy(payload).to_string(),
    };
    liveness.mark_forwarding();
    if ingress_kind.is_best_effort() {
        match event_tx.try_send(MqttNotification::Message(event)) {
            Ok(()) => liveness.mark_notification_forwarded(),
            Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {
                let remaining_capacity = event_tx.capacity();
                let total_drops = liveness.mark_best_effort_drop();
                if should_log_best_effort_drop(total_drops) {
                    tracing::warn!(
                        "MQTT OVERFLOW DROP: broker={} kind={} total={} remaining_capacity={} reserve={}",
                        broker_id,
                        ingress_kind.as_str(),
                        total_drops,
                        remaining_capacity,
                        MQTT_LOSS_INTOLERANT_RESERVE
                    );
                }
            }
            Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => {
                return Err("core notification channel closed while forwarding publish");
            }
        }
    } else {
        let pending = match inbox_permit {
            Some(permit) => shared_state
                .loss_intolerant_inbox
                .push_reserved(event, permit),
            None => shared_state
                .loss_intolerant_inbox
                .push_owned_bounded(event)
                .await,
        };
        liveness.mark_loss_intolerant_buffered(pending);
        if pending > shared_state.loss_intolerant_inbox.capacity() {
            tracing::error!(
                "MQTT LOSS-INTOLERANT INBOX INVARIANT: broker={} pending={} capacity={}; event retained",
                broker_id,
                pending,
                shared_state.loss_intolerant_inbox.capacity()
            );
        }
    }
    Ok(())
}

/// «Любая сеть»: если через FFI установлен SOCKS5-прокси, все соединения к брокерам
/// (основной и вторичный) идут туннелем через него. При неудаче рукопожатия SOCKS5
/// конкретная попытка честно откатывается на прямое соединение — канал не умирает
/// вместе с прокси, а автопилот на Kotlin сменит прокси и повторит.
fn apply_socks5_transport(opts: &mut MqttOptions) {
    let Some(proxy) = crate::network::socks5::mqtt_socks5_proxy_config() else {
        return;
    };
    tracing::info!(
        "MQTT: SOCKS5 transport enabled via {}:{} (user={})",
        proxy.host,
        proxy.port,
        !proxy.username.is_empty()
    );
    opts.set_transport(rumqttc::Transport::tcp_with_connect(
        move |broker_host, broker_port| {
            let proxy = proxy.clone();
            Box::pin(async move {
                match crate::network::socks5::connect_through(
                    &proxy,
                    broker_host.clone(),
                    broker_port,
                )
                .await
                {
                    Ok(stream) => Ok(stream),
                    Err(error) => {
                        tracing::warn!(
                            "MQTT: SOCKS5 tunnel to {} failed ({}), falling back direct",
                            broker_host,
                            error
                        );
                        tokio::net::TcpStream::connect((broker_host.as_str(), broker_port)).await
                    }
                }
            })
        },
    ));
}

impl MqttTransport {
    pub async fn connect(node_id: &str, display_name: &str) -> Result<Self, String> {
        let shared_state = Arc::new(MqttSharedRuntimeState::new(
            MQTT_LOSS_INTOLERANT_INBOX_CAPACITY,
        ));
        Self::connect_with_shared_state(node_id, display_name, shared_state).await
    }

    pub(crate) async fn connect_with_shared_state(
        node_id: &str,
        _display_name: &str,
        shared_state: Arc<MqttSharedRuntimeState>,
    ) -> Result<Self, String> {
        let client_id = format!("p2pm_{}", &node_id[..16.min(node_id.len())]);
        let (host, port) = MQTT_BROKERS
            .first()
            .copied()
            .ok_or_else(|| "No MQTT brokers configured".to_string())?;

        // Creating AsyncClient only creates a bounded local request channel; real readiness still
        // requires ConnAck followed by a queued wildcard subscription request.
        tracing::info!("MQTT: initializing primary session {}:{}", host, port);
        let mut opts = MqttOptions::new(&client_id, host, port);
        opts.set_keep_alive(Duration::from_secs(60));
        opts.set_clean_session(true);
        apply_socks5_transport(&mut opts);

        let (client, eventloop) = AsyncClient::new(opts, MQTT_CLIENT_REQUEST_BUFFER);
        let (event_tx, event_rx) = mpsc::channel(MQTT_EVENT_BUFFER);
        let liveness = Arc::new(MqttLivenessProbe::new());
        liveness.set_loss_intolerant_pending(shared_state.loss_intolerant_inbox.len());

        #[cfg(feature = "mqtt-dual-broker")]
        let (secondary_client, secondary_eventloop, secondary_liveness, secondary_ready) = {
            let suffix = &node_id[..16.min(node_id.len())];
            let secondary_client_id = format!("p2pm_emqx_{suffix}");
            let mut secondary_options = MqttOptions::new(
                secondary_client_id,
                SECONDARY_BROKER_HOST,
                SECONDARY_BROKER_PORT,
            );
            secondary_options.set_keep_alive(Duration::from_secs(60));
            secondary_options.set_clean_session(true);
            apply_socks5_transport(&mut secondary_options);
            let (secondary_client, secondary_eventloop) =
                AsyncClient::new(secondary_options, MQTT_CLIENT_REQUEST_BUFFER);
            tracing::info!(
                "MQTT SECONDARY STATUS: broker=emqx state=starting mode=dual_publish max_fanout=2"
            );
            (
                secondary_client,
                secondary_eventloop,
                Arc::new(MqttLivenessProbe::new()),
                Arc::new(AtomicBool::new(false)),
            )
        };

        tracing::info!("MQTT: primary session created; awaiting broker ConnAck");
        Ok(Self {
            client,
            eventloop: Some(eventloop),
            event_tx: Some(event_tx),
            event_rx,
            shared_state,
            eventloop_task: None,
            eventloop_watchdog_task: None,
            liveness,
            shutdown_requested: Arc::new(AtomicBool::new(false)),
            notification_channel_closed: false,
            has_connected_once: false,
            #[cfg(feature = "mqtt-dual-broker")]
            primary_ready: Arc::new(AtomicBool::new(false)),
            #[cfg(feature = "mqtt-dual-broker")]
            secondary_client,
            #[cfg(feature = "mqtt-dual-broker")]
            secondary_eventloop: Some(secondary_eventloop),
            #[cfg(feature = "mqtt-dual-broker")]
            secondary_eventloop_task: None,
            #[cfg(feature = "mqtt-dual-broker")]
            secondary_liveness,
            #[cfg(feature = "mqtt-dual-broker")]
            secondary_ready,
            node_id: node_id.to_string(),
            peers: Arc::new(Mutex::new(Vec::new())),
            relay_nodes: Arc::new(Mutex::new(Vec::new())),
        })
    }

    #[cfg(not(feature = "mqtt-dual-broker"))]
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

    #[cfg(feature = "mqtt-dual-broker")]
    async fn enqueue_publish(
        &self,
        operation: &'static str,
        topic: &str,
        qos: QoS,
        retain: bool,
        payload: Vec<u8>,
    ) -> Result<(), String> {
        self.enqueue_publish_to_targets(
            operation,
            topic,
            qos,
            retain,
            payload,
            self.active_brokers(),
            false,
        )
        .await
        .map(|_| ())
    }

    #[cfg(feature = "mqtt-dual-broker")]
    fn active_brokers(&self) -> MqttBrokerSet {
        MqttBrokerSet::from_active_sessions(
            self.primary_ready.load(Ordering::Acquire),
            self.secondary_ready.load(Ordering::Acquire),
        )
    }

    #[cfg(feature = "mqtt-dual-broker")]
    async fn enqueue_publish_to_targets(
        &self,
        operation: &'static str,
        topic: &str,
        qos: QoS,
        retain: bool,
        payload: Vec<u8>,
        targets: MqttBrokerSet,
        allow_inactive_targets: bool,
    ) -> Result<MqttFanoutOutcome, String> {
        let primary_requested = targets.contains(MqttBrokerId::Primary);
        let secondary_requested = targets.contains(MqttBrokerId::Secondary);
        let secondary_available = self.secondary_ready.load(Ordering::Acquire);

        let primary_result = if primary_requested {
            Some(
                await_mqtt_request(
                    operation,
                    MQTT_REQUEST_ENQUEUE_TIMEOUT,
                    self.client
                        .publish(topic, qos, retain, payload.clone()),
                )
                .await,
            )
        } else {
            None
        };
        let secondary_result =
            if secondary_requested && (secondary_available || allow_inactive_targets) {
            Some(
                await_mqtt_request(
                    operation,
                    MQTT_REQUEST_ENQUEUE_TIMEOUT,
                    self.secondary_client.publish(topic, qos, retain, payload),
                )
                .await,
            )
        } else {
            None
        };

        let mut queued = MqttBrokerSet::EMPTY;
        let mut failures = Vec::with_capacity(2);
        if let Some(result) = primary_result {
            self.record_request_result(&result);
            match result {
                Ok(()) => queued = queued.union(MqttBrokerSet::PRIMARY),
                Err(error) => failures.push(format!("hivemq={error}")),
            }
        }
        if let Some(result) = secondary_result {
            self.record_secondary_request_result(&result);
            match result {
                Ok(()) => queued = queued.union(MqttBrokerSet::SECONDARY),
                Err(error) => failures.push(format!("emqx={error}")),
            }
        } else if secondary_requested {
            failures.push("emqx=not_ready".to_string());
        }

        let outcome = MqttFanoutOutcome::new(targets, queued);
        if !outcome.queued_any() {
            return Err(format!(
                "MQTT fanout request failed operation={} targets={} failures={}",
                operation,
                targets.len(),
                failures.join(",")
            ));
        }
        if !outcome.all_attempted_queued() {
            tracing::warn!(
                "MQTT FANOUT DEGRADED: operation={} attempted={} queued={} failures={}",
                operation,
                outcome.attempted.len(),
                outcome.queued.len(),
                failures.join(",")
            );
        } else {
            tracing::info!(
                "MQTT FANOUT QUEUED: operation={} brokers={} max_fanout=2",
                operation,
                outcome.queued.len()
            );
        }
        Ok(outcome)
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

    #[cfg(feature = "mqtt-dual-broker")]
    async fn enqueue_secondary_subscribe(&self) -> Result<(), String> {
        let result = await_mqtt_request(
            "secondary wildcard subscribe",
            MQTT_REQUEST_ENQUEUE_TIMEOUT,
            self.secondary_client
                .subscribe("p2pm2/#", QoS::AtLeastOnce),
        )
        .await;
        self.record_secondary_request_result(&result);
        result.map_err(|error| error.to_string())
    }

    #[cfg(feature = "mqtt-dual-broker")]
    fn record_secondary_request_result<T>(&self, result: &Result<T, MqttRequestError>) {
        if let Err(error) = result {
            if error.is_timeout() {
                self.secondary_liveness.mark_request_timeout();
                tracing::warn!("MQTT SECONDARY REQUEST TIMEOUT: broker=emqx {}", error);
            } else {
                self.secondary_liveness.mark_request_error();
                tracing::warn!("MQTT SECONDARY REQUEST ERROR: broker=emqx {}", error);
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
        // основной цикл получает refreshable события через bounded channel, а delivery-bearing
        // события — через отдельный core-owned bounded inbox.
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
        let eventloop_shared_state = Arc::clone(&self.shared_state);
        #[cfg(feature = "mqtt-dual-broker")]
        let eventloop_primary_ready = Arc::clone(&self.primary_ready);
        let watchdog_liveness = Arc::clone(&self.liveness);
        let watchdog_shutdown_requested = Arc::clone(&self.shutdown_requested);

        #[cfg(feature = "mqtt-dual-broker")]
        let secondary_event_tx = event_tx.clone();
        #[cfg(feature = "mqtt-dual-broker")]
        let secondary_shared_state = Arc::clone(&self.shared_state);
        #[cfg(feature = "mqtt-dual-broker")]
        let secondary_liveness = Arc::clone(&self.secondary_liveness);
        #[cfg(feature = "mqtt-dual-broker")]
        let secondary_ready = Arc::clone(&self.secondary_ready);
        #[cfg(feature = "mqtt-dual-broker")]
        let mut secondary_eventloop = self
            .secondary_eventloop
            .take()
            .ok_or_else(|| "MQTT secondary event loop already started".to_string())?;

        #[cfg(feature = "mqtt-dual-broker")]
        {
            self.secondary_eventloop_task = Some(tokio::spawn(async move {
                let mut reconnect_backoff_secs = 1u64;
                let mut has_connected_once = false;
                loop {
                    let inbox_permit = if has_connected_once {
                        let waited = secondary_shared_state.loss_intolerant_inbox.is_full();
                        if waited {
                            let pending = secondary_shared_state.loss_intolerant_inbox.len();
                            let total_waits =
                                secondary_liveness.mark_loss_intolerant_backpressure();
                            if should_log_bounded_counter(total_waits) {
                                tracing::warn!(
                                    "MQTT SECONDARY BACKPRESSURE: broker=emqx total={} pending={} capacity={}",
                                    total_waits,
                                    pending,
                                    secondary_shared_state.loss_intolerant_inbox.capacity()
                                );
                            }
                        }
                        let permit = secondary_shared_state
                            .loss_intolerant_inbox
                            .reserve_owned()
                            .await;
                        if waited {
                            secondary_liveness.mark_loss_intolerant_capacity_available();
                        }
                        Some(permit)
                    } else {
                        None
                    };

                    secondary_liveness.mark_poll_started();
                    let poll_result = secondary_eventloop.poll().await;
                    secondary_liveness.mark_poll_completed();
                    match poll_result {
                        Ok(Event::Incoming(Packet::Publish(publish))) => {
                            reconnect_backoff_secs = 1;
                            let topic = publish.topic;
                            let payload = publish.payload;
                            if let Err(reason) = forward_incoming_publish(
                                "emqx",
                                topic,
                                payload.as_ref(),
                                inbox_permit,
                                &secondary_event_tx,
                                &secondary_shared_state,
                                &secondary_liveness,
                            )
                            .await
                            {
                                secondary_ready.store(false, Ordering::Release);
                                tracing::error!(
                                    "MQTT SECONDARY STATUS: broker=emqx state=stopped reason={}",
                                    reason
                                );
                                break;
                            }
                        }
                        Ok(Event::Incoming(Packet::ConnAck(_))) => {
                            reconnect_backoff_secs = 1;
                            has_connected_once = true;
                            secondary_ready.store(false, Ordering::Release);
                            secondary_liveness.mark_connack();
                            secondary_liveness.mark_forwarding();
                            if secondary_event_tx
                                .send(MqttNotification::SecondaryConnectionAcknowledged)
                                .await
                                .is_err()
                            {
                                tracing::error!(
                                    "MQTT SECONDARY STATUS: broker=emqx state=stopped reason=notification_channel_closed"
                                );
                                break;
                            }
                            secondary_liveness.mark_notification_forwarded();
                            let snapshot = secondary_liveness.snapshot();
                            tracing::info!(
                                "MQTT SECONDARY STATUS: broker=emqx state=connected connacks={} polls={}/{} poll_errors={} mode=dual_publish max_fanout=2",
                                snapshot.connacks,
                                snapshot.polls_completed,
                                snapshot.polls_started,
                                snapshot.poll_errors
                            );
                        }
                        Ok(_) => {
                            reconnect_backoff_secs = 1;
                        }
                        Err(error) => {
                            secondary_ready.store(false, Ordering::Release);
                            secondary_liveness.mark_poll_error();
                            secondary_liveness.mark_backoff();
                            tracing::warn!(
                                "MQTT SECONDARY STATUS: broker=emqx state=backoff retry_in={}s error={}",
                                reconnect_backoff_secs,
                                error
                            );
                            tokio::time::sleep(Duration::from_secs(reconnect_backoff_secs)).await;
                            reconnect_backoff_secs = reconnect_backoff_secs
                                .saturating_mul(2)
                                .min(MQTT_RECONNECT_BACKOFF_MAX_SECS);
                        }
                    }
                }
                secondary_ready.store(false, Ordering::Release);
                secondary_liveness.mark_stopped();
            }));
        }

        self.eventloop_task = Some(tokio::spawn(async move {
            let mut reconnect_backoff_secs = 1u64;
            let mut initial_connack_tx = Some(initial_connack_tx);
            let exit_reason = loop {
                // The first ConnAck remains pollable even with inherited pending events. After it,
                // reserve one core-owned slot before each poll. A critical packet transfers that
                // permit into the queue; all other packet types release it at the end of the arm.
                let inbox_permit = if initial_connack_tx.is_none() {
                    let waited = eventloop_shared_state.loss_intolerant_inbox.is_full();
                    if waited {
                        let pending = eventloop_shared_state.loss_intolerant_inbox.len();
                        let total_waits = eventloop_liveness.mark_loss_intolerant_backpressure();
                        if should_log_bounded_counter(total_waits) {
                            tracing::warn!(
                                "MQTT LOSS-INTOLERANT BACKPRESSURE: total={} pending={} capacity={}",
                                total_waits,
                                pending,
                                eventloop_shared_state.loss_intolerant_inbox.capacity()
                            );
                        }
                    }
                    let permit = eventloop_shared_state
                        .loss_intolerant_inbox
                        .reserve_owned()
                        .await;
                    if waited {
                        eventloop_liveness.mark_loss_intolerant_capacity_available();
                    }
                    Some(permit)
                } else {
                    None
                };

                eventloop_liveness.mark_poll_started();
                let poll_result = eventloop.poll().await;
                eventloop_liveness.mark_poll_completed();

                match poll_result {
                    Ok(Event::Incoming(Packet::Publish(publish))) => {
                        reconnect_backoff_secs = 1;
                        let topic = publish.topic;
                        let payload = publish.payload;
                        if let Err(reason) = forward_incoming_publish(
                            "hivemq",
                            topic,
                            payload.as_ref(),
                            inbox_permit,
                            &event_tx,
                            &eventloop_shared_state,
                            &eventloop_liveness,
                        )
                        .await
                        {
                            break reason;
                        }
                    }
                    Ok(Event::Incoming(Packet::ConnAck(_))) => {
                        reconnect_backoff_secs = 1;
                        #[cfg(feature = "mqtt-dual-broker")]
                        eventloop_primary_ready.store(false, Ordering::Release);
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
                        #[cfg(feature = "mqtt-dual-broker")]
                        eventloop_primary_ready.store(false, Ordering::Release);
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

            #[cfg(feature = "mqtt-dual-broker")]
            eventloop_primary_ready.store(false, Ordering::Release);
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
                                "MQTT LIVENESS: EventLoop task exited: reason={} phase={} polls={}/{} incoming={} connacks={} forwarded={} best_effort_drops={} loss_intolerant_buffered={} loss_intolerant_pending={} loss_intolerant_backpressure={} poll_errors={} request_timeouts={} request_errors={}",
                                reason,
                                snapshot.phase.as_str(),
                                snapshot.polls_completed,
                                snapshot.polls_started,
                                snapshot.incoming_publishes,
                                snapshot.connacks,
                                snapshot.notifications_forwarded,
                                snapshot.best_effort_drops,
                                snapshot.loss_intolerant_buffered,
                                snapshot.loss_intolerant_pending,
                                snapshot.loss_intolerant_backpressure,
                                snapshot.poll_errors,
                                snapshot.request_timeouts,
                                snapshot.request_errors
                            ),
                            Err(_) if watchdog_shutdown_requested.load(Ordering::Acquire) => {
                                tracing::info!("MQTT LIVENESS: EventLoop task closed during requested shutdown")
                            }
                            Err(_) => tracing::error!(
                                "MQTT LIVENESS: EventLoop task ended without completion signal; possible panic/abort; phase={} phase_age_ms={} progress_age_ms={} polls={}/{} incoming={} connacks={} forwarded={} best_effort_drops={} loss_intolerant_buffered={} loss_intolerant_pending={} loss_intolerant_backpressure={} poll_errors={} request_timeouts={} request_errors={}",
                                snapshot.phase.as_str(),
                                snapshot.phase_age_ms(),
                                snapshot.progress_age_ms(),
                                snapshot.polls_completed,
                                snapshot.polls_started,
                                snapshot.incoming_publishes,
                                snapshot.connacks,
                                snapshot.notifications_forwarded,
                                snapshot.best_effort_drops,
                                snapshot.loss_intolerant_buffered,
                                snapshot.loss_intolerant_pending,
                                snapshot.loss_intolerant_backpressure,
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
                                        "MQTT LIVENESS STALLED: phase={} phase_age_ms={} progress_age_ms={} polls={}/{} incoming={} connacks={} forwarded={} best_effort_drops={} loss_intolerant_buffered={} loss_intolerant_pending={} loss_intolerant_backpressure={} poll_errors={} request_timeouts={} request_errors={}",
                                        phase.as_str(),
                                        stalled_for_ms,
                                        snapshot.progress_age_ms(),
                                        snapshot.polls_completed,
                                        snapshot.polls_started,
                                        snapshot.incoming_publishes,
                                        snapshot.connacks,
                                        snapshot.notifications_forwarded,
                                        snapshot.best_effort_drops,
                                        snapshot.loss_intolerant_buffered,
                                        snapshot.loss_intolerant_pending,
                                        snapshot.loss_intolerant_backpressure,
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
                                "MQTT LIVENESS HEARTBEAT: phase={} phase_age_ms={} progress_age_ms={} polls={}/{} incoming={} connacks={} forwarded={} best_effort_drops={} loss_intolerant_buffered={} loss_intolerant_pending={} loss_intolerant_backpressure={} poll_errors={} request_timeouts={} request_errors={}",
                                snapshot.phase.as_str(),
                                snapshot.phase_age_ms(),
                                snapshot.progress_age_ms(),
                                snapshot.polls_completed,
                                snapshot.polls_started,
                                snapshot.incoming_publishes,
                                snapshot.connacks,
                                snapshot.notifications_forwarded,
                                snapshot.best_effort_drops,
                                snapshot.loss_intolerant_buffered,
                                snapshot.loss_intolerant_pending,
                                snapshot.loss_intolerant_backpressure,
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
        #[cfg(feature = "mqtt-dual-broker")]
        self.primary_ready.store(true, Ordering::Release);
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
        #[cfg(feature = "mqtt-dual-broker")]
        {
            let outcome = self
                .enqueue_publish_to_targets(
                    "mesh receipt publish",
                    &topic,
                    QoS::AtLeastOnce,
                    true,
                    payload.as_bytes().to_vec(),
                    self.active_brokers(),
                    false,
                )
                .await?;
            let mut ledger = self
                .shared_state
                .retained_targets
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            ledger.record(msg_id, outcome.queued);
            return Ok(());
        }
        #[cfg(not(feature = "mqtt-dual-broker"))]
        {
            self.enqueue_publish(
                "mesh receipt publish",
                &topic,
                QoS::AtLeastOnce,
                true,
                payload.as_bytes().to_vec(),
            )
            .await
        }
    }

    /// Online relay nodes тоже должны удалить custody сразу после доставки.
    /// Cleanup fanout non-retained: не создаёт вечный retained мусор; offline
    /// relay в худшем случае удалит запись по исходному TTL.
    pub async fn send_mesh_cleanup_receipt(
        &self,
        msg_id: &str,
        payload: &str,
    ) -> Result<(), String> {
        let topic = mesh_cleanup_receipt_topic(msg_id)?;
        #[cfg(feature = "mqtt-dual-broker")]
        {
            self.enqueue_publish_to_targets(
                "mesh cleanup receipt publish",
                &topic,
                QoS::AtLeastOnce,
                false,
                payload.as_bytes().to_vec(),
                self.active_brokers(),
                false,
            )
            .await?;
            return Ok(());
        }
        #[cfg(not(feature = "mqtt-dual-broker"))]
        {
            self.enqueue_publish(
                "mesh cleanup receipt publish",
                &topic,
                QoS::AtLeastOnce,
                false,
                payload.as_bytes().to_vec(),
            )
            .await
        }
    }

    /// Только локальный origin имеет право очистить свой retained receipt.
    pub fn is_own_mesh_receipt_topic(&self, topic: &str, msg_id: &str) -> bool {
        mesh_receipt_topic(&self.node_id, msg_id)
            .map(|expected| expected == topic)
            .unwrap_or(false)
    }

    pub async fn clear_own_mesh_receipt(&self, msg_id: &str) -> Result<(), String> {
        let topic = mesh_receipt_topic(&self.node_id, msg_id)?;
        #[cfg(feature = "mqtt-dual-broker")]
        {
            let recorded_targets = {
                let ledger = self
                    .shared_state
                    .retained_targets
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                ledger.targets(msg_id)
            };
            // The publishing phone's active-broker mask is not carried in the legacy-compatible
            // receipt envelope. Clearing the exact configured pair is a safe bounded superset and
            // prevents an offline secondary retained copy from surviving a primary-only cleanup.
            let targets = recorded_targets
                .union(MqttBrokerSet::PRIMARY)
                .union(MqttBrokerSet::SECONDARY);
            {
                let mut ledger = self
                    .shared_state
                    .retained_targets
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                ledger.record(msg_id, targets);
            }
            let outcome = self
                .enqueue_publish_to_targets(
                    "mesh receipt clear",
                    &topic,
                    QoS::AtLeastOnce,
                    true,
                    Vec::new(),
                    targets,
                    true,
                )
                .await?;
            if outcome.all_attempted_queued() {
                let mut ledger = self
                    .shared_state
                    .retained_targets
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                ledger.remove(msg_id);
                return Ok(());
            }
            return Err(format!(
                "MQTT retained receipt clear pending: attempted={} queued={}",
                outcome.attempted.len(),
                outcome.queued.len()
            ));
        }
        #[cfg(not(feature = "mqtt-dual-broker"))]
        {
            self.enqueue_publish(
                "mesh receipt clear",
                &topic,
                QoS::AtLeastOnce,
                true,
                Vec::new(),
            )
            .await
        }
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
        // Delivery-bearing events always bypass refreshable FIFO traffic and remain owned by this
        // core-level inbox across a transport/session replacement.
        if let Some((event, remaining)) = self.shared_state.loss_intolerant_inbox.pop() {
            self.liveness.mark_loss_intolerant_drained(remaining);
            return Some(event);
        }

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
                        Ok(_) => {
                            #[cfg(feature = "mqtt-dual-broker")]
                            self.primary_ready.store(true, Ordering::Release);
                            tracing::info!(
                                "MQTT: connection restored; subscription requested for p2pm2/# (node={})",
                                self.node_id
                            );
                        }
                        Err(e) => {
                            #[cfg(feature = "mqtt-dual-broker")]
                            self.primary_ready.store(false, Ordering::Release);
                            tracing::warn!(
                                "MQTT: connection restored but subscription request failed: {}",
                                e
                            );
                        }
                    }
                } else {
                    self.has_connected_once = true;
                    tracing::info!("MQTT: connection acknowledged by broker");
                }
                None
            }
            #[cfg(feature = "mqtt-dual-broker")]
            Ok(Some(MqttNotification::SecondaryConnectionAcknowledged)) => {
                self.secondary_ready.store(false, Ordering::Release);
                match self.enqueue_secondary_subscribe().await {
                    Ok(()) => {
                        self.secondary_ready.store(true, Ordering::Release);
                        tracing::info!(
                            "MQTT SECONDARY READY: broker=emqx ConnAck=true subscription_request=true mode=dual_publish max_fanout=2"
                        );
                    }
                    Err(error) => {
                        tracing::warn!(
                            "MQTT SECONDARY STATUS: broker=emqx state=degraded subscription_request=false error={}",
                            error
                        );
                    }
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
        #[cfg(feature = "mqtt-dual-broker")]
        {
            self.primary_ready.store(false, Ordering::Release);
            self.secondary_ready.store(false, Ordering::Release);
            if let Some(task) = self.secondary_eventloop_task.take() {
                task.abort();
            }
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
    fn r4_broker_set_is_exactly_hivemq_then_emqx() {
        assert_eq!(
            MQTT_BROKERS,
            &[("broker.hivemq.com", 1883), ("broker.emqx.io", 1883)]
        );
    }

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
    fn cleanup_receipt_topic_is_shared_non_origin_path() {
        let topic = mesh_cleanup_receipt_topic("message/with+#wildcards").unwrap();
        assert!(topic.starts_with("p2pm2/receipt/cleanup/"));
        assert_eq!(topic, mesh_cleanup_receipt_topic("message/with+#wildcards").unwrap());
        assert!(!topic.contains("message/with+#wildcards"));
        assert!(mesh_cleanup_receipt_topic("").is_err());
        assert!(mesh_cleanup_receipt_topic(&"m".repeat(257)).is_err());
    }

    #[test]
    fn receipt_topic_rejects_unsafe_origin_and_unbounded_message_id() {
        assert!(mesh_receipt_topic("pk_origin/other", "m1").is_err());
        assert!(mesh_receipt_topic("pk_origin+", "m1").is_err());
        assert!(mesh_receipt_topic("pk_origin", "").is_err());
        assert!(mesh_receipt_topic("pk_origin", &"m".repeat(257)).is_err());
    }
}

