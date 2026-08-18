//! # Message Queue — Store-and-Forward для офлайн-узлов
//!
//! Если получатель офлайн — сохраняем сообщение и доставляем при появлении.
//!
//! ## Кто использует эту очередь:
//!
//! - **Наш узел** — сохраняет исходящие сообщения если получатель офлайн
//! - **Tier 1 Super Node** — хранит сообщения для других узлов
//!   (принимает `RelayReq` для офлайн-получателей)
//!
//! ## Ограничения:
//!
//! - **TTL сообщения**: 7 дней (потом удаляется автоматически)
//! - **Retry counter**: макс 10 попыток доставки
//! - **Лимит на узел**: 1000 сообщений (защита от DoS)
//! - **Общий лимит**: 100_000 сообщений (защита памяти)
//!
//! ## Что делает этот модуль в MVP:
//!
//! - Хранит очередь в памяти (`HashMap<NodeID, Vec<QueuedMessage>>`)
//! - API для добавления, получения, удаления
//! - Автоматическая очистка просроченных
//!
//! ## Персистентность (этап M8)
//!
//! Очередь сама по себе живёт в RAM. Постоянное зашифрованное хранение
//! («custody») добавлено в модуле `custody`: `export_snapshots()` /
//! `restore()` переводят сообщения в сериализуемые снапшоты с абсолютным
//! wall-clock deadline, поэтому TTL переживает перезапуск процесса и не
//! сбрасывается.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::time::{Duration, Instant};

use tokio::sync::Mutex;

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// TTL сообщения по умолчанию (7 дней).
pub const DEFAULT_MESSAGE_TTL: Duration = Duration::from_secs(7 * 24 * 3600);

/// Максимальное число попыток доставки.
pub const MAX_RETRY_COUNT: u32 = 10;

/// Максимальное число сообщений на одного получателя.
pub const MAX_MESSAGES_PER_RECIPIENT: usize = 1000;

/// Максимальное общее число сообщений в очереди.
pub const MAX_TOTAL_MESSAGES: usize = 100_000;

// ═══════════════════════════════════════════════════════════════════
// ОШИБКИ
// ═══════════════════════════════════════════════════════════════════

#[derive(Debug, thiserror::Error)]
pub enum QueueError {
    #[error("Очередь получателя переполнена (максимум {max} сообщений)")]
    RecipientQueueFull { max: usize },

    #[error("Общая очередь переполнена (максимум {max} сообщений)")]
    GlobalQueueFull { max: usize },

    #[error("Превышен максимум попыток доставки ({max})")]
    MaxRetriesExceeded { max: u32 },
}

pub type QueueResult<T> = Result<T, QueueError>;

// ═══════════════════════════════════════════════════════════════════
// QUEUED MESSAGE
// ═══════════════════════════════════════════════════════════════════

/// Одно сообщение в очереди.
#[derive(Debug, Clone)]
pub struct QueuedMessage {
    /// Уникальный ID сообщения.
    pub msg_id: [u8; 16],

    /// NodeID получателя.
    pub recipient: [u8; 32],

    /// Зашифрованный payload — то что нужно доставить.
    pub payload: Vec<u8>,

    /// Когда сообщение поставлено в очередь.
    pub queued_at: Instant,

    /// Сколько попыток доставки уже было.
    pub retry_count: u32,

    /// TTL — когда сообщение истекает.
    pub expires_at: Instant,
}

impl QueuedMessage {
    /// Создать новое сообщение с TTL по умолчанию.
    pub fn new(msg_id: [u8; 16], recipient: [u8; 32], payload: Vec<u8>) -> Self {
        let now = Instant::now();
        QueuedMessage {
            msg_id,
            recipient,
            payload,
            queued_at: now,
            retry_count: 0,
            expires_at: now + DEFAULT_MESSAGE_TTL,
        }
    }

    /// Создать с кастомным TTL.
    pub fn with_ttl(
        msg_id: [u8; 16],
        recipient: [u8; 32],
        payload: Vec<u8>,
        ttl: Duration,
    ) -> Self {
        let now = Instant::now();
        QueuedMessage {
            msg_id,
            recipient,
            payload,
            queued_at: now,
            retry_count: 0,
            expires_at: now + ttl,
        }
    }

    /// Просрочено ли сообщение.
    pub fn is_expired(&self) -> bool {
        Instant::now() >= self.expires_at
    }

    /// Превышены ли попытки доставки.
    pub fn max_retries_exceeded(&self) -> bool {
        self.retry_count >= MAX_RETRY_COUNT
    }

    /// Увеличить счётчик попыток.
    pub fn increment_retry(&mut self) {
        self.retry_count += 1;
    }

    /// Преобразовать в сериализуемый снапшот (для сохранения custody на диск).
    ///
    /// `Instant` не переживает перезапуск процесса, поэтому снапшот хранит
    /// абсолютное wall-clock время в миллисекундах Unix. Оставшийся TTL
    /// при этом сохраняется.
    pub fn to_snapshot(&self) -> QueuedMessageSnapshot {
        QueuedMessageSnapshot {
            msg_id: self.msg_id,
            recipient: self.recipient,
            payload: self.payload.clone(),
            queued_at_ms: self.absolute_queued_ms(),
            expires_at_ms: self.absolute_deadline_ms(),
            retry_count: self.retry_count,
        }
    }

    /// Восстановить сообщение из снапшота, сохранив исходный абсолютный
    /// deadline (TTL не сбрасывается). Если сообщение уже просрочено,
    /// возвращаемый объект сразу считается истёкшим.
    pub fn from_snapshot(snapshot: QueuedMessageSnapshot) -> Self {
        let now = Instant::now();
        let now_ms = crate::storage::models::now_ms();
        let remaining_ms = snapshot.expires_at_ms.saturating_sub(now_ms).max(0) as u64;
        let elapsed_ms = now_ms.saturating_sub(snapshot.queued_at_ms).max(0) as u64;

        QueuedMessage {
            msg_id: snapshot.msg_id,
            recipient: snapshot.recipient,
            payload: snapshot.payload,
            queued_at: now
                .checked_sub(Duration::from_millis(elapsed_ms))
                .unwrap_or(now),
            expires_at: now
                .checked_add(Duration::from_millis(remaining_ms))
                .unwrap_or(now),
            retry_count: snapshot.retry_count,
        }
    }

    /// Абсолютный deadline (Unix ms) с сохранением оставшегося TTL.
    fn absolute_deadline_ms(&self) -> i64 {
        let now = Instant::now();
        let now_ms = crate::storage::models::now_ms();
        let remaining = self.expires_at.saturating_duration_since(now);
        now_ms.saturating_add(remaining.as_millis().min(i64::MAX as u128) as i64)
    }

    /// Абсолютное время постановки в очередь (Unix ms).
    fn absolute_queued_ms(&self) -> i64 {
        let now = Instant::now();
        let now_ms = crate::storage::models::now_ms();
        let elapsed = now.saturating_duration_since(self.queued_at);
        now_ms.saturating_sub(elapsed.as_millis().min(i64::MAX as u128) as i64)
    }
}

/// Сериализуемый снапшот одного сообщения очереди.
///
/// В отличие от [`QueuedMessage`] (который хранит `Instant`), снапшот хранит
/// абсолютное wall-clock время в миллисекундах Unix. Благодаря этому TTL
/// сообщений переживает перезапуск процесса и не сбрасывается при
/// восстановлении зашифрованной custody (этап M8).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct QueuedMessageSnapshot {
    pub msg_id: [u8; 16],
    pub recipient: [u8; 32],
    pub payload: Vec<u8>,
    /// Время постановки в очередь (Unix ms).
    pub queued_at_ms: i64,
    /// Абсолютный deadline (Unix ms) — когда сообщение истекает.
    pub expires_at_ms: i64,
    /// Число уже сделанных попыток доставки.
    pub retry_count: u32,
}

// ═══════════════════════════════════════════════════════════════════
// MESSAGE QUEUE
// ═══════════════════════════════════════════════════════════════════

/// Store-and-Forward очередь для сообщений.
pub struct MessageQueue {
    /// Сообщения группированы по получателю для быстрого доступа.
    queue: Mutex<HashMap<[u8; 32], Vec<QueuedMessage>>>,

    /// Максимум сообщений на одного получателя.
    max_per_recipient: usize,

    /// Максимум общее число сообщений.
    max_total: usize,
}

impl MessageQueue {
    /// Создать новую очередь с лимитами по умолчанию.
    pub fn new() -> Self {
        MessageQueue {
            queue: Mutex::new(HashMap::new()),
            max_per_recipient: MAX_MESSAGES_PER_RECIPIENT,
            max_total: MAX_TOTAL_MESSAGES,
        }
    }

    /// Создать с кастомными лимитами (для тестов).
    pub fn with_limits(max_per_recipient: usize, max_total: usize) -> Self {
        MessageQueue {
            queue: Mutex::new(HashMap::new()),
            max_per_recipient,
            max_total,
        }
    }

    // ─── Добавление ────────────────────────────────────────────────

    /// Поставить сообщение в очередь.
    ///
    /// # Ошибки
    /// - `RecipientQueueFull` — превышен лимит для этого получателя
    /// - `GlobalQueueFull` — превышен общий лимит
    pub async fn enqueue(&self, message: QueuedMessage) -> QueueResult<()> {
        let mut queue = self.queue.lock().await;

        // Проверка общего лимита
        let total: usize = queue.values().map(|v| v.len()).sum();
        if total >= self.max_total {
            return Err(QueueError::GlobalQueueFull {
                max: self.max_total,
            });
        }

        // Проверка per-recipient лимита
        let recipient_queue = queue.entry(message.recipient).or_default();
        if recipient_queue.len() >= self.max_per_recipient {
            return Err(QueueError::RecipientQueueFull {
                max: self.max_per_recipient,
            });
        }

        recipient_queue.push(message);
        Ok(())
    }

    // ─── Получение ─────────────────────────────────────────────────

    /// Получить все сообщения для указанного получателя.
    /// Сообщения НЕ удаляются из очереди (используйте `dequeue_for`).
    pub async fn peek_for(&self, recipient: &[u8; 32]) -> Vec<QueuedMessage> {
        self.queue
            .lock()
            .await
            .get(recipient)
            .cloned()
            .unwrap_or_default()
    }

    /// Забрать все сообщения для получателя и удалить из очереди.
    /// Используется когда мы уверены что сможем доставить (получатель онлайн).
    pub async fn dequeue_for(&self, recipient: &[u8; 32]) -> Vec<QueuedMessage> {
        let mut queue = self.queue.lock().await;
        queue.remove(recipient).unwrap_or_default()
    }

    /// Удалить конкретное сообщение по msg_id.
    /// Возвращает `true` если найдено и удалено.
    pub async fn remove_message(&self, recipient: &[u8; 32], msg_id: &[u8; 16]) -> bool {
        let mut queue = self.queue.lock().await;
        if let Some(messages) = queue.get_mut(recipient) {
            if let Some(pos) = messages.iter().position(|m| &m.msg_id == msg_id) {
                messages.remove(pos);
                // Удаляем пустой Vec чтобы не копить мусор
                if messages.is_empty() {
                    queue.remove(recipient);
                }
                return true;
            }
        }
        false
    }

    // ─── Retry management ──────────────────────────────────────────

    /// Пометить попытку доставки как неудачную (увеличить retry_count).
    /// Если превышен лимит — удаляет сообщение и возвращает `Err(MaxRetriesExceeded)`.
    pub async fn mark_failed_delivery(
        &self,
        recipient: &[u8; 32],
        msg_id: &[u8; 16],
    ) -> QueueResult<u32> {
        let mut queue = self.queue.lock().await;
        if let Some(messages) = queue.get_mut(recipient) {
            if let Some(msg) = messages.iter_mut().find(|m| &m.msg_id == msg_id) {
                msg.increment_retry();

                if msg.max_retries_exceeded() {
                    // Удаляем и возвращаем ошибку
                    let count = msg.retry_count;
                    messages.retain(|m| &m.msg_id != msg_id);
                    if messages.is_empty() {
                        queue.remove(recipient);
                    }
                    return Err(QueueError::MaxRetriesExceeded { max: count });
                }

                return Ok(msg.retry_count);
            }
        }
        Ok(0) // Сообщение не найдено — ничего не делаем
    }

    // ─── Cleanup ────────────────────────────────────────────────────

    /// Удалить все просроченные сообщения.
    /// Возвращает число удалённых.
    pub async fn cleanup_expired(&self) -> usize {
        let mut queue = self.queue.lock().await;
        let mut removed = 0;

        // Проходим по каждому получателю
        let recipients: Vec<[u8; 32]> = queue.keys().copied().collect();
        for recipient in recipients {
            if let Some(messages) = queue.get_mut(&recipient) {
                let before = messages.len();
                messages.retain(|m| !m.is_expired());
                removed += before - messages.len();

                if messages.is_empty() {
                    queue.remove(&recipient);
                }
            }
        }

        removed
    }

    // ─── Статистика ────────────────────────────────────────────────

    /// Общее число сообщений в очереди.
    pub async fn total_count(&self) -> usize {
        self.queue.lock().await.values().map(|v| v.len()).sum()
    }

    /// Число сообщений для конкретного получателя.
    pub async fn count_for(&self, recipient: &[u8; 32]) -> usize {
        self.queue
            .lock()
            .await
            .get(recipient)
            .map(|v| v.len())
            .unwrap_or(0)
    }

    /// Число уникальных получателей в очереди.
    pub async fn recipient_count(&self) -> usize {
        self.queue.lock().await.len()
    }

    /// Есть ли сообщения для получателя.
    pub async fn has_messages_for(&self, recipient: &[u8; 32]) -> bool {
        self.count_for(recipient).await > 0
    }

    // ─── Persistence / custody (M8) ───────────────────────────────

    /// Экспортировать все сообщения очереди в сериализуемые снапшоты.
    ///
    /// Используется для зашифрованного сохранения relay-custody перед «сном»
    /// (остановкой приложения / завершением процесса).
    pub async fn export_snapshots(&self) -> Vec<QueuedMessageSnapshot> {
        let queue = self.queue.lock().await;
        let mut snapshots = Vec::new();
        for messages in queue.values() {
            for msg in messages {
                snapshots.push(msg.to_snapshot());
            }
        }
        snapshots
    }

    /// Восстановить очередь из снапшотов после перезапуска.
    ///
    /// Просроченные сообщения отбрасываются; у остальных сохраняется
    /// исходный абсолютный deadline (TTL не сбрасывается). Возвращает число
    /// восстановленных сообщений.
    pub async fn restore(&self, snapshots: Vec<QueuedMessageSnapshot>) -> usize {
        let now_ms = crate::storage::models::now_ms();
        let mut restored = 0;
        for snapshot in snapshots {
            if snapshot.expires_at_ms <= now_ms {
                continue; // Просрочено — не восстанавливаем
            }
            let msg = QueuedMessage::from_snapshot(snapshot);
            if self.enqueue(msg).await.is_ok() {
                restored += 1;
            }
        }
        restored
    }
}

impl Default for MessageQueue {
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

    fn make_recipient(byte: u8) -> [u8; 32] {
        [byte; 32]
    }

    fn make_msg_id(byte: u8) -> [u8; 16] {
        [byte; 16]
    }

    fn make_message(recipient_byte: u8, msg_id_byte: u8) -> QueuedMessage {
        QueuedMessage::new(
            make_msg_id(msg_id_byte),
            make_recipient(recipient_byte),
            vec![0xAA; 100],
        )
    }

    // ── Базовые ────────────────────────────────────────────────────

    #[tokio::test]
    async fn test_new_queue_empty() {
        let q = MessageQueue::new();
        assert_eq!(q.total_count().await, 0);
        assert_eq!(q.recipient_count().await, 0);
        println!("✅ Новая очередь пустая");
    }

    #[tokio::test]
    async fn test_enqueue_and_peek() {
        let q = MessageQueue::new();
        let msg = make_message(0x42, 0x01);

        q.enqueue(msg.clone()).await.unwrap();

        assert_eq!(q.total_count().await, 1);
        assert_eq!(q.count_for(&make_recipient(0x42)).await, 1);

        let peeked = q.peek_for(&make_recipient(0x42)).await;
        assert_eq!(peeked.len(), 1);
        assert_eq!(peeked[0].msg_id, make_msg_id(0x01));

        // peek не удаляет
        assert_eq!(q.count_for(&make_recipient(0x42)).await, 1);
        println!("✅ enqueue + peek работают");
    }

    #[tokio::test]
    async fn test_dequeue_removes_messages() {
        let q = MessageQueue::new();

        for i in 1..=5u8 {
            q.enqueue(make_message(0x42, i)).await.unwrap();
        }

        let dequeued = q.dequeue_for(&make_recipient(0x42)).await;
        assert_eq!(dequeued.len(), 5);
        assert_eq!(q.count_for(&make_recipient(0x42)).await, 0);
        assert_eq!(q.total_count().await, 0);
        println!("✅ dequeue удаляет все сообщения");
    }

    #[tokio::test]
    async fn test_multiple_recipients() {
        let q = MessageQueue::new();

        // 3 сообщения для получателя A
        for i in 1..=3u8 {
            q.enqueue(make_message(0x01, i)).await.unwrap();
        }

        // 2 сообщения для получателя B
        for i in 10..=11u8 {
            q.enqueue(make_message(0x02, i)).await.unwrap();
        }

        assert_eq!(q.total_count().await, 5);
        assert_eq!(q.recipient_count().await, 2);
        assert_eq!(q.count_for(&make_recipient(0x01)).await, 3);
        assert_eq!(q.count_for(&make_recipient(0x02)).await, 2);
        println!("✅ Несколько получателей работают независимо");
    }

    #[tokio::test]
    async fn test_remove_specific_message() {
        let q = MessageQueue::new();

        for i in 1..=3u8 {
            q.enqueue(make_message(0x42, i)).await.unwrap();
        }

        // Удаляем среднее сообщение
        assert!(
            q.remove_message(&make_recipient(0x42), &make_msg_id(2))
                .await
        );
        assert_eq!(q.count_for(&make_recipient(0x42)).await, 2);

        // Пытаемся удалить его же снова — false
        assert!(
            !q.remove_message(&make_recipient(0x42), &make_msg_id(2))
                .await
        );
        println!("✅ remove_message удаляет конкретное сообщение");
    }

    // ── Лимиты ─────────────────────────────────────────────────────

    #[tokio::test]
    async fn test_recipient_limit() {
        // Очередь с очень маленьким лимитом
        let q = MessageQueue::with_limits(3, 100);

        for i in 1..=3u8 {
            q.enqueue(make_message(0x42, i)).await.unwrap();
        }

        // Четвёртое — превышение
        let result = q.enqueue(make_message(0x42, 4)).await;
        assert!(matches!(
            result,
            Err(QueueError::RecipientQueueFull { max: 3 })
        ));
        println!("✅ Лимит per-recipient работает");
    }

    #[tokio::test]
    async fn test_global_limit() {
        let q = MessageQueue::with_limits(1000, 3);

        // Разные получатели, но общий лимит 3
        q.enqueue(make_message(0x01, 1)).await.unwrap();
        q.enqueue(make_message(0x02, 2)).await.unwrap();
        q.enqueue(make_message(0x03, 3)).await.unwrap();

        let result = q.enqueue(make_message(0x04, 4)).await;
        assert!(matches!(
            result,
            Err(QueueError::GlobalQueueFull { max: 3 })
        ));
        println!("✅ Общий лимит работает");
    }

    // ── Retry management ───────────────────────────────────────────

    #[tokio::test]
    async fn test_mark_failed_increments_retry() {
        let q = MessageQueue::new();
        q.enqueue(make_message(0x42, 1)).await.unwrap();

        let count = q
            .mark_failed_delivery(&make_recipient(0x42), &make_msg_id(1))
            .await
            .unwrap();
        assert_eq!(count, 1);

        let count = q
            .mark_failed_delivery(&make_recipient(0x42), &make_msg_id(1))
            .await
            .unwrap();
        assert_eq!(count, 2);

        // Сообщение всё ещё в очереди
        assert_eq!(q.count_for(&make_recipient(0x42)).await, 1);
        println!("✅ retry_count увеличивается");
    }

    #[tokio::test]
    async fn test_max_retries_removes_message() {
        let q = MessageQueue::new();
        q.enqueue(make_message(0x42, 1)).await.unwrap();

        // Делаем MAX_RETRY_COUNT попыток
        for _ in 0..MAX_RETRY_COUNT - 1 {
            q.mark_failed_delivery(&make_recipient(0x42), &make_msg_id(1))
                .await
                .unwrap();
        }

        // Последняя попытка — превышение
        let result = q
            .mark_failed_delivery(&make_recipient(0x42), &make_msg_id(1))
            .await;
        assert!(matches!(result, Err(QueueError::MaxRetriesExceeded { .. })));

        // Сообщение удалено
        assert_eq!(q.count_for(&make_recipient(0x42)).await, 0);
        println!("✅ Превышение MAX_RETRY удаляет сообщение");
    }

    // ── TTL / Expiration ────────────────────────────────────────────

    #[tokio::test]
    async fn test_message_expiration() {
        let q = MessageQueue::new();

        // Сообщение с очень маленьким TTL
        let msg = QueuedMessage::with_ttl(
            make_msg_id(1),
            make_recipient(0x42),
            vec![0; 10],
            Duration::from_millis(50),
        );
        // Прямая проверка is_expired на свежем — false
        assert!(!msg.is_expired());

        q.enqueue(msg.clone()).await.unwrap();
        assert_eq!(q.total_count().await, 1);

        // Ждём чтобы истёк
        tokio::time::sleep(Duration::from_millis(100)).await;

        let removed = q.cleanup_expired().await;
        assert_eq!(removed, 1);
        assert_eq!(q.total_count().await, 0);
        println!("✅ Просроченные сообщения удаляются");
    }

    #[tokio::test]
    async fn test_cleanup_keeps_fresh() {
        let q = MessageQueue::new();

        for i in 1..=5u8 {
            q.enqueue(make_message(0x42, i)).await.unwrap();
        }

        // TTL по умолчанию 7 дней — все свежие
        let removed = q.cleanup_expired().await;
        assert_eq!(removed, 0);
        assert_eq!(q.total_count().await, 5);
        println!("✅ Cleanup сохраняет свежие сообщения");
    }

    // ── Утилиты ────────────────────────────────────────────────────

    #[tokio::test]
    async fn test_has_messages_for() {
        let q = MessageQueue::new();

        assert!(!q.has_messages_for(&make_recipient(0x42)).await);

        q.enqueue(make_message(0x42, 1)).await.unwrap();
        assert!(q.has_messages_for(&make_recipient(0x42)).await);

        q.dequeue_for(&make_recipient(0x42)).await;
        assert!(!q.has_messages_for(&make_recipient(0x42)).await);
        println!("✅ has_messages_for работает");
    }

    #[tokio::test]
    async fn test_queued_message_creation() {
        let msg = QueuedMessage::new(make_msg_id(1), make_recipient(0x42), vec![0xAA; 100]);

        assert_eq!(msg.msg_id, make_msg_id(1));
        assert_eq!(msg.recipient, make_recipient(0x42));
        assert_eq!(msg.retry_count, 0);
        assert!(!msg.is_expired());
        assert!(!msg.max_retries_exceeded());
        println!("✅ QueuedMessage создаётся с правильными полями");
    }

    #[test]
    fn test_ttl_default_reasonable() {
        // 7 дней = 604800 секунд
        assert_eq!(DEFAULT_MESSAGE_TTL.as_secs(), 7 * 24 * 3600);
        println!("✅ TTL по умолчанию = 7 дней");
    }

    #[test]
    fn test_max_retry_reasonable() {
        assert!(MAX_RETRY_COUNT >= 3);
        assert!(MAX_RETRY_COUNT <= 100);
        println!("✅ MAX_RETRY_COUNT разумный: {}", MAX_RETRY_COUNT);
    }

    // ── Persistence / custody (M8) ────────────────────────────────

    #[tokio::test]
    async fn test_export_restore_roundtrip_preserves_ttl() {
        let q = MessageQueue::new();
        q.enqueue(make_message(0x42, 0x01)).await.unwrap();

        let snapshots = q.export_snapshots().await;
        assert_eq!(snapshots.len(), 1);

        // Полный TTL ≈ 7 дней (на момент экспорта).
        let ttl_ms = snapshots[0].expires_at_ms - snapshots[0].queued_at_ms;
        assert!(
            ttl_ms > 6 * 24 * 3600 * 1000,
            "TTL должен быть ~7 дней, получили {}",
            ttl_ms
        );

        // Восстанавливаем в новую очередь (имитация перезапуска).
        let q2 = MessageQueue::new();
        let restored = q2.restore(snapshots.clone()).await;
        assert_eq!(restored, 1);

        // Deadline не изменился — TTL не сброшен.
        let snapshots2 = q2.export_snapshots().await;
        assert_eq!(snapshots2.len(), 1);
        let drift = (snapshots2[0].expires_at_ms - snapshots[0].expires_at_ms).abs();
        assert!(
            drift <= 100,
            "Deadline сдвинулся на {} ms — TTL сброшен",
            drift
        );
    }

    #[tokio::test]
    async fn test_restore_filters_expired() {
        let q = MessageQueue::new();
        let now = crate::storage::models::now_ms();

        let expired = QueuedMessageSnapshot {
            msg_id: make_msg_id(1),
            recipient: make_recipient(0x42),
            payload: vec![1, 2, 3],
            queued_at_ms: now - 10_000,
            expires_at_ms: now - 1, // уже истекло
            retry_count: 5,
        };
        let fresh = QueuedMessageSnapshot {
            msg_id: make_msg_id(2),
            recipient: make_recipient(0x42),
            payload: vec![4, 5, 6],
            queued_at_ms: now,
            expires_at_ms: now + 60_000, // 60 секунд осталось
            retry_count: 3,
        };

        let restored = q.restore(vec![expired, fresh]).await;
        assert_eq!(restored, 1);

        let messages = q.peek_for(&make_recipient(0x42)).await;
        assert_eq!(messages.len(), 1);
        assert_eq!(messages[0].msg_id, make_msg_id(2));
        assert_eq!(messages[0].retry_count, 3);
    }
}
