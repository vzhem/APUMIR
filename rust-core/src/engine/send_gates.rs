//! Serialize concurrent attempts for the same message without blocking other messages.
//!
//! The UI can start an initial send while the outbox or a manual retry is already
//! attempting the same ID. Those attempts touch the same durable relay entry and
//! must retain their former ordering even though independent sends may now overlap.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, Weak};

#[derive(Default)]
pub(crate) struct MessageSendGates {
    entries: Mutex<HashMap<String, Weak<Mutex<()>>>>,
}

impl MessageSendGates {
    pub(crate) fn for_message(&self, message_id: &str) -> Arc<Mutex<()>> {
        let mut entries = self.entries.lock().unwrap();
        if let Some(existing) = entries.get(message_id).and_then(Weak::upgrade) {
            return existing;
        }
        // Keep only gates held by an active or waiting caller. No history of
        // message IDs is retained when traffic has stopped.
        entries.retain(|_, gate| gate.strong_count() > 0);
        let gate = Arc::new(Mutex::new(()));
        entries.insert(message_id.to_owned(), Arc::downgrade(&gate));
        gate
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;
    use std::time::Duration;

    #[test]
    fn same_message_shares_a_gate_but_other_messages_do_not() {
        let gates = MessageSendGates::default();
        let first = gates.for_message("m1");
        assert!(Arc::ptr_eq(&first, &gates.for_message("m1")));
        assert!(!Arc::ptr_eq(&first, &gates.for_message("m2")));
    }

    #[test]
    fn same_message_waits_while_another_message_can_proceed() {
        let gates = Arc::new(MessageSendGates::default());
        let first = gates.for_message("m1");
        let first_lock = first.lock().unwrap();
        let same = gates.for_message("m1");
        let different = gates.for_message("m2");
        let (entered, observed) = mpsc::channel();
        let waiting = std::thread::spawn(move || {
            let _lock = same.lock().unwrap();
            entered.send(()).unwrap();
        });
        assert!(different.try_lock().is_ok());
        assert!(observed.recv_timeout(Duration::from_millis(50)).is_err());
        drop(first_lock);
        observed.recv_timeout(Duration::from_secs(1)).unwrap();
        waiting.join().unwrap();
    }

    #[test]
    fn stale_ids_are_removed_without_disturbing_live_gates() {
        let gates = MessageSendGates::default();
        let live = gates.for_message("live");
        for id in 0..100 {
            drop(gates.for_message(&format!("done-{id}")));
        }
        let next = gates.for_message("next");
        assert!(Arc::ptr_eq(&live, &gates.for_message("live")));
        let entries = gates.entries.lock().unwrap();
        assert_eq!(entries.len(), 2);
        drop(entries);
        drop((live, next));
        drop(gates.for_message("fresh"));
        assert_eq!(gates.entries.lock().unwrap().len(), 1);
    }
}
