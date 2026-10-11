//! Local online-transit admission. Advertised capacity is never authority to bypass this.
//! Separate from durable custody; already accepted routes may drain after disabling.
#[derive(Debug, Clone, Copy)]
pub struct HelperPolicy {
    pub enabled: bool,
    pub max_routes: usize,
    pub max_bytes_per_minute: usize,
    pub max_relays_per_minute: usize,
}
impl Default for HelperPolicy {
    fn default() -> Self { Self::bounded(true, 8, 4 * 1024 * 1024, 120) }
}
impl HelperPolicy {
    pub fn bounded(enabled: bool, routes: u32, bytes: u32, relays: u32) -> Self {
        Self { enabled: enabled && routes > 0 && bytes > 0 && relays > 0,
            max_routes: (routes as usize).min(32),
            // At least half of the existing aggregate 8 MiB admission remains for own incoming data.
            max_bytes_per_minute: (bytes as usize).min(4 * 1024 * 1024),
            max_relays_per_minute: (relays as usize).min(240) }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn untrusted_or_buggy_callers_cannot_remove_safety_caps() {
        let p = HelperPolicy::bounded(true, u32::MAX, u32::MAX, u32::MAX);
        assert_eq!((p.max_routes, p.max_bytes_per_minute, p.max_relays_per_minute), (32, 4 * 1024 * 1024, 240));
        for p in [HelperPolicy::bounded(false, 8, 100, 2), HelperPolicy::bounded(true, 0, 100, 2),
            HelperPolicy::bounded(true, 8, 0, 2), HelperPolicy::bounded(true, 8, 100, 0)] { assert!(!p.enabled); }
    }
}
