//! Feature-gated status-only secondary MQTT session for r4.3.
//!
//! This observer proves that a second independent broker can maintain its own EventLoop and
//! ConnAck state. It deliberately exposes no subscribe or publish operation. User envelopes stay
//! on the verified primary HiveMQ session until r4.4 enables bounded fanout and production dedup.

use std::sync::atomic::{AtomicU64, AtomicU8, Ordering};
use std::sync::Arc;
use std::time::Duration;

use rumqttc::{AsyncClient, Event, MqttOptions, Packet};

const SECONDARY_BROKER_ID: &str = "emqx";
const SECONDARY_BROKER_HOST: &str = "broker.emqx.io";
const SECONDARY_BROKER_PORT: u16 = 1883;
const SECONDARY_KEEP_ALIVE: Duration = Duration::from_secs(60);
const SECONDARY_BACKOFF_MAX_SECS: u64 = 30;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub(crate) enum SecondaryObserverState {
    Connecting = 0,
    Connected = 1,
    Backoff = 2,
    Stopped = 3,
}

impl SecondaryObserverState {
    fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Connecting,
            1 => Self::Connected,
            2 => Self::Backoff,
            _ => Self::Stopped,
        }
    }

    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::Connecting => "connecting",
            Self::Connected => "connected",
            Self::Backoff => "backoff",
            Self::Stopped => "stopped",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct SecondaryObserverSnapshot {
    pub state: SecondaryObserverState,
    pub polls_started: u64,
    pub polls_completed: u64,
    pub connacks: u64,
    pub poll_errors: u64,
}

#[derive(Debug)]
struct SecondaryObserverProbe {
    state: AtomicU8,
    polls_started: AtomicU64,
    polls_completed: AtomicU64,
    connacks: AtomicU64,
    poll_errors: AtomicU64,
}

impl SecondaryObserverProbe {
    fn new() -> Self {
        Self {
            state: AtomicU8::new(SecondaryObserverState::Connecting as u8),
            polls_started: AtomicU64::new(0),
            polls_completed: AtomicU64::new(0),
            connacks: AtomicU64::new(0),
            poll_errors: AtomicU64::new(0),
        }
    }

    fn mark_connecting(&self) {
        self.set_state(SecondaryObserverState::Connecting);
    }

    fn mark_poll_started(&self) {
        self.polls_started.fetch_add(1, Ordering::Relaxed);
    }

    fn mark_poll_completed(&self) {
        self.polls_completed.fetch_add(1, Ordering::Relaxed);
    }

    fn mark_connack(&self) {
        self.connacks.fetch_add(1, Ordering::Relaxed);
        self.set_state(SecondaryObserverState::Connected);
    }

    fn mark_error(&self) {
        self.poll_errors.fetch_add(1, Ordering::Relaxed);
        self.set_state(SecondaryObserverState::Backoff);
    }

    fn mark_stopped(&self) {
        self.set_state(SecondaryObserverState::Stopped);
    }

    fn set_state(&self, state: SecondaryObserverState) {
        self.state.store(state as u8, Ordering::Release);
    }

    fn snapshot(&self) -> SecondaryObserverSnapshot {
        SecondaryObserverSnapshot {
            state: SecondaryObserverState::from_u8(self.state.load(Ordering::Acquire)),
            polls_started: self.polls_started.load(Ordering::Relaxed),
            polls_completed: self.polls_completed.load(Ordering::Relaxed),
            connacks: self.connacks.load(Ordering::Relaxed),
            poll_errors: self.poll_errors.load(Ordering::Relaxed),
        }
    }
}

pub(crate) struct SecondaryBrokerObserver {
    task: Option<tokio::task::JoinHandle<()>>,
    probe: Arc<SecondaryObserverProbe>,
}

impl SecondaryBrokerObserver {
    pub(crate) fn spawn(node_id: &str) -> Self {
        let suffix = &node_id[..node_id.len().min(8)];
        let client_id = format!("apu_observe_{suffix}");
        let mut options = MqttOptions::new(
            client_id,
            SECONDARY_BROKER_HOST,
            SECONDARY_BROKER_PORT,
        );
        options.set_keep_alive(SECONDARY_KEEP_ALIVE);
        options.set_clean_session(true);

        // Keep the AsyncClient alive so rumqttc's bounded request channel remains valid. r4.3
        // never calls subscribe or publish on it; only EventLoop::poll performs network I/O.
        let (client, mut eventloop) = AsyncClient::new(options, 1);
        let probe = Arc::new(SecondaryObserverProbe::new());
        let task_probe = Arc::clone(&probe);

        tracing::info!(
            "MQTT SECONDARY STATUS: broker={} state=starting mode=observe_only subscriptions=0 publishes=0",
            SECONDARY_BROKER_ID
        );

        let task = tokio::spawn(async move {
            let _client = client;
            let mut backoff_secs = 1u64;

            loop {
                task_probe.mark_poll_started();
                let result = eventloop.poll().await;
                task_probe.mark_poll_completed();

                match result {
                    Ok(Event::Incoming(Packet::ConnAck(_))) => {
                        backoff_secs = 1;
                        task_probe.mark_connack();
                        let snapshot = task_probe.snapshot();
                        tracing::info!(
                            "MQTT SECONDARY STATUS: broker={} state={} connacks={} polls={}/{} poll_errors={} subscriptions=0 publishes=0",
                            SECONDARY_BROKER_ID,
                            snapshot.state.as_str(),
                            snapshot.connacks,
                            snapshot.polls_completed,
                            snapshot.polls_started,
                            snapshot.poll_errors
                        );
                    }
                    Ok(_) => {
                        backoff_secs = 1;
                    }
                    Err(error) => {
                        task_probe.mark_error();
                        let snapshot = task_probe.snapshot();
                        tracing::warn!(
                            "MQTT SECONDARY STATUS: broker={} state={} poll_errors={} retry_in={}s subscriptions=0 publishes=0 error={}",
                            SECONDARY_BROKER_ID,
                            snapshot.state.as_str(),
                            snapshot.poll_errors,
                            backoff_secs,
                            error
                        );
                        tokio::time::sleep(Duration::from_secs(backoff_secs)).await;
                        backoff_secs = next_secondary_backoff_secs(
                            backoff_secs,
                            SECONDARY_BACKOFF_MAX_SECS,
                        );
                        task_probe.mark_connecting();
                    }
                }
            }
        });

        Self {
            task: Some(task),
            probe,
        }
    }

    pub(crate) fn snapshot(&self) -> SecondaryObserverSnapshot {
        self.probe.snapshot()
    }
}

impl Drop for SecondaryBrokerObserver {
    fn drop(&mut self) {
        self.probe.mark_stopped();
        if let Some(task) = self.task.take() {
            task.abort();
        }
    }
}

pub(crate) fn next_secondary_backoff_secs(current_secs: u64, maximum_secs: u64) -> u64 {
    current_secs
        .saturating_mul(2)
        .max(1)
        .min(maximum_secs.max(1))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn observer_starts_connecting_with_zero_bounded_counters() {
        let probe = SecondaryObserverProbe::new();
        let snapshot = probe.snapshot();
        assert_eq!(snapshot.state, SecondaryObserverState::Connecting);
        assert_eq!(snapshot.polls_started, 0);
        assert_eq!(snapshot.polls_completed, 0);
        assert_eq!(snapshot.connacks, 0);
        assert_eq!(snapshot.poll_errors, 0);
    }

    #[test]
    fn observer_tracks_connack_and_error_without_payload_state() {
        let probe = SecondaryObserverProbe::new();
        probe.mark_poll_started();
        probe.mark_poll_completed();
        probe.mark_connack();
        let connected = probe.snapshot();
        assert_eq!(connected.state, SecondaryObserverState::Connected);
        assert_eq!(connected.polls_started, 1);
        assert_eq!(connected.polls_completed, 1);
        assert_eq!(connected.connacks, 1);

        probe.mark_error();
        let backoff = probe.snapshot();
        assert_eq!(backoff.state, SecondaryObserverState::Backoff);
        assert_eq!(backoff.poll_errors, 1);
    }

    #[test]
    fn observer_state_labels_are_stable_for_android_harnesses() {
        assert_eq!(SecondaryObserverState::Connecting.as_str(), "connecting");
        assert_eq!(SecondaryObserverState::Connected.as_str(), "connected");
        assert_eq!(SecondaryObserverState::Backoff.as_str(), "backoff");
        assert_eq!(SecondaryObserverState::Stopped.as_str(), "stopped");
    }

    #[test]
    fn secondary_backoff_is_exponential_and_capped() {
        let mut current = 1u64;
        let mut observed = Vec::new();
        for _ in 0..6 {
            current = next_secondary_backoff_secs(current, 30);
            observed.push(current);
        }
        assert_eq!(observed, vec![2, 4, 8, 16, 30, 30]);
        assert_eq!(next_secondary_backoff_secs(0, 30), 1);
        assert_eq!(next_secondary_backoff_secs(u64::MAX, 30), 30);
    }
}
