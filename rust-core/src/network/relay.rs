//! # Relay — Ретрансляция через промежуточные узлы
//!
//! Когда прямое соединение с получателем невозможно (NAT, отсутствие маршрута),
//! отправляем сообщение через промежуточный узел-ретранслятор.
//!
//! ## Ключевой принцип: слепая ретрансляция
//!
//! Ретранслирующий узел видит:
//! - Кто отправитель (from_node_id)
//! - Кто получатель (to_node_id)
//! - Зашифрованные байты payload
//!
//! Ретранслятор **НЕ МОЖЕТ** прочитать содержимое — оно зашифровано
//! Double Ratchet между отправителем и получателем.
//!
//! ## Выбор ретранслятора:
//!
//! 1. Ищем Tier 1 узлы (Super Nodes) — с рейтингом > 90
//! 2. Если нет — Tier 2 (Relay Nodes)
//! 3. Из подходящих выбираем с наивысшим рейтингом
//!
//! ## Что делает этот модуль:
//!
//! - Выбирает подходящих ретрансляторов по Tier + рейтингу
//! - Проверяет TTL при обработке RelayReq
//! - Отслеживает статистику ретрансляций
//!
//! ## Что НЕ делает:
//!
//! - Фактическая отправка через сеть — на уровне выше (`fallback_chain.rs`)
//! - Само шифрование — уже сделано в `crypto::session`

use std::collections::HashMap;
use std::time::Instant;

use tokio::sync::Mutex;

use crate::protocol::messages::{NodeInfo, NodeTier};

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// Минимальный рейтинг Tier 1 узла.
pub const TIER1_MIN_RATING: f32 = 90.0;

/// Минимальный рейтинг Tier 2 узла.
pub const TIER2_MIN_RATING: f32 = 50.0;

/// Максимальный TTL для RelayReq (защита от циклов).
pub const MAX_RELAY_TTL: u8 = 10;

// ═══════════════════════════════════════════════════════════════════
// ОШИБКИ
// ═══════════════════════════════════════════════════════════════════

#[derive(Debug, thiserror::Error)]
pub enum RelayError {
    #[error("Нет подходящих ретрансляторов")]
    NoRelayAvailable,

    #[error("TTL истёк — пакет должен быть отброшен")]
    TtlExpired,

    #[error("Обнаружен цикл — пакет уже проходил через нас")]
    LoopDetected,

    #[error("Мы не должны ретранслировать этот пакет")]
    NotForUs,
}

pub type RelayResult<T> = Result<T, RelayError>;

// ═══════════════════════════════════════════════════════════════════
// РЕШЕНИЕ О РЕТРАНСЛЯЦИИ
// ═══════════════════════════════════════════════════════════════════

/// Что делать с полученным RelayReq.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RelayAction {
    /// Пакет для нас — обработать локально.
    DeliverLocally,

    /// Переслать дальше с уменьшенным TTL.
    ForwardTo { to_node_id: [u8; 32], new_ttl: u8 },

    /// Отбросить (TTL истёк или цикл).
    Drop { reason: DropReason },
}

/// Причина отбрасывания пакета.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DropReason {
    TtlExpired,
    LoopDetected,
    UnknownRoute,
}

// ═══════════════════════════════════════════════════════════════════
// СТАТИСТИКА
// ═══════════════════════════════════════════════════════════════════

/// Статистика ретрансляций для одного соседа.
#[derive(Debug, Clone, Default)]
pub struct RelayStats {
    /// Сколько пакетов мы ретранслировали через этот узел.
    pub packets_forwarded: u64,

    /// Сколько пакетов ретранслировано ЧЕРЕЗ нас (мы были посредником).
    pub packets_relayed_for: u64,

    /// Общий объём (байт).
    pub bytes_transferred: u64,

    /// Последняя ретрансляция.
    pub last_activity: Option<Instant>,
}

// ═══════════════════════════════════════════════════════════════════
// RELAY MANAGER
// ═══════════════════════════════════════════════════════════════════

/// Менеджер ретрансляций.
pub struct RelayManager {
    /// Наш собственный NodeID.
    our_id: [u8; 32],

    /// Статистика по узлам с которыми мы взаимодействовали.
    stats: Mutex<HashMap<[u8; 32], RelayStats>>,
}

impl RelayManager {
    pub fn new(our_id: [u8; 32]) -> Self {
        RelayManager {
            our_id,
            stats: Mutex::new(HashMap::new()),
        }
    }

    pub fn our_id(&self) -> [u8; 32] {
        self.our_id
    }

    // ─── Выбор ретранслятора ──────────────────────────────────────

    /// Выбрать наилучшего ретранслятора из списка кандидатов.
    ///
    /// Приоритет:
    /// 1. Tier 1 узлы с рейтингом > TIER1_MIN_RATING
    /// 2. Tier 2 узлы с рейтингом > TIER2_MIN_RATING
    /// 3. Ошибка — нет подходящих
    ///
    /// Внутри группы — выбираем с наивысшим рейтингом.
    pub fn select_relay(candidates: &[NodeInfo]) -> RelayResult<NodeInfo> {
        // Сначала пробуем Tier 1
        let best_tier1 = candidates
            .iter()
            .filter(|n| n.tier == NodeTier::Super && n.rating >= TIER1_MIN_RATING)
            .max_by(|a, b| {
                a.rating
                    .partial_cmp(&b.rating)
                    .unwrap_or(std::cmp::Ordering::Equal)
            });

        if let Some(node) = best_tier1 {
            return Ok(node.clone());
        }

        // Затем Tier 2
        let best_tier2 = candidates
            .iter()
            .filter(|n| n.tier == NodeTier::Relay && n.rating >= TIER2_MIN_RATING)
            .max_by(|a, b| {
                a.rating
                    .partial_cmp(&b.rating)
                    .unwrap_or(std::cmp::Ordering::Equal)
            });

        if let Some(node) = best_tier2 {
            return Ok(node.clone());
        }

        Err(RelayError::NoRelayAvailable)
    }

    /// Выбрать N лучших ретрансляторов (для redundancy).
    pub fn select_top_n_relays(candidates: &[NodeInfo], n: usize) -> Vec<NodeInfo> {
        let mut suitable: Vec<&NodeInfo> = candidates
            .iter()
            .filter(|n| {
                (n.tier == NodeTier::Super && n.rating >= TIER1_MIN_RATING)
                    || (n.tier == NodeTier::Relay && n.rating >= TIER2_MIN_RATING)
            })
            .collect();

        // Сортируем: сначала Tier 1, затем Tier 2; внутри — по рейтингу
        suitable.sort_by(|a, b| {
            let tier_order = |t: &NodeTier| match t {
                NodeTier::Super => 0,
                NodeTier::Relay => 1,
                NodeTier::Leaf => 2,
            };

            tier_order(&a.tier).cmp(&tier_order(&b.tier)).then_with(|| {
                b.rating
                    .partial_cmp(&a.rating)
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
        });

        suitable.into_iter().take(n).cloned().collect()
    }

    // ─── Обработка входящих RelayReq ──────────────────────────────

    /// Принять решение о действии при получении RelayReq.
    ///
    /// # Аргументы
    /// - `from_node_id` — отправитель (нужен для обновления статистики)
    /// - `to_node_id` — конечный получатель
    /// - `ttl` — текущий TTL пакета
    /// - `payload_size` — размер payload (для статистики)
    pub async fn handle_relay_request(
        &self,
        from_node_id: [u8; 32],
        to_node_id: [u8; 32],
        ttl: u8,
        payload_size: usize,
    ) -> RelayAction {
        // Пакет для нас — обрабатываем локально
        if to_node_id == self.our_id {
            self.update_stats(from_node_id, payload_size, false).await;
            return RelayAction::DeliverLocally;
        }

        // TTL истёк
        if ttl == 0 {
            return RelayAction::Drop {
                reason: DropReason::TtlExpired,
            };
        }

        // Всё ОК — пересылаем дальше с уменьшенным TTL
        self.update_stats(from_node_id, payload_size, true).await;
        RelayAction::ForwardTo {
            to_node_id,
            new_ttl: ttl - 1,
        }
    }

    /// Проверить можем ли мы быть отправителем RelayReq.
    /// (Отправитель — мы, а не какой-то узел через нас)
    pub fn validate_outgoing_ttl(ttl: u8) -> RelayResult<()> {
        if ttl == 0 {
            return Err(RelayError::TtlExpired);
        }
        if ttl > MAX_RELAY_TTL {
            return Err(RelayError::TtlExpired); // Считаем что подделка
        }
        Ok(())
    }

    // ─── Статистика ────────────────────────────────────────────────

    /// Обновить статистику взаимодействия с узлом.
    async fn update_stats(&self, node_id: [u8; 32], bytes: usize, forwarded: bool) {
        let mut stats = self.stats.lock().await;
        let entry = stats.entry(node_id).or_default();

        entry.bytes_transferred += bytes as u64;
        entry.last_activity = Some(Instant::now());

        if forwarded {
            entry.packets_forwarded += 1;
        } else {
            entry.packets_relayed_for += 1;
        }
    }

    /// Получить статистику по узлу.
    pub async fn get_stats(&self, node_id: &[u8; 32]) -> Option<RelayStats> {
        self.stats.lock().await.get(node_id).cloned()
    }

    /// Общее число узлов в статистике.
    pub async fn stats_count(&self) -> usize {
        self.stats.lock().await.len()
    }

    /// Сбросить всю статистику.
    pub async fn reset_stats(&self) {
        self.stats.lock().await.clear();
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

    fn make_node(byte: u8, tier: NodeTier, rating: f32) -> NodeInfo {
        NodeInfo {
            node_id: make_id(byte),
            ed25519_pubkey: vec![byte; 32],
            address: format!("10.0.0.{}:7777", byte),
            tier,
            rating,
        }
    }

    // ── Выбор ретранслятора ────────────────────────────────────────

    #[test]
    fn test_select_relay_prefers_tier1() {
        let candidates = vec![
            make_node(1, NodeTier::Relay, 100.0), // Tier 2 но высокий рейтинг
            make_node(2, NodeTier::Super, 95.0),  // Tier 1
            make_node(3, NodeTier::Leaf, 100.0),  // Не подходит (Leaf)
        ];

        let selected = RelayManager::select_relay(&candidates).unwrap();
        assert_eq!(selected.node_id, make_id(2));
        assert_eq!(selected.tier, NodeTier::Super);
        println!("✅ Приоритет Tier 1 над Tier 2");
    }

    #[test]
    fn test_select_relay_falls_back_to_tier2() {
        let candidates = vec![
            make_node(1, NodeTier::Super, 50.0), // Tier 1 но низкий рейтинг
            make_node(2, NodeTier::Relay, 75.0), // Tier 2 подходящий
            make_node(3, NodeTier::Leaf, 100.0), // Не подходит
        ];

        let selected = RelayManager::select_relay(&candidates).unwrap();
        assert_eq!(selected.node_id, make_id(2));
        assert_eq!(selected.tier, NodeTier::Relay);
        println!("✅ Fallback на Tier 2 если нет Tier 1");
    }

    #[test]
    fn test_select_relay_picks_highest_rating() {
        let candidates = vec![
            make_node(1, NodeTier::Super, 91.0),
            make_node(2, NodeTier::Super, 99.0), // Лучший
            make_node(3, NodeTier::Super, 92.0),
        ];

        let selected = RelayManager::select_relay(&candidates).unwrap();
        assert_eq!(selected.node_id, make_id(2));
        println!("✅ Выбирается узел с наивысшим рейтингом");
    }

    #[test]
    fn test_select_relay_no_suitable() {
        let candidates = vec![
            make_node(1, NodeTier::Leaf, 100.0),
            make_node(2, NodeTier::Super, 50.0), // Ниже TIER1_MIN
            make_node(3, NodeTier::Relay, 40.0), // Ниже TIER2_MIN
        ];

        let result = RelayManager::select_relay(&candidates);
        assert!(matches!(result, Err(RelayError::NoRelayAvailable)));
        println!("✅ Нет подходящих → NoRelayAvailable");
    }

    #[test]
    fn test_select_relay_empty_list() {
        let result = RelayManager::select_relay(&[]);
        assert!(matches!(result, Err(RelayError::NoRelayAvailable)));
        println!("✅ Пустой список → NoRelayAvailable");
    }

    #[test]
    fn test_select_top_n_relays() {
        let candidates = vec![
            make_node(1, NodeTier::Super, 95.0),
            make_node(2, NodeTier::Super, 92.0),
            make_node(3, NodeTier::Relay, 80.0),
            make_node(4, NodeTier::Leaf, 100.0), // Не подходит
        ];

        let top = RelayManager::select_top_n_relays(&candidates, 5);
        assert_eq!(top.len(), 3); // 3 подходящих

        // Порядок: Tier1(95) → Tier1(92) → Tier2(80)
        assert_eq!(top[0].node_id, make_id(1));
        assert_eq!(top[1].node_id, make_id(2));
        assert_eq!(top[2].node_id, make_id(3));
        println!("✅ Top-N ретрансляторов в правильном порядке");
    }

    // ── Обработка RelayReq ─────────────────────────────────────────

    #[tokio::test]
    async fn test_deliver_to_us() {
        let our_id = make_id(0x01);
        let manager = RelayManager::new(our_id);
        let from = make_id(0x02);

        let action = manager.handle_relay_request(from, our_id, 5, 100).await;
        assert_eq!(action, RelayAction::DeliverLocally);
        println!("✅ Пакет для нас → DeliverLocally");
    }

    #[tokio::test]
    async fn test_forward_with_decremented_ttl() {
        let manager = RelayManager::new(make_id(0x01));
        let from = make_id(0x02);
        let to = make_id(0x03);

        let action = manager.handle_relay_request(from, to, 5, 100).await;
        assert_eq!(
            action,
            RelayAction::ForwardTo {
                to_node_id: to,
                new_ttl: 4,
            }
        );
        println!("✅ Пересылка с TTL 5 → 4");
    }

    #[tokio::test]
    async fn test_drop_ttl_expired() {
        let manager = RelayManager::new(make_id(0x01));
        let from = make_id(0x02);
        let to = make_id(0x03);

        let action = manager.handle_relay_request(from, to, 0, 100).await;
        assert_eq!(
            action,
            RelayAction::Drop {
                reason: DropReason::TtlExpired,
            }
        );
        println!("✅ TTL=0 → Drop");
    }

    #[test]
    fn test_validate_outgoing_ttl_ok() {
        assert!(RelayManager::validate_outgoing_ttl(5).is_ok());
        assert!(RelayManager::validate_outgoing_ttl(1).is_ok());
        assert!(RelayManager::validate_outgoing_ttl(MAX_RELAY_TTL).is_ok());
        println!("✅ Валидные TTL проходят");
    }

    #[test]
    fn test_validate_outgoing_ttl_zero() {
        let result = RelayManager::validate_outgoing_ttl(0);
        assert!(matches!(result, Err(RelayError::TtlExpired)));
        println!("✅ TTL=0 → ошибка");
    }

    #[test]
    fn test_validate_outgoing_ttl_too_large() {
        let result = RelayManager::validate_outgoing_ttl(255);
        assert!(matches!(result, Err(RelayError::TtlExpired)));
        println!("✅ TTL слишком большой → ошибка (защита от подделки)");
    }

    // ── Статистика ────────────────────────────────────────────────

    #[tokio::test]
    async fn test_stats_update_on_forward() {
        let manager = RelayManager::new(make_id(0x01));
        let from = make_id(0x02);
        let to = make_id(0x03);

        manager.handle_relay_request(from, to, 5, 1000).await;
        manager.handle_relay_request(from, to, 5, 500).await;

        let stats = manager.get_stats(&from).await.unwrap();
        assert_eq!(stats.packets_forwarded, 2);
        assert_eq!(stats.bytes_transferred, 1500);
        assert!(stats.last_activity.is_some());
        println!("✅ Статистика forward обновляется");
    }

    #[tokio::test]
    async fn test_stats_update_on_deliver_locally() {
        let our_id = make_id(0x01);
        let manager = RelayManager::new(our_id);
        let from = make_id(0x02);

        manager.handle_relay_request(from, our_id, 5, 800).await;

        let stats = manager.get_stats(&from).await.unwrap();
        assert_eq!(stats.packets_relayed_for, 1);
        assert_eq!(stats.packets_forwarded, 0);
        assert_eq!(stats.bytes_transferred, 800);
        println!("✅ Статистика relay_for обновляется");
    }

    #[tokio::test]
    async fn test_reset_stats() {
        let manager = RelayManager::new(make_id(0x01));
        let from = make_id(0x02);
        let to = make_id(0x03);

        manager.handle_relay_request(from, to, 5, 100).await;
        assert_eq!(manager.stats_count().await, 1);

        manager.reset_stats().await;
        assert_eq!(manager.stats_count().await, 0);
        println!("✅ reset_stats работает");
    }

    #[tokio::test]
    async fn test_stats_missing_returns_none() {
        let manager = RelayManager::new(make_id(0x01));
        let stats = manager.get_stats(&make_id(0x99)).await;
        assert!(stats.is_none());
        println!("✅ Статистика неизвестного узла → None");
    }
}
