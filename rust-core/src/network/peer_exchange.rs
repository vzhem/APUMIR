//! Bounded, direct peer exchange. Public endpoints are candidates, not proof of an open NAT.
//!
//! A request shares recent owner-signed address leases and asks for several contacts at once.
//! Requests/replies are signed with the existing Android Ed25519 sidecar and bound to the actual
//! QUIC TLS session. Legacy routing IDs are not Ed25519 hashes: their discovery keys use persistent
//! TOFU pins, never pins learned from somebody else's gossip. Forwarded observations never make a
//! third party online and never renew an owner's lease. No broker/DNS/STUN service is used here.

use std::collections::{HashMap, HashSet, VecDeque};
use std::fs;
use std::io::Read;
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

use rand::{rngs::OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::crypto::keys::Ed25519KeyPair;
use crate::crypto::signing_identity::InstalledSigningIdentity;

pub const EXCHANGE_MAGIC: &[u8] = b"APUX1\n";
pub const MAX_EXCHANGE_BYTES: usize = 16 * 1024;
pub const MAX_RECORDS: usize = 16;
pub const MAX_SHARED_IN_REQUEST: usize = 8;
pub const MAX_TARGETS: usize = 32;
pub const MAX_DIRECTORY_ENTRIES: usize = 512;
pub const MAX_PER_NODE_ADDRESSES: usize = 2;
pub const MAX_PARALLEL_EXCHANGES: usize = 2;
pub const MAX_REQUESTS_PER_MINUTE: usize = 6;
pub const ONLINE_WINDOW_MS: i64 = 2 * 60_000;
pub const ADDRESS_LIFETIME_MS: i64 = 24 * 60 * 60_000;
pub const REPLY_TIMEOUT_MS: i64 = 15_000;
pub const HEALTHY_REFRESH_MS: i64 = 10 * 60_000;
pub const BLOOM_BYTES: usize = 512;
const SELF_RENEW_MS: i64 = 15 * 60_000;
const FRAME_LIFETIME_MS: i64 = 2 * 60_000;
const CLOCK_SKEW_MS: i64 = 2 * 60_000;
const MAX_BACKOFF_MS: i64 = 30 * 60_000;
const MAX_PINNED_IDENTITIES: usize = 2048;
const MAX_STORE_BYTES: u64 = 1024 * 1024;
const SAVE_INTERVAL_MS: i64 = 30_000;
const LEASE_DOMAIN: &[u8] = b"apu-peer-address-v1\0";
const FRAME_DOMAIN: &[u8] = b"apu-peer-exchange-v1\0";

pub fn valid_node_id(value: &str) -> bool {
    value.strip_prefix("pk_").map(|hex| {
        matches!(hex.len(), 32 | 64)
            && hex.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
    }).unwrap_or(false)
}

/// Global unicast only. In particular CGNAT, LAN, link-local, documentation and mapped LAN
/// addresses must not be distributed as Internet bootstrap endpoints. A global IP can still be
/// behind NAT/firewall; only a successful exchange proves reachability from this device.
pub fn is_public_endpoint(addr: SocketAddr) -> bool {
    if addr.port() == 0 { return false; }
    match addr.ip() {
        IpAddr::V4(ip) => {
            let [a, b, c, _] = ip.octets();
            !(a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 100 && (64..=127).contains(&b))
                || (a == 169 && b == 254) || (a == 172 && (16..=31).contains(&b))
                || (a == 192 && b == 168) || (a == 192 && b == 0 && c == 0)
                || (a == 192 && b == 0 && c == 2) || (a == 192 && b == 88 && c == 99)
                || (a == 198 && (b == 18 || b == 19))
                || (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113))
        }
        IpAddr::V6(ip) => {
            let s = ip.segments();
            // Current globally allocated unicast space, excluding reserved transition/test space.
            s[0] & 0xe000 == 0x2000
                && !(s[0] == 0x2001 && (s[1] == 0 || s[1] == 0x0db8
                    || (s[1] & 0xfff0) == 0x0010 || (s[1] & 0xfff0) == 0x0020))
        }
    }
}

pub fn usable_local_endpoint(addr: SocketAddr) -> bool {
    addr.port() != 0 && !addr.ip().is_unspecified() && !addr.ip().is_multicast()
        && !addr.ip().is_loopback()
}

fn canonical<T: Serialize>(domain: &[u8], value: &T) -> Option<Vec<u8>> {
    let encoded = serde_json::to_vec(value).ok()?;
    let mut bytes = Vec::with_capacity(domain.len() + encoded.len());
    bytes.extend_from_slice(domain);
    bytes.extend_from_slice(&encoded);
    Some(bytes)
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct AddressClaims {
    pub node_id: String,
    pub endpoint: SocketAddr,
    pub issued_at_ms: i64,
    pub expires_at_ms: i64,
    pub signing_key: [u8; 32],
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct AddressLease {
    pub claims: AddressClaims,
    pub signature: Vec<u8>,
}

impl AddressLease {
    pub fn create(identity: &InstalledSigningIdentity, endpoint: SocketAddr, now: i64) -> Option<Self> {
        if !is_public_endpoint(endpoint) || now <= 0 { return None; }
        let claims = AddressClaims {
            node_id: identity.legacy_routing_node_id().to_string(), endpoint,
            issued_at_ms: now, expires_at_ms: now.checked_add(ADDRESS_LIFETIME_MS)?,
            signing_key: identity.public_key().try_into().ok()?,
        };
        let signature = identity.sign_security_payload(&canonical(LEASE_DOMAIN, &claims)?);
        Some(Self { claims, signature })
    }

    pub fn verify(&self, now: i64) -> bool {
        let c = &self.claims;
        valid_node_id(&c.node_id) && is_public_endpoint(c.endpoint)
            && c.issued_at_ms > 0 && c.issued_at_ms <= now.saturating_add(CLOCK_SKEW_MS)
            && c.expires_at_ms > now && c.expires_at_ms > c.issued_at_ms
            && c.expires_at_ms.saturating_sub(c.issued_at_ms) <= ADDRESS_LIFETIME_MS
            && self.signature.len() == 64
            && canonical(LEASE_DOMAIN, c).map(|bytes| {
                Ed25519KeyPair::verify(&c.signing_key, &bytes, &self.signature).is_ok()
            }).unwrap_or(false)
    }

    fn fingerprint(&self) -> [u8; 32] {
        let mut hash = Sha256::new();
        if let Some(bytes) = canonical(LEASE_DOMAIN, &self.claims) { hash.update(bytes); }
        hash.update(&self.signature);
        hash.finalize().into()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct SharedRecord {
    lease: AddressLease,
    /// An observation by the sender, not proof for the recipient. Never used as local online time.
    checked_at_ms: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct ExchangeClaims {
    version: u8,
    reply: bool,
    from: String,
    to: String,
    nonce: [u8; 16],
    created_at_ms: i64,
    channel_binding: [u8; 32],
    signing_key: [u8; 32],
    known: Vec<u8>,
    targets: Vec<String>,
    records: Vec<SharedRecord>,
    /// The actual requester endpoint on this QUIC session; useful even when STUN is unavailable.
    observed_requester: Option<SocketAddr>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct SignedExchange {
    claims: ExchangeClaims,
    signature: Vec<u8>,
}

fn encode(identity: &InstalledSigningIdentity, claims: ExchangeClaims) -> Option<Vec<u8>> {
    let signature = identity.sign_security_payload(&canonical(FRAME_DOMAIN, &claims)?);
    let mut bytes = EXCHANGE_MAGIC.to_vec();
    bytes.extend(serde_json::to_vec(&SignedExchange { claims, signature }).ok()?);
    (bytes.len() <= MAX_EXCHANGE_BYTES).then_some(bytes)
}

fn decode(bytes: &[u8], recipient: &str, binding: [u8; 32], now: i64) -> Option<SignedExchange> {
    if bytes.len() > MAX_EXCHANGE_BYTES { return None; }
    let signed: SignedExchange = serde_json::from_slice(bytes.strip_prefix(EXCHANGE_MAGIC)?).ok()?;
    let c = &signed.claims;
    if c.version != 1 || c.to != recipient || c.from == recipient || !valid_node_id(&c.from)
        || !valid_node_id(&c.to) || c.channel_binding != binding || c.nonce == [0; 16]
        || c.created_at_ms <= 0 || c.created_at_ms > now.saturating_add(CLOCK_SKEW_MS)
        || now.saturating_sub(c.created_at_ms) > FRAME_LIFETIME_MS
        || c.known.len() != BLOOM_BYTES || c.targets.len() > MAX_TARGETS
        || c.targets.iter().any(|id| !valid_node_id(id)) || c.records.len() > MAX_RECORDS
        || (!c.reply && c.records.len() > MAX_SHARED_IN_REQUEST) || signed.signature.len() != 64
    { return None; }
    Ed25519KeyPair::verify(&c.signing_key, &canonical(FRAME_DOMAIN, c)?, &signed.signature).ok()?;
    Some(signed)
}

fn bloom_add(bloom: &mut [u8], digest: [u8; 32]) {
    for pair in digest[..8].chunks_exact(2) {
        let bit = u16::from_be_bytes([pair[0], pair[1]]) as usize % (BLOOM_BYTES * 8);
        bloom[bit / 8] |= 1 << (bit % 8);
    }
}

fn bloom_has(bloom: &[u8], digest: [u8; 32]) -> bool {
    bloom.len() == BLOOM_BYTES && digest[..8].chunks_exact(2).all(|pair| {
        let bit = u16::from_be_bytes([pair[0], pair[1]]) as usize % (BLOOM_BYTES * 8);
        bloom[bit / 8] & (1 << (bit % 8)) != 0
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct CachedRecord {
    lease: AddressLease,
    #[serde(default)]
    locally_checked_at_ms: i64,
    #[serde(default)]
    reported_checked_at_ms: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct Attempt {
    #[serde(default)] verified_at_ms: i64,
    #[serde(default)] failed_at_ms: i64,
    #[serde(default)] failures: u32,
    #[serde(default)] retry_at_ms: i64,
    #[serde(skip)] remote_bloom: Vec<u8>,
}

#[derive(Serialize, Deserialize)]
struct StoredDirectory {
    version: u8,
    records: Vec<CachedRecord>,
    pins: Vec<(String, [u8; 32])>,
    attempts: Vec<(String, SocketAddr, Attempt)>,
}

#[derive(Clone)]
struct Pending {
    nonce: [u8; 16],
    endpoint: SocketAddr,
    deadline_ms: i64,
    /// Delivery of a stream to an older app is not failure of its address.
    stream_delivered: bool,
    binding: Option<[u8; 32]>,
    had_targets: bool,
}

#[derive(Default)]
struct State {
    records: HashMap<(String, SocketAddr), CachedRecord>,
    pins: HashMap<String, [u8; 32]>,
    attempts: HashMap<(String, SocketAddr), Attempt>,
    pending: HashMap<String, Pending>,
    received: VecDeque<(String, [u8; 16], i64)>,
    sent: VecDeque<i64>,
    ingress: VecDeque<i64>,
    last_inbound: HashMap<String, i64>,
    self_lease: Option<AddressLease>,
    observed_own: Option<(SocketAddr, i64)>,
    next_round_ms: i64,
    saved_at_ms: i64,
    target_cursor: usize,
    dirty: bool,
}

#[derive(Debug, Clone)]
pub struct Candidate {
    pub node_id: String,
    pub endpoint: SocketAddr,
    pub learned_at_ms: i64,
    pub live_connection: bool,
}

#[derive(Clone)]
pub struct PreparedExchange {
    pub peer_id: String,
    pub endpoint: SocketAddr,
    claims: ExchangeClaims,
}

impl PreparedExchange {
    /// Called by the transport AFTER acquiring the actual connection, not with a made-up binding.
    pub fn encode(&self, signer: &InstalledSigningIdentity, binding: [u8; 32]) -> Option<Vec<u8>> {
        let mut claims = self.claims.clone();
        claims.channel_binding = binding;
        encode(signer, claims)
    }
}

pub struct ExchangeEffect {
    pub sender_id: String,
    pub response: Option<Vec<u8>>,
    pub candidates: Vec<(String, SocketAddr, i64)>,
    pub sender_endpoint: SocketAddr,
}

pub struct PeerExchange {
    path: Option<PathBuf>,
    state: Mutex<State>,
    write_lock: Mutex<()>,
    wake: AtomicBool,
}

impl PeerExchange {
    pub fn open(path: Option<PathBuf>) -> Self {
        let mut state = State::default();
        let now = crate::storage::models::now_ms();
        if let Some(p) = &path {
            if let Ok(file) = fs::File::open(p) {
                let mut bytes = Vec::new();
                if file.take(MAX_STORE_BYTES + 1).read_to_end(&mut bytes).is_ok()
                    && bytes.len() as u64 <= MAX_STORE_BYTES
                {
                    if let Ok(stored) = serde_json::from_slice::<StoredDirectory>(&bytes) {
                        if stored.version == 1 {
                            for (id, key) in stored.pins.into_iter().take(MAX_PINNED_IDENTITIES) {
                                if valid_node_id(&id) { state.pins.insert(id, key); }
                            }
                            for record in stored.records.into_iter().take(MAX_DIRECTORY_ENTRIES) {
                                let c = &record.lease.claims;
                                if record.lease.verify(now) && state.pins.get(&c.node_id)
                                    .map(|key| key == &c.signing_key).unwrap_or(true)
                                {
                                    state.records.insert((c.node_id.clone(), c.endpoint), CachedRecord {
                                        locally_checked_at_ms: record.locally_checked_at_ms.max(0).min(now),
                                        reported_checked_at_ms: record.reported_checked_at_ms.max(0).min(now), ..record
                                    });
                                }
                            }
                            for (id, addr, mut attempt) in stored.attempts.into_iter().take(MAX_DIRECTORY_ENTRIES) {
                                if valid_node_id(&id) && usable_local_endpoint(addr) {
                                    attempt.retry_at_ms = attempt.retry_at_ms.min(now.saturating_add(MAX_BACKOFF_MS));
                                    attempt.verified_at_ms = attempt.verified_at_ms.max(0).min(now);
                                    attempt.failed_at_ms = attempt.failed_at_ms.max(0).min(now);
                                    state.attempts.insert((id, addr), attempt);
                                }
                            }
                        }
                    }
                }
            }
        }
        Self { path, state: Mutex::new(state), write_lock: Mutex::new(()), wake: AtomicBool::new(true) }
    }

    /// Retry the plan, NOT failures/cooldowns. Repeated manual triggers cannot cause a storm.
    pub fn wake(&self) { self.wake.store(true, Ordering::Relaxed); }

    pub fn reset_session(&self) {
        let mut s = self.state.lock().unwrap();
        s.pending.clear(); s.next_round_ms = 0; s.observed_own = None; s.self_lease = None;
        self.wake();
    }

    pub fn due(&self, now: i64) -> bool {
        if self.wake.load(Ordering::Relaxed) { return true; }
        let s = self.state.lock().unwrap();
        now >= s.next_round_ms || s.pending.values().any(|p| now >= p.deadline_ms)
    }

    pub fn observed_own_addr(&self, now: i64) -> Option<SocketAddr> {
        self.state.lock().unwrap().observed_own.filter(|(_, at)| now.saturating_sub(*at) <= ONLINE_WINDOW_MS)
            .map(|(addr, _)| addr)
    }

    pub fn candidates(&self, now: i64) -> Vec<(String, SocketAddr, i64)> {
        self.state.lock().unwrap().records.values().filter(|r| r.lease.claims.expires_at_ms > now)
            .map(|r| (r.lease.claims.node_id.clone(), r.lease.claims.endpoint,
                r.lease.claims.issued_at_ms.max(r.reported_checked_at_ms))).collect()
    }

    pub fn needs_lookup(&self, id: &str, addr: Option<SocketAddr>, learned_at: i64, now: i64) -> bool {
        let Some(addr) = addr else { return true; };
        let s = self.state.lock().unwrap();
        let failed = s.attempts.get(&(id.to_string(), addr)).map(|a| {
            a.failed_at_ms >= a.verified_at_ms && a.failures > 0
        }).unwrap_or(false);
        failed || learned_at <= 0 || now.saturating_sub(learned_at) > ADDRESS_LIFETIME_MS
    }

    pub fn priority(&self, candidate: &Candidate, now: i64) -> (u8, i64, String, SocketAddr) {
        let s = self.state.lock().unwrap();
        priority(&s, candidate, now)
    }

    /// Save the actual session/address chosen by the pool. Replies must match this exact
    /// exporter as well as the random nonce, not merely a self-reported sender ID.
    pub fn bind_request(&self, request: &PreparedExchange, binding: [u8; 32], remote: SocketAddr) -> bool {
        let mut s = self.state.lock().unwrap();
        let Some(p) = s.pending.get_mut(&request.peer_id) else { return false; };
        if p.nonce != request.claims.nonce { return false; }
        p.endpoint = remote;
        p.binding = Some(binding);
        true
    }

    pub fn note_route_failure(&self, peer: &str, endpoint: Option<SocketAddr>, now: i64) {
        if let Some(addr) = endpoint {
            fail(&mut self.state.lock().unwrap(), peer, addr, now);
        }
        self.wake();
    }

    pub fn mark_send_result(&self, peer: &str, endpoint: SocketAddr, delivered: bool, now: i64) {
        let mut s = self.state.lock().unwrap();
        if delivered {
            if let Some(p) = s.pending.get_mut(peer) { p.stream_delivered = true; }
        } else if let Some(p) = s.pending.remove(peer) {
            fail(&mut s, peer, p.endpoint, now);
        } else {
            // An authenticated reply can arrive before the stream-completion callback.
            // Do not downgrade a just-proven session because that callback was delayed.
            let recently_verified = s.attempts.get(&(peer.to_string(), endpoint))
                .map(|a| now.saturating_sub(a.verified_at_ms) < REPLY_TIMEOUT_MS).unwrap_or(false);
            if !recently_verified { fail(&mut s, peer, endpoint, now); }
        }
    }

    pub fn plan(
        &self, identity: &InstalledSigningIdentity, own_addr: Option<SocketAddr>,
        candidates: Vec<Candidate>, targets: &[String], now: i64,
    ) -> Vec<PreparedExchange> {
        let mut s = self.state.lock().unwrap();
        cleanup(&mut s, now);
        let expired: Vec<(String, Pending)> = s.pending.iter().filter(|(_, p)| p.deadline_ms <= now)
            .map(|(id, p)| (id.clone(), p.clone())).collect();
        for (id, pending) in expired {
            s.pending.remove(&id);
            if pending.stream_delivered {
                // Old app / no PEX response: preserve its address and use legacy lookup as fallback.
                let a = s.attempts.entry((id, pending.endpoint)).or_default();
                a.retry_at_ms = now.saturating_add(HEALTHY_REFRESH_MS);
            } else { fail(&mut s, &id, pending.endpoint, now); }
        }
        if !self.wake.swap(false, Ordering::Relaxed) && now < s.next_round_ms { return Vec::new(); }
        refresh_self(&mut s, identity, own_addr, now);
        let mut candidates = candidates;
        for r in s.records.values() {
            candidates.push(Candidate { node_id: r.lease.claims.node_id.clone(), endpoint: r.lease.claims.endpoint,
                learned_at_ms: r.lease.claims.issued_at_ms.max(r.reported_checked_at_ms), live_connection: false });
        }
        candidates.retain(|c| valid_node_id(&c.node_id) && c.node_id != identity.legacy_routing_node_id()
            && usable_local_endpoint(c.endpoint));
        candidates.sort_by_key(|c| priority(&s, c, now));
        let mut seen = HashSet::new();
        let mut out = Vec::new();
        let mut all_targets: Vec<String> = targets.iter().filter(|id| valid_node_id(id)).cloned().collect();
        all_targets.sort(); all_targets.dedup();
        let target_list: Vec<String> = if all_targets.is_empty() { Vec::new() } else {
            let start = s.target_cursor % all_targets.len();
            (0..all_targets.len().min(MAX_TARGETS)).map(|step| all_targets[(start + step) % all_targets.len()].clone()).collect()
        };
        // Keep a small primary neighbourhood. Once two authenticated public peers are healthy,
        // an idle device must not keep dialing the whole Internet directory behind their cooldowns.
        let primary: HashSet<String> = candidates.iter().filter(|c| {
            is_public_endpoint(c.endpoint) && s.attempts.get(&(c.node_id.clone(), c.endpoint))
                .map(|a| a.failures == 0 && a.verified_at_ms > 0
                    && now.saturating_sub(a.verified_at_ms) <= ADDRESS_LIFETIME_MS).unwrap_or(false)
        }).map(|c| c.node_id.clone()).take(MAX_PARALLEL_EXCHANGES).collect();
        if target_list.is_empty() && primary.len() == MAX_PARALLEL_EXCHANGES {
            candidates.retain(|c| primary.contains(&c.node_id));
        }
        for c in candidates {
            if s.pending.len() >= MAX_PARALLEL_EXCHANGES || s.sent.len() >= MAX_REQUESTS_PER_MINUTE { break; }
            if s.pending.contains_key(&c.node_id) { continue; }
            if s.attempts.get(&(c.node_id.clone(), c.endpoint)).map(|a| {
                let urgent_refresh = !target_list.is_empty() && a.failures == 0 && a.verified_at_ms > 0
                    && now.saturating_sub(a.verified_at_ms) >= 60_000;
                now < a.retry_at_ms && !urgent_refresh
            }).unwrap_or(false) { continue; }
            if !seen.insert(c.node_id.clone()) { continue; }
            let remote_bloom = s.attempts.get(&(c.node_id.clone(), c.endpoint)).map(|a| a.remote_bloom.clone()).unwrap_or_default();
            let records = select_records(&s, &remote_bloom, &[], identity.legacy_routing_node_id(), MAX_SHARED_IN_REQUEST, now);
            let mut nonce = [0u8; 16]; OsRng.fill_bytes(&mut nonce);
            let claims = ExchangeClaims { version: 1, reply: false,
                from: identity.legacy_routing_node_id().to_string(), to: c.node_id.clone(), nonce,
                created_at_ms: now, channel_binding: [0; 32],
                signing_key: match identity.public_key().try_into() { Ok(k) => k, Err(_) => return Vec::new() },
                known: known_bloom(&s, now), targets: target_list.clone(), records, observed_requester: None };
            s.pending.insert(c.node_id.clone(), Pending { nonce, endpoint: c.endpoint,
                deadline_ms: now.saturating_add(REPLY_TIMEOUT_MS), stream_delivered: false, binding: None, had_targets: !target_list.is_empty() });
            s.sent.push_back(now);
            out.push(PreparedExchange { peer_id: c.node_id, endpoint: c.endpoint, claims });
        }
        if !out.is_empty() && !all_targets.is_empty() {
            s.target_cursor = (s.target_cursor + MAX_TARGETS) % all_targets.len();
        }
        s.next_round_ms = now.saturating_add(if target_list.is_empty() { HEALTHY_REFRESH_MS } else { 5_000 });
        out
    }

    /// Process only session-bound signed frames. The sender is online; all records about OTHER
    /// people remain address candidates, regardless of their reported checked_at timestamp.
    pub fn handle(
        &self, identity: &InstalledSigningIdentity, bytes: &[u8], binding: [u8; 32],
        remote: SocketAddr, own_addr: Option<SocketAddr>, now: i64,
    ) -> Option<ExchangeEffect> {
        if bytes.len() > MAX_EXCHANGE_BYTES { return None; }
        // A global ingress ceiling also bounds cryptographic work by previously unknown senders.
        {
            let mut s = self.state.lock().unwrap();
            while s.ingress.front().map(|t| now.saturating_sub(*t) >= 60_000).unwrap_or(false) { s.ingress.pop_front(); }
            if s.ingress.len() >= 120 { return None; }
            s.ingress.push_back(now);
        }
        let signed = decode(bytes, identity.legacy_routing_node_id(), binding, now)?;
        let c = signed.claims;
        let mut s = self.state.lock().unwrap();
        cleanup(&mut s, now);
        if s.pins.get(&c.from).map(|key| key != &c.signing_key).unwrap_or(false) { return None; }
        let mut urgent = !c.targets.is_empty();
        if c.reply {
            let pending = s.pending.get(&c.from)?;
            if pending.nonce != c.nonce || pending.deadline_ms < now || pending.endpoint != remote
                || pending.binding != Some(binding) { return None; }
            urgent = pending.had_targets;
            s.pending.remove(&c.from);
            if let Some(addr) = c.observed_requester.filter(|a| is_public_endpoint(*a)) { s.observed_own = Some((addr, now)); }
        } else {
            if s.received.iter().any(|(id, nonce, _)| id == &c.from && nonce == &c.nonce)
                || s.last_inbound.get(&c.from).map(|at| now.saturating_sub(*at) < 30_000).unwrap_or(false)
            { return None; }
            if s.received.len() >= MAX_DIRECTORY_ENTRIES { s.received.pop_front(); }
            s.received.push_back((c.from.clone(), c.nonce, now));
            s.last_inbound.insert(c.from.clone(), now);
        }
        // Only the directly signed session establishes a TOFU pin, never a forwarded lease.
        if !s.pins.contains_key(&c.from) && s.pins.len() >= MAX_PINNED_IDENTITIES { return None; }
        s.pins.insert(c.from.clone(), c.signing_key);
        let a = s.attempts.entry((c.from.clone(), remote)).or_default();
        a.verified_at_ms = now; a.failures = 0; a.failed_at_ms = 0;
        a.retry_at_ms = now.saturating_add(if urgent { 60_000 } else { HEALTHY_REFRESH_MS });
        a.remote_bloom = c.known.clone();
        if let Some(record) = s.records.get_mut(&(c.from.clone(), remote)) {
            record.locally_checked_at_ms = now;
        }
        s.dirty = true;
        let mut learned = Vec::new();
        for record in c.records {
            if !record.lease.verify(now) { continue; }
            let claims = &record.lease.claims;
            if claims.node_id == identity.legacy_routing_node_id()
                || s.pins.get(&claims.node_id).map(|k| k != &claims.signing_key).unwrap_or(false)
                || (claims.node_id == c.from && claims.signing_key != c.signing_key)
            { continue; }
            let key = (claims.node_id.clone(), claims.endpoint);
            if let Some(old) = s.records.get_mut(&key) {
                if old.lease.claims.issued_at_ms >= claims.issued_at_ms {
                    // Preserve a newer lease while allowing an explicit target answer to report
                    // a later observation. Neither value changes the owner's signed expiry.
                    if old.lease == record.lease && record.checked_at_ms <= now.saturating_add(CLOCK_SKEW_MS) {
                        old.reported_checked_at_ms = old.reported_checked_at_ms.max(record.checked_at_ms.min(now));
                    }
                    continue;
                }
            }
            let previous_check = s.records.get(&key).map(|r| r.locally_checked_at_ms).unwrap_or(0);
            let checked = if claims.node_id == c.from && claims.endpoint == remote { now } else { previous_check };
            learned.push((claims.node_id.clone(), claims.endpoint, claims.issued_at_ms));
            let reported = if record.checked_at_ms > 0 && record.checked_at_ms <= now.saturating_add(CLOCK_SKEW_MS) {
                record.checked_at_ms.min(now)
            } else { 0 };
            s.records.insert(key, CachedRecord { lease: record.lease, locally_checked_at_ms: checked,
                reported_checked_at_ms: reported });
        }
        trim_records(&mut s);
        refresh_self(&mut s, identity, own_addr, now);
        let response = if c.reply { None } else {
            let records = select_records(&s, &c.known, &c.targets, identity.legacy_routing_node_id(), MAX_RECORDS, now);
            encode(identity, ExchangeClaims { version: 1, reply: true,
                from: identity.legacy_routing_node_id().to_string(), to: c.from.clone(), nonce: c.nonce,
                created_at_ms: now, channel_binding: binding,
                signing_key: identity.public_key().try_into().ok()?, known: known_bloom(&s, now),
                targets: Vec::new(), records, observed_requester: Some(remote) })
        };
        self.wake();
        Some(ExchangeEffect { sender_id: c.from, response, candidates: learned, sender_endpoint: remote })
    }

    /// Flush outside the socket receive callback; no signing seed/private key is persisted here.
    pub fn flush(&self, force: bool, now: i64) {
        let Some(path) = &self.path else { return; };
        let _write_guard = self.write_lock.lock().unwrap();
        let snapshot = {
            let mut s = self.state.lock().unwrap();
            if !s.dirty || (!force && now.saturating_sub(s.saved_at_ms) < SAVE_INTERVAL_MS) { return; }
            cleanup(&mut s, now);
            let snapshot = StoredDirectory { version: 1, records: s.records.values().cloned().collect(),
                pins: s.pins.iter().map(|(id, key)| (id.clone(), *key)).collect(),
                attempts: s.attempts.iter().map(|((id, addr), a)| (id.clone(), *addr, a.clone())).collect() };
            s.dirty = false; s.saved_at_ms = now;
            snapshot
        };
        let result = (|| -> Result<(), String> {
            let bytes = serde_json::to_vec(&snapshot).map_err(|e| e.to_string())?;
            if bytes.len() as u64 > MAX_STORE_BYTES { return Err("directory exceeds its byte limit".into()); }
            if let Some(parent) = path.parent() { fs::create_dir_all(parent).map_err(|e| e.to_string())?; }
            let tmp = path.with_extension("tmp");
            fs::write(&tmp, bytes).map_err(|e| e.to_string())?;
            fs::rename(tmp, path).map_err(|e| e.to_string())?;
            Ok(())
        })();
        if let Err(error) = result {
            self.state.lock().unwrap().dirty = true;
            tracing::warn!("PEER EXCHANGE: cannot persist directory: {}", error);
        }
    }
}

fn priority(s: &State, c: &Candidate, now: i64) -> (u8, i64, String, SocketAddr) {
    let verified = s.attempts.get(&(c.node_id.clone(), c.endpoint))
        .filter(|a| a.failures == 0 || a.verified_at_ms > a.failed_at_ms)
        .map(|a| a.verified_at_ms).unwrap_or(0);
    let recent = verified > 0 && now.saturating_sub(verified) <= ONLINE_WINDOW_MS;
    let public = is_public_endpoint(c.endpoint);
    let class = if public && recent { 0 }
        else if public && verified > 0 && now.saturating_sub(verified) <= ADDRESS_LIFETIME_MS { 1 }
        else if c.live_connection { 2 }
        else if public && now.saturating_sub(c.learned_at_ms) <= ADDRESS_LIFETIME_MS { 3 }
        else if public { 4 } else { 5 };
    (class, -verified.max(c.learned_at_ms).max(0), c.node_id.clone(), c.endpoint)
}

fn fail(s: &mut State, id: &str, endpoint: SocketAddr, now: i64) {
    let a = s.attempts.entry((id.to_string(), endpoint)).or_default();
    // File chunk retries must not count as hundreds of independent failed dial attempts.
    if a.failed_at_ms > 0 && now.saturating_sub(a.failed_at_ms) < 30_000 { return; }
    a.failures = a.failures.saturating_add(1);
    a.failed_at_ms = now;
    let delay = 30_000_i64.saturating_mul(1_i64 << a.failures.min(6)).min(MAX_BACKOFF_MS);
    a.retry_at_ms = now.saturating_add(delay);
    s.dirty = true;
}

fn cleanup(s: &mut State, now: i64) {
    s.records.retain(|_, r| r.lease.claims.expires_at_ms > now);
    while s.sent.front().map(|at| now.saturating_sub(*at) >= 60_000).unwrap_or(false) { s.sent.pop_front(); }
    s.received.retain(|(_, _, at)| now.saturating_sub(*at) <= FRAME_LIFETIME_MS + CLOCK_SKEW_MS);
    s.last_inbound.retain(|_, at| now.saturating_sub(*at) <= FRAME_LIFETIME_MS + CLOCK_SKEW_MS);
    if s.attempts.len() > MAX_DIRECTORY_ENTRIES {
        let mut keys: Vec<_> = s.attempts.iter().map(|(k, a)| (k.clone(), a.verified_at_ms.max(a.failed_at_ms))).collect();
        keys.sort_by_key(|(_, at)| *at);
        let remove = keys.len() - MAX_DIRECTORY_ENTRIES;
        for (key, _) in keys.into_iter().take(remove) { s.attempts.remove(&key); }
    }
}

fn trim_records(s: &mut State) {
    let mut records: Vec<_> = s.records.iter().map(|(key, r)| (key.clone(), r.lease.claims.issued_at_ms)).collect();
    records.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    let mut per_node: HashMap<String, usize> = HashMap::new();
    let mut kept = 0;
    for (key, _) in records {
        let count = per_node.entry(key.0.clone()).or_default();
        if *count >= MAX_PER_NODE_ADDRESSES || kept >= MAX_DIRECTORY_ENTRIES { s.records.remove(&key); }
        else { *count += 1; kept += 1; }
    }
}

fn refresh_self(s: &mut State, identity: &InstalledSigningIdentity, own: Option<SocketAddr>, now: i64) {
    let endpoint = own.filter(|addr| is_public_endpoint(*addr)).or_else(|| {
        s.observed_own.filter(|(_, at)| now.saturating_sub(*at) <= ONLINE_WINDOW_MS).map(|(addr, _)| addr)
    });
    let Some(endpoint) = endpoint else { s.self_lease = None; return; };
    if s.self_lease.as_ref().map(|l| l.claims.endpoint == endpoint
        && l.claims.signing_key.as_slice() == identity.public_key()
        && now.saturating_sub(l.claims.issued_at_ms) < SELF_RENEW_MS).unwrap_or(false) { return; }
    s.self_lease = AddressLease::create(identity, endpoint, now);
}

fn known_bloom(s: &State, now: i64) -> Vec<u8> {
    let mut bloom = vec![0; BLOOM_BYTES];
    for r in s.records.values().filter(|r| r.lease.claims.expires_at_ms > now) { bloom_add(&mut bloom, r.lease.fingerprint()); }
    if let Some(own) = &s.self_lease { bloom_add(&mut bloom, own.fingerprint()); }
    bloom
}

fn select_records(s: &State, bloom: &[u8], targets: &[String], our_id: &str, limit: usize, now: i64) -> Vec<SharedRecord> {
    let mut records: Vec<CachedRecord> = s.records.values().filter(|r| r.lease.claims.expires_at_ms > now).cloned().collect();
    if let Some(own) = &s.self_lease { records.push(CachedRecord { lease: own.clone(), locally_checked_at_ms: now, reported_checked_at_ms: now }); }
    // Explicit targets bypass Bloom filters: false positives must not hide the wanted contact.
    records.retain(|r| targets.contains(&r.lease.claims.node_id) || !bloom_has(bloom, r.lease.fingerprint()));
    records.sort_by(|a, b| {
        let rank = |r: &CachedRecord| (
            !targets.contains(&r.lease.claims.node_id), r.lease.claims.node_id != our_id,
            r.locally_checked_at_ms == 0, -r.locally_checked_at_ms, -r.lease.claims.issued_at_ms,
            r.lease.claims.node_id.clone(), r.lease.claims.endpoint,
        );
        rank(a).cmp(&rank(b))
    });
    records.into_iter().take(limit).map(|r| SharedRecord { lease: r.lease, checked_at_ms: r.locally_checked_at_ms }).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_000_000;
    const BINDING: [u8; 32] = [7; 32];

    fn identity(n: u8) -> InstalledSigningIdentity {
        InstalledSigningIdentity::from_seed(1, format!("pk_{}", format!("{n:02x}").repeat(32)), &[n; 32]).unwrap()
    }
    fn addr(n: u8) -> SocketAddr { format!("8.8.4.{n}:7777").parse().unwrap() }
    fn candidate(n: u8, now: i64) -> Candidate {
        Candidate { node_id: identity(n).legacy_routing_node_id().into(), endpoint: addr(n),
            learned_at_ms: now, live_connection: false }
    }
    fn request(a: &InstalledSigningIdentity, da: &PeerExchange, b: &InstalledSigningIdentity,
        targets: &[String], now: i64) -> PreparedExchange {
        da.plan(a, Some(addr(1)), vec![Candidate { node_id: b.legacy_routing_node_id().into(),
            endpoint: addr(2), learned_at_ms: now, live_connection: false }], targets, now).remove(0)
    }
    fn round_trip(a: &InstalledSigningIdentity, da: &PeerExchange, b: &InstalledSigningIdentity,
        db: &PeerExchange, now: i64) -> ExchangeEffect {
        let q = request(a, da, b, &[], now);
        assert!(da.bind_request(&q, BINDING, addr(2)));
        let at_b = db.handle(b, &q.encode(a, BINDING).unwrap(), BINDING, addr(1), Some(addr(2)), now).unwrap();
        da.handle(a, &at_b.response.unwrap(), BINDING, addr(2), Some(addr(1)), now).unwrap()
    }

    #[test]
    fn public_address_filter_excludes_lan_cgnat_tests_and_ipv6_local() {
        for bad in ["10.1.2.3:7777", "100.64.0.2:7777", "100.127.255.255:9", "172.31.2.3:1",
            "192.168.1.1:7", "127.0.0.1:7777", "169.254.1.2:7", "0.0.0.0:8", "224.0.0.1:8",
            "192.0.2.1:7777", "198.51.100.1:7777", "203.0.113.1:7777", "8.8.8.8:0",
            "[::1]:7777", "[fe80::1]:7777", "[fc00::1]:7777", "[ff02::1]:7777",
            "[::ffff:10.0.0.1]:7777", "[2001:db8::1]:7777"] {
            assert!(!is_public_endpoint(bad.parse().unwrap()), "{bad}");
        }
        for good in ["1.1.1.1:7777", "8.8.8.8:34567", "[2606:4700:4700::1111]:7777"] {
            assert!(is_public_endpoint(good.parse().unwrap()), "{good}");
        }
        assert!(!valid_node_id("pk_ab_public"));
        assert!(!valid_node_id("pk_"));
    }

    #[test]
    fn owner_signature_expiry_and_future_clock_are_checked() {
        let a = identity(1);
        let lease = AddressLease::create(&a, addr(1), NOW).unwrap();
        assert!(lease.verify(NOW));
        assert!(!lease.verify(NOW + ADDRESS_LIFETIME_MS));
        assert!(!lease.verify(NOW - CLOCK_SKEW_MS - 1));
        let mut forged = lease.clone(); forged.claims.endpoint = addr(99);
        assert!(!forged.verify(NOW));
        assert!(AddressLease::create(&a, "10.0.0.2:7777".parse().unwrap(), NOW).is_none());
    }

    #[test]
    fn exchange_is_bidirectional_batched_and_only_sender_becomes_verified() {
        let a = identity(1); let b = identity(2); let c = identity(3);
        let da = PeerExchange::open(None); let db = PeerExchange::open(None);
        let lease = AddressLease::create(&c, addr(3), NOW).unwrap();
        db.state.lock().unwrap().records.insert((c.legacy_routing_node_id().into(), addr(3)),
            CachedRecord { lease, locally_checked_at_ms: NOW, reported_checked_at_ms: NOW });
        let q = request(&a, &da, &b, &[c.legacy_routing_node_id().into()], NOW);
        da.bind_request(&q, BINDING, addr(2));
        let at_b = db.handle(&b, &q.encode(&a, BINDING).unwrap(), BINDING, addr(1), Some(addr(2)), NOW).unwrap();
        assert!(db.candidates(NOW).iter().any(|(id, _, _)| id == a.legacy_routing_node_id()), "request shared our address");
        let at_a = da.handle(&a, &at_b.response.unwrap(), BINDING, addr(2), Some(addr(1)), NOW).unwrap();
        assert!(at_a.candidates.iter().any(|(id, _, _)| id == c.legacy_routing_node_id()));
        let state = da.state.lock().unwrap();
        assert!(state.attempts.get(&(b.legacy_routing_node_id().into(), addr(2))).unwrap().verified_at_ms > 0);
        assert!(!state.attempts.contains_key(&(c.legacy_routing_node_id().into(), addr(3))));
        assert_eq!(state.records.get(&(c.legacy_routing_node_id().into(), addr(3))).unwrap().locally_checked_at_ms, 0);
        assert!(!state.pins.contains_key(c.legacy_routing_node_id()), "gossip must not pin another user's identity");
    }

    #[test]
    fn nonce_recipient_signature_and_real_session_binding_reject_replays() {
        let a = identity(1); let b = identity(2);
        let da = PeerExchange::open(None); let db = PeerExchange::open(None);
        let q = request(&a, &da, &b, &[], NOW);
        da.bind_request(&q, BINDING, addr(2));
        let bytes = q.encode(&a, BINDING).unwrap();
        assert!(db.handle(&b, &bytes, [8; 32], addr(1), Some(addr(2)), NOW).is_none());
        let result = db.handle(&b, &bytes, BINDING, addr(1), Some(addr(2)), NOW).unwrap();
        assert!(db.handle(&b, &bytes, BINDING, addr(1), Some(addr(2)), NOW).is_none());
        let reply = result.response.unwrap();
        assert!(da.handle(&a, &reply, BINDING, addr(99), Some(addr(1)), NOW).is_none());
        assert!(da.handle(&a, &reply, BINDING, addr(2), Some(addr(1)), NOW).is_some());
        assert!(da.handle(&a, &reply, BINDING, addr(2), Some(addr(1)), NOW).is_none());
        let wrong = identity(4);
        assert!(decode(&bytes, wrong.legacy_routing_node_id(), BINDING, NOW).is_none());
        let mut corrupted: SignedExchange = serde_json::from_slice(&bytes[EXCHANGE_MAGIC.len()..]).unwrap();
        corrupted.claims.targets.push(wrong.legacy_routing_node_id().into());
        let mut raw = EXCHANGE_MAGIC.to_vec(); raw.extend(serde_json::to_vec(&corrupted).unwrap());
        assert!(decode(&raw, b.legacy_routing_node_id(), BINDING, NOW).is_none());
    }

    #[test]
    fn a_different_key_cannot_replace_a_directly_pinned_routing_id() {
        let a = identity(1); let b = identity(2);
        let da = PeerExchange::open(None); let db = PeerExchange::open(None);
        round_trip(&a, &da, &b, &db, NOW);
        let impostor = InstalledSigningIdentity::from_seed(1, a.legacy_routing_node_id().into(), &[99; 32]).unwrap();
        let attacker = PeerExchange::open(None);
        let q = request(&impostor, &attacker, &b, &[], NOW + 60_000);
        assert!(db.handle(&b, &q.encode(&impostor, BINDING).unwrap(), BINDING, addr(1), Some(addr(2)), NOW + 60_000).is_none());
    }

    #[test]
    fn verified_public_nodes_rank_before_raw_public_and_local_hints() {
        let directory = PeerExchange::open(None);
        let public = candidate(2, NOW);
        let mut local = candidate(3, NOW); local.endpoint = "192.168.1.2:7777".parse().unwrap();
        let raw = candidate(4, NOW);
        directory.state.lock().unwrap().attempts.insert((public.node_id.clone(), public.endpoint),
            Attempt { verified_at_ms: NOW, ..Attempt::default() });
        let mut all = vec![local, raw, public.clone()];
        all.sort_by_key(|c| directory.priority(c, NOW));
        assert_eq!(all[0].node_id, public.node_id);
        assert!(is_public_endpoint(all[1].endpoint));
        directory.note_route_failure(&public.node_id, Some(public.endpoint), NOW + 1);
        assert!(directory.needs_lookup(&public.node_id, Some(public.endpoint), NOW, NOW + 1));
    }

    #[test]
    fn two_parallel_exchanges_six_per_minute_and_manual_trigger_does_not_reset_backoff() {
        let a = identity(1); let directory = PeerExchange::open(None);
        let all: Vec<_> = (2..22).map(|n| candidate(n, NOW)).collect();
        let mut total = 0;
        for step in 0..10 {
            directory.wake();
            let now = NOW + step * 5_000;
            let planned = directory.plan(&a, Some(addr(1)), all.clone(), &[identity(22).legacy_routing_node_id().into()], now);
            assert!(planned.len() <= MAX_PARALLEL_EXCHANGES);
            total += planned.len();
            for q in planned { directory.mark_send_result(&q.peer_id, q.endpoint, false, now); }
        }
        assert_eq!(total, MAX_REQUESTS_PER_MINUTE);
        assert!(directory.state.lock().unwrap().attempts.values().all(|a| a.retry_at_ms > NOW));
    }

    #[test]
    fn same_node_alternate_address_is_tried_when_first_address_is_in_backoff() {
        let a = identity(1); let d = PeerExchange::open(None);
        let first = candidate(2, NOW);
        let mut alternative = first.clone(); alternative.endpoint = "1.1.1.2:8888".parse().unwrap();
        d.note_route_failure(&first.node_id, Some(first.endpoint), NOW);
        let out = d.plan(&a, Some(addr(1)), vec![first, alternative.clone()], &[], NOW + 1);
        assert_eq!(out.len(), 1); assert_eq!(out[0].endpoint, alternative.endpoint);
    }

    #[test]
    fn explicit_wanted_contacts_bypass_bloom_false_positives() {
        let a = identity(1); let b = identity(2); let c = identity(3);
        let da = PeerExchange::open(None); let db = PeerExchange::open(None);
        let lease = AddressLease::create(&c, addr(3), NOW).unwrap();
        db.state.lock().unwrap().records.insert((c.legacy_routing_node_id().into(), addr(3)),
            CachedRecord { lease, locally_checked_at_ms: NOW, reported_checked_at_ms: NOW });
        let mut q = request(&a, &da, &b, &[c.legacy_routing_node_id().into()], NOW);
        q.claims.known = vec![255; BLOOM_BYTES];
        let result = db.handle(&b, &q.encode(&a, BINDING).unwrap(), BINDING, addr(1), Some(addr(2)), NOW).unwrap();
        let reply = decode(&result.response.unwrap(), a.legacy_routing_node_id(), BINDING, NOW).unwrap();
        assert!(reply.claims.records.iter().any(|r| r.lease.claims.node_id == c.legacy_routing_node_id()));
    }

    #[test]
    fn unchanged_records_are_not_resent_but_expiry_is_never_refreshed_by_gossip() {
        let c = identity(3);
        let lease = AddressLease::create(&c, addr(3), NOW).unwrap();
        let mut s = State::default();
        s.records.insert((c.legacy_routing_node_id().into(), addr(3)),
            CachedRecord { lease: lease.clone(), locally_checked_at_ms: NOW, reported_checked_at_ms: NOW });
        let bloom = known_bloom(&s, NOW);
        assert!(select_records(&s, &bloom, &[], identity(1).legacy_routing_node_id(), MAX_RECORDS, NOW).is_empty());
        assert!(select_records(&s, &[], &[], identity(1).legacy_routing_node_id(), MAX_RECORDS, NOW + ADDRESS_LIFETIME_MS).is_empty());
        assert_eq!(lease.claims.expires_at_ms, NOW + ADDRESS_LIFETIME_MS);
    }

    #[test]
    fn original_expiry_and_key_pins_survive_restart_and_corrupt_store_is_harmless() {
        let now = crate::storage::models::now_ms();
        let path = std::env::temp_dir().join(format!("apu-peer-directory-{}.json", uuid::Uuid::new_v4()));
        let a = identity(1); let b = identity(2);
        let da = PeerExchange::open(Some(path.clone())); let db = PeerExchange::open(None);
        round_trip(&a, &da, &b, &db, now);
        let expiry = da.state.lock().unwrap().records.values().next().unwrap().lease.claims.expires_at_ms;
        da.flush(true, now);
        let reopened = PeerExchange::open(Some(path.clone()));
        let state = reopened.state.lock().unwrap();
        assert_eq!(state.pins.get(b.legacy_routing_node_id()).copied(), Some(b.public_key().try_into().unwrap()));
        assert_eq!(state.records.values().next().unwrap().lease.claims.expires_at_ms, expiry);
        drop(state);
        fs::write(&path, b"truncated").unwrap();
        assert!(PeerExchange::open(Some(path.clone())).candidates(now).is_empty());
        let _ = fs::remove_file(path);
    }

    #[test]
    fn peer_reflector_supplies_own_endpoint_without_stun() {
        let a = identity(1); let b = identity(2);
        let da = PeerExchange::open(None); let db = PeerExchange::open(None);
        round_trip(&a, &da, &b, &db, NOW);
        assert_eq!(da.observed_own_addr(NOW), Some(addr(1)));
        assert_eq!(da.observed_own_addr(NOW + ONLINE_WINDOW_MS + 1), None);
    }

    #[test]
    fn a_stream_ack_from_a_legacy_phone_is_not_online_proof_or_a_dead_address() {
        let a = identity(1); let b = identity(2); let d = PeerExchange::open(None);
        let q = request(&a, &d, &b, &[], NOW);
        d.mark_send_result(&q.peer_id, q.endpoint, true, NOW);
        d.wake();
        let _ = d.plan(&a, Some(addr(1)), vec![candidate(2, NOW)], &[], NOW + REPLY_TIMEOUT_MS + 1);
        assert!(!d.needs_lookup(&q.peer_id, Some(q.endpoint), NOW, NOW + REPLY_TIMEOUT_MS + 1));
        assert_eq!(d.state.lock().unwrap().attempts.get(&(q.peer_id, q.endpoint)).unwrap().verified_at_ms, 0);
    }

    #[test]
    fn wanted_batches_rotate_and_payloads_are_bounded() {
        let a = identity(1); let d = PeerExchange::open(None);
        let targets: Vec<String> = (30..100).map(|n| identity(n).legacy_routing_node_id().into()).collect();
        let first = d.plan(&a, Some(addr(1)), vec![candidate(2, NOW)], &targets, NOW).remove(0);
        assert_eq!(first.claims.targets.len(), MAX_TARGETS);
        let first_targets = first.claims.targets.clone();
        assert!(first.encode(&a, BINDING).unwrap().len() <= MAX_EXCHANGE_BYTES);
        d.mark_send_result(&first.peer_id, first.endpoint, false, NOW);
        d.wake();
        let second = d.plan(&a, Some(addr(1)), vec![candidate(3, NOW)], &targets, NOW + 5_000).remove(0);
        assert!(second.claims.targets.iter().any(|id| !first_targets.contains(id)));
        assert!(decode(&vec![0; MAX_EXCHANGE_BYTES + 1], a.legacy_routing_node_id(), BINDING, NOW).is_none());
    }
}
