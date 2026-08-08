//! # Network Module — Сетевой движок
//!
//! Отвечает за всё, что касается сети:
//!
//! - **quic_client**       — QUIC-соединения через библиотеку `quinn`
//! - **connection_pool**   — пул активных соединений (переиспользование)
//! - **mdns**              — обнаружение узлов в локальной сети
//! - **dht**               — Kademlia DHT (следующий этап)
//! - **ice**               — NAT Traversal (следующий этап)
//! - **relay**             — ретрансляция (следующий этап)
//! - **router**            — маршрутизация (следующий этап)
//! - **message_queue**     — store-and-forward (следующий этап)
//! - **presence**          — Gossip Protocol (следующий этап)
//! - **fallback_chain**    — каскадный поиск соединения (следующий этап)
//! - **adaptive_polling**  — экспоненциальный backoff (следующий этап)

pub mod adaptive_polling;
pub mod connection_manager;
pub mod connection_pool;
pub mod dht;
pub mod fallback_chain;
pub mod ice;
pub mod mdns;
pub mod message_queue;
pub mod nat_types;
pub mod presence;
pub mod quic_client;
pub mod relay;
pub mod router;

// Реэкспорты для удобства
pub use connection_pool::{ConnectionPool, ConnectionPoolError};
pub use dht::{bucket_index, xor_distance, Bucket, DhtNodeInfo, RoutingTable};
pub use ice::{IceError, StunClient, DEFAULT_STUN_SERVERS};
pub use mdns::{DiscoveredNode, MdnsError, MdnsService};
pub use message_queue::{
    MessageQueue, QueueError, QueuedMessage, DEFAULT_MESSAGE_TTL, MAX_RETRY_COUNT,
};
pub use presence::{GossipDecision, KnownNode, PresenceManager, DEFAULT_GOSSIP_TTL};
pub use quic_client::{QuicClient, QuicClientError, QuicConnection};
pub use relay::{DropReason, RelayAction, RelayError, RelayManager, RelayStats};
pub use router::{RouteDecision, Router, RoutingRecord, DEFAULT_TTL};

// ═══════════════════════════════════════════════════════════════════
// ОБЩИЕ ТИПЫ
// ═══════════════════════════════════════════════════════════════════

/// Единый результат сетевых операций.
pub type NetworkResult<T> = Result<T, NetworkError>;

/// Общий тип ошибки для всего сетевого модуля.
///
/// Разные подмодули имеют свои специфические ошибки, но они все
/// могут быть преобразованы в `NetworkError` через `From`.
#[derive(Debug, thiserror::Error)]
pub enum NetworkError {
    #[error("Ошибка QUIC: {0}")]
    Quic(#[from] QuicClientError),

    #[error("Ошибка пула соединений: {0}")]
    ConnectionPool(#[from] ConnectionPoolError),

    #[error("Ошибка mDNS: {0}")]
    Mdns(#[from] MdnsError),

    #[error("Ошибка ICE/STUN: {0}")]
    Ice(#[from] IceError),

    #[error("Ошибка ретрансляции: {0}")]
    Relay(#[from] RelayError),

    #[error("Ошибка очереди сообщений: {0}")]
    Queue(#[from] QueueError),

    #[error("Ошибка ввода-вывода: {0}")]
    Io(#[from] std::io::Error),

    #[error("Таймаут операции")]
    Timeout,

    #[error("Соединение не найдено: {0}")]
    ConnectionNotFound(String),
}

pub mod mqtt_transport;
pub mod multi_broker;
