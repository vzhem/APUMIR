use std::collections::{HashMap, HashSet, VecDeque};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use crate::ffi::crypto_ffi::CryptoManager;
use crate::ffi::network_ffi::{NetworkManagerFfi, NetworkStatus, PeerInfo};
use crate::ffi::storage_ffi::StorageManagerFfi;
use crate::storage::models::MessageStatus;

use super::events::{CoreEvent, EventBus};
use crate::network::connection_pool::ConnectionPool;
use crate::network::router::Router;
use crate::network::dht::{RoutingTable, DhtNodeInfo};
use crate::network::relay::RelayManager;
use crate::network::presence::PresenceManager;
use crate::network::message_queue::MessageQueue;
use crate::network::offline_send::prepare_offline_relay;
use crate::network::relay_queue::{
    RelayMessage, RelayQueue, DEFAULT_RELAY_TTL, MAX_MESH_RELAY_ENVELOPE_BYTES, MAX_TOTAL,
};
use crate::network::wire::MeshEnvelope;
use crate::storage::relay_at_rest::{self as at_rest, RelayAtRestKeySource};
use crate::storage::relay_store::RelayStore;
use crate::network::adaptive_polling::AdaptivePolling;

const MQTT_OUTBOUND_COMMAND_CAPACITY: usize = 256;

#[derive(Debug)]
enum MqttOutboundCommand {
    MeshRelay {
        recipient: String,
        envelope: String,
        message_id: String,
    },
    /// Немедленно объявить себя и запросить presence остальных (кнопка
    /// «Собрать данные об абонентах»). Обычный цикл делает это раз в 30 секунд.
    AnnounceNow,
}

// ═══════════════════════════════════════════════════════════════════
// M8-B/D/C: durable encrypted relay custody helpers
// ═══════════════════════════════════════════════════════════════════

/// M8-C slice 3: relay custody bundle — зашифрованное хранилище + источник
/// device-ключа. Все записи пишутся/читаются только через encrypted API
/// (`store_encrypted` / `load_*_encrypted` / `remove_encrypted*` /
/// `purge_expired_encrypted`). Общие tombstone-таблицы (ненуждающиеся в
/// шифровании пары msg_id+время) остаются открытыми по дизайну.
pub(crate) struct RelayCustody {
    pub store: RelayStore,
    pub keys: Arc<dyn RelayAtRestKeySource>,
    /// true = зашифрованный durable-файл на диске; false = RAM-only degrade
    /// (честно логируется; custody не переживает restart — как v11.16.16).
    pub durable: bool,
}

/// Открыть relay custody. Правила M8-C:
/// - Ключ установлен (Keystore-мост) + путь задан → зашифрованный durable-файл.
/// - Ключ установлен, пути нет (тесты/дефолт) → in-memory, тот же encrypted API.
/// - Ключа нет → честный RAM-only с эфемерным ключом: durable-файл без ключа
///   сознательно НЕ создаётся (незашифрованная durable-запись запрещена, а
///   «durable, зашифрованное одноразовым ключом» было бы ложной семантикой).
/// - Ошибка открытия файла → in-memory с тем же ключом (RAM-only, warn).
fn open_relay_custody(db_path: Option<&str>) -> RelayCustody {
    match (db_path, at_rest::installed_key_source()) {
        (Some(path), Some(keys)) => {
            // `EngineConfig.relay_db_path` is an exact file path supplied by the
            // Android host. Do not append another suffix: backup exclusions and
            // recovery tooling must refer to the file that is actually opened.
            let relay_path = path.to_owned();
            match RelayStore::open(&relay_path) {
                Ok(store) => {
                    tracing::info!(
                        "MESH durable: encrypted relay store opened at {} (key_id={})",
                        relay_path,
                        keys.key_id()
                    );
                    RelayCustody {
                        store,
                        keys,
                        durable: true,
                    }
                }
                Err(e) => {
                    tracing::warn!(
                        "MESH durable: cannot open relay store at {} ({}); relay custody is RAM-only this session",
                        relay_path,
                        e
                    );
                    RelayCustody {
                        store: RelayStore::open_in_memory()
                            .expect("in-memory relay store must open"),
                        keys,
                        durable: false,
                    }
                }
            }
        }
        (None, Some(keys)) => {
            tracing::info!("MESH durable: no db path; encrypted relay custody is in-memory");
            RelayCustody {
                store: RelayStore::open_in_memory().expect("in-memory relay store must open"),
                keys,
                durable: false,
            }
        }
        (path_opt, None) => {
            if path_opt.is_some() {
                // Keystore недоступен / ключ ещё не установлен. НЕ создаём
                // durable-файл: честный RAM-only вместо незашифрованного диска.
                tracing::warn!(
                    "MESH durable: at-rest key unavailable (Keystore); relay custody is RAM-only this session (durable custody honestly disabled, no plaintext file written)"
                );
            }
            RelayCustody {
                store: RelayStore::open_in_memory().expect("in-memory relay store must open"),
                keys: at_rest::ephemeral_key_source(),
                durable: false,
            }
        }
    }
}

/// Восстановить RAM RelayQueue из durable-хранилища при старте (M8-D),
/// записи дешифруются at-rest конвертом (M8-C).
///
/// - Сначала удаляются истёкшие записи (БЕЗ доставки в UI), включая истёкший
///   карантин.
/// - Затем загружаются не-истёкшие (bounded, `MAX_TOTAL`): decrypt →
///   deserialize → `validate_durable`. Нерасшифровываемые/невалидные строки
///   уходят в quarantine атомарно (не молча, не в UI) и логируются.
/// - TTL НЕ продлевается: записи восстанавливаются с исходным абсолютным
///   `expires_at_ms`; дедуп/hop сохраняются самим содержимым записи.
///
/// Ошибки не роняют движок: восстановление best-effort, логируется.
fn restore_relay_custody(
    relay_queue: Option<&Arc<RelayQueue>>,
    relay_custody: Option<&Arc<RelayCustody>>,
) {
    let (queue, custody) = match (relay_queue, relay_custody) {
        (Some(q), Some(s)) => (q, s),
        _ => return,
    };

    let now_ms = crate::network::relay_queue::utc_now_ms();

    match custody.store.purge_expired_encrypted(now_ms) {
        Ok((0, 0)) => {}
        Ok((records, quarantined)) => tracing::info!(
            "MESH durable: purged {} expired relay record(s) (+{} quarantined) at startup",
            records,
            quarantined
        ),
        Err(e) => tracing::warn!("MESH durable: purge_expired failed at startup: {}", e),
    }

    match custody
        .store
        .load_unexpired_encrypted(&*custody.keys, now_ms, MAX_TOTAL)
    {
        Ok(outcome) => {
            if outcome.quarantined > 0 {
                // Честная потеря: расшифровать нельзя (data clear / другой ключ /
                // повреждение). Строки не загружаются и не удаляются молча — они
                // в quarantine-таблице для диагностики; UI их не видит.
                tracing::warn!(
                    "MESH durable: {} relay record(s) quarantined at startup (undecryptable/invalid; custody honestly lost for them)",
                    outcome.quarantined
                );
            }
            let total = outcome.records.len();
            let mut restored = 0usize;
            for record in outcome.records {
                match queue.enqueue(record) {
                    Ok(true) => restored += 1,
                    Ok(false) => {} // дубль/исчерпанный hop — ожидаемо пропускаем
                    Err(e) => {
                        tracing::warn!("MESH durable: restore enqueue failed: {}", e);
                        break; // лимит очереди — дальше восстанавливать бессмысленно
                    }
                }
            }
            if total > 0 || restored > 0 {
                tracing::info!(
                    "MESH durable: restored {}/{} relay record(s) after startup",
                    restored,
                    total
                );
            }
        }
        Err(e) => tracing::warn!("MESH durable: startup load failed: {}", e),
    }
}

fn remember_bounded_id(
    entries: &mut HashSet<String>,
    order: &mut VecDeque<String>,
    id: &str,
    capacity: usize,
) {
    if capacity == 0 || entries.contains(id) {
        return;
    }
    while order.len() >= capacity {
        if let Some(oldest) = order.pop_front() {
            entries.remove(&oldest);
        }
    }
    let owned = id.to_string();
    entries.insert(owned.clone());
    order.push_back(owned);
}

fn remember_bounded_delivery(
    entries: &mut HashMap<String, String>,
    order: &mut VecDeque<String>,
    msg_id: &str,
    origin: &str,
    capacity: usize,
) {
    if capacity == 0 || entries.contains_key(msg_id) {
        return;
    }
    while order.len() >= capacity {
        if let Some(oldest) = order.pop_front() {
            entries.remove(&oldest);
        }
    }
    entries.insert(msg_id.to_string(), origin.to_string());
    order.push_back(msg_id.to_string());
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EngineState {
    Uninitialized,
    Starting,
    Running,
    Stopped,
    Failed(String),
}

impl EngineState {
    pub fn is_running(&self) -> bool {
        matches!(self, EngineState::Running)
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            EngineState::Uninitialized => "uninitialized",
            EngineState::Starting => "starting",
            EngineState::Running => "running",
            EngineState::Stopped => "stopped",
            EngineState::Failed(_) => "failed",
        }
    }
}

impl std::fmt::Display for EngineState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.as_str())
    }
}

#[derive(Debug, Clone)]
pub struct EngineConfig {
    pub db_path: Option<String>,
    /// M8-C slice 3: отдельный путь для durable encrypted relay custody.
    /// Намеренно НЕ переиспользует `db_path` основного storage: legacy-поведение
    /// основного хранилища (in-memory) не меняется, а relay custody получает
    /// собственный SQLite-файл (`<relay_db_path>` на диске, encrypted schema v2).
    pub relay_db_path: Option<String>,
    pub display_name: String,
    pub existing_public_key: Option<String>,
    pub existing_private_key: Option<String>,
    pub event_bus_size: usize,
    pub quic_port: u16,
}

impl Default for EngineConfig {
    fn default() -> Self {
        Self {
            db_path: None,
            relay_db_path: None,
            display_name: "Anonymous".into(),
            existing_public_key: None,
            existing_private_key: None,
            event_bus_size: 1000,
            quic_port: 7777,
        }
    }
}

impl EngineConfig {
    pub fn new(display_name: String) -> Self {
        Self {
            display_name,
            ..Default::default()
        }
    }

    pub fn with_db(mut self, path: String) -> Self {
        self.db_path = Some(path);
        self
    }

    pub fn with_relay_db(mut self, path: String) -> Self {
        self.relay_db_path = Some(path);
        self
    }

    pub fn with_keys(mut self, public_key: String, private_key: String) -> Self {
        self.existing_public_key = Some(public_key);
        self.existing_private_key = Some(private_key);
        self
    }
}

pub struct P2PCore {
    state: EngineState,
    config: EngineConfig,
    crypto: Arc<CryptoManager>,
    network: Arc<NetworkManagerFfi>,
    storage: Arc<StorageManagerFfi>,
    events: Arc<EventBus>,
    runtime: Option<tokio::runtime::Runtime>,
    peer_addrs: Arc<Mutex<HashMap<String, SocketAddr>>>,
    public_addr: Arc<Mutex<Option<SocketAddr>>>,
    connection_pool: Arc<ConnectionPool>,
    router: Option<Arc<Router>>,
    dht: Option<Arc<Mutex<RoutingTable>>>,
    relay: Option<Arc<RelayManager>>,
    presence: Option<Arc<PresenceManager>>,
    message_queue: Option<Arc<MessageQueue>>,
    relay_queue: Option<Arc<RelayQueue>>,
    relay_custody: Option<Arc<RelayCustody>>,
    mqtt_outbound_tx: Option<tokio::sync::mpsc::Sender<MqttOutboundCommand>>,
    adaptive_polling: Arc<Mutex<AdaptivePolling>>,
    node_id_str: Option<String>,
}

impl P2PCore {
    pub fn new(config: EngineConfig) -> Self {
        Self {
            state: EngineState::Uninitialized,
            config,
            crypto: Arc::new(CryptoManager::new()),
            network: Arc::new(NetworkManagerFfi::new()),
            storage: Arc::new(StorageManagerFfi::new()),
            events: Arc::new(EventBus::with_defaults()),
            runtime: None,
            peer_addrs: Arc::new(Mutex::new(HashMap::new())),
            public_addr: Arc::new(Mutex::new(None)),
            connection_pool: Arc::new(ConnectionPool::new(256)),
            router: None,
            dht: None,
            relay: None,
            presence: None,
            message_queue: None,
            relay_queue: None,
            relay_custody: None,
            mqtt_outbound_tx: None,
            adaptive_polling: Arc::new(Mutex::new(AdaptivePolling::with_defaults())),
            node_id_str: None,
        }
    }

    pub fn with_defaults() -> Self {
        Self::new(EngineConfig::default())
    }

    pub fn state(&self) -> &EngineState {
        &self.state
    }

    pub fn is_running(&self) -> bool {
        self.state.is_running()
    }

    pub fn start(&mut self) -> bool {
        if self.state.is_running() {
            return true;
        }

        self.state = EngineState::Starting;

        let storage_result = match &self.config.db_path {
            Some(path) => self.storage.init(path),
            None => self.storage.init_in_memory(),
        };

        if !storage_result.is_ok() {
            self.state = EngineState::Failed("Storage init failed".into());
            self.events.emit(CoreEvent::Error {
                code: "STORAGE_INIT".into(),
                message: "Failed to initialize storage".into(),
            });
            return false;
        }

        let node_id = match (
            &self.config.existing_public_key,
            &self.config.existing_private_key,
        ) {
            (Some(pk), Some(sk)) => {
                if self.crypto.load_keys(pk.clone(), sk.clone()) {
                    pk.clone()
                } else {
                    self.crypto.generate_keys()
                }
            }
            _ => self.crypto.generate_keys(),
        };

        self.events.emit(CoreEvent::KeysGenerated {
            public_key: node_id.clone(),
        });

        self.node_id_str = Some(node_id.clone());

        // Initialize network stack modules
        {
            use sha2::{Sha256, Digest};
            let mut hasher = Sha256::new();
            hasher.update(node_id.as_bytes());
            let hash = hasher.finalize();
            let mut nid = [0u8; 32];
            nid.copy_from_slice(&hash);
            self.router = Some(Arc::new(Router::new(nid)));
            self.dht = Some(Arc::new(Mutex::new(RoutingTable::new(nid))));
            self.relay = Some(Arc::new(RelayManager::new(nid)));
            self.presence = Some(Arc::new(PresenceManager::new(nid)));
            self.message_queue = Some(Arc::new(MessageQueue::new()));
            self.relay_queue = Some(Arc::new(RelayQueue::new()));
            // M8-C: custody всегда encrypted; durable — только если Keystore-мост
            // установил ключ И задан relay_db_path (см. open_relay_custody).
            self.relay_custody = Some(Arc::new(open_relay_custody(
                self.config.relay_db_path.as_deref(),
            )));
            let _ = 0; // modules initialized
        }
        tracing::info!("Network stack initialized: Router+DHT+Relay+Presence+Queue+RelayQueue+RelayStore");

        // M8-B/D/C: durable encrypted relay custody. Восстанавливаем RAM
        // RelayQueue из RelayStore после process death/reboot: берём только
        // не-истёкшие расшифрованные записи (bounded), истёкшие удаляем БЕЗ
        // доставки в UI, нерасшифровываемые — в quarantine. Абсолютный
        // expires_at_ms при этом НЕ продлевается — дедлайн сохраняется как был.
        restore_relay_custody(self.relay_queue.as_ref(), self.relay_custody.as_ref());

        let _ = self
            .storage
            .save_user(node_id.clone(), self.config.display_name.clone(), true);

        if !self.network.start(node_id.clone()) {
            self.state = EngineState::Failed("Network init failed".into());
            self.events.emit(CoreEvent::Error {
                code: "NETWORK_INIT".into(),
                message: "Failed to initialize network".into(),
            });
            return false;
        }

        self.start_async_runtime(node_id.clone());

        self.state = EngineState::Running;
        self.events.emit(CoreEvent::EngineStarted {
            node_id: node_id.clone(),
        });

        true
    }

    fn start_async_runtime(&mut self, node_id: String) {
        let events_arc = Arc::clone(&self.events);
        let network_arc = Arc::clone(&self.network);
        let peer_addrs_arc = Arc::clone(&self.peer_addrs);
        let public_addr_arc = Arc::clone(&self.public_addr);
        let dht2 = self.dht.clone();
        let queue2 = self.message_queue.clone();
        let relay_queue2 = self.relay_queue.clone();
        let relay_custody2 = self.relay_custody.clone();
        let display_name = self.config.display_name.clone();
        let quic_port = self.config.quic_port;

        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build();

        match rt {
            Err(e) => {
                tracing::error!("Failed to create tokio runtime: {}", e);
            }
            Ok(runtime) => {
                let (mqtt_outbound_tx, mqtt_outbound_rx) =
                    tokio::sync::mpsc::channel(MQTT_OUTBOUND_COMMAND_CAPACITY);
                self.mqtt_outbound_tx = Some(mqtt_outbound_tx);

                let events_quic = Arc::clone(&events_arc);
                let network_quic = Arc::clone(&network_arc);
                let node_id_quic = node_id.clone();

                let events_mdns = Arc::clone(&events_arc);
                let network_mdns = Arc::clone(&network_arc);
                let peer_addrs_mdns = Arc::clone(&peer_addrs_arc);
                let node_id_mdns = node_id.clone();
                let display_mdns = display_name.clone();

                let events_tcp = Arc::clone(&events_arc);
                let network_tcp = Arc::clone(&network_arc);

                runtime.spawn(async move {
                    Self::run_quic_listener(events_quic, network_quic, node_id_quic, quic_port).await;
                });

                runtime.spawn(async move {
                    Self::run_tcp_listener(events_tcp, network_tcp, 7778).await;
                });

                // MQTT transport (internet fallback) вЂ” separate thread (EventLoop not Send)
                let events_mqtt = Arc::clone(&events_arc);
                let network_mqtt = Arc::clone(&network_arc);
                let peer_addrs_mqtt = Arc::clone(&peer_addrs_arc);
                let node_id_mqtt = node_id.clone();
                let display_mqtt = display_name.clone();
                let public_addr_mqtt = Arc::clone(&public_addr_arc);
                let queue_mqtt = queue2.clone();
                let relay_queue_mqtt = relay_queue2.clone();
                let relay_custody_mqtt = relay_custody2.clone();
                std::thread::spawn(move || {
                    let rt = tokio::runtime::Builder::new_current_thread()
                        .enable_all()
                        .build()
                        .unwrap();
                    rt.block_on(async move {
                        Self::run_mqtt_transport(
                            events_mqtt,
                            network_mqtt,
                            peer_addrs_mqtt,
                            node_id_mqtt,
                            display_mqtt,
                            public_addr_mqtt,
                            queue_mqtt,
                            relay_queue_mqtt,
                            relay_custody_mqtt,
                            mqtt_outbound_rx,
                        ).await;
                    });
                });

                runtime.spawn(async move {
                    Self::run_mdns_discovery(
                        events_mdns,
                        network_mdns,
                        peer_addrs_mdns,
                        node_id_mdns,
                        display_mdns,
                        quic_port,
                        dht2,
                        queue2,
                    ).await;
                });

                
                // STUN: discover external address
                let public_addr_stun = Arc::clone(&public_addr_arc);
                runtime.spawn(async move {
                    Self::run_stun_discovery(public_addr_stun).await;
                });
self.runtime = Some(runtime);
                tracing::info!("Async runtime started (mDNS + QUIC)");
            }
        }
    }

    async fn run_quic_listener(
        events: Arc<EventBus>,
        network: Arc<NetworkManagerFfi>,
        _node_id: String,
        port: u16,
    ) {
        use crate::network::quic_client::QuicClient;
        use std::net::{IpAddr, Ipv4Addr};

        let bind_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), port);

        let client = match QuicClient::new(bind_addr) {
            Ok(c) => {
                tracing::info!("QUIC listener started on {:?}", c.local_address());
                network.set_status(NetworkStatus::Connecting);
                c
            }
            Err(e) => {
                tracing::error!("QUIC listener failed to start: {}", e);
                return;
            }
        };

        loop {
            match client.accept().await {
                Err(e) => {
                    tracing::warn!("QUIC accept error: {}", e);
                    break;
                }
                Ok(conn) => {
                    let peer_addr = conn.remote_address().to_string();
                    tracing::info!("QUIC: incoming connection from {}", peer_addr);

                    let events2 = Arc::clone(&events);
                    let network2 = Arc::clone(&network);

                    tokio::spawn(async move {
                        let mut ping_interval = tokio::time::interval(std::time::Duration::from_secs(15));
        ping_interval.tick().await; // skip first immediate tick

    loop {
                            match conn.receive_message().await {
                                Err(_) => break,
                                Ok(payload) => {
                                    let decoded = String::from_utf8_lossy(&payload);
                                    let parts: Vec<&str> = decoded.splitn(4, '|').collect();

                                    if parts.len() == 4 {
                                        let sender_id = parts[0].to_string();
                                        let message_id = parts[1].to_string();
                                        let chat_id = parts[2].to_string();
                                        let text = parts[3].to_string();

                                        tracing::info!(
                                            "QUIC: received message from {} in chat {} text={}",
                                            sender_id, chat_id, text
                                        );

                                        network2.add_peer(PeerInfo::new(
                                            sender_id.clone(),
                                            "Unknown".into(),
                                        ));
                                        network2.touch_peer(&sender_id);
                                        network2.set_status(NetworkStatus::Connected);

                                        events2.emit(CoreEvent::MessageReceived {
                                            message_id,
                                            chat_id,
                                            sender_id,
                                            text,
                                            timestamp: crate::storage::models::now_ms(),
                                        });
                                    } else {
                                        tracing::warn!("QUIC: invalid payload format: {}", decoded);
                                    }
                                }
                            }
                        }
                    });
                }
            }
        }
    }

    async fn run_mdns_discovery(
        events: Arc<EventBus>,
        network: Arc<NetworkManagerFfi>,
        peer_addrs: Arc<Mutex<HashMap<String, SocketAddr>>>,
        node_id: String,
        display_name: String,
        port: u16,
        dht: Option<Arc<Mutex<RoutingTable>>>,
        dht_queue: Option<Arc<crate::network::message_queue::MessageQueue>>,
    ) {
        use crate::network::mdns::MdnsService;
        use std::time::Duration;

        let mdns = match MdnsService::new() {
            Ok(m) => m,
            Err(e) => {
                tracing::error!("mDNS init failed: {}", e);
                return;
            }
        };

        // STUN: get external address for mDNS TXT record
        let public_addr_str = {
            use crate::network::ice::StunClient;
            let stun_servers: &[&str] = &["stun.cloudflare.com:3478", "stun.nextcloud.com:443"];
            match tokio::task::spawn_blocking(move || {
                StunClient::get_external_address_from_any(stun_servers)
            }).await {
                Ok(Ok(addr)) => {
                    tracing::info!("mDNS-STUN: external = {}", addr);
                    Some(addr.to_string())
                }
                _ => None,
            }
        };

        if let Err(e) = mdns.publish_self(&node_id, port, &display_name, 1, public_addr_str.as_deref()).await {
            tracing::warn!("mDNS publish failed: {}", e);
        } else {
            tracing::info!("mDNS: published self as {} port {}", display_name, port);
        }

        // Bounded re-publish: the one-shot announcement dies when the phone switches
        // networks (Wi-Fi/hotspot/mobile), leaving it invisible to LAN peers. Refresh the
        // service so LAN-direct delivery keeps working after network changes.
        let mut last_publish = std::time::Instant::now();
        loop {
            if last_publish.elapsed() >= Duration::from_secs(60) {
                let _ = mdns.unpublish().await;
                match mdns
                    .publish_self(&node_id, port, &display_name, 1, public_addr_str.as_deref())
                    .await
                {
                    Ok(()) => {
                        tracing::info!("mDNS: re-published self as {} port {}", display_name, port)
                    }
                    Err(e) => tracing::warn!("mDNS re-publish failed: {}", e),
                }
                last_publish = std::time::Instant::now();
            }
            match mdns.discover(Duration::from_secs(5)).await {
                Err(e) => tracing::warn!("mDNS discover error: {}", e),
                Ok(nodes) => {
                    for node in nodes {
                        if let Some(ref nid) = node.node_id_hex {
                            if nid == &node_id {
                                continue;
                            }
                        }

                        let peer_id = node.node_id_hex.clone().unwrap_or(node.full_name.clone());
                        let peer_name = node.name.clone();
                        let peer_addr = node.address;

                        tracing::info!("mDNS: discovered peer {} at {}", peer_id, peer_addr);

                        peer_addrs.lock().unwrap().insert(peer_id.clone(), peer_addr);

                        // Update DHT routing table
                        if let Some(ref dht_table) = dht {
                            use sha2::{Sha256, Digest};
                            let mut h = Sha256::new();
                            h.update(peer_id.as_bytes());
                            let hash = h.finalize();
                            let mut nid = [0u8; 32];
                            nid.copy_from_slice(&hash);
                            dht_table.lock().unwrap().add_or_update(DhtNodeInfo {
                                node_id: nid,
                                address: peer_addr.to_string(),
                            });
                        }
                        // Retry queued messages for this peer
                        if let Some(ref queue) = dht_queue {
                            use sha2::{Sha256, Digest};
                            let mut rh = Sha256::new();
                            rh.update(peer_id.as_bytes());
                            let rhash = rh.finalize();
                            let mut rid = [0u8; 32];
                            rid.copy_from_slice(&rhash);
                            let queue2 = Arc::clone(queue);
                            let peer_addr2 = peer_addr;
                            tokio::spawn(async move {
                                let msgs = queue2.dequeue_for(&rid).await;
                                if !msgs.is_empty() {
                                    tracing::info!("Retrying {} queued messages for peer", msgs.len());
                                    for msg in msgs {
                                        let payload = String::from_utf8_lossy(&msg.payload).to_string();
                                        let client = crate::network::quic_client::QuicClient::new(
                                            std::net::SocketAddr::new(std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED), 0)
                                        );
                                        if let Ok(client) = client {
                                            if let Ok(conn) = client.connect(peer_addr2, "p2p-messenger").await {
                                                let _ = conn.send_message(payload.as_bytes()).await;
                                                tracing::info!("Queued message delivered to peer");
                                            }
                                        }
                                    }
                                }
                            });
                        }
                        // Also register under mDNS full name (old chats may use it as contactId)
                        if node.node_id_hex.is_some() {
                            peer_addrs.lock().unwrap().insert(node.full_name.clone(), peer_addr);
                        }

                        network.add_peer(PeerInfo::new(peer_id.clone(), peer_name.clone()));
                        // Метка времени обязательна: уборка мёртвых узлов
                        // ориентируется на неё, а mDNS-соседи присылают
                        // presence не всегда.
                        network.touch_peer(&peer_id);

                        // Save public address for internet connectivity
                        if let Some(ref pa) = node.public_addr {
                            if let Ok(pub_addr) = pa.parse::<SocketAddr>() {
                                let key = format!("{}_public", peer_id);
                                peer_addrs.lock().unwrap().insert(key, pub_addr);
                                tracing::info!("mDNS: public addr for {} = {}", peer_id, pub_addr);
                            }
                        }
                        network.set_status(NetworkStatus::Connected);

                        events.emit(CoreEvent::PeerDiscovered {
                            peer_id: peer_id.clone(),
                            display_name: peer_name,
                            is_local: true,
                        });
                    }
                }
            }

            tokio::time::sleep(Duration::from_secs(10)).await;
        }
    }

    async fn run_tcp_listener(
        events: Arc<EventBus>,
        _network: Arc<NetworkManagerFfi>,
        port: u16,
    ) {
        use tokio::net::TcpListener;
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let bind_addr = std::net::SocketAddr::new(
            std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED), port
        );
        let listener = match TcpListener::bind(bind_addr).await {
            Ok(l) => {
                tracing::info!("TCP listener started on {:?}", l.local_addr());
                l
            }
            Err(e) => {
                tracing::error!("TCP bind failed: {}", e);
                return;
            }
        };

        loop {
            match listener.accept().await {
                Ok((mut stream, peer)) => {
                    tracing::info!("TCP incoming from {}", peer);
                    let ev = Arc::clone(&events);
                    tokio::spawn(async move {
                        let mut buf = vec![0u8; 65536];
                        loop {
                            match stream.read(&mut buf).await {
                                Ok(0) => break,
                                Ok(n) => {
                                    let text = String::from_utf8_lossy(&buf[..n]).to_string();
                                    tracing::info!("TCP recv: {}", &text[..text.len().min(100)]);
                                    let parts: Vec<&str> = text.splitn(4, '|').collect();
                                    if parts.len() == 4 {
                                        let ts = std::time::SystemTime::now()
                                            .duration_since(std::time::UNIX_EPOCH)
                                            .unwrap_or_default()
                                            .as_secs() as i64;
                                        ev.emit(CoreEvent::MessageReceived {
                                            message_id: parts[1].to_string(),
                                            chat_id: parts[2].to_string(),
                                            sender_id: parts[0].to_string(),
                                            text: parts[3].to_string(),
                                            timestamp: ts,
                                        });
                                    }
                                    let _ = stream.write_all(b"ACK").await;
                                }
                                Err(_) => break,
                            }
                        }
                    });
                }
                Err(e) => tracing::warn!("TCP accept error: {}", e),
            }
        }
    }
    async fn connect_ready_mqtt_session(
        node_id: &str,
        display_name: &str,
        shared_state: Arc<crate::network::mqtt_transport::MqttSharedRuntimeState>,
    ) -> Result<crate::network::mqtt_transport::MqttTransport, String> {
        use crate::network::mqtt_transport::MqttTransport;

        const MQTT_SESSION_READY_TIMEOUT: std::time::Duration =
            std::time::Duration::from_secs(45);

        let mut transport = MqttTransport::connect_with_shared_state(
            node_id,
            display_name,
            shared_state,
        )
        .await?;
        match tokio::time::timeout(MQTT_SESSION_READY_TIMEOUT, transport.subscribe()).await {
            Ok(Ok(())) => Ok(transport),
            Ok(Err(error)) => Err(error),
            Err(_) => Err(format!(
                "MQTT session did not reach ConnAck/subscription request within {}s",
                MQTT_SESSION_READY_TIMEOUT.as_secs()
            )),
        }
    }

    async fn announce_mqtt_session(
        transport: &crate::network::mqtt_transport::MqttTransport,
        display_name: &str,
        addr_str: Option<&str>,
        is_relay: bool,
        generation: u64,
    ) {
        match transport
            .publish_presence(display_name, addr_str, is_relay)
            .await
        {
            Ok(_) => tracing::info!(
                "MQTT SESSION: presence request queued after ConnAck generation={}",
                generation
            ),
            Err(error) => tracing::warn!(
                "MQTT SESSION: presence request failed generation={}: {}",
                generation,
                error
            ),
        }

        if is_relay {
            if let Some(addr) = addr_str {
                match transport.register_as_relay(addr).await {
                    Ok(_) => tracing::info!(
                        "MQTT SESSION: relay registration request queued generation={} addr={}",
                        generation,
                        addr
                    ),
                    Err(error) => tracing::warn!(
                        "MQTT SESSION: relay registration request failed generation={} addr={}: {}",
                        generation,
                        addr,
                        error
                    ),
                }
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    async fn run_mqtt_transport(
        events: Arc<EventBus>,
        network: Arc<NetworkManagerFfi>,
        peer_addrs: Arc<Mutex<HashMap<String, SocketAddr>>>,
        node_id: String,
        display_name: String,
        public_addr: Arc<Mutex<Option<SocketAddr>>>,
        queue: Option<Arc<MessageQueue>>,
        relay_queue: Option<Arc<RelayQueue>>,
        relay_custody: Option<Arc<RelayCustody>>,
        mut outbound_rx: tokio::sync::mpsc::Receiver<MqttOutboundCommand>,
    ) {
        use crate::network::mqtt_liveness::next_mqtt_restart_backoff_secs;
        use crate::network::mqtt_overflow::MQTT_LOSS_INTOLERANT_INBOX_CAPACITY;
        use crate::network::mqtt_transport::MqttSharedRuntimeState;

        const MQTT_SESSION_RESTART_BACKOFF_MAX_SECS: u64 = 30;

        #[cfg(all(
            feature = "mqtt-secondary-observe",
            not(feature = "mqtt-dual-broker")
        ))]
        let _secondary_observer = {
            let observer =
                crate::network::mqtt_secondary_observer::SecondaryBrokerObserver::spawn(&node_id);
            let snapshot = observer.snapshot();
            tracing::info!(
                "MQTT SECONDARY SUPERVISOR: feature=enabled mode=observe_only state={} subscriptions=0 publishes=0",
                snapshot.state.as_str()
            );
            observer
        };

        // Core owns the critical inbox and, under r4.4, duplicate/retained-target state across
        // every primary transport replacement. Accepted delivery events and the 30-second
        // cross-broker dedup window therefore survive a generation restart.
        let mqtt_shared_state = Arc::new(MqttSharedRuntimeState::new(
            MQTT_LOSS_INTOLERANT_INBOX_CAPACITY,
        ));

        // Wait for STUN to complete
        tokio::time::sleep(std::time::Duration::from_secs(3)).await;

        let addr_str = {
            let pa = public_addr.lock().unwrap();
            pa.map(|a| a.to_string())
        };
        let is_relay = addr_str.is_some();
        let mut session_generation = 1u64;
        let mut session_restart_backoff_secs = 1u64;
        let mut session_start_attempt = 1u64;

        let mut transport = loop {
            match Self::connect_ready_mqtt_session(
                &node_id,
                &display_name,
                Arc::clone(&mqtt_shared_state),
            )
            .await {
                Ok(transport) => {
                    tracing::info!(
                        "MQTT SESSION READY: generation={} attempt={} ConnAck=true subscription_request=true",
                        session_generation,
                        session_start_attempt
                    );
                    break transport;
                }
                Err(error) => {
                    tracing::warn!(
                        "MQTT SESSION START FAILED: generation={} attempt={} error={}; retrying_in={}s",
                        session_generation,
                        session_start_attempt,
                        error,
                        session_restart_backoff_secs
                    );
                    tokio::time::sleep(std::time::Duration::from_secs(
                        session_restart_backoff_secs,
                    ))
                    .await;
                    session_restart_backoff_secs = next_mqtt_restart_backoff_secs(
                        session_restart_backoff_secs,
                        MQTT_SESSION_RESTART_BACKOFF_MAX_SECS,
                    );
                    session_start_attempt = session_start_attempt.saturating_add(1);
                }
            }
        };

        session_restart_backoff_secs = 1;
        Self::announce_mqtt_session(
            &transport,
            &display_name,
            addr_str.as_deref(),
            is_relay,
            session_generation,
        )
        .await;

        // Event loop (poll every 1s, presence every 30s)
        let mut tick: u32 = 0;
        // === GOSSIP PROTOCOL: peer exchange state ===
        let mut known_peers: std::collections::HashMap<String, (String, std::time::Instant)> = std::collections::HashMap::new();
        let mut seen_gossip: std::collections::VecDeque<String> = std::collections::VecDeque::new();
        const MAX_GOSSIP_CACHE: usize = 500;
        /// Узел считается исчезнувшим, если не присылал presence столько секунд.
        /// Presence уходит раз в 30 секунд, поэтому три пропуска подряд - потеря.
        const PEER_STALE_SECS: u64 = 100;
        /// Узлы, чей адрес пришёл из MQTT presence: только их адреса подлежат
        /// уборке. Адреса от mDNS живут по своим правилам.
        let mut seen_via_presence: std::collections::HashSet<String> =
            std::collections::HashSet::new();

        // Mesh gossip budgets: прототип не должен превращать тысячи presence-событий
        // в безлимитную рассылку. Полная пагинация/Bloom summaries будет в hardening.
        const MAX_MESH_SUMMARY_ITEMS: usize = 256;
        const MAX_MESH_SUMMARY_BYTES: usize = 64 * 1024;
        const MESH_SUMMARY_COOLDOWN_SECS: u64 = 60;
        const MESH_SUMMARY_WINDOW_SECS: u64 = 30;
        const MAX_MESH_SUMMARIES_PER_WINDOW: usize = 8;
        let mut last_mesh_summary_sent: std::collections::HashMap<String, std::time::Instant> =
            std::collections::HashMap::new();
        let mut mesh_summary_window_started = std::time::Instant::now();
        let mut mesh_summaries_sent_in_window: usize = 0;

        // M3(c.2) medium-mode relay budgets. Остаток остаётся в RelayQueue до
        // следующего раунда; hard safety (dedup/TTL/hops/queue caps) не отключается.
        const MAX_MESH_RELAYS_PER_ROUND: usize = 16;
        const MAX_MESH_RELAY_CANDIDATES_PER_ROUND: usize = 64;
        const MAX_MESH_RELAY_BYTES_PER_ROUND: usize = 256 * 1024;
        // MAX_MESH_RELAY_ENVELOPE_BYTES импортирован из relay_queue (общий
        // bounded-лимит mesh-метаданных, M8-A).
        const MESH_RELAY_WINDOW_SECS: u64 = 30;
        const MAX_MESH_RELAYS_PER_WINDOW: usize = 32;
        const MAX_MESH_RELAY_BYTES_PER_WINDOW: usize = 512 * 1024;
        let mut mesh_relay_window_started = std::time::Instant::now();
        let mut mesh_relays_sent_in_window: usize = 0;
        let mut mesh_relay_bytes_sent_in_window: usize = 0;
        let mut mesh_relay_cursor: usize = 0;

        // M3(c.2-r3): bounded tombstones не дают позднему/повторному relay
        // снова попасть в очередь после receipt cleanup или повторно появиться в UI.
        const MAX_SEEN_MESH_RELAY_IDS: usize = 10_000;
        const MAX_DELIVERED_MESH_RELAY_IDS: usize = 4_096;
        let mut seen_mesh_relay_ids: HashSet<String> = HashSet::new();
        let mut seen_mesh_relay_order: VecDeque<String> = VecDeque::new();
        let mut delivered_mesh_relay_origins: HashMap<String, String> = HashMap::new();
        let mut delivered_mesh_relay_order: VecDeque<String> = VecDeque::new();

        // M8-D: после restart восстанавливаем durable tombstones в RAM seen-set,
        // чтобы уже доставленные/очищенные relay ID не были повторно поставлены
        // в очередь или показаны в UI из retained/поздних конвертов.
        // (Tombstones — открытые пары msg_id+время, общие для V1/V2 по дизайну.)
        if let Some(ref custody) = relay_custody {
            match custody.store.load_tombstone_ids(MAX_SEEN_MESH_RELAY_IDS) {
                Ok(ids) => {
                    let restored = ids.len();
                    for id in ids {
                        remember_bounded_id(
                            &mut seen_mesh_relay_ids,
                            &mut seen_mesh_relay_order,
                            &id,
                            MAX_SEEN_MESH_RELAY_IDS,
                        );
                    }
                    if restored > 0 {
                        tracing::info!(
                            "MESH durable: restored {} tombstone(s) into seen set",
                            restored
                        );
                    }
                }
                Err(e) => tracing::warn!("MESH durable: tombstone restore failed: {}", e),
            }
        }

        loop {
            // M3(d): drain a bounded number of origin send commands into the already-running
            // persistent transport. `try_send` on the producer side never claims SENT; local
            // RelayQueue ownership remains the durability/retry source until a receipt arrives.
            for _ in 0..32 {
                match outbound_rx.try_recv() {
                    Ok(MqttOutboundCommand::MeshRelay {
                        recipient,
                        envelope,
                        message_id,
                    }) => match transport.send_mesh_relay(&recipient, &envelope).await {
                        Ok(()) => tracing::info!(
                            "MESH origin: relay publish request queued {} for {}",
                            message_id,
                            recipient
                        ),
                        Err(error) => tracing::warn!(
                            "MESH origin: relay publish request failed {} for {}: {}; retained locally",
                            message_id,
                            recipient,
                            error
                        ),
                    },
                    Ok(MqttOutboundCommand::AnnounceNow) => {
                        let current_addr = {
                            let pa = public_addr.lock().unwrap();
                            pa.map(|a| a.to_string())
                        };
                        let current_is_relay = current_addr.is_some();
                        match transport
                            .publish_presence(
                                &display_name,
                                current_addr.as_deref(),
                                current_is_relay,
                            )
                            .await
                        {
                            Ok(()) => tracing::info!(
                                "MQTT: manual presence announce queued (addr={:?})",
                                current_addr
                            ),
                            Err(error) => tracing::warn!(
                                "MQTT: manual presence announce failed: {}",
                                error
                            ),
                        }
                    }
                    Err(tokio::sync::mpsc::error::TryRecvError::Empty) => break,
                    Err(tokio::sync::mpsc::error::TryRecvError::Disconnected) => break,
                }
            }

            let polled_event = transport.poll_event().await;

            // Never discard an event already handed to core. If a terminal/stalled condition is
            // observed together with one final event, process it now and restart next iteration.
            if polled_event.is_none() {
                if let Some(reason) = transport.restart_reason() {
                    let previous_generation = session_generation;
                    let next_generation = session_generation.saturating_add(1);
                    tracing::error!(
                        "MQTT SESSION RESTART REQUIRED: generation={} reason={} detail={:?}",
                        previous_generation,
                        reason.as_str(),
                        reason
                    );

                    drop(transport);
                    let mut recovery_attempt = 1u64;
                    transport = loop {
                        let retry_delay_secs = session_restart_backoff_secs;
                        tracing::warn!(
                            "MQTT SESSION RESTART SCHEDULED: from_generation={} to_generation={} attempt={} delay={}s",
                            previous_generation,
                            next_generation,
                            recovery_attempt,
                            retry_delay_secs
                        );
                        tokio::time::sleep(std::time::Duration::from_secs(retry_delay_secs)).await;

                        match Self::connect_ready_mqtt_session(
                            &node_id,
                            &display_name,
                            Arc::clone(&mqtt_shared_state),
                        )
                        .await {
                            Ok(recovered_transport) => break recovered_transport,
                            Err(error) => {
                                session_restart_backoff_secs = next_mqtt_restart_backoff_secs(
                                    session_restart_backoff_secs,
                                    MQTT_SESSION_RESTART_BACKOFF_MAX_SECS,
                                );
                                tracing::warn!(
                                    "MQTT SESSION RESTART FAILED: target_generation={} attempt={} error={}; next_retry_in={}s",
                                    next_generation,
                                    recovery_attempt,
                                    error,
                                    session_restart_backoff_secs
                                );
                                recovery_attempt = recovery_attempt.saturating_add(1);
                            }
                        }
                    };

                    session_generation = next_generation;
                    session_restart_backoff_secs = 1;
                    tick = 0;
                    // Адрес перечитываем: при восстановлении сессии (часто как раз
                    // после смены сети) стартовое значение уже устарело.
                    let recovered_addr = {
                        let pa = public_addr.lock().unwrap();
                        pa.map(|a| a.to_string())
                    };
                    let recovered_is_relay = recovered_addr.is_some();
                    Self::announce_mqtt_session(
                        &transport,
                        &display_name,
                        recovered_addr.as_deref(),
                        recovered_is_relay,
                        session_generation,
                    )
                    .await;
                    tracing::info!(
                        "MQTT SESSION RECOVERED: generation={} ConnAck=true subscription_request=true",
                        session_generation
                    );
                    continue;
                }
            }

            if let Some(evt) = polled_event {
                if evt.topic.starts_with("p2pm2/ping/") {
                    // Heartbeat ping РѕС‚ peer
                    let peer_id = evt.topic.trim_start_matches("p2pm2/ping/");
                    if peer_id != node_id {
                        tracing::trace!("Ping from {}", peer_id);
                    }
                } else if evt.topic.starts_with("p2pm2/presence/") {
                    if evt.payload.is_empty() { continue; }  // Skip empty (retained clear)
                    let parts: Vec<&str> = evt.payload.splitn(4, '|').collect();
                    if parts.len() >= 4 && parts[0] != node_id {
                        let peer_id = parts[0];
                        let display_name = parts[1];
                        tracing::info!("MQTT: peer online: {} ({})", display_name, peer_id);

                        // Presence несёт публичный адрес пира (STUN), но раньше он
                        // просто отбрасывался: peer_addrs заполнял только mDNS. Из-за
                        // этого через интернет абоненты «не находили» друг друга -
                        // прямая отправка не знала адреса и всё уходило в очередь.
                        // Регистрируем адрес и самого пира так же, как это делает mDNS.
                        seen_via_presence.insert(peer_id.to_string());
                        let peer_public_addr = parts[2].trim();
                        if !peer_public_addr.is_empty() {
                            match peer_public_addr.parse::<SocketAddr>() {
                                Ok(addr) => {
                                    let mut addrs = peer_addrs.lock().unwrap();
                                    addrs.insert(peer_id.to_string(), addr);
                                    addrs.insert(format!("{}_public", peer_id), addr);
                                    tracing::info!(
                                        "MQTT: public addr for {} = {}",
                                        peer_id,
                                        addr
                                    );
                                }
                                Err(e) => tracing::warn!(
                                    "MQTT: bad public addr '{}' for {}: {}",
                                    peer_public_addr,
                                    peer_id,
                                    e
                                ),
                            }
                        }
                        network.add_peer(PeerInfo::new(
                            peer_id.to_string(),
                            display_name.to_string(),
                        ));
                        network.touch_peer(peer_id);
                        network.set_status(NetworkStatus::Connected);
                        
                        // PEER ONLINE: РґРѕСЃС‚Р°РІРёС‚СЊ РЅР°РєРѕРїР»РµРЅРЅС‹Рµ СЃРѕРѕР±С‰РµРЅРёСЏ
                        if let Some(ref q) = queue {
                            use sha2::{Sha256, Digest};
                            
                            let mut rh = Sha256::new();
                            rh.update(peer_id.as_bytes());
                            let rh_result = rh.finalize();
                            let mut rid = [0u8; 32];
                            rid.copy_from_slice(&rh_result);
                            
                            let messages = q.dequeue_for(&rid).await;
                            if !messages.is_empty() {
                                tracing::info!("вњ“ Peer {} online, delivering {} msgs", 
                                    peer_id, messages.len());
                                
                                for msg in messages {
                                    if let Ok(payload) = String::from_utf8(msg.payload) {
                                        match transport.send_message(peer_id, &payload).await {
                                            Ok(_) => tracing::info!("  вњ“ Delivered to {}", peer_id),
                                            Err(e) => tracing::warn!("  вњ— Failed: {}", e),
                                        }
                                    }
                                }
                            }
                        }

                        // M3(c.1): отправляем peer только сводку RelayQueue. Никакие relay
                        // на этом подшаге ещё не пересылаются. Cooldown и глобальный budget
                        // защищают телефон, если одновременно видны тысячи peers.
                        if let Some(ref q) = relay_queue {
                            let now = std::time::Instant::now();
                            if mesh_summary_window_started.elapsed().as_secs()
                                >= MESH_SUMMARY_WINDOW_SECS
                            {
                                mesh_summary_window_started = now;
                                mesh_summaries_sent_in_window = 0;
                                last_mesh_summary_sent.retain(|_, sent_at| {
                                    sent_at.elapsed().as_secs()
                                        < MESH_SUMMARY_COOLDOWN_SECS * 10
                                });
                            }

                            let cooldown_ready = last_mesh_summary_sent
                                .get(peer_id)
                                .map(|sent_at| {
                                    sent_at.elapsed().as_secs()
                                        >= MESH_SUMMARY_COOLDOWN_SECS
                                })
                                .unwrap_or(true);

                            if cooldown_ready
                                && mesh_summaries_sent_in_window
                                    < MAX_MESH_SUMMARIES_PER_WINDOW
                            {
                                let digest = q.digest();
                                if digest.len() > MAX_MESH_SUMMARY_ITEMS {
                                    tracing::warn!(
                                        "MESH gossip: summary for {} has {} items (limit {}), skipped",
                                        peer_id,
                                        digest.len(),
                                        MAX_MESH_SUMMARY_ITEMS
                                    );
                                    last_mesh_summary_sent.insert(peer_id.to_string(), now);
                                } else {
                                    let summary =
                                        crate::network::wire::build_gossip_summary(&digest);
                                    if summary.len() > MAX_MESH_SUMMARY_BYTES {
                                        tracing::warn!(
                                            "MESH gossip: summary for {} is {} bytes (limit {}), skipped",
                                            peer_id,
                                            summary.len(),
                                            MAX_MESH_SUMMARY_BYTES
                                        );
                                        last_mesh_summary_sent.insert(peer_id.to_string(), now);
                                    } else {
                                        match transport.send_message(peer_id, &summary).await {
                                            Ok(_) => {
                                                mesh_summaries_sent_in_window += 1;
                                                last_mesh_summary_sent
                                                    .insert(peer_id.to_string(), now);
                                                tracing::info!(
                                                    "MESH gossip: sent summary with {} items to {}",
                                                    digest.len(),
                                                    peer_id
                                                );
                                            }
                                            Err(e) => tracing::warn!(
                                                "MESH gossip: failed to send summary to {}: {}",
                                                peer_id,
                                                e
                                            ),
                                        }
                                    }
                                }
                            }
                        }
                        
                        events.emit(CoreEvent::PeerDiscovered {
                            peer_id: peer_id.to_string(),
                            display_name: display_name.to_string(),
                            is_local: false,
                        });
                        // === GOSSIP: record in known_peers ===
                        known_peers.insert(peer_id.to_string(), (display_name.to_string(), std::time::Instant::now()));
                    }
                } else if evt.topic.starts_with("p2pm2/gossip/broadcast") {
                    // === GOSSIP: receive peer list from other nodes ===
                    if evt.payload.is_empty() { continue; }
                    let parts: Vec<&str> = evt.payload.split('|').collect();
                    if parts.len() < 3 || parts[0] != "gossip" { continue; }
                    let sender = parts[1];
                    let msg_uuid = parts[2];
                    if sender == node_id { continue; }
                    if seen_gossip.contains(&msg_uuid.to_string()) { continue; }
                    
                    seen_gossip.push_back(msg_uuid.to_string());
                    while seen_gossip.len() > MAX_GOSSIP_CACHE {
                        seen_gossip.pop_front();
                    }
                    
                    let mut i = 3;
                    while i + 1 < parts.len() {
                        let peer_id = parts[i];
                        let peer_name = parts[i + 1];
                        i += 2;
                        if peer_id == node_id { continue; }
                        if !known_peers.contains_key(peer_id) {
                            known_peers.insert(peer_id.to_string(), (peer_name.to_string(), std::time::Instant::now()));
                            tracing::info!("Gossip: learned about peer {} ({})", peer_name, peer_id);
                            events.emit(CoreEvent::PeerDiscovered {
                                peer_id: peer_id.to_string(),
                                display_name: peer_name.to_string(),
                                is_local: false,
                            });
                        }
                        // Слух НЕ продлевает жизнь узла. Раньше здесь
                        // обновлялось время последней встречи, и мёртвые узлы
                        // становились бессмертными: телефоны пересказывали
                        // друг другу один и тот же устаревший список, взаимно
                        // подтверждая давно удалённые установки. Живым узел
                        // делает только его собственный presence.
                    }
                } else if evt.topic.starts_with("p2pm2/msg/") {
                    // ACK: "ack|messageId" — подтверждение обычной прямой доставки.
                    if let Some(rest) = evt.payload.strip_prefix("ack|") {
                        let mid = rest.trim();
                        if !mid.is_empty() {
                            tracing::info!("MQTT: delivery ACK received for {}", mid);
                            events.emit(CoreEvent::MessageDelivered {
                                message_id: mid.to_string(),
                            });
                        }
                    } else if evt.payload.starts_with("relay|") {
                        // M3(a): relay-конверт. Gossip будет добавлен отдельным шагом
                        // после сборки и телефонного теста receipt-cleanup.
                        match crate::network::wire::parse(&evt.payload) {
                            Some(MeshEnvelope::Relay {
                                msg_id,
                                recipient,
                                origin,
                                chat_scope,
                                ttl_secs,
                                hop,
                                e2e_payload,
                            }) => {
                                if msg_id.is_empty() || recipient.is_empty() || origin.is_empty() {
                                    tracing::warn!("MESH relay: missing required metadata, dropped");
                                } else if recipient == node_id {
                                    // M3(c.2-r3): одно msg_id показываем локальному recipient
                                    // не больше раза. Повтор от того же origin получает новый
                                    // receipt (для cleanup relay), но не второе UI-событие.
                                    let previous_origin = delivered_mesh_relay_origins
                                        .get(&msg_id)
                                        .cloned();
                                    let should_send_receipt = match previous_origin {
                                        Some(ref known_origin) if known_origin == &origin => {
                                            tracing::info!(
                                                "MESH relay: duplicate local delivery {} suppressed",
                                                msg_id
                                            );
                                            true
                                        }
                                        Some(known_origin) => {
                                            tracing::warn!(
                                                "MESH relay: conflicting origin for {}, dropped ({} != {})",
                                                msg_id,
                                                origin,
                                                known_origin
                                            );
                                            false
                                        }
                                        None => match String::from_utf8(e2e_payload) {
                                            Ok(text) => {
                                                // M8-D: после restart RAM-мапа доставок пуста;
                                                // durable tombstone защищает от повторной UI-доставки.
                                                // Receipt при этом всё равно шлём (идемпотентный
                                                // cleanup чужих custody-копий).
                                                let durable_tombstoned = relay_custody
                                                    .as_ref()
                                                    .map(|custody| {
                                                        custody.store.has_tombstone(&msg_id).unwrap_or(false)
                                                    })
                                                    .unwrap_or(false);
                                                if durable_tombstoned {
                                                    tracing::info!(
                                                        "MESH relay: {} already delivered before restart, UI suppressed",
                                                        msg_id
                                                    );
                                                    true
                                                } else {
                                                    let now = std::time::SystemTime::now()
                                                        .duration_since(std::time::UNIX_EPOCH)
                                                        .unwrap_or_default();
                                                    events.emit(CoreEvent::MessageReceived {
                                                        message_id: msg_id.clone(),
                                                        chat_id: chat_scope,
                                                        sender_id: origin.clone(),
                                                        text,
                                                        timestamp: now.as_millis() as i64,
                                                    });
                                                    remember_bounded_delivery(
                                                        &mut delivered_mesh_relay_origins,
                                                        &mut delivered_mesh_relay_order,
                                                        &msg_id,
                                                        &origin,
                                                        MAX_DELIVERED_MESH_RELAY_IDS,
                                                    );
                                                    // M8-B/D: durable tombstone — после restart поздний/
                                                    // повторный relay с этим ID не даст вторую UI-доставку.
                                                    if let Some(ref custody) = relay_custody {
                                                        let now_durable =
                                                            crate::network::relay_queue::utc_now_ms();
                                                        if let Err(e) =
                                                            custody.store.record_tombstone(&msg_id, now_durable)
                                                        {
                                                            tracing::warn!(
                                                                "MESH relay: durable tombstone failed for {}: {}",
                                                                msg_id,
                                                                e
                                                            );
                                                        }
                                                    }
                                                    tracing::info!(
                                                        "MESH relay: {} delivered to local recipient",
                                                        msg_id
                                                    );
                                                    true
                                                }
                                            }
                                            Err(_) => {
                                                tracing::warn!(
                                                    "MESH relay: payload for {} is not valid UTF-8, dropped",
                                                    msg_id
                                                );
                                                false
                                            }
                                        },
                                    };

                                    if should_send_receipt {
                                        let now = std::time::SystemTime::now()
                                            .duration_since(std::time::UNIX_EPOCH)
                                            .unwrap_or_default();
                                        let receipt = crate::network::wire::build_receipt(
                                            &msg_id,
                                            &node_id,
                                            now.as_secs(),
                                        );
                                        match transport
                                            .send_mesh_receipt(&origin, &msg_id, &receipt)
                                            .await
                                        {
                                            Ok(_) => {
                                                tracing::info!(
                                                    "MESH receipt: {} sent automatically to unique origin topic",
                                                    msg_id
                                                );
                                                match transport
                                                    .send_mesh_cleanup_receipt(&msg_id, &receipt)
                                                    .await
                                                {
                                                    Ok(_) => tracing::info!(
                                                        "MESH receipt: {} cleanup fanout queued for relay nodes",
                                                        msg_id
                                                    ),
                                                    Err(e) => tracing::warn!(
                                                        "MESH receipt: cleanup fanout failed for {}: {}",
                                                        msg_id,
                                                        e
                                                    ),
                                                }
                                            }
                                            Err(e) => tracing::warn!(
                                                "MESH receipt: failed to send {}: {}",
                                                msg_id,
                                                e
                                            ),
                                        }
                                    }
                                } else if let Some(ref q) = relay_queue {
                                    // КРИТИЧНО: contains ОБЯЗАТЕЛЕН перед каждым enqueue.
                                    // Без этого self-receive MQTT создаёт бесконечную петлю/шторм.
                                    if q.contains(&msg_id) {
                                        remember_bounded_id(
                                            &mut seen_mesh_relay_ids,
                                            &mut seen_mesh_relay_order,
                                            &msg_id,
                                            MAX_SEEN_MESH_RELAY_IDS,
                                        );
                                        tracing::trace!("MESH relay: duplicate {} ignored", msg_id);
                                    } else if seen_mesh_relay_ids.contains(&msg_id) {
                                        tracing::info!(
                                            "MESH relay: previously seen {} ignored after cleanup",
                                            msg_id
                                        );
                                    } else if ttl_secs == 0 {
                                        tracing::warn!("MESH relay: {} has expired TTL, dropped", msg_id);
                                    } else {
                                        // Ограничиваем входной TTL текущим максимумом очереди:
                                        // это защищает от переполнения/слишком долгого хранения.
                                        let bounded_ttl_secs =
                                            ttl_secs.min(DEFAULT_RELAY_TTL.as_secs());
                                        let mut message = RelayMessage::with_ttl(
                                            msg_id.clone(),
                                            recipient.clone(),
                                            origin,
                                            chat_scope,
                                            e2e_payload,
                                            std::time::Duration::from_secs(bounded_ttl_secs),
                                        );
                                        message.hop_count = hop;

                                        match message.next_hop() {
                                            Some(next_hop_message) => {
                                                let stored_hop = next_hop_message.hop_count;
                                                // Tombstone ставим до enqueue attempt: переполненный
                                                // телефон не должен бесконечно разбирать один spam ID.
                                                remember_bounded_id(
                                                    &mut seen_mesh_relay_ids,
                                                    &mut seen_mesh_relay_order,
                                                    &msg_id,
                                                    MAX_SEEN_MESH_RELAY_IDS,
                                                );

                                                // M8-B/C: durable custody persist-ится ДО enqueue
                                                // (зашифрованным конвертом). Если store отклоняет
                                                // (tombstone/валидация/IO/at-rest) — в RAM не ставим:
                                                // custody не подтверждена durable.
                                                let durable_admitted = match relay_custody.as_ref() {
                                                    Some(custody) => {
                                                        let now_durable =
                                                            crate::network::relay_queue::utc_now_ms();
                                                        if custody
                                                            .store
                                                            .has_tombstone(&next_hop_message.msg_id)
                                                            .unwrap_or(false)
                                                        {
                                                            tracing::info!(
                                                                "MESH relay: {} already tombstoned, not stored",
                                                                msg_id
                                                            );
                                                            false
                                                        } else {
                                                            match custody.store.store_encrypted(
                                                                &*custody.keys,
                                                                &next_hop_message,
                                                                now_durable,
                                                            ) {
                                                                Ok(_) => true,
                                                                Err(e) => {
                                                                    tracing::warn!(
                                                                        "MESH relay: durable store failed for {}: {}",
                                                                        msg_id,
                                                                        e
                                                                    );
                                                                    false
                                                                }
                                                            }
                                                        }
                                                    }
                                                    None => true, // RAM-only режим: legacy поведение
                                                };

                                                if durable_admitted {
                                                    match q.enqueue(next_hop_message) {
                                                        Ok(true) => tracing::info!(
                                                            "MESH relay: stored {} for {} at hop {}",
                                                            msg_id,
                                                            recipient,
                                                            stored_hop
                                                        ),
                                                        Ok(false) => tracing::trace!(
                                                            "MESH relay: {} not stored (duplicate/hop limit)",
                                                            msg_id
                                                        ),
                                                        Err(e) => tracing::warn!(
                                                            "MESH relay: failed to store {}: {}",
                                                            msg_id,
                                                            e
                                                        ),
                                                    }
                                                }
                                            }
                                            None => tracing::warn!(
                                                "MESH relay: {} reached hop limit, dropped",
                                                msg_id
                                            ),
                                        }
                                    }
                                } else {
                                    tracing::warn!("MESH relay: queue unavailable, dropped {}", msg_id);
                                }
                            }
                            _ => tracing::warn!("MESH relay: malformed envelope dropped"),
                        }
                    } else if evt.payload.starts_with("receipt|") {
                        // M3(b): receipt удаляет доставленное сообщение только если msg_id
                        // и recipient совпадают с записью в RelayQueue. Подпись добавит M7.
                        match crate::network::wire::parse(&evt.payload) {
                            Some(MeshEnvelope::Receipt {
                                msg_id,
                                recipient,
                                ts,
                            }) => {
                                let is_own_retained_receipt = !msg_id.is_empty()
                                    && !recipient.is_empty()
                                    && transport.is_own_mesh_receipt_topic(&evt.topic, &msg_id);

                                if msg_id.is_empty() || recipient.is_empty() {
                                    tracing::warn!("MESH receipt: missing metadata, dropped");
                                } else if let Some(ref q) = relay_queue {
                                    let stored_message = q
                                        .for_recipient(&recipient)
                                        .into_iter()
                                        .find(|message| message.msg_id == msg_id);

                                    if let Some(message) = stored_message {
                                        let is_origin = message.origin_sender == node_id;
                                        if q.remove(&msg_id) {
                                            tracing::info!(
                                                "MESH receipt: removed {} for {} at {}",
                                                msg_id,
                                                recipient,
                                                ts
                                            );

                                            if is_origin {
                                                events.emit(CoreEvent::MessageDelivered {
                                                    message_id: msg_id.clone(),
                                                });
                                                tracing::info!(
                                                    "MESH receipt: {} delivered at origin",
                                                    msg_id
                                                );
                                            }
                                        }
                                    } else {
                                        // Повторный/чужой receipt безопасно игнорируется.
                                        tracing::trace!(
                                            "MESH receipt: unknown {} for {}, ignored",
                                            msg_id,
                                            recipient
                                        );
                                    }
                                } else {
                                    tracing::warn!(
                                        "MESH receipt: queue unavailable, dropped {}",
                                        msg_id
                                    );
                                }

                                // M8-B/C: receipt авторитетен независимо от RAM-копии: durable
                                // encrypted custody удаляется и tombstone-ится даже если запись
                                // после restart осталась только в SQLite (RAM о ней не знает).
                                if !msg_id.is_empty() && !recipient.is_empty() {
                                    if let Some(ref custody) = relay_custody {
                                        let now_durable =
                                            crate::network::relay_queue::utc_now_ms();
                                        match custody.store.remove_encrypted_and_tombstone(&msg_id, now_durable) {
                                            Ok(true) => tracing::info!(
                                                "MESH receipt: durable custody removed for {}",
                                                msg_id
                                            ),
                                            Ok(false) => {}
                                            Err(e) => tracing::warn!(
                                                "MESH receipt: durable cleanup failed for {}: {}",
                                                msg_id,
                                                e
                                            ),
                                        }
                                    }
                                }

                                // Удаляет retained receipt только его локальный origin и
                                // только с topic, чей SHA-256 key совпал с msg_id.
                                if is_own_retained_receipt {
                                    match transport.clear_own_mesh_receipt(&msg_id).await {
                                        Ok(_) => tracing::info!(
                                            "MESH receipt: cleared retained topic for {}",
                                            msg_id
                                        ),
                                        Err(e) => tracing::warn!(
                                            "MESH receipt: failed to clear retained topic for {}: {}",
                                            msg_id,
                                            e
                                        ),
                                    }
                                }
                            }
                            _ => tracing::warn!("MESH receipt: malformed envelope dropped"),
                        }
                    } else if evt.payload == "gsumm" || evt.payload.starts_with("gsumm|") {
                        // M3(c.1): все MQTT-узлы видят topic из-за p2pm2/#, но summary
                        // принимает только peer, которому адресован p2pm2/msg/<node_id>.
                        let topic_target = evt
                            .topic
                            .strip_prefix("p2pm2/msg/")
                            .unwrap_or("");
                        if topic_target == node_id {
                            if evt.payload.len() > MAX_MESH_SUMMARY_BYTES {
                                tracing::warn!(
                                    "MESH gossip: incoming summary is {} bytes (limit {}), dropped",
                                    evt.payload.len(),
                                    MAX_MESH_SUMMARY_BYTES
                                );
                            } else {
                                match crate::network::wire::parse(&evt.payload) {
                                    Some(MeshEnvelope::GossipSummary { items }) => {
                                        if items.len() > MAX_MESH_SUMMARY_ITEMS {
                                            tracing::warn!(
                                                "MESH gossip: incoming summary has {} items (limit {}), dropped",
                                                items.len(),
                                                MAX_MESH_SUMMARY_ITEMS
                                            );
                                        } else {
                                            tracing::info!(
                                                "MESH gossip: received summary with {} items",
                                                items.len()
                                            );

                                            // M3(c.2): peer прислал свою сводку. Отправляем
                                            // только то, чего в ней нет, с medium-mode budgets.
                                            if let Some(ref q) = relay_queue {
                                                let expired = q.cleanup_expired();
                                                if expired > 0 {
                                                    tracing::info!(
                                                        "MESH gossip: removed {} expired relay(s)",
                                                        expired
                                                    );
                                                }

                                                // M8-B/C: durable-слой тоже чистим по абсолютному
                                                // expiry (без доставки в UI); истёкший карантин
                                                // тоже удаляется.
                                                if let Some(ref custody) = relay_custody {
                                                    let now_durable =
                                                        crate::network::relay_queue::utc_now_ms();
                                                    match custody.store.purge_expired_encrypted(now_durable) {
                                                        Ok((0, 0)) => {}
                                                        Ok((purged, purged_quarantine)) => tracing::info!(
                                                            "MESH gossip: purged {} expired durable relay(s) (+{} quarantined)",
                                                            purged,
                                                            purged_quarantine
                                                        ),
                                                        Err(e) => tracing::warn!(
                                                            "MESH gossip: durable purge failed: {}",
                                                            e
                                                        ),
                                                    }
                                                }

                                                let (candidates, total_missing) = q
                                                    .gossip_candidates(
                                                        &items,
                                                        mesh_relay_cursor,
                                                        MAX_MESH_RELAY_CANDIDATES_PER_ROUND,
                                                    );

                                                if total_missing > 0 {
                                                    if mesh_relay_window_started
                                                        .elapsed()
                                                        .as_secs()
                                                        >= MESH_RELAY_WINDOW_SECS
                                                    {
                                                        mesh_relay_window_started =
                                                            std::time::Instant::now();
                                                        mesh_relays_sent_in_window = 0;
                                                        mesh_relay_bytes_sent_in_window = 0;
                                                    }

                                                    let mut examined = 0usize;
                                                    let mut sent = 0usize;
                                                    let mut sent_bytes = 0usize;

                                                    for message in &candidates {
                                                        if sent >= MAX_MESH_RELAYS_PER_ROUND
                                                            || mesh_relays_sent_in_window
                                                                >= MAX_MESH_RELAYS_PER_WINDOW
                                                        {
                                                            break;
                                                        }

                                                        examined += 1;

                                                        let ttl_secs = message
                                                            .remaining_ttl_secs();
                                                        if ttl_secs == 0
                                                            || message.hops_exceeded()
                                                        {
                                                            continue;
                                                        }

                                                        let relay =
                                                            crate::network::wire::build_relay(
                                                                &message.msg_id,
                                                                &message.recipient,
                                                                &message.origin_sender,
                                                                &message.chat_scope,
                                                                ttl_secs,
                                                                message.hop_count,
                                                                &message.e2e_payload,
                                                            );
                                                        let relay_bytes = relay.len();
                                                        if relay_bytes
                                                            > MAX_MESH_RELAY_ENVELOPE_BYTES
                                                        {
                                                            tracing::warn!(
                                                                "MESH gossip: relay {} is {} bytes (limit {}), skipped",
                                                                message.msg_id,
                                                                relay_bytes,
                                                                MAX_MESH_RELAY_ENVELOPE_BYTES
                                                            );
                                                            continue;
                                                        }
                                                        if sent_bytes.saturating_add(relay_bytes)
                                                            > MAX_MESH_RELAY_BYTES_PER_ROUND
                                                            || mesh_relay_bytes_sent_in_window
                                                                .saturating_add(relay_bytes)
                                                                > MAX_MESH_RELAY_BYTES_PER_WINDOW
                                                        {
                                                            break;
                                                        }

                                                        match transport
                                                            .send_mesh_relay(
                                                                &message.recipient,
                                                                &relay,
                                                            )
                                                            .await
                                                        {
                                                            Ok(_) => {
                                                                sent += 1;
                                                                sent_bytes += relay_bytes;
                                                                mesh_relays_sent_in_window += 1;
                                                                mesh_relay_bytes_sent_in_window +=
                                                                    relay_bytes;
                                                                tracing::trace!(
                                                                    "MESH gossip: resent {} for {}",
                                                                    message.msg_id,
                                                                    message.recipient
                                                                );
                                                            }
                                                            Err(e) => tracing::warn!(
                                                                "MESH gossip: failed to resend {}: {}",
                                                                message.msg_id,
                                                                e
                                                            ),
                                                        }
                                                    }

                                                    if examined > 0 {
                                                        mesh_relay_cursor = mesh_relay_cursor
                                                            .wrapping_add(examined)
                                                            % total_missing;
                                                    }
                                                    if sent > 0 {
                                                        tracing::info!(
                                                            "MESH gossip: resent {} relay(s), {} bytes ({} missing)",
                                                            sent,
                                                            sent_bytes,
                                                            total_missing
                                                        );
                                                    } else {
                                                        tracing::trace!(
                                                            "MESH gossip: no relay sent ({} missing, budget/limits)",
                                                            total_missing
                                                        );
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    _ => tracing::warn!(
                                        "MESH gossip: malformed summary dropped"
                                    ),
                                }
                            }
                        }
                    } else {
                        // Обычный формат: senderId|messageId|chatId|recipientId|text.
                        let parts: Vec<&str> = evt.payload.splitn(5, '|').collect();
                        if parts.len() == 5 && parts[0] != node_id {  // Skip own messages
                            let sender_id = parts[0];
                            let message_id = parts[1];
                            let chat_id = parts[2];
                            let recipient_id = parts[3];
                            let text = parts[4];

                            // Получаем и показываем только адресованные этому телефону сообщения.
                            if recipient_id == node_id {
                                tracing::info!("MQTT: message from {} to me", sender_id);
                                let ts = std::time::SystemTime::now()
                                    .duration_since(std::time::UNIX_EPOCH)
                                    .unwrap_or_default()
                                    .as_millis() as i64;
                                events.emit(CoreEvent::MessageReceived {
                                    message_id: message_id.to_string(),
                                    chat_id: chat_id.to_string(),
                                    sender_id: sender_id.to_string(),
                                    text: text.to_string(),
                                    timestamp: ts,
                                });
                                tracing::info!("MQTT: MessageReceived EMITTED to EventBus");
                            }
                        }
                    }
                }
            }
            tick += 1;
            if tick >= 30 {
                tick = 0;

                // Уборка мёртвых узлов. Presence приходит раз в 30 секунд;
                // всё, о чём не слышали PEER_STALE_SECS, считаем исчезнувшим.
                // Без этого список рос копиями одной и той же трубки (каждая
                // переустановка = новый node_id) и мешал выбирать живого
                // получателя для файлов.
                let stale_cutoff = std::time::Duration::from_secs(PEER_STALE_SECS);
                let before = known_peers.len();
                known_peers.retain(|_, (_, seen_at)| seen_at.elapsed() < stale_cutoff);
                let removed = before.saturating_sub(known_peers.len());

                // Общий список чистим по возрасту записи, а не по списку из
                // MQTT: соседей по Wi-Fi добавляет mDNS, и они бы вылетали на
                // каждом круге уборки.
                let dropped_from_network =
                    network.drop_peers_older_than((PEER_STALE_SECS * 1000) as i64);
                if removed > 0 || dropped_from_network > 0 {
                    tracing::info!(
                        "PEERS: dropped {} stale presence entries (network list -{}), {} alive",
                        removed,
                        dropped_from_network,
                        known_peers.len()
                    );
                    let alive: std::collections::HashSet<&str> =
                        known_peers.keys().map(|k| k.as_str()).collect();
                    let mut addrs = peer_addrs.lock().unwrap();
                    addrs.retain(|key, _| {
                        let base = key.strip_suffix("_public").unwrap_or(key.as_str());
                        // Адреса из mDNS не трогаем: их владельцы могут не
                        // присылать presence, оставаясь достижимыми по Wi-Fi.
                        alive.contains(base) || !seen_via_presence.contains(base)
                    });
                }
                // Адрес перечитываем каждый раз. Раньше он брался один раз при
                // старте - через 3 секунды после запуска, когда STUN обычно ещё
                // не ответил. Телефон навсегда объявлял себя без адреса, и
                // остальные не могли к нему подключиться напрямую.
                let current_addr = {
                    let pa = public_addr.lock().unwrap();
                    pa.map(|a| a.to_string())
                };
                let current_is_relay = current_addr.is_some();
                if let Err(e) = transport
                    .publish_presence(&display_name, current_addr.as_deref(), current_is_relay)
                    .await
                {
                    tracing::warn!("MQTT: periodic presence request failed: {}", e);
                }
                // === GOSSIP: broadcast known peers to the network ===
                if !known_peers.is_empty() {
                    let ts = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_millis();
                    let prefix = &node_id[..std::cmp::min(8, node_id.len())];
                    let msg_uuid = format!("{}-{}", ts, prefix);
                    let mut gossip_parts: Vec<String> = vec![
                        "gossip".to_string(),
                        node_id.clone(),
                        msg_uuid,
                    ];
                    let count = std::cmp::min(known_peers.len(), 30);
                    for (i, (pid, (pname, _))) in known_peers.iter().enumerate() {
                        if i >= count { break; }
                        gossip_parts.push(pid.clone());
                        gossip_parts.push(pname.clone());
                    }
                    let gossip_payload = gossip_parts.join("|");
                    match transport.publish_gossip(&gossip_payload).await {
                        Ok(_) => tracing::info!("Gossip: broadcasted {} known peers", count),
                        Err(e) => tracing::info!("Gossip: broadcast failed: {}", e),
                    }
                }
            }
        }
    }
    async fn run_stun_discovery(
        public_addr: Arc<Mutex<Option<SocketAddr>>>,
    ) {
        use crate::network::ice::{StunClient, DEFAULT_STUN_SERVERS};

        // Повторяем периодически: одна попытка при старте часто приходится на
        // момент, когда сети ещё нет, а внешний адрес меняется при переходе
        // Wi-Fi <-> мобильный интернет. Без обновления телефон остаётся
        // недостижимым до перезапуска приложения.
        let mut backoff_secs = 15u64;
        loop {
            tracing::info!("STUN: discovering external address...");

            let result = tokio::task::spawn_blocking(|| {
                StunClient::get_external_address_from_any(DEFAULT_STUN_SERVERS)
            })
            .await;

            match result {
                Ok(Ok(addr)) => {
                    let changed = {
                        let mut guard = public_addr.lock().unwrap();
                        let changed = *guard != Some(addr);
                        *guard = Some(addr);
                        changed
                    };
                    if changed {
                        tracing::info!("STUN: my external address = {}", addr);
                    }
                    backoff_secs = 120;
                }
                Ok(Err(e)) => {
                    tracing::warn!("STUN: all servers failed: {}", e);
                    backoff_secs = std::cmp::min(backoff_secs.saturating_mul(2), 120);
                }
                Err(e) => {
                    tracing::warn!("STUN: task panicked: {}", e);
                    backoff_secs = std::cmp::min(backoff_secs.saturating_mul(2), 120);
                }
            }

            tokio::time::sleep(std::time::Duration::from_secs(backoff_secs)).await;
        }
    }
    /// Send message via MQTT (internet fallback)
    pub fn send_message_mqtt(&self, to_node_id: &str, payload: &str) -> bool {
        use crate::network::mqtt_transport::MQTT_BROKERS;
        use rumqttc::{AsyncClient, MqttOptions, QoS};

        let nid = self.node_id_str.clone().unwrap_or_default();
        let client_id = format!("p2pm_snd_{}", &nid[..8.min(nid.len())]);
        for (host, port) in MQTT_BROKERS {
            let mut opts = MqttOptions::new(&client_id, *host, *port);
            opts.set_keep_alive(std::time::Duration::from_secs(30));
            let (client, mut el) = AsyncClient::new(opts, 10);
            let topic = format!("p2pm2/msg/{}", to_node_id);
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all().build().unwrap();
            let result = rt.block_on(async {
                // 1. Poll to establish CONNECT
                for _ in 0..5 {
                    let _ = tokio::time::timeout(
                        std::time::Duration::from_millis(500), el.poll()
                    ).await;
                }
                // 2. Publish
                client.publish(&topic, QoS::AtLeastOnce, false, payload.as_bytes()).await?;
                // 3. Poll to flush PUBLISH + receive PUBACK
                for _ in 0..5 {
                    let _ = tokio::time::timeout(
                        std::time::Duration::from_millis(500), el.poll()
                    ).await;
                }
                Ok::<(), rumqttc::ClientError>(())
            });
            match result {
                Ok(_) => {
                    tracing::info!("MQTT: message sent to {}", to_node_id);
                    return true;
                }
                Err(e) => tracing::warn!("MQTT send failed via {}: {}", host, e),
            }
        }
        false
    }
    /// Generate invite link with our public address
    pub fn generate_invite(&self) -> String {
        let node_id = match &self.node_id_str {
            Some(id) => id.clone(),
            None => return String::new(),
        };
        let public_addr = self.public_addr.lock().unwrap();
        match *public_addr {
            Some(addr) => {
                let quic_addr = std::net::SocketAddr::new(addr.ip(), 7778);
                format!("p2pm://connect?node={}&addr={}", node_id, quic_addr)
            }
            None => format!("p2pm://connect?node={}", node_id),
        }
    }

    /// Connect to a remote peer via invite link
    pub fn connect_via_invite(&mut self, link: String) -> bool {
        let link = link.trim().to_string();
        if !link.starts_with("p2pm://connect?") {
            tracing::warn!("Invalid invite link");
            return false;
        }
        let params = &link["p2pm://connect?".len()..];
        let mut node_id_opt: Option<String> = None;
        let mut addr_opt: Option<SocketAddr> = None;
        for param in params.split('&') {
            if let Some(val) = param.strip_prefix("node=") {
                node_id_opt = Some(val.to_string());
            } else if let Some(val) = param.strip_prefix("addr=") {
                addr_opt = val.parse::<SocketAddr>().ok();
            }
        }
        let node_id = match node_id_opt {
            Some(id) => id,
            None => return false,
        };
        let addr = match addr_opt {
            Some(a) => a,
            None => return false,
        };
        self.peer_addrs.lock().unwrap().insert(node_id.clone(), addr);
        tracing::info!("Invite: added peer {} at {}", node_id, addr);
        self.events.emit(CoreEvent::PeerDiscovered {
            peer_id: node_id.clone(),
            display_name: "Anonymous".to_string(),
            is_local: false,
        });
        true
    }
    pub fn stop(&mut self) {
        self.network.stop();
        self.mqtt_outbound_tx = None;
        if let Some(rt) = self.runtime.take() {
            rt.shutdown_background();
        }
        self.state = EngineState::Stopped;
        self.events.emit(CoreEvent::EngineStopped);
    }

    pub fn node_id(&self) -> Option<String> {
        self.crypto.node_id()
    }

    pub fn public_key(&self) -> Option<String> {
        self.crypto.public_key()
    }

    pub fn on_network_available(&self) {
        self.network.set_status(NetworkStatus::Connecting);
        self.events.emit(CoreEvent::NetworkStatusChanged {
            status: "connecting".into(),
        });
    }

    pub fn on_network_lost(&self) {
        self.network.set_status(NetworkStatus::Offline);
        self.events.emit(CoreEvent::NetworkStatusChanged {
            status: "offline".into(),
        });
    }

    pub fn on_peer_connected(&self, peer_id: String, display_name: String) {
        self.network.set_status(NetworkStatus::Connected);
        self.events.emit(CoreEvent::PeerDiscovered {
            peer_id,
            display_name,
            is_local: false,
        });
        self.events.emit(CoreEvent::NetworkStatusChanged {
            status: "connected".into(),
        });
    }

    pub fn on_peer_lost(&self, peer_id: String) {
        self.network.remove_peer(&peer_id);
        self.events.emit(CoreEvent::PeerLost { peer_id });
    }

    pub fn network_status(&self) -> String {
        self.network.status_str()
    }

    /// M8-C: честный режим relay custody для диагностики/acceptance.
    /// "durable-encrypted" — зашифрованный durable-файл; "ram-only" — честный
    /// degrade (Keystore недоступен или путь не задан); "disabled" — движок
    /// ещё не стартовал.
    pub fn relay_custody_mode(&self) -> String {
        match &self.relay_custody {
            Some(custody) if custody.durable => "durable-encrypted".into(),
            Some(_) => "ram-only".into(),
            None => "disabled".into(),
        }
    }

    /// M8-C: число записей в relay-карантине (нерасшифровываемые/невалидные).
    /// Это диагностика честной потери custody, НЕ UI-доставка.
    pub fn relay_quarantine_count(&self) -> u64 {
        match &self.relay_custody {
            Some(custody) => custody.store.quarantine_count().unwrap_or(0) as u64,
            None => 0,
        }
    }

    pub fn connected_peers(&self) -> usize {
        self.network.peer_count()
    }

    /// Принудительно объявить себя и запросить присутствие остальных.
    /// Кнопка «Собрать данные об абонентах» раньше была заглушкой: она писала
    /// строку в лог и возвращала true, ничего не отправляя.
    pub fn trigger_gossip_discovery(&self) -> bool {
        if !self.state.is_running() {
            tracing::warn!("Gossip: manual trigger ignored, engine is not running");
            return false;
        }
        match self.mqtt_outbound_tx.as_ref() {
            Some(sender) => match sender.try_send(MqttOutboundCommand::AnnounceNow) {
                Ok(()) => {
                    tracing::info!("Gossip: manual announce command accepted");
                    true
                }
                Err(error) => {
                    tracing::warn!("Gossip: manual announce command rejected: {}", error);
                    false
                }
            },
            None => {
                tracing::warn!("Gossip: MQTT outbound channel unavailable");
                false
            }
        }
    }

    /// Параллельный QUIC-поток для файлов: отправка НАПРЯМУЮ получателю по QUIC,
    /// БЕЗ relay queue (файловые ACKи уже есть на прикладном уровне — message-level
    /// durability не нужен и только забивает очередь для текстовых сообщений).
    /// Возвращает true если QUIC-доставка удалась; false = получатель недоступен
    /// напрямую (вызывающий может использовать fallback через sendMessage).
    pub fn send_direct_payload(
        &self,
        recipient_id: String,
        payload: String,
    ) -> bool {
        if !self.state.is_running() {
            return false;
        }
        let sender_id = match self.node_id() {
            Some(id) => id,
            None => return false,
        };
        let addr_opt = {
            let addrs = self.peer_addrs.lock().unwrap();
            addrs
                .get(&recipient_id)
                .copied()
                .or_else(|| addrs.get(&format!("{}_public", recipient_id)).copied())
        };
        let addr = match addr_opt {
            Some(a) => a,
            None => return false,
        };
        // Формат должен совпадать с sendMessage: sender|msgId|chatId|text
        // Получатель парсит 4 части и routes по text (файловый хендлер смотрит на префикс apu-file1)
        let msg_id = uuid::Uuid::new_v4().to_string();
        let wire_payload = format!("{}|{}|direct|{}", sender_id, msg_id, payload);
        match &self.runtime {
            Some(rt) => {
                tracing::debug!(
                    "FILE STREAM: direct QUIC to {} ({} bytes)",
                    recipient_id,
                    wire_payload.len()
                );
                rt.block_on(async move { Self::send_via_quic(addr, wire_payload).await })
            }
            None => false,
        }
    }

    pub fn send_message(
        &self,
        message_id: String,
        chat_id: String,
        recipient_id: String,
        text: String,
    ) -> bool {
        if !self.state.is_running() {
            return false;
        }

        let sender_id = match self.node_id() {
            Some(id) => id,
            None => return false,
        };

        let _ = self.storage.save_message(
            message_id.clone(),
            chat_id.clone(),
            sender_id.clone(),
            text.clone(),
        );

        let addr_opt = {
            let addrs = self.peer_addrs.lock().unwrap();
            addrs
                .get(&recipient_id)
                .copied()
                .or_else(|| addrs.get(&format!("{}_public", recipient_id)).copied())
        };

        let direct_send_ok = if let Some(addr) = addr_opt {
            if let Some(rt) = &self.runtime {
                let payload = format!("{}|{}|{}|{}", sender_id, message_id, chat_id, text);
                tracing::info!("send_via_quic: to {}", recipient_id);
                rt.block_on(async move { Self::send_via_quic(addr, payload).await })
            } else {
                false
            }
        } else {
            false
        };

        if direct_send_ok {
            let _ = self
                .storage
                .update_message_status(&message_id, MessageStatus::Sent);
            self.events.emit(CoreEvent::MessageStatusChanged {
                message_id,
                status: "sent".into(),
            });
            return true;
        }

        // M3(d): no direct address (or direct send failed). Keep the existing N-1-compatible
        // relay encoding, own the origin copy in RelayQueue first, and only then offer one bounded
        // command to the persistent MQTT session. This is QUEUED_OFFLINE, never a SENT claim.
        let prepared = match prepare_offline_relay(
            &message_id,
            &recipient_id,
            &sender_id,
            &chat_id,
            text.as_bytes(),
        ) {
            Ok(prepared) => prepared,
            Err(error) => {
                tracing::warn!(
                    "MESH origin: rejected offline relay {} for {}: {}",
                    message_id,
                    recipient_id,
                    error
                );
                let _ = self
                    .storage
                    .update_message_status(&message_id, MessageStatus::Pending);
                self.events.emit(CoreEvent::MessageStatusChanged {
                    message_id,
                    status: "pending".into(),
                });
                return false;
            }
        };

        let relay_inserted = match self.relay_queue.as_ref() {
            Some(queue) if queue.contains(&message_id) => {
                tracing::info!(
                    "MESH origin: offline relay {} already retained for {}",
                    message_id,
                    recipient_id
                );
                false
            }
            Some(queue) => {
                // M8-B/C: durable encrypted custody собственного offline relay
                // persist-ится ДО enqueue. Если store недоступен/отклоняет —
                // честно не заявляем локальное retention (Outbox/Room retry при
                // этом сохраняются).
                let durable_admitted = match self.relay_custody.as_ref() {
                    Some(custody) => {
                        let now_durable = crate::network::relay_queue::utc_now_ms();
                        match custody.store.store_encrypted(
                            &*custody.keys,
                            &prepared.message,
                            now_durable,
                        ) {
                            Ok(_) => true,
                            Err(e) => {
                                tracing::warn!(
                                    "MESH origin: durable store failed for {}: {}",
                                    message_id,
                                    e
                                );
                                false
                            }
                        }
                    }
                    None => true, // RAM-only режим: legacy поведение
                };

                if !durable_admitted {
                    false
                } else {
                    match queue.enqueue(prepared.message) {
                        Ok(true) => {
                            tracing::info!(
                                "MESH origin: retained offline relay {} for {} at hop 0",
                                message_id,
                                recipient_id
                            );
                            true
                        }
                        Ok(false) => {
                            tracing::info!(
                                "MESH origin: offline relay {} not inserted (duplicate/hop limit)",
                                message_id
                            );
                            false
                        }
                        Err(error) => {
                            tracing::warn!(
                                "MESH origin: cannot retain offline relay {} for {}: {}",
                                message_id,
                                recipient_id,
                                error
                            );
                            false
                        }
                    }
                }
            }
            None => {
                tracing::warn!(
                    "MESH origin: relay queue unavailable for offline message {}",
                    message_id
                );
                false
            }
        };

        if relay_inserted {
            // Preserve the legacy same-LAN retry queue during rollout. It is populated only once,
            // after RelayQueue dedup admitted this msg_id, so Kotlin retries do not multiply it.
            if let (Some(queue), Some(rt)) = (&self.message_queue, &self.runtime) {
                use sha2::{Digest, Sha256};
                let mut recipient_hasher = Sha256::new();
                recipient_hasher.update(recipient_id.as_bytes());
                let recipient_hash = recipient_hasher.finalize();
                let mut recipient_key = [0u8; 32];
                recipient_key.copy_from_slice(&recipient_hash);

                let mut message_hasher = Sha256::new();
                message_hasher.update(message_id.as_bytes());
                let message_hash = message_hasher.finalize();
                let mut message_key = [0u8; 16];
                message_key.copy_from_slice(&message_hash[..16]);

                let legacy_payload =
                    format!("{}|{}|{}|{}", sender_id, message_id, chat_id, text);
                let queued = crate::network::message_queue::QueuedMessage::new(
                    message_key,
                    recipient_key,
                    legacy_payload.into_bytes(),
                );
                let queue = Arc::clone(queue);
                if let Err(error) = rt.block_on(async move { queue.enqueue(queued).await }) {
                    tracing::warn!(
                        "MESH origin: legacy retry queue rejected {}: {}",
                        message_id,
                        error
                    );
                }
            }

            match self.mqtt_outbound_tx.as_ref() {
                Some(sender) => match sender.try_send(MqttOutboundCommand::MeshRelay {
                    recipient: recipient_id.clone(),
                    envelope: prepared.envelope,
                    message_id: message_id.clone(),
                }) {
                    Ok(()) => tracing::info!(
                        "MESH origin: offline relay command accepted {} for {}",
                        message_id,
                        recipient_id
                    ),
                    Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => tracing::warn!(
                        "MESH origin: outbound command queue full for {}; retained locally",
                        message_id
                    ),
                    Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => tracing::warn!(
                        "MESH origin: outbound command queue closed for {}; retained locally",
                        message_id
                    ),
                },
                None => tracing::warn!(
                    "MESH origin: MQTT outbound channel unavailable for {}; retained locally",
                    message_id
                ),
            }
        }

        let _ = self
            .storage
            .update_message_status(&message_id, MessageStatus::Pending);
        self.events.emit(CoreEvent::MessageStatusChanged {
            message_id,
            status: "queued_offline".into(),
        });
        false
    }
    async fn send_via_quic(addr: SocketAddr, payload: String) -> bool {
        use crate::network::quic_client::QuicClient;
        use std::net::{IpAddr, Ipv4Addr};
        use std::time::Duration;

        let bind_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
        tracing::info!("QUIC send start to {} payload={}", addr, payload);

        let client = match QuicClient::new(bind_addr) {
            Ok(c) => c,
            Err(e) => {
                tracing::warn!("QUIC client create failed: {}", e);
                return false;
            }
        };

        let conn = match tokio::time::timeout(
            Duration::from_secs(5),
            client.connect(addr, "p2p-messenger"),
        ).await {
            Ok(Ok(c)) => {
                tracing::info!("QUIC connect ok to {}", addr);
                c
            }
            Ok(Err(e)) => {
                tracing::warn!("QUIC connect failed to {}: {}", addr, e);
                return false;
            }
            Err(_) => {
                tracing::warn!("QUIC connect timeout to {}", addr);
                return false;
                    // TCP fallback
            }
        };

        match tokio::time::timeout(
            Duration::from_secs(5),
            conn.send_message(payload.as_bytes()),
        ).await {
            Ok(Ok(_)) => {
                tracing::info!("QUIC send ok to {}", addr);
                true
            }
            Ok(Err(e)) => {
                tracing::warn!("QUIC send failed to {}: {}", addr, e);
                false
            }
            Err(_) => {
                tracing::warn!("QUIC send timeout to {}", addr);
                false
            }
        }
    }

    pub fn receive_message(
        &self,
        message_id: String,
        chat_id: String,
        sender_id: String,
        encrypted_text: String,
        timestamp: i64,
    ) {
        let text = match self.crypto.decrypt(&encrypted_text, &sender_id) {
            Some(bytes) => String::from_utf8(bytes).unwrap_or(encrypted_text.clone()),
            None => encrypted_text,
        };

        let _ = self.storage.save_message(
            message_id.clone(),
            chat_id.clone(),
            sender_id.clone(),
            text.clone(),
        );

        self.events.emit(CoreEvent::MessageReceived {
            message_id,
            chat_id,
            sender_id,
            text,
            timestamp,
        });
    }

    pub fn mark_message_read(&self, message_id: &str) -> bool {
        let result = self
            .storage
            .update_message_status(message_id, MessageStatus::Read);

        if result.is_ok() {
            self.events.emit(CoreEvent::MessageStatusChanged {
                message_id: message_id.to_string(),
                status: "read".into(),
            });
        }

        result.is_ok()
    }

    pub fn poll_event(&self) -> Option<CoreEvent> {
        self.events.poll()
    }

    pub fn drain_events(&self) -> Vec<CoreEvent> {
        let evts = self.events.drain();
        if !evts.is_empty() {
            tracing::info!("drain_events: {} events: {:?}", evts.len(),
                evts.iter().map(|e| e.event_type()).collect::<Vec<_>>());
        }
        evts
    }

    pub fn pending_events(&self) -> usize {
        self.events.len()
    }

    pub fn get_chats(&self) -> Vec<crate::storage::models::Chat> {
        self.storage.get_all_chats()
    }

    pub fn get_messages(
        &self,
        chat_id: &str,
        limit: usize,
    ) -> Vec<crate::storage::models::Message> {
        self.storage.get_messages(chat_id, limit)
    }

    pub fn create_chat(&self, chat_id: String) -> bool {
        self.storage.create_direct_chat(chat_id).is_ok()
    }

    pub fn add_contact(&self, user_id: String, display_name: String) -> bool {
        self.storage.save_user(user_id, display_name, false).is_ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_engine() -> P2PCore {
        P2PCore::with_defaults()
    }

    fn make_running_engine() -> P2PCore {
        let mut e = make_engine();
        e.start();
        e
    }

    #[test]
    fn bounded_id_cache_evicts_oldest() {
        let mut entries = HashSet::new();
        let mut order = VecDeque::new();
        remember_bounded_id(&mut entries, &mut order, "m1", 2);
        remember_bounded_id(&mut entries, &mut order, "m2", 2);
        remember_bounded_id(&mut entries, &mut order, "m3", 2);

        assert!(!entries.contains("m1"));
        assert!(entries.contains("m2"));
        assert!(entries.contains("m3"));
        assert_eq!(order.len(), 2);
    }

    #[test]
    fn bounded_delivery_cache_keeps_first_origin_and_evicts_oldest() {
        let mut entries = HashMap::new();
        let mut order = VecDeque::new();
        remember_bounded_delivery(&mut entries, &mut order, "m1", "pk_a", 2);
        remember_bounded_delivery(&mut entries, &mut order, "m1", "pk_attacker", 2);

        assert_eq!(entries.get("m1").map(String::as_str), Some("pk_a"));
        assert_eq!(order.len(), 1);

        remember_bounded_delivery(&mut entries, &mut order, "m2", "pk_b", 2);
        remember_bounded_delivery(&mut entries, &mut order, "m3", "pk_c", 2);

        assert!(!entries.contains_key("m1"));
        assert_eq!(entries.get("m2").map(String::as_str), Some("pk_b"));
        assert_eq!(entries.get("m3").map(String::as_str), Some("pk_c"));
        assert_eq!(order.len(), 2);
    }

    #[test]
    fn test_initial_state() {
        let engine = make_engine();
        assert_eq!(engine.state(), &EngineState::Uninitialized);
        assert!(!engine.is_running());
    }

    #[test]
    fn test_start_success() {
        let mut engine = make_engine();
        assert!(engine.start());
        assert!(engine.is_running());
    }

    #[test]
    fn test_stop() {
        let mut engine = make_running_engine();
        engine.stop();
        assert_eq!(engine.state(), &EngineState::Stopped);
    }

    #[test]
    fn test_node_id_after_start() {
        let engine = make_running_engine();
        assert!(engine.node_id().is_some());
    }

    #[test]
    fn test_load_existing_keys() {
        let config = EngineConfig::default().with_keys("pk_test".into(), "sk_test".into());
        let mut engine = P2PCore::new(config);
        engine.start();
        assert_eq!(engine.public_key(), Some("pk_test".into()));
    }
}


