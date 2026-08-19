//! Install-before-engine registry for the real Ed25519 signing sidecar.
//! No application startup path uses this registry yet.

use std::sync::{Arc, OnceLock, RwLock};

use crate::crypto::keys::Ed25519KeyPair;
use crate::crypto::referral::{
    sign_referral_invite_v1, ReferralInviteClaimsV1, ReferralInviteError, SignedReferralInviteV1,
};

pub const IDENTITY_SIGNING_FORMAT_V1: u8 = 1;
pub const IDENTITY_SIGNING_SEED_BYTES: usize = 32;

pub struct InstalledSigningIdentity {
    format_version: u8,
    legacy_routing_node_id: String,
    key_pair: Ed25519KeyPair,
    public_key: Vec<u8>,
    key_id: String,
}

impl std::fmt::Debug for InstalledSigningIdentity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("InstalledSigningIdentity")
            .field("format_version", &self.format_version)
            .field("legacy_routing_node_id", &self.legacy_routing_node_id)
            .field("key_id", &self.key_id)
            .field("private_seed", &"[REDACTED]")
            .finish()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum SigningIdentityError {
    #[error("unsupported signing identity format {0}")]
    UnsupportedFormat(u8),
    #[error("invalid legacy routing node id")]
    InvalidLegacyRoutingNodeId,
    #[error("identity signing seed must be 32 bytes, got {0}")]
    InvalidSeedLength(usize),
    #[error("cannot construct Ed25519 signing key")]
    InvalidSeed,
    #[error("referral signing failed: {0}")]
    Referral(#[from] ReferralInviteError),
}

impl InstalledSigningIdentity {
    pub fn from_seed(
        format_version: u8,
        legacy_routing_node_id: String,
        seed: &[u8],
    ) -> Result<Self, SigningIdentityError> {
        if format_version != IDENTITY_SIGNING_FORMAT_V1 {
            return Err(SigningIdentityError::UnsupportedFormat(format_version));
        }
        if !is_legacy_routing_node_id(&legacy_routing_node_id) {
            return Err(SigningIdentityError::InvalidLegacyRoutingNodeId);
        }
        if seed.len() != IDENTITY_SIGNING_SEED_BYTES {
            return Err(SigningIdentityError::InvalidSeedLength(seed.len()));
        }
        let key_pair = Ed25519KeyPair::from_secret_bytes(seed)
            .map_err(|_| SigningIdentityError::InvalidSeed)?;
        let public_key = key_pair.public_key().0;
        let key_id = key_pair.node_id().to_hex();
        Ok(Self {
            format_version,
            legacy_routing_node_id,
            key_pair,
            public_key,
            key_id,
        })
    }

    pub fn format_version(&self) -> u8 {
        self.format_version
    }

    pub fn legacy_routing_node_id(&self) -> &str {
        &self.legacy_routing_node_id
    }

    pub fn public_key(&self) -> &[u8] {
        &self.public_key
    }

    pub fn key_id(&self) -> &str {
        &self.key_id
    }

    pub fn sign_referral(
        &self,
        claims: ReferralInviteClaimsV1,
    ) -> Result<SignedReferralInviteV1, SigningIdentityError> {
        Ok(sign_referral_invite_v1(claims, &self.key_pair)?)
    }
}

fn registry() -> &'static RwLock<Option<Arc<InstalledSigningIdentity>>> {
    static REGISTRY: OnceLock<RwLock<Option<Arc<InstalledSigningIdentity>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| RwLock::new(None))
}

/// Validate completely before replacing the previous registry value.
pub fn install_signing_identity(
    format_version: u8,
    legacy_routing_node_id: String,
    seed: &[u8],
) -> Result<Arc<InstalledSigningIdentity>, SigningIdentityError> {
    let identity = Arc::new(InstalledSigningIdentity::from_seed(
        format_version,
        legacy_routing_node_id,
        seed,
    )?);
    let mut guard = registry()
        .write()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    *guard = Some(identity.clone());
    Ok(identity)
}

pub fn installed_signing_identity() -> Option<Arc<InstalledSigningIdentity>> {
    registry()
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone()
}

pub fn clear_signing_identity() {
    let mut guard = registry()
        .write()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    *guard = None;
}

pub fn signing_identity_mode() -> &'static str {
    if installed_signing_identity().is_some() {
        "legacy+ed25519-sidecar-v1"
    } else {
        "legacy-only"
    }
}

fn is_legacy_routing_node_id(value: &str) -> bool {
    let Some(suffix) = value.strip_prefix("pk_") else {
        return false;
    };
    matches!(suffix.len(), 32 | 64)
        && suffix
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::referral::inviter_node_id;

    fn legacy() -> String {
        "pk_0123456789abcdef0123456789abcdef".to_string()
    }

    #[test]
    fn seed_produces_stable_public_identity_and_redacted_debug() {
        let first = InstalledSigningIdentity::from_seed(1, legacy(), &[7; 32]).unwrap();
        let second = InstalledSigningIdentity::from_seed(1, legacy(), &[7; 32]).unwrap();
        assert_eq!(first.public_key(), second.public_key());
        assert_eq!(first.key_id(), second.key_id());
        assert_eq!(first.key_id().len(), 64);
        let debug = format!("{first:?}");
        assert!(debug.contains("[REDACTED]"));
        assert!(!debug.contains(&"07".repeat(32)));
    }

    #[test]
    fn invalid_install_does_not_replace_previous_identity() {
        clear_signing_identity();
        let installed = install_signing_identity(1, legacy(), &[8; 32]).unwrap();
        assert!(matches!(
            install_signing_identity(1, legacy(), &[9; 31]),
            Err(SigningIdentityError::InvalidSeedLength(31))
        ));
        let current = installed_signing_identity().unwrap();
        assert_eq!(current.key_id(), installed.key_id());
        clear_signing_identity();
    }

    #[test]
    fn registry_snapshot_survives_clear() {
        clear_signing_identity();
        let snapshot = install_signing_identity(1, legacy(), &[10; 32]).unwrap();
        clear_signing_identity();
        assert!(installed_signing_identity().is_none());
        assert_eq!(snapshot.public_key().len(), 32);
    }

    #[test]
    fn installed_identity_signs_bound_referral_claims() {
        let identity = InstalledSigningIdentity::from_seed(1, legacy(), &[11; 32]).unwrap();
        let claims = ReferralInviteClaimsV1 {
            inviter_node_id: inviter_node_id(identity.public_key()),
            nonce: [0x42; 16],
            created_at_ms: 1_800_000_000_000,
            expires_at_ms: 1_800_086_400_000,
        };
        let token = identity.sign_referral(claims).unwrap();
        assert_eq!(token.inviter_ed25519_public_key, identity.public_key());
        assert_eq!(token.signature.len(), 64);
    }

    #[test]
    fn legacy_routing_id_and_format_are_strict() {
        for invalid in [
            "",
            "pk_ABCDEF0123456789ABCDEF0123456789",
            "sk_0123456789abcdef0123456789abcdef",
            "pk_short",
        ] {
            assert!(matches!(
                InstalledSigningIdentity::from_seed(1, invalid.to_string(), &[1; 32]),
                Err(SigningIdentityError::InvalidLegacyRoutingNodeId)
            ));
        }
        assert!(matches!(
            InstalledSigningIdentity::from_seed(2, legacy(), &[1; 32]),
            Err(SigningIdentityError::UnsupportedFormat(2))
        ));
    }
}
