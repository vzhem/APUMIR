//! CRDT — Conflict-free Replicated Data Types
//! Для синхронизации состояния групп и списков участников

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};

// ============================================================
// LWW-Register (Last-Write-Wins Register)
// ============================================================

/// LWW-Register: значение с меткой времени
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LWWRegister<T> {
    value: Option<T>,
    timestamp: u64,
    node_id: String,
}

impl<T: Clone> LWWRegister<T> {
    pub fn new(node_id: String) -> Self {
        Self {
            value: None,
            timestamp: 0,
            node_id,
        }
    }

    pub fn set(&mut self, value: T, timestamp: u64, node_id: &str) {
        // Обновляем только если timestamp новее
        // При равном timestamp выигрывает больший node_id (deterministic)
        if timestamp > self.timestamp
            || (timestamp == self.timestamp && node_id > self.node_id.as_str())
        {
            self.value = Some(value);
            self.timestamp = timestamp;
            self.node_id = node_id.to_string();
        }
    }

    pub fn get(&self) -> Option<&T> {
        self.value.as_ref()
    }

    pub fn merge(&mut self, other: &LWWRegister<T>) {
        if let Some(ref v) = other.value {
            self.set(v.clone(), other.timestamp, &other.node_id);
        }
    }
}

// ============================================================
// G-Set (Grow-only Set)
// ============================================================

/// G-Set: множество, в которое можно только добавлять
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GSet<T: Eq + std::hash::Hash> {
    elements: HashSet<T>,
}

impl<T: Eq + std::hash::Hash + Clone> GSet<T> {
    pub fn new() -> Self {
        Self {
            elements: HashSet::new(),
        }
    }

    pub fn add(&mut self, element: T) {
        self.elements.insert(element);
    }

    pub fn contains(&self, element: &T) -> bool {
        self.elements.contains(element)
    }

    pub fn merge(&mut self, other: &GSet<T>) {
        for e in &other.elements {
            self.elements.insert(e.clone());
        }
    }

    pub fn len(&self) -> usize {
        self.elements.len()
    }

    pub fn is_empty(&self) -> bool {
        self.elements.is_empty()
    }

    pub fn iter(&self) -> impl Iterator<Item = &T> {
        self.elements.iter()
    }
}

impl<T: Eq + std::hash::Hash + Clone> Default for GSet<T> {
    fn default() -> Self {
        Self::new()
    }
}

// ============================================================
// Two-Phase Set (2P-Set)
// ============================================================

/// 2P-Set: множество с добавлением и удалением
/// Элемент можно удалить только если он был добавлен
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TwoPhaseSet<T: Eq + std::hash::Hash> {
    added: HashSet<T>,
    removed: HashSet<T>,
}

impl<T: Eq + std::hash::Hash + Clone> TwoPhaseSet<T> {
    pub fn new() -> Self {
        Self {
            added: HashSet::new(),
            removed: HashSet::new(),
        }
    }

    pub fn add(&mut self, element: T) {
        self.added.insert(element);
    }

    pub fn remove(&mut self, element: T) {
        // Удаляем только если элемент был добавлен
        if self.added.contains(&element) {
            self.removed.insert(element);
        }
    }

    pub fn contains(&self, element: &T) -> bool {
        self.added.contains(element) && !self.removed.contains(element)
    }

    pub fn merge(&mut self, other: &TwoPhaseSet<T>) {
        for e in &other.added {
            self.added.insert(e.clone());
        }
        for e in &other.removed {
            if self.added.contains(e) {
                self.removed.insert(e.clone());
            }
        }
    }

    pub fn len(&self) -> usize {
        self.added.len() - self.removed.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

impl<T: Eq + std::hash::Hash + Clone> Default for TwoPhaseSet<T> {
    fn default() -> Self {
        Self::new()
    }
}

// ============================================================
// OR-Set (Observed-Remove Set)
// ============================================================

/// OR-Set: множество с уникальными "наблюдениями"
/// Более корректная реализация чем 2P-Set
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ORSet<T: Eq + std::hash::Hash> {
    /// element -> set of unique tags
    elements: HashMap<T, HashSet<String>>,
}

impl<T: Eq + std::hash::Hash + Clone> ORSet<T> {
    pub fn new() -> Self {
        Self {
            elements: HashMap::new(),
        }
    }

    /// Добавить элемент с уникальным тегом
    pub fn add(&mut self, element: T, tag: String) {
        self.elements.entry(element).or_default().insert(tag);
    }

    /// Удалить элемент (удаляем все его теги)
    pub fn remove(&mut self, element: &T) {
        self.elements.remove(element);
    }

    pub fn contains(&self, element: &T) -> bool {
        self.elements
            .get(element)
            .map(|tags| !tags.is_empty())
            .unwrap_or(false)
    }

    pub fn merge(&mut self, other: &ORSet<T>) {
        for (elem, tags) in &other.elements {
            self.elements
                .entry(elem.clone())
                .or_default()
                .extend(tags.clone());
        }
    }

    pub fn len(&self) -> usize {
        self.elements
            .values()
            .filter(|tags| !tags.is_empty())
            .count()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

impl<T: Eq + std::hash::Hash + Clone> Default for ORSet<T> {
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

    // --- LWWRegister ---

    #[test]
    fn test_lww_register_set() {
        let mut r: LWWRegister<String> = LWWRegister::new("node1".into());
        r.set("hello".into(), 100, "node1");
        assert_eq!(r.get(), Some(&"hello".into()));
    }

    #[test]
    fn test_lww_register_newer_wins() {
        let mut r: LWWRegister<String> = LWWRegister::new("node1".into());
        r.set("old".into(), 100, "node1");
        r.set("new".into(), 200, "node2");
        assert_eq!(r.get(), Some(&"new".into()));
    }

    #[test]
    fn test_lww_register_same_ts_deterministic() {
        let mut r: LWWRegister<String> = LWWRegister::new("node1".into());
        r.set("a".into(), 100, "node1");
        r.set("b".into(), 100, "node2"); // node2 > node1
        assert_eq!(r.get(), Some(&"b".into()));
    }

    #[test]
    fn test_lww_register_merge() {
        let mut a: LWWRegister<i32> = LWWRegister::new("A".into());
        let mut b: LWWRegister<i32> = LWWRegister::new("B".into());
        a.set(1, 10, "A");
        b.set(2, 20, "B");
        a.merge(&b);
        assert_eq!(a.get(), Some(&2));
    }

    // --- GSet ---

    #[test]
    fn test_gset_add() {
        let mut s = GSet::new();
        s.add("apple");
        s.add("banana");
        assert!(s.contains(&"apple"));
        assert_eq!(s.len(), 2);
    }

    #[test]
    fn test_gset_merge() {
        let mut a = GSet::new();
        let mut b = GSet::new();
        a.add(1);
        b.add(2);
        a.merge(&b);
        assert_eq!(a.len(), 2);
    }

    #[test]
    fn test_gset_idempotent() {
        let mut s = GSet::new();
        s.add("x");
        s.add("x");
        assert_eq!(s.len(), 1);
    }

    // --- TwoPhaseSet ---

    #[test]
    fn test_2pset_add_remove() {
        let mut s = TwoPhaseSet::new();
        s.add("a");
        s.add("b");
        assert!(s.contains(&"a"));
        s.remove("a");
        assert!(!s.contains(&"a"));
        assert!(s.contains(&"b"));
    }

    #[test]
    fn test_2pset_remove_before_add_ignored() {
        let mut s = TwoPhaseSet::new();
        s.remove("x"); // не добавлен — игнорируется
        s.add("x");
        assert!(s.contains(&"x")); // всё ещё есть
    }

    #[test]
    fn test_2pset_merge() {
        let mut a = TwoPhaseSet::new();
        let mut b = TwoPhaseSet::new();
        a.add("x");
        b.add("y");
        b.remove("y");
        a.merge(&b);
        assert!(a.contains(&"x"));
        assert!(!a.contains(&"y"));
    }

    // --- ORSet ---

    #[test]
    fn test_orset_add_remove() {
        let mut s = ORSet::new();
        s.add("item", "tag1".into());
        assert!(s.contains(&"item"));
        s.remove(&"item");
        assert!(!s.contains(&"item"));
    }

    #[test]
    fn test_orset_merge_tags() {
        let mut a = ORSet::new();
        let mut b = ORSet::new();
        a.add("x", "tagA".into());
        b.add("x", "tagB".into());
        a.merge(&b);
        assert!(a.contains(&"x"));
        assert_eq!(a.len(), 1);
    }

    #[test]
    fn test_orset_remove_one_tag() {
        let mut s = ORSet::new();
        s.add("x", "t1".into());
        s.add("x", "t2".into());
        assert_eq!(s.len(), 1);
        s.remove(&"x");
        assert!(!s.contains(&"x"));
    }

    #[test]
    fn test_orset_empty() {
        let s: ORSet<String> = ORSet::new();
        assert!(s.is_empty());
    }
}
