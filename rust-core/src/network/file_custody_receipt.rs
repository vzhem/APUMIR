//! Canonical signed receipts for one exact durable file-custody ciphertext range.
//!
//! A receipt is not a final-delivery ACK. It proves that one pinned custodian accepted one exact
//! transfer/chunk/range digest until an absolute expiry. The signer seam also supports the installed
//! device identity without exposing its private seed.

use crate::crypto::keys::{
    Ed25519KeyPair, NodeId, ED25519_PUBLIC_KEY_SIZE, SIGNATURE_SIZE,
};
use crate::network::file_control::{FileControlSigner, MAX_FILE_CONTROL_CLOCK_SKEW_MS};
use crate::crypto::file_transfer::{FILE_CHUNK_TAG_BYTES, MAX_FILE_CHUNK_BYTES};
use crate::network::file_custody::{
    FileCustodyRangeId, FileCustodyReceiptClaimsV1, FILE_CUSTODY_MAX_TTL_MS,
    FILE_CUSTODY_MIN_TTL_MS,
};
use crate::network::file_wire::MAX_FILE_CHUNK_DATA_BYTES;

pub const FILE_CUSTODY_RECEIPT_MAGIC: [u8; 4] = *b"APUR";
pub const FILE_CUSTODY_RECEIPT_VERSION_V1: u8 = 1;
pub const FILE_CUSTODY_RECEIPT_HEADER_BYTES: usize = 12;
pub const MAX_FILE_CUSTODY_RECEIPT_BYTES: usize = 512;

const FILE_CUSTODY_RECEIPT_TYPE_STORED: u8 = 1;
const FILE_CUSTODY_RECEIPT_FLAGS_V1: u16 = 0;
const FILE_CUSTODY_RECEIPT_DOMAIN_V1: &[u8] = b"apu-file-custody-receipt-v1\0";
const MIN_NODE_ID_BYTES: usize = 35;
const MAX_NODE_ID_BYTES: usize = 67;
const FIXED_PAYLOAD_BYTES: usize = ED25519_PUBLIC_KEY_SIZE * 2
    + 16
    + 8
    + 4
    + 4
    + 4
    + 32
    + 8
    + 8
    + SIGNATURE_SIZE
    + 3;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SignedFileCustodyReceiptV1 {
    pub origin_node_id: String,
    pub custodian_node_id: String,
    pub claims: FileCustodyReceiptClaimsV1,
    pub custodian_ed25519_public_key: [u8; ED25519_PUBLIC_KEY_SIZE],
    pub signature: [u8; SIGNATURE_SIZE],
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum FileCustodyReceiptError {
    #[error("file custody receipt header is truncated: {actual} bytes")]
    TruncatedHeader { actual: usize },
    #[error("invalid file custody receipt magic")]
    InvalidMagic,
    #[error("unsupported file custody receipt version: {got}")]
    UnsupportedVersion { got: u8 },
    #[error("unsupported file custody receipt type: {got}")]
    UnsupportedType { got: u8 },
    #[error("unsupported file custody receipt flags: 0x{got:04x}")]
    UnsupportedFlags { got: u16 },
    #[error("file custody receipt is too large: {size} bytes")]
    TooLarge { size: usize },
    #[error("file custody receipt length arithmetic overflow")]
    LengthOverflow,
    #[error("file custody receipt length mismatch")]
    LengthMismatch,
    #[error("malformed file custody receipt")]
    Malformed,
    #[error("invalid file custody receipt claims: {0}")]
    InvalidClaims(&'static str),
    #[error("file custody receipt signature is invalid")]
    InvalidSignature,
    #[error("file custody receipt signer is not bound to its modern node ID")]
    InvalidSignerBinding,
    #[error("file custody receipt origin is not bound to its modern node ID")]
    InvalidOriginBinding,
    #[error("file custody receipt signer failed: {0}")]
    Signer(String),
    #[error("file custody receipt custodian does not match the authenticated peer")]
    UnexpectedCustodian,
    #[error("file custody receipt origin does not match the expected transfer sender")]
    UnexpectedOrigin,
    #[error("file custody receipt recipient does not match the expected transfer recipient")]
    UnexpectedRecipient,
    #[error("file custody receipt is not active at the supplied time")]
    NotActive,
}

impl SignedFileCustodyReceiptV1 {
    pub fn canonical_signing_bytes(&self) -> Result<Vec<u8>, FileCustodyReceiptError> {
        validate_claims(
            &self.origin_node_id,
            &self.custodian_node_id,
            &self.claims,
            &self.custodian_ed25519_public_key,
        )?;
        canonical_signing_bytes(
            &self.origin_node_id,
            &self.custodian_node_id,
            &self.claims,
            &self.custodian_ed25519_public_key,
        )
    }

    pub fn encode(&self) -> Result<Vec<u8>, FileCustodyReceiptError> {
        self.verify_self_signature()?;
        let mut payload = canonical_payload_without_signature(
            &self.origin_node_id,
            &self.custodian_node_id,
            &self.claims,
            &self.custodian_ed25519_public_key,
        )?;
        payload.extend_from_slice(&self.signature);
        let total = FILE_CUSTODY_RECEIPT_HEADER_BYTES
            .checked_add(payload.len())
            .ok_or(FileCustodyReceiptError::LengthOverflow)?;
        if total > MAX_FILE_CUSTODY_RECEIPT_BYTES {
            return Err(FileCustodyReceiptError::TooLarge { size: total });
        }
        let payload_len = u32::try_from(payload.len())
            .map_err(|_| FileCustodyReceiptError::LengthOverflow)?;
        let mut encoded = Vec::with_capacity(total);
        encoded.extend_from_slice(&FILE_CUSTODY_RECEIPT_MAGIC);
        encoded.push(FILE_CUSTODY_RECEIPT_VERSION_V1);
        encoded.push(FILE_CUSTODY_RECEIPT_TYPE_STORED);
        encoded.extend_from_slice(&FILE_CUSTODY_RECEIPT_FLAGS_V1.to_be_bytes());
        encoded.extend_from_slice(&payload_len.to_be_bytes());
        encoded.extend_from_slice(&payload);
        Ok(encoded)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, FileCustodyReceiptError> {
        if bytes.len() < FILE_CUSTODY_RECEIPT_HEADER_BYTES {
            return Err(FileCustodyReceiptError::TruncatedHeader {
                actual: bytes.len(),
            });
        }
        if bytes[..4] != FILE_CUSTODY_RECEIPT_MAGIC {
            return Err(FileCustodyReceiptError::InvalidMagic);
        }
        if bytes[4] != FILE_CUSTODY_RECEIPT_VERSION_V1 {
            return Err(FileCustodyReceiptError::UnsupportedVersion { got: bytes[4] });
        }
        if bytes[5] != FILE_CUSTODY_RECEIPT_TYPE_STORED {
            return Err(FileCustodyReceiptError::UnsupportedType { got: bytes[5] });
        }
        let flags = u16::from_be_bytes(
            bytes[6..8]
                .try_into()
                .map_err(|_| FileCustodyReceiptError::Malformed)?,
        );
        if flags != FILE_CUSTODY_RECEIPT_FLAGS_V1 {
            return Err(FileCustodyReceiptError::UnsupportedFlags { got: flags });
        }
        let payload_len = usize::try_from(u32::from_be_bytes(
            bytes[8..12]
                .try_into()
                .map_err(|_| FileCustodyReceiptError::Malformed)?,
        ))
        .map_err(|_| FileCustodyReceiptError::LengthOverflow)?;
        let expected = FILE_CUSTODY_RECEIPT_HEADER_BYTES
            .checked_add(payload_len)
            .ok_or(FileCustodyReceiptError::LengthOverflow)?;
        if expected > MAX_FILE_CUSTODY_RECEIPT_BYTES {
            return Err(FileCustodyReceiptError::TooLarge { size: expected });
        }
        if bytes.len() != expected {
            return Err(FileCustodyReceiptError::LengthMismatch);
        }
        if payload_len < FIXED_PAYLOAD_BYTES + MIN_NODE_ID_BYTES * 3 {
            return Err(FileCustodyReceiptError::Malformed);
        }
        let mut cursor = ReceiptCursor::new(&bytes[FILE_CUSTODY_RECEIPT_HEADER_BYTES..]);
        let custodian_ed25519_public_key = cursor.array::<ED25519_PUBLIC_KEY_SIZE>()?;
        let origin_node_id = cursor.node_id()?;
        let custodian_node_id = cursor.node_id()?;
        let recipient_node_id = cursor.node_id()?;
        let origin_ed25519_public_key = cursor.array::<ED25519_PUBLIC_KEY_SIZE>()?;
        let transfer_id = cursor.array::<16>()?;
        let chunk_index = cursor.u64()?;
        let chunk_offset = cursor.u32()?;
        let ciphertext_chunk_len = cursor.u32()?;
        let ciphertext_len = cursor.u32()?;
        let ciphertext_digest = cursor.array::<32>()?;
        let stored_at_ms = cursor.i64()?;
        let expires_at_ms = cursor.i64()?;
        let signature = cursor.array::<SIGNATURE_SIZE>()?;
        if cursor.remaining() != 0 {
            return Err(FileCustodyReceiptError::Malformed);
        }
        let receipt = Self {
            origin_node_id,
            custodian_node_id,
            claims: FileCustodyReceiptClaimsV1 {
                range_id: FileCustodyRangeId {
                    origin_ed25519_public_key,
                    recipient_node_id,
                    transfer_id,
                    chunk_index,
                    chunk_offset,
                },
                ciphertext_chunk_len,
                ciphertext_len,
                ciphertext_digest,
                stored_at_ms,
                expires_at_ms,
            },
            custodian_ed25519_public_key,
            signature,
        };
        receipt.verify_self_signature()?;
        Ok(receipt)
    }

    pub fn verify_active_at(
        &self,
        expected_custodian_node_id: &str,
        expected_custodian_public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
        expected_origin_node_id: &str,
        expected_origin_public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
        expected_recipient_node_id: &str,
        now_ms: i64,
    ) -> Result<(), FileCustodyReceiptError> {
        self.verify_self_signature()?;
        if self.custodian_node_id != expected_custodian_node_id
            || &self.custodian_ed25519_public_key != expected_custodian_public_key
        {
            return Err(FileCustodyReceiptError::UnexpectedCustodian);
        }
        if self.origin_node_id != expected_origin_node_id
            || &self.claims.range_id.origin_ed25519_public_key != expected_origin_public_key
        {
            return Err(FileCustodyReceiptError::UnexpectedOrigin);
        }
        if self.claims.range_id.recipient_node_id != expected_recipient_node_id {
            return Err(FileCustodyReceiptError::UnexpectedRecipient);
        }
        if now_ms
            < self
                .claims
                .stored_at_ms
                .saturating_sub(MAX_FILE_CONTROL_CLOCK_SKEW_MS)
            || now_ms > self.claims.expires_at_ms
        {
            return Err(FileCustodyReceiptError::NotActive);
        }
        Ok(())
    }

    pub fn verify_self_signature(&self) -> Result<(), FileCustodyReceiptError> {
        let payload = self.canonical_signing_bytes()?;
        Ed25519KeyPair::verify(
            &self.custodian_ed25519_public_key,
            &payload,
            &self.signature,
        )
        .map_err(|_| FileCustodyReceiptError::InvalidSignature)
    }
}

pub fn sign_file_custody_receipt_v1(
    origin_node_id: String,
    custodian_node_id: String,
    claims: FileCustodyReceiptClaimsV1,
    identity: &Ed25519KeyPair,
) -> Result<SignedFileCustodyReceiptV1, FileCustodyReceiptError> {
    sign_file_custody_receipt_with_signer_v1(
        origin_node_id,
        custodian_node_id,
        claims,
        identity,
    )
}

pub(crate) fn sign_file_custody_receipt_with_signer_v1<S: FileControlSigner + ?Sized>(
    origin_node_id: String,
    custodian_node_id: String,
    claims: FileCustodyReceiptClaimsV1,
    identity: &S,
) -> Result<SignedFileCustodyReceiptV1, FileCustodyReceiptError> {
    let custodian_ed25519_public_key = identity
        .file_control_public_key()
        .map_err(|error| FileCustodyReceiptError::Signer(error.to_string()))?;
    validate_claims(
        &origin_node_id,
        &custodian_node_id,
        &claims,
        &custodian_ed25519_public_key,
    )?;
    let payload = canonical_signing_bytes(
        &origin_node_id,
        &custodian_node_id,
        &claims,
        &custodian_ed25519_public_key,
    )?;
    let signature = identity
        .sign_file_control_payload(&payload)
        .map_err(|error| FileCustodyReceiptError::Signer(error.to_string()))?;
    let receipt = SignedFileCustodyReceiptV1 {
        origin_node_id,
        custodian_node_id,
        claims,
        custodian_ed25519_public_key,
        signature,
    };
    receipt.verify_self_signature()?;
    Ok(receipt)
}

fn validate_claims(
    origin_node_id: &str,
    custodian_node_id: &str,
    claims: &FileCustodyReceiptClaimsV1,
    custodian_public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
) -> Result<(), FileCustodyReceiptError> {
    if !is_canonical_node_id(origin_node_id)
        || !is_canonical_node_id(custodian_node_id)
        || !is_canonical_node_id(&claims.range_id.recipient_node_id)
        || origin_node_id == custodian_node_id
        || origin_node_id == claims.range_id.recipient_node_id
        || custodian_node_id == claims.range_id.recipient_node_id
    {
        return Err(FileCustodyReceiptError::InvalidClaims(
            "origin, custodian and recipient must be distinct canonical node IDs",
        ));
    }
    validate_modern_binding(origin_node_id, &claims.range_id.origin_ed25519_public_key)
        .map_err(|_| FileCustodyReceiptError::InvalidOriginBinding)?;
    validate_modern_binding(custodian_node_id, custodian_public_key)
        .map_err(|_| FileCustodyReceiptError::InvalidSignerBinding)?;
    if claims.range_id.transfer_id.iter().all(|byte| *byte == 0) {
        return Err(FileCustodyReceiptError::InvalidClaims(
            "transfer ID is all zero",
        ));
    }
    let min_chunk_len = FILE_CHUNK_TAG_BYTES
        .checked_add(1)
        .ok_or(FileCustodyReceiptError::LengthOverflow)?;
    let max_chunk_len = usize::try_from(MAX_FILE_CHUNK_BYTES)
        .map_err(|_| FileCustodyReceiptError::LengthOverflow)?
        .checked_add(FILE_CHUNK_TAG_BYTES)
        .ok_or(FileCustodyReceiptError::LengthOverflow)?;
    let chunk_len = usize::try_from(claims.ciphertext_chunk_len)
        .map_err(|_| FileCustodyReceiptError::LengthOverflow)?;
    let range_len = usize::try_from(claims.ciphertext_len)
        .map_err(|_| FileCustodyReceiptError::LengthOverflow)?;
    let range_end = usize::try_from(claims.range_id.chunk_offset)
        .map_err(|_| FileCustodyReceiptError::LengthOverflow)?
        .checked_add(range_len)
        .ok_or(FileCustodyReceiptError::LengthOverflow)?;
    if !(min_chunk_len..=max_chunk_len).contains(&chunk_len)
        || range_len == 0
        || range_len > MAX_FILE_CHUNK_DATA_BYTES
        || range_end > chunk_len
    {
        return Err(FileCustodyReceiptError::InvalidClaims(
            "invalid encrypted chunk/range geometry",
        ));
    }
    let ttl = claims.expires_at_ms.saturating_sub(claims.stored_at_ms);
    if claims.stored_at_ms < 0
        || ttl < FILE_CUSTODY_MIN_TTL_MS
        || ttl > FILE_CUSTODY_MAX_TTL_MS
    {
        return Err(FileCustodyReceiptError::InvalidClaims(
            "invalid absolute custody TTL",
        ));
    }
    Ok(())
}

fn canonical_signing_bytes(
    origin_node_id: &str,
    custodian_node_id: &str,
    claims: &FileCustodyReceiptClaimsV1,
    custodian_public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
) -> Result<Vec<u8>, FileCustodyReceiptError> {
    let payload = canonical_payload_without_signature(
        origin_node_id,
        custodian_node_id,
        claims,
        custodian_public_key,
    )?;
    let capacity = FILE_CUSTODY_RECEIPT_DOMAIN_V1
        .len()
        .checked_add(payload.len())
        .ok_or(FileCustodyReceiptError::LengthOverflow)?;
    let mut signing = Vec::with_capacity(capacity);
    signing.extend_from_slice(FILE_CUSTODY_RECEIPT_DOMAIN_V1);
    signing.extend_from_slice(&payload);
    Ok(signing)
}

fn canonical_payload_without_signature(
    origin_node_id: &str,
    custodian_node_id: &str,
    claims: &FileCustodyReceiptClaimsV1,
    custodian_public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
) -> Result<Vec<u8>, FileCustodyReceiptError> {
    validate_claims(
        origin_node_id,
        custodian_node_id,
        claims,
        custodian_public_key,
    )?;
    let mut payload = Vec::with_capacity(MAX_FILE_CUSTODY_RECEIPT_BYTES);
    payload.extend_from_slice(custodian_public_key);
    push_node_id(&mut payload, origin_node_id)?;
    push_node_id(&mut payload, custodian_node_id)?;
    push_node_id(&mut payload, &claims.range_id.recipient_node_id)?;
    payload.extend_from_slice(&claims.range_id.origin_ed25519_public_key);
    payload.extend_from_slice(&claims.range_id.transfer_id);
    payload.extend_from_slice(&claims.range_id.chunk_index.to_be_bytes());
    payload.extend_from_slice(&claims.range_id.chunk_offset.to_be_bytes());
    payload.extend_from_slice(&claims.ciphertext_chunk_len.to_be_bytes());
    payload.extend_from_slice(&claims.ciphertext_len.to_be_bytes());
    payload.extend_from_slice(&claims.ciphertext_digest);
    payload.extend_from_slice(&claims.stored_at_ms.to_be_bytes());
    payload.extend_from_slice(&claims.expires_at_ms.to_be_bytes());
    Ok(payload)
}

fn push_node_id(
    output: &mut Vec<u8>,
    node_id: &str,
) -> Result<(), FileCustodyReceiptError> {
    if !is_canonical_node_id(node_id) {
        return Err(FileCustodyReceiptError::InvalidClaims(
            "noncanonical node ID",
        ));
    }
    output.push(
        u8::try_from(node_id.len()).map_err(|_| FileCustodyReceiptError::LengthOverflow)?,
    );
    output.extend_from_slice(node_id.as_bytes());
    Ok(())
}

fn validate_modern_binding(
    node_id: &str,
    public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
) -> Result<(), ()> {
    if node_id.len() == MAX_NODE_ID_BYTES {
        let expected = format!("pk_{}", NodeId::from_ed25519_pubkey(public_key).to_hex());
        if node_id != expected {
            return Err(());
        }
    }
    Ok(())
}

fn is_canonical_node_id(node_id: &str) -> bool {
    (node_id.len() == MIN_NODE_ID_BYTES || node_id.len() == MAX_NODE_ID_BYTES)
        && node_id.starts_with("pk_")
        && node_id[3..]
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

struct ReceiptCursor<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> ReceiptCursor<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }

    fn remaining(&self) -> usize {
        self.bytes.len().saturating_sub(self.offset)
    }

    fn bytes(&mut self, count: usize) -> Result<&'a [u8], FileCustodyReceiptError> {
        let end = self
            .offset
            .checked_add(count)
            .ok_or(FileCustodyReceiptError::LengthOverflow)?;
        if end > self.bytes.len() {
            return Err(FileCustodyReceiptError::Malformed);
        }
        let value = &self.bytes[self.offset..end];
        self.offset = end;
        Ok(value)
    }

    fn array<const N: usize>(&mut self) -> Result<[u8; N], FileCustodyReceiptError> {
        self.bytes(N)?
            .try_into()
            .map_err(|_| FileCustodyReceiptError::Malformed)
    }

    fn node_id(&mut self) -> Result<String, FileCustodyReceiptError> {
        let length = usize::from(self.array::<1>()?[0]);
        if length != MIN_NODE_ID_BYTES && length != MAX_NODE_ID_BYTES {
            return Err(FileCustodyReceiptError::Malformed);
        }
        let value = std::str::from_utf8(self.bytes(length)?)
            .map_err(|_| FileCustodyReceiptError::Malformed)?
            .to_owned();
        if !is_canonical_node_id(&value) {
            return Err(FileCustodyReceiptError::Malformed);
        }
        Ok(value)
    }

    fn u32(&mut self) -> Result<u32, FileCustodyReceiptError> {
        Ok(u32::from_be_bytes(self.array::<4>()?))
    }

    fn u64(&mut self) -> Result<u64, FileCustodyReceiptError> {
        Ok(u64::from_be_bytes(self.array::<8>()?))
    }

    fn i64(&mut self) -> Result<i64, FileCustodyReceiptError> {
        Ok(i64::from_be_bytes(self.array::<8>()?))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::signing_identity::{
        InstalledSigningIdentity, IDENTITY_SIGNING_FORMAT_V1,
    };

    const NOW: i64 = 1_900_000_000_000;

    fn identity(byte: u8) -> Ed25519KeyPair {
        Ed25519KeyPair::from_secret_bytes(&[byte; 32]).unwrap()
    }

    fn modern_node(identity: &Ed25519KeyPair) -> String {
        format!("pk_{}", identity.node_id().to_hex())
    }

    fn legacy_node(byte: u8) -> String {
        format!("pk_{}", format!("{byte:02x}").repeat(16))
    }

    fn claims(origin: &Ed25519KeyPair, recipient_node_id: String) -> FileCustodyReceiptClaimsV1 {
        FileCustodyReceiptClaimsV1 {
            range_id: FileCustodyRangeId {
                origin_ed25519_public_key: origin.public_key().0.try_into().unwrap(),
                recipient_node_id,
                transfer_id: [0x44; 16],
                chunk_index: u64::from(u32::MAX) + 9,
                chunk_offset: 32,
            },
            ciphertext_chunk_len: 128,
            ciphertext_len: 64,
            ciphertext_digest: [0xa5; 32],
            stored_at_ms: NOW,
            expires_at_ms: NOW + FILE_CUSTODY_MIN_TTL_MS,
        }
    }

    #[test]
    fn exact_range_receipt_is_canonical_signed_and_pinned() {
        let origin = identity(1);
        let custodian = identity(2);
        let recipient = identity(3);
        let origin_node = modern_node(&origin);
        let custodian_node = modern_node(&custodian);
        let recipient_node = modern_node(&recipient);
        let signed = sign_file_custody_receipt_v1(
            origin_node.clone(),
            custodian_node.clone(),
            claims(&origin, recipient_node.clone()),
            &custodian,
        )
        .unwrap();
        let encoded = signed.encode().unwrap();
        assert!(encoded.len() <= MAX_FILE_CUSTODY_RECEIPT_BYTES);
        let decoded = SignedFileCustodyReceiptV1::decode(&encoded).unwrap();
        assert_eq!(decoded, signed);
        decoded
            .verify_active_at(
                &custodian_node,
                &custodian.public_key().0.try_into().unwrap(),
                &origin_node,
                &origin.public_key().0.try_into().unwrap(),
                &recipient_node,
                NOW + 1,
            )
            .unwrap();
        assert_eq!(decoded.claims.range_id.chunk_index, u64::from(u32::MAX) + 9);
    }

    #[test]
    fn every_truncation_trailing_byte_and_tamper_fail_closed() {
        let origin = identity(1);
        let custodian = identity(2);
        let recipient = identity(3);
        let encoded = sign_file_custody_receipt_v1(
            modern_node(&origin),
            modern_node(&custodian),
            claims(&origin, modern_node(&recipient)),
            &custodian,
        )
        .unwrap()
        .encode()
        .unwrap();
        for end in 0..encoded.len() {
            assert!(SignedFileCustodyReceiptV1::decode(&encoded[..end]).is_err());
        }
        let mut trailing = encoded.clone();
        trailing.push(0);
        assert_eq!(
            SignedFileCustodyReceiptV1::decode(&trailing),
            Err(FileCustodyReceiptError::LengthMismatch)
        );
        let mut tampered = encoded;
        tampered[80] ^= 1;
        assert!(SignedFileCustodyReceiptV1::decode(&tampered).is_err());
    }

    #[test]
    fn wrong_pins_recipient_expiry_and_geometry_are_rejected() {
        let origin = identity(1);
        let custodian = identity(2);
        let recipient = identity(3);
        let origin_node = modern_node(&origin);
        let custodian_node = modern_node(&custodian);
        let recipient_node = modern_node(&recipient);
        let signed = sign_file_custody_receipt_v1(
            origin_node.clone(),
            custodian_node.clone(),
            claims(&origin, recipient_node.clone()),
            &custodian,
        )
        .unwrap();
        assert_eq!(
            signed.verify_active_at(
                &custodian_node,
                &identity(9).public_key().0.try_into().unwrap(),
                &origin_node,
                &origin.public_key().0.try_into().unwrap(),
                &recipient_node,
                NOW,
            ),
            Err(FileCustodyReceiptError::UnexpectedCustodian)
        );
        assert_eq!(
            signed.verify_active_at(
                &custodian_node,
                &custodian.public_key().0.try_into().unwrap(),
                &origin_node,
                &origin.public_key().0.try_into().unwrap(),
                &modern_node(&identity(9)),
                NOW,
            ),
            Err(FileCustodyReceiptError::UnexpectedRecipient)
        );
        assert_eq!(
            signed.verify_active_at(
                &custodian_node,
                &custodian.public_key().0.try_into().unwrap(),
                &origin_node,
                &origin.public_key().0.try_into().unwrap(),
                &recipient_node,
                NOW + FILE_CUSTODY_MIN_TTL_MS + 1,
            ),
            Err(FileCustodyReceiptError::NotActive)
        );
        let mut invalid = claims(&origin, recipient_node);
        invalid.range_id.chunk_offset = 100;
        assert!(matches!(
            sign_file_custody_receipt_v1(
                origin_node,
                custodian_node,
                invalid,
                &custodian,
            ),
            Err(FileCustodyReceiptError::InvalidClaims(_))
        ));
    }

    #[test]
    fn installed_identity_signs_without_exporting_private_seed() {
        let origin = identity(1);
        let installed = InstalledSigningIdentity::from_seed(
            IDENTITY_SIGNING_FORMAT_V1,
            legacy_node(2),
            &[2; 32],
        )
        .unwrap();
        let recipient_node = legacy_node(3);
        let receipt = sign_file_custody_receipt_with_signer_v1(
            modern_node(&origin),
            installed.legacy_routing_node_id().to_owned(),
            claims(&origin, recipient_node.clone()),
            &installed,
        )
        .unwrap();
        receipt
            .verify_active_at(
                installed.legacy_routing_node_id(),
                &installed.public_key().try_into().unwrap(),
                &modern_node(&origin),
                &origin.public_key().0.try_into().unwrap(),
                &recipient_node,
                NOW,
            )
            .unwrap();
    }
}
