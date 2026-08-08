//! # Router — Маршрутизация сообщений
//!
//! Определяет как доставить сообщение до получателя:
//! - Прямая доставка (у нас есть активное соединение с получателем)
//! - Через relay (если прямое соединение невозможно)
//! - Store-and-forward (получатель офлайн)
//!
//! ## Что делает этот модуль в MVP:
//!
//! - Хранит **routing table** (наши знания о том как достичь других узлов)
//! - Ведёт **кэш виденных сообщений** (защита от циклов при gossip/relay)
//! - Управляет **TTL** пакетов
//!
//! ## Что НЕ делает (будет в других файлах):
//!
//! - Фактическая отправка через сеть — `relay.rs`
//! - Полный Fallback Chain (6 шагов) — `fallback_chain.rs`
//! - Store-and-forward очередь — `message_queue.rs`

use std::collections::HashMap;
use std::time::{Duration, Instant};

use tokio::sync::Mutex;

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// TTL по умолчанию для сообщений (число хопов).
pub const DEFAULT_TTL: u8 = 10;

/// Время жизни записи в кэше виденных message_id.
pub const SEEN_CACHE_TTL: Duration = Duration::from_secs(60);

/// Максимальный размер кэша виденных сообщений.
pub const SEEN_CACHE_MAX_SIZE: usize = 10_000;

// ═══════════════════════════════════════════════════════════════════
// ТИПЫ РЕШЕНИЙ О МАРШРУТИЗАЦИИ
// ═══════════════════════════════════════════════════════════════════

/// Как доставить сообщение до узла.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RouteDecision {
    /// У нас есть прямой маршрут (адрес известен).
    Direct { address: String },

    /// Нужен relay через промежуточный узел.
    ViaRelay { relay_node_id: [u8; 32] },

    /// Получатель офлайн — положить в очередь.
    Queue,

    /// Не знаем как достичь получателя.
    Unknown,
}

// ═══════════════════════════════════════════════════════════════════
// ROUTING RECORD
// ═══════════════════════════════════════════════════════════════════

/// Запись о том как достичь конкретного узла.
#[derive(Debug, Clone)]
pub struct RoutingRecord {
    /// Прямой адрес если известен.
    pub direct_address: Option<String>,

    /// ID relay-узла через который можно достичь.
    pub relay_via: Option<[u8; 32]>,

    /// Когда обновлена запись (для сравнения свежести).
    pub last_updated: Instant,
}

impl RoutingRecord {
    pub fn direct(address: String) -> Self {
        RoutingRecord {
            direct_address: Some(address),
            relay_via: None,
            last_updated: Instant::now(),
        }
    }

    pub fn via_relay(relay_id: [u8; 32]) -> Self {
        RoutingRecord {
            direct_address: None,
            relay_via: Some(relay_id),
            last_updated: Instant::now(),
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// SEEN MESSAGE CACHE (защита от дублей/циклов)
// ═══════════════════════════════════════════════════════════════════

/// Кэш недавно виденных `message_id`.
///
/// При gossip/relay сообщение может прийти несколько раз (через разные пути).
/// Кэш позволяет обработать только один раз и не пересылать повторно.
struct SeenCache {
    entries: HashMap<[u8; 16], Instant>,
    max_size: usize,
    ttl: Duration,
}

impl SeenCache {
    fn new(max_size: usize, ttl: Duration) -> Self {
        SeenCache {
            entries: HashMap::new(),
            max_size,
            ttl,
        }
    }

    /// Записать сообщение как виденное.
    /// Возвращает `true` если это НОВОЕ сообщение (было записано),
    /// `false` если уже виделось (дубликат).
    fn mark_seen(&mut self, message_id: [u8; 16]) -> bool {
        // Очищаем устаревшие записи если кэш почти полный
        if self.entries.len() >= self.max_size {
            self.cleanup();
        }

        if let std::collections::hash_map::Entry::Vacant(e) = self.entries.entry(message_id) {
            e.insert(Instant::now());
            true // Новое
        } else {
            false // Уже виделось
        }
    }

    /// Проверить видели ли раньше (без записи).
    fn was_seen(&self, message_id: &[u8; 16]) -> bool {
        self.entries.contains_key(message_id)
    }

    /// Очистить устаревшие записи.
    fn cleanup(&mut self) -> usize {
        let now = Instant::now();
        let before = self.entries.len();
        self.entries
            .retain(|_id, timestamp| now.duration_since(*timestamp) < self.ttl);
        before - self.entries.len()
    }

    fn len(&self) -> usize {
        self.entries.len()
    }
}

// ═══════════════════════════════════════════════════════════════════
// ROUTER
// ═══════════════════════════════════════════════════════════════════

/// Роутер сообщений — центральный компонент маршрутизации.
pub struct Router {
    /// Наш собственный NodeID.
    our_id: [u8; 32],

    /// Известные маршруты до других узлов.
    routes: Mutex<HashMap<[u8; 32], RoutingRecord>>,

    /// Кэш виденных сообщений.
    seen_cache: Mutex<SeenCache>,
}

impl Router {
    /// Создать новый роутер.
    pub fn new(our_id: [u8; 32]) -> Self {
        Router {
            our_id,
            routes: Mutex::new(HashMap::new()),
            seen_cache: Mutex::new(SeenCache::new(SEEN_CACHE_MAX_SIZE, SEEN_CACHE_TTL)),
        }
    }

    /// Наш NodeID.
    pub fn our_id(&self) -> [u8; 32] {
        self.our_id
    }

    /// Обновить маршрут до узла (например после успешного соединения).
    pub async fn update_route(&self, target: [u8; 32], record: RoutingRecord) {
        self.routes.lock().await.insert(target, record);
    }

    /// Удалить маршрут (например после разрыва соединения).
    pub async fn remove_route(&self, target: &[u8; 32]) -> bool {
        self.routes.lock().await.remove(target).is_some()
    }

    /// Принять решение о маршрутизации сообщения к `target`.
    pub async fn decide_route(&self, target: &[u8; 32]) -> RouteDecision {
        // Проверяем что не отправляем сами себе
        if target == &self.our_id {
            return RouteDecision::Unknown;
        }

        let routes = self.routes.lock().await;
        match routes.get(target) {
            Some(record) => {
                if let Some(ref addr) = record.direct_address {
                    RouteDecision::Direct {
                        address: addr.clone(),
                    }
                } else if let Some(relay_id) = record.relay_via {
                    RouteDecision::ViaRelay {
                        relay_node_id: relay_id,
                    }
                } else {
                    RouteDecision::Unknown
                }
            }
            None => RouteDecision::Unknown,
        }
    }

    /// Проверить и пометить сообщение как виденное.
    /// Возвращает `true` если сообщение НОВОЕ (нужно обработать),
    /// `false` если уже виделось (дубликат — игнорировать).
    pub async fn check_and_mark_seen(&self, message_id: [u8; 16]) -> bool {
        self.seen_cache.lock().await.mark_seen(message_id)
    }

    /// Только проверить видели ли (без записи).
    pub async fn was_seen(&self, message_id: &[u8; 16]) -> bool {
        self.seen_cache.lock().await.was_seen(message_id)
    }

    /// Уменьшить TTL пакета на 1.
    /// Возвращает `Some(new_ttl)` если можно ещё пересылать,
    /// `None` если TTL исчерпан (сообщение должно быть отброшено).
    pub fn decrement_ttl(ttl: u8) -> Option<u8> {
        if ttl == 0 {
            None
        } else {
            Some(ttl - 1)
        }
    }

    /// Проверить не превышен ли TTL.
    /// Возвращает `true` если пакет ещё жив (TTL > 0).
    pub fn is_ttl_alive(ttl: u8) -> bool {
        ttl > 0
    }

    /// Общее число известных маршрутов.
    pub async fn route_count(&self) -> usize {
        self.routes.lock().await.len()
    }

    /// Число сообщений в кэше виденных.
    pub async fn seen_cache_size(&self) -> usize {
        self.seen_cache.lock().await.len()
    }

    /// Ручная очистка устаревших записей в кэше.
    pub async fn cleanup_seen_cache(&self) -> usize {
        self.seen_cache.lock().await.cleanup()
    }
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;

    fn make_id(byte: u8) -> [u8; 32] {
        [byte; 32]
    }

    fn make_msg_id(byte: u8) -> [u8; 16] {
        [byte; 16]
    }

    // ── Router базовые ────────────────────────────────────────────

    #[tokio::test]
    async fn test_router_new() {
        let router = Router::new(make_id(0x01));
        assert_eq!(router.our_id(), make_id(0x01));
        assert_eq!(router.route_count().await, 0);
        println!("✅ Router создан пустым");
    }

    #[tokio::test]
    async fn test_update_and_decide_direct_route() {
        let router = Router::new(make_id(0x00));
        let target = make_id(0x42);

        router
            .update_route(target, RoutingRecord::direct("1.2.3.4:7777".to_string()))
            .await;

        let decision = router.decide_route(&target).await;
        assert_eq!(
            decision,
            RouteDecision::Direct {
                address: "1.2.3.4:7777".to_string()
            }
        );
        println!("✅ Прямой маршрут корректно определяется");
    }

    #[tokio::test]
    async fn test_update_and_decide_relay_route() {
        let router = Router::new(make_id(0x00));
        let target = make_id(0x42);
        let relay = make_id(0x99);

        router
            .update_route(target, RoutingRecord::via_relay(relay))
            .await;

        let decision = router.decide_route(&target).await;
        assert_eq!(
            decision,
            RouteDecision::ViaRelay {
                relay_node_id: relay
            }
        );
        println!("✅ Relay-маршрут корректно определяется");
    }

    #[tokio::test]
    async fn test_decide_unknown_route() {
        let router = Router::new(make_id(0x00));
        let unknown_target = make_id(0x42);

        let decision = router.decide_route(&unknown_target).await;
        assert_eq!(decision, RouteDecision::Unknown);
        println!("✅ Неизвестный target → Unknown");
    }

    #[tokio::test]
    async fn test_decide_self_route_unknown() {
        let our_id = make_id(0x01);
        let router = Router::new(our_id);

        // Не отправляем сами себе
        let decision = router.decide_route(&our_id).await;
        assert_eq!(decision, RouteDecision::Unknown);
        println!("✅ Route до самого себя = Unknown");
    }

    #[tokio::test]
    async fn test_remove_route() {
        let router = Router::new(make_id(0x00));
        let target = make_id(0x42);

        router
            .update_route(target, RoutingRecord::direct("addr".to_string()))
            .await;
        assert_eq!(router.route_count().await, 1);

        assert!(router.remove_route(&target).await);
        assert_eq!(router.route_count().await, 0);

        // Повторное удаление возвращает false
        assert!(!router.remove_route(&target).await);
        println!("✅ remove_route работает");
    }

    // ── Seen Cache ─────────────────────────────────────────────────

    #[tokio::test]
    async fn test_seen_cache_new_message() {
        let router = Router::new(make_id(0x00));
        let msg_id = make_msg_id(0x01);

        // Первый раз — новое
        assert!(router.check_and_mark_seen(msg_id).await);
        // Второй раз — уже виделось
        assert!(!router.check_and_mark_seen(msg_id).await);
        println!("✅ Дубликат сообщения обнаружен");
    }

    #[tokio::test]
    async fn test_seen_cache_different_messages() {
        let router = Router::new(make_id(0x00));

        for i in 0..10u8 {
            let msg_id = make_msg_id(i);
            assert!(router.check_and_mark_seen(msg_id).await);
        }

        assert_eq!(router.seen_cache_size().await, 10);
        println!("✅ 10 разных сообщений в кэше");
    }

    #[tokio::test]
    async fn test_was_seen_without_marking() {
        let router = Router::new(make_id(0x00));
        let msg_id = make_msg_id(0x42);

        // Без mark — не должен быть виден
        assert!(!router.was_seen(&msg_id).await);

        // После mark — виден
        router.check_and_mark_seen(msg_id).await;
        assert!(router.was_seen(&msg_id).await);
        println!("✅ was_seen без mark корректен");
    }

    // ── TTL ────────────────────────────────────────────────────────

    #[test]
    fn test_ttl_alive() {
        assert!(Router::is_ttl_alive(10));
        assert!(Router::is_ttl_alive(1));
        assert!(!Router::is_ttl_alive(0));
        println!("✅ is_ttl_alive корректен");
    }

    #[test]
    fn test_decrement_ttl() {
        assert_eq!(Router::decrement_ttl(10), Some(9));
        assert_eq!(Router::decrement_ttl(1), Some(0));
        assert_eq!(Router::decrement_ttl(0), None);
        println!("✅ decrement_ttl корректен");
    }

    #[test]
    fn test_default_ttl_reasonable() {
        assert!(DEFAULT_TTL >= 3);
        assert!(DEFAULT_TTL <= 20);
        println!("✅ DEFAULT_TTL разумный: {}", DEFAULT_TTL);
    }

    // ── Комбинированные сценарии ────────────────────────────────────

    #[tokio::test]
    async fn test_multiple_routes() {
        let router = Router::new(make_id(0x00));

        // Добавляем 5 разных маршрутов
        for i in 1..=5u8 {
            let target = make_id(i);
            router
                .update_route(target, RoutingRecord::direct(format!("1.1.1.{}:7777", i)))
                .await;
        }

        assert_eq!(router.route_count().await, 5);

        // Проверяем каждый
        for i in 1..=5u8 {
            let target = make_id(i);
            let decision = router.decide_route(&target).await;
            match decision {
                RouteDecision::Direct { address } => {
                    assert!(address.contains(&i.to_string()));
                }
                _ => panic!("Expected Direct route"),
            }
        }
        println!("✅ 5 маршрутов работают независимо");
    }

    #[tokio::test]
    async fn test_route_update_overwrites() {
        let router = Router::new(make_id(0x00));
        let target = make_id(0x42);

        // Первый маршрут — прямой
        router
            .update_route(target, RoutingRecord::direct("addr-1".to_string()))
            .await;

        // Обновляем на relay
        let relay = make_id(0x99);
        router
            .update_route(target, RoutingRecord::via_relay(relay))
            .await;

        // Проверяем что применился второй
        let decision = router.decide_route(&target).await;
        assert!(matches!(
            decision,
            RouteDecision::ViaRelay { relay_node_id } if relay_node_id == relay
        ));
        println!("✅ update_route перезаписывает старый маршрут");
    }
}
