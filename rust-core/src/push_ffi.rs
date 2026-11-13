//! Bounded UniFFI entry points for the disabled remote-wake prototype.
//!
//! These functions perform no network work. The caller must load the peer
//! binding from a previously established pin and revalidate the pin and local
//! identity immediately before any eventual side effect. Passing a self-signed
//! binding learned from the packet currently being processed is NOT sufficient.
//! Zeroizing covers the Rust-owned inputs on every return/unwind, not copies in
//! UniFFI, Java, cryptographic internals, or the returned capability buffer.

use crate::crypto::mailbox_access::{sign_mailbox_access, MailboxAccess};
use crate::crypto::push_capability::derive_push_capability;
use crate::crypto::push_registration::{sign_push_registration, PushRegistrationAction};
use crate::CoreError;
use zeroize::Zeroizing;

const MAX_BINDING_BYTES: usize = 512;
const MAX_TOKEN_BYTES: usize = 2048;

fn bad(detail: &'static str) -> CoreError {
    CoreError::CryptoError { detail: detail.to_string() }
}

fn valid_binding(binding: &[u8]) -> bool {
    !binding.is_empty() && binding.len() <= MAX_BINDING_BYTES
}

/// Returns receiver public key || capability (32 + 32 bytes).
/// The caller must explicitly wipe the returned bytes after use.
pub fn derive_push_wake_material(
    local_binding: Vec<u8>,
    pinned_peer_binding: Vec<u8>,
    local_exchange_secret: Vec<u8>,
    epoch: Vec<u8>,
    local_is_sender: bool,
) -> Result<Vec<u8>, CoreError> {
    let local_exchange_secret = Zeroizing::new(local_exchange_secret);
    let epoch = Zeroizing::new(epoch);
    if !valid_binding(&local_binding) || !valid_binding(&pinned_peer_binding) {
        return Err(bad("invalid push binding"));
    }
    let material = derive_push_capability(
        &local_binding,
        &pinned_peer_binding,
        &local_exchange_secret,
        &epoch,
        local_is_sender,
    ).map_err(bad)?;
    let mut output = Vec::with_capacity(64);
    output.extend_from_slice(&material.receiver_signing_public_key);
    output.extend_from_slice(&material.capability);
    Ok(output)
}

/// Returns owner public key || signature (32 + 64 bytes); no token/capability.
/// The signed operation is strictly domain-separated and validated by the core.
pub fn sign_push_wake_registration(
    local_binding: Vec<u8>,
    capability: Vec<u8>,
    device: Vec<u8>,
    sequence: u64,
    issued_at_seconds: u64,
    token: Vec<u8>,
    revoke: bool,
) -> Result<Vec<u8>, CoreError> {
    let capability = Zeroizing::new(capability);
    let token = Zeroizing::new(token);
    if !valid_binding(&local_binding) || token.len() > MAX_TOKEN_BYTES {
        return Err(bad("invalid push registration input"));
    }
    let token_text = std::str::from_utf8(&token).map_err(|_| bad("invalid push token"))?;
    let action = if revoke { PushRegistrationAction::Revoke } else { PushRegistrationAction::Register };
    let signed = sign_push_registration(
        &local_binding, &capability, &device, sequence, issued_at_seconds, token_text, action,
    ).map_err(bad)?;
    let mut output = Vec::with_capacity(96);
    output.extend_from_slice(&signed.owner_signing_public_key);
    output.extend_from_slice(&signed.signature);
    Ok(output)
}

/// Returns owner public key || signature for a constrained mailbox pull/ack.
/// Pull has an empty or 16-byte cursor and no items. Ack has an empty cursor
/// and 1..=16 packed 16-byte (id, receipt) pairs, strictly ordered by id.
/// This does not perform networking or authenticate the current peer pin.
pub fn sign_push_mailbox_access(
    local_binding: Vec<u8>,
    capability: Vec<u8>,
    device: Vec<u8>,
    issued_at_seconds: u64,
    after: Vec<u8>,
    items: Vec<u8>,
    acknowledge: bool,
) -> Result<Vec<u8>, CoreError> {
    let capability = Zeroizing::new(capability);
    let after = Zeroizing::new(after);
    let items = Zeroizing::new(items);
    if !valid_binding(&local_binding) || (!acknowledge && !items.is_empty()) || (acknowledge && !after.is_empty()) {
        return Err(bad("invalid mailbox access input"));
    }
    let access = if acknowledge { MailboxAccess::Ack { items: &items } } else { MailboxAccess::Pull { after: &after } };
    let signed = sign_mailbox_access(&local_binding, &capability, &device, issued_at_seconds, access).map_err(bad)?;
    let mut output = Vec::with_capacity(96);
    output.extend_from_slice(&signed.owner_signing_public_key);
    output.extend_from_slice(&signed.signature);
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::signing_identity::{
        clear_signing_identity, install_signing_identity, signing_identity_registry_guard,
        InstalledSigningIdentity,
    };
    use zeroize::Zeroize;

    fn legacy(n: char) -> String { format!("pk_{}", n.to_string().repeat(32)) }
    fn binding(n: char, seed: u8, exchange: u8) -> Vec<u8> {
        let identity = InstalledSigningIdentity::from_seed(1, legacy(n), &[seed; 32]).unwrap();
        identity.create_file_exchange_binding(identity.create_binding(100).unwrap(), &[exchange; 32], 200)
            .unwrap().to_bytes().unwrap()
    }
    fn hex(bytes: &[u8]) -> String { bytes.iter().map(|b| format!("{:02x}", b)).collect() }

    #[test]
    fn ffi_round_trip_matches_independent_directional_and_signature_vectors() {
        let _guard = signing_identity_registry_guard();
        clear_signing_identity();
        let alice = binding('1', 1, 3);
        let bob = binding('2', 2, 4);
        let epoch = vec![9; 16];
        install_signing_identity(1, legacy('1'), &[1; 32]).unwrap();
        let mut sender = derive_push_wake_material(alice.clone(), bob.clone(), vec![3; 32], epoch.clone(), true).unwrap();
        install_signing_identity(1, legacy('2'), &[2; 32]).unwrap();
        let mut receiver = derive_push_wake_material(bob.clone(), alice.clone(), vec![4; 32], epoch, false).unwrap();
        assert_eq!(sender, receiver);
        assert_eq!(hex(&receiver[..32]), "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394");
        assert_eq!(hex(&receiver[32..]), "e684e7708138f2e247613a7784c120d6154816a7bd1d88a54c53f155d8e9617a");
        let signature = sign_push_wake_registration(
            bob, receiver[32..].to_vec(), vec![0x56; 16], 17, 1_700_000_000,
            b"fcm-test-token-abcdefghijklmnop".to_vec(), false,
        ).unwrap();
        assert_eq!(signature.len(), 96);
        assert_eq!(signature[..32], receiver[..32]);
        assert_eq!(hex(&signature[32..]), "51d08645a428e7615aaf8cec4d606f9c2a0b65f98a3703844cd0a10156ba10c67971f74b6ee86a90374c37343bd714b82f18560c0b8c389e8297ae1ef178af03");
        sender.zeroize();
        receiver.zeroize();
        clear_signing_identity();
    }

    #[test]
    fn mailbox_ffi_accepts_only_the_constrained_pull_and_ack_formats() {
        let _guard = signing_identity_registry_guard();
        clear_signing_identity();
        let bob = binding('2', 2, 4);
        install_signing_identity(1, legacy('2'), &[2; 32]).unwrap();
        let cap = vec![7; 32];
        let device = vec![0x56; 16];
        let pull = sign_push_mailbox_access(bob.clone(), cap.clone(), device.clone(), 1_700_000_000, vec![], vec![], false).unwrap();
        assert_eq!(pull.len(), 96);
        assert_eq!(hex(&pull[..32]), "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394");
        let mut item = vec![0x11; 16];
        item.extend_from_slice(&[0x22; 16]);
        assert_eq!(sign_push_mailbox_access(bob.clone(), cap.clone(), device.clone(), 1, vec![], item.clone(), true).unwrap().len(), 96);
        assert!(sign_push_mailbox_access(bob.clone(), cap.clone(), device.clone(), 1, vec![], item.clone(), false).is_err());
        assert!(sign_push_mailbox_access(bob.clone(), cap.clone(), device.clone(), 1, vec![1; 16], item.clone(), true).is_err());
        assert!(sign_push_mailbox_access(bob.clone(), cap.clone(), device.clone(), 1, vec![], vec![], true).is_err());
        install_signing_identity(1, legacy('1'), &[1; 32]).unwrap();
        assert!(sign_push_mailbox_access(bob, cap, device, 1, vec![], item, true).is_err());
        clear_signing_identity();
    }

    #[test]
    fn rejects_changed_identity_unverified_inputs_and_invalid_tokens() {
        let _guard = signing_identity_registry_guard();
        clear_signing_identity();
        let alice = binding('1', 1, 3);
        let bob = binding('2', 2, 4);
        install_signing_identity(1, legacy('1'), &[1; 32]).unwrap();
        assert!(derive_push_wake_material(bob.clone(), alice.clone(), vec![4; 32], vec![9; 16], false).is_err());
        assert!(derive_push_wake_material(alice.clone(), bob.clone(), vec![3; 32], vec![0; 16], true).is_err());
        assert!(derive_push_wake_material(alice.clone(), vec![0; 513], vec![3; 32], vec![9; 16], true).is_err());
        assert!(sign_push_wake_registration(alice.clone(), vec![7; 32], vec![8; 16], 1, 1, vec![0xff], false).is_err());
        assert!(sign_push_wake_registration(alice.clone(), vec![7; 32], vec![8; 16], 1, 1, vec![b'x'; 2049], false).is_err());
        assert!(sign_push_wake_registration(alice, vec![7; 32], vec![8; 16], 1, 1, b"not-empty".to_vec(), true).is_err());
        clear_signing_identity();
    }
}
