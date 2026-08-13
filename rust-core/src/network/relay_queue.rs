//! # Relay Queue — mesh store-and-forward для ЧУЖИХ сообщений
//!
//! В отличие от [`crate::network::message_queue::MessageQueue`] (который хранит СВОИ
//! исходящие сообщения), `RelayQueue` хранит сообщения, адресованные **другим** узлам,
//! чтобы переслать их дальше (mesh / эпидемически), пока получатель не появится в сети.
//!
//! Содержимое — **E2E-зашифрованный payload**; узел-relay его НЕ читает (видит только
//! `msg_id`, `recipient`, `origin_sender`, `chat_scope`, TTL, hop).
//!
//! См. `docs/MESH_DELIVERY.md`.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// TTL релейного сообщения по умолчанию (7 дней).
pub const DEFAULT_RELAY_TTL: Duration = Duration::from_secs(7 * 24 * 3600);

/// Максимум хопов (пересылок) — защита от петель и бесконечного распространения.
pub const MAX_HOPS: u8 = 8;

/// Максимум сообщений на одного получателя (защита от DoS / переполнения).
pub const MAX_PER_RECIPIENT: usize = 200;

/// Максимум сообщений в очереди всего (защита памяти).
pub const MAX_TOTAL: usize = 10_000;

// ═══════════════════════════════════════════════════════════════════
// ОШИБКИ
// ═══════════════════════════════════════════════════════════════════

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum RelayQueueError {
    #[error("Relay-очередь получателя переполнена (максимум {max})")]
    RecipientQueueFull { max: usize },

    #[error("Общая relay-очередь переполнена (максимум {max})")]
    GlobalQueueFull { max: usize },
}

// ═══════════════════════════════════════════════════════════════════
// RELAY MESSAGE
// ═══════════════════════════════════════════════════════════════════

/// Одно сообщение в relay-очереди (адресовано другому узлу).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RelayMessage {
    /// Глобальный уникальный ID — ключ дедупликации.
    pub msg_id: String,

    /// NodeID получателя (только он расшифрует payload).
    pub recipient: String,

    /// NodeID изначального отправителя (для ACK/статуса).
    pub origin_sender: String,

    /// chat_id (1:1) ИЛИ group_id / group_id:topic (группы/каналы).
    pub chat_scope: String,

    /// E2E-зашифрованный payload — relay НЕ читает.
    pub e2e_payload: Vec<u8>,

    /// Когда сообщение поставлено в очередь.
    pub created_at: Instant,

    /// Когда сообщение истекает (TTL).
    pub expires_at: Instant,

    /// Сколько пересылок уже было (защита от петель).
    pub hop_count: u8,
}

impl RelayMessage {
    /// Создать новое релейное сообщение с TTL по умолчанию и hop = 0.
    pub fn new(
        msg_id: String,
        recipient: String,
        origin_sender: String,
        chat_scope: String,
        e2e_payload: Vec<u8>,
    ) -> Self {
        let now = Instant::now();
        RelayMessage {
            msg_id,
            recipient,
            origin_sender,
            chat_scope,
            e2e_payload,
            created_at: now,
            expires_at: now + DEFAULT_RELAY_TTL,
            hop_count: 0,
        }
    }

    /// Создать с кастомным TTL.
    pub fn with_ttl(
        msg_id: String,
        recipient: String,
        origin_sender: String,
        chat_scope: String,
        e2e_payload: Vec<u8>,
        ttl: Duration,
    ) -> Self {
        let now = Instant::now();
        RelayMessage {
            msg_id,
            recipient,
            origin_sender,
            chat_scope,
            e2e_payload,
            created_at: now,
            expires_at: now + ttl,
            hop_count: 0,
        }
    }

    /// Создать копию с увеличенным hop_count (для следующей пересылки).
    /// Возвращает `None`, если хопы исчерпаны.
    pub fn next_hop(&self) -> Option<RelayMessage> {
        if self.hops_exceeded() {
            return None;
        }
        let mut clone = self.clone();
        clone.hop_count += 1;
        Some(clone)
    }

    /// Просрочено ли сообщение.
    pub fn is_expired(&self) -> bool {
        Instant::now() >= self.expires_at
    }

    /// Превышен ли лимит хопов.
    pub fn hops_exceeded(&self) -> bool {
        self.hop_count >= MAX_HOPS
    }
}

// ═══════════════════════════════════════════════════════════════════
// RELAY QUEUE
// ═══════════════════════════════════════════════════════════════════

/// Mesh store-and-forward очередь для ЧУЖИХ сообщений.
///
/// Ключ — `msg_id` (для дедупликации и cleanup). Поиск по получателю — линейный
/// (очередь небольшая; для больших объёмов позже добавим индекс).
pub struct RelayQueue {
    entries: Mutex<HashMap<String, RelayMessage>>,
    max_per_recipient: usize,
    max_total: usize,
}

impl RelayQueue {
    /// Создать очередь с лимитами по умолчанию.
    pub fn new() -> Self {
        RelayQueue {
            entries: Mutex::new(HashMap::new()),
            max_per_recipient: MAX_PER_RECIPIENT,
            max_total: MAX_TOTAL,
        }
    }

    /// Создать с кастомными лимитами (для тестов).
    pub fn with_limits(max_per_recipient: usize, max_total: usize) -> Self {
        RelayQueue {
            entries: Mutex::new(HashMap::new()),
            max_per_recipient,
            max_total,
        }
    }

    // ─── Добавление ─────────────────────────────────────────────────

    /// Поставить сообщение в relay-очередь.
    ///
    /// Возвращает:
    /// - `Ok(true)` — добавлено;
    /// - `Ok(false)` — НЕ добавлено (дубль по `msg_id` ИЛИ исчерпаны хопы) — ожидаемо;
    /// - `Err(...)` — НЕ добавлено из-за лимита (per-recipient / global).
    pub fn enqueue(&self, msg: RelayMessage) -> Result<bool, RelayQueueError> {
        // Дубли и исчерпанные хопы — тихо пропускаем (Ok(false)).
        if msg.hops_exceeded() {
            return Ok(false);
        }

        let mut entries = self.entries.lock().unwrap();

        if entries.contains_key(&msg.msg_id) {
            return Ok(false); // дедупликация
        }

        // Глобальный лимит
        if entries.len() >= self.max_total {
            return Err(RelayQueueError::GlobalQueueFull {
                max: self.max_total,
            });
        }

        // Per-recipient лимит
        let per_recipient = entries.values().filter(|m| m.recipient == msg.recipient).count();
        if per_recipient >= self.max_per_recipient {
            return Err(RelayQueueError::RecipientQueueFull {
                max: self.max_per_recipient,
            });
        }

        entries.insert(msg.msg_id.clone(), msg);
        Ok(true)
    }

    // ─── Чтение ─────────────────────────────────────────────────────

    /// Есть ли уже сообщение с таким `msg_id` (дедупликация).
    pub fn contains(&self, msg_id: &str) -> bool {
        self.entries.lock().unwrap().contains_key(msg_id)
    }

    /// Все сообщения для указанного получателя (клонированные, не удаляются).
    /// Используется когда получатель появился — выдать ему всё накопленное.
    pub fn for_recipient(&self, recipient: &str) -> Vec<RelayMessage> {
        self.entries
            .lock()
            .unwrap()
            .values()
            .filter(|m| m.recipient == recipient)
            .cloned()
            .collect()
    }

    /// Сводка очереди: `(msg_id, recipient)` для gossip-обмена.
    pub fn digest(&self) -> Vec<(String, String)> {
        self.entries
            .lock()
            .unwrap()
            .values()
            .map(|m| (m.msg_id.clone(), m.recipient.clone()))
            .collect()
    }

    // ─── Удаление (cleanup) ─────────────────────────────────────────

    /// Удалить конкретное сообщение по `msg_id` (cleanup после receipt).
    /// Возвращает `true` если найдено и удалено.
    pub fn remove(&self, msg_id: &str) -> bool {
        self.entries.lock().unwrap().remove(msg_id).is_some()
    }

    /// Удалить все сообщения для получателя (например, после прямой доставки).
    /// Возвращает число удалённых.
    pub fn remove_for_recipient(&self, recipient: &str) -> usize {
        let mut entries = self.entries.lock().unwrap();
        let before = entries.len();
        entries.retain(|_, m| m.recipient != recipient);
        before - entries.len()
    }

    /// Удалить все просроченные. Возвращает число удалённых.
    pub fn cleanup_expired(&self) -> usize {
        let mut entries = self.entries.lock().unwrap();
        let before = entries.len();
        entries.retain(|_, m| !m.is_expired());
        before - entries.len()
    }

    // ─── Статистика ─────────────────────────────────────────────────

    /// Общее число сообщений в очереди.
    pub fn total_count(&self) -> usize {
        self.entries.lock().unwrap().len()
    }

    /// Число сообщений для конкретного получателя.
    pub fn count_for(&self, recipient: &str) -> usize {
        self.entries
            .lock()
            .unwrap()
            .values()
            .filter(|m| m.recipient == recipient)
            .count()
    }

    /// Число уникальных получателей в очереди.
    pub fn recipient_count(&self) -> usize {
        let entries = self.entries.lock().unwrap();
        let mut seen: Vec<String> = Vec::new();
        for m in entries.values() {
            if !seen.contains(&m.recipient) {
                seen.push(m.recipient.clone());
            }
        }
        seen.len()
    }
}

impl Default for RelayQueue {
    fn default() -> Self {
        Self::new()
    }
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(msg_id: &str, recipient: &str) -> RelayMessage {
        RelayMessage::new(
            msg_id.into(),
            recipient.into(),
            "pk_origin".into(),
            "chat-1".into(),
            vec![0xAA; 50],
        )
    }

    // ── Базовые ─────────────────────────────────────────────────────

    #[test]
    fn test_new_queue_empty() {
        let q = RelayQueue::new();
        assert_eq!(q.total_count(), 0);
        assert_eq!(q.recipient_count(), 0);
        assert!(!q.contains("m1"));
    }

    #[test]
    fn test_enqueue_and_contains() {
        let q = RelayQueue::new();
        assert!(q.enqueue(msg("m1", "pk_b")).unwrap());
        assert!(q.contains("m1"));
        assert_eq!(q.total_count(), 1);
        assert_eq!(q.count_for("pk_b"), 1);
    }

    #[test]
    fn test_for_recipient_returns_only_target() {
        let q = RelayQueue::new();
        q.enqueue(msg("m1", "pk_b")).unwrap();
        q.enqueue(msg("m2", "pk_b")).unwrap();
        q.enqueue(msg("m3", "pk_c")).unwrap();

        let for_b = q.for_recipient("pk_b");
        assert_eq!(for_b.len(), 2);
        assert!(for_b.iter().all(|m| m.recipient == "pk_b"));

        let for_c = q.for_recipient("pk_c");
        assert_eq!(for_c.len(), 1);

        // for_recipient не удаляет
        assert_eq!(q.total_count(), 3);
    }

    #[test]
    fn test_digest() {
        let q = RelayQueue::new();
        q.enqueue(msg("m1", "pk_b")).unwrap();
        q.enqueue(msg("m2", "pk_c")).unwrap();

        let mut d = q.digest();
        d.sort();
        assert_eq!(d, vec![("m1".into(), "pk_b".into()), ("m2".into(), "pk_c".into())]);
    }

    // ── Дедупликация ────────────────────────────────────────────────

    #[test]
    fn test_dedup_same_msg_id() {
        let q = RelayQueue::new();
        assert!(q.enqueue(msg("m1", "pk_b")).unwrap()); // true — добавлено
        // Тот же msg_id (даже с другим recipient) — дубликат
        let second = q.enqueue(msg("m1", "pk_x")).unwrap();
        assert!(!second); // false — НЕ добавлено
        assert_eq!(q.total_count(), 1);
        assert_eq!(q.count_for("pk_b"), 1);
        assert_eq!(q.count_for("pk_x"), 0);
    }

    // ── Удаление / cleanup ──────────────────────────────────────────

    #[test]
    fn test_remove_by_msg_id() {
        let q = RelayQueue::new();
        q.enqueue(msg("m1", "pk_b")).unwrap();
        assert!(q.remove("m1"));
        assert!(!q.contains("m1"));
        assert!(!q.remove("m1")); // повторно — false
    }

    #[test]
    fn test_remove_for_recipient() {
        let q = RelayQueue::new();
        q.enqueue(msg("m1", "pk_b")).unwrap();
        q.enqueue(msg("m2", "pk_b")).unwrap();
        q.enqueue(msg("m3", "pk_c")).unwrap();

        let removed = q.remove_for_recipient("pk_b");
        assert_eq!(removed, 2);
        assert_eq!(q.count_for("pk_b"), 0);
        assert_eq!(q.count_for("pk_c"), 1);
    }

    #[test]
    fn test_cleanup_expired() {
        let q = RelayQueue::new();
        // Короткий TTL — почти сразу истекает
        let soon = RelayMessage::with_ttl(
            "m1".into(), "pk_b".into(), "pk_o".into(), "c".into(), vec![1], Duration::from_millis(1),
        );
        q.enqueue(soon).unwrap();
        q.enqueue(msg("m2", "pk_b")).unwrap(); // TTL по умолчанию — свежее
        assert_eq!(q.total_count(), 2);

        std::thread::sleep(Duration::from_millis(20));
        let removed = q.cleanup_expired();
        assert_eq!(removed, 1);
        assert_eq!(q.total_count(), 1);
        assert!(q.contains("m2"));
        assert!(!q.contains("m1"));
    }

    // ── Лимиты ──────────────────────────────────────────────────────

    #[test]
    fn test_per_recipient_limit() {
        let q = RelayQueue::with_limits(2, 100);
        q.enqueue(msg("m1", "pk_b")).unwrap();
        q.enqueue(msg("m2", "pk_b")).unwrap();
        // Третье для того же получателя — превышение per-recipient
        let res = q.enqueue(msg("m3", "pk_b"));
        assert_eq!(res, Err(RelayQueueError::RecipientQueueFull { max: 2 }));
        // Другой получатель — нормально
        assert!(q.enqueue(msg("m4", "pk_c")).unwrap());
    }

    #[test]
    fn test_global_limit() {
        let q = RelayQueue::with_limits(100, 2);
        q.enqueue(msg("m1", "pk_b")).unwrap();
        q.enqueue(msg("m2", "pk_c")).unwrap();
        // Третье — общий лимит
        let res = q.enqueue(msg("m3", "pk_d"));
        assert_eq!(res, Err(RelayQueueError::GlobalQueueFull { max: 2 }));
    }

    // ── Хопы ────────────────────────────────────────────────────────

    #[test]
    fn test_next_hop_increments() {
        let m = msg("m1", "pk_b");
        assert_eq!(m.hop_count, 0);
        let m1 = m.next_hop().unwrap();
        assert_eq!(m1.hop_count, 1);
    }

    #[test]
    fn test_enqueue_rejects_hops_exceeded() {
        let mut m = msg("m1", "pk_b");
        m.hop_count = MAX_HOPS;
        // hop исчерпан — enqueue не добавляет (Ok(false)), next_hop — None
        assert_eq!(m.next_hop(), None);
        let q = RelayQueue::new();
        let added = q.enqueue(m).unwrap();
        assert!(!added);
        assert_eq!(q.total_count(), 0);
    }

    // ── Несколько получателей ───────────────────────────────────────

    #[test]
    fn test_multiple_recipients_independent() {
        let q = RelayQueue::new();
        for i in 1..=3 {
            q.enqueue(msg(&format!("a{i}"), "pk_a")).unwrap();
        }
        for i in 1..=2 {
            q.enqueue(msg(&format!("b{i}"), "pk_b")).unwrap();
        }
        assert_eq!(q.total_count(), 5);
        assert_eq!(q.recipient_count(), 2);
        assert_eq!(q.count_for("pk_a"), 3);
        assert_eq!(q.count_for("pk_b"), 2);
    }

    #[test]
    fn test_relay_message_creation() {
        let m = RelayMessage::new("m1".into(), "pk_b".into(), "pk_o".into(), "c".into(), vec![1, 2, 3]);
        assert_eq!(m.msg_id, "m1");
        assert_eq!(m.recipient, "pk_b");
        assert_eq!(m.origin_sender, "pk_o");
        assert_eq!(m.chat_scope, "c");
        assert_eq!(m.e2e_payload, vec![1, 2, 3]);
        assert_eq!(m.hop_count, 0);
        assert!(!m.is_expired());
        assert!(!m.hops_exceeded());
    }

    #[test]
    fn test_default_ttl_is_7_days() {
        assert_eq!(DEFAULT_RELAY_TTL.as_secs(), 7 * 24 * 3600);
    }
}
