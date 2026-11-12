//! Receipt-driven relay cleanup. Legacy mesh receipts are deliberately not
//! used here: older clients sent them before Android stored the message.

use super::core::RelayCustody;
use crate::network::relay_queue::{RelayQueue, utc_now_ms};

/// Called only after Android committed an outgoing row's delivery status.
/// Unknown and foreign on-disk entries are never removed based on RAM alone.
pub(super) fn confirmed_local(
    queue: Option<&RelayQueue>,
    custody: Option<&RelayCustody>,
    local_node: &str,
    message_id: &str,
) -> bool {
    if local_node.is_empty() || message_id.is_empty() { return false; }
    let removed_durable = match custody {
        None => false,
        Some(custody) => match custody.store.remove_encrypted_own(
            &*custody.keys, message_id, local_node, utc_now_ms(),
        ) {
            Ok(removed) => removed,
            Err(error) => {
                tracing::warn!("MESH app ACK: durable ownership check failed: {}", error);
                false
            }
        },
    };
    let removed_memory = queue.is_some_and(|q| q.remove_delivered_own(message_id));
    removed_durable || removed_memory
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::network::relay_queue::RelayMessage;
    use crate::storage::relay_at_rest::ephemeral_key_source;
    use crate::storage::relay_store::RelayStore;

    fn custody() -> RelayCustody {
        RelayCustody { store: RelayStore::open_in_memory().unwrap(), keys: ephemeral_key_source(), durable: false }
    }

    fn message(id: &str, origin: &str) -> RelayMessage {
        RelayMessage::new(id.into(), "pk_recipient".into(), origin.into(), "chat".into(), b"ciphertext".to_vec())
    }

    // Negative controls for the independent legacy queue. They describe current
    // limitations; they do not claim that a network send has been cancelled.
    #[tokio::test]
    async fn confirmed_local_does_not_clear_independent_legacy_queue() {
        use crate::network::message_queue::{MessageQueue, QueuedMessage};
        use sha2::{Digest, Sha256};
        let relay = RelayQueue::new();
        let custody = custody();
        let ours = message("ours", "pk_local");
        custody.store.store_encrypted(&*custody.keys, &ours, utc_now_ms()).unwrap();
        assert!(relay.enqueue_own(ours).unwrap());
        // Same key derivation used by the engine's legacy enqueue path.
        let recipient_hash = Sha256::digest(b"pk_recipient");
        let mut recipient = [0u8; 32];
        recipient.copy_from_slice(&recipient_hash);
        let digest = Sha256::digest(b"ours");
        let mut id = [0u8; 16];
        id.copy_from_slice(&digest[..16]);
        let legacy = MessageQueue::new();
        legacy.enqueue(QueuedMessage::new(id, recipient, b"payload".to_vec())).await.unwrap();
        assert!(confirmed_local(Some(&relay), Some(&custody), "pk_local", "ours"));
        assert!(!relay.contains("ours"));
        assert!(!custody.store.contains_encrypted("ours").unwrap());
        assert_eq!(legacy.peek_for(&recipient).await.len(), 1);
    }

    #[tokio::test]
    async fn legacy_removal_is_recipient_scoped_even_with_a_colliding_id() {
        use crate::network::message_queue::{MessageQueue, QueuedMessage};
        let q = MessageQueue::new();
        let id = [7u8; 16];
        q.enqueue(QueuedMessage::new(id, [1u8; 32], b"one".to_vec())).await.unwrap();
        q.enqueue(QueuedMessage::new(id, [2u8; 32], b"two".to_vec())).await.unwrap();
        assert!(q.remove_message(&[1u8; 32], &id).await);
        assert!(q.peek_for(&[1u8; 32]).await.is_empty());
        assert_eq!(q.peek_for(&[2u8; 32]).await.len(), 1);
    }

    #[tokio::test]
    async fn legacy_dequeued_batch_cannot_be_recalled_by_queue_removal() {
        use crate::network::message_queue::{MessageQueue, QueuedMessage};
        use std::sync::Arc;
        use tokio::sync::oneshot;
        let q = Arc::new(MessageQueue::new());
        let id = [7u8; 16];
        let recipient = [1u8; 32];
        q.enqueue(QueuedMessage::new(id, recipient, b"payload".to_vec())).await.unwrap();
        let worker = Arc::clone(&q);
        let (extracted_tx, extracted_rx) = oneshot::channel();
        let (resume_tx, resume_rx) = oneshot::channel();
        // Both production presence paths extract a batch before sending it.
        let task = tokio::spawn(async move {
            let batch = worker.dequeue_for(&recipient).await;
            extracted_tx.send(()).unwrap();
            resume_rx.await.unwrap();
            batch
        });
        extracted_rx.await.unwrap();
        assert!(!q.remove_message(&recipient, &id).await);
        resume_tx.send(()).unwrap();
        let batch = task.await.unwrap();
        assert_eq!(batch.len(), 1);
        assert_eq!(batch[0].msg_id, id);
    }

    #[test]
    fn local_ack_cleans_local_only_including_an_evicted_ram_copy() {
        let q = RelayQueue::new();
        let c = custody();
        let now = utc_now_ms();
        let ours = message("ours", "pk_local");
        let foreign = message("foreign", "pk_other");
        c.store.store_encrypted(&*c.keys, &ours, now).unwrap();
        c.store.store_encrypted(&*c.keys, &foreign, now).unwrap();
        q.enqueue(foreign).unwrap();
        assert!(!confirmed_local(Some(&q), Some(&c), "pk_local", "foreign"));
        assert!(q.contains("foreign"));
        assert!(c.store.contains_encrypted("foreign").unwrap());
        assert!(confirmed_local(Some(&q), Some(&c), "pk_local", "ours"));
        assert!(!c.store.contains_encrypted("ours").unwrap());
        assert!(c.store.has_tombstone("ours").unwrap());
    }
}
