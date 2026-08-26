//! Signed, contact-scoped path presence and deterministic file-path selection.
//!
//! This module does not trust the legacy unsigned gossip presence table. A beacon is addressed to
//! one contact, signed by the already pinned Ed25519 identity, short-lived, replay ordered and
//! bounded. It advertises connection candidates only; file bytes never pass through this codec.

use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, SocketAddr};
use std::path::Path;
use std::sync::Mutex;

use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};

use crate::crypto::keys::{
    Ed25519KeyPair, NodeId, ED25519_PUBLIC_KEY_SIZE, SIGNATURE_SIZE,
};
use crate::network::file_control::{FileControlError, FileControlSigner};
use crate::network::file_session::FileSessionPeer;
use crate::network::file_session_owner::FileSessionTarget;

pub const FILE_PATH_BEACON_INTERVAL_MS: i64 = 60_000;
pub const FILE_PATH_BEACON_LIFETIME_MS: i64 = 90_000;
pub const FILE_PATH_CLOCK_SKEW_MS: i64 = 5_000;
pub const MAX_FILE_PATH_CANDIDATES: usize = 8;
pub const MAX_FILE_PATH_CONTACTS: usize = 256;
pub const MAX_FILE_PATH_BEACON_BYTES: usize = 4 * 1024;
pub const MAX_FILE_PATH_FAILURE_BACKOFF_MS: i64 = 60_000;

const FILE_PATH_MAGIC: [u8; 4] = *b"APUP";
const FILE_PATH_VERSION_V1: u8 = 1;
const FILE_PATH_FLAGS_V1: u16 = 0;
const FILE_PATH_HEADER_BYTES: usize = 12;
const FILE_PATH_ID_BYTES: usize = 16;
const FILE_PATH_DOMAIN_V1: &[u8] = b"apu-file-path-presence-v1\0";
const MIN_NODE_ID_BYTES: usize = 35;
const MAX_NODE_ID_BYTES: usize = 67;

const FILE_PATH_STORE_MIGRATION_V1: &str = "
CREATE TABLE IF NOT EXISTS file_path_beacons (
    peer_key       BLOB PRIMARY KEY NOT NULL CHECK(length(peer_key) = 32),
    peer_node_id   TEXT NOT NULL,
    sequence_be    BLOB NOT NULL CHECK(length(sequence_be) = 8),
    expires_at_ms  INTEGER NOT NULL,
    beacon         BLOB NOT NULL CHECK(length(beacon) <= 4096)
);
CREATE INDEX IF NOT EXISTS idx_file_path_beacons_expiry
    ON file_path_beacons(expires_at_ms);
CREATE TABLE IF NOT EXISTS file_path_schema_version (version INTEGER PRIMARY KEY);
INSERT OR IGNORE INTO file_path_schema_version(version) VALUES (1);
";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum FilePathKindV1 {
    LanQuic = 1,
    InternetQuic = 2,
    DirectTcp = 3,
    TcpTunnel = 4,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FilePathCandidateV1 {
    pub candidate_id: [u8; FILE_PATH_ID_BYTES],
    pub kind: FilePathKindV1,
    /// Larger values win only among candidates of the same path kind.
    pub priority: u8,
    pub endpoint: SocketAddr,
    pub expires_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FilePathBeaconClaimsV1 {
    pub record_id: [u8; FILE_PATH_ID_BYTES],
    pub sequence: u64,
    pub created_at_ms: i64,
    pub expires_at_ms: i64,
    pub signer_node_id: String,
    pub recipient_node_id: String,
    pub candidates: Vec<FilePathCandidateV1>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SignedFilePathBeaconV1 {
    pub claims: FilePathBeaconClaimsV1,
    pub signer_ed25519_public_key: [u8; ED25519_PUBLIC_KEY_SIZE],
    pub signature: [u8; SIGNATURE_SIZE],
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FilePathPeer {
    pub node_id: String,
    pub ed25519_public_key: [u8; ED25519_PUBLIC_KEY_SIZE],
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FilePathNetworkState {
    pub lan_available: bool,
    pub udp_available: bool,
    pub tcp_available: bool,
    pub tunnel_available: bool,
}

impl Default for FilePathNetworkState {
    fn default() -> Self {
        Self {
            lan_available: true,
            udp_available: true,
            tcp_available: true,
            tunnel_available: true,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FilePathSelection {
    Selected(FilePathCandidateV1),
    Unavailable { retry_after_ms: Option<i64> },
}

/// Typed boundary between path selection and transport owners. Only `AuthenticatedQuic` contains a
/// `FileSessionTarget`; TCP routes cannot accidentally enter the QUIC-only authenticated owner.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FilePathDispatch {
    AuthenticatedQuic {
        candidate_id: [u8; FILE_PATH_ID_BYTES],
        target: FileSessionTarget,
    },
    DirectTcp {
        candidate_id: [u8; FILE_PATH_ID_BYTES],
        peer: FileSessionPeer,
        endpoint: SocketAddr,
    },
    TcpTunnel {
        candidate_id: [u8; FILE_PATH_ID_BYTES],
        peer: FileSessionPeer,
        endpoint: SocketAddr,
    },
    Unavailable { retry_after_ms: Option<i64> },
}

impl FilePathDispatch {
    pub fn authenticated_quic_target(&self) -> Option<&FileSessionTarget> {
        match self {
            Self::AuthenticatedQuic { target, .. } => Some(target),
            Self::DirectTcp { .. } | Self::TcpTunnel { .. } | Self::Unavailable { .. } => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum FilePathError {
    #[error("file path beacon header is truncated: {actual} bytes")]
    TruncatedHeader { actual: usize },
    #[error("invalid file path beacon magic")]
    InvalidMagic,
    #[error("unsupported file path beacon version: {0}")]
    UnsupportedVersion(u8),
    #[error("unsupported file path beacon flags: 0x{0:04x}")]
    UnsupportedFlags(u16),
    #[error("file path beacon length mismatch")]
    LengthMismatch,
    #[error("file path beacon is too large: {size} bytes (max {max})")]
    TooLarge { size: usize, max: usize },
    #[error("malformed file path beacon")]
    Malformed,
    #[error("invalid file path beacon: {0}")]
    Invalid(&'static str),
    #[error("file path beacon signer failed: {0}")]
    Signer(String),
    #[error("file path beacon signature is invalid")]
    InvalidSignature,
    #[error("file path beacon signer is not the pinned contact")]
    UnexpectedSigner,
    #[error("file path beacon recipient is not this device")]
    UnexpectedRecipient,
    #[error("file path beacon is not active")]
    NotActive,
    #[error("file path beacon sequence is replayed or out of order")]
    ReplayOrOutOfOrder,
    #[error("file path manager reached its contact limit {max}")]
    Capacity { max: usize },
    #[error("unknown file path candidate")]
    UnknownCandidate,
    #[error("selected QUIC path cannot form an authenticated session target: {0}")]
    InvalidSessionTarget(String),
    #[error("durable file path store failed: {0}")]
    Store(String),
}

#[derive(Debug, Clone)]
struct ManagedCandidate {
    candidate: FilePathCandidateV1,
    consecutive_failures: u8,
    retry_after_ms: i64,
}

#[derive(Debug, Clone)]
struct ManagedPeerPaths {
    node_id: String,
    last_sequence: u64,
    beacon_expires_at_ms: i64,
    candidates: Vec<ManagedCandidate>,
}

/// Bounded contact/path table. Durable replay persistence is supplied by the engine integration;
/// this in-memory host layer deliberately exposes the accepted sequence through `last_sequence`.
#[derive(Clone)]
pub struct FilePathManager {
    local_node_id: String,
    max_contacts: usize,
    peers: HashMap<[u8; ED25519_PUBLIC_KEY_SIZE], ManagedPeerPaths>,
}

/// Sync-only SQLite replay/expiry store. The transaction commits before the staged manager becomes
/// visible, so process death cannot leave an accepted in-memory sequence ahead of durable state.
pub struct FilePathStore {
    connection: Mutex<Connection>,
}

impl FilePathKindV1 {
    fn from_u8(value: u8) -> Result<Self, FilePathError> {
        match value {
            1 => Ok(Self::LanQuic),
            2 => Ok(Self::InternetQuic),
            3 => Ok(Self::DirectTcp),
            4 => Ok(Self::TcpTunnel),
            _ => Err(FilePathError::Invalid("unknown path kind")),
        }
    }

    fn rank(self) -> u8 {
        self as u8
    }

    fn available(self, state: FilePathNetworkState) -> bool {
        match self {
            Self::LanQuic => state.lan_available && state.udp_available,
            Self::InternetQuic => state.udp_available,
            Self::DirectTcp => state.tcp_available,
            Self::TcpTunnel => state.tcp_available && state.tunnel_available,
        }
    }
}

impl FilePathBeaconClaimsV1 {
    pub fn validate(&self) -> Result<(), FilePathError> {
        if self.record_id.iter().all(|byte| *byte == 0) {
            return Err(FilePathError::Invalid("record ID is all zero"));
        }
        if self.sequence == 0 {
            return Err(FilePathError::Invalid("sequence must be positive"));
        }
        let lifetime = self.expires_at_ms.saturating_sub(self.created_at_ms);
        if self.created_at_ms < 0 || lifetime <= 0 || lifetime > FILE_PATH_BEACON_LIFETIME_MS {
            return Err(FilePathError::Invalid("invalid beacon lifetime"));
        }
        if !is_canonical_node_id(&self.signer_node_id)
            || !is_canonical_node_id(&self.recipient_node_id)
            || self.signer_node_id == self.recipient_node_id
        {
            return Err(FilePathError::Invalid("invalid contact-scoped node IDs"));
        }
        if self.candidates.is_empty() || self.candidates.len() > MAX_FILE_PATH_CANDIDATES {
            return Err(FilePathError::Invalid("invalid candidate count"));
        }
        let mut ids = HashSet::with_capacity(self.candidates.len());
        for candidate in &self.candidates {
            candidate.validate(self.created_at_ms, self.expires_at_ms)?;
            if !ids.insert(candidate.candidate_id) {
                return Err(FilePathError::Invalid("duplicate candidate ID"));
            }
        }
        Ok(())
    }

    fn encode_canonical(&self) -> Result<Vec<u8>, FilePathError> {
        self.validate()?;
        let mut out = Vec::new();
        out.extend_from_slice(&self.record_id);
        out.extend_from_slice(&self.sequence.to_be_bytes());
        out.extend_from_slice(&self.created_at_ms.to_be_bytes());
        out.extend_from_slice(&self.expires_at_ms.to_be_bytes());
        push_node_id(&mut out, &self.signer_node_id)?;
        push_node_id(&mut out, &self.recipient_node_id)?;
        out.push(u8::try_from(self.candidates.len()).map_err(|_| FilePathError::Malformed)?);
        for candidate in &self.candidates {
            candidate.encode_canonical(&mut out);
        }
        Ok(out)
    }
}

impl FilePathCandidateV1 {
    fn validate(&self, created_at_ms: i64, beacon_expires_at_ms: i64) -> Result<(), FilePathError> {
        if self.candidate_id.iter().all(|byte| *byte == 0) {
            return Err(FilePathError::Invalid("candidate ID is all zero"));
        }
        if self.expires_at_ms <= created_at_ms || self.expires_at_ms > beacon_expires_at_ms {
            return Err(FilePathError::Invalid("candidate expiry is outside beacon lifetime"));
        }
        let ip = self.endpoint.ip();
        if self.endpoint.port() == 0 || ip.is_unspecified() || ip.is_multicast() {
            return Err(FilePathError::Invalid("candidate endpoint is not concrete unicast"));
        }
        if matches!(ip, IpAddr::V4(address) if address.is_broadcast()) {
            return Err(FilePathError::Invalid("candidate endpoint is broadcast"));
        }
        Ok(())
    }

    fn encode_canonical(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.candidate_id);
        out.push(self.kind as u8);
        out.push(self.priority);
        out.extend_from_slice(&self.expires_at_ms.to_be_bytes());
        match self.endpoint.ip() {
            IpAddr::V4(address) => {
                out.push(4);
                out.extend_from_slice(&address.octets());
            }
            IpAddr::V6(address) => {
                out.push(6);
                out.extend_from_slice(&address.octets());
            }
        }
        out.extend_from_slice(&self.endpoint.port().to_be_bytes());
    }
}

impl SignedFilePathBeaconV1 {
    pub fn canonical_signing_bytes(&self) -> Result<Vec<u8>, FilePathError> {
        let claims = self.claims.encode_canonical()?;
        let mut out = Vec::with_capacity(
            FILE_PATH_DOMAIN_V1.len() + ED25519_PUBLIC_KEY_SIZE + claims.len(),
        );
        out.extend_from_slice(FILE_PATH_DOMAIN_V1);
        out.extend_from_slice(&self.signer_ed25519_public_key);
        out.extend_from_slice(&claims);
        Ok(out)
    }

    pub fn encode(&self) -> Result<Vec<u8>, FilePathError> {
        self.verify_self_signature()?;
        let claims = self.claims.encode_canonical()?;
        let payload_len = ED25519_PUBLIC_KEY_SIZE
            .checked_add(claims.len())
            .and_then(|size| size.checked_add(SIGNATURE_SIZE))
            .ok_or(FilePathError::Malformed)?;
        let total_len = FILE_PATH_HEADER_BYTES
            .checked_add(payload_len)
            .ok_or(FilePathError::Malformed)?;
        if total_len > MAX_FILE_PATH_BEACON_BYTES {
            return Err(FilePathError::TooLarge {
                size: total_len,
                max: MAX_FILE_PATH_BEACON_BYTES,
            });
        }
        let mut out = Vec::with_capacity(total_len);
        out.extend_from_slice(&FILE_PATH_MAGIC);
        out.push(FILE_PATH_VERSION_V1);
        out.push(0);
        out.extend_from_slice(&FILE_PATH_FLAGS_V1.to_be_bytes());
        out.extend_from_slice(
            &u32::try_from(payload_len)
                .map_err(|_| FilePathError::Malformed)?
                .to_be_bytes(),
        );
        out.extend_from_slice(&self.signer_ed25519_public_key);
        out.extend_from_slice(&claims);
        out.extend_from_slice(&self.signature);
        Ok(out)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, FilePathError> {
        if bytes.len() < FILE_PATH_HEADER_BYTES {
            return Err(FilePathError::TruncatedHeader { actual: bytes.len() });
        }
        if &bytes[..4] != FILE_PATH_MAGIC.as_slice() {
            return Err(FilePathError::InvalidMagic);
        }
        if bytes[4] != FILE_PATH_VERSION_V1 {
            return Err(FilePathError::UnsupportedVersion(bytes[4]));
        }
        if bytes[5] != 0 {
            return Err(FilePathError::Malformed);
        }
        let flags = u16::from_be_bytes(bytes[6..8].try_into().map_err(|_| FilePathError::Malformed)?);
        if flags != FILE_PATH_FLAGS_V1 {
            return Err(FilePathError::UnsupportedFlags(flags));
        }
        let payload_len = usize::try_from(u32::from_be_bytes(
            bytes[8..12].try_into().map_err(|_| FilePathError::Malformed)?,
        ))
        .map_err(|_| FilePathError::Malformed)?;
        if bytes.len() > MAX_FILE_PATH_BEACON_BYTES {
            return Err(FilePathError::TooLarge {
                size: bytes.len(),
                max: MAX_FILE_PATH_BEACON_BYTES,
            });
        }
        if FILE_PATH_HEADER_BYTES.checked_add(payload_len) != Some(bytes.len())
            || payload_len < ED25519_PUBLIC_KEY_SIZE + SIGNATURE_SIZE
        {
            return Err(FilePathError::LengthMismatch);
        }
        let payload = &bytes[FILE_PATH_HEADER_BYTES..];
        let signer_ed25519_public_key = payload[..ED25519_PUBLIC_KEY_SIZE]
            .try_into()
            .map_err(|_| FilePathError::Malformed)?;
        let signature_offset = payload.len() - SIGNATURE_SIZE;
        let signature = payload[signature_offset..]
            .try_into()
            .map_err(|_| FilePathError::Malformed)?;
        let claims = decode_claims(&payload[ED25519_PUBLIC_KEY_SIZE..signature_offset])?;
        let beacon = Self {
            claims,
            signer_ed25519_public_key,
            signature,
        };
        beacon.verify_self_signature()?;
        Ok(beacon)
    }

    pub fn verify_at(
        &self,
        expected_signer: &FilePathPeer,
        expected_recipient_node_id: &str,
        now_ms: i64,
        last_seen_sequence: Option<u64>,
    ) -> Result<(), FilePathError> {
        self.verify_self_signature()?;
        if self.signer_ed25519_public_key != expected_signer.ed25519_public_key
            || self.claims.signer_node_id != expected_signer.node_id
        {
            return Err(FilePathError::UnexpectedSigner);
        }
        if self.claims.recipient_node_id != expected_recipient_node_id {
            return Err(FilePathError::UnexpectedRecipient);
        }
        if now_ms < self.claims.created_at_ms.saturating_sub(FILE_PATH_CLOCK_SKEW_MS)
            || now_ms > self.claims.expires_at_ms
        {
            return Err(FilePathError::NotActive);
        }
        if last_seen_sequence
            .map(|sequence| self.claims.sequence <= sequence)
            .unwrap_or(false)
        {
            return Err(FilePathError::ReplayOrOutOfOrder);
        }
        Ok(())
    }

    fn verify_self_signature(&self) -> Result<(), FilePathError> {
        self.claims.validate()?;
        validate_modern_signer_binding(
            &self.claims.signer_node_id,
            &self.signer_ed25519_public_key,
        )?;
        Ed25519KeyPair::verify(
            &self.signer_ed25519_public_key,
            &self.canonical_signing_bytes()?,
            &self.signature,
        )
        .map_err(|_| FilePathError::InvalidSignature)
    }
}

pub fn sign_file_path_beacon_v1(
    claims: FilePathBeaconClaimsV1,
    identity: &Ed25519KeyPair,
) -> Result<SignedFilePathBeaconV1, FilePathError> {
    sign_file_path_beacon_with_signer_v1(claims, identity)
}

pub(crate) fn sign_file_path_beacon_with_signer_v1<S: FileControlSigner + ?Sized>(
    claims: FilePathBeaconClaimsV1,
    identity: &S,
) -> Result<SignedFilePathBeaconV1, FilePathError> {
    claims.validate()?;
    let signer_ed25519_public_key = identity
        .file_control_public_key()
        .map_err(map_signer_error)?;
    validate_modern_signer_binding(&claims.signer_node_id, &signer_ed25519_public_key)?;
    let unsigned = SignedFilePathBeaconV1 {
        claims,
        signer_ed25519_public_key,
        signature: [0u8; SIGNATURE_SIZE],
    };
    let payload = unsigned.canonical_signing_bytes()?;
    let signature = identity
        .sign_file_control_payload(&payload)
        .map_err(map_signer_error)?;
    let signed = SignedFilePathBeaconV1 {
        signature,
        ..unsigned
    };
    signed.verify_self_signature()?;
    Ok(signed)
}

impl FilePathManager {
    pub fn new(local_node_id: String) -> Result<Self, FilePathError> {
        Self::with_capacity(local_node_id, MAX_FILE_PATH_CONTACTS)
    }

    pub fn with_capacity(local_node_id: String, max_contacts: usize) -> Result<Self, FilePathError> {
        if !is_canonical_node_id(&local_node_id) || max_contacts == 0 || max_contacts > MAX_FILE_PATH_CONTACTS {
            return Err(FilePathError::Invalid("invalid path manager configuration"));
        }
        Ok(Self {
            local_node_id,
            max_contacts,
            peers: HashMap::new(),
        })
    }

    /// Install only after the caller durably persists `(peer key, sequence, expiry, candidates)`.
    /// The host manager itself is intentionally memory-only; engine wiring supplies that boundary.
    pub fn install_verified_beacon(
        &mut self,
        beacon: SignedFilePathBeaconV1,
        peer: &FilePathPeer,
        now_ms: i64,
    ) -> Result<(), FilePathError> {
        let previous = self.peers.get(&peer.ed25519_public_key);
        if previous
            .map(|paths| paths.node_id != peer.node_id)
            .unwrap_or(false)
        {
            return Err(FilePathError::UnexpectedSigner);
        }
        beacon.verify_at(
            peer,
            &self.local_node_id,
            now_ms,
            previous.map(|paths| paths.last_sequence),
        )?;
        if previous.is_none() && self.peers.len() >= self.max_contacts {
            return Err(FilePathError::Capacity { max: self.max_contacts });
        }
        let prior_candidates = previous
            .map(|paths| {
                paths.candidates
                    .iter()
                    .map(|managed| (managed.candidate.candidate_id, managed.clone()))
                    .collect::<HashMap<_, _>>()
            })
            .unwrap_or_default();
        let candidates = beacon
            .claims
            .candidates
            .iter()
            .cloned()
            .map(|candidate| {
                let prior = prior_candidates.get(&candidate.candidate_id);
                let preserve = prior.filter(|managed| managed.candidate == candidate);
                ManagedCandidate {
                    candidate,
                    consecutive_failures: preserve.map(|value| value.consecutive_failures).unwrap_or(0),
                    retry_after_ms: preserve.map(|value| value.retry_after_ms).unwrap_or(0),
                }
            })
            .collect();
        self.peers.insert(
            peer.ed25519_public_key,
            ManagedPeerPaths {
                node_id: peer.node_id.clone(),
                last_sequence: beacon.claims.sequence,
                beacon_expires_at_ms: beacon.claims.expires_at_ms,
                candidates,
            },
        );
        Ok(())
    }

    pub fn select_path(
        &mut self,
        peer: &FilePathPeer,
        now_ms: i64,
        network: FilePathNetworkState,
    ) -> FilePathSelection {
        let remove_peer = self
            .peers
            .get(&peer.ed25519_public_key)
            .map(|paths| paths.node_id != peer.node_id || paths.beacon_expires_at_ms < now_ms)
            .unwrap_or(false);
        if remove_peer {
            self.peers.remove(&peer.ed25519_public_key);
            return FilePathSelection::Unavailable { retry_after_ms: None };
        }
        let Some(paths) = self.peers.get_mut(&peer.ed25519_public_key) else {
            return FilePathSelection::Unavailable { retry_after_ms: None };
        };
        paths.candidates.retain(|managed| managed.candidate.expires_at_ms >= now_ms);
        let selected = paths
            .candidates
            .iter()
            .filter(|managed| {
                managed.retry_after_ms <= now_ms && managed.candidate.kind.available(network)
            })
            .min_by(|left, right| compare_candidates(&left.candidate, &right.candidate));
        if let Some(selected) = selected {
            return FilePathSelection::Selected(selected.candidate.clone());
        }
        let retry_after_ms = paths
            .candidates
            .iter()
            .filter(|managed| managed.candidate.kind.available(network) && managed.retry_after_ms > now_ms)
            .map(|managed| managed.retry_after_ms)
            .min();
        FilePathSelection::Unavailable { retry_after_ms }
    }

    pub fn select_dispatch(
        &mut self,
        peer: &FilePathPeer,
        now_ms: i64,
        network: FilePathNetworkState,
    ) -> Result<FilePathDispatch, FilePathError> {
        let session_peer = FileSessionPeer {
            node_id: peer.node_id.clone(),
            ed25519_public_key: peer.ed25519_public_key,
        };
        match self.select_path(peer, now_ms, network) {
            FilePathSelection::Selected(candidate) => match candidate.kind {
                FilePathKindV1::LanQuic | FilePathKindV1::InternetQuic => {
                    let target = FileSessionTarget::new(session_peer, candidate.endpoint)
                        .map_err(|error| FilePathError::InvalidSessionTarget(error.to_string()))?;
                    Ok(FilePathDispatch::AuthenticatedQuic {
                        candidate_id: candidate.candidate_id,
                        target,
                    })
                }
                FilePathKindV1::DirectTcp => Ok(FilePathDispatch::DirectTcp {
                    candidate_id: candidate.candidate_id,
                    peer: session_peer,
                    endpoint: candidate.endpoint,
                }),
                FilePathKindV1::TcpTunnel => Ok(FilePathDispatch::TcpTunnel {
                    candidate_id: candidate.candidate_id,
                    peer: session_peer,
                    endpoint: candidate.endpoint,
                }),
            },
            FilePathSelection::Unavailable { retry_after_ms } => {
                Ok(FilePathDispatch::Unavailable { retry_after_ms })
            }
        }
    }

    pub fn report_failure(
        &mut self,
        peer: &FilePathPeer,
        candidate_id: &[u8; FILE_PATH_ID_BYTES],
        now_ms: i64,
    ) -> Result<i64, FilePathError> {
        let managed = self.find_candidate_mut(peer, candidate_id)?;
        managed.consecutive_failures = managed.consecutive_failures.saturating_add(1);
        let shift = u32::from(managed.consecutive_failures.saturating_sub(1).min(6));
        let backoff = 1_000i64
            .checked_shl(shift)
            .unwrap_or(MAX_FILE_PATH_FAILURE_BACKOFF_MS)
            .min(MAX_FILE_PATH_FAILURE_BACKOFF_MS);
        managed.retry_after_ms = now_ms.saturating_add(backoff);
        Ok(managed.retry_after_ms)
    }

    pub fn report_success(
        &mut self,
        peer: &FilePathPeer,
        candidate_id: &[u8; FILE_PATH_ID_BYTES],
    ) -> Result<(), FilePathError> {
        let managed = self.find_candidate_mut(peer, candidate_id)?;
        managed.consecutive_failures = 0;
        managed.retry_after_ms = 0;
        Ok(())
    }

    pub fn last_sequence(&self, peer: &FilePathPeer) -> Option<u64> {
        self.peers.get(&peer.ed25519_public_key).map(|paths| paths.last_sequence)
    }

    pub fn cleanup_expired(&mut self, now_ms: i64) -> usize {
        let before = self.peers.len();
        self.peers.retain(|_, paths| paths.beacon_expires_at_ms >= now_ms);
        before - self.peers.len()
    }

    pub fn peer_count(&self) -> usize {
        self.peers.len()
    }

    fn find_candidate_mut(
        &mut self,
        peer: &FilePathPeer,
        candidate_id: &[u8; FILE_PATH_ID_BYTES],
    ) -> Result<&mut ManagedCandidate, FilePathError> {
        self.peers
            .get_mut(&peer.ed25519_public_key)
            .filter(|paths| paths.node_id == peer.node_id)
            .and_then(|paths| {
                paths
                    .candidates
                    .iter_mut()
                    .find(|managed| &managed.candidate.candidate_id == candidate_id)
            })
            .ok_or(FilePathError::UnknownCandidate)
    }
}

impl FilePathStore {
    pub fn open<P: AsRef<Path>>(path: P) -> Result<Self, FilePathError> {
        Self::from_connection(Connection::open(path).map_err(store_error)?)
    }

    pub fn open_in_memory() -> Result<Self, FilePathError> {
        Self::from_connection(Connection::open_in_memory().map_err(store_error)?)
    }

    fn from_connection(connection: Connection) -> Result<Self, FilePathError> {
        connection
            .execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;")
            .map_err(store_error)?;
        connection
            .execute_batch(FILE_PATH_STORE_MIGRATION_V1)
            .map_err(store_error)?;
        Ok(Self {
            connection: Mutex::new(connection),
        })
    }

    pub fn schema_version(&self) -> Result<i64, FilePathError> {
        let connection = self.connection.lock().map_err(|_| store_poisoned())?;
        connection
            .query_row(
                "SELECT version FROM file_path_schema_version ORDER BY version DESC LIMIT 1",
                [],
                |row| row.get(0),
            )
            .map_err(store_error)
    }

    /// Verify against the durable sequence, stage the in-memory update, commit the row, and only
    /// then publish the staged manager. This is the production replay-admission boundary.
    pub fn admit_and_install(
        &self,
        manager: &mut FilePathManager,
        beacon: SignedFilePathBeaconV1,
        peer: &FilePathPeer,
        now_ms: i64,
    ) -> Result<(), FilePathError> {
        let mut connection = self.connection.lock().map_err(|_| store_poisoned())?;
        let transaction = connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(store_error)?;
        let last_sequence_blob = transaction
            .query_row(
                "SELECT sequence_be FROM file_path_beacons WHERE peer_key = ?1",
                params![peer.ed25519_public_key.as_slice()],
                |row| row.get::<_, Vec<u8>>(0),
            )
            .optional()
            .map_err(store_error)?;
        let last_sequence = last_sequence_blob
            .as_deref()
            .map(decode_sequence)
            .transpose()?;
        beacon.verify_at(peer, &manager.local_node_id, now_ms, last_sequence)?;
        let mut staged = manager.clone();
        staged.install_verified_beacon(beacon.clone(), peer, now_ms)?;
        let wire = beacon.encode()?;
        transaction
            .execute(
                "INSERT OR REPLACE INTO file_path_beacons
                 (peer_key, peer_node_id, sequence_be, expires_at_ms, beacon)
                 VALUES (?1, ?2, ?3, ?4, ?5)",
                params![
                    peer.ed25519_public_key.as_slice(),
                    peer.node_id,
                    beacon.claims.sequence.to_be_bytes().as_slice(),
                    beacon.claims.expires_at_ms,
                    wire,
                ],
            )
            .map_err(store_error)?;
        transaction.commit().map_err(store_error)?;
        *manager = staged;
        Ok(())
    }

    /// Rebuild an empty/stale manager from bounded, unexpired signed rows after process restart.
    /// Any corrupt row fails the whole load instead of silently authorizing a partial path table.
    pub fn load_into(
        &self,
        manager: &mut FilePathManager,
        now_ms: i64,
    ) -> Result<usize, FilePathError> {
        let connection = self.connection.lock().map_err(|_| store_poisoned())?;
        let mut statement = connection
            .prepare(
                "SELECT peer_key, peer_node_id, beacon FROM file_path_beacons
                 WHERE expires_at_ms >= ?1
                 ORDER BY expires_at_ms ASC, peer_key ASC
                 LIMIT ?2",
            )
            .map_err(store_error)?;
        let rows = statement
            .query_map(params![now_ms, manager.max_contacts as i64], |row| {
                Ok((
                    row.get::<_, Vec<u8>>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, Vec<u8>>(2)?,
                ))
            })
            .map_err(store_error)?;
        let mut records = Vec::new();
        for row in rows {
            records.push(row.map_err(store_error)?);
        }
        drop(statement);
        drop(connection);

        let mut staged = FilePathManager::with_capacity(
            manager.local_node_id.clone(),
            manager.max_contacts,
        )?;
        for (key, node_id, wire) in records {
            let ed25519_public_key: [u8; ED25519_PUBLIC_KEY_SIZE] = key
                .try_into()
                .map_err(|_| FilePathError::Store("invalid persisted peer key length".into()))?;
            let peer = FilePathPeer {
                node_id,
                ed25519_public_key,
            };
            let beacon = SignedFilePathBeaconV1::decode(&wire)?;
            staged.install_verified_beacon(beacon, &peer, now_ms)?;
        }
        let count = staged.peer_count();
        *manager = staged;
        Ok(count)
    }

    pub fn purge_expired(&self, now_ms: i64) -> Result<usize, FilePathError> {
        let connection = self.connection.lock().map_err(|_| store_poisoned())?;
        connection
            .execute(
                "DELETE FROM file_path_beacons WHERE expires_at_ms < ?1",
                params![now_ms],
            )
            .map_err(store_error)
    }

    pub fn count(&self) -> Result<usize, FilePathError> {
        let connection = self.connection.lock().map_err(|_| store_poisoned())?;
        let count: i64 = connection
            .query_row("SELECT COUNT(*) FROM file_path_beacons", [], |row| row.get(0))
            .map_err(store_error)?;
        usize::try_from(count).map_err(|_| FilePathError::Store("negative row count".into()))
    }
}

fn compare_candidates(left: &FilePathCandidateV1, right: &FilePathCandidateV1) -> Ordering {
    left.kind
        .rank()
        .cmp(&right.kind.rank())
        .then_with(|| right.priority.cmp(&left.priority))
        .then_with(|| left.candidate_id.cmp(&right.candidate_id))
}

fn decode_claims(bytes: &[u8]) -> Result<FilePathBeaconClaimsV1, FilePathError> {
    let mut cursor = Cursor::new(bytes);
    let record_id = cursor.take_array::<FILE_PATH_ID_BYTES>()?;
    let sequence = cursor.take_u64()?;
    let created_at_ms = cursor.take_i64()?;
    let expires_at_ms = cursor.take_i64()?;
    let signer_node_id = cursor.take_node_id()?;
    let recipient_node_id = cursor.take_node_id()?;
    let count = usize::from(cursor.take_u8()?);
    if count == 0 || count > MAX_FILE_PATH_CANDIDATES {
        return Err(FilePathError::Invalid("invalid candidate count"));
    }
    let mut candidates = Vec::with_capacity(count);
    for _ in 0..count {
        let candidate_id = cursor.take_array::<FILE_PATH_ID_BYTES>()?;
        let kind = FilePathKindV1::from_u8(cursor.take_u8()?)?;
        let priority = cursor.take_u8()?;
        let candidate_expires_at_ms = cursor.take_i64()?;
        let family = cursor.take_u8()?;
        let ip = match family {
            4 => IpAddr::V4(cursor.take_array::<4>()?.into()),
            6 => IpAddr::V6(cursor.take_array::<16>()?.into()),
            _ => return Err(FilePathError::Invalid("unknown endpoint address family")),
        };
        let port = cursor.take_u16()?;
        candidates.push(FilePathCandidateV1 {
            candidate_id,
            kind,
            priority,
            endpoint: SocketAddr::new(ip, port),
            expires_at_ms: candidate_expires_at_ms,
        });
    }
    if !cursor.is_done() {
        return Err(FilePathError::LengthMismatch);
    }
    let claims = FilePathBeaconClaimsV1 {
        record_id,
        sequence,
        created_at_ms,
        expires_at_ms,
        signer_node_id,
        recipient_node_id,
        candidates,
    };
    claims.validate()?;
    Ok(claims)
}

struct Cursor<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Cursor<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }

    fn take(&mut self, len: usize) -> Result<&'a [u8], FilePathError> {
        let end = self.offset.checked_add(len).ok_or(FilePathError::Malformed)?;
        let value = self.bytes.get(self.offset..end).ok_or(FilePathError::Malformed)?;
        self.offset = end;
        Ok(value)
    }

    fn take_array<const N: usize>(&mut self) -> Result<[u8; N], FilePathError> {
        self.take(N)?.try_into().map_err(|_| FilePathError::Malformed)
    }

    fn take_u8(&mut self) -> Result<u8, FilePathError> {
        Ok(self.take(1)?[0])
    }

    fn take_u16(&mut self) -> Result<u16, FilePathError> {
        Ok(u16::from_be_bytes(self.take_array()?))
    }

    fn take_u64(&mut self) -> Result<u64, FilePathError> {
        Ok(u64::from_be_bytes(self.take_array()?))
    }

    fn take_i64(&mut self) -> Result<i64, FilePathError> {
        Ok(i64::from_be_bytes(self.take_array()?))
    }

    fn take_node_id(&mut self) -> Result<String, FilePathError> {
        let len = usize::from(self.take_u8()?);
        if len < MIN_NODE_ID_BYTES || len > MAX_NODE_ID_BYTES {
            return Err(FilePathError::Invalid("invalid node ID length"));
        }
        let value = std::str::from_utf8(self.take(len)?)
            .map_err(|_| FilePathError::Invalid("node ID is not UTF-8"))?
            .to_owned();
        if !is_canonical_node_id(&value) {
            return Err(FilePathError::Invalid("node ID is not canonical"));
        }
        Ok(value)
    }

    fn is_done(&self) -> bool {
        self.offset == self.bytes.len()
    }
}

fn push_node_id(out: &mut Vec<u8>, node_id: &str) -> Result<(), FilePathError> {
    if !is_canonical_node_id(node_id) {
        return Err(FilePathError::Invalid("node ID is not canonical"));
    }
    out.push(u8::try_from(node_id.len()).map_err(|_| FilePathError::Malformed)?);
    out.extend_from_slice(node_id.as_bytes());
    Ok(())
}

fn is_canonical_node_id(node_id: &str) -> bool {
    (node_id.len() == MIN_NODE_ID_BYTES || node_id.len() == MAX_NODE_ID_BYTES)
        && node_id.starts_with("pk_")
        && node_id[3..]
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn validate_modern_signer_binding(
    node_id: &str,
    public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
) -> Result<(), FilePathError> {
    if node_id.len() == MAX_NODE_ID_BYTES {
        let expected = format!("pk_{}", NodeId::from_ed25519_pubkey(public_key).to_hex());
        if node_id != expected {
            return Err(FilePathError::UnexpectedSigner);
        }
    }
    Ok(())
}

fn decode_sequence(bytes: &[u8]) -> Result<u64, FilePathError> {
    let sequence: [u8; 8] = bytes
        .try_into()
        .map_err(|_| FilePathError::Store("invalid persisted sequence length".into()))?;
    Ok(u64::from_be_bytes(sequence))
}

fn store_error(error: rusqlite::Error) -> FilePathError {
    FilePathError::Store(error.to_string())
}

fn store_poisoned() -> FilePathError {
    FilePathError::Store("SQLite mutex poisoned".into())
}

fn map_signer_error(error: FileControlError) -> FilePathError {
    FilePathError::Signer(error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::signing_identity::InstalledSigningIdentity;
    use std::sync::Arc;

    const NOW: i64 = 1_900_000_000_000;

    fn identity(secret: u8) -> Ed25519KeyPair {
        Ed25519KeyPair::from_secret_bytes(&[secret; 32]).unwrap()
    }

    fn modern_node(identity: &Ed25519KeyPair) -> String {
        format!("pk_{}", identity.node_id().to_hex())
    }

    fn peer(identity: &Ed25519KeyPair) -> FilePathPeer {
        FilePathPeer {
            node_id: modern_node(identity),
            ed25519_public_key: identity.public_key().0.try_into().unwrap(),
        }
    }

    fn candidate(id: u8, kind: FilePathKindV1, port: u16) -> FilePathCandidateV1 {
        FilePathCandidateV1 {
            candidate_id: [id; FILE_PATH_ID_BYTES],
            kind,
            priority: id,
            endpoint: format!("10.0.0.{id}:{port}").parse().unwrap(),
            expires_at_ms: NOW + FILE_PATH_BEACON_LIFETIME_MS,
        }
    }

    fn claims(
        sender: &Ed25519KeyPair,
        recipient: &Ed25519KeyPair,
        sequence: u64,
        candidates: Vec<FilePathCandidateV1>,
    ) -> FilePathBeaconClaimsV1 {
        FilePathBeaconClaimsV1 {
            record_id: [sequence as u8; FILE_PATH_ID_BYTES],
            sequence,
            created_at_ms: NOW,
            expires_at_ms: NOW + FILE_PATH_BEACON_LIFETIME_MS,
            signer_node_id: modern_node(sender),
            recipient_node_id: modern_node(recipient),
            candidates,
        }
    }

    #[test]
    fn signed_contact_beacon_round_trips_and_selects_lan_first() {
        let sender = identity(1);
        let recipient = identity(2);
        let signed = sign_file_path_beacon_v1(
            claims(
                &sender,
                &recipient,
                1,
                vec![
                    candidate(2, FilePathKindV1::InternetQuic, 2002),
                    candidate(1, FilePathKindV1::LanQuic, 2001),
                    candidate(3, FilePathKindV1::DirectTcp, 2003),
                ],
            ),
            &sender,
        )
        .unwrap();
        let decoded = SignedFilePathBeaconV1::decode(&signed.encode().unwrap()).unwrap();
        assert_eq!(decoded, signed);
        let mut manager = FilePathManager::new(modern_node(&recipient)).unwrap();
        manager.install_verified_beacon(decoded, &peer(&sender), NOW).unwrap();
        assert!(matches!(
            manager.select_path(&peer(&sender), NOW, FilePathNetworkState::default()),
            FilePathSelection::Selected(FilePathCandidateV1 {
                kind: FilePathKindV1::LanQuic,
                ..
            })
        ));
        assert_eq!(manager.last_sequence(&peer(&sender)), Some(1));
    }

    #[test]
    fn tamper_wrong_contact_replay_and_expiry_fail_closed() {
        let sender = identity(3);
        let recipient = identity(4);
        let wrong = identity(5);
        let signed = sign_file_path_beacon_v1(
            claims(&sender, &recipient, 1, vec![candidate(1, FilePathKindV1::LanQuic, 3001)]),
            &sender,
        )
        .unwrap();
        let mut bytes = signed.encode().unwrap();
        *bytes.last_mut().unwrap() ^= 1;
        assert_eq!(SignedFilePathBeaconV1::decode(&bytes), Err(FilePathError::InvalidSignature));
        assert_eq!(
            signed.verify_at(&peer(&wrong), &modern_node(&recipient), NOW, None),
            Err(FilePathError::UnexpectedSigner)
        );
        assert_eq!(
            signed.verify_at(&peer(&sender), &modern_node(&wrong), NOW, None),
            Err(FilePathError::UnexpectedRecipient)
        );
        assert_eq!(
            signed.verify_at(&peer(&sender), &modern_node(&recipient), NOW, Some(1)),
            Err(FilePathError::ReplayOrOutOfOrder)
        );
        assert_eq!(
            signed.verify_at(
                &peer(&sender),
                &modern_node(&recipient),
                NOW + FILE_PATH_BEACON_LIFETIME_MS + 1,
                None,
            ),
            Err(FilePathError::NotActive)
        );
    }

    #[test]
    fn udp_blocked_selects_tcp_then_tunnel_and_reports_honest_unavailable() {
        let sender = identity(6);
        let recipient = identity(7);
        let sender_peer = peer(&sender);
        let signed = sign_file_path_beacon_v1(
            claims(
                &sender,
                &recipient,
                1,
                vec![
                    candidate(1, FilePathKindV1::InternetQuic, 4001),
                    candidate(2, FilePathKindV1::DirectTcp, 4002),
                    candidate(3, FilePathKindV1::TcpTunnel, 4003),
                ],
            ),
            &sender,
        )
        .unwrap();
        let mut manager = FilePathManager::new(modern_node(&recipient)).unwrap();
        manager.install_verified_beacon(signed, &sender_peer, NOW).unwrap();
        let udp_blocked = FilePathNetworkState {
            lan_available: false,
            udp_available: false,
            tcp_available: true,
            tunnel_available: true,
        };
        let direct_tcp = match manager.select_path(&sender_peer, NOW, udp_blocked) {
            FilePathSelection::Selected(candidate) => candidate,
            other => panic!("unexpected selection: {other:?}"),
        };
        assert_eq!(direct_tcp.kind, FilePathKindV1::DirectTcp);
        let retry_at = manager
            .report_failure(&sender_peer, &direct_tcp.candidate_id, NOW)
            .unwrap();
        assert!(retry_at > NOW);
        assert!(matches!(
            manager.select_path(&sender_peer, NOW, udp_blocked),
            FilePathSelection::Selected(FilePathCandidateV1 {
                kind: FilePathKindV1::TcpTunnel,
                ..
            })
        ));
        assert!(matches!(
            manager.select_path(
                &sender_peer,
                NOW,
                FilePathNetworkState {
                    tunnel_available: false,
                    tcp_available: false,
                    ..udp_blocked
                },
            ),
            FilePathSelection::Unavailable { retry_after_ms: None }
        ));
    }

    #[test]
    fn typed_dispatch_never_feeds_tcp_or_tunnel_into_quic_owner() {
        let sender = identity(17);
        let recipient = identity(18);
        let sender_peer = peer(&sender);
        let signed = sign_file_path_beacon_v1(
            claims(
                &sender,
                &recipient,
                1,
                vec![
                    candidate(1, FilePathKindV1::InternetQuic, 4201),
                    candidate(2, FilePathKindV1::DirectTcp, 4202),
                    candidate(3, FilePathKindV1::TcpTunnel, 4203),
                ],
            ),
            &sender,
        )
        .unwrap();
        let mut manager = FilePathManager::new(modern_node(&recipient)).unwrap();
        manager.install_verified_beacon(signed, &sender_peer, NOW).unwrap();

        let quic = manager
            .select_dispatch(&sender_peer, NOW, FilePathNetworkState::default())
            .unwrap();
        let target = quic.authenticated_quic_target().unwrap();
        assert_eq!(target.peer().ed25519_public_key, sender_peer.ed25519_public_key);
        assert_eq!(target.remote_address().port(), 4201);

        let udp_blocked = FilePathNetworkState {
            lan_available: false,
            udp_available: false,
            tcp_available: true,
            tunnel_available: true,
        };
        let tcp = manager.select_dispatch(&sender_peer, NOW, udp_blocked).unwrap();
        assert!(matches!(&tcp, FilePathDispatch::DirectTcp { endpoint, .. } if endpoint.port() == 4202));
        assert!(tcp.authenticated_quic_target().is_none());
        manager.report_failure(&sender_peer, &[2; 16], NOW).unwrap();
        let tunnel = manager.select_dispatch(&sender_peer, NOW, udp_blocked).unwrap();
        assert!(matches!(&tunnel, FilePathDispatch::TcpTunnel { endpoint, .. } if endpoint.port() == 4203));
        assert!(tunnel.authenticated_quic_target().is_none());
    }

    #[test]
    fn cooldown_expiry_cleanup_and_capacity_are_bounded() {
        let sender = identity(8);
        let recipient = identity(9);
        let sender_peer = peer(&sender);
        let signed = sign_file_path_beacon_v1(
            claims(&sender, &recipient, 1, vec![candidate(1, FilePathKindV1::InternetQuic, 5001)]),
            &sender,
        )
        .unwrap();
        let mut manager = FilePathManager::with_capacity(modern_node(&recipient), 1).unwrap();
        manager.install_verified_beacon(signed.clone(), &sender_peer, NOW).unwrap();
        let retry_at = manager.report_failure(&sender_peer, &[1; 16], NOW).unwrap();
        assert_eq!(
            manager.select_path(&sender_peer, NOW, FilePathNetworkState::default()),
            FilePathSelection::Unavailable { retry_after_ms: Some(retry_at) }
        );
        manager.report_success(&sender_peer, &[1; 16]).unwrap();
        assert!(matches!(
            manager.select_path(&sender_peer, NOW, FilePathNetworkState::default()),
            FilePathSelection::Selected(_)
        ));
        let second = identity(10);
        let second_record = sign_file_path_beacon_v1(
            claims(&second, &recipient, 1, vec![candidate(2, FilePathKindV1::InternetQuic, 5002)]),
            &second,
        )
        .unwrap();
        assert_eq!(
            manager.install_verified_beacon(second_record, &peer(&second), NOW),
            Err(FilePathError::Capacity { max: 1 })
        );
        assert_eq!(manager.cleanup_expired(NOW + FILE_PATH_BEACON_LIFETIME_MS + 1), 1);
        assert_eq!(manager.peer_count(), 0);
    }

    #[test]
    fn candidate_bounds_duplicates_and_noncanonical_endpoints_are_rejected() {
        let sender = identity(11);
        let recipient = identity(12);
        let mut duplicate = candidate(1, FilePathKindV1::LanQuic, 6001);
        duplicate.kind = FilePathKindV1::DirectTcp;
        assert!(matches!(
            sign_file_path_beacon_v1(
                claims(
                    &sender,
                    &recipient,
                    1,
                    vec![candidate(1, FilePathKindV1::LanQuic, 6001), duplicate],
                ),
                &sender,
            ),
            Err(FilePathError::Invalid("duplicate candidate ID"))
        ));
        let mut invalid = candidate(2, FilePathKindV1::LanQuic, 6002);
        invalid.endpoint = "0.0.0.0:6002".parse().unwrap();
        assert!(sign_file_path_beacon_v1(
            claims(&sender, &recipient, 1, vec![invalid]),
            &sender,
        )
        .is_err());
        let too_many = (1..=MAX_FILE_PATH_CANDIDATES + 1)
            .map(|index| candidate(index as u8, FilePathKindV1::InternetQuic, 6100 + index as u16))
            .collect();
        assert!(sign_file_path_beacon_v1(claims(&sender, &recipient, 1, too_many), &sender).is_err());
    }

    #[test]
    fn durable_store_admits_before_visibility_and_restores_after_restart() {
        let sender = identity(19);
        let recipient = identity(20);
        let sender_peer = peer(&sender);
        let local_node = modern_node(&recipient);
        let first = sign_file_path_beacon_v1(
            claims(
                &sender,
                &recipient,
                1,
                vec![candidate(1, FilePathKindV1::InternetQuic, 7201)],
            ),
            &sender,
        )
        .unwrap();
        let store = FilePathStore::open_in_memory().unwrap();
        assert_eq!(store.schema_version().unwrap(), 1);
        let mut manager = FilePathManager::new(local_node.clone()).unwrap();
        store
            .admit_and_install(&mut manager, first.clone(), &sender_peer, NOW)
            .unwrap();
        assert_eq!(store.count().unwrap(), 1);
        assert_eq!(manager.last_sequence(&sender_peer), Some(1));
        assert_eq!(
            store.admit_and_install(&mut manager, first, &sender_peer, NOW),
            Err(FilePathError::ReplayOrOutOfOrder)
        );

        let mut restarted = FilePathManager::new(local_node).unwrap();
        assert_eq!(store.load_into(&mut restarted, NOW).unwrap(), 1);
        assert_eq!(restarted.last_sequence(&sender_peer), Some(1));
        assert!(matches!(
            restarted.select_dispatch(&sender_peer, NOW, FilePathNetworkState::default()).unwrap(),
            FilePathDispatch::AuthenticatedQuic { .. }
        ));
        assert_eq!(
            store.purge_expired(NOW + FILE_PATH_BEACON_LIFETIME_MS + 1).unwrap(),
            1
        );
        assert_eq!(store.load_into(&mut restarted, NOW + FILE_PATH_BEACON_LIFETIME_MS + 1).unwrap(), 0);
        assert_eq!(restarted.peer_count(), 0);
    }

    #[test]
    fn every_truncation_trailing_byte_and_oversize_declaration_fail_closed() {
        let sender = identity(15);
        let recipient = identity(16);
        let wire = sign_file_path_beacon_v1(
            claims(
                &sender,
                &recipient,
                1,
                vec![candidate(1, FilePathKindV1::InternetQuic, 7101)],
            ),
            &sender,
        )
        .unwrap()
        .encode()
        .unwrap();
        for length in 0..wire.len() {
            assert!(SignedFilePathBeaconV1::decode(&wire[..length]).is_err(), "length {length}");
        }
        let mut trailing = wire.clone();
        trailing.push(0);
        assert_eq!(
            SignedFilePathBeaconV1::decode(&trailing),
            Err(FilePathError::LengthMismatch)
        );
        let mut declared_oversize = wire;
        declared_oversize[8..12].copy_from_slice(&u32::MAX.to_be_bytes());
        assert_eq!(
            SignedFilePathBeaconV1::decode(&declared_oversize),
            Err(FilePathError::LengthMismatch)
        );
    }

    #[test]
    fn installed_identity_signer_never_exports_seed_and_is_accepted_for_legacy_contact() {
        let installed = Arc::new(
            InstalledSigningIdentity::from_seed(
                1,
                format!("pk_{}", "ab".repeat(16)),
                &[13; 32],
            )
            .unwrap(),
        );
        let recipient = identity(14);
        let claims = FilePathBeaconClaimsV1 {
            record_id: [1; 16],
            sequence: 1,
            created_at_ms: NOW,
            expires_at_ms: NOW + FILE_PATH_BEACON_LIFETIME_MS,
            signer_node_id: installed.legacy_routing_node_id().to_owned(),
            recipient_node_id: modern_node(&recipient),
            candidates: vec![candidate(1, FilePathKindV1::InternetQuic, 7001)],
        };
        let signed = sign_file_path_beacon_with_signer_v1(claims, &installed).unwrap();
        let installed_peer = FilePathPeer {
            node_id: installed.legacy_routing_node_id().to_owned(),
            ed25519_public_key: installed.public_key().try_into().unwrap(),
        };
        signed
            .verify_at(&installed_peer, &modern_node(&recipient), NOW, None)
            .unwrap();
    }
}
