//! A queued ConnAck can outlive its socket. Tie readiness to the precise
//! connection generation so a delayed notification or subscription enqueue
//! cannot resurrect a disconnected/replaced broker session.

use std::sync::atomic::{AtomicU64, Ordering};

const STATUS_MASK: u64 = 3;
const CONNECTED: u64 = 1;
const READY: u64 = 2;

pub(super) struct BrokerConnection(AtomicU64);

impl BrokerConnection {
    pub fn new() -> Self { Self(AtomicU64::new(0)) }

    /// Called only by this broker's event loop after a ConnAck.
    pub fn connected(&self) -> u64 {
        let previous = self.0.fetch_update(Ordering::AcqRel, Ordering::Acquire, |state| {
            Some((state & !STATUS_MASK).wrapping_add(4) | CONNECTED)
        }).unwrap();
        (previous & !STATUS_MASK).wrapping_add(4) | CONNECTED
    }

    pub fn disconnected(&self) { self.0.fetch_and(!STATUS_MASK, Ordering::AcqRel); }

    pub fn is_current(&self, token: u64) -> bool {
        self.0.load(Ordering::Acquire) == token
    }

    /// Atomically validate that the same socket is still connected after the
    /// asynchronous subscribe request was queued.
    pub fn mark_ready(&self, token: u64) -> bool {
        self.0.compare_exchange(token, (token & !STATUS_MASK) | READY,
            Ordering::AcqRel, Ordering::Acquire).is_ok()
    }

    pub fn is_ready(&self) -> bool { self.0.load(Ordering::Acquire) & STATUS_MASK == READY }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stale_connack_cannot_restore_a_disconnected_or_replaced_session() {
        let connection = BrokerConnection::new();
        let old = connection.connected();
        connection.disconnected();
        assert!(!connection.is_current(old));
        assert!(!connection.mark_ready(old));
        let current = connection.connected();
        assert_ne!(current, old);
        assert!(!connection.mark_ready(old));
        assert!(connection.mark_ready(current));
        assert!(connection.is_ready());
        connection.disconnected();
        assert!(!connection.is_ready());
    }
}
