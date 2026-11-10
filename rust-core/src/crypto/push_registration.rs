//! Owner-signed registration for the disabled remote-wake prototype.
//!
//! This is not an Android/UniFFI API and does not register anything. It signs
//! only the domain-separated registration/revocation fields understood by the
//! experimental Worker; arbitrary payload signing is deliberately unavailable.
//! The caller must serialize or revalidate the local identity and peer pin
//! before a network side effect, including after any asynchronous boundary.

use sha2::{Digest, Sha256};
use std::io::Write;

use crate::crypto::signing_identity::{installed_signing_identity, SignedFileExchangeBindingV1};

const DOMAIN: &[u8] = b"apu-push-registration-v1\n";
const MAX_SAFE_INTEGER: u64 = 9_007_199_254_740_991;
const MAX_TOKEN_BYTES: usize = 2048;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum PushRegistrationAction {
    Register,
    Revoke,
}

/// Signature and receiver key are public; capability and token are never returned.
pub struct SignedPushRegistration {
    pub owner_signing_public_key: [u8; 32],
    pub signature: [u8; 64],
}

/// Sign a canonical operation under the currently installed owner identity.
/// The caller owns the epoch/capability and must use the validated peer pin.
/// Sequence must be persisted monotonically per (route, device) before sending.
pub fn sign_push_registration(
    local_binding_bytes: &[u8],
    capability: &[u8],
    device: &[u8],
    sequence: u64,
    issued_at_seconds: u64,
    token: &str,
    action: PushRegistrationAction,
) -> Result<SignedPushRegistration, &'static str> {
    if capability.len() != 32 || capability.iter().all(|b| *b == 0) {
        return Err("invalid push capability");
    }
    if device.len() != 16 || device.iter().all(|b| *b == 0) {
        return Err("invalid push device");
    }
    if sequence == 0 || sequence > MAX_SAFE_INTEGER || issued_at_seconds == 0 || issued_at_seconds > MAX_SAFE_INTEGER {
        return Err("invalid push registration counter or time");
    }
    match action {
        PushRegistrationAction::Register if !valid_token(token) => return Err("invalid push token"),
        PushRegistrationAction::Revoke if !token.is_empty() => return Err("invalid revoke token"),
        _ => {}
    }

    let identity = installed_signing_identity().ok_or("local identity unavailable")?;
    let binding = SignedFileExchangeBindingV1::from_bytes(local_binding_bytes)
        .map_err(|_| "invalid local binding")?;
    binding.verify().map_err(|_| "invalid local binding")?;
    if binding.identity_binding.legacy_routing_node_id != identity.legacy_routing_node_id()
        || binding.identity_binding.signing_public_key != identity.public_key()
        || identity.public_key().len() != 32
    {
        return Err("local identity changed");
    }

    let mut owner = [0u8; 32];
    owner.copy_from_slice(identity.public_key());
    let mut payload = Vec::with_capacity(300);
    payload.extend_from_slice(DOMAIN);
    payload.extend_from_slice(match action {
        PushRegistrationAction::Register => b"register\n" as &[u8],
        PushRegistrationAction::Revoke => b"revoke\n" as &[u8],
    });
    append_hex(&mut payload, &owner);
    payload.push(b'\n');
    append_hex(&mut payload, capability);
    payload.push(b'\n');
    append_hex(&mut payload, device);
    // Writing integers into Vec cannot fail; no user-controlled free text is
    // interpolated into the canonical payload.
    write!(&mut payload, "\n{sequence}\n{issued_at_seconds}\n").map_err(|_| "push encoding failed")?;
    append_hex(&mut payload, &Sha256::digest(token.as_bytes()));
    payload.push(b'\n');
    let signed = identity.sign_security_payload(&payload);
    payload.fill(0); // best effort; see REMOTE_PUSH_DESIGN.md on memory erasure
    let signature: [u8; 64] = signed.as_slice().try_into().map_err(|_| "push signing failed")?;
    Ok(SignedPushRegistration { owner_signing_public_key: owner, signature })
}

fn valid_token(token: &str) -> bool {
    (20..=MAX_TOKEN_BYTES).contains(&token.len()) && token.bytes().all(|b| {
        b.is_ascii_alphanumeric() || matches!(b, b'_' | b':' | b'.' | b'-')
    })
}

fn append_hex(output: &mut Vec<u8>, bytes: &[u8]) {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    for byte in bytes {
        output.push(HEX[(byte >> 4) as usize]);
        output.push(HEX[(byte & 15) as usize]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::keys::Ed25519KeyPair;
    use crate::crypto::signing_identity::{
        clear_signing_identity, install_signing_identity, signing_identity_registry_guard,
        InstalledSigningIdentity,
    };

    fn legacy(n: char) -> String { format!("pk_{}", n.to_string().repeat(32)) }
    fn binding(n: char, seed: u8, exchange: u8) -> Vec<u8> {
        let identity = InstalledSigningIdentity::from_seed(1, legacy(n), &[seed; 32]).unwrap();
        identity.create_file_exchange_binding(identity.create_binding(100).unwrap(), &[exchange; 32], 200)
            .unwrap().to_bytes().unwrap()
    }
    fn hex(bytes: &[u8]) -> String { bytes.iter().map(|b| format!("{:02x}", b)).collect() }
    fn unhex(value: &str) -> Vec<u8> {
        value.as_bytes().chunks_exact(2).map(|pair| {
            u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap()
        }).collect()
    }

    #[test]
    fn registration_matches_independent_node_signature_vector() {
        let _guard = signing_identity_registry_guard();
        clear_signing_identity();
        let local = binding('2', 2, 4);
        install_signing_identity(1, legacy('2'), &[2; 32]).unwrap();
        let cap = unhex("e684e7708138f2e247613a7784c120d6154816a7bd1d88a54c53f155d8e9617a");
        let signed = sign_push_registration(&local, &cap, &[0x56; 16], 17, 1_700_000_000,
            "fcm-test-token-abcdefghijklmnop", PushRegistrationAction::Register).unwrap();
        assert_eq!(hex(&signed.owner_signing_public_key), "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394");
        assert_eq!(hex(&signed.signature), "51d08645a428e7615aaf8cec4d606f9c2a0b65f98a3703844cd0a10156ba10c67971f74b6ee86a90374c37343bd714b82f18560c0b8c389e8297ae1ef178af03");
        let token_hash = "0888cbe168defc517d4f1d0fc30cf5d813403503d8cf4e3e1f58c8f4998ae70f";
        let payload = format!("apu-push-registration-v1\nregister\n{}\n{}\n{}\n17\n1700000000\n{}\n",
            hex(&signed.owner_signing_public_key), hex(&cap), hex(&[0x56; 16]), token_hash);
        assert!(Ed25519KeyPair::verify(&signed.owner_signing_public_key, payload.as_bytes(), &signed.signature).is_ok());
        clear_signing_identity();
    }

    #[test]
    fn rejects_unavailable_or_changed_identity_and_bad_parameters() {
        let _guard = signing_identity_registry_guard();
        clear_signing_identity();
        let local = binding('2', 2, 4);
        let cap = [9u8; 32];
        let device = [4u8; 16];
        let token = "fcm-test-token-abcdefghijklmnop";
        let sign = |cap: &[u8], device: &[u8], sequence: u64, time: u64, token: &str, action| {
            sign_push_registration(&local, cap, device, sequence, time, token, action).is_ok()
        };
        assert!(!sign(&cap, &device, 1, 1, token, PushRegistrationAction::Register));
        install_signing_identity(1, legacy('1'), &[1; 32]).unwrap();
        assert!(!sign(&cap, &device, 1, 1, token, PushRegistrationAction::Register));
        install_signing_identity(1, legacy('2'), &[2; 32]).unwrap();
        assert!(sign(&cap, &device, 1, 1, token, PushRegistrationAction::Register));
        assert!(!sign(&[0; 32], &device, 1, 1, token, PushRegistrationAction::Register));
        assert!(!sign(&cap, &[0; 16], 1, 1, token, PushRegistrationAction::Register));
        assert!(!sign(&cap, &device, 0, 1, token, PushRegistrationAction::Register));
        assert!(!sign(&cap, &device, MAX_SAFE_INTEGER + 1, 1, token, PushRegistrationAction::Register));
        assert!(!sign(&cap, &device, 1, 0, token, PushRegistrationAction::Register));
        assert!(!sign(&cap, &device, 1, 1, "short", PushRegistrationAction::Register));
        assert!(!sign(&cap, &device, 1, 1, "token-with-newline-abcdefgh\n", PushRegistrationAction::Register));
        assert!(!sign(&cap, &device, 1, 1, token, PushRegistrationAction::Revoke));
        assert!(sign(&cap, &device, 1, 1, "", PushRegistrationAction::Revoke));
        let mut tampered = local.clone();
        *tampered.last_mut().unwrap() ^= 1;
        assert!(sign_push_registration(&tampered, &cap, &device, 1, 1, token, PushRegistrationAction::Register).is_err());
        clear_signing_identity();
    }
}
