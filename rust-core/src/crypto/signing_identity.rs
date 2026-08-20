//! Install-before-engine registry for the real Ed25519 signing sidecar.
//! No application startup path uses this registry yet.

use std::sync::{Arc, OnceLock, RwLock};

use rand::{rngs::OsRng, RngCore};

use crate::crypto::keys::{Ed25519KeyPair, X25519KeyPair};
use crate::crypto::referral::{
    sign_referral_invite_v1, ReferralInviteClaimsV1, ReferralInviteError, SignedReferralInviteV1,
};

pub const IDENTITY_SIGNING_FORMAT_V1: u8 = 1;
pub const IDENTITY_SIGNING_SEED_BYTES: usize = 32;
const IDENTITY_BINDING_DOMAIN_V1: &[u8] = b"apu-identity-binding-v1\0";
const FILE_EXCHANGE_BINDING_DOMAIN_V1: &[u8] = b"apu-file-exchange-binding-v1\0";
const ED25519_PUBLIC_KEY_BYTES: usize = 32;
const ED25519_SIGNATURE_BYTES: usize = 64;
const MAX_LEGACY_ROUTING_ID_BYTES: usize = 67;

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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SignedIdentityBindingV1 {
    pub legacy_routing_node_id: String,
    pub signing_public_key: Vec<u8>,
    pub created_at_ms: i64,
    pub signature: Vec<u8>,
}

/// Canonical R1 wire envelope. The self-signed migration binding ties the
/// stable legacy routing ID to the Ed25519 key that signs the invite claims.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IdentityBoundReferralInviteV1 {
    pub identity_binding: SignedIdentityBindingV1,
    pub signed_invite: SignedReferralInviteV1,
}

/// Signed static X25519 public key used only to wrap per-transfer file keys.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SignedFileExchangeBindingV1 {
    pub identity_binding: SignedIdentityBindingV1,
    pub x25519_public_key: Vec<u8>,
    pub created_at_ms: i64,
    pub signature: Vec<u8>,
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
    #[error("invalid identity binding timestamp")]
    InvalidBindingTimestamp,
    #[error("malformed identity binding")]
    MalformedBinding,
    #[error("identity binding signature is invalid")]
    InvalidBindingSignature,
    #[error("referral invite does not match its identity binding")]
    ReferralBindingMismatch,
    #[error("malformed identity-bound referral token")]
    MalformedReferralToken,
    #[error("malformed file exchange binding")]
    MalformedFileExchangeBinding,
    #[error("file exchange binding does not match installed identity")]
    FileExchangeBindingMismatch,
    #[error("file exchange binding signature is invalid")]
    InvalidFileExchangeSignature,
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
        if claims.inviter_node_id != self.legacy_routing_node_id {
            return Err(SigningIdentityError::ReferralBindingMismatch);
        }
        Ok(sign_referral_invite_v1(claims, &self.key_pair)?)
    }

    pub fn create_binding(
        &self,
        created_at_ms: i64,
    ) -> Result<SignedIdentityBindingV1, SigningIdentityError> {
        if created_at_ms < 0 {
            return Err(SigningIdentityError::InvalidBindingTimestamp);
        }
        let mut binding = SignedIdentityBindingV1 {
            legacy_routing_node_id: self.legacy_routing_node_id.clone(),
            signing_public_key: self.public_key.clone(),
            created_at_ms,
            signature: Vec::new(),
        };
        binding.signature = self.key_pair.sign(&binding.canonical_bytes()?);
        Ok(binding)
    }

    pub fn create_file_exchange_binding(
        &self,
        identity_binding: SignedIdentityBindingV1,
        x25519_secret: &[u8],
        created_at_ms: i64,
    ) -> Result<SignedFileExchangeBindingV1, SigningIdentityError> {
        identity_binding.verify()?;
        if identity_binding.legacy_routing_node_id != self.legacy_routing_node_id()
            || identity_binding.signing_public_key != self.public_key()
            || created_at_ms < identity_binding.created_at_ms
        {
            return Err(SigningIdentityError::FileExchangeBindingMismatch);
        }
        let exchange = X25519KeyPair::from_secret_bytes(x25519_secret)
            .map_err(|_| SigningIdentityError::MalformedFileExchangeBinding)?;
        let mut binding = SignedFileExchangeBindingV1 {
            identity_binding,
            x25519_public_key: exchange.public_key().0,
            created_at_ms,
            signature: Vec::new(),
        };
        binding.signature = self.key_pair.sign(&binding.canonical_bytes()?);
        Ok(binding)
    }
}

impl SignedIdentityBindingV1 {
    pub fn canonical_bytes(&self) -> Result<Vec<u8>, SigningIdentityError> {
        validate_binding_shape(self)?;
        let legacy = self.legacy_routing_node_id.as_bytes();
        let mut output = Vec::with_capacity(
            IDENTITY_BINDING_DOMAIN_V1.len() + 1 + 2 + legacy.len() + 32 + 8,
        );
        output.extend_from_slice(IDENTITY_BINDING_DOMAIN_V1);
        output.push(IDENTITY_SIGNING_FORMAT_V1);
        output.extend_from_slice(&(legacy.len() as u16).to_be_bytes());
        output.extend_from_slice(legacy);
        output.extend_from_slice(&self.signing_public_key);
        output.extend_from_slice(&self.created_at_ms.to_be_bytes());
        Ok(output)
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, SigningIdentityError> {
        validate_binding_shape(self)?;
        if self.signature.len() != ED25519_SIGNATURE_BYTES {
            return Err(SigningIdentityError::MalformedBinding);
        }
        let legacy = self.legacy_routing_node_id.as_bytes();
        let mut output = Vec::with_capacity(1 + 2 + legacy.len() + 32 + 8 + 64);
        output.push(IDENTITY_SIGNING_FORMAT_V1);
        output.extend_from_slice(&(legacy.len() as u16).to_be_bytes());
        output.extend_from_slice(legacy);
        output.extend_from_slice(&self.signing_public_key);
        output.extend_from_slice(&self.created_at_ms.to_be_bytes());
        output.extend_from_slice(&self.signature);
        Ok(output)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, SigningIdentityError> {
        if bytes.len() < 1 + 2 + 32 + 8 + 64 || bytes[0] != IDENTITY_SIGNING_FORMAT_V1 {
            return Err(SigningIdentityError::MalformedBinding);
        }
        let legacy_len = u16::from_be_bytes([bytes[1], bytes[2]]) as usize;
        if legacy_len == 0 || legacy_len > MAX_LEGACY_ROUTING_ID_BYTES {
            return Err(SigningIdentityError::MalformedBinding);
        }
        let expected = 1 + 2 + legacy_len + 32 + 8 + 64;
        if bytes.len() != expected {
            return Err(SigningIdentityError::MalformedBinding);
        }
        let legacy_end = 3 + legacy_len;
        let public_end = legacy_end + ED25519_PUBLIC_KEY_BYTES;
        let time_end = public_end + 8;
        let legacy_routing_node_id = std::str::from_utf8(&bytes[3..legacy_end])
            .map_err(|_| SigningIdentityError::MalformedBinding)?
            .to_string();
        let created_at_ms = i64::from_be_bytes(
            bytes[public_end..time_end]
                .try_into()
                .map_err(|_| SigningIdentityError::MalformedBinding)?,
        );
        let binding = Self {
            legacy_routing_node_id,
            signing_public_key: bytes[legacy_end..public_end].to_vec(),
            created_at_ms,
            signature: bytes[time_end..].to_vec(),
        };
        validate_binding_shape(&binding)?;
        Ok(binding)
    }

    pub fn verify(&self) -> Result<(), SigningIdentityError> {
        validate_binding_shape(self)?;
        Ed25519KeyPair::verify(
            &self.signing_public_key,
            &self.canonical_bytes()?,
            &self.signature,
        )
        .map_err(|_| SigningIdentityError::InvalidBindingSignature)
    }

    pub fn key_id(&self) -> String {
        crate::crypto::keys::NodeId::from_ed25519_pubkey(&self.signing_public_key).to_hex()
    }
}

impl SignedFileExchangeBindingV1 {
    pub fn canonical_bytes(&self) -> Result<Vec<u8>, SigningIdentityError> {
        self.identity_binding.verify()?;
        if self.x25519_public_key.len() != 32
            || self.x25519_public_key.iter().all(|byte| *byte == 0)
            || self.created_at_ms < self.identity_binding.created_at_ms
        {
            return Err(SigningIdentityError::MalformedFileExchangeBinding);
        }
        let identity = self.identity_binding.to_bytes()?;
        let identity_len = u16::try_from(identity.len())
            .map_err(|_| SigningIdentityError::MalformedFileExchangeBinding)?;
        let mut output = Vec::with_capacity(
            FILE_EXCHANGE_BINDING_DOMAIN_V1.len() + 1 + 2 + identity.len() + 32 + 8,
        );
        output.extend_from_slice(FILE_EXCHANGE_BINDING_DOMAIN_V1);
        output.push(IDENTITY_SIGNING_FORMAT_V1);
        output.extend_from_slice(&identity_len.to_be_bytes());
        output.extend_from_slice(&identity);
        output.extend_from_slice(&self.x25519_public_key);
        output.extend_from_slice(&self.created_at_ms.to_be_bytes());
        Ok(output)
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, SigningIdentityError> {
        self.canonical_bytes()?;
        if self.signature.len() != ED25519_SIGNATURE_BYTES {
            return Err(SigningIdentityError::MalformedFileExchangeBinding);
        }
        let identity = self.identity_binding.to_bytes()?;
        let mut output = Vec::with_capacity(1 + 2 + identity.len() + 32 + 8 + 64);
        output.push(IDENTITY_SIGNING_FORMAT_V1);
        output.extend_from_slice(&(identity.len() as u16).to_be_bytes());
        output.extend_from_slice(&identity);
        output.extend_from_slice(&self.x25519_public_key);
        output.extend_from_slice(&self.created_at_ms.to_be_bytes());
        output.extend_from_slice(&self.signature);
        Ok(output)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, SigningIdentityError> {
        const TAIL: usize = 32 + 8 + 64;
        if bytes.len() < 1 + 2 + TAIL || bytes[0] != IDENTITY_SIGNING_FORMAT_V1 {
            return Err(SigningIdentityError::MalformedFileExchangeBinding);
        }
        let identity_len = u16::from_be_bytes([bytes[1], bytes[2]]) as usize;
        let identity_end = 3usize
            .checked_add(identity_len)
            .ok_or(SigningIdentityError::MalformedFileExchangeBinding)?;
        if identity_len == 0 || bytes.len() != identity_end + TAIL {
            return Err(SigningIdentityError::MalformedFileExchangeBinding);
        }
        let identity_binding = SignedIdentityBindingV1::from_bytes(&bytes[3..identity_end])?;
        let public_end = identity_end + 32;
        let time_end = public_end + 8;
        let created_at_ms = i64::from_be_bytes(
            bytes[public_end..time_end]
                .try_into()
                .map_err(|_| SigningIdentityError::MalformedFileExchangeBinding)?,
        );
        let binding = Self {
            identity_binding,
            x25519_public_key: bytes[identity_end..public_end].to_vec(),
            created_at_ms,
            signature: bytes[time_end..].to_vec(),
        };
        binding.canonical_bytes()?;
        Ok(binding)
    }

    pub fn verify(&self) -> Result<(), SigningIdentityError> {
        self.identity_binding.verify()?;
        Ed25519KeyPair::verify(
            &self.identity_binding.signing_public_key,
            &self.canonical_bytes()?,
            &self.signature,
        )
        .map_err(|_| SigningIdentityError::InvalidFileExchangeSignature)
    }

    pub fn legacy_routing_node_id(&self) -> &str {
        &self.identity_binding.legacy_routing_node_id
    }
}

impl IdentityBoundReferralInviteV1 {
    pub fn create(
        identity: &InstalledSigningIdentity,
        identity_binding: SignedIdentityBindingV1,
        nonce: [u8; crate::crypto::referral::REFERRAL_NONCE_BYTES],
        created_at_ms: i64,
        expires_at_ms: i64,
    ) -> Result<Self, SigningIdentityError> {
        identity_binding.verify()?;
        if identity_binding.legacy_routing_node_id != identity.legacy_routing_node_id()
            || identity_binding.signing_public_key != identity.public_key()
            || identity_binding.created_at_ms > created_at_ms
        {
            return Err(SigningIdentityError::ReferralBindingMismatch);
        }
        let claims = ReferralInviteClaimsV1 {
            inviter_node_id: identity.legacy_routing_node_id().to_string(),
            nonce,
            created_at_ms,
            expires_at_ms,
        };
        let signed_invite = identity.sign_referral(claims)?;
        Ok(Self {
            identity_binding,
            signed_invite,
        })
    }

    /// Exact token layout:
    /// `[v1][binding_len:u16][binding][nonce16][created:i64][expires:i64][signature64]`.
    pub fn to_bytes(&self) -> Result<Vec<u8>, SigningIdentityError> {
        self.verify(self.signed_invite.claims.created_at_ms)?;
        let binding = self.identity_binding.to_bytes()?;
        let binding_len = u16::try_from(binding.len())
            .map_err(|_| SigningIdentityError::MalformedReferralToken)?;
        let mut output = Vec::with_capacity(1 + 2 + binding.len() + 16 + 8 + 8 + 64);
        output.push(IDENTITY_SIGNING_FORMAT_V1);
        output.extend_from_slice(&binding_len.to_be_bytes());
        output.extend_from_slice(&binding);
        output.extend_from_slice(&self.signed_invite.claims.nonce);
        output.extend_from_slice(&self.signed_invite.claims.created_at_ms.to_be_bytes());
        output.extend_from_slice(&self.signed_invite.claims.expires_at_ms.to_be_bytes());
        output.extend_from_slice(&self.signed_invite.signature);
        Ok(output)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, SigningIdentityError> {
        const TAIL_BYTES: usize = 16 + 8 + 8 + 64;
        if bytes.len() < 1 + 2 + TAIL_BYTES || bytes[0] != IDENTITY_SIGNING_FORMAT_V1 {
            return Err(SigningIdentityError::MalformedReferralToken);
        }
        let binding_len = u16::from_be_bytes([bytes[1], bytes[2]]) as usize;
        let binding_end = 3usize
            .checked_add(binding_len)
            .ok_or(SigningIdentityError::MalformedReferralToken)?;
        if binding_len == 0 || bytes.len() != binding_end + TAIL_BYTES {
            return Err(SigningIdentityError::MalformedReferralToken);
        }
        let identity_binding = SignedIdentityBindingV1::from_bytes(&bytes[3..binding_end])?;
        let nonce_end = binding_end + 16;
        let created_end = nonce_end + 8;
        let expires_end = created_end + 8;
        let nonce = bytes[binding_end..nonce_end]
            .try_into()
            .map_err(|_| SigningIdentityError::MalformedReferralToken)?;
        let created_at_ms = i64::from_be_bytes(
            bytes[nonce_end..created_end]
                .try_into()
                .map_err(|_| SigningIdentityError::MalformedReferralToken)?,
        );
        let expires_at_ms = i64::from_be_bytes(
            bytes[created_end..expires_end]
                .try_into()
                .map_err(|_| SigningIdentityError::MalformedReferralToken)?,
        );
        let signed_invite = SignedReferralInviteV1 {
            claims: ReferralInviteClaimsV1 {
                inviter_node_id: identity_binding.legacy_routing_node_id.clone(),
                nonce,
                created_at_ms,
                expires_at_ms,
            },
            inviter_ed25519_public_key: identity_binding.signing_public_key.clone(),
            signature: bytes[expires_end..].to_vec(),
        };
        Ok(Self {
            identity_binding,
            signed_invite,
        })
    }

    pub fn verify(&self, now_ms: i64) -> Result<(), SigningIdentityError> {
        self.identity_binding.verify()?;
        if self.signed_invite.claims.inviter_node_id
            != self.identity_binding.legacy_routing_node_id
            || self.signed_invite.inviter_ed25519_public_key
                != self.identity_binding.signing_public_key
            || self.identity_binding.created_at_ms > self.signed_invite.claims.created_at_ms
        {
            return Err(SigningIdentityError::ReferralBindingMismatch);
        }
        crate::crypto::referral::verify_referral_invite_v1(&self.signed_invite, now_ms)?;
        Ok(())
    }
}

fn validate_binding_shape(binding: &SignedIdentityBindingV1) -> Result<(), SigningIdentityError> {
    if !is_legacy_routing_node_id(&binding.legacy_routing_node_id)
        || binding.signing_public_key.len() != ED25519_PUBLIC_KEY_BYTES
        || binding.created_at_ms < 0
    {
        return Err(SigningIdentityError::MalformedBinding);
    }
    Ok(())
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

pub fn create_installed_identity_binding(
    created_at_ms: i64,
) -> Result<Vec<u8>, SigningIdentityError> {
    let identity = installed_signing_identity().ok_or(SigningIdentityError::MalformedBinding)?;
    identity.create_binding(created_at_ms)?.to_bytes()
}

pub fn create_installed_file_exchange_binding(
    identity_binding: &[u8],
    x25519_secret: &[u8],
    created_at_ms: i64,
) -> Result<Vec<u8>, SigningIdentityError> {
    let identity = installed_signing_identity()
        .ok_or(SigningIdentityError::FileExchangeBindingMismatch)?;
    let identity_binding = SignedIdentityBindingV1::from_bytes(identity_binding)?;
    identity
        .create_file_exchange_binding(identity_binding, x25519_secret, created_at_ms)?
        .to_bytes()
}

pub fn verify_file_exchange_binding(bytes: &[u8]) -> bool {
    SignedFileExchangeBindingV1::from_bytes(bytes)
        .and_then(|binding| binding.verify())
        .is_ok()
}

pub fn file_exchange_binding_node_id(bytes: &[u8]) -> Result<String, SigningIdentityError> {
    let binding = SignedFileExchangeBindingV1::from_bytes(bytes)?;
    binding.verify()?;
    Ok(binding.legacy_routing_node_id().to_string())
}

pub fn file_exchange_binding_public_key(bytes: &[u8]) -> Result<Vec<u8>, SigningIdentityError> {
    let binding = SignedFileExchangeBindingV1::from_bytes(bytes)?;
    binding.verify()?;
    Ok(binding.x25519_public_key)
}

pub fn verify_identity_binding(bytes: &[u8]) -> bool {
    SignedIdentityBindingV1::from_bytes(bytes)
        .and_then(|binding| binding.verify())
        .is_ok()
}

pub fn identity_binding_matches_installed(bytes: &[u8]) -> bool {
    let Some(identity) = installed_signing_identity() else {
        return false;
    };
    let Ok(binding) = SignedIdentityBindingV1::from_bytes(bytes) else {
        return false;
    };
    binding.verify().is_ok()
        && binding.legacy_routing_node_id == identity.legacy_routing_node_id()
        && binding.signing_public_key == identity.public_key()
        && binding.key_id() == identity.key_id()
}

pub fn create_installed_referral_token(
    identity_binding: &[u8],
    created_at_ms: i64,
    expires_at_ms: i64,
) -> Result<Vec<u8>, SigningIdentityError> {
    let identity = installed_signing_identity().ok_or(SigningIdentityError::MalformedReferralToken)?;
    let binding = SignedIdentityBindingV1::from_bytes(identity_binding)?;
    let mut nonce = [0u8; crate::crypto::referral::REFERRAL_NONCE_BYTES];
    let mut rng = OsRng;
    rng.fill_bytes(&mut nonce);
    IdentityBoundReferralInviteV1::create(
        &identity,
        binding,
        nonce,
        created_at_ms,
        expires_at_ms,
    )?
    .to_bytes()
}

pub fn verify_identity_bound_referral_token(bytes: &[u8], now_ms: i64) -> bool {
    IdentityBoundReferralInviteV1::from_bytes(bytes)
        .and_then(|token| token.verify(now_ms))
        .is_ok()
}

pub fn verified_referral_inviter_node_id(
    bytes: &[u8],
    now_ms: i64,
) -> Result<String, SigningIdentityError> {
    let token = IdentityBoundReferralInviteV1::from_bytes(bytes)?;
    token.verify(now_ms)?;
    Ok(token.signed_invite.claims.inviter_node_id)
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
            inviter_node_id: identity.legacy_routing_node_id().to_string(),
            nonce: [0x42; 16],
            created_at_ms: 1_800_000_000_000,
            expires_at_ms: 1_800_086_400_000,
        };
        let token = identity.sign_referral(claims).unwrap();
        assert_eq!(token.inviter_ed25519_public_key, identity.public_key());
        assert_eq!(token.signature.len(), 64);
    }

    #[test]
    fn binding_round_trip_is_canonical_and_self_signed() {
        let identity = InstalledSigningIdentity::from_seed(1, legacy(), &[21; 32]).unwrap();
        let binding = identity.create_binding(1_800_000_000_000).unwrap();
        binding.verify().unwrap();
        let bytes = binding.to_bytes().unwrap();
        let decoded = SignedIdentityBindingV1::from_bytes(&bytes).unwrap();
        assert_eq!(decoded, binding);
        assert_eq!(decoded.key_id(), identity.key_id());
    }

    #[test]
    fn tamper_truncation_and_trailing_bytes_are_rejected() {
        let identity = InstalledSigningIdentity::from_seed(1, legacy(), &[22; 32]).unwrap();
        let bytes = identity.create_binding(1_800_000_000_000).unwrap().to_bytes().unwrap();
        for length in 0..bytes.len() {
            assert!(SignedIdentityBindingV1::from_bytes(&bytes[..length]).is_err());
        }
        assert!(SignedIdentityBindingV1::from_bytes(&[bytes.clone(), vec![0]].concat()).is_err());
        let mut tampered = bytes;
        let last = tampered.len() - 1;
        tampered[last] ^= 1;
        assert_eq!(
            SignedIdentityBindingV1::from_bytes(&tampered)
                .unwrap()
                .verify(),
            Err(SigningIdentityError::InvalidBindingSignature)
        );
    }

    #[test]
    fn installed_binding_match_is_strict() {
        clear_signing_identity();
        let identity = install_signing_identity(1, legacy(), &[23; 32]).unwrap();
        let bytes = identity.create_binding(1_800_000_000_000).unwrap().to_bytes().unwrap();
        assert!(verify_identity_binding(&bytes));
        assert!(identity_binding_matches_installed(&bytes));
        install_signing_identity(
            1,
            "pk_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".to_string(),
            &[24; 32],
        )
        .unwrap();
        assert!(verify_identity_binding(&bytes));
        assert!(!identity_binding_matches_installed(&bytes));
        clear_signing_identity();
    }

    #[test]
    fn file_exchange_binding_is_signed_bound_and_strict() {
        let identity = InstalledSigningIdentity::from_seed(1, legacy(), &[40; 32]).unwrap();
        let identity_binding = identity.create_binding(1_800_000_000_000).unwrap();
        let exchange = identity
            .create_file_exchange_binding(identity_binding, &[41; 32], 1_800_000_001_000)
            .unwrap();
        exchange.verify().unwrap();
        assert_eq!(exchange.x25519_public_key.len(), 32);
        let bytes = exchange.to_bytes().unwrap();
        let decoded = SignedFileExchangeBindingV1::from_bytes(&bytes).unwrap();
        assert_eq!(decoded, exchange);
        assert_eq!(decoded.legacy_routing_node_id(), legacy());
        for length in 0..bytes.len() {
            assert!(SignedFileExchangeBindingV1::from_bytes(&bytes[..length]).is_err());
        }
        assert!(SignedFileExchangeBindingV1::from_bytes(&[bytes.clone(), vec![0]].concat()).is_err());
        let mut tampered = bytes;
        let last = tampered.len() - 1;
        tampered[last] ^= 1;
        assert!(SignedFileExchangeBindingV1::from_bytes(&tampered)
            .unwrap()
            .verify()
            .is_err());
    }

    #[test]
    fn foreign_identity_binding_cannot_be_signed_as_local_exchange() {
        let local = InstalledSigningIdentity::from_seed(1, legacy(), &[42; 32]).unwrap();
        let foreign = InstalledSigningIdentity::from_seed(
            1,
            "pk_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".to_string(),
            &[43; 32],
        )
        .unwrap();
        assert_eq!(
            local.create_file_exchange_binding(
                foreign.create_binding(1_800_000_000_000).unwrap(),
                &[44; 32],
                1_800_000_001_000,
            ),
            Err(SigningIdentityError::FileExchangeBindingMismatch)
        );
    }

    #[test]
    fn identity_bound_referral_wire_round_trip_and_tamper_rejection() {
        let identity = InstalledSigningIdentity::from_seed(1, legacy(), &[31; 32]).unwrap();
        let binding = identity.create_binding(1_800_000_000_000).unwrap();
        let token = IdentityBoundReferralInviteV1::create(
            &identity,
            binding,
            [0x5a; 16],
            1_800_000_001_000,
            1_800_086_401_000,
        )
        .unwrap();
        let bytes = token.to_bytes().unwrap();
        let decoded = IdentityBoundReferralInviteV1::from_bytes(&bytes).unwrap();
        decoded.verify(1_800_000_002_000).unwrap();
        assert_eq!(decoded, token);
        assert_eq!(decoded.signed_invite.claims.inviter_node_id, legacy());

        let mut tampered = bytes.clone();
        let last = tampered.len() - 1;
        tampered[last] ^= 1;
        assert!(IdentityBoundReferralInviteV1::from_bytes(&tampered)
            .unwrap()
            .verify(1_800_000_002_000)
            .is_err());
        assert!(IdentityBoundReferralInviteV1::from_bytes(&[bytes, vec![0]].concat()).is_err());
    }

    #[test]
    fn referral_binding_must_match_installed_sidecar() {
        let identity = InstalledSigningIdentity::from_seed(1, legacy(), &[32; 32]).unwrap();
        let foreign = InstalledSigningIdentity::from_seed(
            1,
            "pk_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".to_string(),
            &[33; 32],
        )
        .unwrap();
        let foreign_binding = foreign.create_binding(1_800_000_000_000).unwrap();
        assert_eq!(
            IdentityBoundReferralInviteV1::create(
                &identity,
                foreign_binding,
                [1; 16],
                1_800_000_001_000,
                1_800_086_401_000,
            ),
            Err(SigningIdentityError::ReferralBindingMismatch)
        );
    }

    #[test]
    fn installed_registry_creates_and_extracts_verified_referral_token() {
        clear_signing_identity();
        let identity = install_signing_identity(1, legacy(), &[34; 32]).unwrap();
        let binding = identity
            .create_binding(1_800_000_000_000)
            .unwrap()
            .to_bytes()
            .unwrap();
        let bytes = create_installed_referral_token(
            &binding,
            1_800_000_001_000,
            1_800_086_401_000,
        )
        .unwrap();
        assert!(verify_identity_bound_referral_token(
            &bytes,
            1_800_000_002_000
        ));
        assert_eq!(
            verified_referral_inviter_node_id(&bytes, 1_800_000_002_000).unwrap(),
            legacy()
        );
        let mut tampered = bytes;
        let last = tampered.len() - 1;
        tampered[last] ^= 1;
        assert!(!verify_identity_bound_referral_token(
            &tampered,
            1_800_000_002_000
        ));
        clear_signing_identity();
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
