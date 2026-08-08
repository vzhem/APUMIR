//! Vector Clock — упорядочивание сообщений в распределённой сети
//! Реализует happens-before отношение между событиями

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// ============================================================
// Vector Clock
// ============================================================

/// Векторные часы: map<node_id, logical_time>
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VectorClock {
    clock: HashMap<String, u64>,
}

impl VectorClock {
    /// Создать пустые часы
    pub fn new() -> Self {
        Self {
            clock: HashMap::new(),
        }
    }

    /// Создать из существующей map
    pub fn from_map(map: HashMap<String, u64>) -> Self {
        Self { clock: map }
    }

    /// Получить время для узла
    pub fn get(&self, node_id: &str) -> u64 {
        *self.clock.get(node_id).unwrap_or(&0)
    }

    /// Инкрементировать время локального узла
    pub fn tick(&mut self, node_id: &str) -> u64 {
        let entry = self.clock.entry(node_id.to_string()).or_insert(0);
        *entry += 1;
        *entry
    }

    /// Слияние: берём максимум по каждому узлу
    pub fn merge(&mut self, other: &VectorClock) {
        for (node, &time) in &other.clock {
            let entry = self.clock.entry(node.clone()).or_insert(0);
            if time > *entry {
                *entry = time;
            }
        }
    }

    /// Слияние + инкремент локального узла
    pub fn merge_and_tick(&mut self, other: &VectorClock, node_id: &str) {
        self.merge(other);
        self.tick(node_id);
    }

    /// Все узлы в часах
    pub fn nodes(&self) -> Vec<&String> {
        self.clock.keys().collect()
    }

    /// Количество узлов
    pub fn len(&self) -> usize {
        self.clock.len()
    }

    pub fn is_empty(&self) -> bool {
        self.clock.is_empty()
    }

    /// Сериализация в JSON строку
    pub fn to_json(&self) -> String {
        serde_json::to_string(&self.clock).unwrap_or_else(|_| "{}".into())
    }

    /// Десериализация из JSON строки
    pub fn from_json(s: &str) -> Self {
        let clock: HashMap<String, u64> = serde_json::from_str(s).unwrap_or_default();
        Self { clock }
    }
}

impl Default for VectorClock {
    fn default() -> Self {
        Self::new()
    }
}

// ============================================================
// Causal Ordering
// ============================================================

/// Отношение между двумя векторными часами
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CausalOrder {
    /// A happened before B
    HappensBefore,
    /// B happened before A
    HappensAfter,
    /// A и B произошли одновременно (concurrent)
    Concurrent,
    /// A == B (идентичны)
    Equal,
}

impl CausalOrder {
    /// Определить отношение между двумя часами
    pub fn compare(a: &VectorClock, b: &VectorClock) -> Self {
        let mut a_less = false;
        let mut b_less = false;

        // Все узлы из обоих часов
        let all_nodes: std::collections::HashSet<&String> =
            a.clock.keys().chain(b.clock.keys()).collect();

        for node in all_nodes {
            let ta = a.get(node);
            let tb = b.get(node);

            if ta < tb {
                a_less = true;
            } else if ta > tb {
                b_less = true;
            }

            if a_less && b_less {
                return CausalOrder::Concurrent;
            }
        }

        match (a_less, b_less) {
            (true, false) => CausalOrder::HappensBefore,
            (false, true) => CausalOrder::HappensAfter,
            (false, false) => CausalOrder::Equal,
            (true, true) => CausalOrder::Concurrent,
        }
    }

    pub fn is_concurrent(&self) -> bool {
        matches!(self, CausalOrder::Concurrent)
    }

    pub fn is_before(&self) -> bool {
        matches!(self, CausalOrder::HappensBefore)
    }
}

// ============================================================
// Stamped Event
// ============================================================

/// Событие с векторными часами
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StampedEvent {
    /// ID события
    pub id: String,
    /// ID узла-отправителя
    pub node_id: String,
    /// Векторные часы в момент события
    pub clock: VectorClock,
    /// Данные события (JSON)
    pub payload: String,
    /// Wall clock (ms UTC) — для tie-breaking
    pub wall_time: i64,
}

impl StampedEvent {
    pub fn new(
        id: String,
        node_id: String,
        clock: VectorClock,
        payload: String,
        wall_time: i64,
    ) -> Self {
        Self {
            id,
            node_id,
            clock,
            payload,
            wall_time,
        }
    }

    /// Сравнить с другим событием
    pub fn causal_order(&self, other: &StampedEvent) -> CausalOrder {
        CausalOrder::compare(&self.clock, &other.clock)
    }
}

// ============================================================
// Event Log
// ============================================================

/// Лог событий с каузальным упорядочиванием
pub struct EventLog {
    events: Vec<StampedEvent>,
    local_node: String,
    local_clock: VectorClock,
}

impl EventLog {
    pub fn new(local_node: String) -> Self {
        Self {
            events: Vec::new(),
            local_node,
            local_clock: VectorClock::new(),
        }
    }

    /// Добавить локальное событие
    pub fn append_local(&mut self, id: String, payload: String, wall_time: i64) -> StampedEvent {
        self.local_clock.tick(&self.local_node);
        let event = StampedEvent::new(
            id,
            self.local_node.clone(),
            self.local_clock.clone(),
            payload,
            wall_time,
        );
        self.events.push(event.clone());
        event
    }

    /// Принять удалённое событие
    pub fn receive_remote(&mut self, event: StampedEvent) {
        self.local_clock
            .merge_and_tick(&event.clock, &self.local_node);
        // Вставляем в правильном каузальном порядке
        self.events.push(event);
        self.sort();
    }

    /// Сортировка: каузальный порядок + wall_time как tie-breaker
    fn sort(&mut self) {
        self.events
            .sort_by(|a, b| match CausalOrder::compare(&a.clock, &b.clock) {
                CausalOrder::HappensBefore => std::cmp::Ordering::Less,
                CausalOrder::HappensAfter => std::cmp::Ordering::Greater,
                _ => a
                    .wall_time
                    .cmp(&b.wall_time)
                    .then(a.node_id.cmp(&b.node_id)),
            });
    }

    pub fn events(&self) -> &[StampedEvent] {
        &self.events
    }

    pub fn len(&self) -> usize {
        self.events.len()
    }

    pub fn is_empty(&self) -> bool {
        self.events.is_empty()
    }

    pub fn local_clock(&self) -> &VectorClock {
        &self.local_clock
    }
}

// ============================================================
// TESTS
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn vc(pairs: &[(&str, u64)]) -> VectorClock {
        let mut map = HashMap::new();
        for (k, v) in pairs {
            map.insert(k.to_string(), *v);
        }
        VectorClock::from_map(map)
    }

    // --- VectorClock basic ---

    #[test]
    fn test_new_clock_empty() {
        let c = VectorClock::new();
        assert!(c.is_empty());
        assert_eq!(c.get("any"), 0);
    }

    #[test]
    fn test_tick_increments() {
        let mut c = VectorClock::new();
        assert_eq!(c.tick("A"), 1);
        assert_eq!(c.tick("A"), 2);
        assert_eq!(c.tick("B"), 1);
        assert_eq!(c.get("A"), 2);
        assert_eq!(c.get("B"), 1);
    }

    #[test]
    fn test_merge_takes_max() {
        let mut a = vc(&[("A", 3), ("B", 1)]);
        let b = vc(&[("A", 1), ("B", 5), ("C", 2)]);
        a.merge(&b);
        assert_eq!(a.get("A"), 3);
        assert_eq!(a.get("B"), 5);
        assert_eq!(a.get("C"), 2);
    }

    #[test]
    fn test_merge_and_tick() {
        let mut a = vc(&[("A", 1)]);
        let b = vc(&[("B", 3)]);
        a.merge_and_tick(&b, "A");
        assert_eq!(a.get("A"), 2);
        assert_eq!(a.get("B"), 3);
    }

    #[test]
    fn test_len() {
        let c = vc(&[("A", 1), ("B", 2)]);
        assert_eq!(c.len(), 2);
    }

    // --- JSON roundtrip ---

    #[test]
    fn test_json_roundtrip() {
        let c = vc(&[("A", 5), ("B", 3)]);
        let json = c.to_json();
        let c2 = VectorClock::from_json(&json);
        assert_eq!(c2.get("A"), 5);
        assert_eq!(c2.get("B"), 3);
    }

    #[test]
    fn test_json_empty() {
        let c = VectorClock::new();
        let json = c.to_json();
        let c2 = VectorClock::from_json(&json);
        assert!(c2.is_empty());
    }

    #[test]
    fn test_json_invalid_returns_empty() {
        let c = VectorClock::from_json("not json");
        assert!(c.is_empty());
    }

    // --- CausalOrder ---

    #[test]
    fn test_equal_clocks() {
        let a = vc(&[("A", 1), ("B", 2)]);
        let b = vc(&[("A", 1), ("B", 2)]);
        assert_eq!(CausalOrder::compare(&a, &b), CausalOrder::Equal);
    }

    #[test]
    fn test_happens_before() {
        let a = vc(&[("A", 1), ("B", 1)]);
        let b = vc(&[("A", 2), ("B", 1)]);
        assert_eq!(CausalOrder::compare(&a, &b), CausalOrder::HappensBefore);
    }

    #[test]
    fn test_happens_after() {
        let a = vc(&[("A", 3), ("B", 1)]);
        let b = vc(&[("A", 1), ("B", 1)]);
        assert_eq!(CausalOrder::compare(&a, &b), CausalOrder::HappensAfter);
    }

    #[test]
    fn test_concurrent() {
        let a = vc(&[("A", 2), ("B", 1)]);
        let b = vc(&[("A", 1), ("B", 2)]);
        assert_eq!(CausalOrder::compare(&a, &b), CausalOrder::Concurrent);
        assert!(CausalOrder::compare(&a, &b).is_concurrent());
    }

    #[test]
    fn test_empty_clocks_are_equal() {
        let a = VectorClock::new();
        let b = VectorClock::new();
        assert_eq!(CausalOrder::compare(&a, &b), CausalOrder::Equal);
    }

    #[test]
    fn test_one_empty_one_not() {
        let a = VectorClock::new();
        let b = vc(&[("A", 1)]);
        assert_eq!(CausalOrder::compare(&a, &b), CausalOrder::HappensBefore);
    }

    // --- EventLog ---

    #[test]
    fn test_append_local_increments_clock() {
        let mut log = EventLog::new("node1".into());
        let e1 = log.append_local("e1".into(), "data".into(), 1000);
        let e2 = log.append_local("e2".into(), "data".into(), 2000);
        assert_eq!(e1.clock.get("node1"), 1);
        assert_eq!(e2.clock.get("node1"), 2);
    }

    #[test]
    fn test_receive_remote_merges_clock() {
        let mut log = EventLog::new("A".into());
        log.append_local("e1".into(), "x".into(), 1000);

        let remote_clock = vc(&[("B", 5)]);
        let remote = StampedEvent::new("r1".into(), "B".into(), remote_clock, "y".into(), 500);
        log.receive_remote(remote);

        assert_eq!(log.local_clock().get("B"), 5);
        assert!(log.local_clock().get("A") >= 1);
    }

    #[test]
    fn test_event_log_ordering() {
        let mut log = EventLog::new("A".into());

        let e1 = log.append_local("e1".into(), "first".into(), 1000);
        let e2 = log.append_local("e2".into(), "second".into(), 2000);

        assert_eq!(log.len(), 2);
        // e1 happens before e2
        assert_eq!(e1.causal_order(&e2), CausalOrder::HappensBefore);
    }

    #[test]
    fn test_event_log_empty() {
        let log = EventLog::new("A".into());
        assert!(log.is_empty());
        assert_eq!(log.len(), 0);
    }

    #[test]
    fn test_stamped_event_causal_order() {
        let c1 = vc(&[("A", 1)]);
        let c2 = vc(&[("A", 2)]);
        let e1 = StampedEvent::new("e1".into(), "A".into(), c1, "".into(), 0);
        let e2 = StampedEvent::new("e2".into(), "A".into(), c2, "".into(), 0);
        assert_eq!(e1.causal_order(&e2), CausalOrder::HappensBefore);
        assert_eq!(e2.causal_order(&e1), CausalOrder::HappensAfter);
    }
}
