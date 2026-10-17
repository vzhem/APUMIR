//! Receiver side of `identity-binding v2` and the device certificate cache (P4 of
//! `docs/MULTI_DEVICE_IDENTITY.md`, section 5).
//!
//! A v2 artifact is signed by a device key (DK) and carries the device certificate (DC) that the
//! account key (AK) issued for that DK. The receiver accepts it only when:
//! 1. the DC is signed by the account key pinned for the sender's routing id;
//! 2. the artifact is not dated in the future (beyond a small clock tolerance);
//! 3. the device is not revoked at the artifact time and the artifact is not older than its DC;
//! 4. the artifact signature verifies with the DK from the DC.
//!
//! Format 1 (`SignedIdentityBindingV1`, account-level) is still accepted unchanged, so devices with
//! and without device certificates can talk. Nothing here is wired into the live receive path yet:
//! that switch belongs to phase M1 of the migration.

use std::collections::HashMap;

use crate::crypto::device_certificate::{
    verify_device_artifact, verify_device_certificate, verify_device_revocation, DeviceCertError,
    DeviceCertificateV1, DeviceRevocationV1, DEVICE_ID_BYTES,
};
use crate::crypto::keys::Ed25519KeyPair;
use crate::crypto::signing_identity::{SignedIdentityBindingV1, IDENTITY_SIGNING_FORMAT_V1};

/// Format byte of the device-signed artifact (`identity-binding v2`).
pub const IDENTITY_BINDING_V2_FORMAT: u8 = 2;
/// How far an artifact time may run ahead of the local clock.
pub const ARTIFACT_CLOCK_TOLERANCE_MS: i64 = 5 * 60 * 1000;

const BINDING_V2_DOMAIN: &[u8] = b"apu-identity-binding-v2\0";
const ED25519_SIGNATURE_BYTES: usize = 64;
const MAX_BODY_BYTES: usize = 64 * 1024;

pub type DeviceId = [u8; DEVICE_ID_BYTES];

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum DeviceBindingError {
    #[error("unsupported identity binding format {0}")]
    UnsupportedFormat(u8),
    #[error("malformed identity binding")]
    Malformed,
    #[error("identity binding is for another routing id")]
    LegacyMismatch,
    #[error("no account key is pinned for this routing id")]
    UnknownAccount,
    #[error("artifact is dated in the future")]
    FutureArtifact,
    #[error("certificate is older than the one already cached for this device")]
    StaleCertificate,
    #[error("device key conflicts with the cached certificate of the same issue time")]
    DeviceKeyConflict,
    #[error("artifact body is too large")]
    BodyTooLarge,
    #[error("account-level identity binding is invalid")]
    InvalidIdentityBinding,
    #[error(transparent)]
    Device(#[from] DeviceCertError),
}

/// Result of a successful check. `device_id` is `None` for format 1, which is account-level.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AcceptedBinding {
    pub legacy_routing_node_id: String,
    pub account_public_key: Vec<u8>,
    pub device_id: Option<DeviceId>,
    pub signed_at_ms: i64,
    pub body: Vec<u8>,
}

/// Certificates and revocations already verified on this device, keyed by routing id and device id.
#[derive(Debug, Default)]
pub struct DeviceCertCache {
    certificates: HashMap<(String, DeviceId), DeviceCertificateV1>,
    revocations: HashMap<(String, DeviceId), DeviceRevocationV1>,
}

impl DeviceCertCache {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.certificates.len()
    }

    pub fn is_empty(&self) -> bool {
        self.certificates.is_empty()
    }

    pub fn certificate(&self, legacy_routing_node_id: &str, device_id: &DeviceId) -> Option<&DeviceCertificateV1> {
        self.certificates.get(&(legacy_routing_node_id.to_string(), *device_id))
    }

    pub fn revocation(&self, legacy_routing_node_id: &str, device_id: &DeviceId) -> Option<&DeviceRevocationV1> {
        self.revocations.get(&(legacy_routing_node_id.to_string(), *device_id))
    }

    /// Record a revocation from the swarm after checking it against the pinned account key.
    /// When several revocations exist for one device, the earliest one wins.
    pub fn accept_revocation(
        &mut self,
        rev: DeviceRevocationV1,
        pinned_account_public_key: &[u8],
    ) -> Result<(), DeviceBindingError> {
        verify_device_revocation(&rev, pinned_account_public_key)?;
        let key = (rev.legacy_routing_node_id.clone(), rev.device_id);
        match self.revocations.get(&key) {
            Some(existing) if existing.revoked_at_ms <= rev.revoked_at_ms => {}
            _ => {
                self.revocations.insert(key, rev);
            }
        }
        Ok(())
    }

    /// Accept an identity binding of either format.
    ///
    /// `pinned_account_public_key` is the account key the caller pinned for
    /// `expected_legacy_routing_node_id`. Format 2 needs it; format 1 checks it when present.
    pub fn accept_binding(
        &mut self,
        bytes: &[u8],
        expected_legacy_routing_node_id: &str,
        pinned_account_public_key: Option<&[u8]>,
        now_ms: i64,
    ) -> Result<AcceptedBinding, DeviceBindingError> {
        match bytes.first().copied() {
            None => Err(DeviceBindingError::Malformed),
            Some(IDENTITY_SIGNING_FORMAT_V1) => {
                accept_v1(bytes, expected_legacy_routing_node_id, pinned_account_public_key)
            }
            Some(IDENTITY_BINDING_V2_FORMAT) => {
                let pinned = pinned_account_public_key.ok_or(DeviceBindingError::UnknownAccount)?;
                self.accept_v2(bytes, expected_legacy_routing_node_id, pinned, now_ms)
            }
            Some(other) => Err(DeviceBindingError::UnsupportedFormat(other)),
        }
    }

    fn accept_v2(
        &mut self,
        bytes: &[u8],
        expected_legacy_routing_node_id: &str,
        pinned_account_public_key: &[u8],
        now_ms: i64,
    ) -> Result<AcceptedBinding, DeviceBindingError> {
        let parsed = parse_v2(bytes)?;
        let cert = DeviceCertificateV1::from_bytes(&parsed.certificate)?;
        if cert.legacy_routing_node_id != expected_legacy_routing_node_id {
            return Err(DeviceBindingError::LegacyMismatch);
        }
        verify_device_certificate(&cert, pinned_account_public_key)?;
        if parsed.signed_at_ms > now_ms.saturating_add(ARTIFACT_CLOCK_TOLERANCE_MS) {
            return Err(DeviceBindingError::FutureArtifact);
        }

        let key = (cert.legacy_routing_node_id.clone(), cert.device_id);
        if let Some(cached) = self.certificates.get(&key) {
            if cert.created_at_ms < cached.created_at_ms {
                return Err(DeviceBindingError::StaleCertificate);
            }
            if cert.created_at_ms == cached.created_at_ms
                && cert.device_public_key != cached.device_public_key
            {
                return Err(DeviceBindingError::DeviceKeyConflict);
            }
        }

        let message = v2_signed_message(&cert.legacy_routing_node_id, parsed.signed_at_ms, &parsed.body);
        verify_device_artifact(
            &cert,
            pinned_account_public_key,
            self.revocations.get(&key),
            parsed.signed_at_ms,
            &message,
            &parsed.signature,
        )?;

        // Cache only after every check has passed, so a rejected artifact cannot poison the cache.
        let accepted = AcceptedBinding {
            legacy_routing_node_id: cert.legacy_routing_node_id.clone(),
            account_public_key: cert.account_public_key.clone(),
            device_id: Some(cert.device_id),
            signed_at_ms: parsed.signed_at_ms,
            body: parsed.body,
        };
        self.certificates.insert(key, cert);
        Ok(accepted)
    }
}

fn accept_v1(
    bytes: &[u8],
    expected_legacy_routing_node_id: &str,
    pinned_account_public_key: Option<&[u8]>,
) -> Result<AcceptedBinding, DeviceBindingError> {
    let binding =
        SignedIdentityBindingV1::from_bytes(bytes).map_err(|_| DeviceBindingError::Malformed)?;
    binding
        .verify()
        .map_err(|_| DeviceBindingError::InvalidIdentityBinding)?;
    if binding.legacy_routing_node_id != expected_legacy_routing_node_id {
        return Err(DeviceBindingError::LegacyMismatch);
    }
    if let Some(pinned) = pinned_account_public_key {
        if pinned != binding.signing_public_key.as_slice() {
            return Err(DeviceBindingError::Device(DeviceCertError::AccountKeyMismatch));
        }
    }
    Ok(AcceptedBinding {
        legacy_routing_node_id: binding.legacy_routing_node_id,
        account_public_key: binding.signing_public_key,
        device_id: None,
        signed_at_ms: binding.created_at_ms,
        body: Vec::new(),
    })
}

struct ParsedV2 {
    certificate: Vec<u8>,
    signed_at_ms: i64,
    body: Vec<u8>,
    signature: Vec<u8>,
}

/// Wire form: format(2) | u16 cert len | cert | i64 signed_at | u32 body len | body | sig(64).
fn parse_v2(bytes: &[u8]) -> Result<ParsedV2, DeviceBindingError> {
    if bytes.len() < 3 || bytes[0] != IDENTITY_BINDING_V2_FORMAT {
        return Err(DeviceBindingError::Malformed);
    }
    let cert_len = u16::from_be_bytes([bytes[1], bytes[2]]) as usize;
    let cert_end = 3usize
        .checked_add(cert_len)
        .ok_or(DeviceBindingError::Malformed)?;
    let header_end = cert_end.checked_add(8 + 4).ok_or(DeviceBindingError::Malformed)?;
    if bytes.len() < header_end + ED25519_SIGNATURE_BYTES {
        return Err(DeviceBindingError::Malformed);
    }
    let mut time = [0u8; 8];
    time.copy_from_slice(&bytes[cert_end..cert_end + 8]);
    let mut body_len_raw = [0u8; 4];
    body_len_raw.copy_from_slice(&bytes[cert_end + 8..header_end]);
    let body_len = u32::from_be_bytes(body_len_raw) as usize;
    if body_len > MAX_BODY_BYTES {
        return Err(DeviceBindingError::BodyTooLarge);
    }
    let body_end = header_end
        .checked_add(body_len)
        .ok_or(DeviceBindingError::Malformed)?;
    if bytes.len() != body_end + ED25519_SIGNATURE_BYTES {
        return Err(DeviceBindingError::Malformed);
    }
    Ok(ParsedV2 {
        certificate: bytes[3..cert_end].to_vec(),
        signed_at_ms: i64::from_be_bytes(time),
        body: bytes[header_end..body_end].to_vec(),
        signature: bytes[body_end..].to_vec(),
    })
}

/// The bytes the device key signs. They bind the routing id, so an artifact cannot be moved to
/// another contact.
fn v2_signed_message(legacy_routing_node_id: &str, signed_at_ms: i64, body: &[u8]) -> Vec<u8> {
    let legacy = legacy_routing_node_id.as_bytes();
    let mut out = Vec::with_capacity(BINDING_V2_DOMAIN.len() + 2 + legacy.len() + 8 + 4 + body.len());
    out.extend_from_slice(BINDING_V2_DOMAIN);
    out.extend_from_slice(&(legacy.len() as u16).to_be_bytes());
    out.extend_from_slice(legacy);
    out.extend_from_slice(&signed_at_ms.to_be_bytes());
    out.extend_from_slice(&(body.len() as u32).to_be_bytes());
    out.extend_from_slice(body);
    out
}

/// Sender side: build a v2 artifact signed by this device's key. The certificate must be for that key.
pub fn encode_identity_binding_v2(
    cert: &DeviceCertificateV1,
    device_key: &Ed25519KeyPair,
    signed_at_ms: i64,
    body: &[u8],
) -> Result<Vec<u8>, DeviceBindingError> {
    if body.len() > MAX_BODY_BYTES {
        return Err(DeviceBindingError::BodyTooLarge);
    }
    if device_key.public_key().0.to_vec() != cert.device_public_key {
        return Err(DeviceBindingError::Device(DeviceCertError::InvalidKey));
    }
    let cert_bytes = cert.to_bytes()?;
    let signature = device_key.sign(&v2_signed_message(
        &cert.legacy_routing_node_id,
        signed_at_ms,
        body,
    ));
    let mut out = Vec::with_capacity(3 + cert_bytes.len() + 8 + 4 + body.len() + signature.len());
    out.push(IDENTITY_BINDING_V2_FORMAT);
    out.extend_from_slice(&(cert_bytes.len() as u16).to_be_bytes());
    out.extend_from_slice(&cert_bytes);
    out.extend_from_slice(&signed_at_ms.to_be_bytes());
    out.extend_from_slice(&(body.len() as u32).to_be_bytes());
    out.extend_from_slice(body);
    out.extend_from_slice(&signature);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::device_certificate::{issue_device_certificate, issue_device_revocation};

    const LEGACY: &str = "pk_0123456789abcdef0123456789abcdef";
    const OTHER_LEGACY: &str = "pk_fedcba9876543210fedcba9876543210";
    const BODY: &[u8] = b"hello from pc";

    fn account() -> Ed25519KeyPair {
        Ed25519KeyPair::from_secret_bytes(&[21u8; 32]).unwrap()
    }

    fn device(seed: u8) -> Ed25519KeyPair {
        Ed25519KeyPair::from_secret_bytes(&[seed; 32]).unwrap()
    }

    fn pinned() -> Vec<u8> {
        account().public_key().0.to_vec()
    }

    fn cert_at(created: i64) -> DeviceCertificateV1 {
        issue_device_certificate(&account(), LEGACY, [9u8; 16], &device(40).public_key().0.to_vec(), created)
            .unwrap()
    }

    fn binding_v2(cert: &DeviceCertificateV1, signed_at: i64) -> Vec<u8> {
        encode_identity_binding_v2(cert, &device(40), signed_at, BODY).unwrap()
    }

    #[test]
    fn accepts_v2_binding_from_pinned_account_and_caches_certificate() {
        let mut cache = DeviceCertCache::new();
        let pk = pinned();
        let accepted = cache
            .accept_binding(&binding_v2(&cert_at(1_000), 2_000), LEGACY, Some(pk.as_slice()), 3_000)
            .unwrap();
        assert_eq!(accepted.device_id, Some([9u8; 16]));
        assert_eq!(accepted.account_public_key, pk);
        assert_eq!(accepted.body, BODY.to_vec());
        assert_eq!(accepted.signed_at_ms, 2_000);
        assert_eq!(cache.len(), 1);
        assert!(cache.certificate(LEGACY, &[9u8; 16]).is_some());
    }

    #[test]
    fn binding_from_another_account_is_rejected() {
        let mut cache = DeviceCertCache::new();
        let other = device(22).public_key().0.to_vec();
        assert_eq!(
            cache.accept_binding(&binding_v2(&cert_at(1_000), 2_000), LEGACY, Some(other.as_slice()), 3_000),
            Err(DeviceBindingError::Device(DeviceCertError::AccountKeyMismatch))
        );
        assert!(cache.is_empty());
    }

    #[test]
    fn v2_without_pinned_account_is_rejected() {
        let mut cache = DeviceCertCache::new();
        assert_eq!(
            cache.accept_binding(&binding_v2(&cert_at(1_000), 2_000), LEGACY, None, 3_000),
            Err(DeviceBindingError::UnknownAccount)
        );
    }

    #[test]
    fn binding_for_another_routing_id_is_rejected() {
        let mut cache = DeviceCertCache::new();
        let pk = pinned();
        assert_eq!(
            cache.accept_binding(&binding_v2(&cert_at(1_000), 2_000), OTHER_LEGACY, Some(pk.as_slice()), 3_000),
            Err(DeviceBindingError::LegacyMismatch)
        );
    }

    #[test]
    fn future_artifact_is_rejected_beyond_tolerance() {
        let mut cache = DeviceCertCache::new();
        let pk = pinned();
        let signed_at = 1_000 + ARTIFACT_CLOCK_TOLERANCE_MS + 1;
        assert_eq!(
            cache.accept_binding(&binding_v2(&cert_at(1_000), signed_at), LEGACY, Some(pk.as_slice()), 1_000),
            Err(DeviceBindingError::FutureArtifact)
        );
    }

    #[test]
    fn artifact_before_certificate_is_rejected() {
        let mut cache = DeviceCertCache::new();
        let pk = pinned();
        assert_eq!(
            cache.accept_binding(&binding_v2(&cert_at(1_000), 999), LEGACY, Some(pk.as_slice()), 3_000),
            Err(DeviceBindingError::Device(DeviceCertError::ArtifactBeforeCertificate))
        );
    }

    #[test]
    fn revocation_cuts_only_later_artifacts() {
        let mut cache = DeviceCertCache::new();
        let pk = pinned();
        let rev = issue_device_revocation(&account(), LEGACY, [9u8; 16], 2_000).unwrap();
        cache.accept_revocation(rev, pk.as_slice()).unwrap();
        assert_eq!(
            cache.accept_binding(&binding_v2(&cert_at(1_000), 2_000), LEGACY, Some(pk.as_slice()), 3_000),
            Err(DeviceBindingError::Device(DeviceCertError::Revoked))
        );
        assert!(cache.accept_binding(&binding_v2(&cert_at(1_000), 1_999), LEGACY, Some(pk.as_slice()), 3_000).is_ok());
    }

    #[test]
    fn revocation_from_another_account_is_refused() {
        let mut cache = DeviceCertCache::new();
        let pk = pinned();
        let other = Ed25519KeyPair::from_secret_bytes(&[22u8; 32]).unwrap();
        let rev = issue_device_revocation(&other, LEGACY, [9u8; 16], 2_000).unwrap();
        assert_eq!(
            cache.accept_revocation(rev, pk.as_slice()),
            Err(DeviceBindingError::Device(DeviceCertError::AccountKeyMismatch))
        );
    }

    #[test]
    fn earliest_revocation_wins() {
        let mut cache = DeviceCertCache::new();
        let pk = pinned();
        let late = issue_device_revocation(&account(), LEGACY, [9u8; 16], 5_000).unwrap();
        let early = issue_device_revocation(&account(), LEGACY, [9u8; 16], 2_000).unwrap();
        cache.accept_revocation(late, pk.as_slice()).unwrap();
        cache.accept_revocation(early, pk.as_slice()).unwrap();
        assert_eq!(cache.revocation(LEGACY, &[9u8; 16]).unwrap().revoked_at_ms, 2_000);
    }

    #[test]
    fn tampered_body_fails_and_is_not_cached() {
        let mut cache = DeviceCertCache::new();
        let pk = pinned();
        let mut bytes = binding_v2(&cert_at(1_000), 2_000);
        let index = bytes.len() - ED25519_SIGNATURE_BYTES - 1;
        bytes[index] ^= 1;
        assert_eq!(
            cache.accept_binding(&bytes, LEGACY, Some(pk.as_slice()), 3_000),
            Err(DeviceBindingError::Device(DeviceCertError::InvalidSignature))
        );
        assert!(cache.is_empty());
    }

    #[test]
    fn truncated_v2_is_malformed() {
        let mut cache = DeviceCertCache::new();
        let pk = pinned();
        let bytes = binding_v2(&cert_at(1_000), 2_000);
        assert_eq!(
            cache.accept_binding(&bytes[..bytes.len() - 1], LEGACY, Some(pk.as_slice()), 3_000),
            Err(DeviceBindingError::Malformed)
        );
    }

    #[test]
    fn older_certificate_than_cached_is_stale() {
        let mut cache = DeviceCertCache::new();
        let pk = pinned();
        cache
            .accept_binding(&binding_v2(&cert_at(2_000), 2_500), LEGACY, Some(pk.as_slice()), 3_000)
            .unwrap();
        assert_eq!(
            cache.accept_binding(&binding_v2(&cert_at(1_000), 2_500), LEGACY, Some(pk.as_slice()), 3_000),
            Err(DeviceBindingError::StaleCertificate)
        );
    }

    #[test]
    fn same_issue_time_with_other_device_key_is_a_conflict() {
        let mut cache = DeviceCertCache::new();
        let pk = pinned();
        cache
            .accept_binding(&binding_v2(&cert_at(1_000), 2_000), LEGACY, Some(pk.as_slice()), 3_000)
            .unwrap();
        let forged_key_cert =
            issue_device_certificate(&account(), LEGACY, [9u8; 16], &device(41).public_key().0.to_vec(), 1_000)
                .unwrap();
        let bytes = encode_identity_binding_v2(&forged_key_cert, &device(41), 2_000, BODY).unwrap();
        assert_eq!(
            cache.accept_binding(&bytes, LEGACY, Some(pk.as_slice()), 3_000),
            Err(DeviceBindingError::DeviceKeyConflict)
        );
    }

    #[test]
    fn encoder_refuses_certificate_of_another_device_key() {
        assert_eq!(
            encode_identity_binding_v2(&cert_at(1_000), &device(41), 2_000, BODY),
            Err(DeviceBindingError::Device(DeviceCertError::InvalidKey))
        );
    }

    #[test]
    fn format_one_binding_still_accepted_and_is_account_level() {
        let mut binding = SignedIdentityBindingV1 {
            legacy_routing_node_id: LEGACY.to_string(),
            signing_public_key: pinned(),
            created_at_ms: 1_000,
            signature: Vec::new(),
        };
        let canonical = binding.canonical_bytes().unwrap();
        binding.signature = account().sign(&canonical);
        let bytes = binding.to_bytes().unwrap();
        let pk = pinned();
        let accepted = DeviceCertCache::new()
            .accept_binding(&bytes, LEGACY, Some(pk.as_slice()), 3_000)
            .unwrap();
        assert_eq!(accepted.device_id, None);
        assert_eq!(accepted.account_public_key, pk);
        assert!(accepted.body.is_empty());
    }

    #[test]
    fn format_one_with_other_pinned_key_is_rejected() {
        let mut binding = SignedIdentityBindingV1 {
            legacy_routing_node_id: LEGACY.to_string(),
            signing_public_key: pinned(),
            created_at_ms: 1_000,
            signature: Vec::new(),
        };
        let canonical = binding.canonical_bytes().unwrap();
        binding.signature = account().sign(&canonical);
        let bytes = binding.to_bytes().unwrap();
        let other = device(22).public_key().0.to_vec();
        assert_eq!(
            DeviceCertCache::new().accept_binding(&bytes, LEGACY, Some(other.as_slice()), 3_000),
            Err(DeviceBindingError::Device(DeviceCertError::AccountKeyMismatch))
        );
    }

    #[test]
    fn unknown_and_empty_formats_are_refused() {
        let mut cache = DeviceCertCache::new();
        assert_eq!(
            cache.accept_binding(&[7, 0, 0], LEGACY, None, 0),
            Err(DeviceBindingError::UnsupportedFormat(7))
        );
        assert_eq!(cache.accept_binding(&[], LEGACY, None, 0), Err(DeviceBindingError::Malformed));
    }
}
