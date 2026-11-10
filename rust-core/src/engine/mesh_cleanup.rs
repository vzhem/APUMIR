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
