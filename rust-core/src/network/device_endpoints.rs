//! Device-level LAN addressing for multi-device accounts (P3 of `docs/MULTI_DEVICE_IDENTITY.md`).
//!
//! Devices of one account share a `node_id`, so the mDNS-based peer table cannot tell them
//! apart. This module keeps the device key id of this installation and the LAN endpoint of every
//! other device that was discovered. Pairing messages use it to reach one exact device.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Mutex;

static LOCAL_DEVICE_ID: Mutex<Option<String>> = Mutex::new(None);
static DEVICE_ENDPOINTS: Mutex<Option<HashMap<String, SocketAddr>>> = Mutex::new(None);
static CONTACT_HINTS: Mutex<Vec<String>> = Mutex::new(Vec::new());

/// Lower-case hex of the 16-byte device id.
pub fn set_local_device_id_hex(device_id_hex: Option<String>) {
    if let Ok(mut guard) = LOCAL_DEVICE_ID.lock() {
        *guard = device_id_hex.map(|value| value.to_ascii_lowercase());
    }
}

pub fn local_device_id_hex() -> Option<String> {
    LOCAL_DEVICE_ID.lock().ok().and_then(|guard| guard.clone())
}

pub fn record_device_endpoint(device_id_hex: &str, addr: SocketAddr) {
    if let Ok(mut guard) = DEVICE_ENDPOINTS.lock() {
        guard
            .get_or_insert_with(HashMap::new)
            .insert(device_id_hex.to_ascii_lowercase(), addr);
    }
}

pub fn device_endpoint(device_id_hex: &str) -> Option<SocketAddr> {
    DEVICE_ENDPOINTS
        .lock()
        .ok()
        .and_then(|guard| guard.as_ref().and_then(|map| map.get(&device_id_hex.to_ascii_lowercase()).copied()))
}

/// This device's own contact hints (socket addresses as text), printed into pairing QR
/// offers so a scanning device can dial us directly. The engine refreshes them as the
/// network changes; an empty list just means "no hint known yet".
pub fn set_local_contact_hints(hints: Vec<String>) {
    if let Ok(mut guard) = CONTACT_HINTS.lock() {
        *guard = hints;
    }
}

pub fn local_contact_hints() -> Vec<String> {
    CONTACT_HINTS
        .lock()
        .map(|guard| guard.clone())
        .unwrap_or_default()
}

/// Is this hex the id of the current device? An empty local id never matches.
pub fn is_local_device(device_id_hex: &str) -> bool {
    local_device_id_hex()
        .map(|local| local.eq_ignore_ascii_case(device_id_hex))
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex as TestMutex;

    static LOCK: TestMutex<()> = TestMutex::new(());

    #[test]
    fn local_device_and_endpoints_are_keyed_by_device_id() {
        let _guard = LOCK.lock().unwrap();
        set_local_device_id_hex(Some("AA11".to_string()));
        assert!(is_local_device("aa11"));
        assert!(!is_local_device("bb22"));
        let addr: SocketAddr = "192.168.1.20:7777".parse().unwrap();
        record_device_endpoint("BB22", addr);
        assert_eq!(device_endpoint("bb22"), Some(addr));
        assert_eq!(device_endpoint("cc33"), None);
        set_local_device_id_hex(None);
        assert!(!is_local_device("aa11"));
    }

    #[test]
    fn contact_hints_round_trip() {
        let _guard = LOCK.lock().unwrap();
        set_local_contact_hints(vec!["192.168.1.20:7777".to_string()]);
        assert_eq!(local_contact_hints(), vec!["192.168.1.20:7777".to_string()]);
        set_local_contact_hints(Vec::new());
        assert!(local_contact_hints().is_empty());
    }
}
