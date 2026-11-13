//! Restricted owner signatures for the disabled experimental ciphertext mailbox.
//!
//! Only the two canonical mailbox operations can be signed; callers cannot use
//! this API as a generic signing oracle. The caller must obtain the capability
//! from a previously pinned peer and coordinate identity, pin, restore and the
//! eventual network effect. A snapshot check alone does not prevent ABA.

use std::io::Write;
use zeroize::Zeroizing;

use crate::crypto::signing_identity::{installed_signing_identity, SignedFileExchangeBindingV1};

const DOMAIN: &[u8] = b"apu-mailbox-access-v1\n";
const MAX_SAFE_INTEGER: u64 = 9_007_199_254_740_991;
const MAX_ITEMS: usize = 16;
const ID_BYTES: usize = 16;
const ITEM_BYTES: usize = ID_BYTES * 2;
const MAX_BINDING_BYTES: usize = 512;

/// Pull uses an empty cursor for the first page or a 16-byte previous id.
/// Ack uses 1..=16 packed (id, receipt) pairs, sorted strictly by id.
pub enum MailboxAccess<'a> {
    Pull { after: &'a [u8] },
    Ack { items: &'a [u8] },
}

pub struct SignedMailboxAccess {
    pub owner_signing_public_key: [u8; 32],
    pub signature: [u8; 64],
}

/// Sign exactly one bounded mailbox operation under the installed identity.
/// Receipt bytes and capability are not returned; temporary payload is wiped.
pub fn sign_mailbox_access(
    local_binding_bytes: &[u8],
    capability: &[u8],
    device: &[u8],
    issued_at_seconds: u64,
    access: MailboxAccess<'_>,
) -> Result<SignedMailboxAccess, &'static str> {
    if local_binding_bytes.is_empty() || local_binding_bytes.len() > MAX_BINDING_BYTES {
        return Err("invalid mailbox binding");
    }
    if capability.len() != 32 || capability.iter().all(|b| *b == 0) {
        return Err("invalid mailbox capability");
    }
    if !valid_id(device) || issued_at_seconds == 0 || issued_at_seconds > MAX_SAFE_INTEGER {
        return Err("invalid mailbox device or time");
    }
    match &access {
        MailboxAccess::Pull { after } if !after.is_empty() && !valid_id(after) => {
            return Err("invalid mailbox cursor");
        }
        MailboxAccess::Ack { items } => {
            if items.is_empty() || items.len() > MAX_ITEMS * ITEM_BYTES || items.len() % ITEM_BYTES != 0 {
                return Err("invalid mailbox acknowledgements");
            }
            let mut previous: Option<&[u8]> = None;
            for item in items.chunks_exact(ITEM_BYTES) {
                let (id, receipt) = item.split_at(ID_BYTES);
                if !valid_id(id) || !valid_id(receipt) || previous.is_some_and(|last| last >= id) {
                    return Err("invalid mailbox acknowledgements");
                }
                previous = Some(id);
            }
        }
        _ => {}
    }

    let identity = installed_signing_identity().ok_or("local identity unavailable")?;
    let binding = SignedFileExchangeBindingV1::from_bytes(local_binding_bytes).map_err(|_| "invalid local binding")?;
    binding.verify().map_err(|_| "invalid local binding")?;
    if binding.identity_binding.legacy_routing_node_id != identity.legacy_routing_node_id()
        || binding.identity_binding.signing_public_key != identity.public_key()
        || identity.public_key().len() != 32
    {
        return Err("local identity changed");
    }
    let mut owner = [0u8; 32];
    owner.copy_from_slice(identity.public_key());
    let mut payload = Zeroizing::new(Vec::with_capacity(1300));
    payload.extend_from_slice(DOMAIN);
    payload.extend_from_slice(match &access {
        MailboxAccess::Pull { .. } => b"pull\n" as &[u8],
        MailboxAccess::Ack { .. } => b"ack\n" as &[u8],
    });
    append_hex(&mut payload, &owner);
    payload.push(b'\n');
    append_hex(&mut payload, capability);
    payload.push(b'\n');
    append_hex(&mut payload, device);
    write!(&mut payload, "\n{issued_at_seconds}\n").map_err(|_| "mailbox encoding failed")?;
    match access {
        MailboxAccess::Pull { after } => {
            if after.is_empty() { payload.push(b'-'); } else { append_hex(&mut payload, after); }
            payload.push(b'\n');
        }
        MailboxAccess::Ack { items } => {
            write!(&mut payload, "{}\n", items.len() / ITEM_BYTES).map_err(|_| "mailbox encoding failed")?;
            for item in items.chunks_exact(ITEM_BYTES) {
                append_hex(&mut payload, &item[..ID_BYTES]);
                payload.push(b':');
                append_hex(&mut payload, &item[ID_BYTES..]);
                payload.push(b'\n');
            }
        }
    }
    let signed = identity.sign_security_payload(&payload);
    let signature: [u8; 64] = signed.as_slice().try_into().map_err(|_| "mailbox signing failed")?;
    Ok(SignedMailboxAccess { owner_signing_public_key: owner, signature })
}

fn valid_id(bytes: &[u8]) -> bool { bytes.len() == ID_BYTES && bytes.iter().any(|b| *b != 0) }

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
    use crate::crypto::signing_identity::{
        clear_signing_identity, install_signing_identity, signing_identity_registry_guard, InstalledSigningIdentity,
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
    fn pull_and_ack_match_independent_node_ed25519_vectors() {
        let _guard = signing_identity_registry_guard();
        clear_signing_identity();
        let local = binding('2', 2, 4);
        install_signing_identity(1, legacy('2'), &[2; 32]).unwrap();
        let cap = unhex("e684e7708138f2e247613a7784c120d6154816a7bd1d88a54c53f155d8e9617a");
        let pull = sign_mailbox_access(&local, &cap, &[0x56; 16], 1_700_000_000, MailboxAccess::Pull { after: &[] }).unwrap();
        assert_eq!(hex(&pull.owner_signing_public_key), "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394");
        assert_eq!(hex(&pull.signature), "8283a87b9ae172188e4c7dd879bb456d0474eba22acf0f8f3b83d56b5f87f22533506e7a5eb3256263b65d3581c6c1d3f2502f4416347aab80212ba486b2d00f");
        let resumed = sign_mailbox_access(&local, &cap, &[0x56; 16], 1_700_000_000, MailboxAccess::Pull { after: &[0x33; 16] }).unwrap();
        assert_eq!(hex(&resumed.signature), "5df1c4eaa4fb6b02de113ddfcdaba5f7cd5841538e23c160986babaf8c0e86239aa9d5d2f108edf4d16fd7e751f811d285613e419d5afcebeed6ec0c56dd3c0f");
        let mut items = vec![0x11; 16];
        items.extend_from_slice(&[0x22; 16]);
        items.extend_from_slice(&[0x33; 16]);
        items.extend_from_slice(&[0x44; 16]);
        let ack = sign_mailbox_access(&local, &cap, &[0x56; 16], 1_700_000_000, MailboxAccess::Ack { items: &items }).unwrap();
        assert_eq!(hex(&ack.signature), "9e5945387e6560401b96bf3ddedf5914f34f7665e0cd517b797498785535d9dae0972f64a26c661bf5578137616ca994408ae6fd23982422e1bf1a400e96e40e");
        clear_signing_identity();
    }

    #[test]
    fn rejects_changed_identity_bad_inputs_and_unsorted_or_duplicate_receipts() {
        let _guard = signing_identity_registry_guard();
        clear_signing_identity();
        let local = binding('2', 2, 4);
        let cap = [9u8; 32];
        let device = [4u8; 16];
        let sign = |cap: &[u8], device: &[u8], time: u64, access| {
            sign_mailbox_access(&local, cap, device, time, access).is_ok()
        };
        assert!(!sign(&cap, &device, 1, MailboxAccess::Pull { after: &[] }));
        install_signing_identity(1, legacy('1'), &[1; 32]).unwrap();
        assert!(!sign(&cap, &device, 1, MailboxAccess::Pull { after: &[] }));
        install_signing_identity(1, legacy('2'), &[2; 32]).unwrap();
        assert!(sign(&cap, &device, 1, MailboxAccess::Pull { after: &[] }));
        assert!(sign(&cap, &device, 1, MailboxAccess::Pull { after: &[1; 16] }));
        assert!(!sign(&[0; 32], &device, 1, MailboxAccess::Pull { after: &[] }));
        assert!(!sign(&cap, &[0; 16], 1, MailboxAccess::Pull { after: &[] }));
        assert!(!sign(&cap, &device, 0, MailboxAccess::Pull { after: &[] }));
        assert!(!sign(&cap, &device, MAX_SAFE_INTEGER + 1, MailboxAccess::Pull { after: &[] }));
        assert!(!sign(&cap, &device, 1, MailboxAccess::Pull { after: &[0; 16] }));
        assert!(!sign(&cap, &device, 1, MailboxAccess::Pull { after: &[1; 15] }));
        assert!(!sign(&cap, &device, 1, MailboxAccess::Ack { items: &[] }));
        assert!(!sign(&cap, &device, 1, MailboxAccess::Ack { items: &[1; 31] }));
        assert!(!sign(&cap, &device, 1, MailboxAccess::Ack { items: &[1; ITEM_BYTES * 17] }));
        let duplicated = vec![1; ITEM_BYTES * 2];
        assert!(!sign(&cap, &device, 1, MailboxAccess::Ack { items: &duplicated }));
        let mut ascending = duplicated.clone();
        ascending[ITEM_BYTES] = 2;
        assert!(sign(&cap, &device, 1, MailboxAccess::Ack { items: &ascending }));
        let mut descending = vec![2; ITEM_BYTES * 2];
        descending[ITEM_BYTES..].fill(1);
        assert!(!sign(&cap, &device, 1, MailboxAccess::Ack { items: &descending }));
        let mut zero_receipt = vec![1; ITEM_BYTES];
        zero_receipt[ID_BYTES..].fill(0);
        assert!(!sign(&cap, &device, 1, MailboxAccess::Ack { items: &zero_receipt }));
        let mut tampered = local.clone();
        *tampered.last_mut().unwrap() ^= 1;
        assert!(sign_mailbox_access(&tampered, &cap, &device, 1, MailboxAccess::Pull { after: &[] }).is_err());
        clear_signing_identity();
    }
}
