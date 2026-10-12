//! Bounded online-helper race. A completed write is NOT end-to-end acceptance.
//! Both legs carry the same sealed packet and share one recipient-signed receipt.

use std::future::Future;
use std::time::Duration;
use tokio::sync::oneshot;

pub const HELPER_HEDGE_DELAY: Duration = Duration::from_millis(1500);
/// One deadline for both helpers, not an independent full wait for every leg.
pub const HELPER_DELIVERY_BUDGET: Duration = Duration::from_millis(9500);

/// `send` starts at most two legs; the reserve starts on delay or primary failure.
/// Dropping a leg's transport reply waiter does not cancel its queued stream write:
/// DirectTransport's bounded lane owns that write and its own deadline.
/// Receipt verification belongs to PeerAssistance, before this oneshot is resolved.
pub async fn hedged_delivery<F, Fut>(
    helpers: usize,
    mut receipt: oneshot::Receiver<bool>,
    mut send: F,
    hedge_delay: Duration,
    budget: Duration,
) -> bool
where
    F: FnMut(usize) -> Fut,
    Fut: Future<Output = bool>,
{
    if helpers == 0 { return false; }
    let deadline = tokio::time::sleep(budget);
    let hedge = tokio::time::sleep(hedge_delay);
    tokio::pin!(deadline, hedge);
    let primary = send(0);
    tokio::pin!(primary);
    let mut primary_done = false;
    let mut reserve: Option<std::pin::Pin<Box<Fut>>> = None;
    let mut reserve_done = false;
    loop {
        tokio::select! {
            biased;
            accepted = &mut receipt => return matches!(accepted, Ok(true)),
            _ = &mut deadline => return false,
            sent = &mut primary, if !primary_done => {
                primary_done = true;
                if !sent && helpers > 1 && reserve.is_none() {
                    reserve = Some(Box::pin(send(1)));
                }
            }
            _ = &mut hedge, if helpers > 1 && reserve.is_none() => {
                reserve = Some(Box::pin(send(1)));
            }
            _ = async { reserve.as_mut().unwrap().as_mut().await },
                if reserve.is_some() && !reserve_done => { reserve_done = true; }
        }
        // Even a write failure may race with a receipt; only a verified receipt
        // can succeed. Keep the same absolute deadline rather than restart it.
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    #[tokio::test(start_paused = true)]
    async fn fast_receipt_never_starts_reserve() {
        let (tx, rx) = oneshot::channel();
        let mut tx = Some(tx);
        let mut started = Vec::new();
        assert!(hedged_delivery(2, rx, |leg| {
            started.push(leg);
            let tx = tx.take().unwrap();
            async move { tx.send(true).unwrap(); true }
        }, HELPER_HEDGE_DELAY, HELPER_DELIVERY_BUDGET).await);
        assert_eq!(started, [0]);
    }

    #[tokio::test(start_paused = true)]
    async fn stalled_primary_launches_reserve_after_delay_not_after_write() {
        let start = tokio::time::Instant::now();
        let (tx, rx) = oneshot::channel();
        let mut tx = Some(tx);
        let mut started = Vec::new();
        assert!(hedged_delivery(2, rx, |leg| {
            started.push((leg, start.elapsed()));
            let receipt = if leg == 1 { tx.take() } else { None };
            async move {
                if let Some(tx) = receipt { tx.send(true).unwrap(); true }
                else { std::future::pending::<bool>().await }
            }
        }, HELPER_HEDGE_DELAY, HELPER_DELIVERY_BUDGET).await);
        assert_eq!(started, [(0, Duration::ZERO), (1, HELPER_HEDGE_DELAY)]);
    }

    #[tokio::test(start_paused = true)]
    async fn failed_primary_launches_reserve_immediately() {
        let start = tokio::time::Instant::now();
        let (tx, rx) = oneshot::channel();
        let mut tx = Some(tx);
        let mut started = Vec::new();
        assert!(hedged_delivery(2, rx, |leg| {
            started.push((leg, start.elapsed()));
            let receipt = if leg == 1 { tx.take() } else { None };
            async move { if let Some(tx) = receipt { tx.send(true).unwrap(); true } else { false } }
        }, HELPER_HEDGE_DELAY, HELPER_DELIVERY_BUDGET).await);
        assert_eq!(started, [(0, Duration::ZERO), (1, Duration::ZERO)]);
    }

    #[tokio::test(start_paused = true)]
    async fn successful_writes_without_receipt_share_one_deadline() {
        let start = tokio::time::Instant::now();
        let (_tx, rx) = oneshot::channel();
        let mut started = Vec::new();
        assert!(!hedged_delivery(7, rx, |leg| {
            started.push(leg); async { true }
        }, HELPER_HEDGE_DELAY, HELPER_DELIVERY_BUDGET).await);
        assert_eq!(started, [0, 1], "never more than two helpers");
        assert_eq!(start.elapsed(), HELPER_DELIVERY_BUDGET);
    }

    #[tokio::test(start_paused = true)]
    async fn one_helper_waits_for_receipt_without_reserve() {
        let start = tokio::time::Instant::now();
        let (_tx, rx) = oneshot::channel();
        let mut started = Vec::new();
        assert!(!hedged_delivery(1, rx, |leg| {
            started.push(leg); async { false }
        }, HELPER_HEDGE_DELAY, HELPER_DELIVERY_BUDGET).await);
        assert_eq!(started, [0]);
        assert_eq!(start.elapsed(), HELPER_DELIVERY_BUDGET);
    }

    #[tokio::test(start_paused = true)]
    async fn closed_receipt_or_no_helpers_fail_without_starting_extra_legs() {
        let (tx, rx) = oneshot::channel(); drop(tx);
        let mut started = Vec::new();
        assert!(!hedged_delivery(2, rx, |leg| {
            started.push(leg); async { true }
        }, HELPER_HEDGE_DELAY, HELPER_DELIVERY_BUDGET).await);
        assert_eq!(started, [0]);
        let (_tx, rx) = oneshot::channel();
        assert!(!hedged_delivery(0, rx, |_| async { panic!("no eligible helpers") },
            HELPER_HEDGE_DELAY, HELPER_DELIVERY_BUDGET).await);
    }

    #[tokio::test(start_paused = true)]
    async fn started_lane_work_can_finish_after_reply_waiter_is_dropped() {
        let completed = Arc::new(Mutex::new(Vec::new()));
        let (tx, rx) = oneshot::channel();
        let mut tx = Some(tx);
        assert!(hedged_delivery(2, rx, |leg| {
            let completed = Arc::clone(&completed);
            let receipt = if leg == 1 { tx.take() } else { None };
            // Models DirectTransport: command/lane owns the write, not send's waiter.
            let (done_tx, done_rx) = oneshot::channel();
            tokio::spawn(async move {
                tokio::time::sleep(if leg == 0 { Duration::from_secs(3) } else { Duration::ZERO }).await;
                completed.lock().unwrap().push(leg);
                if let Some(tx) = receipt { let _ = tx.send(true); }
                let _ = done_tx.send(true);
            });
            async move { done_rx.await.unwrap_or(false) }
        }, HELPER_HEDGE_DELAY, HELPER_DELIVERY_BUDGET).await);
        tokio::time::sleep(Duration::from_secs(4)).await;
        let mut legs = completed.lock().unwrap().clone(); legs.sort();
        assert_eq!(legs, [0, 1]);
    }
}
