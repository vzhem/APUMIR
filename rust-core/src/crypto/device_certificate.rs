//! Device certificates for one account on several devices (P1 of
//! `docs/MULTI_DEVICE_IDENTITY.md`).
//!
//! The account key (AK) signs a certificate for every device key (DK). Receivers pin the
//! account key of a legacy routing id, never a device key. A revocation is also signed by
//! the account key. Nothing in this module is wired into the live wire protocol yet.

use crate::crypto::keys::Ed25519KeyPair;
use crate::crypto::signing_identity::is_legacy_routing_node_id;

pub const DEVICE_CERT_FORMAT_V1: u8 = 1;
pub const DEVICE_ID_BYTES: usize = 16;
const DEVICE_CERT_DOMAIN_V1: &[u8] = b"apu-device-certificate-v1\0";
const DEVICE_REVOCATION_DOMAIN_V1: &[u8] = b"apu-device-revocation-v1\0";
const ED25519_PUBLIC_KEY_BYTES: usize = 32;
const ED25519_SIGNATURE_BYTES: usize = 64;
const MAX_LEGACY_ROUTING_ID_BYTES: usize = 67;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceCertificateV1 {
    pub legacy_routing_node_id: String,
    pub account_public_key: Vec<u8>,
    pub device_id: [u8; DEVICE_ID_BYTES],
    pub device_public_key: Vec<u8>,
    pub created_at_ms: i64,
    pub signature: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceRevocationV1 {
    pub legacy_routing_node_id: String,
    pub account_public_key: Vec<u8>,
    pub device_id: [u8; DEVICE_ID_BYTES],
    pub revoked_at_ms: i64,
    pub signature: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum DeviceCertError {
    #[error("unsupported device certificate format {0}")]
    UnsupportedFormat(u8),
    #[error("malformed device certificate")]
    Malformed,
    #[error("invalid device key")]
    InvalidKey,
    #[error("device certificate account key does not match the pinned account key")]
    AccountKeyMismatch,
    #[error("device certificate signature is invalid")]
    InvalidSignature,
    #[error("artifact was signed before the device certificate was issued")]
    ArtifactBeforeCertificate,
    #[error("device is revoked for this artifact time")]
    Revoked,
    #[error("revocation does not match the device certificate")]
    RevocationMismatch,
}

fn validate_common(
    legacy_routing_node_id: &str,
    account_public_key: &[u8],
    device_public_key: Option<&[u8]>,
    time_ms: i64,
) -> Result<(), DeviceCertError> {
    if !is_legacy_routing_node_id(legacy_routing_node_id)
        || legacy_routing_node_id.len() > MAX_LEGACY_ROUTING_ID_BYTES
        || account_public_key.len() != ED25519_PUBLIC_KEY_BYTES
        || time_ms < 0
    {
        return Err(DeviceCertError::Malformed);
    }
    if let Some(device_key) = device_public_key {
        if device_key.len() != ED25519_PUBLIC_KEY_BYTES || device_key.iter().all(|b| *b == 0) {
            return Err(DeviceCertError::InvalidKey);
        }
    }
    Ok(())
}

fn certificate_canonical_bytes(cert: &DeviceCertificateV1) -> Result<Vec<u8>, DeviceCertError> {
    validate_common(
        &cert.legacy_routing_node_id,
        &cert.account_public_key,
        Some(&cert.device_public_key),
        cert.created_at_ms,
    )?;
    let legacy = cert.legacy_routing_node_id.as_bytes();
    let mut out = Vec::with_capacity(
        DEVICE_CERT_DOMAIN_V1.len() + 1 + 2 + legacy.len() + 32 + DEVICE_ID_BYTES + 32 + 8,
    );
    out.extend_from_slice(DEVICE_CERT_DOMAIN_V1);
    out.push(DEVICE_CERT_FORMAT_V1);
    out.extend_from_slice(&(legacy.len() as u16).to_be_bytes());
    out.extend_from_slice(legacy);
    out.extend_from_slice(&cert.account_public_key);
    out.extend_from_slice(&cert.device_id);
    out.extend_from_slice(&cert.device_public_key);
    out.extend_from_slice(&cert.created_at_ms.to_be_bytes());
    Ok(out)
}

fn revocation_canonical_bytes(rev: &DeviceRevocationV1) -> Result<Vec<u8>, DeviceCertError> {
    validate_common(
        &rev.legacy_routing_node_id,
        &rev.account_public_key,
        None,
        rev.revoked_at_ms,
    )?;
    let legacy = rev.legacy_routing_node_id.as_bytes();
    let mut out = Vec::with_capacity(
        DEVICE_REVOCATION_DOMAIN_V1.len() + 1 + 2 + legacy.len() + 32 + DEVICE_ID_BYTES + 8,
    );
    out.extend_from_slice(DEVICE_REVOCATION_DOMAIN_V1);
    out.push(DEVICE_CERT_FORMAT_V1);
    out.extend_from_slice(&(legacy.len() as u16).to_be_bytes());
    out.extend_from_slice(legacy);
    out.extend_from_slice(&rev.account_public_key);
    out.extend_from_slice(&rev.device_id);
    out.extend_from_slice(&rev.revoked_at_ms.to_be_bytes());
    Ok(out)
}

/// Issue a certificate. Only the primary device holds the account key.
pub fn issue_device_certificate(
    account_key: &Ed25519KeyPair,
    legacy_routing_node_id: &str,
    device_id: [u8; DEVICE_ID_BYTES],
    device_public_key: &[u8],
    created_at_ms: i64,
) -> Result<DeviceCertificateV1, DeviceCertError> {
    let mut cert = DeviceCertificateV1 {
        legacy_routing_node_id: legacy_routing_node_id.to_string(),
        account_public_key: account_key.public_key().0.to_vec(),
        device_id,
        device_public_key: device_public_key.to_vec(),
        created_at_ms,
        signature: Vec::new(),
    };
    let canonical = certificate_canonical_bytes(&cert)?;
    cert.signature = account_key.sign(&canonical);
    Ok(cert)
}

/// Issue a revocation for a device of the same account.
pub fn issue_device_revocation(
    account_key: &Ed25519KeyPair,
    legacy_routing_node_id: &str,
    device_id: [u8; DEVICE_ID_BYTES],
    revoked_at_ms: i64,
) -> Result<DeviceRevocationV1, DeviceCertError> {
    let mut rev = DeviceRevocationV1 {
        legacy_routing_node_id: legacy_routing_node_id.to_string(),
        account_public_key: account_key.public_key().0.to_vec(),
        device_id,
        revoked_at_ms,
        signature: Vec::new(),
    };
    let canonical = revocation_canonical_bytes(&rev)?;
    rev.signature = account_key.sign(&canonical);
    Ok(rev)
}

/// Public key of a device key seed. The seed is never stored in Rust; the caller zeroes it.
pub fn device_public_key_from_seed(seed: &[u8]) -> Result<Vec<u8>, DeviceCertError> {
    let key = Ed25519KeyPair::from_secret_bytes(seed).map_err(|_| DeviceCertError::InvalidKey)?;
    Ok(key.public_key().0.to_vec())
}

/// Check the certificate against the account key that the receiver pinned for the id.
pub fn verify_device_certificate(
    cert: &DeviceCertificateV1,
    pinned_account_public_key: &[u8],
) -> Result<(), DeviceCertError> {
    let canonical = certificate_canonical_bytes(cert)?;
    if cert.account_public_key != pinned_account_public_key {
        return Err(DeviceCertError::AccountKeyMismatch);
    }
    if cert.signature.len() != ED25519_SIGNATURE_BYTES {
        return Err(DeviceCertError::Malformed);
    }
    Ed25519KeyPair::verify(&cert.account_public_key, &canonical, &cert.signature)
        .map_err(|_| DeviceCertError::InvalidSignature)
}

fn verify_revocation(
    rev: &DeviceRevocationV1,
    cert: &DeviceCertificateV1,
) -> Result<(), DeviceCertError> {
    let canonical = revocation_canonical_bytes(rev)?;
    if rev.legacy_routing_node_id != cert.legacy_routing_node_id
        || rev.account_public_key != cert.account_public_key
        || rev.device_id != cert.device_id
    {
        return Err(DeviceCertError::RevocationMismatch);
    }
    if rev.signature.len() != ED25519_SIGNATURE_BYTES {
        return Err(DeviceCertError::Malformed);
    }
    Ed25519KeyPair::verify(&rev.account_public_key, &canonical, &rev.signature)
        .map_err(|_| DeviceCertError::InvalidSignature)
}

/// Full receiver-side decision for one device-signed artifact:
/// 1. certificate is signed by the pinned account key;
/// 2. artifact time is not before certificate issuance;
/// 3. if revoked, the artifact must have been signed before `revoked_at_ms`;
/// 4. the artifact signature verifies with the device key from the certificate.
pub fn verify_device_artifact(
    cert: &DeviceCertificateV1,
    pinned_account_public_key: &[u8],
    revocation: Option<&DeviceRevocationV1>,
    artifact_signed_at_ms: i64,
    artifact_bytes: &[u8],
    artifact_signature: &[u8],
) -> Result<(), DeviceCertError> {
    verify_device_certificate(cert, pinned_account_public_key)?;
    if artifact_signed_at_ms < cert.created_at_ms {
        return Err(DeviceCertError::ArtifactBeforeCertificate);
    }
    if let Some(rev) = revocation {
        verify_revocation(rev, cert)?;
        if artifact_signed_at_ms >= rev.revoked_at_ms {
            return Err(DeviceCertError::Revoked);
        }
    }
    if artifact_signature.len() != ED25519_SIGNATURE_BYTES {
        return Err(DeviceCertError::InvalidSignature);
    }
    Ed25519KeyPair::verify(&cert.device_public_key, artifact_bytes, artifact_signature)
        .map_err(|_| DeviceCertError::InvalidSignature)
}

impl DeviceCertificateV1 {
    /// Wire form: format | u16 legacy len | legacy | account pk | device id | device pk | created | signature.
    pub fn to_bytes(&self) -> Result<Vec<u8>, DeviceCertError> {
        certificate_canonical_bytes(self)?;
        if self.signature.len() != ED25519_SIGNATURE_BYTES {
            return Err(DeviceCertError::Malformed);
        }
        let legacy = self.legacy_routing_node_id.as_bytes();
        let mut out = Vec::new();
        out.push(DEVICE_CERT_FORMAT_V1);
        out.extend_from_slice(&(legacy.len() as u16).to_be_bytes());
        out.extend_from_slice(legacy);
        out.extend_from_slice(&self.account_public_key);
        out.extend_from_slice(&self.device_id);
        out.extend_from_slice(&self.device_public_key);
        out.extend_from_slice(&self.created_at_ms.to_be_bytes());
        out.extend_from_slice(&self.signature);
        Ok(out)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, DeviceCertError> {
        if bytes.is_empty() {
            return Err(DeviceCertError::Malformed);
        }
        if bytes[0] != DEVICE_CERT_FORMAT_V1 {
            return Err(DeviceCertError::UnsupportedFormat(bytes[0]));
        }
        if bytes.len() < 3 {
            return Err(DeviceCertError::Malformed);
        }
        let legacy_len = u16::from_be_bytes([bytes[1], bytes[2]]) as usize;
        if legacy_len == 0 || legacy_len > MAX_LEGACY_ROUTING_ID_BYTES {
            return Err(DeviceCertError::Malformed);
        }
        let expected = 3 + legacy_len + 32 + DEVICE_ID_BYTES + 32 + 8 + ED25519_SIGNATURE_BYTES;
        if bytes.len() != expected {
            return Err(DeviceCertError::Malformed);
        }
        let mut pos = 3;
        let legacy = std::str::from_utf8(&bytes[pos..pos + legacy_len])
            .map_err(|_| DeviceCertError::Malformed)?
            .to_string();
        pos += legacy_len;
        let account = bytes[pos..pos + 32].to_vec();
        pos += 32;
        let mut device_id = [0u8; DEVICE_ID_BYTES];
        device_id.copy_from_slice(&bytes[pos..pos + DEVICE_ID_BYTES]);
        pos += DEVICE_ID_BYTES;
        let device_pk = bytes[pos..pos + 32].to_vec();
        pos += 32;
        let mut created = [0u8; 8];
        created.copy_from_slice(&bytes[pos..pos + 8]);
        pos += 8;
        let signature = bytes[pos..pos + ED25519_SIGNATURE_BYTES].to_vec();
        let cert = Self {
            legacy_routing_node_id: legacy,
            account_public_key: account,
            device_id,
            device_public_key: device_pk,
            created_at_ms: i64::from_be_bytes(created),
            signature,
        };
        certificate_canonical_bytes(&cert)?;
        Ok(cert)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const LEGACY: &str = "pk_0123456789abcdef0123456789abcdef";

    fn account() -> Ed25519KeyPair {
        Ed25519KeyPair::from_secret_bytes(&[21u8; 32]).unwrap()
    }

    fn device_pk(seed: u8) -> Vec<u8> {
        Ed25519KeyPair::from_secret_bytes(&[seed; 32])
            .unwrap()
            .public_key()
            .0
            .to_vec()
    }

    fn pinned() -> Vec<u8> {
        account().public_key().0.to_vec()
    }

    fn cert(created: i64) -> DeviceCertificateV1 {
        issue_device_certificate(&account(), LEGACY, [9u8; 16], &device_pk(40), created).unwrap()
    }

    #[test]
    fn issued_certificate_verifies_against_pinned_account_key() {
        assert_eq!(verify_device_certificate(&cert(1_000), &pinned()), Ok(()));
    }

    #[test]
    fn certificate_from_another_account_is_rejected() {
        let other = Ed25519KeyPair::from_secret_bytes(&[22u8; 32]).unwrap();
        let other_pinned = other.public_key().0.to_vec();
        assert_eq!(
            verify_device_certificate(&cert(1_000), &other_pinned),
            Err(DeviceCertError::AccountKeyMismatch)
        );
    }

    #[test]
    fn device_cannot_certify_itself() {
        let device = Ed25519KeyPair::from_secret_bytes(&[40u8; 32]).unwrap();
        let self_cert =
            issue_device_certificate(&device, LEGACY, [9u8; 16], &device_pk(40), 1_000).unwrap();
        assert_eq!(
            verify_device_certificate(&self_cert, &pinned()),
            Err(DeviceCertError::AccountKeyMismatch)
        );
    }

    #[test]
    fn tampered_device_key_breaks_signature() {
        let mut bad = cert(1_000);
        bad.device_public_key = device_pk(41);
        assert_eq!(
            verify_device_certificate(&bad, &pinned()),
            Err(DeviceCertError::InvalidSignature)
        );
    }

    #[test]
    fn certificate_bytes_round_trip() {
        let original = cert(1_000);
        let bytes = original.to_bytes().unwrap();
        assert_eq!(DeviceCertificateV1::from_bytes(&bytes).unwrap(), original);
        assert!(matches!(
            DeviceCertificateV1::from_bytes(&bytes[..bytes.len() - 1]),
            Err(DeviceCertError::Malformed)
        ));
        let mut wrong_format = bytes.clone();
        wrong_format[0] = 9;
        assert_eq!(
            DeviceCertificateV1::from_bytes(&wrong_format),
            Err(DeviceCertError::UnsupportedFormat(9))
        );
    }

    #[test]
    fn device_public_key_from_seed_matches_keypair() {
        let seed = [40u8; 32];
        let pk = device_public_key_from_seed(&seed).unwrap();
        assert_eq!(pk, device_pk(40));
        assert_eq!(device_public_key_from_seed(&[1u8; 31]), Err(DeviceCertError::InvalidKey));
    }

    #[test]
    fn artifact_from_device_is_accepted_after_issue() {
        let device = Ed25519KeyPair::from_secret_bytes(&[40u8; 32]).unwrap();
        let msg = b"hello from phone pc";
        let sig = device.sign(msg);
        assert_eq!(
            verify_device_artifact(&cert(1_000), &pinned(), None, 1_500, msg, &sig),
            Ok(())
        );
    }

    #[test]
    fn artifact_before_certificate_is_rejected() {
        let device = Ed25519KeyPair::from_secret_bytes(&[40u8; 32]).unwrap();
        let msg = b"too early";
        let sig = device.sign(msg);
        assert_eq!(
            verify_device_artifact(&cert(1_000), &pinned(), None, 999, msg, &sig),
            Err(DeviceCertError::ArtifactBeforeCertificate)
        );
    }

    #[test]
    fn revocation_cuts_only_later_artifacts() {
        let device = Ed25519KeyPair::from_secret_bytes(&[40u8; 32]).unwrap();
        let msg = b"after revoke";
        let sig = device.sign(msg);
        let rev = issue_device_revocation(&account(), LEGACY, [9u8; 16], 2_000).unwrap();
        assert_eq!(
            verify_device_artifact(&cert(1_000), &pinned(), Some(&rev), 1_999, msg, &sig),
            Ok(())
        );
        assert_eq!(
            verify_device_artifact(&cert(1_000), &pinned(), Some(&rev), 2_000, msg, &sig),
            Err(DeviceCertError::Revoked)
        );
    }

    #[test]
    fn revocation_for_other_device_is_rejected() {
        let device = Ed25519KeyPair::from_secret_bytes(&[40u8; 32]).unwrap();
        let msg = b"x";
        let sig = device.sign(msg);
        let rev = issue_device_revocation(&account(), LEGACY, [8u8; 16], 2_000).unwrap();
        assert_eq!(
            verify_device_artifact(&cert(1_000), &pinned(), Some(&rev), 3_000, msg, &sig),
            Err(DeviceCertError::RevocationMismatch)
        );
    }

    #[test]
    fn artifact_signed_by_other_key_is_rejected() {
        let other = Ed25519KeyPair::from_secret_bytes(&[41u8; 32]).unwrap();
        let msg = b"forged";
        let sig = other.sign(msg);
        assert_eq!(
            verify_device_artifact(&cert(1_000), &pinned(), None, 1_500, msg, &sig),
            Err(DeviceCertError::InvalidSignature)
        );
    }
}
