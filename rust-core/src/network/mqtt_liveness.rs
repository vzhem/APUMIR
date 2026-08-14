//! Observable MQTT EventLoop liveness without changing network behavior.
//!
//! The probe stores only monotonic timestamps, phases and bounded counters. It does not retain
//! MQTT topics or payloads. A separate watchdog can therefore report a stalled background task
//! even when the core consumer is itself waiting on another bounded channel.

use std::sync::atomic::{AtomicU64, AtomicU8, Ordering};
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum MqttLoopPhase {
    Starting = 0,
    Polling = 1,
    Forwarding = 2,
    Backoff = 3,
    Idle = 4,
    Stopped = 5,
}

impl MqttLoopPhase {
    fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Starting,
            1 => Self::Polling,
            2 => Self::Forwarding,
            3 => Self::Backoff,
            4 => Self::Idle,
            _ => Self::Stopped,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Starting => "starting",
            Self::Polling => "polling",
            Self::Forwarding => "forwarding",
            Self::Backoff => "backoff",
            Self::Idle => "idle",
            Self::Stopped => "stopped",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MqttLivenessSnapshot {
    pub now_ms: u64,
    pub phase: MqttLoopPhase,
    pub phase_since_ms: u64,
    pub last_progress_ms: u64,
    pub polls_started: u64,
    pub polls_completed: u64,
    pub incoming_publishes: u64,
    pub connacks: u64,
    pub notifications_forwarded: u64,
    pub poll_errors: u64,
}

impl MqttLivenessSnapshot {
    pub fn phase_age_ms(self) -> u64 {
        self.now_ms.saturating_sub(self.phase_since_ms)
    }

    pub fn progress_age_ms(self) -> u64 {
        self.now_ms.saturating_sub(self.last_progress_ms)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MqttLivenessAssessment {
    Healthy,
    Stalled {
        phase: MqttLoopPhase,
        stalled_for_ms: u64,
    },
    Stopped,
}

pub fn assess_mqtt_liveness(
    snapshot: MqttLivenessSnapshot,
    stall_after: Duration,
) -> MqttLivenessAssessment {
    if snapshot.phase == MqttLoopPhase::Stopped {
        return MqttLivenessAssessment::Stopped;
    }

    let stall_after_ms = duration_millis_u64(stall_after);
    let phase_age_ms = snapshot.phase_age_ms();
    if phase_age_ms >= stall_after_ms {
        MqttLivenessAssessment::Stalled {
            phase: snapshot.phase,
            stalled_for_ms: phase_age_ms,
        }
    } else {
        MqttLivenessAssessment::Healthy
    }
}

#[derive(Debug)]
pub struct MqttLivenessProbe {
    started_at: Instant,
    phase: AtomicU8,
    phase_since_ms: AtomicU64,
    last_progress_ms: AtomicU64,
    polls_started: AtomicU64,
    polls_completed: AtomicU64,
    incoming_publishes: AtomicU64,
    connacks: AtomicU64,
    notifications_forwarded: AtomicU64,
    poll_errors: AtomicU64,
}

impl Default for MqttLivenessProbe {
    fn default() -> Self {
        Self::new()
    }
}

impl MqttLivenessProbe {
    pub fn new() -> Self {
        Self {
            started_at: Instant::now(),
            phase: AtomicU8::new(MqttLoopPhase::Starting as u8),
            phase_since_ms: AtomicU64::new(0),
            last_progress_ms: AtomicU64::new(0),
            polls_started: AtomicU64::new(0),
            polls_completed: AtomicU64::new(0),
            incoming_publishes: AtomicU64::new(0),
            connacks: AtomicU64::new(0),
            notifications_forwarded: AtomicU64::new(0),
            poll_errors: AtomicU64::new(0),
        }
    }

    pub fn mark_poll_started(&self) {
        self.polls_started.fetch_add(1, Ordering::Relaxed);
        self.set_phase(MqttLoopPhase::Polling);
    }

    pub fn mark_poll_completed(&self) {
        self.polls_completed.fetch_add(1, Ordering::Relaxed);
        self.mark_progress();
        self.set_phase(MqttLoopPhase::Idle);
    }

    pub fn mark_incoming_publish(&self) {
        self.incoming_publishes.fetch_add(1, Ordering::Relaxed);
        self.mark_progress();
    }

    pub fn mark_connack(&self) {
        self.connacks.fetch_add(1, Ordering::Relaxed);
        self.mark_progress();
    }

    pub fn mark_forwarding(&self) {
        self.set_phase(MqttLoopPhase::Forwarding);
    }

    pub fn mark_notification_forwarded(&self) {
        self.notifications_forwarded
            .fetch_add(1, Ordering::Relaxed);
        self.mark_progress();
        self.set_phase(MqttLoopPhase::Idle);
    }

    pub fn mark_poll_error(&self) {
        self.poll_errors.fetch_add(1, Ordering::Relaxed);
        self.mark_progress();
    }

    pub fn mark_backoff(&self) {
        self.set_phase(MqttLoopPhase::Backoff);
    }

    pub fn mark_stopped(&self) {
        self.mark_progress();
        self.set_phase(MqttLoopPhase::Stopped);
    }

    pub fn snapshot(&self) -> MqttLivenessSnapshot {
        MqttLivenessSnapshot {
            now_ms: self.now_ms(),
            phase: MqttLoopPhase::from_u8(self.phase.load(Ordering::Acquire)),
            phase_since_ms: self.phase_since_ms.load(Ordering::Acquire),
            last_progress_ms: self.last_progress_ms.load(Ordering::Acquire),
            polls_started: self.polls_started.load(Ordering::Relaxed),
            polls_completed: self.polls_completed.load(Ordering::Relaxed),
            incoming_publishes: self.incoming_publishes.load(Ordering::Relaxed),
            connacks: self.connacks.load(Ordering::Relaxed),
            notifications_forwarded: self.notifications_forwarded.load(Ordering::Relaxed),
            poll_errors: self.poll_errors.load(Ordering::Relaxed),
        }
    }

    fn set_phase(&self, phase: MqttLoopPhase) {
        let now_ms = self.now_ms();
        self.phase_since_ms.store(now_ms, Ordering::Release);
        self.phase.store(phase as u8, Ordering::Release);
    }

    fn mark_progress(&self) {
        self.last_progress_ms
            .store(self.now_ms(), Ordering::Release);
    }

    fn now_ms(&self) -> u64 {
        u64::try_from(self.started_at.elapsed().as_millis()).unwrap_or(u64::MAX)
    }
}

fn duration_millis_u64(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snapshot(phase: MqttLoopPhase, now_ms: u64, phase_since_ms: u64) -> MqttLivenessSnapshot {
        MqttLivenessSnapshot {
            now_ms,
            phase,
            phase_since_ms,
            last_progress_ms: phase_since_ms,
            polls_started: 0,
            polls_completed: 0,
            incoming_publishes: 0,
            connacks: 0,
            notifications_forwarded: 0,
            poll_errors: 0,
        }
    }

    #[test]
    fn recent_phase_is_healthy() {
        let state = snapshot(MqttLoopPhase::Polling, 89_999, 0);
        assert_eq!(
            assess_mqtt_liveness(state, Duration::from_secs(90)),
            MqttLivenessAssessment::Healthy
        );
    }

    #[test]
    fn threshold_is_stalled_and_reports_exact_phase_age() {
        let state = snapshot(MqttLoopPhase::Forwarding, 95_000, 5_000);
        assert_eq!(
            assess_mqtt_liveness(state, Duration::from_secs(90)),
            MqttLivenessAssessment::Stalled {
                phase: MqttLoopPhase::Forwarding,
                stalled_for_ms: 90_000,
            }
        );
    }

    #[test]
    fn stopped_is_not_reported_as_a_generic_stall() {
        let state = snapshot(MqttLoopPhase::Stopped, 1_000_000, 0);
        assert_eq!(
            assess_mqtt_liveness(state, Duration::from_secs(90)),
            MqttLivenessAssessment::Stopped
        );
    }

    #[test]
    fn probe_tracks_phase_and_counters_without_payload_data() {
        let probe = MqttLivenessProbe::new();
        probe.mark_poll_started();
        probe.mark_poll_completed();
        probe.mark_incoming_publish();
        probe.mark_forwarding();
        probe.mark_notification_forwarded();
        probe.mark_connack();
        probe.mark_poll_error();
        probe.mark_backoff();

        let state = probe.snapshot();
        assert_eq!(state.phase, MqttLoopPhase::Backoff);
        assert_eq!(state.polls_started, 1);
        assert_eq!(state.polls_completed, 1);
        assert_eq!(state.incoming_publishes, 1);
        assert_eq!(state.notifications_forwarded, 1);
        assert_eq!(state.connacks, 1);
        assert_eq!(state.poll_errors, 1);
    }

    #[test]
    fn duration_conversion_saturates() {
        assert_eq!(duration_millis_u64(Duration::MAX), u64::MAX);
    }
}
