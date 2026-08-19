//! Signed direct-referral claims foundation (R0.5).
//!
//! This module deliberately does not expose a URL/wire token yet. It defines a
//! canonical, domain-separated payload and proves that a referral is signed by
//! the Ed25519 key whose public key derives the declared inviter node ID.
//! Engine/UniFFI wiring comes only after durable identity-key migration exists.

use crate::crypto::keys::{Ed25519KeyPair, NodeId};
use crate::crypto::CryptoError;

pub const REFERRAL_VERSION_V1: u8 = 1;
pub const REFERRAL_NONCE_BYTES: usize = 16;
pub const MAX_REFERRAL_LIFETIME_MS: i64 = 30 * 24 * 60 * 60 * 1_000;
pub const MAX_REFERRAL_CLOCK_SKEW_MS: i64 = 5 * 60 * 1_000;
const REFERRAL_DOMAIN_V1: &[u8] = b"apu-referral-invite-v1\0";
const DIRECT_FRIEND_SCOPE: u8 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReferralInviteClaimsV1 {
    pub inviter_node_id: String,
    pub nonce: [u8; REFERRAL_NONCE_BYTES],
    pub created_at_ms: i64,
    pub expires_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SignedReferralInviteV1 {
    pub claims: ReferralInviteClaimsV1,
    pub inviter_ed25519_public_key: Vec<u8>,
    pub signature: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ReferralInviteError {
    #[error("unsupported referral version {0}")]
    UnsupportedVersion(u8),
    #[error("inviter node id does not match Ed25519 public key")]
    InviterBindingMismatch,
    #[error("invalid referral nonce")]
    InvalidNonce,
    #[error("invalid referral time window")]
    InvalidTimeWindow,
    #[error("referral invite is not active at the supplied time")]
    NotActive,
    #[error("invalid referral signature")]
    InvalidSignature,
}

pub fn inviter_node_id(public_key: &[u8]) -> String {
    format!("pk_{}", NodeId::from_ed25519_pubkey(public_key).to_hex())
}

impl ReferralInviteClaimsV1 {
    /// Exact canonical bytes signed by Ed25519. Never replace this with JSON,
    /// locale-aware formatting, or map iteration.
    pub fn canonical_bytes(&self) -> Result<Vec<u8>, ReferralInviteError> {
        validate_claim_shape(self)?;
        let node = self.inviter_node_id.as_bytes();
        let node_len = u16::try_from(node.len())
            .map_err(|_| ReferralInviteError::InviterBindingMismatch)?;
        let mut out = Vec::with_capacity(
            REFERRAL_DOMAIN_V1.len() + 1 + 1 + 2 + node.len() + REFERRAL_NONCE_BYTES + 16,
        );
        out.extend_from_slice(REFERRAL_DOMAIN_V1);
        out.push(REFERRAL_VERSION_V1);
        out.push(DIRECT_FRIEND_SCOPE);
        out.extend_from_slice(&node_len.to_be_bytes());
        out.extend_from_slice(node);
        out.extend_from_slice(&self.nonce);
        out.extend_from_slice(&self.created_at_ms.to_be_bytes());
        out.extend_from_slice(&self.expires_at_ms.to_be_bytes());
        Ok(out)
    }
}

pub fn sign_referral_invite_v1(
    claims: ReferralInviteClaimsV1,
    identity: &Ed25519KeyPair,
) -> Result<SignedReferralInviteV1, ReferralInviteError> {
    let public_key = identity.public_key().0;
    if claims.inviter_node_id != inviter_node_id(&public_key) {
        return Err(ReferralInviteError::InviterBindingMismatch);
    }
    let payload = claims.canonical_bytes()?;
    let signature = identity.sign(&payload);
    Ok(SignedReferralInviteV1 {
        claims,
        inviter_ed25519_public_key: public_key,
        signature,
    })
}

pub fn verify_referral_invite_v1(
    token: &SignedReferralInviteV1,
    now_ms: i64,
) -> Result<(), ReferralInviteError> {
    validate_claim_shape(&token.claims)?;
    if token.claims.inviter_node_id != inviter_node_id(&token.inviter_ed25519_public_key) {
        return Err(ReferralInviteError::InviterBindingMismatch);
    }
    if now_ms < token.claims.created_at_ms.saturating_sub(MAX_REFERRAL_CLOCK_SKEW_MS)
        || now_ms > token.claims.expires_at_ms
    {
        return Err(ReferralInviteError::NotActive);
    }
    let payload = token.claims.canonical_bytes()?;
    Ed25519KeyPair::verify(
        &token.inviter_ed25519_public_key,
        &payload,
        &token.signature,
    )
    .map_err(map_signature_error)
}

fn validate_claim_shape(claims: &ReferralInviteClaimsV1) -> Result<(), ReferralInviteError> {
    if claims.nonce.iter().all(|byte| *byte == 0) {
        return Err(ReferralInviteError::InvalidNonce);
    }
    if !is_canonical_node_id(&claims.inviter_node_id) {
        return Err(ReferralInviteError::InviterBindingMismatch);
    }
    let lifetime = claims.expires_at_ms.saturating_sub(claims.created_at_ms);
    if claims.created_at_ms < 0 || lifetime <= 0 || lifetime > MAX_REFERRAL_LIFETIME_MS {
        return Err(ReferralInviteError::InvalidTimeWindow);
    }
    Ok(())
}

fn is_canonical_node_id(value: &str) -> bool {
    value.len() == 67
        && value.starts_with("pk_")
        && value[3..].bytes().all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

fn map_signature_error(_error: CryptoError) -> ReferralInviteError {
    ReferralInviteError::InvalidSignature
}

#[cfg(test)]
mod tests {
    use super::*;

    const CREATED: i64 = 1_800_000_000_000;

    fn claims(identity: &Ed25519KeyPair) -> ReferralInviteClaimsV1 {
        ReferralInviteClaimsV1 {
            inviter_node_id: inviter_node_id(identity.public_key().as_bytes()),
            nonce: [0xA5; REFERRAL_NONCE_BYTES],
            created_at_ms: CREATED,
            expires_at_ms: CREATED + 7 * 24 * 60 * 60 * 1_000,
        }
    }

    #[test]
    fn canonical_payload_is_deterministic_and_domain_separated() {
        let identity = Ed25519KeyPair::from_secret_bytes(&[7; 32]).unwrap();
        let claims = claims(&identity);
        let first = claims.canonical_bytes().unwrap();
        let second = claims.canonical_bytes().unwrap();
        assert_eq!(first, second);
        assert!(first.starts_with(REFERRAL_DOMAIN_V1));
        assert_eq!(first[REFERRAL_DOMAIN_V1.len()], REFERRAL_VERSION_V1);
        assert_eq!(first[REFERRAL_DOMAIN_V1.len() + 1], DIRECT_FRIEND_SCOPE);
        assert!(!first.windows(3).any(|window| window == b"sig"));
    }

    #[test]
    fn real_ed25519_round_trip_passes() {
        let identity = Ed25519KeyPair::from_secret_bytes(&[11; 32]).unwrap();
        let token = sign_referral_invite_v1(claims(&identity), &identity).unwrap();
        assert_eq!(token.signature.len(), 64);
        assert!(verify_referral_invite_v1(&token, CREATED + 1_000).is_ok());
    }

    #[test]
    fn modified_claim_fails_signature() {
        let identity = Ed25519KeyPair::from_secret_bytes(&[12; 32]).unwrap();
        let mut token = sign_referral_invite_v1(claims(&identity), &identity).unwrap();
        token.claims.expires_at_ms -= 1;
        assert_eq!(
            verify_referral_invite_v1(&token, CREATED + 1_000),
            Err(ReferralInviteError::InvalidSignature)
        );
    }

    #[test]
    fn wrong_public_key_fails_node_binding() {
        let identity = Ed25519KeyPair::from_secret_bytes(&[13; 32]).unwrap();
        let other = Ed25519KeyPair::from_secret_bytes(&[14; 32]).unwrap();
        let mut token = sign_referral_invite_v1(claims(&identity), &identity).unwrap();
        token.inviter_ed25519_public_key = other.public_key().0;
        assert_eq!(
            verify_referral_invite_v1(&token, CREATED + 1_000),
            Err(ReferralInviteError::InviterBindingMismatch)
        );
    }

    #[test]
    fn malformed_key_and_signature_are_rejected_without_panic() {
        let identity = Ed25519KeyPair::from_secret_bytes(&[15; 32]).unwrap();
        let mut token = sign_referral_invite_v1(claims(&identity), &identity).unwrap();
        token.signature.truncate(63);
        assert_eq!(
            verify_referral_invite_v1(&token, CREATED + 1_000),
            Err(ReferralInviteError::InvalidSignature)
        );
        token.signature = vec![0; 64];
        token.inviter_ed25519_public_key.truncate(31);
        assert_eq!(
            verify_referral_invite_v1(&token, CREATED + 1_000),
            Err(ReferralInviteError::InviterBindingMismatch)
        );
    }

    #[test]
    fn time_nonce_and_lifetime_bounds_are_enforced() {
        let identity = Ed25519KeyPair::from_secret_bytes(&[16; 32]).unwrap();
        let mut invalid = claims(&identity);
        invalid.nonce = [0; REFERRAL_NONCE_BYTES];
        assert_eq!(
            sign_referral_invite_v1(invalid, &identity),
            Err(ReferralInviteError::InvalidNonce)
        );

        let token = sign_referral_invite_v1(claims(&identity), &identity).unwrap();
        assert_eq!(
            verify_referral_invite_v1(&token, token.claims.expires_at_ms + 1),
            Err(ReferralInviteError::NotActive)
        );
        assert_eq!(
            verify_referral_invite_v1(
                &token,
                token.claims.created_at_ms - MAX_REFERRAL_CLOCK_SKEW_MS - 1,
            ),
            Err(ReferralInviteError::NotActive)
        );

        let mut too_long = claims(&identity);
        too_long.expires_at_ms = too_long.created_at_ms + MAX_REFERRAL_LIFETIME_MS + 1;
        assert_eq!(
            sign_referral_invite_v1(too_long, &identity),
            Err(ReferralInviteError::InvalidTimeWindow)
        );
    }

    #[test]
    fn declared_inviter_must_match_signing_identity() {
        let identity = Ed25519KeyPair::from_secret_bytes(&[17; 32]).unwrap();
        let other = Ed25519KeyPair::from_secret_bytes(&[18; 32]).unwrap();
        assert_eq!(
            sign_referral_invite_v1(claims(&other), &identity),
            Err(ReferralInviteError::InviterBindingMismatch)
        );
    }
}
