//! Directional remote-wake capability. This is not yet wired to Android/FCM.
//!
//! **Trust boundary:** verifying a self-signed exchange binding does not prove
//! that it belongs to a legacy routing ID. The caller MUST obtain `peer_binding`
//! from the byte-identical, verified `FileExchangePeerStore` pin, and must not
//! substitute a binding merely learned from an opened packet. An independently
//! agreed, per-contact epoch supports revocation without reusing the old route.
//! Identity and pin can change during or after derivation: the caller must
//! serialize/revalidate them before any registration or wake side effect.

use hkdf::Hkdf;
use sha2::{Digest, Sha256};

use crate::crypto::keys::X25519KeyPair;
use crate::crypto::signing_identity::{
    identity_binding_matches_installed, SignedFileExchangeBindingV1,
};

const SALT_DOMAIN: &[u8] = b"apu-push-cap-salt-v1\0";
const CAPABILITY_INFO: &[u8] = b"apu-push-capability-v1\0";
const EPOCH_BYTES: usize = 16;
const CAPABILITY_BYTES: usize = 32;

/// No Debug/Clone: callers must keep the capability out of logs and backups.
/// Drop clears this instance on a best-effort basis; compiler optimizations and
/// caller-made copies mean this is not a secure-erasure guarantee.
pub struct PushCapability {
    pub capability: [u8; CAPABILITY_BYTES],
    pub receiver_signing_public_key: [u8; 32],
}

impl Drop for PushCapability {
    fn drop(&mut self) {
        self.capability.fill(0);
    }
}

/// Derive the *same* capability at sender and receiver; reversing direction or
/// rotating the authenticated epoch produces a different capability. This uses
/// long-lived exchange keys and does not provide forward secrecy. The caller
/// owns the local secret and MUST enforce the peer pin.
pub fn derive_push_capability(
    local_binding_bytes: &[u8],
    peer_binding_bytes: &[u8],
    local_x25519_secret: &[u8],
    epoch: &[u8],
    local_is_sender: bool,
) -> Result<PushCapability, &'static str> {
    if epoch.len() != EPOCH_BYTES || epoch.iter().all(|byte| *byte == 0) {
        return Err("invalid push epoch");
    }
    // Identical bindings make the two direction labels indistinguishable.
    if local_binding_bytes == peer_binding_bytes {
        return Err("identical push bindings");
    }
    let local = SignedFileExchangeBindingV1::from_bytes(local_binding_bytes)
        .map_err(|_| "invalid local binding")?;
    local.verify().map_err(|_| "invalid local binding")?;
    let identity = local.identity_binding.to_bytes().map_err(|_| "invalid local identity")?;
    if !identity_binding_matches_installed(&identity) {
        return Err("local identity unavailable or changed");
    }
    let peer = SignedFileExchangeBindingV1::from_bytes(peer_binding_bytes)
        .map_err(|_| "invalid peer binding")?;
    peer.verify().map_err(|_| "invalid peer binding")?;
    let exchange = X25519KeyPair::from_secret_bytes(local_x25519_secret)
        .map_err(|_| "invalid local exchange secret")?;
    if exchange.public_key().0 != local.x25519_public_key {
        return Err("local exchange secret does not match binding");
    }
    let mut shared = exchange.diffie_hellman(&peer.x25519_public_key)
        .map_err(|_| "invalid peer exchange key")?;
    if shared.iter().all(|byte| *byte == 0) {
        shared.fill(0);
        return Err("invalid all-zero shared secret");
    }
    let (sender_bytes, receiver_bytes, receiver_key) = if local_is_sender {
        (binding_digest(local_binding_bytes), binding_digest(peer_binding_bytes), &peer.identity_binding.signing_public_key)
    } else {
        (binding_digest(peer_binding_bytes), binding_digest(local_binding_bytes), &local.identity_binding.signing_public_key)
    };
    let mut salt = Sha256::new();
    salt.update(SALT_DOMAIN);
    salt.update(sender_bytes);
    salt.update(receiver_bytes);
    salt.update(epoch);
    let salt = salt.finalize();
    let hkdf = Hkdf::<Sha256>::new(Some(&salt), &shared);
    shared.fill(0);
    let mut capability = [0u8; CAPABILITY_BYTES];
    hkdf.expand(CAPABILITY_INFO, &mut capability).map_err(|_| "push key derivation failed")?;
    if capability.iter().all(|byte| *byte == 0) {
        return Err("invalid derived capability");
    }
    let mut receiver_signing_public_key = [0u8; 32];
    receiver_signing_public_key.copy_from_slice(receiver_key);
    Ok(PushCapability { capability, receiver_signing_public_key })
}

fn binding_digest(binding_bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(binding_bytes).into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::signing_identity::{
        clear_signing_identity, install_signing_identity, signing_identity_registry_guard,
        InstalledSigningIdentity,
    };

    fn legacy(n: char) -> String { format!("pk_{}", n.to_string().repeat(32)) }
    fn identity(n: char, seed: u8) -> InstalledSigningIdentity {
        InstalledSigningIdentity::from_seed(1, legacy(n), &[seed; 32]).unwrap()
    }
    fn binding(identity: &InstalledSigningIdentity, exchange_secret: u8) -> Vec<u8> {
        identity.create_file_exchange_binding(identity.create_binding(100).unwrap(), &[exchange_secret; 32], 200)
            .unwrap().to_bytes().unwrap()
    }
    fn hex(bytes: &[u8]) -> String { bytes.iter().map(|b| format!("{:02x}", b)).collect() }

    #[test]
    fn sender_and_receiver_match_independent_vector_and_reverse_is_distinct() {
        let _guard = signing_identity_registry_guard();
        clear_signing_identity();
        let alice = identity('1', 1);
        let bob = identity('2', 2);
        let a = binding(&alice, 3);
        let b = binding(&bob, 4);
        let epoch = [9u8; 16];
        install_signing_identity(1, legacy('1'), &[1; 32]).unwrap();
        let sender = derive_push_capability(&a, &b, &[3; 32], &epoch, true).unwrap();
        let receiver_reverse = derive_push_capability(&a, &b, &[3; 32], &epoch, false).unwrap();
        let rotated = derive_push_capability(&a, &b, &[3; 32], &[10; 16], true).unwrap();
        install_signing_identity(1, legacy('2'), &[2; 32]).unwrap();
        let receiver = derive_push_capability(&b, &a, &[4; 32], &epoch, false).unwrap();
        let sender_reverse = derive_push_capability(&b, &a, &[4; 32], &epoch, true).unwrap();
        assert_eq!(sender.capability, receiver.capability);
        assert_eq!(sender.receiver_signing_public_key, receiver.receiver_signing_public_key);
        assert_eq!(receiver_reverse.capability, sender_reverse.capability);
        assert_ne!(sender.capability, sender_reverse.capability);
        assert_ne!(sender.capability, rotated.capability);
        // Independently generated with Node's native X25519/HKDF and Ed25519.
        assert_eq!(hex(&sender.capability), "e684e7708138f2e247613a7784c120d6154816a7bd1d88a54c53f155d8e9617a");
        assert_eq!(hex(&sender.receiver_signing_public_key), "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394");
        clear_signing_identity();
    }

    #[test]
    fn rejects_bad_epoch_secret_identity_and_tampered_binding() {
        let _guard = signing_identity_registry_guard();
        clear_signing_identity();
        let a = binding(&identity('1', 1), 3);
        let b = binding(&identity('2', 2), 4);
        install_signing_identity(1, legacy('1'), &[1; 32]).unwrap();
        assert!(derive_push_capability(&a, &b, &[3; 32], &[0; 16], true).is_err());
        assert!(derive_push_capability(&a, &b, &[3; 32], &[9; 15], true).is_err());
        assert!(derive_push_capability(&a, &a, &[3; 32], &[9; 16], true).is_err());
        assert!(derive_push_capability(&a, &b, &[4; 32], &[9; 16], true).is_err());
        let mut tampered = b.clone();
        *tampered.last_mut().unwrap() ^= 1;
        assert!(derive_push_capability(&a, &tampered, &[3; 32], &[9; 16], true).is_err());
        install_signing_identity(1, legacy('2'), &[2; 32]).unwrap();
        assert!(derive_push_capability(&a, &b, &[3; 32], &[9; 16], true).is_err());
        clear_signing_identity();
        assert!(derive_push_capability(&a, &b, &[3; 32], &[9; 16], true).is_err());
    }

    #[test]
    fn rejects_signed_low_order_peer_key() {
        let _guard = signing_identity_registry_guard();
        clear_signing_identity();
        let alice = identity('1', 1);
        let bob = identity('2', 2);
        let a = binding(&alice, 3);
        let b = binding(&bob, 4);
        let mut malicious = SignedFileExchangeBindingV1::from_bytes(&b).unwrap();
        malicious.x25519_public_key = vec![0; 32];
        malicious.x25519_public_key[0] = 1; // nonzero, but X25519 shared secret is all zero
        malicious.signature = bob.sign_security_payload(&malicious.canonical_bytes().unwrap());
        let malicious = malicious.to_bytes().unwrap();
        install_signing_identity(1, legacy('1'), &[1; 32]).unwrap();
        assert!(derive_push_capability(&a, &malicious, &[3; 32], &[9; 16], true).is_err());
        clear_signing_identity();
    }
}
