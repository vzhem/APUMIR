//! Broadcast a network-recovery hint to every MQTT reconnect loop. The hint
//! never tears down an established session or overrides reconnect backoff more
//! than once per loop. A single consumed boolean could wake only one broker.

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

pub(super) struct ReconnectWake(AtomicU64);

impl ReconnectWake {
    pub const fn new() -> Self { Self(AtomicU64::new(0)) }

    pub fn signal(&self) { self.0.fetch_add(1, Ordering::AcqRel); }

    pub fn generation(&self) -> u64 { self.0.load(Ordering::Acquire) }

    pub async fn wait(&self, seconds: u64, seen: &mut u64) {
        let step = Duration::from_millis(250);
        let mut waited = Duration::ZERO;
        let total = Duration::from_secs(seconds);
        while waited < total {
            let current = self.generation();
            if current != *seen {
                *seen = current;
                tracing::info!("MQTT: network recovery hint interrupted reconnect backoff");
                return;
            }
            tokio::time::sleep(step).await;
            waited += step;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[tokio::test]
    async fn one_signal_wakes_both_independent_brokers() {
        let wake = Arc::new(ReconnectWake::new());
        let generation = wake.generation();
        let first_wake = Arc::clone(&wake);
        let second_wake = Arc::clone(&wake);
        let first = tokio::spawn(async move {
            let mut seen = generation;
            first_wake.wait(30, &mut seen).await;
            seen
        });
        let second = tokio::spawn(async move {
            let mut seen = generation;
            second_wake.wait(30, &mut seen).await;
            seen
        });
        tokio::task::yield_now().await;
        wake.signal();
        let (first, second) = tokio::time::timeout(Duration::from_secs(2), async {
            (first.await.unwrap(), second.await.unwrap())
        }).await.expect("both waiters should be woken");
        assert_eq!(first, generation + 1);
        assert_eq!(second, generation + 1);
    }

    #[tokio::test]
    async fn a_signal_is_consumed_only_once_by_each_loop() {
        let wake = ReconnectWake::new();
        let mut seen = wake.generation();
        wake.signal();
        wake.wait(30, &mut seen).await;
        assert!(tokio::time::timeout(Duration::from_millis(50), wake.wait(30, &mut seen)).await.is_err());
        wake.signal();
        tokio::time::timeout(Duration::from_secs(1), wake.wait(30, &mut seen)).await.unwrap();
        assert_eq!(seen, 2);
    }
}
