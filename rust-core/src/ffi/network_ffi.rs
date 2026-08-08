//! Network FFI — публичный API сети для Kotlin

use std::collections::HashMap;
use std::sync::Mutex;

// ============================================================
// Network Status
// ============================================================

/// Статус сетевого соединения
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum NetworkStatus {
    /// Нет соединения
    Offline,
    /// Подключаемся
    Connecting,
    /// Подключены (P2P)
    Connected,
    /// Только через ретранслятор
    Relayed,
}

impl NetworkStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            NetworkStatus::Offline => "offline",
            NetworkStatus::Connecting => "connecting",
            NetworkStatus::Connected => "connected",
            NetworkStatus::Relayed => "relayed",
        }
    }

    pub fn is_online(&self) -> bool {
        matches!(self, NetworkStatus::Connected | NetworkStatus::Relayed)
    }
}

impl std::fmt::Display for NetworkStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.as_str())
    }
}

// ============================================================
// Peer Info
// ============================================================

/// Информация о подключённом peer
#[derive(Debug, Clone)]
pub struct PeerInfo {
    pub peer_id: String,
    pub display_name: String,
    pub status: String,
    pub last_seen_ms: i64,
    pub is_direct: bool,
}

impl PeerInfo {
    pub fn new(peer_id: String, display_name: String) -> Self {
        Self {
            peer_id,
            display_name,
            status: "online".into(),
            last_seen_ms: 0,
            is_direct: false,
        }
    }
}

// ============================================================
// Outbound Message
// ============================================================

/// Исходящее сообщение
#[derive(Debug, Clone)]
pub struct OutboundMessage {
    pub id: String,
    pub recipient_id: String,
    pub payload: Vec<u8>,
    pub created_at: i64,
}

impl OutboundMessage {
    pub fn new(id: String, recipient_id: String, payload: Vec<u8>) -> Self {
        Self {
            id,
            recipient_id,
            payload,
            created_at: crate::storage::models::now_ms(),
        }
    }
}

// ============================================================
// Send Result
// ============================================================

/// Результат отправки сообщения
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SendResult {
    /// Доставлено напрямую
    Delivered,
    /// Поставлено в очередь (store-and-forward)
    Queued,
    /// Ошибка
    Failed(String),
}

impl SendResult {
    pub fn is_ok(&self) -> bool {
        matches!(self, SendResult::Delivered | SendResult::Queued)
    }
}

// ============================================================
// Network Manager FFI
// ============================================================

/// Менеджер сети — единственная точка входа из Kotlin
pub struct NetworkManagerFfi {
    status: Mutex<NetworkStatus>,
    peers: Mutex<HashMap<String, PeerInfo>>,
    sent_count: Mutex<u64>,
    queued_count: Mutex<u64>,
    local_node_id: Mutex<Option<String>>,
}

impl NetworkManagerFfi {
    pub fn new() -> Self {
        Self {
            status: Mutex::new(NetworkStatus::Offline),
            peers: Mutex::new(HashMap::new()),
            sent_count: Mutex::new(0),
            queued_count: Mutex::new(0),
            local_node_id: Mutex::new(None),
        }
    }

    /// Запустить сетевой стек
    pub fn start(&self, node_id: String) -> bool {
        if node_id.is_empty() {
            return false;
        }
        *self.local_node_id.lock().unwrap() = Some(node_id);
        *self.status.lock().unwrap() = NetworkStatus::Connecting;
        true
    }

    /// Остановить сетевой стек
    pub fn stop(&self) {
        *self.status.lock().unwrap() = NetworkStatus::Offline;
        *self.local_node_id.lock().unwrap() = None;
    }

    /// Текущий статус сети
    pub fn status(&self) -> NetworkStatus {
        self.status.lock().unwrap().clone()
    }

    /// Статус как строка (для Kotlin)
    pub fn status_str(&self) -> String {
        self.status.lock().unwrap().as_str().to_string()
    }

    /// Установить статус (вызывается внутренними событиями)
    pub fn set_status(&self, status: NetworkStatus) {
        *self.status.lock().unwrap() = status;
    }

    /// Добавить peer
    pub fn add_peer(&self, info: PeerInfo) {
        self.peers
            .lock()
            .unwrap()
            .insert(info.peer_id.clone(), info);
    }

    /// Удалить peer
    pub fn remove_peer(&self, peer_id: &str) {
        self.peers.lock().unwrap().remove(peer_id);
    }

    /// Получить список peer
    pub fn peers(&self) -> Vec<PeerInfo> {
        self.peers.lock().unwrap().values().cloned().collect()
    }

    /// Количество подключённых peers
    pub fn peer_count(&self) -> usize {
        self.peers.lock().unwrap().len()
    }

    /// Отправить сообщение peer
    pub fn send_message(&self, msg: OutboundMessage) -> SendResult {
        if self.status.lock().unwrap().as_str() == "offline" {
            return SendResult::Failed("Network is offline".into());
        }

        let peers = self.peers.lock().unwrap();
        if peers.contains_key(&msg.recipient_id) {
            // Peer доступен — отправляем напрямую
            drop(peers);
            *self.sent_count.lock().unwrap() += 1;
            SendResult::Delivered
        } else {
            // Peer недоступен — в очередь
            drop(peers);
            *self.queued_count.lock().unwrap() += 1;
            SendResult::Queued
        }
    }

    /// Статистика отправок
    pub fn sent_count(&self) -> u64 {
        *self.sent_count.lock().unwrap()
    }

    pub fn queued_count(&self) -> u64 {
        *self.queued_count.lock().unwrap()
    }

    /// Node ID
    pub fn local_node_id(&self) -> Option<String> {
        self.local_node_id.lock().unwrap().clone()
    }

    /// Сеть запущена?
    pub fn is_running(&self) -> bool {
        self.local_node_id.lock().unwrap().is_some()
    }
}

impl Default for NetworkManagerFfi {
    fn default() -> Self {
        Self::new()
    }
}

// ============================================================
// TESTS
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn make_manager() -> NetworkManagerFfi {
        NetworkManagerFfi::new()
    }

    // --- NetworkStatus ---

    #[test]
    fn test_status_display() {
        assert_eq!(NetworkStatus::Offline.as_str(), "offline");
        assert_eq!(NetworkStatus::Connected.as_str(), "connected");
        assert_eq!(NetworkStatus::Relayed.as_str(), "relayed");
    }

    #[test]
    fn test_status_is_online() {
        assert!(!NetworkStatus::Offline.is_online());
        assert!(!NetworkStatus::Connecting.is_online());
        assert!(NetworkStatus::Connected.is_online());
        assert!(NetworkStatus::Relayed.is_online());
    }

    // --- NetworkManagerFfi ---

    #[test]
    fn test_initial_state() {
        let m = make_manager();
        assert_eq!(m.status(), NetworkStatus::Offline);
        assert_eq!(m.peer_count(), 0);
        assert!(!m.is_running());
    }

    #[test]
    fn test_start_sets_connecting() {
        let m = make_manager();
        assert!(m.start("node1".into()));
        assert_eq!(m.status(), NetworkStatus::Connecting);
        assert!(m.is_running());
    }

    #[test]
    fn test_start_empty_node_fails() {
        let m = make_manager();
        assert!(!m.start("".into()));
        assert_eq!(m.status(), NetworkStatus::Offline);
    }

    #[test]
    fn test_stop() {
        let m = make_manager();
        m.start("node1".into());
        m.stop();
        assert_eq!(m.status(), NetworkStatus::Offline);
        assert!(!m.is_running());
    }

    #[test]
    fn test_set_status() {
        let m = make_manager();
        m.set_status(NetworkStatus::Connected);
        assert_eq!(m.status(), NetworkStatus::Connected);
        assert_eq!(m.status_str(), "connected");
    }

    #[test]
    fn test_add_and_get_peers() {
        let m = make_manager();
        m.add_peer(PeerInfo::new("p1".into(), "Alice".into()));
        m.add_peer(PeerInfo::new("p2".into(), "Bob".into()));
        assert_eq!(m.peer_count(), 2);
    }

    #[test]
    fn test_remove_peer() {
        let m = make_manager();
        m.add_peer(PeerInfo::new("p1".into(), "Alice".into()));
        m.remove_peer("p1");
        assert_eq!(m.peer_count(), 0);
    }

    #[test]
    fn test_send_message_offline() {
        let m = make_manager();
        let msg = OutboundMessage::new("m1".into(), "p1".into(), b"hello".to_vec());
        let result = m.send_message(msg);
        assert_eq!(result, SendResult::Failed("Network is offline".into()));
        assert!(!result.is_ok());
    }

    #[test]
    fn test_send_message_peer_online() {
        let m = make_manager();
        m.start("me".into());
        m.set_status(NetworkStatus::Connected);
        m.add_peer(PeerInfo::new("p1".into(), "Alice".into()));
        let msg = OutboundMessage::new("m1".into(), "p1".into(), b"hi".to_vec());
        assert_eq!(m.send_message(msg), SendResult::Delivered);
        assert_eq!(m.sent_count(), 1);
    }

    #[test]
    fn test_send_message_peer_offline_queued() {
        let m = make_manager();
        m.start("me".into());
        m.set_status(NetworkStatus::Connected);
        // peer не добавлен — в очередь
        let msg = OutboundMessage::new("m1".into(), "unknown".into(), b"hi".to_vec());
        assert_eq!(m.send_message(msg), SendResult::Queued);
        assert_eq!(m.queued_count(), 1);
    }

    #[test]
    fn test_local_node_id() {
        let m = make_manager();
        m.start("my_node".into());
        assert_eq!(m.local_node_id(), Some("my_node".into()));
    }

    #[test]
    fn test_send_result_is_ok() {
        assert!(SendResult::Delivered.is_ok());
        assert!(SendResult::Queued.is_ok());
        assert!(!SendResult::Failed("err".into()).is_ok());
    }
}
