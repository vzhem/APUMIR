//! Bounded redispatch of a locally addressed mesh envelope.
//!
//! An EventBus enqueue is not a durable Android inbox commit. In particular,
//! a process can die, or its handler can fail, after enqueueing the event.
//! Suppress a short duplicate burst but allow the same origin to retry later;
//! Android's Room/message-specific handlers perform durable deduplication.

use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};

const REDISPATCH_AFTER: Duration = Duration::from_secs(5);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum Admission {
    Dispatch,
    Suppress,
    ConflictingOrigin,
}

struct Entry {
    origin: String,
    last_dispatch: Instant,
}

pub(super) struct LocalMeshDeliveries {
    entries: HashMap<String, Entry>,
    order: VecDeque<String>,
    capacity: usize,
}

impl LocalMeshDeliveries {
    pub(super) fn new(capacity: usize) -> Self {
        assert!(capacity > 0);
        Self { entries: HashMap::new(), order: VecDeque::new(), capacity }
    }

    /// A mesh cleanup receipt is permitted only for a locally observed message
    /// and its original sender, after Android's explicit handling ACK.
    pub(super) fn knows_sender(&self, message_id: &str, origin: &str) -> bool {
        self.entries.get(message_id).is_some_and(|entry| entry.origin == origin)
    }

    pub(super) fn admit(&mut self, message_id: &str, origin: &str, now: Instant) -> Admission {
        if let Some(entry) = self.entries.get_mut(message_id) {
            if entry.origin != origin {
                return Admission::ConflictingOrigin;
            }
            if now.saturating_duration_since(entry.last_dispatch) < REDISPATCH_AFTER {
                return Admission::Suppress;
            }
            entry.last_dispatch = now;
            return Admission::Dispatch;
        }
        while self.entries.len() >= self.capacity {
            if let Some(oldest) = self.order.pop_front() {
                self.entries.remove(&oldest);
            } else {
                break;
            }
        }
        self.entries.insert(message_id.to_owned(), Entry { origin: origin.to_owned(), last_dispatch: now });
        self.order.push_back(message_id.to_owned());
        Admission::Dispatch
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn event_enqueue_is_not_a_final_receipt_and_can_be_retried() {
        let mut deliveries = LocalMeshDeliveries::new(4);
        let now = Instant::now();
        assert!(!deliveries.knows_sender("m1", "alice"));
        assert_eq!(deliveries.admit("m1", "alice", now), Admission::Dispatch);
        assert!(deliveries.knows_sender("m1", "alice"));
        assert!(!deliveries.knows_sender("m1", "mallory"));
        assert_eq!(deliveries.admit("m1", "alice", now + Duration::from_secs(1)), Admission::Suppress);
        // The first handler may have failed without killing the process.
        assert_eq!(deliveries.admit("m1", "alice", now + Duration::from_secs(5)), Admission::Dispatch);
        assert_eq!(deliveries.admit("m1", "alice", now + Duration::from_secs(10)), Admission::Dispatch);
    }

    #[test]
    fn conflicting_origin_never_replaces_the_tracked_sender() {
        let mut deliveries = LocalMeshDeliveries::new(4);
        let now = Instant::now();
        assert_eq!(deliveries.admit("m1", "alice", now), Admission::Dispatch);
        assert_eq!(deliveries.admit("m1", "mallory", now + Duration::from_secs(50)), Admission::ConflictingOrigin);
        assert_eq!(deliveries.admit("m1", "alice", now + Duration::from_secs(50)), Admission::Dispatch);
    }

    #[test]
    fn bounded_cache_releases_oldest_and_restarts_without_losing_a_retry() {
        let mut deliveries = LocalMeshDeliveries::new(2);
        let now = Instant::now();
        assert_eq!(deliveries.admit("m1", "alice", now), Admission::Dispatch);
        assert_eq!(deliveries.admit("m2", "bob", now), Admission::Dispatch);
        assert_eq!(deliveries.admit("m3", "carol", now), Admission::Dispatch);
        assert_eq!(deliveries.entries.len(), 2);
        assert_eq!(deliveries.admit("m1", "alice", now), Admission::Dispatch);
        let mut restarted = LocalMeshDeliveries::new(2);
        assert_eq!(restarted.admit("m1", "alice", now), Admission::Dispatch);
    }

    #[test]
    fn backwards_instant_never_expands_a_duplicate_burst() {
        let mut deliveries = LocalMeshDeliveries::new(1);
        let now = Instant::now();
        assert_eq!(deliveries.admit("m1", "alice", now), Admission::Dispatch);
        assert_eq!(deliveries.admit("m1", "alice", now - Duration::from_secs(1)), Admission::Suppress);
    }
}
