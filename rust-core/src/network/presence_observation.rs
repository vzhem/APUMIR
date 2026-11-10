//! A presence frame is a time-limited self-announcement, not an authenticated
//! identity or a guarantee of reachability. Old retained frames may contribute
//! an address candidate for an existing contact, but never an online event.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Mutex;

use crate::engine::events::{CoreEvent, EventBus};
use crate::ffi::network_ffi::{NetworkManagerFfi, NetworkStatus};
use crate::network::presence_scope::{is_live_observation, MAX_FUTURE_SKEW_MS};
use crate::resilience::AddressBook;

#[derive(Debug, PartialEq, Eq)]
pub enum PresenceOutcome {
    Invalid,
    Candidate,
    Live,
}

pub struct PresenceObservation<'a> {
    pub peer_id: &'a str,
    pub display_name: &'a str,
    pub addr: Option<SocketAddr>,
    pub sent_at_ms: i64,
    pub is_local: bool,
}

/// Update a route and its persisted observation in the same order. Callers
/// holding the route lock may use `remember_locked`; all other writers should
/// use `remember_route` so an older retained/forwarded hint cannot race a new
/// live observation back into the in-memory map.
pub fn remember_locked(
    addresses: &mut HashMap<String, SocketAddr>, book: &AddressBook,
    id: &str, addr: SocketAddr, observed_at_ms: i64,
) -> bool {
    if !book.record_if_fresh(id, addr, observed_at_ms) { return false; }
    addresses.insert(id.to_string(), addr);
    true
}

pub fn remember_route(
    addresses: &Mutex<HashMap<String, SocketAddr>>, book: &AddressBook,
    id: &str, addr: SocketAddr, observed_at_ms: i64,
) -> bool {
    remember_locked(&mut addresses.lock().unwrap(), book, id, addr, observed_at_ms)
}

/// Address hints still are not proof that a connection can actually be made.
pub fn observe(
    event_bus: &EventBus,
    network: &NetworkManagerFfi,
    addresses: &Mutex<HashMap<String, SocketAddr>>,
    book: &AddressBook,
    observation: PresenceObservation<'_>,
    now_ms: i64,
    allow_stale_candidate: bool,
) -> PresenceOutcome {
    let PresenceObservation { peer_id, display_name, addr, sent_at_ms, is_local } = observation;
    if !peer_id.starts_with("pk_") || peer_id.len() <= 3 || peer_id.len() > 131
        || now_ms <= 0 || sent_at_ms <= 0
        || sent_at_ms > now_ms.saturating_add(MAX_FUTURE_SKEW_MS)
        || now_ms.saturating_sub(sent_at_ms) > crate::config::defaults::PRESENCE_MAX_AGE_MS
    {
        return PresenceOutcome::Invalid;
    }
    let live = is_live_observation(sent_at_ms, now_ms);
    let observed_at_ms = sent_at_ms.min(now_ms);
    if (live || allow_stale_candidate) && addr.is_some() {
        let addr = addr.expect("checked above");
        let public_key = format!("{}_public", peer_id);
        // Writers using this function acquire the map before the book, so a
        // delayed frame cannot race a newer one into the in-memory map.
        let mut map = addresses.lock().unwrap();
        remember_locked(&mut map, book, peer_id, addr, observed_at_ms);
        remember_locked(&mut map, book, &public_key, addr, observed_at_ms);
    }
    if !live { return PresenceOutcome::Candidate; }

    network.observe_peer_at(peer_id, display_name, observed_at_ms);
    network.set_status(NetworkStatus::Connected);
    event_bus.emit(CoreEvent::PeerDiscovered {
        peer_id: peer_id.to_string(),
        display_name: display_name.to_string(),
        is_local,
        observed_at_ms,
    });
    PresenceOutcome::Live
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::network::presence_scope::{LIVE_PRESENCE_TTL_MS, MAX_FUTURE_SKEW_MS};

    fn frame<'a>(id: &'a str, addr: SocketAddr, sent_at_ms: i64) -> PresenceObservation<'a> {
        PresenceObservation { peer_id: id, display_name: "Alice", addr: Some(addr), sent_at_ms, is_local: false }
    }

    #[test]
    fn live_frame_keeps_time_and_old_retained_frame_is_only_a_candidate() {
        let now = crate::storage::models::now_ms();
        let events = EventBus::with_defaults();
        let network = NetworkManagerFfi::new();
        let addresses = Mutex::new(HashMap::new());
        let book = AddressBook::open(None);
        let addr = "8.8.8.8:7777".parse().unwrap();
        let id = "pk_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let old = now - LIVE_PRESENCE_TTL_MS - 1;
        assert_eq!(observe(&events, &network, &addresses, &book, frame(id, addr, old), now, true), PresenceOutcome::Candidate);
        assert_eq!(addresses.lock().unwrap().get(id), Some(&addr));
        assert!(events.is_empty());
        assert!(network.peers().is_empty());

        let sent = now - 20_000;
        assert_eq!(observe(&events, &network, &addresses, &book, frame(id, addr, sent), now, true), PresenceOutcome::Live);
        assert!(matches!(events.poll(), Some(CoreEvent::PeerDiscovered { observed_at_ms, .. }) if observed_at_ms == sent));
        assert_eq!(network.peers()[0].last_seen_ms, sent);
        assert_eq!(observe(&events, &network, &addresses, &book, frame(id, addr, old), now, true), PresenceOutcome::Candidate);
        assert!(events.is_empty());
        assert_eq!(network.peers()[0].last_seen_ms, sent);
    }

    #[test]
    fn future_or_missing_time_does_not_poison_the_address_book() {
        let now = crate::storage::models::now_ms();
        let events = EventBus::with_defaults();
        let network = NetworkManagerFfi::new();
        let addresses = Mutex::new(HashMap::new());
        let book = AddressBook::open(None);
        let addr = "8.8.8.8:7777".parse().unwrap();
        let id = "pk_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        for at in [0, -1, now + MAX_FUTURE_SKEW_MS + 1, now - crate::config::defaults::PRESENCE_MAX_AGE_MS - 1] {
            assert_eq!(observe(&events, &network, &addresses, &book, frame(id, addr, at), now, true), PresenceOutcome::Invalid);
        }
        assert!(events.is_empty());
        assert!(addresses.lock().unwrap().is_empty());
        assert_eq!(book.len(), 0);
        assert_eq!(observe(&events, &network, &addresses, &book, frame(id, addr, now + 1), now, true), PresenceOutcome::Live);
        assert!(matches!(events.poll(), Some(CoreEvent::PeerDiscovered { observed_at_ms, .. }) if observed_at_ms == now));
    }

    #[test]
    fn concurrent_old_and_new_routes_finish_in_newest_order() {
        use std::sync::{Arc, Barrier};
        let now = crate::storage::models::now_ms();
        let routes = Arc::new(Mutex::new(HashMap::new()));
        let book = Arc::new(AddressBook::open(None));
        let barrier = Arc::new(Barrier::new(3));
        let id = "pk_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let old: SocketAddr = "1.1.1.1:7777".parse().unwrap();
        let new: SocketAddr = "8.8.8.8:7777".parse().unwrap();
        let mut threads = Vec::new();
        for (addr, at) in [(old, now - 10_000), (new, now)] {
            let routes = Arc::clone(&routes);
            let book = Arc::clone(&book);
            let barrier = Arc::clone(&barrier);
            threads.push(std::thread::spawn(move || {
                barrier.wait();
                remember_route(&routes, &book, id, addr, at);
            }));
        }
        barrier.wait();
        for thread in threads { thread.join().unwrap(); }
        assert_eq!(routes.lock().unwrap().get(id), Some(&new));
        assert_eq!(book.observations()[id], (new, now));
    }

    #[test]
    fn older_different_address_never_overwrites_newer_memory_or_book() {
        let now = crate::storage::models::now_ms();
        let events = EventBus::with_defaults();
        let network = NetworkManagerFfi::new();
        let addresses = Mutex::new(HashMap::new());
        let book = AddressBook::open(None);
        let id = "pk_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let new = "8.8.8.8:7777".parse().unwrap();
        let old = "1.1.1.1:7777".parse().unwrap();
        observe(&events, &network, &addresses, &book, frame(id, new, now - 10_000), now, true);
        observe(&events, &network, &addresses, &book, frame(id, old, now - 20_000), now, true);
        let map = addresses.lock().unwrap();
        assert_eq!(map.get(id), Some(&new));
        assert_eq!(map.get(&format!("{}_public", id)), Some(&new));
        assert_eq!(book.observations()[id], (new, now - 10_000));
        assert_eq!(network.peers()[0].last_seen_ms, now - 10_000);
    }
}
