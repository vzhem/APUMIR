//! # Presence Protocol — Gossip оповещения о присутствии
//!
//! Узнаём кто в сети и уведомляем других о своём появлении.
//!
//! ## Принцип работы:
//!
//! ```text
//! Узел A появляется в сети:
//!
//! 1. A → Seed Node: "Я онлайн, вот мой NodeID"
//! 2. Seed Node → подписчикам A: "A появился"
//! 3. Seed Node → A: "Вот кто сейчас онлайн: [B, C, D]"
//! 4. A → B (напрямую): "Привет, я онлайн"
//! 5. B → своим соседям (gossip, TTL=3): "A онлайн"
//! ```
//!
//! ## Защита от штормов:
//!
//! - **TTL** — сколько хопов пересылать (обычно 3)
//! - **Message ID cache** — не пересылаем то что уже видели
//! - **Signature** — каждый анонс подписан Ed25519 (защита от подделки)
//!
//! ## Что делает этот модуль в MVP:
//!
//! - Хранит список известных онлайн-узлов
//! - Управляет подписками (кто хочет знать о ком)
//! - Обрабатывает входящие `PresenceAnnounce`
//! - Определяет какие gossip-сообщения нужно пересылать
//!
//! Реальная отправка по сети — в `router.rs` + `relay.rs` (следующие этапы).

use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

use tokio::sync::Mutex;

use crate::protocol::messages::{NodeInfo, NodeTier, PresenceAnnounce};

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// Узел считается онлайн если мы видели его presence не позднее.
pub const ONLINE_TIMEOUT: Duration = Duration::from_secs(300); // 5 минут

/// TTL по умолчанию для gossip-сообщений.
pub const DEFAULT_GOSSIP_TTL: u8 = 3;

/// Максимальное число известных онлайн-узлов (защита от переполнения).
pub const MAX_KNOWN_NODES: usize = 10_000;

// ═══════════════════════════════════════════════════════════════════
// РЕШЕНИЕ ПО GOSSIP
// ═══════════════════════════════════════════════════════════════════

/// Что делать с полученным PresenceAnnounce.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GossipDecision {
    /// Новое сообщение — обработать и переслать дальше (с уменьшенным TTL).
    ProcessAndForward { new_ttl: u8 },

    /// Новое сообщение но TTL исчерпан — только обработать локально.
    ProcessLocally,

    /// Дубликат или устаревшее — игнорировать.
    Ignore,
}

// ═══════════════════════════════════════════════════════════════════
// KNOWN NODE ENTRY
// ═══════════════════════════════════════════════════════════════════

/// Запись об известном узле.
#[derive(Debug, Clone)]
pub struct KnownNode {
    /// Полная информация об узле.
    pub info: NodeInfo,

    /// Когда мы последний раз получали презентацию этого узла.
    pub last_seen: Instant,
}

impl KnownNode {
    /// Онлайн ли узел (последняя презентация < ONLINE_TIMEOUT назад).
    pub fn is_online(&self) -> bool {
        self.last_seen.elapsed() < ONLINE_TIMEOUT
    }
}

// ═══════════════════════════════════════════════════════════════════
// PRESENCE MANAGER
// ═══════════════════════════════════════════════════════════════════

/// Основной компонент управления Presence Protocol.
pub struct PresenceManager {
    /// Наш собственный NodeID.
    our_id: [u8; 32],

    /// Известные узлы (NodeID → KnownNode).
    known_nodes: Mutex<HashMap<[u8; 32], KnownNode>>,

    /// Подписки: target_node_id → множество подписчиков.
    /// "Кто хочет знать о появлении target"
    subscriptions: Mutex<HashMap<[u8; 32], HashSet<[u8; 32]>>>,
}

impl PresenceManager {
    /// Создать новый менеджер.
    pub fn new(our_id: [u8; 32]) -> Self {
        PresenceManager {
            our_id,
            known_nodes: Mutex::new(HashMap::new()),
            subscriptions: Mutex::new(HashMap::new()),
        }
    }

    /// Наш NodeID.
    pub fn our_id(&self) -> [u8; 32] {
        self.our_id
    }

    // ─── Управление известными узлами ─────────────────────────────

    /// Добавить/обновить узел в списке известных.
    /// Возвращает `true` если узел ранее не был известен (новый).
    pub async fn record_presence(&self, info: NodeInfo) -> bool {
        // Игнорируем самих себя
        if info.node_id == self.our_id {
            return false;
        }

        let mut nodes = self.known_nodes.lock().await;

        // Ограничение размера — удаляем старейших если слишком много
        if nodes.len() >= MAX_KNOWN_NODES && !nodes.contains_key(&info.node_id) {
            self.evict_oldest_locked(&mut nodes);
        }

        let is_new = !nodes.contains_key(&info.node_id);
        nodes.insert(
            info.node_id,
            KnownNode {
                info,
                last_seen: Instant::now(),
            },
        );

        is_new
    }

    /// Проверить онлайн ли узел.
    pub async fn is_online(&self, node_id: &[u8; 32]) -> bool {
        let nodes = self.known_nodes.lock().await;
        nodes.get(node_id).map(|n| n.is_online()).unwrap_or(false)
    }

    /// Получить информацию об узле если известна.
    pub async fn get_node(&self, node_id: &[u8; 32]) -> Option<KnownNode> {
        self.known_nodes.lock().await.get(node_id).cloned()
    }

    /// Список всех онлайн-узлов.
    pub async fn online_nodes(&self) -> Vec<NodeInfo> {
        let nodes = self.known_nodes.lock().await;
        nodes
            .values()
            .filter(|n| n.is_online())
            .map(|n| n.info.clone())
            .collect()
    }

    /// Список узлов определённого Tier.
    pub async fn nodes_by_tier(&self, tier: NodeTier) -> Vec<NodeInfo> {
        let nodes = self.known_nodes.lock().await;
        nodes
            .values()
            .filter(|n| n.is_online() && n.info.tier == tier)
            .map(|n| n.info.clone())
            .collect()
    }

    /// Число известных узлов (все, не только онлайн).
    pub async fn known_count(&self) -> usize {
        self.known_nodes.lock().await.len()
    }

    /// Число онлайн-узлов.
    pub async fn online_count(&self) -> usize {
        let nodes = self.known_nodes.lock().await;
        nodes.values().filter(|n| n.is_online()).count()
    }

    /// Удалить устаревшие записи (offline давно).
    /// Возвращает число удалённых.
    pub async fn cleanup_offline(&self, offline_ttl: Duration) -> usize {
        let mut nodes = self.known_nodes.lock().await;
        let before = nodes.len();
        nodes.retain(|_id, node| node.last_seen.elapsed() < offline_ttl);
        before - nodes.len()
    }

    /// Вспомогательное: удалить самый старый узел (когда достигнут лимит).
    fn evict_oldest_locked(&self, nodes: &mut HashMap<[u8; 32], KnownNode>) {
        if let Some((oldest_id, _)) = nodes
            .iter()
            .min_by_key(|(_, n)| n.last_seen)
            .map(|(id, n)| (*id, n.clone()))
        {
            nodes.remove(&oldest_id);
        }
    }

    // ─── Подписки на присутствие ──────────────────────────────────

    /// Подписать узел на уведомления о появлении target.
    pub async fn subscribe(&self, subscriber: [u8; 32], target: [u8; 32]) {
        let mut subs = self.subscriptions.lock().await;
        subs.entry(target)
            .or_insert_with(HashSet::new)
            .insert(subscriber);
    }

    /// Отписать.
    pub async fn unsubscribe(&self, subscriber: &[u8; 32], target: &[u8; 32]) -> bool {
        let mut subs = self.subscriptions.lock().await;
        if let Some(subscribers) = subs.get_mut(target) {
            let removed = subscribers.remove(subscriber);
            // Удаляем пустой set чтобы не копить мусор
            if subscribers.is_empty() {
                subs.remove(target);
            }
            removed
        } else {
            false
        }
    }

    /// Получить подписчиков на конкретный target.
    pub async fn subscribers_of(&self, target: &[u8; 32]) -> Vec<[u8; 32]> {
        let subs = self.subscriptions.lock().await;
        subs.get(target)
            .map(|set| set.iter().copied().collect())
            .unwrap_or_default()
    }

    // ─── Gossip обработка ─────────────────────────────────────────

    /// Обработать входящий PresenceAnnounce.
    ///
    /// # Аргументы
    /// - `announce` — полученное сообщение
    /// - `already_seen` — видели ли этот message_id ранее (проверить через Router)
    ///
    /// # Возвращает
    /// - `GossipDecision` — что делать с сообщением
    ///
    /// **ВАЖНО**: этот метод НЕ проверяет подпись Ed25519 — это делается
    /// на уровне выше (там где есть доступ к NodeIdentity). Мы предполагаем
    /// что подпись уже проверена или доверяем отправителю.
    pub async fn handle_announce(
        &self,
        announce: &PresenceAnnounce,
        already_seen: bool,
    ) -> GossipDecision {
        // Игнорируем анонсы от нас самих
        if announce.node.node_id == self.our_id {
            return GossipDecision::Ignore;
        }

        // Дубликат — игнорируем
        if already_seen {
            return GossipDecision::Ignore;
        }

        // Записываем presence
        self.record_presence(announce.node.clone()).await;

        // Решаем что делать дальше на основании TTL
        if announce.ttl > 1 {
            GossipDecision::ProcessAndForward {
                new_ttl: announce.ttl - 1,
            }
        } else {
            // TTL == 1 или 0 — только локально
            GossipDecision::ProcessLocally
        }
    }

    /// Создать announce о самих себе (для рассылки в сеть).
    ///
    /// # Аргументы
    /// - `my_node_info` — наша полная NodeInfo
    /// - `signature` — Ed25519 подпись над (node + timestamp)
    /// - `message_id` — уникальный ID (обычно UUID v4)
    pub fn create_self_announce(
        &self,
        my_node_info: NodeInfo,
        signature: Vec<u8>,
        message_id: [u8; 16],
    ) -> PresenceAnnounce {
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        PresenceAnnounce {
            node: my_node_info,
            timestamp,
            signature,
            message_id,
            ttl: DEFAULT_GOSSIP_TTL,
        }
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

    fn make_node_info(byte: u8, tier: NodeTier) -> NodeInfo {
        NodeInfo {
            node_id: make_id(byte),
            ed25519_pubkey: vec![byte; 32],
            address: format!("192.168.1.{}:7777", byte),
            tier,
            rating: 50.0,
        }
    }

    fn make_announce(byte: u8, ttl: u8) -> PresenceAnnounce {
        PresenceAnnounce {
            node: make_node_info(byte, NodeTier::Leaf),
            timestamp: 1_700_000_000_000,
            signature: vec![byte; 64],
            message_id: make_msg_id(byte),
            ttl,
        }
    }

    // ── Базовые ────────────────────────────────────────────────────

    #[tokio::test]
    async fn test_new_manager_empty() {
        let pm = PresenceManager::new(make_id(0x00));
        assert_eq!(pm.known_count().await, 0);
        assert_eq!(pm.online_count().await, 0);
        println!("✅ Новый PresenceManager пустой");
    }

    #[tokio::test]
    async fn test_record_new_presence() {
        let pm = PresenceManager::new(make_id(0x00));
        let node = make_node_info(0x42, NodeTier::Leaf);

        let is_new = pm.record_presence(node.clone()).await;
        assert!(is_new);
        assert_eq!(pm.known_count().await, 1);
        assert!(pm.is_online(&node.node_id).await);
        println!("✅ Новая презентация записана");
    }

    #[tokio::test]
    async fn test_record_existing_presence_returns_false() {
        let pm = PresenceManager::new(make_id(0x00));
        let node = make_node_info(0x42, NodeTier::Leaf);

        assert!(pm.record_presence(node.clone()).await);
        // Повторно — уже известен
        assert!(!pm.record_presence(node).await);
        assert_eq!(pm.known_count().await, 1);
        println!("✅ Повторная презентация → не новый");
    }

    #[tokio::test]
    async fn test_ignore_self_presence() {
        let our_id = make_id(0x01);
        let pm = PresenceManager::new(our_id);

        let self_info = NodeInfo {
            node_id: our_id,
            ed25519_pubkey: vec![0; 32],
            address: "127.0.0.1:7777".to_string(),
            tier: NodeTier::Leaf,
            rating: 0.0,
        };

        assert!(!pm.record_presence(self_info).await);
        assert_eq!(pm.known_count().await, 0);
        println!("✅ Свою собственную презентацию игнорируем");
    }

    // ── Онлайн статус ──────────────────────────────────────────────

    #[tokio::test]
    async fn test_online_nodes_list() {
        let pm = PresenceManager::new(make_id(0x00));

        for i in 1..=5u8 {
            pm.record_presence(make_node_info(i, NodeTier::Leaf)).await;
        }

        assert_eq!(pm.online_count().await, 5);
        let online = pm.online_nodes().await;
        assert_eq!(online.len(), 5);
        println!("✅ 5 узлов онлайн");
    }

    #[tokio::test]
    async fn test_unknown_node_is_not_online() {
        let pm = PresenceManager::new(make_id(0x00));
        assert!(!pm.is_online(&make_id(0x42)).await);
        println!("✅ Неизвестный узел не онлайн");
    }

    #[tokio::test]
    async fn test_nodes_by_tier() {
        let pm = PresenceManager::new(make_id(0x00));

        pm.record_presence(make_node_info(1, NodeTier::Super)).await;
        pm.record_presence(make_node_info(2, NodeTier::Super)).await;
        pm.record_presence(make_node_info(3, NodeTier::Relay)).await;
        pm.record_presence(make_node_info(4, NodeTier::Leaf)).await;

        let supers = pm.nodes_by_tier(NodeTier::Super).await;
        assert_eq!(supers.len(), 2);

        let relays = pm.nodes_by_tier(NodeTier::Relay).await;
        assert_eq!(relays.len(), 1);

        let leaves = pm.nodes_by_tier(NodeTier::Leaf).await;
        assert_eq!(leaves.len(), 1);

        println!("✅ Фильтрация по Tier работает");
    }

    // ── Подписки ───────────────────────────────────────────────────

    #[tokio::test]
    async fn test_subscribe_and_unsubscribe() {
        let pm = PresenceManager::new(make_id(0x00));
        let subscriber = make_id(0x01);
        let target = make_id(0x42);

        pm.subscribe(subscriber, target).await;
        let subs = pm.subscribers_of(&target).await;
        assert_eq!(subs.len(), 1);
        assert_eq!(subs[0], subscriber);

        assert!(pm.unsubscribe(&subscriber, &target).await);
        let subs = pm.subscribers_of(&target).await;
        assert_eq!(subs.len(), 0);
        println!("✅ Подписка/отписка работает");
    }

    #[tokio::test]
    async fn test_multiple_subscribers() {
        let pm = PresenceManager::new(make_id(0x00));
        let target = make_id(0xFF);

        for i in 1..=5u8 {
            pm.subscribe(make_id(i), target).await;
        }

        let subs = pm.subscribers_of(&target).await;
        assert_eq!(subs.len(), 5);
        println!("✅ Несколько подписчиков на один target");
    }

    #[tokio::test]
    async fn test_unsubscribe_nonexistent() {
        let pm = PresenceManager::new(make_id(0x00));
        assert!(!pm.unsubscribe(&make_id(0x01), &make_id(0x42)).await);
        println!("✅ Отписка несуществующего → false");
    }

    // ── Gossip decision ────────────────────────────────────────────

    #[tokio::test]
    async fn test_handle_announce_new_message() {
        let pm = PresenceManager::new(make_id(0x00));
        let announce = make_announce(0x42, 3);

        let decision = pm.handle_announce(&announce, false).await;
        assert_eq!(decision, GossipDecision::ProcessAndForward { new_ttl: 2 });

        // Узел должен быть записан
        assert!(pm.is_online(&make_id(0x42)).await);
        println!("✅ Новый announce → ProcessAndForward с уменьшенным TTL");
    }

    #[tokio::test]
    async fn test_handle_announce_already_seen() {
        let pm = PresenceManager::new(make_id(0x00));
        let announce = make_announce(0x42, 3);

        let decision = pm.handle_announce(&announce, true).await;
        assert_eq!(decision, GossipDecision::Ignore);

        // Не должно быть записано
        assert!(!pm.is_online(&make_id(0x42)).await);
        println!("✅ Дубликат announce → Ignore");
    }

    #[tokio::test]
    async fn test_handle_announce_ttl_expired() {
        let pm = PresenceManager::new(make_id(0x00));
        let announce = make_announce(0x42, 1); // TTL=1 → только локально

        let decision = pm.handle_announce(&announce, false).await;
        assert_eq!(decision, GossipDecision::ProcessLocally);
        assert!(pm.is_online(&make_id(0x42)).await);
        println!("✅ TTL=1 → ProcessLocally");
    }

    #[tokio::test]
    async fn test_handle_announce_zero_ttl() {
        let pm = PresenceManager::new(make_id(0x00));
        let announce = make_announce(0x42, 0);

        let decision = pm.handle_announce(&announce, false).await;
        assert_eq!(decision, GossipDecision::ProcessLocally);
        println!("✅ TTL=0 → ProcessLocally (не форвардим)");
    }

    #[tokio::test]
    async fn test_handle_self_announce_ignored() {
        let our_id = make_id(0x01);
        let pm = PresenceManager::new(our_id);
        let mut announce = make_announce(0x01, 3); // Наш собственный ID
        announce.node.node_id = our_id;

        let decision = pm.handle_announce(&announce, false).await;
        assert_eq!(decision, GossipDecision::Ignore);
        println!("✅ Собственный announce игнорируется");
    }

    // ── Create self announce ───────────────────────────────────────

    #[tokio::test]
    async fn test_create_self_announce() {
        let pm = PresenceManager::new(make_id(0x01));
        let info = make_node_info(0x01, NodeTier::Leaf);
        let sig = vec![0xAA; 64];
        let msg_id = make_msg_id(0xBB);

        let announce = pm.create_self_announce(info.clone(), sig.clone(), msg_id);

        assert_eq!(announce.node.node_id, info.node_id);
        assert_eq!(announce.signature, sig);
        assert_eq!(announce.message_id, msg_id);
        assert_eq!(announce.ttl, DEFAULT_GOSSIP_TTL);
        assert!(announce.timestamp > 0);
        println!("✅ Собственный announce создаётся корректно");
    }

    // ── Cleanup ────────────────────────────────────────────────────

    #[tokio::test]
    async fn test_cleanup_offline() {
        let pm = PresenceManager::new(make_id(0x00));

        for i in 1..=3u8 {
            pm.record_presence(make_node_info(i, NodeTier::Leaf)).await;
        }
        assert_eq!(pm.known_count().await, 3);

        // Cleanup с очень маленьким TTL → все удалятся (кроме только что добавленных)
        tokio::time::sleep(Duration::from_millis(10)).await;
        let removed = pm.cleanup_offline(Duration::from_nanos(1)).await;
        assert_eq!(removed, 3);
        assert_eq!(pm.known_count().await, 0);
        println!("✅ Cleanup удаляет offline узлы");
    }

    #[tokio::test]
    async fn test_cleanup_keeps_fresh() {
        let pm = PresenceManager::new(make_id(0x00));

        for i in 1..=3u8 {
            pm.record_presence(make_node_info(i, NodeTier::Leaf)).await;
        }

        // Большой TTL — не удаляем ничего
        let removed = pm.cleanup_offline(Duration::from_secs(3600)).await;
        assert_eq!(removed, 0);
        assert_eq!(pm.known_count().await, 3);
        println!("✅ Cleanup сохраняет свежие узлы");
    }
}
