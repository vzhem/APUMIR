//! Store-and-Forward Module for Offline Message Delivery

use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};
use tokio::sync::Mutex;

pub const DEFAULT_TTL_SECONDS: u64 = 7 * 24 * 60 * 60;
pub const MAX_STORED_MESSAGES_PER_RECIPIENT: usize = 1000;
pub const MAX_TOTAL_STORED_MESSAGES: usize = 10000;

#[derive(Debug, Clone)]
pub struct StoredMessage {
    pub message_id: String,
    pub encrypted_payload: Vec<u8>,
    pub stored_at: Instant,
    pub expires_at: Instant,
    pub delivery_attempts: u32,
    pub last_attempt: Option<Instant>,
}

impl StoredMessage {
    pub fn new(message_id: String, encrypted_payload: Vec<u8>, ttl_seconds: u64) -> Self {
        let now = Instant::now();
        Self {
            message_id,
            encrypted_payload,
            stored_at: now,
            expires_at: now + Duration::from_secs(ttl_seconds),
            delivery_attempts: 0,
            last_attempt: None,
        }
    }
    
    pub fn is_expired(&self) -> bool {
        Instant::now() >= self.expires_at
    }
    
    pub fn should_retry(&self, min_retry_interval: Duration) -> bool {
        if self.is_expired() { return false; }
        match self.last_attempt {
            None => true,
            Some(last) => Instant::now() >= last + min_retry_interval,
        }
    }
    
    pub fn mark_attempted(&mut self) {
        self.delivery_attempts += 1;
        self.last_attempt = Some(Instant::now());
    }
}

#[derive(Debug, Clone)]
pub struct RecipientQueue {
    pub messages: VecDeque<StoredMessage>,
    pub total_bytes: usize,
}

impl RecipientQueue {
    pub fn new() -> Self {
        Self { messages: VecDeque::new(), total_bytes: 0 }
    }
    
    pub fn push(&mut self, msg: StoredMessage) -> bool {
        if self.messages.len() >= MAX_STORED_MESSAGES_PER_RECIPIENT {
            if let Some(oldest) = self.messages.pop_front() {
                self.total_bytes = self.total_bytes.saturating_sub(oldest.encrypted_payload.len());
            }
        }
        self.total_bytes += msg.encrypted_payload.len();
        self.messages.push_back(msg);
        true
    }
    
    pub fn pop_expired(&mut self) -> Vec<StoredMessage> {
        let now = Instant::now();
        let mut expired = Vec::new();
        while let Some(front) = self.messages.front() {
            if front.expires_at <= now {
                if let Some(msg) = self.messages.pop_front() {
                    self.total_bytes = self.total_bytes.saturating_sub(msg.encrypted_payload.len());
                    expired.push(msg);
                }
            } else { break; }
        }
        expired
    }
    
    pub fn get_ready_for_delivery(&mut self, min_retry_interval: Duration) -> Vec<StoredMessage> {
        let mut ready = Vec::new();
        for msg in self.messages.iter_mut() {
            if msg.should_retry(min_retry_interval) {
                ready.push(msg.clone());
                msg.mark_attempted();
            }
        }
        ready
    }
}

#[derive(Debug, Clone, Default)]
pub struct StoreForwardStats {
    pub total_messages_stored: u64,
    pub total_messages_delivered: u64,
    pub total_messages_expired: u64,
    pub current_queue_size: usize,
    pub total_bytes_stored: usize,
}

pub struct StoreAndForwardManager {
    our_node_id: [u8; 32],
    queues: Mutex<HashMap<[u8; 32], RecipientQueue>>,
    stats: Mutex<StoreForwardStats>,
    min_retry_interval: Duration,
    default_ttl: Duration,
}

#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error("Cannot store messages for self")]
    CannotStoreForSelf,
    #[error("Storage capacity exceeded")]
    StorageFull,
    #[error("Invalid recipient ID")]
    InvalidRecipient,
}

impl StoreAndForwardManager {
    pub fn new(our_node_id: [u8; 32]) -> Self {
        Self {
            our_node_id,
            queues: Mutex::new(HashMap::new()),
            stats: Mutex::new(StoreForwardStats::default()),
            min_retry_interval: Duration::from_secs(30),
            default_ttl: Duration::from_secs(DEFAULT_TTL_SECONDS),
        }
    }
    
    pub async fn store_message(&self, recipient_id: [u8; 32], message_id: String, encrypted_payload: Vec<u8>) -> Result<(), StoreError> {
        if recipient_id == self.our_node_id {
            return Err(StoreError::CannotStoreForSelf);
        }
        let mut queues = self.queues.lock().await;
        let mut stats = self.stats.lock().await;
        if stats.current_queue_size >= MAX_TOTAL_STORED_MESSAGES {
            drop(stats);
            self.cleanup_expired().await;
            stats = self.stats.lock().await;
            if stats.current_queue_size >= MAX_TOTAL_STORED_MESSAGES {
                return Err(StoreError::StorageFull);
            }
        }
        let queue = queues.entry(recipient_id).or_insert_with(RecipientQueue::new);
        let stored_msg = StoredMessage::new(message_id, encrypted_payload, self.default_ttl.as_secs());
        queue.push(stored_msg);
        stats.total_messages_stored += 1;
        stats.current_queue_size = queues.values().map(|q| q.messages.len()).sum();
        stats.total_bytes_stored = queues.values().map(|q| q.total_bytes).sum();
        Ok(())
    }
    
    pub async fn get_pending_messages(&self, recipient_id: [u8; 32]) -> Vec<StoredMessage> {
        let mut queues = self.queues.lock().await;
        if let Some(queue) = queues.get_mut(&recipient_id) {
            let expired = queue.pop_expired();
            {
                let mut stats = self.stats.lock().await;
                stats.total_messages_expired += expired.len() as u64;
                stats.current_queue_size = queues.values().map(|q| q.messages.len()).sum();
                stats.total_bytes_stored = queues.values().map(|q| q.total_bytes).sum();
            }
            queue.get_ready_for_delivery(self.min_retry_interval)
        } else { Vec::new() }
    }
    
    pub async fn mark_delivered(&self, recipient_id: [u8; 32], message_ids: &[String]) -> usize {
        let mut queues = self.queues.lock().await;
        let mut stats = self.stats.lock().await;
        if let Some(queue) = queues.get_mut(&recipient_id) {
            let before_count = queue.messages.len();
            queue.messages.retain(|msg| !message_ids.contains(&msg.message_id));
            let removed_count = before_count - queue.messages.len();
            stats.total_messages_delivered += removed_count as u64;
            stats.current_queue_size = queues.values().map(|q| q.messages.len()).sum();
            stats.total_bytes_stored = queues.values().map(|q| q.total_bytes).sum();
            removed_count
        } else { 0 }
    }
    
    pub async fn cleanup_expired(&self) -> usize {
        let mut queues = self.queues.lock().await;
        let mut stats = self.stats.lock().await;
        let mut total_expired = 0;
        for queue in queues.values_mut() {
            let expired = queue.pop_expired();
            total_expired += expired.len();
        }
        stats.total_messages_expired += total_expired as u64;
        stats.current_queue_size = queues.values().map(|q| q.messages.len()).sum();
        stats.total_bytes_stored = queues.values().map(|q| q.total_bytes).sum();
        total_expired
    }
    
    pub async fn get_stats(&self) -> StoreForwardStats { self.stats.lock().await.clone() }
    pub async fn total_stored(&self) -> usize { let queues = self.queues.lock().await; queues.values().map(|q| q.messages.len()).sum() }
    pub async fn clear_all(&self) { let mut queues = self.queues.lock().await; let mut stats = self.stats.lock().await; queues.clear(); stats.current_queue_size = 0; stats.total_bytes_stored = 0; }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn make_id(byte: u8) -> [u8; 32] { [byte; 32] }
    
    #[tokio::test]
    async fn test_store_message() {
        let manager = StoreAndForwardManager::new(make_id(0x01));
        let result = manager.store_message(make_id(0x02), "msg_001".to_string(), vec![0x01, 0x02, 0x03]).await;
        assert!(result.is_ok());
        assert_eq!(manager.total_stored().await, 1);
        println!("OK: Message stored");
    }
    
    #[tokio::test]
    async fn test_cannot_store_for_self() {
        let manager = StoreAndForwardManager::new(make_id(0x01));
        let result = manager.store_message(make_id(0x01), "msg_001".to_string(), vec![0x01]).await;
        assert!(matches!(result, Err(StoreError::CannotStoreForSelf)));
        println!("OK: Rejects self-store");
    }
    
    #[tokio::test]
    async fn test_storage_capacity() {
        let manager = StoreAndForwardManager::new(make_id(0x01));
        for i in 0..MAX_STORED_MESSAGES_PER_RECIPIENT + 10 {
            manager.store_message(make_id(0x02), format!("msg_{:05}", i), vec![i as u8; 5]).await.unwrap();
        }
        assert_eq!(manager.total_stored().await, MAX_STORED_MESSAGES_PER_RECIPIENT);
        println!("OK: Capacity enforced");
    }
}
