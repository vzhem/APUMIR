//! Bounded direct presence fan-out, independent of the custody/lookup thread.
//! One round is in flight at a time; no more than 32 addresses per round,
//! with at most four concurrent sends. The caller schedules at most one round
//! per minute.

use std::collections::HashMap;
use std::future::Future;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use super::direct_transport::{DirectTransport, DIRECT_SEND_BUDGET};
use super::presence_scope::{direct_presence_payload, PresenceScope, MAX_OWN_PER_ROUND};

const PARALLEL_SENDS: usize = 4;

pub struct PresenceDispatcher {
    runtime: tokio::runtime::Handle,
    busy: Arc<AtomicBool>,
    stop: Arc<AtomicBool>,
}

struct BatchGuard(Arc<AtomicBool>);
impl Drop for BatchGuard {
    fn drop(&mut self) { self.0.store(false, Ordering::Release); }
}

/// Admission and cleanup are independent of the network; even cancellation or
/// a panic releases the busy flag when the spawned future is dropped.
fn acquire_batch(busy: &Arc<AtomicBool>, stop: &AtomicBool, nonempty: bool) -> Option<BatchGuard> {
    if !nonempty || stop.load(Ordering::Relaxed)
        || busy.compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire).is_err()
    { return None; }
    Some(BatchGuard(Arc::clone(busy)))
}

async fn run_bounded<F, Fut>(recipients: Vec<String>, stop: Arc<AtomicBool>, send: F)
where
    F: Fn(String) -> Fut,
    Fut: Future<Output = ()> + Send + 'static,
{
    let mut tasks = tokio::task::JoinSet::new();
    for peer_id in recipients.into_iter().take(MAX_OWN_PER_ROUND) {
        while tasks.len() >= PARALLEL_SENDS { let _ = tasks.join_next().await; }
        if stop.load(Ordering::Relaxed) { break; }
        tasks.spawn(send(peer_id));
    }
    while tasks.join_next().await.is_some() {}
}

impl PresenceDispatcher {
    pub fn new(runtime: tokio::runtime::Handle, stop: Arc<AtomicBool>) -> Self {
        Self { runtime, busy: Arc::new(AtomicBool::new(false)), stop }
    }

    /// The clock is independent of the older blocking lookup/custody worker:
    /// slow retries in that worker must not postpone presence indefinitely.
    #[allow(clippy::too_many_arguments)]
    pub fn spawn_periodic(
        runtime: tokio::runtime::Handle,
        stop: Arc<AtomicBool>,
        scope: Arc<PresenceScope>,
        direct: Arc<Mutex<Option<DirectTransport>>>,
        addresses: Arc<Mutex<HashMap<String, SocketAddr>>>,
        public_addr: Arc<Mutex<Option<SocketAddr>>>,
        addr_changed: Arc<AtomicBool>,
        node_id: String,
        display_name: String,
    ) {
        let dispatcher = Self::new(runtime.clone(), Arc::clone(&stop));
        runtime.spawn(async move {
            let mut ticker = tokio::time::interval(std::time::Duration::from_secs(5));
            ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                ticker.tick().await;
                if stop.load(Ordering::Relaxed) { break; }
                let now = crate::storage::models::now_ms();
                if dispatcher.is_busy() || !scope.own_due(now) { continue; }
                let transport = { direct.lock().unwrap().clone() };
                let Some(transport) = transport else { continue; };
                let recipients = scope.next_batch(MAX_OWN_PER_ROUND);
                if dispatcher.dispatch(
                    transport, Arc::clone(&scope), Arc::clone(&addresses),
                    Arc::clone(&public_addr), node_id.clone(), display_name.clone(), recipients,
                ) {
                    scope.mark_own(now);
                    if public_addr.lock().unwrap().is_some() && addr_changed.swap(false, Ordering::Relaxed) {
                        tracing::info!("PRESENCE K4: changed address queued for bounded personal rounds");
                    }
                }
            }
        });
    }

    pub fn is_busy(&self) -> bool { self.busy.load(Ordering::Acquire) }

    #[allow(clippy::too_many_arguments)]
    pub fn dispatch(
        &self,
        transport: DirectTransport,
        scope: Arc<PresenceScope>,
        addresses: Arc<Mutex<HashMap<String, SocketAddr>>>,
        public_addr: Arc<Mutex<Option<SocketAddr>>>,
        node_id: String,
        display_name: String,
        recipients: Vec<String>,
    ) -> bool {
        let Some(guard) = acquire_batch(&self.busy, &self.stop, !recipients.is_empty()) else {
            return false;
        };
        let stop = Arc::clone(&self.stop);
        self.runtime.spawn(async move {
            let _guard = guard;
            run_bounded(recipients, stop, move |peer_id| {
                let scope = Arc::clone(&scope);
                let addresses = Arc::clone(&addresses);
                let public_addr = Arc::clone(&public_addr);
                let transport = transport.clone();
                let node_id = node_id.clone();
                let display_name = display_name.clone();
                async move {
                    if !scope.is_own(&peer_id) { return; }
                    let addr = {
                        let map = addresses.lock().unwrap();
                        map.get(&peer_id).copied().or_else(|| map.get(&format!("{}_public", peer_id)).copied())
                    };
                    let own_addr = public_addr.lock().unwrap().map(|addr| addr.to_string());
                    // Stamp when the individual send starts, not at the beginning
                    // of a potentially long round.
                    let payload = direct_presence_payload(
                        &node_id, &display_name, own_addr.as_deref(), own_addr.is_some(),
                        crate::storage::models::now_ms(),
                    ).into_bytes();
                    // Include the command-queue wait in the time budget.
                    let _ = tokio::time::timeout(DIRECT_SEND_BUDGET, transport.send(&peer_id, addr, payload)).await;
                }
            }).await;
        });
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;
    use tokio::sync::{mpsc, Semaphore};

    #[test]
    fn admission_is_single_flight_and_releases_on_drop() {
        let busy = Arc::new(AtomicBool::new(false));
        let stop = AtomicBool::new(false);
        assert!(acquire_batch(&busy, &stop, false).is_none());
        let guard = acquire_batch(&busy, &stop, true).unwrap();
        assert!(acquire_batch(&busy, &stop, true).is_none());
        drop(guard);
        assert!(acquire_batch(&busy, &stop, true).is_some());
        stop.store(true, Ordering::Relaxed);
        assert!(acquire_batch(&busy, &stop, true).is_none());
    }

    #[tokio::test]
    async fn round_caps_fanout_and_parallelism() {
        let stop = Arc::new(AtomicBool::new(false));
        let permits = Arc::new(Semaphore::new(0));
        let active = Arc::new(AtomicUsize::new(0));
        let maximum = Arc::new(AtomicUsize::new(0));
        let (started, mut received) = mpsc::unbounded_channel();
        let send_permits = Arc::clone(&permits);
        let send_active = Arc::clone(&active);
        let send_maximum = Arc::clone(&maximum);
        let worker = tokio::spawn(run_bounded(
            (0..100).map(|i| i.to_string()).collect(), stop,
            move |_| {
                let permits = Arc::clone(&send_permits);
                let active = Arc::clone(&send_active);
                let maximum = Arc::clone(&send_maximum);
                let started = started.clone();
                async move {
                    let count = active.fetch_add(1, Ordering::SeqCst) + 1;
                    maximum.fetch_max(count, Ordering::SeqCst);
                    started.send(()).unwrap();
                    let _permit = permits.acquire().await.unwrap();
                    active.fetch_sub(1, Ordering::SeqCst);
                }
            },
        ));
        for _ in 0..PARALLEL_SENDS { received.recv().await.unwrap(); }
        assert!(received.try_recv().is_err());
        // The four tasks unblock and release the permits for the rest.
        permits.add_permits(PARALLEL_SENDS);
        worker.await.unwrap();
        let mut count = PARALLEL_SENDS;
        while received.try_recv().is_ok() { count += 1; }
        assert_eq!(count, MAX_OWN_PER_ROUND);
        assert_eq!(active.load(Ordering::SeqCst), 0);
        assert_eq!(maximum.load(Ordering::SeqCst), PARALLEL_SENDS);
    }

    #[tokio::test]
    async fn stop_prevents_scheduling_the_rest_of_a_round() {
        let stop = Arc::new(AtomicBool::new(false));
        let permits = Arc::new(Semaphore::new(0));
        let (started, mut received) = mpsc::unbounded_channel();
        let send_permits = Arc::clone(&permits);
        let worker = tokio::spawn(run_bounded(
            (0..32).map(|i| i.to_string()).collect(), Arc::clone(&stop),
            move |_| {
                let permits = Arc::clone(&send_permits);
                let started = started.clone();
                async move {
                    started.send(()).unwrap();
                    let _permit = permits.acquire().await.unwrap();
                }
            },
        ));
        for _ in 0..PARALLEL_SENDS { received.recv().await.unwrap(); }
        stop.store(true, Ordering::Relaxed);
        permits.add_permits(PARALLEL_SENDS);
        worker.await.unwrap();
        assert!(received.try_recv().is_err());
    }
}
