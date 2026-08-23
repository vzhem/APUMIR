//! Canonical, signed and replay-aware file control records (F4-B2).
//!
//! This module is deliberately isolated from sockets, transfer state, Android and FFI. It defines
//! the small control plane that a later transport may carry, while bulk ciphertext remains in
//! `file_wire` chunk frames. Every record is canonical big-endian bytes, domain-separated Ed25519
//! signed, hard-bounded before allocation, time-bounded and tied to an externally pinned peer key.
//!
//! A successful signature is not by itself authorization: callers must use `verify_at` with the
//! expected signer/recipient and the already authenticated signer public key. Replay state is also
//! external and durable; `last_seen_sequence` must be looked up by `(signer key, scope_id)` and only
//! advanced after the complete operation is accepted durably.

use crate::crypto::keys::{
    Ed25519KeyPair, NodeId, ED25519_PUBLIC_KEY_SIZE, SIGNATURE_SIZE,
};
use crate::network::file_wire::{FileCapabilitiesV1, MAX_FILE_CHUNK_COUNT};
use sha2::{Digest, Sha256};

pub const FILE_CONTROL_MAGIC: [u8; 4] = *b"APUC";
pub const FILE_CONTROL_VERSION_V1: u8 = 1;
pub const FILE_CONTROL_HEADER_BYTES: usize = 12;
pub const FILE_CONTROL_ID_BYTES: usize = 16;
pub const FILE_CONTROL_HASH_BYTES: usize = 32;
pub const MAX_FILE_CONTROL_PAYLOAD_BYTES: usize = 64 * 1024;
pub const MAX_FILE_CONTROL_BYTES: usize =
    FILE_CONTROL_HEADER_BYTES + MAX_FILE_CONTROL_PAYLOAD_BYTES;
pub const MAX_ENCRYPTED_FILE_OFFER_BYTES: usize = 32 * 1024;
pub const MAX_FILE_CONTROL_RANGES: usize = 1024;
pub const MAX_FILE_CONTROL_CLOCK_SKEW_MS: i64 = 5 * 60 * 1_000;
pub const MAX_FILE_CAPABILITY_LIFETIME_MS: i64 = 10 * 60 * 1_000;
pub const MAX_FILE_MISSING_REQUEST_LIFETIME_MS: i64 = 7 * 24 * 60 * 60 * 1_000;
pub const MAX_FILE_TRANSFER_CONTROL_LIFETIME_MS: i64 = 30 * 24 * 60 * 60 * 1_000;

const FILE_CONTROL_DOMAIN_V1: &[u8] = b"apu-file-control-v1\0";
const FILE_CONTROL_FLAGS_V1: u16 = 0;
const MIN_ENCRYPTED_FILE_OFFER_BYTES: usize = 16;
const MAX_NODE_ID_BYTES: usize = 67;
const MIN_NODE_ID_BYTES: usize = 35;
const CAPABILITIES_BODY_BYTES: usize = 16;
const FINAL_RECEIPT_BODY_BYTES: usize = FILE_CONTROL_HASH_BYTES * 2 + 8 + 8;
const MIN_SIGNED_PAYLOAD_BYTES: usize = ED25519_PUBLIC_KEY_SIZE + SIGNATURE_SIZE;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum FileControlTypeV1 {
    Capabilities = 1,
    Offer = 2,
    MissingRanges = 3,
    Custody = 4,
    FinalReceipt = 5,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum FileCustodyActionV1 {
    Offer = 1,
    Accept = 2,
    StoredReceipt = 3,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileChunkRangeV1 {
    pub start_chunk: u64,
    pub end_chunk_exclusive: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileChunkRangePageV1 {
    pub batch_id: [u8; FILE_CONTROL_ID_BYTES],
    pub page_index: u32,
    pub is_last_page: bool,
    pub total_chunk_count: u64,
    pub ranges: Vec<FileChunkRangeV1>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncryptedFileOfferV1 {
    /// SHA-256 of `encrypted_offer`; useful for idempotence without exposing filename or size.
    pub offer_digest: [u8; FILE_CONTROL_HASH_BYTES],
    /// Opaque E2E ciphertext. This codec signs and bounds it; encryption is done by the file crypto
    /// boundary before this value is constructed.
    pub encrypted_offer: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileCustodyControlV1 {
    pub action: FileCustodyActionV1,
    pub custodian_node_id: String,
    pub lease_expires_at_ms: i64,
    pub chunks: FileChunkRangePageV1,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileFinalReceiptV1 {
    pub manifest_commitment: [u8; FILE_CONTROL_HASH_BYTES],
    pub whole_file_sha256: [u8; FILE_CONTROL_HASH_BYTES],
    pub verified_file_size: u64,
    pub verified_chunk_count: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FileControlBodyV1 {
    Capabilities(FileCapabilitiesV1),
    Offer(EncryptedFileOfferV1),
    MissingRanges(FileChunkRangePageV1),
    Custody(FileCustodyControlV1),
    FinalReceipt(FileFinalReceiptV1),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileControlClaimsV1 {
    /// Unique nonce/dedup identity for this exact record.
    pub record_id: [u8; FILE_CONTROL_ID_BYTES],
    /// Negotiation session ID for capabilities, transfer ID for all transfer-scoped records.
    pub scope_id: [u8; FILE_CONTROL_ID_BYTES],
    /// Strictly increasing within `(signer public key, scope_id)`.
    pub sequence: u64,
    pub created_at_ms: i64,
    pub expires_at_ms: i64,
    pub signer_node_id: String,
    pub recipient_node_id: String,
    pub body: FileControlBodyV1,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SignedFileControlV1 {
    pub claims: FileControlClaimsV1,
    pub signer_ed25519_public_key: [u8; ED25519_PUBLIC_KEY_SIZE],
    pub signature: [u8; SIGNATURE_SIZE],
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum FileControlError {
    #[error("file control header is truncated: {actual} bytes")]
    TruncatedHeader { actual: usize },
    #[error("invalid file control magic")]
    InvalidMagic,
    #[error("unsupported file control version: {got}")]
    UnsupportedVersion { got: u8 },
    #[error("unsupported file control type: {got}")]
    UnsupportedType { got: u8 },
    #[error("unsupported mandatory file control flags: 0x{got:04x}")]
    UnsupportedFlags { got: u16 },
    #[error("file control payload is too large: {size} bytes (max {max})")]
    PayloadTooLarge { size: usize, max: usize },
    #[error("file control length arithmetic overflow")]
    LengthOverflow,
    #[error("file control length mismatch: declared {declared} bytes, actual {actual} bytes")]
    LengthMismatch { declared: usize, actual: usize },
    #[error("malformed file control payload")]
    MalformedPayload,
    #[error("invalid file control claims: {reason}")]
    InvalidClaims { reason: &'static str },
    #[error("invalid file control body: {reason}")]
    InvalidBody { reason: &'static str },
    #[error("file control signature is invalid")]
    InvalidSignature,
    #[error("file control signer key is not bound to the claimed identity")]
    InvalidSignerBinding,
    #[error("file control signer does not match the authenticated peer")]
    UnexpectedSigner,
    #[error("file control recipient does not match the local peer")]
    UnexpectedRecipient,
    #[error("file control scope does not match the expected session or transfer")]
    UnexpectedScope,
    #[error("file control record is not active at the supplied time")]
    NotActive,
    #[error("file control sequence is replayed or out of order")]
    ReplayOrOutOfOrder,
}

impl FileControlTypeV1 {
    fn from_u8(value: u8) -> Result<Self, FileControlError> {
        match value {
            1 => Ok(Self::Capabilities),
            2 => Ok(Self::Offer),
            3 => Ok(Self::MissingRanges),
            4 => Ok(Self::Custody),
            5 => Ok(Self::FinalReceipt),
            got => Err(FileControlError::UnsupportedType { got }),
        }
    }
}

impl FileCustodyActionV1 {
    fn from_u8(value: u8) -> Result<Self, FileControlError> {
        match value {
            1 => Ok(Self::Offer),
            2 => Ok(Self::Accept),
            3 => Ok(Self::StoredReceipt),
            _ => Err(FileControlError::InvalidBody {
                reason: "unknown custody action",
            }),
        }
    }
}

impl FileControlBodyV1 {
    pub fn control_type(&self) -> FileControlTypeV1 {
        match self {
            Self::Capabilities(_) => FileControlTypeV1::Capabilities,
            Self::Offer(_) => FileControlTypeV1::Offer,
            Self::MissingRanges(_) => FileControlTypeV1::MissingRanges,
            Self::Custody(_) => FileControlTypeV1::Custody,
            Self::FinalReceipt(_) => FileControlTypeV1::FinalReceipt,
        }
    }

    fn max_lifetime_ms(&self) -> i64 {
        match self {
            Self::Capabilities(_) => MAX_FILE_CAPABILITY_LIFETIME_MS,
            Self::MissingRanges(_) => MAX_FILE_MISSING_REQUEST_LIFETIME_MS,
            Self::Offer(_) | Self::Custody(_) | Self::FinalReceipt(_) => {
                MAX_FILE_TRANSFER_CONTROL_LIFETIME_MS
            }
        }
    }
}

impl FileControlClaimsV1 {
    pub fn validate(&self) -> Result<(), FileControlError> {
        if self.record_id.iter().all(|byte| *byte == 0) {
            return Err(FileControlError::InvalidClaims {
                reason: "record ID is all zero",
            });
        }
        if self.scope_id.iter().all(|byte| *byte == 0) {
            return Err(FileControlError::InvalidClaims {
                reason: "scope ID is all zero",
            });
        }
        if self.sequence == 0 {
            return Err(FileControlError::InvalidClaims {
                reason: "sequence must be positive",
            });
        }
        if !is_canonical_node_id(&self.signer_node_id)
            || !is_canonical_node_id(&self.recipient_node_id)
            || self.signer_node_id == self.recipient_node_id
        {
            return Err(FileControlError::InvalidClaims {
                reason: "signer and recipient must be distinct canonical node IDs",
            });
        }
        let lifetime = self.expires_at_ms.saturating_sub(self.created_at_ms);
        if self.created_at_ms < 0
            || lifetime <= 0
            || lifetime > self.body.max_lifetime_ms()
        {
            return Err(FileControlError::InvalidClaims {
                reason: "invalid control record time window",
            });
        }
        validate_body(self)
    }

    fn canonical_claim_bytes(&self) -> Result<Vec<u8>, FileControlError> {
        self.validate()?;
        let mut out = Vec::new();
        out.extend_from_slice(&self.record_id);
        out.extend_from_slice(&self.scope_id);
        out.extend_from_slice(&self.sequence.to_be_bytes());
        out.extend_from_slice(&self.created_at_ms.to_be_bytes());
        out.extend_from_slice(&self.expires_at_ms.to_be_bytes());
        push_node_id(&mut out, &self.signer_node_id)?;
        push_node_id(&mut out, &self.recipient_node_id)?;
        encode_body(&mut out, &self.body)?;
        Ok(out)
    }
}

impl SignedFileControlV1 {
    /// Exact domain-separated bytes covered by Ed25519. The signature itself is excluded.
    pub fn canonical_signing_bytes(&self) -> Result<Vec<u8>, FileControlError> {
        let claims = self.claims.canonical_claim_bytes()?;
        canonical_signing_bytes(
            self.claims.body.control_type(),
            &self.signer_ed25519_public_key,
            &claims,
        )
    }

    pub fn encode(&self) -> Result<Vec<u8>, FileControlError> {
        self.verify_self_signature()?;
        let claims = self.claims.canonical_claim_bytes()?;
        let payload_len = ED25519_PUBLIC_KEY_SIZE
            .checked_add(claims.len())
            .and_then(|size| size.checked_add(SIGNATURE_SIZE))
            .ok_or(FileControlError::LengthOverflow)?;
        if payload_len > MAX_FILE_CONTROL_PAYLOAD_BYTES {
            return Err(FileControlError::PayloadTooLarge {
                size: payload_len,
                max: MAX_FILE_CONTROL_PAYLOAD_BYTES,
            });
        }
        let payload_len_u32 =
            u32::try_from(payload_len).map_err(|_| FileControlError::LengthOverflow)?;
        let total_len = FILE_CONTROL_HEADER_BYTES
            .checked_add(payload_len)
            .ok_or(FileControlError::LengthOverflow)?;
        let mut out = Vec::with_capacity(total_len);
        out.extend_from_slice(&FILE_CONTROL_MAGIC);
        out.push(FILE_CONTROL_VERSION_V1);
        out.push(self.claims.body.control_type() as u8);
        out.extend_from_slice(&FILE_CONTROL_FLAGS_V1.to_be_bytes());
        out.extend_from_slice(&payload_len_u32.to_be_bytes());
        out.extend_from_slice(&self.signer_ed25519_public_key);
        out.extend_from_slice(&claims);
        out.extend_from_slice(&self.signature);
        Ok(out)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, FileControlError> {
        if bytes.len() < FILE_CONTROL_HEADER_BYTES {
            return Err(FileControlError::TruncatedHeader {
                actual: bytes.len(),
            });
        }
        if &bytes[..4] != FILE_CONTROL_MAGIC.as_slice() {
            return Err(FileControlError::InvalidMagic);
        }
        let version = bytes[4];
        if version != FILE_CONTROL_VERSION_V1 {
            return Err(FileControlError::UnsupportedVersion { got: version });
        }
        let control_type = FileControlTypeV1::from_u8(bytes[5])?;
        let flags = u16::from_be_bytes(
            bytes[6..8]
                .try_into()
                .map_err(|_| FileControlError::MalformedPayload)?,
        );
        if flags != FILE_CONTROL_FLAGS_V1 {
            return Err(FileControlError::UnsupportedFlags { got: flags });
        }
        let declared_payload = usize::try_from(u32::from_be_bytes(
            bytes[8..12]
                .try_into()
                .map_err(|_| FileControlError::MalformedPayload)?,
        ))
        .map_err(|_| FileControlError::LengthOverflow)?;
        if declared_payload > MAX_FILE_CONTROL_PAYLOAD_BYTES {
            return Err(FileControlError::PayloadTooLarge {
                size: declared_payload,
                max: MAX_FILE_CONTROL_PAYLOAD_BYTES,
            });
        }
        let expected_len = FILE_CONTROL_HEADER_BYTES
            .checked_add(declared_payload)
            .ok_or(FileControlError::LengthOverflow)?;
        if bytes.len() != expected_len {
            return Err(FileControlError::LengthMismatch {
                declared: expected_len,
                actual: bytes.len(),
            });
        }
        if declared_payload < MIN_SIGNED_PAYLOAD_BYTES {
            return Err(FileControlError::MalformedPayload);
        }
        let payload = &bytes[FILE_CONTROL_HEADER_BYTES..];
        let key_end = ED25519_PUBLIC_KEY_SIZE;
        let signature_start = payload
            .len()
            .checked_sub(SIGNATURE_SIZE)
            .ok_or(FileControlError::MalformedPayload)?;
        if signature_start < key_end {
            return Err(FileControlError::MalformedPayload);
        }
        let signer_ed25519_public_key = payload[..key_end]
            .try_into()
            .map_err(|_| FileControlError::MalformedPayload)?;
        let claims = decode_claims(control_type, &payload[key_end..signature_start])?;
        let signature = payload[signature_start..]
            .try_into()
            .map_err(|_| FileControlError::MalformedPayload)?;
        let record = Self {
            claims,
            signer_ed25519_public_key,
            signature,
        };
        record.verify_self_signature()?;
        Ok(record)
    }

    /// Authorizes a valid self-signed record against authenticated peer state and durable sequence.
    /// `last_seen_sequence` must belong to this record's `(signer key, scope_id)` replay scope.
    pub fn verify_at(
        &self,
        expected_signer_node_id: &str,
        expected_recipient_node_id: &str,
        expected_signer_public_key: &[u8],
        expected_scope_id: &[u8; FILE_CONTROL_ID_BYTES],
        now_ms: i64,
        last_seen_sequence: Option<u64>,
    ) -> Result<(), FileControlError> {
        self.verify_self_signature()?;
        if expected_signer_public_key != self.signer_ed25519_public_key.as_slice() {
            return Err(FileControlError::UnexpectedSigner);
        }
        if expected_signer_node_id != self.claims.signer_node_id {
            return Err(FileControlError::UnexpectedSigner);
        }
        if expected_recipient_node_id != self.claims.recipient_node_id {
            return Err(FileControlError::UnexpectedRecipient);
        }
        if expected_scope_id != &self.claims.scope_id {
            return Err(FileControlError::UnexpectedScope);
        }
        if now_ms < self.claims.created_at_ms.saturating_sub(MAX_FILE_CONTROL_CLOCK_SKEW_MS)
            || now_ms > self.claims.expires_at_ms
        {
            return Err(FileControlError::NotActive);
        }
        if last_seen_sequence
            .map(|sequence| self.claims.sequence <= sequence)
            .unwrap_or(false)
        {
            return Err(FileControlError::ReplayOrOutOfOrder);
        }
        Ok(())
    }

    fn verify_self_signature(&self) -> Result<(), FileControlError> {
        self.claims.validate()?;
        validate_modern_signer_binding(
            &self.claims.signer_node_id,
            &self.signer_ed25519_public_key,
        )?;
        let payload = self.canonical_signing_bytes()?;
        Ed25519KeyPair::verify(
            &self.signer_ed25519_public_key,
            &payload,
            &self.signature,
        )
        .map_err(|_| FileControlError::InvalidSignature)
    }
}

pub fn sign_file_control_v1(
    claims: FileControlClaimsV1,
    identity: &Ed25519KeyPair,
) -> Result<SignedFileControlV1, FileControlError> {
    claims.validate()?;
    let signer_ed25519_public_key: [u8; ED25519_PUBLIC_KEY_SIZE] = identity
        .public_key()
        .0
        .try_into()
        .map_err(|_| FileControlError::InvalidSignerBinding)?;
    validate_modern_signer_binding(&claims.signer_node_id, &signer_ed25519_public_key)?;
    let claims_bytes = claims.canonical_claim_bytes()?;
    let payload = canonical_signing_bytes(
        claims.body.control_type(),
        &signer_ed25519_public_key,
        &claims_bytes,
    )?;
    let signature: [u8; SIGNATURE_SIZE] = identity
        .sign(&payload)
        .try_into()
        .map_err(|_| FileControlError::InvalidSignature)?;
    Ok(SignedFileControlV1 {
        claims,
        signer_ed25519_public_key,
        signature,
    })
}

fn canonical_signing_bytes(
    control_type: FileControlTypeV1,
    signer_public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
    claims: &[u8],
) -> Result<Vec<u8>, FileControlError> {
    let unsigned_payload_len = ED25519_PUBLIC_KEY_SIZE
        .checked_add(claims.len())
        .ok_or(FileControlError::LengthOverflow)?;
    let wire_payload_len = unsigned_payload_len
        .checked_add(SIGNATURE_SIZE)
        .ok_or(FileControlError::LengthOverflow)?;
    if wire_payload_len > MAX_FILE_CONTROL_PAYLOAD_BYTES {
        return Err(FileControlError::PayloadTooLarge {
            size: wire_payload_len,
            max: MAX_FILE_CONTROL_PAYLOAD_BYTES,
        });
    }
    let unsigned_payload_len =
        u32::try_from(unsigned_payload_len).map_err(|_| FileControlError::LengthOverflow)?;
    let mut out = Vec::with_capacity(
        FILE_CONTROL_DOMAIN_V1.len()
            + FILE_CONTROL_HEADER_BYTES
            + unsigned_payload_len as usize,
    );
    out.extend_from_slice(FILE_CONTROL_DOMAIN_V1);
    out.extend_from_slice(&FILE_CONTROL_MAGIC);
    out.push(FILE_CONTROL_VERSION_V1);
    out.push(control_type as u8);
    out.extend_from_slice(&FILE_CONTROL_FLAGS_V1.to_be_bytes());
    out.extend_from_slice(&unsigned_payload_len.to_be_bytes());
    out.extend_from_slice(signer_public_key);
    out.extend_from_slice(claims);
    Ok(out)
}

fn validate_body(claims: &FileControlClaimsV1) -> Result<(), FileControlError> {
    match &claims.body {
        FileControlBodyV1::Capabilities(capabilities) => {
            capabilities
                .validate()
                .map_err(|_| FileControlError::InvalidBody {
                    reason: "invalid file capabilities",
                })?;
        }
        FileControlBodyV1::Offer(offer) => {
            if !(MIN_ENCRYPTED_FILE_OFFER_BYTES..=MAX_ENCRYPTED_FILE_OFFER_BYTES)
                .contains(&offer.encrypted_offer.len())
            {
                return Err(FileControlError::InvalidBody {
                    reason: "encrypted offer length is outside the protocol bounds",
                });
            }
            let digest: [u8; FILE_CONTROL_HASH_BYTES] =
                Sha256::digest(&offer.encrypted_offer).into();
            if digest != offer.offer_digest {
                return Err(FileControlError::InvalidBody {
                    reason: "encrypted offer digest mismatch",
                });
            }
        }
        FileControlBodyV1::MissingRanges(page) => validate_range_page(page)?,
        FileControlBodyV1::Custody(custody) => {
            validate_range_page(&custody.chunks)?;
            if !is_canonical_node_id(&custody.custodian_node_id) {
                return Err(FileControlError::InvalidBody {
                    reason: "invalid custodian node ID",
                });
            }
            let max_lease = claims
                .created_at_ms
                .saturating_add(MAX_FILE_TRANSFER_CONTROL_LIFETIME_MS);
            if custody.lease_expires_at_ms < claims.expires_at_ms
                || custody.lease_expires_at_ms > max_lease
            {
                return Err(FileControlError::InvalidBody {
                    reason: "invalid custody lease",
                });
            }
            match custody.action {
                FileCustodyActionV1::Offer => {
                    if claims.recipient_node_id != custody.custodian_node_id
                        || claims.signer_node_id == custody.custodian_node_id
                    {
                        return Err(FileControlError::InvalidBody {
                            reason: "custody offer must target a different custodian",
                        });
                    }
                }
                FileCustodyActionV1::Accept | FileCustodyActionV1::StoredReceipt => {
                    if claims.signer_node_id != custody.custodian_node_id
                        || claims.recipient_node_id == custody.custodian_node_id
                    {
                        return Err(FileControlError::InvalidBody {
                            reason: "custody acceptance/receipt must be signed by the custodian",
                        });
                    }
                }
            }
        }
        FileControlBodyV1::FinalReceipt(receipt) => {
            if receipt
                .manifest_commitment
                .iter()
                .all(|byte| *byte == 0)
                || receipt.whole_file_sha256.iter().all(|byte| *byte == 0)
            {
                return Err(FileControlError::InvalidBody {
                    reason: "final receipt commitments must not be all zero",
                });
            }
            if (receipt.verified_file_size == 0) != (receipt.verified_chunk_count == 0)
                || receipt.verified_chunk_count > MAX_FILE_CHUNK_COUNT
            {
                return Err(FileControlError::InvalidBody {
                    reason: "final receipt file size and chunk count disagree",
                });
            }
        }
    }
    Ok(())
}

fn validate_range_page(page: &FileChunkRangePageV1) -> Result<(), FileControlError> {
    if page.batch_id.iter().all(|byte| *byte == 0) {
        return Err(FileControlError::InvalidBody {
            reason: "range batch ID is all zero",
        });
    }
    if page.total_chunk_count == 0 || page.total_chunk_count > MAX_FILE_CHUNK_COUNT {
        return Err(FileControlError::InvalidBody {
            reason: "range page total chunk count is outside u64 file geometry",
        });
    }
    if page.ranges.is_empty() || page.ranges.len() > MAX_FILE_CONTROL_RANGES {
        return Err(FileControlError::InvalidBody {
            reason: "range page count is outside the protocol bounds",
        });
    }
    let mut previous_end = None;
    for range in &page.ranges {
        if range.start_chunk >= range.end_chunk_exclusive
            || range.end_chunk_exclusive > page.total_chunk_count
        {
            return Err(FileControlError::InvalidBody {
                reason: "chunk range is empty or outside the file",
            });
        }
        if previous_end
            .map(|end| range.start_chunk <= end)
            .unwrap_or(false)
        {
            return Err(FileControlError::InvalidBody {
                reason: "chunk ranges must be sorted, disjoint and non-adjacent",
            });
        }
        previous_end = Some(range.end_chunk_exclusive);
    }
    Ok(())
}

fn encode_body(out: &mut Vec<u8>, body: &FileControlBodyV1) -> Result<(), FileControlError> {
    match body {
        FileControlBodyV1::Capabilities(capabilities) => {
            out.push(capabilities.min_protocol_version);
            out.push(capabilities.max_protocol_version);
            out.extend_from_slice(&capabilities.max_parallel_streams.to_be_bytes());
            out.extend_from_slice(&capabilities.mandatory_features.to_be_bytes());
            out.extend_from_slice(&capabilities.optional_features.to_be_bytes());
            out.extend_from_slice(&capabilities.max_frame_payload_bytes.to_be_bytes());
        }
        FileControlBodyV1::Offer(offer) => {
            out.extend_from_slice(&offer.offer_digest);
            let offer_len = u32::try_from(offer.encrypted_offer.len())
                .map_err(|_| FileControlError::LengthOverflow)?;
            out.extend_from_slice(&offer_len.to_be_bytes());
            out.extend_from_slice(&offer.encrypted_offer);
        }
        FileControlBodyV1::MissingRanges(page) => encode_range_page(out, page)?,
        FileControlBodyV1::Custody(custody) => {
            out.push(custody.action as u8);
            push_node_id(out, &custody.custodian_node_id)?;
            out.extend_from_slice(&custody.lease_expires_at_ms.to_be_bytes());
            encode_range_page(out, &custody.chunks)?;
        }
        FileControlBodyV1::FinalReceipt(receipt) => {
            out.extend_from_slice(&receipt.manifest_commitment);
            out.extend_from_slice(&receipt.whole_file_sha256);
            out.extend_from_slice(&receipt.verified_file_size.to_be_bytes());
            out.extend_from_slice(&receipt.verified_chunk_count.to_be_bytes());
        }
    }
    Ok(())
}

fn encode_range_page(
    out: &mut Vec<u8>,
    page: &FileChunkRangePageV1,
) -> Result<(), FileControlError> {
    out.extend_from_slice(&page.batch_id);
    out.extend_from_slice(&page.page_index.to_be_bytes());
    out.push(u8::from(page.is_last_page));
    out.extend_from_slice(&page.total_chunk_count.to_be_bytes());
    let range_count =
        u16::try_from(page.ranges.len()).map_err(|_| FileControlError::LengthOverflow)?;
    out.extend_from_slice(&range_count.to_be_bytes());
    for range in &page.ranges {
        out.extend_from_slice(&range.start_chunk.to_be_bytes());
        out.extend_from_slice(&range.end_chunk_exclusive.to_be_bytes());
    }
    Ok(())
}

fn decode_claims(
    control_type: FileControlTypeV1,
    bytes: &[u8],
) -> Result<FileControlClaimsV1, FileControlError> {
    let mut cursor = ControlCursor::new(bytes);
    let record_id = cursor.array()?;
    let scope_id = cursor.array()?;
    let sequence = cursor.u64()?;
    let created_at_ms = cursor.i64()?;
    let expires_at_ms = cursor.i64()?;
    let signer_node_id = cursor.node_id()?;
    let recipient_node_id = cursor.node_id()?;
    let body = match control_type {
        FileControlTypeV1::Capabilities => {
            let body = cursor.bytes(CAPABILITIES_BODY_BYTES)?;
            FileControlBodyV1::Capabilities(FileCapabilitiesV1 {
                min_protocol_version: body[0],
                max_protocol_version: body[1],
                max_parallel_streams: u16::from_be_bytes(
                    body[2..4]
                        .try_into()
                        .map_err(|_| FileControlError::MalformedPayload)?,
                ),
                mandatory_features: u32::from_be_bytes(
                    body[4..8]
                        .try_into()
                        .map_err(|_| FileControlError::MalformedPayload)?,
                ),
                optional_features: u32::from_be_bytes(
                    body[8..12]
                        .try_into()
                        .map_err(|_| FileControlError::MalformedPayload)?,
                ),
                max_frame_payload_bytes: u32::from_be_bytes(
                    body[12..16]
                        .try_into()
                        .map_err(|_| FileControlError::MalformedPayload)?,
                ),
            })
        }
        FileControlTypeV1::Offer => {
            let offer_digest = cursor.array()?;
            let offer_len = cursor.u32_as_usize()?;
            if !(MIN_ENCRYPTED_FILE_OFFER_BYTES..=MAX_ENCRYPTED_FILE_OFFER_BYTES)
                .contains(&offer_len)
            {
                return Err(FileControlError::InvalidBody {
                    reason: "encrypted offer length is outside the protocol bounds",
                });
            }
            let encrypted_offer = cursor.bytes(offer_len)?.to_vec();
            FileControlBodyV1::Offer(EncryptedFileOfferV1 {
                offer_digest,
                encrypted_offer,
            })
        }
        FileControlTypeV1::MissingRanges => {
            FileControlBodyV1::MissingRanges(decode_range_page(&mut cursor)?)
        }
        FileControlTypeV1::Custody => {
            let action = FileCustodyActionV1::from_u8(cursor.u8()?)?;
            let custodian_node_id = cursor.node_id()?;
            let lease_expires_at_ms = cursor.i64()?;
            let chunks = decode_range_page(&mut cursor)?;
            FileControlBodyV1::Custody(FileCustodyControlV1 {
                action,
                custodian_node_id,
                lease_expires_at_ms,
                chunks,
            })
        }
        FileControlTypeV1::FinalReceipt => {
            if cursor.remaining() != FINAL_RECEIPT_BODY_BYTES {
                return Err(FileControlError::MalformedPayload);
            }
            FileControlBodyV1::FinalReceipt(FileFinalReceiptV1 {
                manifest_commitment: cursor.array()?,
                whole_file_sha256: cursor.array()?,
                verified_file_size: cursor.u64()?,
                verified_chunk_count: cursor.u64()?,
            })
        }
    };
    cursor.finish()?;
    let claims = FileControlClaimsV1 {
        record_id,
        scope_id,
        sequence,
        created_at_ms,
        expires_at_ms,
        signer_node_id,
        recipient_node_id,
        body,
    };
    claims.validate()?;
    Ok(claims)
}

fn decode_range_page(
    cursor: &mut ControlCursor<'_>,
) -> Result<FileChunkRangePageV1, FileControlError> {
    let batch_id = cursor.array()?;
    let page_index = cursor.u32()?;
    let is_last_page = match cursor.u8()? {
        0 => false,
        1 => true,
        _ => {
            return Err(FileControlError::InvalidBody {
                reason: "range page final flag is not canonical",
            })
        }
    };
    let total_chunk_count = cursor.u64()?;
    let range_count = usize::from(cursor.u16()?);
    if range_count == 0 || range_count > MAX_FILE_CONTROL_RANGES {
        return Err(FileControlError::InvalidBody {
            reason: "range page count is outside the protocol bounds",
        });
    }
    let encoded_ranges = range_count
        .checked_mul(16)
        .ok_or(FileControlError::LengthOverflow)?;
    if encoded_ranges > cursor.remaining() {
        return Err(FileControlError::MalformedPayload);
    }
    let mut ranges = Vec::with_capacity(range_count);
    for _ in 0..range_count {
        ranges.push(FileChunkRangeV1 {
            start_chunk: cursor.u64()?,
            end_chunk_exclusive: cursor.u64()?,
        });
    }
    Ok(FileChunkRangePageV1 {
        batch_id,
        page_index,
        is_last_page,
        total_chunk_count,
        ranges,
    })
}

fn push_node_id(out: &mut Vec<u8>, node_id: &str) -> Result<(), FileControlError> {
    if !is_canonical_node_id(node_id) {
        return Err(FileControlError::InvalidClaims {
            reason: "invalid canonical node ID",
        });
    }
    let bytes = node_id.as_bytes();
    let len = u16::try_from(bytes.len()).map_err(|_| FileControlError::LengthOverflow)?;
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(bytes);
    Ok(())
}

fn is_canonical_node_id(value: &str) -> bool {
    matches!(value.len(), MIN_NODE_ID_BYTES | MAX_NODE_ID_BYTES)
        && value.starts_with("pk_")
        && value[3..]
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

fn validate_modern_signer_binding(
    signer_node_id: &str,
    public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
) -> Result<(), FileControlError> {
    if signer_node_id.len() == MAX_NODE_ID_BYTES {
        let expected = format!("pk_{}", NodeId::from_ed25519_pubkey(public_key).to_hex());
        if signer_node_id != expected {
            return Err(FileControlError::InvalidSignerBinding);
        }
    }
    Ok(())
}

struct ControlCursor<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> ControlCursor<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }

    fn remaining(&self) -> usize {
        self.bytes.len().saturating_sub(self.offset)
    }

    fn bytes(&mut self, count: usize) -> Result<&'a [u8], FileControlError> {
        let end = self
            .offset
            .checked_add(count)
            .ok_or(FileControlError::LengthOverflow)?;
        let value = self
            .bytes
            .get(self.offset..end)
            .ok_or(FileControlError::MalformedPayload)?;
        self.offset = end;
        Ok(value)
    }

    fn array<const N: usize>(&mut self) -> Result<[u8; N], FileControlError> {
        self.bytes(N)?
            .try_into()
            .map_err(|_| FileControlError::MalformedPayload)
    }

    fn u8(&mut self) -> Result<u8, FileControlError> {
        Ok(self.bytes(1)?[0])
    }

    fn u16(&mut self) -> Result<u16, FileControlError> {
        Ok(u16::from_be_bytes(self.array()?))
    }

    fn u32(&mut self) -> Result<u32, FileControlError> {
        Ok(u32::from_be_bytes(self.array()?))
    }

    fn u32_as_usize(&mut self) -> Result<usize, FileControlError> {
        usize::try_from(self.u32()?).map_err(|_| FileControlError::LengthOverflow)
    }

    fn u64(&mut self) -> Result<u64, FileControlError> {
        Ok(u64::from_be_bytes(self.array()?))
    }

    fn i64(&mut self) -> Result<i64, FileControlError> {
        Ok(i64::from_be_bytes(self.array()?))
    }

    fn node_id(&mut self) -> Result<String, FileControlError> {
        let len = usize::from(self.u16()?);
        if !matches!(len, MIN_NODE_ID_BYTES | MAX_NODE_ID_BYTES) {
            return Err(FileControlError::InvalidClaims {
                reason: "invalid canonical node ID length",
            });
        }
        let text = std::str::from_utf8(self.bytes(len)?)
            .map_err(|_| FileControlError::InvalidClaims {
                reason: "node ID is not UTF-8",
            })?
            .to_owned();
        if !is_canonical_node_id(&text) {
            return Err(FileControlError::InvalidClaims {
                reason: "invalid canonical node ID",
            });
        }
        Ok(text)
    }

    fn finish(self) -> Result<(), FileControlError> {
        if self.offset != self.bytes.len() {
            return Err(FileControlError::MalformedPayload);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::network::file_wire::{
        FileFrameV1, FEATURE_CHUNK_RANGE_FRAMES, FILE_FRAME_HEADER_BYTES,
        MAX_FILE_FRAME_PAYLOAD_BYTES, REQUIRED_FILE_FEATURES_V1,
    };

    const NOW: i64 = 1_900_000_000_000;

    fn node(identity: &Ed25519KeyPair) -> String {
        format!("pk_{}", identity.node_id().to_hex())
    }

    fn legacy(byte: u8) -> String {
        format!("pk_{}", format!("{byte:02x}").repeat(16))
    }

    fn capabilities() -> FileCapabilitiesV1 {
        FileCapabilitiesV1 {
            min_protocol_version: 1,
            max_protocol_version: 1,
            max_parallel_streams: 4,
            mandatory_features: REQUIRED_FILE_FEATURES_V1,
            optional_features: FEATURE_CHUNK_RANGE_FRAMES,
            max_frame_payload_bytes: MAX_FILE_FRAME_PAYLOAD_BYTES as u32,
        }
    }

    fn range_page() -> FileChunkRangePageV1 {
        FileChunkRangePageV1 {
            batch_id: [0x33; FILE_CONTROL_ID_BYTES],
            page_index: 0,
            is_last_page: true,
            total_chunk_count: 100,
            ranges: vec![
                FileChunkRangeV1 {
                    start_chunk: 0,
                    end_chunk_exclusive: 10,
                },
                FileChunkRangeV1 {
                    start_chunk: 20,
                    end_chunk_exclusive: 30,
                },
            ],
        }
    }

    fn encrypted_offer(size: usize) -> EncryptedFileOfferV1 {
        let encrypted_offer = vec![0xA5; size];
        EncryptedFileOfferV1 {
            offer_digest: Sha256::digest(&encrypted_offer).into(),
            encrypted_offer,
        }
    }

    fn final_receipt() -> FileFinalReceiptV1 {
        FileFinalReceiptV1 {
            manifest_commitment: [0x44; FILE_CONTROL_HASH_BYTES],
            whole_file_sha256: [0x55; FILE_CONTROL_HASH_BYTES],
            verified_file_size: 50_000,
            verified_chunk_count: 4,
        }
    }

    fn claims(
        body: FileControlBodyV1,
        signer: &Ed25519KeyPair,
        recipient: &Ed25519KeyPair,
    ) -> FileControlClaimsV1 {
        FileControlClaimsV1 {
            record_id: [0x11; FILE_CONTROL_ID_BYTES],
            scope_id: [0x22; FILE_CONTROL_ID_BYTES],
            sequence: 7,
            created_at_ms: NOW,
            expires_at_ms: NOW
                + match &body {
                    FileControlBodyV1::Capabilities(_) => 60_000,
                    FileControlBodyV1::MissingRanges(_) => 24 * 60 * 60 * 1_000,
                    _ => 7 * 24 * 60 * 60 * 1_000,
                },
            signer_node_id: node(signer),
            recipient_node_id: node(recipient),
            body,
        }
    }

    fn signed(
        body: FileControlBodyV1,
        signer: &Ed25519KeyPair,
        recipient: &Ed25519KeyPair,
    ) -> SignedFileControlV1 {
        sign_file_control_v1(claims(body, signer, recipient), signer).unwrap()
    }

    fn verify(
        record: &SignedFileControlV1,
        signer: &Ed25519KeyPair,
        recipient: &Ed25519KeyPair,
        last_seen_sequence: Option<u64>,
    ) -> Result<(), FileControlError> {
        record.verify_at(
            &node(signer),
            &node(recipient),
            signer.public_key().as_bytes(),
            &record.claims.scope_id,
            NOW + 1_000,
            last_seen_sequence,
        )
    }

    #[test]
    fn all_control_types_are_signed_canonical_and_round_trip() {
        let sender = Ed25519KeyPair::from_secret_bytes(&[1; 32]).unwrap();
        let recipient = Ed25519KeyPair::from_secret_bytes(&[2; 32]).unwrap();
        let custodian = node(&recipient);
        let bodies = vec![
            FileControlBodyV1::Capabilities(capabilities()),
            FileControlBodyV1::Offer(encrypted_offer(64)),
            FileControlBodyV1::MissingRanges(range_page()),
            FileControlBodyV1::Custody(FileCustodyControlV1 {
                action: FileCustodyActionV1::Offer,
                custodian_node_id: custodian,
                lease_expires_at_ms: NOW + 14 * 24 * 60 * 60 * 1_000,
                chunks: range_page(),
            }),
            FileControlBodyV1::FinalReceipt(final_receipt()),
        ];

        for (index, body) in bodies.into_iter().enumerate() {
            let record = signed(body, &sender, &recipient);
            let first = record.encode().unwrap();
            assert_eq!(first, record.encode().unwrap());
            assert_eq!(&first[..4], FILE_CONTROL_MAGIC.as_slice());
            assert_eq!(first[4], FILE_CONTROL_VERSION_V1);
            assert_eq!(first[5], (index + 1) as u8);
            assert_eq!(&first[6..8], &[0, 0]);
            assert!(record
                .canonical_signing_bytes()
                .unwrap()
                .starts_with(FILE_CONTROL_DOMAIN_V1));
            if index == 0 {
                let raw_capabilities = FileFrameV1::Capabilities(capabilities()).encode().unwrap();
                assert!(record
                    .claims
                    .canonical_claim_bytes()
                    .unwrap()
                    .ends_with(&raw_capabilities[FILE_FRAME_HEADER_BYTES..]));
            }
            let decoded = SignedFileControlV1::decode(&first).unwrap();
            assert_eq!(decoded, record);
            assert_eq!(verify(&decoded, &sender, &recipient, Some(6)), Ok(()));
        }
    }

    #[test]
    fn every_truncation_trailing_byte_and_payload_tamper_is_rejected() {
        let sender = Ed25519KeyPair::from_secret_bytes(&[3; 32]).unwrap();
        let recipient = Ed25519KeyPair::from_secret_bytes(&[4; 32]).unwrap();
        let original = signed(
            FileControlBodyV1::Offer(encrypted_offer(48)),
            &sender,
            &recipient,
        )
        .encode()
        .unwrap();

        for len in 0..original.len() {
            assert!(SignedFileControlV1::decode(&original[..len]).is_err());
        }
        let mut trailing = original.clone();
        trailing.push(0);
        assert!(matches!(
            SignedFileControlV1::decode(&trailing),
            Err(FileControlError::LengthMismatch { .. })
        ));
        for index in 0..original.len() {
            let mut tampered = original.clone();
            tampered[index] ^= 1;
            assert!(
                SignedFileControlV1::decode(&tampered).is_err(),
                "tampered byte {index} was accepted"
            );
        }
    }

    #[test]
    fn unknown_version_type_flags_and_oversize_fail_closed() {
        let sender = Ed25519KeyPair::from_secret_bytes(&[5; 32]).unwrap();
        let recipient = Ed25519KeyPair::from_secret_bytes(&[6; 32]).unwrap();
        let original = signed(
            FileControlBodyV1::Capabilities(capabilities()),
            &sender,
            &recipient,
        )
        .encode()
        .unwrap();

        let mut bytes = original.clone();
        bytes[4] = 2;
        assert_eq!(
            SignedFileControlV1::decode(&bytes),
            Err(FileControlError::UnsupportedVersion { got: 2 })
        );
        let mut bytes = original.clone();
        bytes[5] = 0xff;
        assert_eq!(
            SignedFileControlV1::decode(&bytes),
            Err(FileControlError::UnsupportedType { got: 0xff })
        );
        let mut bytes = original.clone();
        bytes[7] = 1;
        assert_eq!(
            SignedFileControlV1::decode(&bytes),
            Err(FileControlError::UnsupportedFlags { got: 1 })
        );
        let mut bytes = original;
        bytes[8..12].copy_from_slice(&u32::MAX.to_be_bytes());
        assert_eq!(
            SignedFileControlV1::decode(&bytes),
            Err(FileControlError::PayloadTooLarge {
                size: u32::MAX as usize,
                max: MAX_FILE_CONTROL_PAYLOAD_BYTES,
            })
        );
    }

    #[test]
    fn authenticated_peer_recipient_and_replay_scope_are_enforced() {
        let sender = Ed25519KeyPair::from_secret_bytes(&[7; 32]).unwrap();
        let recipient = Ed25519KeyPair::from_secret_bytes(&[8; 32]).unwrap();
        let other = Ed25519KeyPair::from_secret_bytes(&[9; 32]).unwrap();
        let record = signed(
            FileControlBodyV1::MissingRanges(range_page()),
            &sender,
            &recipient,
        );

        assert_eq!(verify(&record, &sender, &recipient, Some(6)), Ok(()));
        assert_eq!(
            verify(&record, &sender, &recipient, Some(7)),
            Err(FileControlError::ReplayOrOutOfOrder)
        );
        assert_eq!(
            verify(&record, &sender, &recipient, Some(8)),
            Err(FileControlError::ReplayOrOutOfOrder)
        );
        assert_eq!(
            record.verify_at(
                &node(&other),
                &node(&recipient),
                other.public_key().as_bytes(),
                &record.claims.scope_id,
                NOW + 1_000,
                None,
            ),
            Err(FileControlError::UnexpectedSigner)
        );
        assert_eq!(
            record.verify_at(
                &node(&sender),
                &node(&other),
                sender.public_key().as_bytes(),
                &record.claims.scope_id,
                NOW + 1_000,
                None,
            ),
            Err(FileControlError::UnexpectedRecipient)
        );
        assert_eq!(
            record.verify_at(
                &node(&sender),
                &node(&recipient),
                sender.public_key().as_bytes(),
                &[0x99; FILE_CONTROL_ID_BYTES],
                NOW + 1_000,
                None,
            ),
            Err(FileControlError::UnexpectedScope)
        );
    }

    #[test]
    fn future_expired_and_overlong_records_are_rejected() {
        let sender = Ed25519KeyPair::from_secret_bytes(&[10; 32]).unwrap();
        let recipient = Ed25519KeyPair::from_secret_bytes(&[11; 32]).unwrap();
        let mut future = signed(
            FileControlBodyV1::Capabilities(capabilities()),
            &sender,
            &recipient,
        );
        future.claims.created_at_ms = NOW + MAX_FILE_CONTROL_CLOCK_SKEW_MS + 1;
        future.claims.expires_at_ms = future.claims.created_at_ms + 60_000;
        future = sign_file_control_v1(future.claims, &sender).unwrap();
        assert_eq!(
            future.verify_at(
                &node(&sender),
                &node(&recipient),
                sender.public_key().as_bytes(),
                &future.claims.scope_id,
                NOW,
                None,
            ),
            Err(FileControlError::NotActive)
        );

        let expired = signed(
            FileControlBodyV1::Capabilities(capabilities()),
            &sender,
            &recipient,
        );
        assert_eq!(
            expired.verify_at(
                &node(&sender),
                &node(&recipient),
                sender.public_key().as_bytes(),
                &expired.claims.scope_id,
                expired.claims.expires_at_ms + 1,
                None,
            ),
            Err(FileControlError::NotActive)
        );

        let mut overlong = claims(
            FileControlBodyV1::Capabilities(capabilities()),
            &sender,
            &recipient,
        );
        overlong.expires_at_ms =
            overlong.created_at_ms + MAX_FILE_CAPABILITY_LIFETIME_MS + 1;
        assert!(matches!(
            sign_file_control_v1(overlong, &sender),
            Err(FileControlError::InvalidClaims { .. })
        ));
    }

    #[test]
    fn range_pages_are_bounded_normalized_and_future_size_ready() {
        let sender = Ed25519KeyPair::from_secret_bytes(&[12; 32]).unwrap();
        let recipient = Ed25519KeyPair::from_secret_bytes(&[13; 32]).unwrap();
        let mut page = range_page();
        page.total_chunk_count = MAX_FILE_CHUNK_COUNT;
        page.ranges = vec![FileChunkRangeV1 {
            start_chunk: MAX_FILE_CHUNK_COUNT - 1,
            end_chunk_exclusive: MAX_FILE_CHUNK_COUNT,
        }];
        let record = signed(
            FileControlBodyV1::MissingRanges(page),
            &sender,
            &recipient,
        );
        let bytes = record.encode().unwrap();
        assert_eq!(SignedFileControlV1::decode(&bytes).unwrap(), record);

        let mut adjacent = range_page();
        adjacent.ranges[1].start_chunk = adjacent.ranges[0].end_chunk_exclusive;
        assert!(matches!(
            sign_file_control_v1(
                claims(
                    FileControlBodyV1::MissingRanges(adjacent),
                    &sender,
                    &recipient,
                ),
                &sender,
            ),
            Err(FileControlError::InvalidBody { .. })
        ));

        let mut outside = range_page();
        outside.ranges[1].end_chunk_exclusive = outside.total_chunk_count + 1;
        assert!(matches!(
            sign_file_control_v1(
                claims(
                    FileControlBodyV1::MissingRanges(outside),
                    &sender,
                    &recipient,
                ),
                &sender,
            ),
            Err(FileControlError::InvalidBody { .. })
        ));

        let mut too_many = range_page();
        too_many.total_chunk_count = (MAX_FILE_CONTROL_RANGES as u64 + 1) * 2;
        too_many.ranges = (0..=MAX_FILE_CONTROL_RANGES)
            .map(|index| FileChunkRangeV1 {
                start_chunk: (index as u64) * 2,
                end_chunk_exclusive: (index as u64) * 2 + 1,
            })
            .collect();
        assert!(matches!(
            sign_file_control_v1(
                claims(
                    FileControlBodyV1::MissingRanges(too_many),
                    &sender,
                    &recipient,
                ),
                &sender,
            ),
            Err(FileControlError::InvalidBody { .. })
        ));
    }

    #[test]
    fn encrypted_offer_digest_and_exact_maximum_are_enforced() {
        let sender = Ed25519KeyPair::from_secret_bytes(&[14; 32]).unwrap();
        let recipient = Ed25519KeyPair::from_secret_bytes(&[15; 32]).unwrap();
        let max = signed(
            FileControlBodyV1::Offer(encrypted_offer(MAX_ENCRYPTED_FILE_OFFER_BYTES)),
            &sender,
            &recipient,
        );
        assert!(max.encode().is_ok());

        let oversized = encrypted_offer(MAX_ENCRYPTED_FILE_OFFER_BYTES + 1);
        assert!(matches!(
            sign_file_control_v1(
                claims(FileControlBodyV1::Offer(oversized), &sender, &recipient),
                &sender,
            ),
            Err(FileControlError::InvalidBody { .. })
        ));

        let mut wrong_digest = encrypted_offer(64);
        wrong_digest.offer_digest[0] ^= 1;
        assert!(matches!(
            sign_file_control_v1(
                claims(
                    FileControlBodyV1::Offer(wrong_digest),
                    &sender,
                    &recipient,
                ),
                &sender,
            ),
            Err(FileControlError::InvalidBody { .. })
        ));
    }

    #[test]
    fn custody_roles_actions_and_lease_are_enforced() {
        let origin = Ed25519KeyPair::from_secret_bytes(&[16; 32]).unwrap();
        let custodian = Ed25519KeyPair::from_secret_bytes(&[17; 32]).unwrap();
        let offer = FileCustodyControlV1 {
            action: FileCustodyActionV1::Offer,
            custodian_node_id: node(&custodian),
            lease_expires_at_ms: NOW + 14 * 24 * 60 * 60 * 1_000,
            chunks: range_page(),
        };
        assert!(signed(
            FileControlBodyV1::Custody(offer.clone()),
            &origin,
            &custodian,
        )
        .encode()
        .is_ok());

        let accepted = FileCustodyControlV1 {
            action: FileCustodyActionV1::Accept,
            ..offer.clone()
        };
        assert!(signed(
            FileControlBodyV1::Custody(accepted),
            &custodian,
            &origin,
        )
        .encode()
        .is_ok());

        assert!(matches!(
            sign_file_control_v1(
                claims(
                    FileControlBodyV1::Custody(FileCustodyControlV1 {
                        action: FileCustodyActionV1::StoredReceipt,
                        ..offer.clone()
                    }),
                    &origin,
                    &custodian,
                ),
                &origin,
            ),
            Err(FileControlError::InvalidBody { .. })
        ));

        let mut bad_lease = offer;
        bad_lease.lease_expires_at_ms = NOW + MAX_FILE_TRANSFER_CONTROL_LIFETIME_MS + 1;
        assert!(matches!(
            sign_file_control_v1(
                claims(
                    FileControlBodyV1::Custody(bad_lease),
                    &origin,
                    &custodian,
                ),
                &origin,
            ),
            Err(FileControlError::InvalidBody { .. })
        ));
    }

    #[test]
    fn final_receipt_requires_consistent_verified_geometry() {
        let sender = Ed25519KeyPair::from_secret_bytes(&[18; 32]).unwrap();
        let recipient = Ed25519KeyPair::from_secret_bytes(&[19; 32]).unwrap();
        let mut invalid = final_receipt();
        invalid.manifest_commitment = [0; FILE_CONTROL_HASH_BYTES];
        assert!(matches!(
            sign_file_control_v1(
                claims(
                    FileControlBodyV1::FinalReceipt(invalid),
                    &sender,
                    &recipient,
                ),
                &sender,
            ),
            Err(FileControlError::InvalidBody { .. })
        ));

        let mut invalid = final_receipt();
        invalid.verified_chunk_count = 0;
        assert!(matches!(
            sign_file_control_v1(
                claims(
                    FileControlBodyV1::FinalReceipt(invalid),
                    &sender,
                    &recipient,
                ),
                &sender,
            ),
            Err(FileControlError::InvalidBody { .. })
        ));

        let mut too_many = final_receipt();
        too_many.verified_chunk_count = MAX_FILE_CHUNK_COUNT + 1;
        assert!(matches!(
            sign_file_control_v1(
                claims(
                    FileControlBodyV1::FinalReceipt(too_many),
                    &sender,
                    &recipient,
                ),
                &sender,
            ),
            Err(FileControlError::InvalidBody { .. })
        ));

        let empty = FileFinalReceiptV1 {
            manifest_commitment: [1; FILE_CONTROL_HASH_BYTES],
            whole_file_sha256: [2; FILE_CONTROL_HASH_BYTES],
            verified_file_size: 0,
            verified_chunk_count: 0,
        };
        assert!(signed(
            FileControlBodyV1::FinalReceipt(empty),
            &sender,
            &recipient,
        )
        .encode()
        .is_ok());
    }

    #[test]
    fn invalid_capability_downgrade_is_rejected_before_signing() {
        let sender = Ed25519KeyPair::from_secret_bytes(&[20; 32]).unwrap();
        let recipient = Ed25519KeyPair::from_secret_bytes(&[21; 32]).unwrap();
        let mut invalid = capabilities();
        invalid.mandatory_features = 0;
        assert!(matches!(
            sign_file_control_v1(
                claims(
                    FileControlBodyV1::Capabilities(invalid),
                    &sender,
                    &recipient,
                ),
                &sender,
            ),
            Err(FileControlError::InvalidBody { .. })
        ));
    }

    #[test]
    fn modern_key_binding_and_legacy_pinned_sidecar_are_both_strict() {
        let sender = Ed25519KeyPair::from_secret_bytes(&[22; 32]).unwrap();
        let recipient = Ed25519KeyPair::from_secret_bytes(&[23; 32]).unwrap();
        let other = Ed25519KeyPair::from_secret_bytes(&[26; 32]).unwrap();
        let mut mismatched = claims(
            FileControlBodyV1::Capabilities(capabilities()),
            &sender,
            &recipient,
        );
        mismatched.signer_node_id = node(&other);
        assert_eq!(
            sign_file_control_v1(mismatched, &sender),
            Err(FileControlError::InvalidSignerBinding)
        );

        let mut legacy_claims = claims(
            FileControlBodyV1::Capabilities(capabilities()),
            &sender,
            &recipient,
        );
        legacy_claims.signer_node_id = legacy(0x24);
        let legacy_record = sign_file_control_v1(legacy_claims, &sender).unwrap();
        assert_eq!(
            legacy_record.verify_at(
                &legacy(0x24),
                &node(&recipient),
                sender.public_key().as_bytes(),
                &legacy_record.claims.scope_id,
                NOW + 1_000,
                None,
            ),
            Ok(())
        );
        assert_eq!(
            legacy_record.verify_at(
                &legacy(0x25),
                &node(&recipient),
                sender.public_key().as_bytes(),
                &legacy_record.claims.scope_id,
                NOW + 1_000,
                None,
            ),
            Err(FileControlError::UnexpectedSigner)
        );
    }

    #[test]
    fn zero_ids_sequence_and_noncanonical_peers_are_rejected() {
        let sender = Ed25519KeyPair::from_secret_bytes(&[24; 32]).unwrap();
        let recipient = Ed25519KeyPair::from_secret_bytes(&[25; 32]).unwrap();
        let base = claims(
            FileControlBodyV1::Capabilities(capabilities()),
            &sender,
            &recipient,
        );

        for mutation in 0..4 {
            let mut invalid = base.clone();
            match mutation {
                0 => invalid.record_id = [0; FILE_CONTROL_ID_BYTES],
                1 => invalid.scope_id = [0; FILE_CONTROL_ID_BYTES],
                2 => invalid.sequence = 0,
                _ => invalid.recipient_node_id = invalid.signer_node_id.clone(),
            }
            assert!(matches!(
                sign_file_control_v1(invalid, &sender),
                Err(FileControlError::InvalidClaims { .. })
            ));
        }
    }
}
