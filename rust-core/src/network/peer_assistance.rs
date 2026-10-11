//! Automatic, bounded online connection help; NOT disk custody and NOT an open proxy.
//! Only enrolled APU peers may ask, only another enrolled/live peer may be the destination.
//! Introductions expose only observed public endpoints. Transit has one helper hop, an
//! end-to-end X25519/XChaCha20-Poly1305 box, owner signatures and recipient-signed receipts.

use std::collections::{HashMap, VecDeque};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Instant;
use super::helper_quality::HelperQuality;
use super::helper_policy::HelperPolicy;
use tokio::sync::oneshot;

use base64::{engine::general_purpose::STANDARD, Engine};
use chacha20poly1305::{aead::{Aead, KeyInit, Payload}, XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use rand::{rngs::OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::crypto::keys::{Ed25519KeyPair, X25519KeyPair};
use crate::crypto::signing_identity::InstalledSigningIdentity;
use super::peer_exchange::{is_public_endpoint, valid_node_id, PeerExchange};

pub const ASSIST_MAGIC: &[u8] = b"APUA1\n";
pub const MAX_ASSIST_BYTES: usize = 384 * 1024;
pub const MAX_INNER_BYTES: usize = 256 * 1024;
const LEASE_MS: i64 = 24 * 60 * 60_000;
const PACKET_MS: i64 = 60_000;
const SKEW_MS: i64 = 120_000;
const MAX_PENDING: usize = 16;
const MAX_ROUTES: usize = 512;
const MAX_BYTES_PER_MINUTE: usize = 8 * 1024 * 1024;
const MAX_PEER_BYTES_PER_MINUTE: usize = 2 * 1024 * 1024;
const IDENTITY_DOMAIN: &[u8] = b"apu-helper-identity-v1\0";
const FRAME_DOMAIN: &[u8] = b"apu-helper-frame-v1\0";
const PACKET_DOMAIN: &[u8] = b"apu-helper-packet-v1\0";
const ACK_DOMAIN: &[u8] = b"apu-helper-receipt-v1\0";

fn canonical<T: Serialize>(domain: &[u8], item: &T) -> Option<Vec<u8>> {
    let mut bytes = domain.to_vec(); bytes.extend(serde_json::to_vec(item).ok()?); Some(bytes)
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct HelperIdentityClaims {
    pub node_id: String,
    pub signing_key: [u8; 32],
    pub exchange_key: [u8; 32],
    pub issued_at_ms: i64,
    pub expires_at_ms: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct HelperIdentity {
    pub claims: HelperIdentityClaims,
    pub signature: Vec<u8>,
}

impl HelperIdentity {
    pub fn create(identity: &InstalledSigningIdentity, now: i64) -> Option<Self> {
        let claims = HelperIdentityClaims { node_id: identity.legacy_routing_node_id().into(),
            signing_key: identity.public_key().try_into().ok()?,
            exchange_key: identity.helper_exchange_key_pair()?.public_key().0.try_into().ok()?,
            issued_at_ms: now, expires_at_ms: now.checked_add(LEASE_MS)? };
        let signature = identity.sign_security_payload(&canonical(IDENTITY_DOMAIN, &claims)?);
        Some(Self { claims, signature })
    }
    pub fn verify(&self, now: i64) -> bool {
        let c = &self.claims;
        valid_node_id(&c.node_id) && c.issued_at_ms > 0 && c.issued_at_ms <= now.saturating_add(SKEW_MS)
            && c.expires_at_ms > now && c.expires_at_ms > c.issued_at_ms
            && c.expires_at_ms.saturating_sub(c.issued_at_ms) <= LEASE_MS
            && c.exchange_key != [0; 32] && self.signature.len() == 64
            && canonical(IDENTITY_DOMAIN, c).map(|b| Ed25519KeyPair::verify(&c.signing_key, &b, &self.signature).is_ok()).unwrap_or(false)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct PacketHeader {
    sender: HelperIdentity,
    recipient: String,
    recipient_signing_key: [u8; 32],
    recipient_exchange_key: [u8; 32],
    packet_id: [u8; 16],
    ephemeral_key: [u8; 32],
    nonce: [u8; 24],
    created_at_ms: i64,
    expires_at_ms: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SealedPacket {
    header: PacketHeader,
    ciphertext: String,
    signature: Vec<u8>,
}

impl SealedPacket {
    fn verify(&self, now: i64) -> bool {
        let h = &self.header;
        h.sender.verify(now) && valid_node_id(&h.recipient) && h.recipient != h.sender.claims.node_id
            && h.created_at_ms > 0 && h.created_at_ms <= now.saturating_add(SKEW_MS)
            && h.expires_at_ms > now && h.expires_at_ms.saturating_sub(h.created_at_ms) <= PACKET_MS
            && h.expires_at_ms > h.created_at_ms && h.packet_id != [0; 16]
            && h.ephemeral_key != [0; 32] && self.ciphertext.len() <= (MAX_INNER_BYTES + 16) * 4 / 3 + 8
            && self.signature.len() == 64
            && self.signed_bytes().map(|b| Ed25519KeyPair::verify(&h.sender.claims.signing_key, &b, &self.signature).is_ok()).unwrap_or(false)
    }
    fn signed_bytes(&self) -> Option<Vec<u8>> {
        canonical(PACKET_DOMAIN, &(&self.header, &self.ciphertext))
    }
    fn digest(&self) -> Option<[u8; 32]> { Some(Sha256::digest(self.signed_bytes()?).into()) }
}

fn derive(shared: &[u8], header: &PacketHeader) -> Option<[u8; 32]> {
    let aad = canonical(PACKET_DOMAIN, header)?;
    let salt: [u8; 32] = Sha256::digest(aad).into();
    let mut key = [0; 32];
    Hkdf::<Sha256>::new(Some(&salt), shared).expand(b"apu-helper-box-v1", &mut key).ok()?;
    Some(key)
}

fn seal(identity: &InstalledSigningIdentity, recipient: &HelperIdentity, bytes: &[u8], now: i64) -> Option<SealedPacket> {
    if bytes.is_empty() || bytes.len() > MAX_INNER_BYTES || !recipient.verify(now) { return None; }
    let ephemeral = X25519KeyPair::generate();
    let mut nonce = [0; 24]; let mut packet_id = [0; 16];
    OsRng.fill_bytes(&mut nonce); OsRng.fill_bytes(&mut packet_id);
    let header = PacketHeader { sender: HelperIdentity::create(identity, now)?, recipient: recipient.claims.node_id.clone(),
        recipient_signing_key: recipient.claims.signing_key, recipient_exchange_key: recipient.claims.exchange_key,
        packet_id, ephemeral_key: ephemeral.public_key().0.try_into().ok()?, nonce,
        created_at_ms: now, expires_at_ms: now.checked_add(PACKET_MS)? };
    let mut shared = ephemeral.diffie_hellman(&header.recipient_exchange_key).ok()?;
    if shared.iter().all(|b| *b == 0) { return None; }
    let mut key = derive(&shared, &header)?; shared.fill(0);
    let aad = canonical(PACKET_DOMAIN, &header)?;
    let encrypted = XChaCha20Poly1305::new_from_slice(&key).ok()?.encrypt(XNonce::from_slice(&nonce), Payload { msg: bytes, aad: &aad }).ok();
    key.fill(0);
    let mut packet = SealedPacket { header, ciphertext: STANDARD.encode(encrypted?), signature: Vec::new() };
    packet.signature = identity.sign_security_payload(&packet.signed_bytes()?);
    Some(packet)
}

fn open(identity: &InstalledSigningIdentity, packet: &SealedPacket, now: i64) -> Option<Vec<u8>> {
    if !packet.verify(now) || packet.header.recipient != identity.legacy_routing_node_id()
        || packet.header.recipient_signing_key.as_slice() != identity.public_key() { return None; }
    let pair = identity.helper_exchange_key_pair()?;
    if pair.public_key().0.as_slice() != packet.header.recipient_exchange_key.as_slice() { return None; }
    let mut shared = pair.diffie_hellman(&packet.header.ephemeral_key).ok()?;
    if shared.iter().all(|b| *b == 0) { return None; }
    let mut key = derive(&shared, &packet.header)?; shared.fill(0);
    let cipher = STANDARD.decode(&packet.ciphertext).ok()?;
    let aad = canonical(PACKET_DOMAIN, &packet.header)?;
    let result = XChaCha20Poly1305::new_from_slice(&key).ok()?.decrypt(XNonce::from_slice(&packet.header.nonce), Payload { msg: &cipher, aad: &aad }).ok();
    key.fill(0); result
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct ReceiptClaims {
    packet_id: [u8; 16], origin: String, recipient: String, digest: [u8; 32], expires_at_ms: i64,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Receipt { claims: ReceiptClaims, signature: Vec<u8> }

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub enum AssistOperation {
    Connect { target: String },
    Introduction { peer: HelperIdentity, endpoint: SocketAddr },
    Relay { packet: SealedPacket },
    Deliver { packet: SealedPacket },
    Receipt { receipt: Receipt },
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct AssistClaims {
    version: u8, from: String, to: String, id: [u8; 16], created_at_ms: i64,
    binding: [u8; 32], signing_key: [u8; 32], operation: AssistOperation,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct SignedAssist { claims: AssistClaims, signature: Vec<u8> }

pub fn encode(identity: &InstalledSigningIdentity, peer: &str, operation: AssistOperation, binding: [u8; 32], now: i64) -> Option<Vec<u8>> {
    let mut id = [0; 16]; OsRng.fill_bytes(&mut id);
    let claims = AssistClaims { version: 1, from: identity.legacy_routing_node_id().into(), to: peer.into(), id,
        created_at_ms: now, binding, signing_key: identity.public_key().try_into().ok()?, operation };
    let signature = identity.sign_security_payload(&canonical(FRAME_DOMAIN, &claims)?);
    let mut bytes = ASSIST_MAGIC.to_vec(); bytes.extend(serde_json::to_vec(&SignedAssist { claims, signature }).ok()?);
    (bytes.len() <= MAX_ASSIST_BYTES).then_some(bytes)
}

struct Leg { started: Instant, written: bool }
struct Pending { recipient: HelperIdentity, digest: [u8; 32], expires: i64, tx: oneshot::Sender<bool>, legs: HashMap<String, Leg> }
struct Received { digest: [u8; 32], expires: i64, receipt: Receipt }
struct Route { origin: String, recipient: HelperIdentity, digest: [u8; 32], expires: i64 }
#[derive(Default)]
struct State {
    pending: HashMap<[u8; 16], Pending>,
    quality: HelperQuality,
    policy: HelperPolicy,
    transit_traffic: VecDeque<(usize, i64)>,
    routes: HashMap<(String, [u8; 16]), Route>,
    received: HashMap<(String, [u8; 16]), Received>,
    frames: VecDeque<([u8; 16], i64)>,
    traffic: VecDeque<(String, usize, i64)>,
    controls: VecDeque<i64>,
    requested: HashMap<String, i64>,
    introductions: HashMap<(String, String), i64>,
}

#[derive(Default)]
pub struct AssistanceEffect {
    pub sender: Option<String>,
    pub outbound: Vec<(String, AssistOperation)>,
    pub probe: Option<(HelperIdentity, SocketAddr)>,
    pub delivery: Option<Vec<u8>>,
}

/// Releases origin admission even if the race is timed out, panics, or is aborted.
pub struct PendingRelayGuard { assistance: Arc<PeerAssistance>, id: [u8; 16] }
impl Drop for PendingRelayGuard {
    fn drop(&mut self) { self.assistance.cancel(self.id); }
}

#[derive(Default)]
pub struct PeerAssistance { state: Mutex<State> }
impl PeerAssistance {
    /// Online transit defaults on, but has its own owner control, separate from disk custody.
    pub fn new() -> Self { Self::default() }

    pub fn set_policy(&self, enabled: bool, routes: u32, bytes: u32, relays: u32) {
        // Do not reset counters or drop accepted routes on toggles: neither grants fresh quota.
        self.state.lock().unwrap().policy = HelperPolicy::bounded(enabled, routes, bytes, relays);
    }

    pub fn connect_request(&self, target: &str, now: i64) -> Option<AssistOperation> {
        if !valid_node_id(target) { return None; }
        let mut s = self.state.lock().unwrap(); cleanup(&mut s, now);
        if s.requested.get(target).map(|t| now.saturating_sub(*t) < 30_000).unwrap_or(false) || s.requested.len() >= MAX_ROUTES { return None; }
        s.requested.insert(target.into(), now);
        Some(AssistOperation::Connect { target: target.into() })
    }

    pub fn prepare_relay(&self, identity: &InstalledSigningIdentity, recipient: &HelperIdentity, bytes: &[u8], now: i64)
        -> Option<(AssistOperation, oneshot::Receiver<bool>, [u8; 16])> {
        let packet = seal(identity, recipient, bytes, now)?;
        let (tx, rx) = oneshot::channel();
        let mut s = self.state.lock().unwrap(); cleanup(&mut s, now);
        if s.pending.len() >= MAX_PENDING { return None; }
        let id = packet.header.packet_id;
        s.pending.insert(id, Pending { recipient: recipient.clone(), digest: packet.digest()?, expires: packet.header.expires_at_ms, tx, legs: HashMap::new() });
        Some((AssistOperation::Relay { packet }, rx, id))
    }
    pub fn pending_guard(self: &Arc<Self>, id: [u8; 16]) -> PendingRelayGuard {
        PendingRelayGuard { assistance: Arc::clone(self), id }
    }
    pub fn cancel(&self, id: [u8; 16]) { self.state.lock().unwrap().pending.remove(&id); }

    pub fn order_helpers(&self, recipient: &str, helpers: &mut [String]) {
        self.state.lock().unwrap().quality.order(recipient, helpers, Instant::now());
    }

    /// Called in the polled leg, not when its future is merely constructed.
    pub fn leg_started(&self, id: [u8; 16], helper: &str) {
        if let Some(pending) = self.state.lock().unwrap().pending.get_mut(&id) {
            if pending.legs.len() < 2 {
                pending.legs.entry(helper.into()).or_insert(Leg { started: Instant::now(), written: false });
            }
        }
    }

    pub fn leg_written(&self, id: [u8; 16], helper: &str, written: bool) {
        let mut s = self.state.lock().unwrap();
        let Some(pending) = s.pending.get_mut(&id) else { return; };
        let Some(leg) = pending.legs.get_mut(helper) else { return; };
        leg.written = written;
        let recipient = pending.recipient.claims.node_id.clone();
        if !written { s.quality.write_failed(&recipient, helper, Instant::now()); }
    }

    /// Atomically consumes this attempt. Receipt and cancel paths use the same mutex;
    /// an abort is cancellation, not a failure observation. Packet IDs are fresh per attempt.
    pub fn finish_timeout(&self, id: [u8; 16]) {
        let mut s = self.state.lock().unwrap();
        let Some(pending) = s.pending.remove(&id) else { return; };
        let now = Instant::now();
        for (helper, leg) in pending.legs {
            if leg.written { s.quality.timed_out(&pending.recipient.claims.node_id, &helper, now); }
        }
    }

    pub fn handle(&self, identity: &InstalledSigningIdentity, directory: &PeerExchange,
        bytes: &[u8], binding: [u8; 32], remote: SocketAddr, now: i64) -> Option<AssistanceEffect> {
        if bytes.len() > MAX_ASSIST_BYTES { return None; }
        let signed: SignedAssist = serde_json::from_slice(bytes.strip_prefix(ASSIST_MAGIC)?).ok()?;
        let c = signed.claims;
        if c.version != 1 || c.to != identity.legacy_routing_node_id() || c.from == c.to
            || c.binding != binding || c.id == [0; 16] || c.created_at_ms <= 0
            || c.created_at_ms > now.saturating_add(SKEW_MS) || now.saturating_sub(c.created_at_ms) > PACKET_MS
            || signed.signature.len() != 64 || directory.pinned_key(&c.from)? != c.signing_key { return None; }
        Ed25519KeyPair::verify(&c.signing_key, &canonical(FRAME_DOMAIN, &c)?, &signed.signature).ok()?;
        let mut s = self.state.lock().unwrap(); cleanup(&mut s, now);
        let new_transit = matches!(&c.operation, AssistOperation::Connect { .. } | AssistOperation::Relay { .. });
        if new_transit && !s.policy.enabled { return None; }
        // Receipts consume a matching pending/route exactly once; they need no separate
        // replay slot. This lets already-admitted transit drain even at capacity or opt-out.
        if !matches!(&c.operation, AssistOperation::Receipt { .. }) {
            if s.frames.iter().any(|(id, _)| id == &c.id) { return None; }
            let ceiling = if new_transit { MAX_ROUTES / 2 } else { MAX_ROUTES };
            if s.frames.len() >= ceiling { return None; }
            s.frames.push_back((c.id, now));
        }
        let mut effect = AssistanceEffect { sender: Some(c.from.clone()), ..AssistanceEffect::default() };
        match c.operation {
            AssistOperation::Connect { target } => {
                if target == c.from || target == c.to || !valid_node_id(&target) || s.controls.len() >= 30 { return None; }
                let target_peer = directory.authenticated_peer(&target, now)?;
                let source = directory.authenticated_peer(&c.from, now)?;
                if source.identity.claims.signing_key != c.signing_key { return None; }
                let pair = (c.from.clone(), target.clone());
                if s.introductions.get(&pair).map(|at| now.saturating_sub(*at) < 30_000).unwrap_or(false) { return None; }
                s.controls.push_back(now); s.introductions.insert(pair, now);
                // No user-supplied endpoints and no introductions to arbitrary LAN/Internet hosts.
                if is_public_endpoint(target_peer.endpoint) && is_public_endpoint(remote) {
                    effect.outbound.push((c.from.clone(), AssistOperation::Introduction {
                        peer: target_peer.identity, endpoint: target_peer.endpoint }));
                    effect.outbound.push((target, AssistOperation::Introduction { peer: source.identity, endpoint: remote }));
                }
            }
            AssistOperation::Introduction { peer, endpoint } => {
                if s.controls.len() >= 60 || !is_public_endpoint(endpoint) || peer.claims.node_id == c.to || !peer.verify(now)
                    || !directory.accept_identity_hint(&peer, now) { return None; }
                s.controls.push_back(now);
                effect.probe = Some((peer, endpoint));
            }
            AssistOperation::Relay { packet } => {
                if !packet.verify(now) || packet.header.sender.claims.node_id != c.from
                    || packet.header.sender.claims.signing_key != c.signing_key || packet.header.recipient == c.to
                    || s.routes.len() >= s.policy.max_routes || s.routes.contains_key(&(c.from.clone(), packet.header.packet_id)) { return None; }
                let target = directory.authenticated_peer(&packet.header.recipient, now)?;
                if target.identity.claims.signing_key != packet.header.recipient_signing_key
                    || target.identity.claims.exchange_key != packet.header.recipient_exchange_key
                    || !admit_transit(&mut s, &c.from, bytes.len(), now) { return None; }
                s.routes.insert((c.from.clone(), packet.header.packet_id), Route { origin: c.from, recipient: target.identity,
                    digest: packet.digest()?, expires: packet.header.expires_at_ms });
                effect.outbound.push((packet.header.recipient.clone(), AssistOperation::Deliver { packet }));
            }
            AssistOperation::Deliver { packet } => {
                // Retries use real bandwidth too. Admission precedes expensive box opening
                // and receipt signing, including duplicates and invalid inner packets.
                if !admit_bytes(&mut s, &c.from, bytes.len(), now)
                    || !packet.verify(now) || packet.header.recipient != c.to { return None; }
                let key = (packet.header.sender.claims.node_id.clone(), packet.header.packet_id);
                let digest = packet.digest()?;
                if let Some(seen) = s.received.get(&key) {
                    if seen.digest != digest { return None; }
                    // Exact retry via another enrolled helper: re-use recipient-signed ACK,
                    // without decrypting/re-signing or dispatching the payload again.
                    effect.outbound.push((c.from, AssistOperation::Receipt { receipt: seen.receipt.clone() }));
                    return Some(effect);
                }
                // Never evict a live entry to admit a new packet: that permits re-delivery.
                // Per-sender bytes bound traffic; the cache remains globally bounded too.
                if s.received.len() >= MAX_ROUTES { return None; }
                let plaintext = open(identity, &packet, now)?;
                if !valid_inner(&plaintext, &packet.header.sender.claims.node_id)
                    || !directory.accept_end_to_end_identity(&packet.header.sender, now) { return None; }
                let claims = ReceiptClaims { packet_id: packet.header.packet_id, origin: packet.header.sender.claims.node_id.clone(),
                    recipient: c.to.clone(), digest, expires_at_ms: packet.header.expires_at_ms };
                let signature = identity.sign_security_payload(&canonical(ACK_DOMAIN, &claims)?);
                let receipt = Receipt { claims, signature };
                s.received.insert(key, Received { digest, expires: packet.header.expires_at_ms, receipt: receipt.clone() });
                effect.delivery = Some(plaintext);
                effect.outbound.push((c.from, AssistOperation::Receipt { receipt }));
            }
            AssistOperation::Receipt { receipt } => {
                if receipt.claims.expires_at_ms <= now || receipt.signature.len() != 64 { return None; }
                let id = receipt.claims.packet_id;
                if receipt.claims.origin == c.to {
                    let pending = s.pending.get(&id)?;
                    if pending.digest != receipt.claims.digest || pending.recipient.claims.node_id != receipt.claims.recipient
                        || pending.expires != receipt.claims.expires_at_ms { return None; }
                    Ed25519KeyPair::verify(&pending.recipient.claims.signing_key, &canonical(ACK_DOMAIN, &receipt.claims)?, &receipt.signature).ok()?;
                    let pending = s.pending.remove(&id)?;
                    // The recipient signature proves acceptance, not a globally provable
                    // helper path. Credit only this locally started, authenticated return leg.
                    if let Some(leg) = pending.legs.get(&c.from) {
                        let now = Instant::now();
                        s.quality.accepted(&pending.recipient.claims.node_id, &c.from,
                            now.saturating_duration_since(leg.started), now);
                    }
                    // Other legs are censored by the race, NOT failed deliveries.
                    let _ = pending.tx.send(true);
                } else {
                    let key = (receipt.claims.origin.clone(), id);
                    let route = s.routes.get(&key)?;
                    if route.origin != receipt.claims.origin || route.recipient.claims.node_id != c.from
                        || route.recipient.claims.node_id != receipt.claims.recipient || route.digest != receipt.claims.digest
                        || route.expires != receipt.claims.expires_at_ms { return None; }
                    Ed25519KeyPair::verify(&route.recipient.claims.signing_key, &canonical(ACK_DOMAIN, &receipt.claims)?, &receipt.signature).ok()?;
                    let route = s.routes.remove(&key)?;
                    effect.outbound.push((route.origin, AssistOperation::Receipt { receipt }));
                }
            }
        }
        Some(effect)
    }
}

fn valid_inner(bytes: &[u8], sender: &str) -> bool {
    if bytes.is_empty() || bytes.len() > MAX_INNER_BYTES { return false; }
    if bytes.starts_with(&super::file_wire::FILE_WIRE_MAGIC) {
        return matches!(super::file_wire::FileFrameV1::decode(bytes), Ok(super::file_wire::FileFrameV1::ChunkData(_)));
    }
    let Ok(text) = std::str::from_utf8(bytes) else { return false; };
    let parts: Vec<_> = text.splitn(4, '|').collect();
    parts.len() == 4 && parts[0] == sender && !parts[1].is_empty()
}
fn admit_transit(s: &mut State, peer: &str, bytes: usize, now: i64) -> bool {
    let used: usize = s.transit_traffic.iter().map(|(bytes, _)| *bytes).sum();
    if !s.policy.enabled || s.transit_traffic.len() >= s.policy.max_relays_per_minute
        || used.saturating_add(bytes) > s.policy.max_bytes_per_minute { return false; }
    if !admit_bytes(s, peer, bytes, now) { return false; }
    s.transit_traffic.push_back((bytes, now));
    true
}
fn admit_bytes(s: &mut State, peer: &str, bytes: usize, now: i64) -> bool {
    let global: usize = s.traffic.iter().map(|(_, b, _)| *b).sum();
    let personal: usize = s.traffic.iter().filter(|(id, _, _)| id == peer).map(|(_, b, _)| *b).sum();
    if global.saturating_add(bytes) > MAX_BYTES_PER_MINUTE || personal.saturating_add(bytes) > MAX_PEER_BYTES_PER_MINUTE || s.traffic.len() >= MAX_ROUTES { return false; }
    s.traffic.push_back((peer.into(), bytes, now)); true
}
fn cleanup(s: &mut State, now: i64) {
    s.transit_traffic.retain(|(_, at)| now.saturating_sub(*at) < 60_000);
    s.pending.retain(|_, p| p.expires > now); s.routes.retain(|_, r| r.expires > now);
    s.received.retain(|_, seen| seen.expires > now);
    s.frames.retain(|(_, at)| now.saturating_sub(*at) < PACKET_MS);
    s.traffic.retain(|(_, _, at)| now.saturating_sub(*at) < 60_000);
    s.controls.retain(|at| now.saturating_sub(*at) < 60_000);
    s.requested.retain(|_, at| now.saturating_sub(*at) < 60_000);
    s.introductions.retain(|_, at| now.saturating_sub(*at) < 60_000);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::time::Duration;
    use super::super::peer_exchange::Candidate;
    const NOW: i64 = 1_000_000;
    const BINDING: [u8; 32] = [17; 32];
    fn identity(n: u8) -> Arc<InstalledSigningIdentity> {
        Arc::new(InstalledSigningIdentity::from_seed(1, format!("pk_{}", format!("{n:02x}").repeat(32)), &[n; 32]).unwrap())
    }
    fn addr(n: u8) -> SocketAddr { format!("8.8.4.{n}:7777").parse().unwrap() }
    fn enroll(a: &InstalledSigningIdentity, da: &PeerExchange, h: &InstalledSigningIdentity, dh: &PeerExchange, endpoint: SocketAddr, source: SocketAddr) {
        // Explicit enrollment must target this helper, not a third-party record
        // that the normal discovery planner may legitimately prioritise first.
        let request = da.probe(a, Some(source), h.legacy_routing_node_id(), endpoint, NOW).unwrap();
        da.bind_request(&request, BINDING, endpoint);
        let effect = dh.handle(h, &request.encode(a, BINDING).unwrap(), BINDING, source, Some(endpoint), NOW).unwrap();
        da.handle(a, &effect.response.unwrap(), BINDING, endpoint, Some(source), NOW).unwrap();
    }

    #[test]
    fn encrypted_box_only_recipient_can_open_and_tampering_fails() {
        let a = identity(1); let b = identity(2); let h = identity(3);
        let recipient = HelperIdentity::create(&b, NOW).unwrap();
        let data = format!("{}|m1|chat|private text", a.legacy_routing_node_id());
        let mut packet = seal(&a, &recipient, data.as_bytes(), NOW).unwrap();
        assert_eq!(open(&b, &packet, NOW).unwrap(), data.as_bytes());
        assert!(open(&h, &packet, NOW).is_none());
        assert!(!packet.ciphertext.contains("private text"));
        packet.header.recipient = h.legacy_routing_node_id().into();
        assert!(open(&h, &packet, NOW).is_none());
        assert!(seal(&a, &recipient, &vec![1; MAX_INNER_BYTES + 1], NOW).is_none());
    }

    #[test]
    fn each_helper_introduces_both_parties_using_observed_addresses_only() {
        let a = identity(1); let b = identity(2); let h = identity(3);
        let da = PeerExchange::open(None); let db = PeerExchange::open(None); let dh = PeerExchange::open(None);
        enroll(&a, &da, &h, &dh, addr(3), addr(1));
        enroll(&b, &db, &h, &dh, addr(3), addr(2));
        let helper = PeerAssistance::new();
        let bytes = encode(&a, h.legacy_routing_node_id(), AssistOperation::Connect { target: b.legacy_routing_node_id().into() }, BINDING, NOW).unwrap();
        let effect = helper.handle(&h, &dh, &bytes, BINDING, addr(1), NOW).unwrap();
        assert_eq!(effect.outbound.len(), 2);
        assert!(effect.outbound.iter().any(|(id, op)| id == a.legacy_routing_node_id()
            && matches!(op, AssistOperation::Introduction { endpoint, .. } if *endpoint == addr(2))));
        assert!(helper.handle(&h, &dh, &bytes, [9; 32], addr(1), NOW).is_none());
        assert!(helper.handle(&h, &dh, &bytes, BINDING, addr(1), NOW).is_none());
        let unknown = encode(&a, h.legacy_routing_node_id(), AssistOperation::Connect { target: identity(9).legacy_routing_node_id().into() }, BINDING, NOW).unwrap();
        assert!(helper.handle(&h, &dh, &unknown, BINDING, addr(1), NOW).is_none());
    }

    #[test]
    fn relay_true_requires_recipient_signature_not_helper_acceptance() {
        let a = identity(1); let b = identity(2); let h = identity(3);
        let da = PeerExchange::open(None); let db = PeerExchange::open(None); let dh = PeerExchange::open(None);
        enroll(&a, &da, &h, &dh, addr(3), addr(1));
        enroll(&b, &db, &h, &dh, addr(3), addr(2));
        let sa = PeerAssistance::new(); let sb = PeerAssistance::new(); let sh = PeerAssistance::new();
        sb.set_policy(false, 0, 0, 0); // Own delivery must still work after online opt-out.
        let target = HelperIdentity::create(&b, NOW).unwrap();
        let text = format!("{}|m2|chat|secret", a.legacy_routing_node_id());
        let (op, mut receipt, _) = sa.prepare_relay(&a, &target, text.as_bytes(), NOW).unwrap();
        let wire = encode(&a, h.legacy_routing_node_id(), op, BINDING, NOW).unwrap();
        let forward = sh.handle(&h, &dh, &wire, BINDING, addr(1), NOW).unwrap().outbound.remove(0);
        assert!(receipt.try_recv().is_err(), "writing a helper stream is not a sent receipt");
        sh.set_policy(false, 0, 0, 0); // Accepted relay may drain after opt-out.
        for n in 0..MAX_ROUTES { sh.state.lock().unwrap().frames.push_back(([n as u8; 16], NOW)); }
        let delivered = encode(&h, &forward.0, forward.1, BINDING, NOW).unwrap();
        let at_b = sb.handle(&b, &db, &delivered, BINDING, addr(3), NOW).unwrap();
        assert_eq!(at_b.delivery.unwrap(), text.as_bytes());
        let ack = at_b.outbound.into_iter().next().unwrap();
        let wire = encode(&b, &ack.0, ack.1, BINDING, NOW).unwrap();
        let back = sh.handle(&h, &dh, &wire, BINDING, addr(2), NOW).unwrap().outbound.remove(0);
        let wire = encode(&h, &back.0, back.1, BINDING, NOW).unwrap();
        sa.handle(&a, &da, &wire, BINDING, addr(3), NOW).unwrap();
        assert!(receipt.try_recv().unwrap());
    }

    #[test]
    fn private_or_arbitrary_endpoints_are_not_probed_and_traffic_is_bounded() {
        let a = identity(1); let h = identity(3); let da = PeerExchange::open(None); let dh = PeerExchange::open(None);
        enroll(&a, &da, &h, &dh, addr(3), addr(1));
        let helper = PeerAssistance::new();
        let op = AssistOperation::Introduction { peer: HelperIdentity::create(&identity(2), NOW).unwrap(), endpoint: "10.0.0.1:22".parse().unwrap() };
        let bytes = encode(&h, a.legacy_routing_node_id(), op, BINDING, NOW).unwrap();
        assert!(helper.handle(&a, &da, &bytes, BINDING, addr(3), NOW).is_none());
        let mut state = State::default();
        assert!(admit_bytes(&mut state, "peer", MAX_PEER_BYTES_PER_MINUTE, NOW));
        assert!(!admit_bytes(&mut state, "peer", 1, NOW));
        cleanup(&mut state, NOW + 60_000);
        assert!(admit_bytes(&mut state, "peer", 1, NOW + 60_000));
        assert!(!valid_inner(b"pk_forged|m|chat|text", a.legacy_routing_node_id()));
        assert!(helper.connect_request(h.legacy_routing_node_id(), NOW).is_some());
        assert!(helper.connect_request(h.legacy_routing_node_id(), NOW + 1).is_none());
    }

    #[test]
    fn two_helpers_deliver_once_and_each_gets_recipient_receipt_even_at_capacity() {
        let a = identity(1); let b = identity(2); let h = identity(3); let j = identity(4);
        let da = PeerExchange::open(None); let db = PeerExchange::open(None);
        let dh = PeerExchange::open(None); let dj = PeerExchange::open(None);
        enroll(&a, &da, &h, &dh, addr(3), addr(1));
        enroll(&b, &db, &h, &dh, addr(3), addr(2));
        enroll(&a, &da, &j, &dj, addr(4), addr(1));
        enroll(&b, &db, &j, &dj, addr(4), addr(2));
        let sa = PeerAssistance::new(); let sb = PeerAssistance::new();
        let sh = PeerAssistance::new(); let sj = PeerAssistance::new();
        let text = format!("{}|hedged|chat|one message", a.legacy_routing_node_id());
        let (op, mut receipt, packet_id) = sa.prepare_relay(&a, &HelperIdentity::create(&b, NOW).unwrap(), text.as_bytes(), NOW).unwrap();
        let mut deliveries = 0;
        for (helper, directory, assistance) in [(&h, &dh, &sh), (&j, &dj, &sj)] {
            sa.leg_started(packet_id, helper.legacy_routing_node_id());
            sa.leg_written(packet_id, helper.legacy_routing_node_id(), true);
            let wire = encode(&a, helper.legacy_routing_node_id(), op.clone(), BINDING, NOW).unwrap();
            let forward = assistance.handle(helper, directory, &wire, BINDING, addr(1), NOW).unwrap().outbound.remove(0);
            let wire = encode(helper, &forward.0, forward.1, BINDING, NOW).unwrap();
            let at_b = sb.handle(&b, &db, &wire, BINDING, addr(3), NOW).unwrap();
            if let Some(payload) = at_b.delivery { assert_eq!(payload, text.as_bytes()); deliveries += 1; }
            let ack = at_b.outbound.into_iter().next().unwrap();
            // Fill the cache after first acceptance; known duplicates still get a receipt.
            if deliveries == 1 && sb.state.lock().unwrap().received.len() == 1 {
                let mut state = sb.state.lock().unwrap();
                let cached = match &ack.1 { AssistOperation::Receipt { receipt } => receipt.clone(), _ => unreachable!() };
                for n in 1..MAX_ROUTES {
                    state.received.insert((format!("filler-{n}"), [0; 16]), Received {
                        digest: [0; 32], expires: NOW + PACKET_MS, receipt: cached.clone(),
                    });
                }
            }
            let wire = encode(&b, &ack.0, ack.1, BINDING, NOW).unwrap();
            let back = assistance.handle(helper, directory, &wire, BINDING, addr(2), NOW).unwrap().outbound.remove(0);
            let wire = encode(helper, &back.0, back.1, BINDING, NOW).unwrap();
            if helper.legacy_routing_node_id() == h.legacy_routing_node_id() {
                // Drop the first helper's verified return receipt. The reserve must win.
                assert!(receipt.try_recv().is_err());
            } else { sa.handle(&a, &da, &wire, BINDING, addr(4), NOW).unwrap(); }
        }
        assert_eq!(deliveries, 1);
        assert!(receipt.try_recv().unwrap());
        let mut order = vec![h.legacy_routing_node_id().into(), j.legacy_routing_node_id().into()];
        sa.order_helpers(b.legacy_routing_node_id(), &mut order);
        assert_eq!(order[0], j.legacy_routing_node_id(), "only the authenticated winning leg is credited");
        // The primary losing this race does not enter timeout cooldown.
        sa.state.lock().unwrap().quality.accepted(b.legacy_routing_node_id(), h.legacy_routing_node_id(),
            std::time::Duration::ZERO, Instant::now());
        order = vec![h.legacy_routing_node_id().into(), j.legacy_routing_node_id().into()];
        sa.order_helpers(b.legacy_routing_node_id(), &mut order);
        assert_eq!(order[0], h.legacy_routing_node_id());
        assert!(!sa.state.lock().unwrap().pending.contains_key(&packet_id));
        assert_eq!(sb.state.lock().unwrap().traffic.len(), 2, "duplicate consumes bandwidth budget");
        let (fresh, _, _) = sa.prepare_relay(&a, &HelperIdentity::create(&b, NOW).unwrap(), text.as_bytes(), NOW).unwrap();
        let packet = match fresh { AssistOperation::Relay { packet } => packet, _ => unreachable!() };
        let wire = encode(&h, b.legacy_routing_node_id(), AssistOperation::Deliver { packet }, BINDING, NOW).unwrap();
        assert!(sb.handle(&b, &db, &wire, BINDING, addr(3), NOW).is_none(), "new packet refused at capacity without eviction");
    }

    #[test]
    fn same_sender_id_cannot_reuse_packet_id_for_different_content_but_other_sender_can() {
        let a = identity(1); let b = identity(2); let h = identity(3); let other = identity(4);
        let db = PeerExchange::open(None); let dh = PeerExchange::open(None);
        enroll(&b, &db, &h, &dh, addr(3), addr(2));
        let sb = PeerAssistance::new();
        let mut packet = seal(&a, &HelperIdentity::create(&b, NOW).unwrap(),
            format!("{}|m1|chat|first", a.legacy_routing_node_id()).as_bytes(), NOW).unwrap();
        let collision = packet.header.packet_id;
        let original_header = packet.header.clone();
        let wire = encode(&h, b.legacy_routing_node_id(), AssistOperation::Deliver { packet: packet.clone() }, BINDING, NOW).unwrap();
        assert!(sb.handle(&b, &db, &wire, BINDING, addr(3), NOW).unwrap().delivery.is_some());
        // A signed conflicting ciphertext must not be answered with the cached ACK.
        packet.ciphertext.push('A');
        packet.signature = a.sign_security_payload(&packet.signed_bytes().unwrap());
        let wire = encode(&h, b.legacy_routing_node_id(), AssistOperation::Deliver { packet }, BINDING, NOW).unwrap();
        assert!(sb.handle(&b, &db, &wire, BINDING, addr(3), NOW).is_none());
        // Create a valid second box with the same id, including matching AAD/key derivation.
        let ephemeral = X25519KeyPair::generate();
        let header = PacketHeader { sender: HelperIdentity::create(&other, NOW).unwrap(), packet_id: collision,
            ephemeral_key: ephemeral.public_key().0.try_into().unwrap(), ..original_header };
        let shared = ephemeral.diffie_hellman(&header.recipient_exchange_key).unwrap();
        let key = derive(&shared, &header).unwrap();
        let data = format!("{}|m2|chat|second sender", other.legacy_routing_node_id());
        let ciphertext = XChaCha20Poly1305::new_from_slice(&key).unwrap().encrypt(XNonce::from_slice(&header.nonce),
            Payload { msg: data.as_bytes(), aad: &canonical(PACKET_DOMAIN, &header).unwrap() }).unwrap();
        let mut packet = SealedPacket { header, ciphertext: STANDARD.encode(ciphertext), signature: vec![] };
        packet.signature = other.sign_security_payload(&packet.signed_bytes().unwrap());
        let wire = encode(&h, b.legacy_routing_node_id(), AssistOperation::Deliver { packet }, BINDING, NOW).unwrap();
        assert_eq!(sb.handle(&b, &db, &wire, BINDING, addr(3), NOW).unwrap().delivery.unwrap(), data.as_bytes());
        assert_eq!(sb.state.lock().unwrap().received.len(), 2);
    }

    #[test]
    fn helper_cannot_forge_acceptance_and_guard_releases_origin_admission() {
        let a = identity(1); let b = identity(2); let h = identity(3);
        let da = PeerExchange::open(None); let dh = PeerExchange::open(None);
        enroll(&a, &da, &h, &dh, addr(3), addr(1));
        let sa = Arc::new(PeerAssistance::new());
        let (operation, mut rx, packet_id) = sa.prepare_relay(&a, &HelperIdentity::create(&b, NOW).unwrap(),
            format!("{}|m|chat|private", a.legacy_routing_node_id()).as_bytes(), NOW).unwrap();
        let guard = sa.pending_guard(packet_id);
        sa.leg_started(packet_id, h.legacy_routing_node_id());
        let packet = match operation { AssistOperation::Relay { packet } => packet, _ => unreachable!() };
        let claims = ReceiptClaims { packet_id, origin: a.legacy_routing_node_id().into(),
            recipient: b.legacy_routing_node_id().into(), digest: packet.digest().unwrap(), expires_at_ms: NOW + PACKET_MS };
        let signature = h.sign_security_payload(&canonical(ACK_DOMAIN, &claims).unwrap());
        let wire = encode(&h, a.legacy_routing_node_id(), AssistOperation::Receipt { receipt: Receipt { claims, signature } }, BINDING, NOW).unwrap();
        assert!(sa.handle(&a, &da, &wire, BINDING, addr(3), NOW).is_none());
        assert!(matches!(rx.try_recv(), Err(oneshot::error::TryRecvError::Empty)));
        let mut order = vec!["unknown".into(), h.legacy_routing_node_id().into()];
        sa.order_helpers(b.legacy_routing_node_id(), &mut order);
        assert_eq!(order[0], "unknown", "forged ACK cannot improve ranking");
        drop(guard);
        assert!(sa.state.lock().unwrap().pending.is_empty());
        assert!(matches!(rx.try_recv(), Err(oneshot::error::TryRecvError::Closed)));
    }

    #[test]
    fn timeout_and_abort_only_observe_written_legs_of_this_attempt() {
        let a = identity(1); let b = identity(2);
        let sa = Arc::new(PeerAssistance::new());
        let target = HelperIdentity::create(&b, NOW).unwrap();
        let data = format!("{}|m|chat|text", a.legacy_routing_node_id());
        let (_, _, id) = sa.prepare_relay(&a, &target, data.as_bytes(), NOW).unwrap();
        sa.state.lock().unwrap().quality.accepted(b.legacy_routing_node_id(), "reachable",
            std::time::Duration::from_millis(100), Instant::now());
        let guard = sa.pending_guard(id);
        sa.leg_started(id, "primary");
        sa.leg_written(id, "primary", true);
        sa.leg_started(id, "reserve"); // Canceled write is not a timeout observation.
        sa.finish_timeout(id);
        drop(guard);
        let mut order = vec!["primary".into(), "reserve".into()];
        sa.order_helpers(b.legacy_routing_node_id(), &mut order);
        assert_eq!(order, ["reserve", "primary"]);
        let (_, _, aborted) = sa.prepare_relay(&a, &target, data.as_bytes(), NOW).unwrap();
        let guard = sa.pending_guard(aborted);
        sa.leg_started(aborted, "reserve");
        sa.leg_written(aborted, "reserve", true);
        drop(guard); // Outer FFI abort is not evidence of a failed helper.
        sa.finish_timeout(aborted);
        sa.leg_written(aborted, "reserve", false); // Late write cannot poison history.
        let mut order = vec!["reserve".into(), "fresh".into()];
        sa.order_helpers(b.legacy_routing_node_id(), &mut order);
        assert_eq!(order, ["reserve", "fresh"]);
        assert!(sa.state.lock().unwrap().pending.is_empty());
    }

    #[test]
    fn online_opt_out_refuses_new_transit_without_spending_replay_slots() {
        let a = identity(1); let h = identity(3);
        let da = PeerExchange::open(None); let dh = PeerExchange::open(None);
        enroll(&a, &da, &h, &dh, addr(3), addr(1));
        let sh = PeerAssistance::new();
        sh.set_policy(false, 32, u32::MAX, 240);
        let op = AssistOperation::Connect { target: identity(2).legacy_routing_node_id().into() };
        let bytes = encode(&a, h.legacy_routing_node_id(), op, BINDING, NOW).unwrap();
        assert!(sh.handle(&h, &dh, &bytes, BINDING, addr(1), NOW).is_none());
        assert!(sh.state.lock().unwrap().frames.is_empty());
        let target = HelperIdentity::create(&identity(2), NOW).unwrap();
        let packet = seal(&a, &target, format!("{}|m|c|text", a.legacy_routing_node_id()).as_bytes(), NOW).unwrap();
        let bytes = encode(&a, h.legacy_routing_node_id(), AssistOperation::Relay { packet }, BINDING, NOW).unwrap();
        assert!(sh.handle(&h, &dh, &bytes, BINDING, addr(1), NOW).is_none());
        let state = sh.state.lock().unwrap();
        assert!(state.routes.is_empty() && state.frames.is_empty() && state.transit_traffic.is_empty());
    }

    #[test]
    fn transit_budget_leaves_room_for_own_data_and_toggles_do_not_reset_it() {
        let mut s = State::default();
        s.policy = HelperPolicy::bounded(true, 8, u32::MAX, 240);
        assert!(admit_transit(&mut s, "a", MAX_PEER_BYTES_PER_MINUTE, NOW));
        assert!(admit_transit(&mut s, "b", MAX_PEER_BYTES_PER_MINUTE, NOW));
        assert!(!admit_transit(&mut s, "c", 1, NOW));
        assert!(admit_bytes(&mut s, "c", MAX_PEER_BYTES_PER_MINUTE, NOW));
        assert!(admit_bytes(&mut s, "d", MAX_PEER_BYTES_PER_MINUTE, NOW));
        s.policy = HelperPolicy::bounded(false, 8, 100, 1);
        assert!(!admit_transit(&mut s, "e", 1, NOW));
        s.policy = HelperPolicy::bounded(true, 8, u32::MAX, 240);
        assert!(!admit_transit(&mut s, "e", 1, NOW));
        cleanup(&mut s, NOW + 60_000);
        assert!(admit_transit(&mut s, "e", 1, NOW + 60_000));
        s.policy = HelperPolicy::bounded(true, 8, 100, 1);
        assert!(!admit_transit(&mut s, "f", 1, NOW + 60_000), "packet quota includes earlier policy traffic");
    }

    // Real QUIC with four endpoints: neither sender nor receiver has a direct
    // connection to the other. Primary forwards but drops the ACK; reserve must win.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn real_four_node_hedged_relay_with_lost_primary_receipt_and_no_duplicate() {
        use super::super::direct_transport::{DirectTransport, BoundFrameFactory, BoundFrameHandler, BoundFrameResult};
        struct Node { id: Arc<InstalledSigningIdentity>, directory: Arc<PeerExchange>, assist: Arc<PeerAssistance>, transport: DirectTransport }
        fn node(n: u8, tx: tokio::sync::mpsc::UnboundedSender<Vec<u8>>) -> Node {
            let id = identity(n); let directory = Arc::new(PeerExchange::open(None)); let assist = Arc::new(PeerAssistance::new());
            let slot: Arc<Mutex<Option<DirectTransport>>> = Arc::new(Mutex::new(None));
            let handler: BoundFrameHandler = {
                let id = Arc::clone(&id); let directory = Arc::clone(&directory); let assist = Arc::clone(&assist); let slot = Arc::clone(&slot);
                Arc::new(move |bytes, binding, remote| {
                    let now = crate::storage::models::now_ms();
                    if bytes.starts_with(super::super::peer_exchange::EXCHANGE_MAGIC) {
                        return match directory.handle(&id, bytes, binding, remote, None, now) {
                            Some(effect) => BoundFrameResult { sender: Some(effect.sender_id), response: effect.response },
                            None => BoundFrameResult::default(),
                        };
                    }
                    let Some(effect) = assist.handle(&id, &directory, bytes, binding, remote, now) else { return BoundFrameResult::default(); };
                    if let Some(data) = effect.delivery { let _ = tx.send(data); }
                    if let Some(transport) = slot.lock().unwrap().clone() {
                        for (peer, op) in effect.outbound {
                            if n == 3 && matches!(op, AssistOperation::Receipt { .. }) { continue; }
                            let transport = transport.clone(); let id = Arc::clone(&id);
                            tokio::spawn(async move {
                                let target = peer.clone();
                                let factory: BoundFrameFactory = Arc::new(move |binding, _| encode(&id, &target, op.clone(), binding, crate::storage::models::now_ms()));
                                let _ = transport.send_bound(&peer, None, factory).await;
                            });
                        }
                    }
                    BoundFrameResult { sender: effect.sender, response: None }
                })
            };
            let (transport, _) = DirectTransport::start_with_handlers("127.0.0.1:0".parse().unwrap(), Arc::new(|_| None), None, Some(handler), None).unwrap();
            *slot.lock().unwrap() = Some(transport.clone());
            Node { id, directory, assist, transport }
        }
        async fn hello(client: &Node, helper: &Node) {
            let now = crate::storage::models::now_ms();
            client.directory.wake();
            let request = client.directory.plan(&client.id, None, vec![Candidate { node_id: helper.id.legacy_routing_node_id().into(),
                endpoint: "8.8.8.8:7777".parse().unwrap(), learned_at_ms: now, live_connection: false }], &[], now).remove(0);
            let directory = Arc::clone(&client.directory); let id = Arc::clone(&client.id);
            let factory: BoundFrameFactory = Arc::new(move |binding, remote| {
                if !directory.bind_request(&request, binding, remote) { return None; }
                request.encode(&id, binding)
            });
            assert!(client.transport.send_bound(helper.id.legacy_routing_node_id(), Some(helper.transport.local_addr()), factory).await);
            tokio::time::timeout(Duration::from_secs(3), async {
                while client.directory.authenticated_peer(helper.id.legacy_routing_node_id(), crate::storage::models::now_ms()).is_none()
                    || !helper.transport.has_connection(client.id.legacy_routing_node_id()).await {
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            }).await.unwrap();
        }
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        let a = node(1, tx.clone()); let b = node(2, tx.clone()); let h = node(3, tx.clone()); let j = node(4, tx);
        hello(&a, &h).await; hello(&b, &h).await;
        hello(&a, &j).await; hello(&b, &j).await;
        assert!(!a.transport.has_connection(b.id.legacy_routing_node_id()).await);
        let now = crate::storage::models::now_ms();
        let expected = format!("{}|message|chat|protected transit", a.id.legacy_routing_node_id());
        let recipient = HelperIdentity::create(&b.id, now).unwrap();
        let (operation, receipt, packet_id) = a.assist.prepare_relay(&a.id, &recipient, expected.as_bytes(), now).unwrap();
        let assistance = Arc::clone(&a.assist);
        let _pending = assistance.pending_guard(match &operation { AssistOperation::Relay { packet } => packet.header.packet_id, _ => unreachable!() });
        let peers = [h.id.legacy_routing_node_id().to_string(), j.id.legacy_routing_node_id().to_string()];
        let mut started = Vec::new();
        let accepted = super::super::helper_delivery::hedged_delivery(2, receipt, |leg| {
            started.push(leg);
            let peer = peers[leg].clone(); let target = peer.clone();
            let id = Arc::clone(&a.id); let operation = operation.clone(); let transport = a.transport.clone();
            let assist = Arc::clone(&a.assist);
            async move {
                assist.leg_started(packet_id, &peer);
                let factory: BoundFrameFactory = Arc::new(move |binding, _| encode(&id, &target, operation.clone(), binding, crate::storage::models::now_ms()));
                let written = transport.send_bound(&peer, None, factory).await;
                assist.leg_written(packet_id, &peer, written);
                written
            }
        }, Duration::from_millis(100), Duration::from_secs(3)).await;
        assert!(accepted, "only reserve forwards the recipient receipt");
        assert_eq!(started, [0, 1]);
        let mut order = peers.to_vec();
        a.assist.order_helpers(b.id.legacy_routing_node_id(), &mut order);
        assert_eq!(order[0], j.id.legacy_routing_node_id());
        let data = tokio::time::timeout(Duration::from_secs(3), rx.recv()).await.unwrap().unwrap();
        assert_eq!(data, expected.as_bytes());
        assert!(tokio::time::timeout(Duration::from_millis(200), rx.recv()).await.is_err(), "same box dispatched once");
        assert_eq!(b.assist.state.lock().unwrap().received.len(), 1);
        assert_eq!(h.assist.state.lock().unwrap().routes.len(), 0, "primary consumed ACK but did not return it");
        assert_eq!(j.assist.state.lock().unwrap().routes.len(), 0);
        assert!(!a.transport.has_connection(b.id.legacy_routing_node_id()).await);
        a.transport.shutdown(); b.transport.shutdown(); h.transport.shutdown(); j.transport.shutdown();
    }
}
