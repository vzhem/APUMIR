//! # Relay Queue — mesh store-and-forward для ЧУЖИХ сообщений
//!
//! В отличие от [`crate::network::message_queue::MessageQueue`] (который хранит СВОИ
//! исходящие сообщения), `RelayQueue` хранит сообщения, адресованные **другим** узлам,
//! чтобы переслать их дальше (mesh / эпидемически), пока получатель не появится в сети.
//!
//! Содержимое — **E2E-зашифрованный payload**; узел-relay его НЕ читает (видит только
//! `msg_id`, `recipient`, `origin_sender`, `chat_scope`, TTL, hop).
//!
//! **M8-A (durable model boundary):** временные метки записи — абсолютные UTC
//! миллисекунды от epoch (`created_at_ms` / `expires_at_ms`, `i64`), а не
//! process-local `Instant`. Дедлайн TTL фиксируется один раз при создании и НЕ
//! продлевается после restart/recovery. Запись сериализуема (`serde`) и принимает
//! только bounded/validated представление: загрузка из durable-хранилища обязана
//! проходить [`RelayMessage::from_persisted`] (валидация метаданных, timestamps,
//! expiry и hop).
//!
//! Security-оговорка: payload считается recipient-ciphertext только по построению;
//! строгий M7-acceptance ещё не завершён. Durable-слой (M8-B/C) не должен писать
//! plaintext и не должен предполагать расшифровку на relay-узле.
//!
//! См. `docs/MESH_DELIVERY.md`.

use std::collections::{HashMap, HashSet};
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

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

// ── Общие bounded-лимиты mesh-метаданных (M8-A) ──────────────────────
//
// Это те же лимиты, которые уже использует offline-send/wire путь
// (`network/offline_send.rs`); они вынесены сюда, чтобы durable-модель и
// wire-подготовка опирались на один источник без циклической зависимости
// модулей. Лимиты НЕ новые и НЕ меняют wire format.

/// Максимальная длина `msg_id` в байтах.
pub const MAX_MESSAGE_ID_BYTES: usize = 128;

/// Максимальная длина node ID (`recipient` / `origin_sender`) в байтах.
pub const MAX_NODE_ID_BYTES: usize = 128;

/// Максимальная длина `chat_scope` в байтах.
pub const MAX_CHAT_SCOPE_BYTES: usize = 256;

/// Максимальный размер mesh relay envelope в байтах. Payload durable-записи
/// не может превышать этот предел: в wire-конверте payload несётся base64-ом,
/// поэтому payload заведомо не больше самого конверта.
pub const MAX_MESH_RELAY_ENVELOPE_BYTES: usize = 64 * 1024;

/// Проверка строки метаданных так же, как это делает wire-путь: не пустая,
/// не длиннее `max_bytes`, без разделителя `|` (иначе запись не переживёт
/// повторную отправку через `relay|...` envelope).
pub fn valid_metadata_atom(value: &str, max_bytes: usize) -> bool {
    !value.is_empty() && value.len() <= max_bytes && !value.contains('|')
}

/// Текущее абсолютное время UTC в миллисекундах от epoch.
///
/// Не panic и никогда не отрицательное: если системные часы установлены раньше
/// 1970 epoch, возвращает `0` («clock before epoch» обрабатывается валидацией
/// ниже по потоку, а не crash). Совпадает по смыслу с epoch-ms конвенцией
/// `crate::storage::models::now_ms`, но намеренно живёт в network-модуле:
/// durable storage слой (M8-B) будет импортировать этот модуль, поэтому
/// обратная зависимость network → storage не создаётся.
pub fn utc_now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(i64::MAX as u128) as i64
}

/// Безопасная конверсия `Duration` → миллисекунды для TTL: очень большие
/// значения saturate в `i64::MAX` вместо переполнения/panic.
pub fn ttl_to_ms(ttl: Duration) -> i64 {
    i64::try_from(ttl.as_millis()).unwrap_or(i64::MAX)
}

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

/// Ошибки валидации durable relay-записи при загрузке/восстановлении (M8-B/D).
/// Загрузка обязана отклонять такие записи явно, без panic, enqueue или UI.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum RelayValidationError {
    #[error("durable relay record: пустой msg_id")]
    EmptyMessageId,

    #[error("durable relay record: msg_id длиннее {max} байт")]
    MessageIdTooLong { max: usize },

    #[error("durable relay record: пустой recipient")]
    EmptyRecipient,

    #[error("durable relay record: recipient длиннее {max} байт")]
    RecipientTooLong { max: usize },

    #[error("durable relay record: пустой origin")]
    EmptyOrigin,

    #[error("durable relay record: origin длиннее {max} байт")]
    OriginTooLong { max: usize },

    #[error("durable relay record: пустой chat scope")]
    EmptyChatScope,

    #[error("durable relay record: chat scope длиннее {max} байт")]
    ChatScopeTooLong { max: usize },

    #[error("durable relay record: поле `{field}` содержит wire-разделитель '|'")]
    MetadataContainsDelimiter { field: &'static str },

    #[error("durable relay record: пустой payload")]
    EmptyPayload,

    #[error("durable relay record: payload больше {max} байт")]
    PayloadTooLarge { max: usize },

    #[error("durable relay record: expires_at_ms раньше created_at_ms")]
    ExpiresBeforeCreated,

    #[error("durable relay record: уже истёк (expired)")]
    AlreadyExpired,

    #[error("durable relay record: исчерпан лимит хопов")]
    HopsExceeded,
}

// ═══════════════════════════════════════════════════════════════════
// RELAY MESSAGE
// ═══════════════════════════════════════════════════════════════════

/// Одно сообщение в relay-очереди (адресовано другому узлу).
///
/// **Durable-представление (M8-A):** время хранится как абсолютные UTC
/// миллисекунды от epoch (`i64`), поэтому запись сериализуема и переживает
/// process death/reboot. Дедлайн TTL задаётся один раз при создании и НЕ
/// продлевается после restart/recovery.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
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

    /// Когда сообщение создано (абсолютные UTC epoch-миллисекунды).
    pub created_at_ms: i64,

    /// Когда сообщение истекает (абсолютные UTC epoch-миллисекунды).
    /// Никогда не продлевается при восстановлении после restart.
    pub expires_at_ms: i64,

    /// Сколько пересылок уже было (защита от петель).
    pub hop_count: u8,
}

impl RelayMessage {
    /// Создать новое релейное сообщение с TTL по умолчанию и hop = 0
    /// (время — текущий UTC).
    pub fn new(
        msg_id: String,
        recipient: String,
        origin_sender: String,
        chat_scope: String,
        e2e_payload: Vec<u8>,
    ) -> Self {
        Self::new_at_ms(
            utc_now_ms(),
            msg_id,
            recipient,
            origin_sender,
            chat_scope,
            e2e_payload,
        )
    }

    /// Создать с TTL по умолчанию на фиксированных часах (детерминированные
    /// тесты; durable-загрузка с известным «сейчас»).
    pub fn new_at_ms(
        now_ms: i64,
        msg_id: String,
        recipient: String,
        origin_sender: String,
        chat_scope: String,
        e2e_payload: Vec<u8>,
    ) -> Self {
        RelayMessage {
            msg_id,
            recipient,
            origin_sender,
            chat_scope,
            e2e_payload,
            created_at_ms: now_ms,
            expires_at_ms: now_ms.saturating_add(ttl_to_ms(DEFAULT_RELAY_TTL)),
            hop_count: 0,
        }
    }

    /// Создать с кастомным TTL (время — текущий UTC).
    pub fn with_ttl(
        msg_id: String,
        recipient: String,
        origin_sender: String,
        chat_scope: String,
        e2e_payload: Vec<u8>,
        ttl: Duration,
    ) -> Self {
        Self::with_ttl_at_ms(
            utc_now_ms(),
            msg_id,
            recipient,
            origin_sender,
            chat_scope,
            e2e_payload,
            ttl,
        )
    }

    /// Создать с кастомным TTL на фиксированных часах (детерминированные
    /// тесты; durable-загрузка с известным «сейчас»).
    pub fn with_ttl_at_ms(
        now_ms: i64,
        msg_id: String,
        recipient: String,
        origin_sender: String,
        chat_scope: String,
        e2e_payload: Vec<u8>,
        ttl: Duration,
    ) -> Self {
        RelayMessage {
            msg_id,
            recipient,
            origin_sender,
            chat_scope,
            e2e_payload,
            created_at_ms: now_ms,
            expires_at_ms: now_ms.saturating_add(ttl_to_ms(ttl)),
            hop_count: 0,
        }
    }

    /// Loading-конструктор для durable-записей (M8-B читает их из SQLite):
    /// принимает десериализованную запись и «сейчас» на явных часах, отклоняет
    /// пустые/oversized метаданные, битые timestamps, уже истёкшие записи и
    /// исчерпанные хопы. Никогда не panic (в т.ч. при `now_ms == 0`).
    ///
    /// TTL при этом НЕ продлевается: `expires_at_ms` сохраняется как есть.
    pub fn from_persisted(
        record: RelayMessage,
        now_ms: i64,
    ) -> Result<RelayMessage, RelayValidationError> {
        record.validate_durable(now_ms)?;
        Ok(record)
    }

    /// Проверка durable-записи на bounded/validated представление.
    pub fn validate_durable(&self, now_ms: i64) -> Result<(), RelayValidationError> {
        // Метаданные: непустые, bounded, без wire-разделителя.
        if self.msg_id.is_empty() {
            return Err(RelayValidationError::EmptyMessageId);
        }
        if !valid_metadata_atom(&self.msg_id, MAX_MESSAGE_ID_BYTES) {
            return Err(if self.msg_id.len() > MAX_MESSAGE_ID_BYTES {
                RelayValidationError::MessageIdTooLong {
                    max: MAX_MESSAGE_ID_BYTES,
                }
            } else {
                RelayValidationError::MetadataContainsDelimiter { field: "msg_id" }
            });
        }

        if self.recipient.is_empty() {
            return Err(RelayValidationError::EmptyRecipient);
        }
        if !valid_metadata_atom(&self.recipient, MAX_NODE_ID_BYTES) {
            return Err(if self.recipient.len() > MAX_NODE_ID_BYTES {
                RelayValidationError::RecipientTooLong {
                    max: MAX_NODE_ID_BYTES,
                }
            } else {
                RelayValidationError::MetadataContainsDelimiter { field: "recipient" }
            });
        }

        if self.origin_sender.is_empty() {
            return Err(RelayValidationError::EmptyOrigin);
        }
        if !valid_metadata_atom(&self.origin_sender, MAX_NODE_ID_BYTES) {
            return Err(if self.origin_sender.len() > MAX_NODE_ID_BYTES {
                RelayValidationError::OriginTooLong {
                    max: MAX_NODE_ID_BYTES,
                }
            } else {
                RelayValidationError::MetadataContainsDelimiter {
                    field: "origin_sender",
                }
            });
        }

        if self.chat_scope.is_empty() {
            return Err(RelayValidationError::EmptyChatScope);
        }
        if !valid_metadata_atom(&self.chat_scope, MAX_CHAT_SCOPE_BYTES) {
            return Err(if self.chat_scope.len() > MAX_CHAT_SCOPE_BYTES {
                RelayValidationError::ChatScopeTooLong {
                    max: MAX_CHAT_SCOPE_BYTES,
                }
            } else {
                RelayValidationError::MetadataContainsDelimiter {
                    field: "chat_scope",
                }
            });
        }

        // Payload: непустой и не больше mesh envelope limit.
        if self.e2e_payload.is_empty() {
            return Err(RelayValidationError::EmptyPayload);
        }
        if self.e2e_payload.len() > MAX_MESH_RELAY_ENVELOPE_BYTES {
            return Err(RelayValidationError::PayloadTooLarge {
                max: MAX_MESH_RELAY_ENVELOPE_BYTES,
            });
        }

        // Timestamps: порядок и истечение. TTL никогда не продлевается здесь.
        if self.expires_at_ms < self.created_at_ms {
            return Err(RelayValidationError::ExpiresBeforeCreated);
        }
        if self.is_expired_at(now_ms) {
            return Err(RelayValidationError::AlreadyExpired);
        }

        // Хопы.
        if self.hops_exceeded() {
            return Err(RelayValidationError::HopsExceeded);
        }

        Ok(())
    }

    /// Создать копию с увеличенным hop_count (для следующей пересылки).
    /// Возвращает `None`, если хопы исчерпаны. Абсолютный `expires_at_ms`
    /// сохраняется без изменений (TTL не продлевается пересылкой).
    pub fn next_hop(&self) -> Option<RelayMessage> {
        if self.hops_exceeded() {
            return None;
        }
        let mut clone = self.clone();
        clone.hop_count += 1;
        Some(clone)
    }

    /// Просрочено ли сообщение на явных часах `now_ms` (детерминированно).
    pub fn is_expired_at(&self, now_ms: i64) -> bool {
        now_ms >= self.expires_at_ms
    }

    /// Просрочено ли сообщение (thin wrapper над системным UTC).
    pub fn is_expired(&self) -> bool {
        self.is_expired_at(utc_now_ms())
    }

    /// Оставшийся TTL в целых секундах на явных часах `now_ms` (0, если истёк).
    /// Используется wire-путём gossip: относительный `ttl_secs` в конверте.
    pub fn remaining_ttl_secs_at(&self, now_ms: i64) -> u64 {
        let remaining_ms = self.expires_at_ms.saturating_sub(now_ms).max(0);
        (remaining_ms as u64) / 1000
    }

    /// Оставшийся TTL в целых секундах (thin wrapper над системным UTC).
    pub fn remaining_ttl_secs(&self) -> u64 {
        self.remaining_ttl_secs_at(utc_now_ms())
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

    /// Ограниченный набор relay, отсутствующих в сводке peer.
    ///
    /// Сортирует только ссылки и клонирует не больше `limit` payload-ов, поэтому даже
    /// полная очередь не создаёт вторую полную копию в памяти. `cursor` обеспечивает
    /// продолжение со следующей части очереди в новом gossip-раунде.
    pub fn gossip_candidates(
        &self,
        peer_digest: &[(String, String)],
        cursor: usize,
        limit: usize,
    ) -> (Vec<RelayMessage>, usize) {
        let peer_items: HashSet<(&str, &str)> = peer_digest
            .iter()
            .map(|(msg_id, recipient)| (msg_id.as_str(), recipient.as_str()))
            .collect();
        let entries = self.entries.lock().unwrap();
        let mut missing: Vec<&RelayMessage> = entries
            .values()
            .filter(|message| {
                !peer_items.contains(&(message.msg_id.as_str(), message.recipient.as_str()))
            })
            .collect();
        missing.sort_by(|a, b| {
            a.recipient
                .cmp(&b.recipient)
                .then_with(|| a.msg_id.cmp(&b.msg_id))
        });

        let total_missing = missing.len();
        if total_missing == 0 || limit == 0 {
            return (Vec::new(), total_missing);
        }

        let start = cursor % total_missing;
        let candidates = (0..total_missing.min(limit))
            .map(|offset| (*missing[(start + offset) % total_missing]).clone())
            .collect();
        (candidates, total_missing)
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

    /// Удалить все просроченные (thin wrapper над системным UTC).
    /// Возвращает число удалённых.
    pub fn cleanup_expired(&self) -> usize {
        self.cleanup_expired_at(utc_now_ms())
    }

    /// Удалить все просроченные на явных часах `now_ms` (детерминированно;
    /// M8-D recovery будет использовать это с загруженным «сейчас»).
    /// Возвращает число удалённых.
    pub fn cleanup_expired_at(&self, now_ms: i64) -> usize {
        let mut entries = self.entries.lock().unwrap();
        let before = entries.len();
        entries.retain(|_, m| !m.is_expired_at(now_ms));
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
    fn test_gossip_candidates_are_bounded_rotating_clones() {
        let q = RelayQueue::new();
        q.enqueue(msg("m1", "pk_b")).unwrap();
        q.enqueue(msg("m2", "pk_b")).unwrap();
        q.enqueue(msg("m3", "pk_b")).unwrap();

        let peer_digest = vec![("m2".to_string(), "pk_b".to_string())];
        let (first, total) = q.gossip_candidates(&peer_digest, 0, 1);
        assert_eq!(total, 2);
        assert_eq!(first.len(), 1);
        assert_eq!(first[0].msg_id, "m1");

        let (second, total) = q.gossip_candidates(&peer_digest, 1, 1);
        assert_eq!(total, 2);
        assert_eq!(second.len(), 1);
        assert_eq!(second[0].msg_id, "m3");
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

    // ── Durable timestamps / M8-A ──────────────────────────────────

    /// Фиксированный timestamp: поля создаются детерминированно и переживают
    /// serialize/deserialize без изменений.
    #[test]
    fn test_durable_timestamps_round_trip() {
        let now_ms = 1_700_000_000_000i64;
        let original = RelayMessage::with_ttl_at_ms(
            now_ms,
            "m1".into(),
            "pk_b".into(),
            "pk_o".into(),
            "chat-1".into(),
            vec![1, 2, 3],
            Duration::from_secs(3600),
        );
        assert_eq!(original.created_at_ms, now_ms);
        assert_eq!(original.expires_at_ms, now_ms + 3_600_000);

        let bytes = bincode::serialize(&original).expect("serialize");
        let restored: RelayMessage = bincode::deserialize(&bytes).expect("deserialize");
        assert_eq!(restored, original);

        // Loading-конструктор принимает валидную запись.
        let loaded = RelayMessage::from_persisted(restored, now_ms + 1_000).unwrap();
        assert_eq!(loaded.created_at_ms, now_ms);
        assert_eq!(loaded.expires_at_ms, now_ms + 3_600_000);
        assert_eq!(loaded.hop_count, 0);
    }

    /// TTL НЕ продлевается после serialize/deserialize («restart»): абсолютный
    /// дедлайн сохраняется, остаток только уменьшается.
    #[test]
    fn test_ttl_not_extended_after_restart_simulation() {
        let created_ms = 1_700_000_000_000i64;
        let expires = created_ms + 60_000;
        let original = RelayMessage::with_ttl_at_ms(
            created_ms,
            "m1".into(),
            "pk_b".into(),
            "pk_o".into(),
            "c".into(),
            vec![9],
            Duration::from_secs(60),
        );
        assert_eq!(original.expires_at_ms, expires);

        // «Процесс умер», время ушло вперёд, запись восстановлена из durable store.
        let wake_ms = created_ms + 30_000;
        let bytes = bincode::serialize(&original).unwrap();
        let restored: RelayMessage = bincode::deserialize(&bytes).unwrap();
        let loaded = RelayMessage::from_persisted(restored, wake_ms).unwrap();

        assert_eq!(loaded.expires_at_ms, expires); // НЕ продлён
        assert_eq!(loaded.created_at_ms, created_ms);
        assert!(!loaded.is_expired_at(expires - 1));
        assert!(loaded.is_expired_at(expires));
        assert_eq!(loaded.remaining_ttl_secs_at(wake_ms), 30);
        assert_eq!(loaded.remaining_ttl_secs_at(expires), 0);
    }

    /// Истёкшая durable-запись отклоняется loading-конструктором, а если она
    /// всё ещё в очереди — детерминированно чистится без доставки в UI.
    #[test]
    fn test_expired_persisted_record_rejected_and_cleaned() {
        let created_ms = 1_700_000_000_000i64;
        let expired = RelayMessage::with_ttl_at_ms(
            created_ms,
            "m1".into(),
            "pk_b".into(),
            "pk_o".into(),
            "c".into(),
            vec![9],
            Duration::from_millis(100),
        );
        let now_ms = created_ms + 200;

        assert_eq!(
            RelayMessage::from_persisted(expired.clone(), now_ms),
            Err(RelayValidationError::AlreadyExpired)
        );

        let q = RelayQueue::new();
        q.enqueue(expired).unwrap();
        q.enqueue(msg("m2", "pk_b")).unwrap();
        assert_eq!(q.total_count(), 2);

        assert_eq!(q.cleanup_expired_at(now_ms), 1);
        assert!(!q.contains("m1"));
        assert!(q.contains("m2"));
    }

    /// `expires_at_ms < created_at_ms` отклоняется.
    #[test]
    fn test_invalid_timestamp_order_rejected() {
        let now_ms = 1_700_000_000_000i64;
        let mut record = RelayMessage::with_ttl_at_ms(
            now_ms,
            "m1".into(),
            "pk_b".into(),
            "pk_o".into(),
            "c".into(),
            vec![1],
            Duration::from_secs(60),
        );
        record.expires_at_ms = record.created_at_ms - 1;
        assert_eq!(
            record.validate_durable(now_ms),
            Err(RelayValidationError::ExpiresBeforeCreated)
        );
        assert!(RelayMessage::from_persisted(record, now_ms).is_err());
    }

    /// `hop_count >= MAX_HOPS` отклоняется валидацией; hop ниже лимита — ок.
    #[test]
    fn test_invalid_hop_rejected_by_validation() {
        let now_ms = 1_700_000_000_000i64;
        let mut record = RelayMessage::new_at_ms(
            now_ms,
            "m1".into(),
            "pk_b".into(),
            "pk_o".into(),
            "c".into(),
            vec![1],
        );
        record.hop_count = MAX_HOPS;
        assert_eq!(
            record.validate_durable(now_ms),
            Err(RelayValidationError::HopsExceeded)
        );

        record.hop_count = MAX_HOPS - 1;
        assert!(record.validate_durable(now_ms).is_ok());
    }

    /// `next_hop` сохраняет абсолютный дедлайн (TTL не продлевается пересылкой).
    #[test]
    fn test_next_hop_preserves_absolute_expiry() {
        let now_ms = 1_700_000_000_000i64;
        let m = RelayMessage::with_ttl_at_ms(
            now_ms,
            "m1".into(),
            "pk_b".into(),
            "pk_o".into(),
            "c".into(),
            vec![1],
            Duration::from_secs(600),
        );
        let next = m.next_hop().unwrap();
        assert_eq!(next.hop_count, 1);
        assert_eq!(next.expires_at_ms, m.expires_at_ms);
        assert_eq!(next.created_at_ms, m.created_at_ms);
    }

    /// Clock-before-epoch и граничные значения не вызывают panic.
    #[test]
    fn test_clock_before_epoch_and_saturation_no_panic() {
        // Системный helper никогда не отрицательный (до epoch → 0).
        assert!(utc_now_ms() >= 0);

        // Нулевые «часы» — безопасное создание/проверка/истечение.
        let m = RelayMessage::with_ttl_at_ms(
            0,
            "m1".into(),
            "pk_b".into(),
            "pk_o".into(),
            "c".into(),
            vec![1],
            Duration::from_secs(5),
        );
        assert_eq!(m.created_at_ms, 0);
        assert_eq!(m.expires_at_ms, 5_000);
        assert!(!m.is_expired_at(0));
        assert!(m.is_expired_at(5_000));
        assert!(RelayMessage::from_persisted(m, 0).is_ok());

        // Огромный TTL saturate в i64::MAX вместо переполнения.
        let huge = RelayMessage::with_ttl_at_ms(
            0,
            "m2".into(),
            "pk_b".into(),
            "pk_o".into(),
            "c".into(),
            vec![1],
            Duration::from_secs(u64::MAX),
        );
        assert_eq!(huge.expires_at_ms, i64::MAX);
        assert!(!huge.is_expired_at(i64::MAX - 1));
    }

    /// Пустые/oversized метаданные и payload отклоняются; границы — принимаются.
    #[test]
    fn test_validation_rejects_empty_and_oversized_fields() {
        let now_ms = 1_700_000_000_000i64;
        let base = RelayMessage::new_at_ms(
            now_ms,
            "m1".into(),
            "pk_b".into(),
            "pk_o".into(),
            "c".into(),
            vec![1],
        );

        let mut r = base.clone();
        r.msg_id = String::new();
        assert_eq!(r.validate_durable(now_ms), Err(RelayValidationError::EmptyMessageId));

        let mut r = base.clone();
        r.msg_id = "x".repeat(MAX_MESSAGE_ID_BYTES + 1);
        assert_eq!(
            r.validate_durable(now_ms),
            Err(RelayValidationError::MessageIdTooLong {
                max: MAX_MESSAGE_ID_BYTES
            })
        );

        let mut r = base.clone();
        r.recipient = String::new();
        assert_eq!(r.validate_durable(now_ms), Err(RelayValidationError::EmptyRecipient));

        let mut r = base.clone();
        r.recipient = "y".repeat(MAX_NODE_ID_BYTES + 1);
        assert_eq!(
            r.validate_durable(now_ms),
            Err(RelayValidationError::RecipientTooLong {
                max: MAX_NODE_ID_BYTES
            })
        );

        let mut r = base.clone();
        r.origin_sender = String::new();
        assert_eq!(r.validate_durable(now_ms), Err(RelayValidationError::EmptyOrigin));

        let mut r = base.clone();
        r.origin_sender = "z".repeat(MAX_NODE_ID_BYTES + 1);
        assert_eq!(
            r.validate_durable(now_ms),
            Err(RelayValidationError::OriginTooLong {
                max: MAX_NODE_ID_BYTES
            })
        );

        let mut r = base.clone();
        r.chat_scope = String::new();
        assert_eq!(r.validate_durable(now_ms), Err(RelayValidationError::EmptyChatScope));

        let mut r = base.clone();
        r.chat_scope = "s".repeat(MAX_CHAT_SCOPE_BYTES + 1);
        assert_eq!(
            r.validate_durable(now_ms),
            Err(RelayValidationError::ChatScopeTooLong {
                max: MAX_CHAT_SCOPE_BYTES
            })
        );

        let mut r = base.clone();
        r.e2e_payload.clear();
        assert_eq!(r.validate_durable(now_ms), Err(RelayValidationError::EmptyPayload));

        let mut r = base.clone();
        r.e2e_payload = vec![7u8; MAX_MESH_RELAY_ENVELOPE_BYTES + 1];
        assert_eq!(
            r.validate_durable(now_ms),
            Err(RelayValidationError::PayloadTooLarge {
                max: MAX_MESH_RELAY_ENVELOPE_BYTES
            })
        );

        // Точно на границах запись принимается.
        let mut r = base;
        r.msg_id = "x".repeat(MAX_MESSAGE_ID_BYTES);
        r.e2e_payload = vec![7u8; MAX_MESH_RELAY_ENVELOPE_BYTES];
        assert!(r.validate_durable(now_ms).is_ok());
    }

    /// Wire-разделитель `|` в метаданных отклоняется (запись не переживёт
    /// повторную отправку через `relay|...` envelope).
    #[test]
    fn test_validation_rejects_wire_delimiter_in_metadata() {
        let now_ms = 1_700_000_000_000i64;
        let mut r = RelayMessage::new_at_ms(
            now_ms,
            "m1".into(),
            "pk_b".into(),
            "pk_o".into(),
            "c".into(),
            vec![1],
        );
        r.chat_scope = "chat|1".into();
        assert_eq!(
            r.validate_durable(now_ms),
            Err(RelayValidationError::MetadataContainsDelimiter {
                field: "chat_scope"
            })
        );
    }

    /// Конвертация Duration → ms безопасна и точна для типовых значений.
    #[test]
    fn test_ttl_to_ms_conversion() {
        assert_eq!(ttl_to_ms(DEFAULT_RELAY_TTL), 7 * 24 * 3600 * 1000);
        assert_eq!(ttl_to_ms(Duration::from_millis(0)), 0);
        assert_eq!(ttl_to_ms(Duration::from_millis(1)), 1);
        assert_eq!(ttl_to_ms(Duration::MAX), i64::MAX);
    }

    /// Детерминированный cleanup на фиксированных часах.
    #[test]
    fn test_cleanup_expired_at_fixed_clock() {
        let q = RelayQueue::new();
        let created = 1_000_000i64;
        q.enqueue(RelayMessage::with_ttl_at_ms(
            created,
            "m1".into(),
            "pk_b".into(),
            "o".into(),
            "c".into(),
            vec![1],
            Duration::from_millis(500),
        ))
        .unwrap();
        q.enqueue(RelayMessage::with_ttl_at_ms(
            created,
            "m2".into(),
            "pk_b".into(),
            "o".into(),
            "c".into(),
            vec![1],
            Duration::from_secs(60),
        ))
        .unwrap();

        assert_eq!(q.cleanup_expired_at(created + 499), 0);
        assert_eq!(q.cleanup_expired_at(created + 500), 1);
        assert!(!q.contains("m1"));
        assert!(q.contains("m2"));
    }
}
