use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Кэш всех известных адресов каждого peer.
/// Хранит LAN IP, WAN IP, relay адрес, BLE адрес.
/// При подключении пробует по очереди (приоритет).
pub struct AddressCache {
    inner: Arc<Mutex<Inner>>,
}

#[derive(Debug, Clone)]
pub struct PeerAddress {
    pub addr: String,       // "192.168.1.5:7778" или "mqtt://broker" или "ble://MAC"
    pub kind: AddrKind,
    pub last_seen: Instant,
    pub success_count: u32, // сколько раз успешно соединились
    pub fail_count: u32,    // сколько раз не удалось
}

#[derive(Debug, Clone, PartialEq)]
pub enum AddrKind {
    LanTcp,      // Прямое TCP в локальной сети (приоритет 1)
    WanTcp,      // Прямое TCP через WAN (приоритет 2)
    MqttRelay,   // MQTT relay (приоритет 3)
    BleDirect,   // Bluetooth (приоритет 4)
    WifiDirect,  // WiFi Direct (приоритет 5)
    WebRtc,      // WebRTC TURN (приоритет 6)
}

impl AddrKind {
    pub fn priority(&self) -> u32 {
        match self {
            AddrKind::LanTcp => 1,
            AddrKind::WanTcp => 2,
            AddrKind::MqttRelay => 3,
            AddrKind::BleDirect => 4,
            AddrKind::WifiDirect => 5,
            AddrKind::WebRtc => 6,
        }
    }
}

struct Inner {
    /// peer_id -> список адресов (отсортированы по приоритету + успеху)
    peers: HashMap<String, Vec<PeerAddress>>,
    /// TTL адреса (если не видели 10 мин → устарел)
    ttl: Duration,
}

impl AddressCache {
    pub fn new(ttl_secs: u64) -> Self {
        Self {
            inner: Arc::new(Mutex::new(Inner {
                peers: HashMap::new(),
                ttl: Duration::from_secs(ttl_secs),
            })),
        }
    }

    /// Добавить/обновить адрес peer
    pub fn add_address(&self, peer_id: &str, addr: &str, kind: AddrKind) {
        let mut inner = self.inner.lock().unwrap();
        let list = inner.peers.entry(peer_id.to_string()).or_default();

        // Обновить если уже есть
        if let Some(existing) = list.iter_mut().find(|a| a.addr == addr) {
            existing.last_seen = Instant::now();
            return;
        }

        // Добавить новый
        list.push(PeerAddress {
            addr: addr.to_string(),
            kind,
            last_seen: Instant::now(),
            success_count: 0,
            fail_count: 0,
        });

        // Сортировка: приоритет → успехи → свежесть
        list.sort_by(|a, b| {
            a.kind.priority().cmp(&b.kind.priority())
                .then(b.success_count.cmp(&a.success_count))
                .then(b.last_seen.cmp(&a.last_seen))
        });
    }

    /// Получить лучший адрес для подключения
    pub fn best_address(&self, peer_id: &str) -> Option<String> {
        let inner = self.inner.lock().unwrap();
        let list = inner.peers.get(peer_id)?;
        let now = Instant::now();

        // Первый не-устаревший адрес
        list.iter()
            .find(|a| now.duration_since(a.last_seen) < inner.ttl)
            .map(|a| a.addr.clone())
    }

    /// Получить ВСЕ адреса (для попытки по очереди)
    pub fn all_addresses(&self, peer_id: &str) -> Vec<String> {
        let inner = self.inner.lock().unwrap();
        let list = match inner.peers.get(peer_id) {
            Some(l) => l,
            None => return vec![],
        };
        let now = Instant::now();
        list.iter()
            .filter(|a| now.duration_since(a.last_seen) < inner.ttl)
            .map(|a| a.addr.clone())
            .collect()
    }

    /// Отметить успешное соединение
    pub fn mark_success(&self, peer_id: &str, addr: &str) {
        let mut inner = self.inner.lock().unwrap();
        if let Some(list) = inner.peers.get_mut(peer_id) {
            if let Some(a) = list.iter_mut().find(|a| a.addr == addr) {
                a.success_count += 1;
                a.fail_count = 0;
                a.last_seen = Instant::now();
            }
        }
    }

    /// Отметить неудачное соединение
    pub fn mark_fail(&self, peer_id: &str, addr: &str) {
        let mut inner = self.inner.lock().unwrap();
        if let Some(list) = inner.peers.get_mut(peer_id) {
            if let Some(a) = list.iter_mut().find(|a| a.addr == addr) {
                a.fail_count += 1;
            }
        }
    }

    /// Удалить устаревшие адреса
    pub fn cleanup(&self) {
        let mut inner = self.inner.lock().unwrap();
        let now = Instant::now();
        let ttl = inner.ttl;
        for list in inner.peers.values_mut() {
            list.retain(|a| now.duration_since(a.last_seen) < ttl);
        }
        inner.peers.retain(|_, list| !list.is_empty());
    }

    /// Количество peer в кэше
    pub fn peer_count(&self) -> usize {
        let inner = self.inner.lock().unwrap();
        inner.peers.len()
    }
}