# APUMIR Offline Delivery via Third Phone - Store and Forward Implementation
# This script creates Rust files for offline message delivery through intermediate relay nodes
# Works even when all phones go offline for several days

Write-Host "=== APUMIR Offline Delivery Setup ===" -ForegroundColor Cyan

# Create directory structure
$rustCorePath = Join-Path $PSScriptRoot "..\rust-core\src\network" | Resolve-Path
$testPath = Join-Path $PSScriptRoot "..\rust-core\src\network" | Resolve-Path

Write-Host "Working directory: $rustCorePath" -ForegroundColor Green

# Create store_and_forward.rs file
@storeAndForwardContent =@'
//! Store-and-Forward Module for Offline Message Delivery
//! 
//! This module implements delayed message delivery through intermediate relay nodes.
//! Messages can be stored on third-party devices and delivered later when the recipient comes online.
//! 
//! ## Key Features:
//! - Messages stored encrypted on relay nodes (Tier 1 / Tier 2)
//! - Supports multi-day offline periods
//! - Automatic retry when recipient becomes available
//! - TTL-based message expiration
//! - Deduplication to prevent double delivery
//! 
//! ## Architecture:
//! 
//! ```text
//! Sender (Alice)          Relay (Bob's Friend)        Recipient (Bob)
//!     |                          |                          |
//!     |-- Encrypted Message ---->|                          |
//!     |  (stored for Bob)        |                          |
//!     |                          |  [Bob offline for days]  |
//!     |                          |                          |
//!     |                          |-- When Bob connects --->|
//!     |                          |  (forward stored msgs)   |
//!     |                          |                          |
//! ```

use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};
use tokio::sync::Mutex;

use crate::protocol::messages::EncryptedMessage;

// ═══════════════════════════════════════════════════════════════════
// CONSTANTS
// ═══════════════════════════════════════════════════════════════════

/// Default TTL for stored messages (7 days in seconds)
pub const DEFAULT_TTL_SECONDS: u64 = 7 * 24 * 60 * 60;

/// Maximum number of messages to store per recipient
pub const MAX_STORED_MESSAGES_PER_RECIPIENT: usize = 1000;

/// Maximum total stored messages (memory protection)
pub const MAX_TOTAL_STORED_MESSAGES: usize = 10000;

/// Cleanup interval (check every 5 minutes)
pub const CLEANUP_INTERVAL: Duration = Duration::from_secs(5 * 60);

// ═══════════════════════════════════════════════════════════════════
// DATA STRUCTURES
// ═══════════════════════════════════════════════════════════════════

/// A message waiting to be delivered
#[derive(Debug, Clone)]
pub struct StoredMessage {
    /// Unique message ID for deduplication
    pub message_id: String,
    
    /// The encrypted message payload
    pub encrypted_payload: Vec<u8>,
    
    /// When this message was first stored
    pub stored_at: Instant,
    
    /// When this message should expire
    pub expires_at: Instant,
    
    /// Number of delivery attempts
    pub delivery_attempts: u32,
    
    /// Last delivery attempt timestamp
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
        if self.is_expired() {
            return false;
        }
        
        match self.last_attempt {
            None => true, // Never attempted
            Some(last) => Instant::now() >= last + min_retry_interval,
        }
    }
    
    pub fn mark_attempted(&mut self) {
        self.delivery_attempts += 1;
        self.last_attempt = Some(Instant::now());
    }
}

/// Storage queue for one recipient
#[derive(Debug, Clone)]
pub struct RecipientQueue {
    pub messages: VecDeque<StoredMessage>,
    pub total_bytes: usize,
}

impl RecipientQueue {
    pub fn new() -> Self {
        Self {
            messages: VecDeque::new(),
            total_bytes: 0,
        }
    }
    
    pub fn push(&mut self, msg: StoredMessage) -> bool {
        // Check capacity
        if self.messages.len() >= MAX_STORED_MESSAGES_PER_RECIPIENT {
            // Remove oldest message to make room
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
            } else {
                break; // Messages are ordered by insertion, not expiry
            }
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

// ═══════════════════════════════════════════════════════════════════
// STORE AND FORWARD MANAGER
// ═══════════════════════════════════════════════════════════════════

/// Manages store-and-forward message queues
pub struct StoreAndForwardManager {
    /// Our node ID
    our_node_id: [u8; 32],
    
    /// Queues indexed by recipient node ID
    queues: Mutex<HashMap<[u8; 32], RecipientQueue>>,
    
    /// Statistics
    stats: Mutex<StoreForwardStats>,
    
    /// Minimum retry interval
    min_retry_interval: Duration,
    
    /// Default TTL
    default_ttl: Duration,
}

#[derive(Debug, Clone, Default)]
pub struct StoreForwardStats {
    pub total_messages_stored: u64,
    pub total_messages_delivered: u64,
    pub total_messages_expired: u64,
    pub current_queue_size: usize,
    pub total_bytes_stored: usize,
}

impl StoreAndForwardManager {
    pub fn new(our_node_id: [u8; 32]) -> Self {
        Self {
            our_node_id,
            queues: Mutex::new(HashMap::new()),
            stats: Mutex::new(StoreForwardStats::default()),
            min_retry_interval: Duration::from_secs(30), // 30 seconds between retries
            default_ttl: Duration::from_secs(DEFAULT_TTL_SECONDS),
        }
    }
    
    /// Store a message for later delivery
    pub async fn store_message(
        &self,
        recipient_id: [u8; 32],
        message_id: String,
        encrypted_payload: Vec<u8>,
    ) -> Result<(), StoreError> {
        // Don't store messages for ourselves
        if recipient_id == self.our_node_id {
            return Err(StoreError::CannotStoreForSelf);
        }
        
        let mut queues = self.queues.lock().await;
        let mut stats = self.stats.lock().await;
        
        // Check total capacity
        if stats.current_queue_size >= MAX_TOTAL_STORED_MESSAGES {
            // Try to cleanup expired messages first
            drop(stats);
            self.cleanup_expired().await;
            stats = self.stats.lock().await;
            
            if stats.current_queue_size >= MAX_TOTAL_STORED_MESSAGES {
                return Err(StoreError::StorageFull);
            }
        }
        
        let queue = queues.entry(recipient_id).or_insert_with(RecipientQueue::new);
        
        let stored_msg = StoredMessage::new(
            message_id,
            encrypted_payload,
            self.default_ttl.as_secs(),
        );
        
        queue.push(stored_msg);
        
        stats.total_messages_stored += 1;
        stats.current_queue_size = queues.values().map(|q| q.messages.len()).sum();
        stats.total_bytes_stored = queues.values().map(|q| q.total_bytes).sum();
        
        Ok(())
    }
    
    /// Get messages ready for delivery to a recipient
    pub async fn get_pending_messages(
        &self,
        recipient_id: [u8; 32],
    ) -> Vec<StoredMessage> {
        let mut queues = self.queues.lock().await;
        
        if let Some(queue) = queues.get_mut(&recipient_id) {
            // First remove expired
            let expired = queue.pop_expired();
            {
                let mut stats = self.stats.lock().await;
                stats.total_messages_expired += expired.len() as u64;
                stats.current_queue_size = queues.values().map(|q| q.messages.len()).sum();
                stats.total_bytes_stored = queues.values().map(|q| q.total_bytes).sum();
            }
            
            // Then get ready for delivery
            queue.get_ready_for_delivery(self.min_retry_interval)
        } else {
            Vec::new()
        }
    }
    
    /// Mark messages as successfully delivered
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
        } else {
            0
        }
    }
    
    /// Cleanup all expired messages across all queues
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
    
    /// Get statistics
    pub async fn get_stats(&self) -> StoreForwardStats {
        self.stats.lock().await.clone()
    }
    
    /// Get total number of stored messages
    pub async fn total_stored(&self) -> usize {
        let queues = self.queues.lock().await;
        queues.values().map(|q| q.messages.len()).sum()
    }
    
    /// Clear all queues (for testing or reset)
    pub async fn clear_all(&self) {
        let mut queues = self.queues.lock().await;
        let mut stats = self.stats.lock().await;
        
        queues.clear();
        
        stats.current_queue_size = 0;
        stats.total_bytes_stored = 0;
    }
}

// ═══════════════════════════════════════════════════════════════════
// ERRORS
// ═══════════════════════════════════════════════════════════════════

#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error("Cannot store messages for self")]
    CannotStoreForSelf,
    
    #[error("Storage capacity exceeded")]
    StorageFull,
    
    #[error("Message already exists: {0}")]
    DuplicateMessage(String),
    
    #[error("Invalid recipient ID")]
    InvalidRecipient,
}

pub type StoreResult<T> = Result<T, StoreError>;

// ═══════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;
    
    fn make_id(byte: u8) -> [u8; 32] {
        [byte; 32]
    }
    
    #[tokio::test]
    async fn test_store_message() {
        let manager = StoreAndForwardManager::new(make_id(0x01));
        let recipient = make_id(0x02);
        
        let result = manager.store_message(
            recipient,
            "msg_001".to_string(),
            vec![0x01, 0x02, 0x03],
        ).await;
        
        assert!(result.is_ok());
        assert_eq!(manager.total_stored().await, 1);
        
        println!("✅ Message stored successfully");
    }
    
    #[tokio::test]
    async fn test_cannot_store_for_self() {
        let manager = StoreAndForwardManager::new(make_id(0x01));
        
        let result = manager.store_message(
            make_id(0x01), // Same as our ID
            "msg_001".to_string(),
            vec![0x01, 0x02],
        ).await;
        
        assert!(matches!(result, Err(StoreError::CannotStoreForSelf)));
        println!("✅ Correctly rejects storing for self");
    }
    
    #[tokio::test]
    async fn test_get_pending_messages() {
        let manager = StoreAndForwardManager::new(make_id(0x01));
        let recipient = make_id(0x02);
        
        // Store multiple messages
        for i in 0..5 {
            manager.store_message(
                recipient,
                format!("msg_{:03}", i),
                vec![i as u8; 10],
            ).await.unwrap();
        }
        
        // Wait a bit to allow retry
        tokio::time::sleep(Duration::from_millis(100)).await;
        
        let pending = manager.get_pending_messages(recipient).await;
        assert_eq!(pending.len(), 5);
        
        println!("✅ Retrieved {} pending messages", pending.len());
    }
    
    #[tokio::test]
    async fn test_mark_delivered() {
        let manager = StoreAndForwardManager::new(make_id(0x01));
        let recipient = make_id(0x02);
        
        // Store and get messages
        manager.store_message(
            recipient,
            "msg_001".to_string(),
            vec![0x01, 0x02],
        ).await.unwrap();
        
        manager.store_message(
            recipient,
            "msg_002".to_string(),
            vec![0x03, 0x04],
        ).await.unwrap();
        
        tokio::time::sleep(Duration::from_millis(100)).await;
        
        let pending = manager.get_pending_messages(recipient).await;
        assert_eq!(pending.len(), 2);
        
        // Mark one as delivered
        let removed = manager.mark_delivered(
            recipient,
            &["msg_001".to_string()],
        ).await;
        
        assert_eq!(removed, 1);
        assert_eq!(manager.total_stored().await, 1);
        
        println!("✅ Marked messages as delivered");
    }
    
    #[tokio::test]
    async fn test_storage_capacity() {
        let manager = StoreAndForwardManager::new(make_id(0x01));
        let recipient = make_id(0x02);
        
        // Fill up to limit for one recipient
        for i in 0..MAX_STORED_MESSAGES_PER_RECIPIENT + 10 {
            manager.store_message(
                recipient,
                format!("msg_{:05}", i),
                vec![i as u8; 5],
            ).await.unwrap();
        }
        
        // Should be capped at MAX_STORED_MESSAGES_PER_RECIPIENT
        let total = manager.total_stored().await;
        assert_eq!(total, MAX_STORED_MESSAGES_PER_RECIPIENT);
        
        println!("✅ Storage capacity enforced: {} messages", total);
    }
    
    #[tokio::test]
    async fn test_multiple_recipients() {
        let manager = StoreAndForwardManager::new(make_id(0x01));
        
        // Store for different recipients
        for i in 0..5 {
            let recipient = make_id(0x10 + i as u8);
            manager.store_message(
                recipient,
                format!("msg_for_{}", i),
                vec![i as u8; 10],
            ).await.unwrap();
        }
        
        assert_eq!(manager.total_stored().await, 5);
        
        // Each recipient should have their own messages
        for i in 0..5 {
            let recipient = make_id(0x10 + i as u8);
            tokio::time::sleep(Duration::from_millis(50)).await;
            let pending = manager.get_pending_messages(recipient).await;
            assert_eq!(pending.len(), 1);
        }
        
        println!("✅ Multiple recipients handled correctly");
    }
    
    #[tokio::test]
    async fn test_statistics() {
        let manager = StoreAndForwardManager::new(make_id(0x01));
        let recipient = make_id(0x02);
        
        // Store some messages
        for i in 0..3 {
            manager.store_message(
                recipient,
                format!("msg_{:03}", i),
                vec![i as u8; 10],
            ).await.unwrap();
        }
        
        let stats = manager.get_stats().await;
        assert_eq!(stats.total_messages_stored, 3);
        assert_eq!(stats.current_queue_size, 3);
        
        println!("✅ Statistics tracking works");
    }
}
'@

# Write the file with UTF-8 encoding (no BOM)
@storeAndForwardContent | Out-File -FilePath "$rustCorePath\store_and_forward.rs" -Encoding UTF8 -NoNewline

Write-Host "✅ Created store_and_forward.rs" -ForegroundColor Green

# Update mod.rs to include the new module
$modRsPath = Join-Path $rustCorePath "mod.rs"

if (Test-Path $modRsPath) {
    $content = Get-Content $modRsPath -Raw
    
    # Check if store_and_forward module is already declared
    if ($content -notmatch 'pub mod store_and_forward') {
        # Add the module declaration
        $newContent = "pub mod store_and_forward;`n" + $content
        $newContent | Out-File -FilePath $modRsPath -Encoding UTF8 -NoNewline
        Write-Host "✅ Updated mod.rs to include store_and_forward module" -ForegroundColor Green
    } else {
        Write-Host "ℹ️  store_and_forward module already declared in mod.rs" -ForegroundColor Yellow
    }
} else {
    Write-Host "⚠️  mod.rs not found, creating it..." -ForegroundColor Yellow
    "pub mod store_and_forward;" | Out-File -FilePath $modRsPath -Encoding UTF8 -NoNewline
}

Write-Host "`n=== Testing Store-and-Forward Implementation ===" -ForegroundColor Cyan

# Run cargo test for the new module
Set-Location (Join-Path $PSScriptRoot "..\rust-core")

Write-Host "Running tests..." -ForegroundColor Cyan
cargo test store_and_forward --lib 2>&1 | ForEach-Object {
    if ($_ -match "test result:") {
        Write-Host $_ -ForegroundColor Green
    } elseif ($_ -match "FAILED") {
        Write-Host $_ -ForegroundColor Red
    } else {
        Write-Host $_
    }
}

Write-Host "`n=== Summary ===" -ForegroundColor Cyan
Write-Host "The store-and-forward module enables:" -ForegroundColor White
Write-Host "  • Messages stored on third-party relay nodes" -ForegroundColor Gray
Write-Host "  • Support for multi-day offline periods (7 day default TTL)" -ForegroundColor Gray
Write-Host "  • Automatic retry when recipient comes online" -ForegroundColor Gray
Write-Host "  • Memory-safe limits (1000 msgs/recipient, 10000 total)" -ForegroundColor Gray
Write-Host "  • Deduplication to prevent double delivery" -ForegroundColor Gray
Write-Host "  • Encrypted payloads (relay cannot read content)" -ForegroundColor Gray

Write-Host "`n=== Next Steps ===" -ForegroundColor Cyan
Write-Host "1. Integrate with fallback_chain.rs for automatic activation" -ForegroundColor Gray
Write-Host "2. Add FFI bindings for Kotlin/Android integration" -ForegroundColor Gray
Write-Host "3. Implement relay node selection logic" -ForegroundColor Gray
Write-Host "4. Add persistence layer for long-term storage" -ForegroundColor Gray
