//! Monotonic periodic MQTT maintenance independent of the number of messages.

use std::time::{Duration, Instant};

const PRESENCE_INTERVAL: Duration = Duration::from_secs(60);
const GOSSIP_INTERVAL: Duration = Duration::from_secs(300);

pub(super) struct MqttCadence {
    last_presence: Instant,
    last_gossip: Instant,
}

impl MqttCadence {
    pub(super) fn new(now: Instant) -> Self {
        Self { last_presence: now, last_gossip: now }
    }

    /// A reconnected session announces immediately; restart its minute timer.
    pub(super) fn reset_presence(&mut self, now: Instant) { self.last_presence = now; }

    pub(super) fn take_presence(&mut self, now: Instant) -> bool {
        if now.saturating_duration_since(self.last_presence) < PRESENCE_INTERVAL { return false; }
        self.last_presence = now;
        true
    }

    /// Called during the presence turn. Missed periods are skipped, not replayed.
    pub(super) fn take_gossip(&mut self, now: Instant) -> bool {
        if now.saturating_duration_since(self.last_gossip) < GOSSIP_INTERVAL { return false; }
        self.last_gossip = now;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_burst_of_wakes_never_accelerates_presence_or_gossip() {
        let start = Instant::now();
        let mut cadence = MqttCadence::new(start);
        for _ in 0..10_000 {
            assert!(!cadence.take_presence(start + Duration::from_secs(59)));
            assert!(!cadence.take_gossip(start + Duration::from_secs(59)));
        }
        assert!(cadence.take_presence(start + PRESENCE_INTERVAL));
        assert!(!cadence.take_presence(start + PRESENCE_INTERVAL));
        assert!(!cadence.take_gossip(start + Duration::from_secs(299)));
        assert!(cadence.take_gossip(start + GOSSIP_INTERVAL));
        assert!(!cadence.take_gossip(start + GOSSIP_INTERVAL));
    }

    #[test]
    fn late_wakes_skip_missed_periods_and_restart_resets_only_presence() {
        let start = Instant::now();
        let mut cadence = MqttCadence::new(start);
        let late = start + Duration::from_secs(1_000);
        assert!(cadence.take_presence(late));
        assert!(cadence.take_gossip(late));
        assert!(!cadence.take_presence(late));
        assert!(!cadence.take_gossip(late));
        cadence.reset_presence(late + Duration::from_secs(30));
        assert!(!cadence.take_presence(late + Duration::from_secs(89)));
        assert!(cadence.take_presence(late + Duration::from_secs(90)));
        assert!(cadence.take_gossip(late + GOSSIP_INTERVAL));
    }
}
