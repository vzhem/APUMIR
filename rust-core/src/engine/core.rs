use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use crate::ffi::crypto_ffi::CryptoManager;
use crate::ffi::network_ffi::{NetworkManagerFfi, NetworkStatus, OutboundMessage, PeerInfo};
use crate::ffi::storage_ffi::StorageManagerFfi;
use crate::storage::models::MessageStatus;

use super::events::{CoreEvent, EventBus};
use crate::network::connection_pool::ConnectionPool;
use crate::network::router::Router;
use crate::network::dht::{RoutingTable, DhtNodeInfo};
use crate::network::relay::RelayManager;
use crate::network::mqtt_transport::MqttTransport;
use crate::network::presence::PresenceManager;
use crate::network::message_queue::MessageQueue;
use crate::network::adaptive_polling::AdaptivePolling;

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
            let _ = 0; // modules initialized
        }
        tracing::info!("Network stack initialized: Router+DHT+Relay+Presence+Queue");

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
                let node_id_mqtt = node_id.clone();
                let display_mqtt = display_name.clone();
                let public_addr_mqtt = Arc::clone(&public_addr_arc);
                let queue_mqtt = queue2.clone();
                std::thread::spawn(move || {
                    let rt = tokio::runtime::Builder::new_current_thread()
                        .enable_all()
                        .build()
                        .unwrap();
                    rt.block_on(async move {
                        Self::run_mqtt_transport(events_mqtt, node_id_mqtt, display_mqtt, public_addr_mqtt, queue_mqtt).await;
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

        loop {
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
    async fn run_mqtt_transport(
        events: Arc<EventBus>,
        node_id: String,
        display_name: String,
        public_addr: Arc<Mutex<Option<SocketAddr>>>,
        queue: Option<Arc<MessageQueue>>,
    ) {
        use crate::network::mqtt_transport::MqttTransport;

        // Wait for STUN to complete
        tokio::time::sleep(std::time::Duration::from_secs(3)).await;

        let mut transport = match MqttTransport::connect(&node_id, &display_name).await {
            Ok(t) => {
                tracing::info!("MQTT: transport connected");
                t
            }
            Err(e) => {
                tracing::error!("MQTT: connect failed: {}", e);
                return;
            }
        };

        if let Err(e) = transport.subscribe().await {
            tracing::error!("MQTT: subscribe failed: {}", e);
            return;
        }

        // Publish presence
        let addr_str = {
            let pa = public_addr.lock().unwrap();
            pa.map(|a| a.to_string())
        };
        let is_relay = addr_str.is_some();
        let _ = transport.publish_presence(&display_name, addr_str.as_deref(), is_relay).await;

        if is_relay {
            if let Some(ref addr) = addr_str {
                let _ = transport.register_as_relay(addr).await;
                tracing::info!("MQTT: registered as relay at {}", addr);
            }
        }

        // Event loop (poll every 1s, presence every 30s)
        let mut tick: u32 = 0;
        // === GOSSIP PROTOCOL: peer exchange state ===
        let mut known_peers: std::collections::HashMap<String, (String, std::time::Instant)> = std::collections::HashMap::new();
        let mut seen_gossip: std::collections::VecDeque<String> = std::collections::VecDeque::new();
        const MAX_GOSSIP_CACHE: usize = 500;
        loop {
            if let Some(evt) = transport.poll_event().await {
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
                        } else {
                            if let Some(entry) = known_peers.get_mut(peer_id) {
                                entry.1 = std::time::Instant::now();
                            }
                        }
                    }
                } else if evt.topic.starts_with("p2pm2/msg/") {
                    // Р¤РѕСЂРјР°С‚: senderId|messageId|chatId|recipientId|text
                    // ACK: "ack|messageId" — подтверждение доставки (получатель → отправитель).
                    // Не является сообщением чата — обновляем статус и не парсим как 5-полей.
                    if let Some(rest) = evt.payload.strip_prefix("ack|") {
                        let mid = rest.trim();
                        if !mid.is_empty() {
                            tracing::info!("MQTT: delivery ACK received for {}", mid);
                            events.emit(CoreEvent::MessageDelivered {
                                message_id: mid.to_string(),
                            });
                        }
                    }
                    let parts: Vec<&str> = evt.payload.splitn(5, '|').collect();
                    if parts.len() == 5 && parts[0] != node_id {  // Skip own messages
                        let sender_id = parts[0];
                        let message_id = parts[1];
                        let chat_id = parts[2];
                        let recipient_id = parts[3];
                        let text = parts[4];
                        
                        // Р¤РР›Р¬РўР РђР¦РРЇ: РїСЂРѕРІРµСЂСЏРµРј С‡С‚Рѕ СЃРѕРѕР±С‰РµРЅРёРµ Р°РґСЂРµСЃРѕРІР°РЅРѕ РЅР°Рј
                        if recipient_id != node_id {
                            tracing::debug!("MQTT: message for {} (not me {}) вЂ” relay mode", recipient_id, node_id);
                            
                            // RELAY: СЃРѕС…СЂР°РЅРёС‚СЊ РІ РѕС‡РµСЂРµРґСЊ РґР»СЏ РѕС„С„Р»Р°Р№РЅ РґРѕСЃС‚Р°РІРєРё
                            if let Some(ref queue) = queue {
                                use sha2::{Sha256, Digest};
                                
                                let mut rh = Sha256::new();
                                rh.update(recipient_id.as_bytes());
                                let rh_result = rh.finalize();
                                let mut rid = [0u8; 32];
                                rid.copy_from_slice(&rh_result);
                                
                                let mut mh = Sha256::new();
                                mh.update(message_id.as_bytes());
                                let mh_result = mh.finalize();
                                let mut mid = [0u8; 16];
                                mid.copy_from_slice(&mh_result[..16]);
                                
                                let payload = evt.payload.clone();
                                let qmsg = crate::network::message_queue::QueuedMessage::new(
                                    mid, rid, payload.as_bytes().to_vec()
                                );
                                
                                if let Err(e) = queue.enqueue(qmsg).await {
                                    tracing::warn!("Failed to enqueue: {}", e);
                                } else {
                                    tracing::info!("вњ“ Queued msg {} for relay to {}", message_id, recipient_id);
                                }
                            }
                            
                            // no continue — allow tick += 1 to execute
                        }
                        
                        tracing::info!("MQTT: message from {} to {}", sender_id, recipient_id);
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
            tick += 1;
            if tick >= 30 {
                tick = 0;
                let _ = transport.publish_presence(&display_name, addr_str.as_deref(), is_relay).await;
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

        tracing::info!("STUN: discovering external address...");

        let result = tokio::task::spawn_blocking(|| {
            StunClient::get_external_address_from_any(DEFAULT_STUN_SERVERS)
        })
        .await;

        match result {
            Ok(Ok(addr)) => {
                tracing::info!("STUN: my external address = {}", addr);
                *public_addr.lock().unwrap() = Some(addr);
            }
            Ok(Err(e)) => {
                tracing::warn!("STUN: all servers failed: {}", e);
            }
            Err(e) => {
                tracing::warn!("STUN: task panicked: {}", e);
            }
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

    pub fn connected_peers(&self) -> usize {
        self.network.peer_count()
    }

    /// ринудительно запустить Gossip broadcast (вызывается из UI)
    pub fn trigger_gossip_discovery(&self) -> bool {
        tracing::info!("Gossip: manual trigger requested");
        // TODO: реализовать через канал связи с MQTT loop
        true
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
            addrs.get(&recipient_id).copied()
                .or_else(|| addrs.get(&format!("{}_public", recipient_id)).copied())
        };

        let send_ok = if let Some(addr) = addr_opt {
            if let Some(rt) = &self.runtime {
                let payload = format!("{}|{}|{}|{}", sender_id, message_id, sender_id, text);
                tracing::info!("send_via_quic: to {}", recipient_id);
                tracing::info!("send_via_quic_text: {}", payload);
                rt.block_on(async move {
                    Self::send_via_quic(addr, payload).await
                })
            } else {
                false
            }
        } else {
            tracing::warn!("No address known for {} - PENDING", recipient_id);
            // Store-and-forward: queue message for offline peer
            if let Some(ref queue) = self.message_queue {
                use sha2::{Sha256, Digest};
                let mut rh = Sha256::new();
                rh.update(recipient_id.as_bytes());
                let rhash = rh.finalize();
                let mut rid = [0u8; 32];
                rid.copy_from_slice(&rhash);
                let mut mh = Sha256::new();
                mh.update(message_id.as_bytes());
                let mhash = mh.finalize();
                let mut mid = [0u8; 16];
                mid.copy_from_slice(&mhash[..16]);
                let formatted = format!("{}|{}|{}|{}", sender_id, message_id, sender_id, text);
                let qmsg = crate::network::message_queue::QueuedMessage::new(mid, rid, formatted.as_bytes().to_vec());
                if let Some(rt) = &self.runtime {
                    let queue2 = Arc::clone(queue);
                    rt.block_on(async move {
                        let _ = queue2.enqueue(qmsg).await;
                    });
                }
                tracing::info!("Message queued for offline peer {}", recipient_id);
            }
            false
        };

        let status = if send_ok {
            MessageStatus::Sent
        } else {
            MessageStatus::Failed
        };

        let _ = self.storage.update_message_status(&message_id, status);

        self.events.emit(CoreEvent::MessageStatusChanged {
            message_id: message_id.clone(),
            status: "sent".into(),  // Р’СЃРµРіРґР° sent - retry РїСЂРѕРґРѕР»Р¶РёС‚СЃСЏ С‡РµСЂРµР· queue
        });

        // Store-and-forward: Р’РЎР•Р“Р”Рђ queue РґР»СЏ РѕС„С„Р»Р°Р№РЅ РїРѕР»СѓС‡Р°С‚РµР»РµР№
        // Р”Р°Р¶Рµ РµСЃР»Рё MQTT РїСЂРёРЅСЏР» СЃРѕРѕР±С‰РµРЅРёРµ, Р±СЂРѕРєРµСЂ РЅРµ СЃРѕС…СЂР°РЅСЏРµС‚ РґР»СЏ offline
        // РџРѕР»СѓС‡Р°С‚РµР»СЊ РїРѕР»СѓС‡РёС‚ РїРѕРІС‚РѕСЂРЅРѕ С‡РµСЂРµР· dequeue_for РїСЂРё peer_discovered
        if recipient_id != self.node_id_str.clone().unwrap_or_default() {
            if let Some(ref queue) = self.message_queue {
                use sha2::{Sha256, Digest};
                let mut rh = Sha256::new();
                rh.update(recipient_id.as_bytes());
                let rhash = rh.finalize();
                let mut rid = [0u8; 32];
                rid.copy_from_slice(&rhash);
                let mut mh = Sha256::new();
                mh.update(message_id.as_bytes());
                let mhash = mh.finalize();
                let mut mid = [0u8; 16];
                mid.copy_from_slice(&mhash[..16]);
                let formatted = format!("{}|{}|{}|{}", sender_id, message_id, sender_id, text);
                let qmsg = crate::network::message_queue::QueuedMessage::new(mid, rid, formatted.as_bytes().to_vec());
                if let Some(rt) = &self.runtime {
                    let queue2 = Arc::clone(queue);
                    rt.block_on(async move {
                        let _ = queue2.enqueue(qmsg).await;
                    });
                }
                tracing::info!("Message queued for retry: {}", recipient_id);
            }
        }

        let msg = OutboundMessage::new(message_id, recipient_id, Vec::new());
        self.network.send_message(msg).is_ok()
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


