use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Отслеживает "живость" каждого peer.
/// Если peer не отвечает HEARTBEAT_INTERVAL * MAX_MISSED → offline.
pub struct HeartbeatMonitor {
    inner: Arc<Mutex<Inner>>,
}

struct Inner {
    /// peer_id -> последний раз когда видели (ping или сообщение)
    last_seen: HashMap<String, Instant>,
    /// peer_id -> количество пропущенных ping
    missed: HashMap<String, u32>,
    /// интервал проверки
    interval: Duration,
    /// максимум пропусков до offline
    max_missed: u32,
}

impl HeartbeatMonitor {
    pub fn new(interval_secs: u64, max_missed: u32) -> Self {
        Self {
            inner: Arc::new(Mutex::new(Inner {
                last_seen: HashMap::new(),
                missed: HashMap::new(),
                interval: Duration::from_secs(interval_secs),
                max_missed,
            })),
        }
    }

    /// Вызывается при получении ЛЮБОГО пакета от peer (ping, message, presence)
    pub fn peer_alive(&self, peer_id: &str) {
        let mut inner = self.inner.lock().unwrap();
        inner.last_seen.insert(peer_id.to_string(), Instant::now());
        inner.missed.insert(peer_id.to_string(), 0);
    }

    /// Вызывается при получении ping от peer
    pub fn peer_ping(&self, peer_id: &str) {
        self.peer_alive(peer_id);
    }

    /// Проверка: какие peer "мёртвые" (пропустили max_missed ping)
    /// Возвращает список peer_id которые нужно переподключить
    pub fn check_dead(&self) -> Vec<String> {
        let mut inner = self.inner.lock().unwrap();
        let now = Instant::now();
        let mut dead = Vec::new();

        let peers: Vec<String> = inner.last_seen.keys().cloned().collect();
        for peer in peers {
            if let Some(last) = inner.last_seen.get(&peer) {
                let elapsed = now.duration_since(*last);
                if elapsed > inner.interval {
                    let m = inner.missed.entry(peer.clone()).or_insert(0);
                    *m += 1;
                    if *m >= inner.max_missed {
                        dead.push(peer.clone());
                    }
                }
            }
        }
        dead
    }

    /// Удалить peer из мониторинга
    pub fn remove_peer(&self, peer_id: &str) {
        let mut inner = self.inner.lock().unwrap();
        inner.last_seen.remove(peer_id);
        inner.missed.remove(peer_id);
    }

    /// Количество активных peer
    pub fn active_count(&self) -> usize {
        let inner = self.inner.lock().unwrap();
        inner.last_seen.len()
    }

    /// Интервал (для таймера)
    pub fn interval(&self) -> Duration {
        let inner = self.inner.lock().unwrap();
        inner.interval
    }
}