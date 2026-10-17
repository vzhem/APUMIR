//! P3 of `docs/MULTI_DEVICE_IDENTITY.md`: pairing a new device with the primary device.
//!
//! The primary shows an offer (QR payload): account id, account public key, one-time 32-byte
//! secret and expiry. The new device sends a request over ordinary 1:1 delivery (LAN or swarm;
//! the transport is chosen by the core, the protocol is identical). Both sides run X25519 and
//! derive a root key from the shared secret, the offer secret and the transcript. The user
//! compares the 6-digit code on both screens. The primary then sends the device certificate,
//! encrypted under the root key. The new device checks it against the account key from the QR.
//!
//! Ephemeral secrets live in process memory only. If the process restarts, pairing starts again.

use std::sync::Mutex;

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    ChaCha20Poly1305, Key, Nonce,
};
use hkdf::Hkdf;
use rand::{rngs::OsRng, RngCore};
use sha2::Sha256;

use crate::crypto::device_certificate::{
    issue_device_certificate, verify_device_certificate, DeviceCertificateV1, DEVICE_ID_BYTES,
};
use crate::crypto::keys::{Ed25519KeyPair, X25519KeyPair};
use crate::crypto::signing_identity::is_legacy_routing_node_id;

pub const PAIRING_FORMAT_V1: u8 = 1;
pub const PAIRING_OFFER_TTL_MS: i64 = 5 * 60 * 1000;
const MAX_SESSIONS: usize = 4;
const SECRET_BYTES: usize = 32;
const NONCE_BYTES: usize = 32;
const KEY_BYTES: usize = 32;
const MAX_LEGACY_ROUTING_ID_BYTES: usize = 67;
const DOMAIN: &[u8] = b"apu-device-pairing-v1";

const MSG_REQUEST: u8 = 1;
const MSG_ANSWER: u8 = 2;
const MSG_GRANT: u8 = 3;

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum PairingError {
    #[error("malformed pairing message")]
    Malformed,
    #[error("unsupported pairing format {0}")]
    UnsupportedFormat(u8),
    #[error("no active pairing offer")]
    NoOffer,
    #[error("pairing offer expired")]
    Expired,
    #[error("pairing authentication failed")]
    AuthFailed,
    #[error("no pairing session for this device")]
    UnknownSession,
    #[error("device certificate does not match this pairing")]
    CertificateMismatch,
    #[error("crypto failure: {0}")]
    Crypto(String),
}

/// Keyed PRF (HKDF-Expand with the key as PRK, which is HMAC-SHA256 underneath).
fn prf(key: &[u8], message: &[u8]) -> Result<[u8; 32], PairingError> {
    let hk = Hkdf::<Sha256>::from_prk(key).map_err(|_| PairingError::Crypto("prk".into()))?;
    let mut out = [0u8; 32];
    hk.expand(message, &mut out)
        .map_err(|_| PairingError::Crypto("expand".into()))?;
    Ok(out)
}

fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

struct Transcript {
    bytes: Vec<u8>,
}

impl Transcript {
    #[allow(clippy::too_many_arguments)]
    fn new(
        legacy: &str,
        account_pk: &[u8],
        device_id: &[u8; DEVICE_ID_BYTES],
        device_pk: &[u8],
        nonce_n: &[u8],
        eph_n: &[u8],
        nonce_p: &[u8],
        eph_p: &[u8],
    ) -> Self {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(DOMAIN);
        bytes.extend_from_slice(&(legacy.len() as u16).to_be_bytes());
        bytes.extend_from_slice(legacy.as_bytes());
        for part in [
            account_pk,
            &device_id[..],
            device_pk,
            nonce_n,
            eph_n,
            nonce_p,
            eph_p,
        ] {
            bytes.extend_from_slice(part);
        }
        Self { bytes }
    }
}

struct OfferState {
    legacy: String,
    account_pk: Vec<u8>,
    secret: [u8; SECRET_BYTES],
    expires_at_ms: i64,
    sessions: Vec<PrimarySession>,
}

struct PrimarySession {
    device_id: [u8; DEVICE_ID_BYTES],
    device_pk: Vec<u8>,
    eph_n: Vec<u8>,
    eph_p: X25519KeyPair,
    root: [u8; 32],
    transcript: Vec<u8>,
}

struct JoinerState {
    legacy: String,
    account_pk: Vec<u8>,
    secret: [u8; SECRET_BYTES],
    expires_at_ms: i64,
    device_id: [u8; DEVICE_ID_BYTES],
    device_pk: Vec<u8>,
    eph_n: X25519KeyPair,
    eph_p_pub: Vec<u8>,
    nonce_n: [u8; NONCE_BYTES],
    nonce_p: Vec<u8>,
    root: Option<[u8; 32]>,
    transcript: Vec<u8>,
}

static OFFER: Mutex<Option<OfferState>> = Mutex::new(None);
static JOINER: Mutex<Option<JoinerState>> = Mutex::new(None);

fn random<const N: usize>() -> [u8; N] {
    let mut out = [0u8; N];
    OsRng.fill_bytes(&mut out);
    out
}

/// Six-digit code, shown on both screens. Groups of three: "123 456".
fn sas_code(root: &[u8; 32], transcript: &[u8]) -> Result<String, PairingError> {
    let mut msg = b"sas".to_vec();
    msg.extend_from_slice(transcript);
    let digest = prf(root, &msg)?;
    let value = u32::from_be_bytes([digest[0], digest[1], digest[2], digest[3]]) % 1_000_000;
    Ok(format!("{:03} {:03}", value / 1000, value % 1000))
}

fn derive_enc_key(root: &[u8; 32], transcript: &[u8]) -> Result<[u8; 32], PairingError> {
    let mut msg = b"enc".to_vec();
    msg.extend_from_slice(transcript);
    prf(root, &msg)
}

fn derive_root(
    secret: &[u8; SECRET_BYTES],
    shared: &[u8],
    transcript: &[u8],
) -> Result<[u8; 32], PairingError> {
    let mut msg = b"root".to_vec();
    msg.extend_from_slice(&(shared.len() as u16).to_be_bytes());
    msg.extend_from_slice(shared);
    msg.extend_from_slice(transcript);
    prf(secret, &msg)
}

fn request_mac(
    secret: &[u8; SECRET_BYTES],
    device_id: &[u8; DEVICE_ID_BYTES],
    device_pk: &[u8],
    nonce_n: &[u8],
    eph_n: &[u8],
) -> Result<[u8; 32], PairingError> {
    let mut msg = b"req".to_vec();
    msg.extend_from_slice(device_id);
    msg.extend_from_slice(device_pk);
    msg.extend_from_slice(nonce_n);
    msg.extend_from_slice(eph_n);
    prf(secret, &msg)
}

fn answer_mac(secret: &[u8; SECRET_BYTES], transcript: &[u8]) -> Result<[u8; 32], PairingError> {
    let mut msg = b"ans".to_vec();
    msg.extend_from_slice(transcript);
    prf(secret, &msg)
}

fn check_offer_time(expires_at_ms: i64, now_ms: i64) -> Result<(), PairingError> {
    if now_ms >= expires_at_ms {
        Err(PairingError::Expired)
    } else {
        Ok(())
    }
}

/// Primary device: open a new offer (replaces any previous one). Returns the QR payload.
pub fn open_pairing_offer(
    legacy_routing_node_id: &str,
    account_public_key: &[u8],
    now_ms: i64,
) -> Result<Vec<u8>, PairingError> {
    if !is_legacy_routing_node_id(legacy_routing_node_id)
        || legacy_routing_node_id.len() > MAX_LEGACY_ROUTING_ID_BYTES
        || account_public_key.len() != KEY_BYTES
        || now_ms < 0
    {
        return Err(PairingError::Malformed);
    }
    let secret: [u8; SECRET_BYTES] = random();
    let expires = now_ms + PAIRING_OFFER_TTL_MS;
    let mut out = vec![PAIRING_FORMAT_V1];
    out.extend_from_slice(&(legacy_routing_node_id.len() as u16).to_be_bytes());
    out.extend_from_slice(legacy_routing_node_id.as_bytes());
    out.extend_from_slice(account_public_key);
    out.extend_from_slice(&secret);
    out.extend_from_slice(&expires.to_be_bytes());
    let mut guard = OFFER.lock().map_err(|_| PairingError::Malformed)?;
    *guard = Some(OfferState {
        legacy: legacy_routing_node_id.to_string(),
        account_pk: account_public_key.to_vec(),
        secret,
        expires_at_ms: expires,
        sessions: Vec::new(),
    });
    Ok(out)
}

/// Close the active offer and drop every pending session.
pub fn close_pairing_offer() {
    if let Ok(mut guard) = OFFER.lock() {
        *guard = None;
    }
}

pub struct PrimaryAnswer {
    pub answer: Vec<u8>,
    pub sas: String,
    pub device_id: [u8; DEVICE_ID_BYTES],
}

/// Primary device: verify a request and build the answer and the code to show.
pub fn primary_handle_request(request: &[u8], now_ms: i64) -> Result<PrimaryAnswer, PairingError> {
    let mut parsed = Parsed::new(request, MSG_REQUEST)?;
    let device_id: [u8; DEVICE_ID_BYTES] = parsed.take(DEVICE_ID_BYTES)?.try_into().unwrap();
    let device_pk = parsed.take(KEY_BYTES)?.to_vec();
    let nonce_n = parsed.take(NONCE_BYTES)?.to_vec();
    let eph_n = parsed.take(KEY_BYTES)?.to_vec();
    let mac = parsed.take(32)?.to_vec();
    parsed.finish()?;

    let mut guard = OFFER.lock().map_err(|_| PairingError::Malformed)?;
    let offer = guard.as_mut().ok_or(PairingError::NoOffer)?;
    check_offer_time(offer.expires_at_ms, now_ms)?;
    let expected = request_mac(&offer.secret, &device_id, &device_pk, &nonce_n, &eph_n)?;
    if !ct_eq(&expected, &mac) {
        return Err(PairingError::AuthFailed);
    }

    let nonce_p: [u8; NONCE_BYTES] = random();
    let eph_p = X25519KeyPair::generate();
    let eph_p_pub = eph_p.public_key().0.to_vec();
    let transcript = Transcript::new(
        &offer.legacy,
        &offer.account_pk,
        &device_id,
        &device_pk,
        &nonce_n,
        &eph_n,
        &nonce_p,
        &eph_p_pub,
    )
    .bytes;
    let shared = eph_p
        .diffie_hellman(&eph_n)
        .map_err(|e| PairingError::Crypto(e.to_string()))?;
    let root = derive_root(&offer.secret, &shared, &transcript)?;
    let sas = sas_code(&root, &transcript)?;
    let answer_tag = answer_mac(&offer.secret, &transcript)?;

    let mut answer = vec![PAIRING_FORMAT_V1, MSG_ANSWER];
    answer.extend_from_slice(&nonce_p);
    answer.extend_from_slice(&eph_p_pub);
    answer.extend_from_slice(&answer_tag);

    offer.sessions.retain(|s| s.device_id != device_id);
    if offer.sessions.len() >= MAX_SESSIONS {
        offer.sessions.remove(0);
    }
    offer.sessions.push(PrimarySession {
        device_id,
        device_pk,
        eph_n,
        eph_p,
        root,
        transcript,
    });
    Ok(PrimaryAnswer {
        answer,
        sas,
        device_id,
    })
}

pub struct PrimaryGrant {
    pub grant: Vec<u8>,
    pub certificate: DeviceCertificateV1,
}

/// Primary device: the user confirmed the code. Issue the certificate and encrypt it for the device.
pub fn primary_confirm(
    device_id: &[u8; DEVICE_ID_BYTES],
    account_key: &Ed25519KeyPair,
    now_ms: i64,
) -> Result<PrimaryGrant, PairingError> {
    let mut guard = OFFER.lock().map_err(|_| PairingError::Malformed)?;
    let offer = guard.as_mut().ok_or(PairingError::NoOffer)?;
    check_offer_time(offer.expires_at_ms, now_ms)?;
    let index = offer
        .sessions
        .iter()
        .position(|s| &s.device_id == device_id)
        .ok_or(PairingError::UnknownSession)?;
    let session = offer.sessions.remove(index);
    let certificate = issue_device_certificate(
        account_key,
        &offer.legacy,
        session.device_id,
        &session.device_pk,
        now_ms,
    )
    .map_err(|e| PairingError::Crypto(e.to_string()))?;
    let plaintext = certificate
        .to_bytes()
        .map_err(|e| PairingError::Crypto(e.to_string()))?;

    let enc_key = derive_enc_key(&session.root, &session.transcript)?;
    let cipher = ChaCha20Poly1305::new(Key::from_slice(&enc_key));
    let nonce = Nonce::from_slice(&[0u8; 12]);
    let ciphertext = cipher
        .encrypt(
            nonce,
            Payload {
                msg: &plaintext,
                aad: &session.transcript,
            },
        )
        .map_err(|_| PairingError::Crypto("seal".into()))?;
    let mut grant = vec![PAIRING_FORMAT_V1, MSG_GRANT];
    grant.extend_from_slice(&ciphertext);
    Ok(PrimaryGrant { grant, certificate })
}

/// New device: start pairing from a scanned offer. Returns the request to deliver.
pub fn joiner_start(
    offer: &[u8],
    device_id: &[u8; DEVICE_ID_BYTES],
    device_public_key: &[u8],
    now_ms: i64,
) -> Result<Vec<u8>, PairingError> {
    let mut parsed = Parsed::new(offer, 0)?;
    let legacy_len = parsed.take_u16()? as usize;
    if legacy_len == 0 || legacy_len > MAX_LEGACY_ROUTING_ID_BYTES {
        return Err(PairingError::Malformed);
    }
    let legacy = std::str::from_utf8(parsed.take(legacy_len)?)
        .map_err(|_| PairingError::Malformed)?
        .to_string();
    let account_pk = parsed.take(KEY_BYTES)?.to_vec();
    let secret: [u8; SECRET_BYTES] = parsed.take(SECRET_BYTES)?.try_into().unwrap();
    let expires = parsed.take_i64()?;
    parsed.finish()?;
    if !is_legacy_routing_node_id(&legacy) {
        return Err(PairingError::Malformed);
    }
    check_offer_time(expires, now_ms)?;
    if device_public_key.len() != KEY_BYTES {
        return Err(PairingError::Malformed);
    }

    let nonce_n: [u8; NONCE_BYTES] = random();
    let eph_n = X25519KeyPair::generate();
    let eph_n_pub = eph_n.public_key().0.to_vec();
    let mac = request_mac(&secret, device_id, device_public_key, &nonce_n, &eph_n_pub)?;

    let mut request = vec![PAIRING_FORMAT_V1, MSG_REQUEST];
    request.extend_from_slice(device_id);
    request.extend_from_slice(device_public_key);
    request.extend_from_slice(&nonce_n);
    request.extend_from_slice(&eph_n_pub);
    request.extend_from_slice(&mac);

    let mut guard = JOINER.lock().map_err(|_| PairingError::Malformed)?;
    *guard = Some(JoinerState {
        legacy,
        account_pk,
        secret,
        expires_at_ms: expires,
        device_id: *device_id,
        device_pk: device_public_key.to_vec(),
        eph_n,
        eph_p_pub: Vec::new(),
        nonce_n,
        nonce_p: Vec::new(),
        root: None,
        transcript: Vec::new(),
    });
    Ok(request)
}

/// New device: verify the primary's answer and return the code to show.
pub fn joiner_handle_answer(answer: &[u8], now_ms: i64) -> Result<String, PairingError> {
    let mut parsed = Parsed::new(answer, MSG_ANSWER)?;
    let nonce_p = parsed.take(NONCE_BYTES)?.to_vec();
    let eph_p = parsed.take(KEY_BYTES)?.to_vec();
    let tag = parsed.take(32)?.to_vec();
    parsed.finish()?;

    let mut guard = JOINER.lock().map_err(|_| PairingError::Malformed)?;
    let state = guard.as_mut().ok_or(PairingError::UnknownSession)?;
    check_offer_time(state.expires_at_ms, now_ms)?;
    let eph_n_pub = state.eph_n.public_key().0.to_vec();
    let transcript = Transcript::new(
        &state.legacy,
        &state.account_pk,
        &state.device_id,
        &state.device_pk,
        &state.nonce_n,
        &eph_n_pub,
        &nonce_p,
        &eph_p,
    )
    .bytes;
    let expected = answer_mac(&state.secret, &transcript)?;
    if !ct_eq(&expected, &tag) {
        return Err(PairingError::AuthFailed);
    }
    let shared = state
        .eph_n
        .diffie_hellman(&eph_p)
        .map_err(|e| PairingError::Crypto(e.to_string()))?;
    let root = derive_root(&state.secret, &shared, &transcript)?;
    let sas = sas_code(&root, &transcript)?;
    state.eph_p_pub = eph_p;
    state.nonce_p = nonce_p;
    state.root = Some(root);
    state.transcript = transcript;
    Ok(sas)
}

/// New device: the user confirmed the code. Open the grant and return the device certificate bytes.
pub fn joiner_accept_grant(grant: &[u8], now_ms: i64) -> Result<Vec<u8>, PairingError> {
    let mut parsed = Parsed::new(grant, MSG_GRANT)?;
    let ciphertext = parsed.rest().to_vec();
    let mut guard = JOINER.lock().map_err(|_| PairingError::Malformed)?;
    let state = guard.as_ref().ok_or(PairingError::UnknownSession)?;
    check_offer_time(state.expires_at_ms, now_ms)?;
    let root = state.root.ok_or(PairingError::UnknownSession)?;
    let enc_key = derive_enc_key(&root, &state.transcript)?;
    let cipher = ChaCha20Poly1305::new(Key::from_slice(&enc_key));
    let nonce = Nonce::from_slice(&[0u8; 12]);
    let plaintext = cipher
        .decrypt(
            nonce,
            Payload {
                msg: &ciphertext,
                aad: &state.transcript,
            },
        )
        .map_err(|_| PairingError::AuthFailed)?;
    let cert = DeviceCertificateV1::from_bytes(&plaintext)
        .map_err(|e| PairingError::Crypto(e.to_string()))?;
    verify_device_certificate(&cert, &state.account_pk)
        .map_err(|e| PairingError::Crypto(e.to_string()))?;
    if cert.device_id != state.device_id
        || cert.device_public_key != state.device_pk
        || cert.legacy_routing_node_id != state.legacy
    {
        return Err(PairingError::CertificateMismatch);
    }
    *guard = None;
    Ok(plaintext)
}

/// Drop the joiner state (user cancelled or code did not match).
pub fn joiner_cancel() {
    if let Ok(mut guard) = JOINER.lock() {
        *guard = None;
    }
}

struct Parsed<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Parsed<'a> {
    /// `expected_type` 0 means the offer format (no message type byte).
    fn new(data: &'a [u8], expected_type: u8) -> Result<Self, PairingError> {
        if data.is_empty() {
            return Err(PairingError::Malformed);
        }
        if data[0] != PAIRING_FORMAT_V1 {
            return Err(PairingError::UnsupportedFormat(data[0]));
        }
        let mut parsed = Self { data, pos: 1 };
        if expected_type != 0 {
            let kind = parsed.take(1)?[0];
            if kind != expected_type {
                return Err(PairingError::Malformed);
            }
        }
        Ok(parsed)
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8], PairingError> {
        let end = self.pos.checked_add(n).ok_or(PairingError::Malformed)?;
        if end > self.data.len() {
            return Err(PairingError::Malformed);
        }
        let out = &self.data[self.pos..end];
        self.pos = end;
        Ok(out)
    }

    fn take_u16(&mut self) -> Result<u16, PairingError> {
        let b = self.take(2)?;
        Ok(u16::from_be_bytes([b[0], b[1]]))
    }

    fn take_i64(&mut self) -> Result<i64, PairingError> {
        let b = self.take(8)?;
        let mut arr = [0u8; 8];
        arr.copy_from_slice(b);
        Ok(i64::from_be_bytes(arr))
    }

    fn rest(&mut self) -> &'a [u8] {
        let out = &self.data[self.pos..];
        self.pos = self.data.len();
        out
    }

    fn finish(&self) -> Result<(), PairingError> {
        if self.pos == self.data.len() {
            Ok(())
        } else {
            Err(PairingError::Malformed)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::device_certificate::verify_device_artifact;

    /// The registries are process-wide; pairing tests must not interleave.
    static TEST_LOCK: Mutex<()> = Mutex::new(());

    const LEGACY: &str = "pk_0123456789abcdef0123456789abcdef";

    fn account() -> Ed25519KeyPair {
        Ed25519KeyPair::from_secret_bytes(&[21u8; 32]).unwrap()
    }

    fn device(seed: u8) -> Ed25519KeyPair {
        Ed25519KeyPair::from_secret_bytes(&[seed; 32]).unwrap()
    }

    /// Full happy path. Returns the two SAS codes and the decrypted certificate.
    fn run_pairing(
        joiner_device: &Ed25519KeyPair,
        joiner_id: [u8; 16],
        now: i64,
    ) -> (String, String, Result<Vec<u8>, PairingError>) {
        let offer = open_pairing_offer(LEGACY, &account().public_key().0, now).unwrap();
        let request = joiner_start(&offer, &joiner_id, &joiner_device.public_key().0, now).unwrap();
        let answer = primary_handle_request(&request, now).unwrap();
        let joiner_sas = joiner_handle_answer(&answer.answer, now).unwrap();
        let grant = primary_confirm(&answer.device_id, &account(), now).unwrap();
        let accepted = joiner_accept_grant(&grant.grant, now);
        (answer.sas, joiner_sas, accepted)
    }

    #[test]
    fn full_pairing_issues_certificate_that_checks_against_account_key() {
        let _guard = TEST_LOCK.lock().unwrap();
        let joiner = device(40);
        let (primary_sas, joiner_sas, accepted) = run_pairing(&joiner, [7u8; 16], 10_000);
        assert_eq!(primary_sas, joiner_sas);
        assert_eq!(primary_sas.len(), 7);
        let cert_bytes = accepted.unwrap();
        let cert = DeviceCertificateV1::from_bytes(&cert_bytes).unwrap();
        assert_eq!(cert.device_public_key, joiner.public_key().0.to_vec());
        assert_eq!(
            verify_device_certificate(&cert, &account().public_key().0),
            Ok(())
        );
        let msg = b"signed by new device";
        let sig = joiner.sign(msg);
        assert_eq!(
            verify_device_artifact(&cert, &account().public_key().0, None, 10_001, msg, &sig),
            Ok(())
        );
        close_pairing_offer();
    }

    #[test]
    fn request_without_offer_secret_is_rejected() {
        let _guard = TEST_LOCK.lock().unwrap();
        let offer = open_pairing_offer(LEGACY, &account().public_key().0, 0).unwrap();
        let mut forged = offer.clone();
        // Replace the secret with another value: the joiner now derives a different MAC key.
        let secret_start = 1 + 2 + LEGACY.len() + 32;
        forged[secret_start] ^= 0xff;
        let request = joiner_start(&forged, &[1u8; 16], &device(41).public_key().0, 0).unwrap();
        assert_eq!(
            primary_handle_request(&request, 0).err(),
            Some(PairingError::AuthFailed)
        );
        close_pairing_offer();
    }

    #[test]
    fn tampered_answer_is_rejected_by_joiner() {
        let _guard = TEST_LOCK.lock().unwrap();
        let joiner = device(42);
        let offer = open_pairing_offer(LEGACY, &account().public_key().0, 0).unwrap();
        let request = joiner_start(&offer, &[2u8; 16], &joiner.public_key().0, 0).unwrap();
        let mut answer = primary_handle_request(&request, 0).unwrap().answer;
        let last = answer.len() - 1;
        answer[last] ^= 1;
        assert_eq!(
            joiner_handle_answer(&answer, 0),
            Err(PairingError::AuthFailed)
        );
        joiner_cancel();
        close_pairing_offer();
    }

    #[test]
    fn swapped_ephemeral_key_is_rejected_by_joiner() {
        let _guard = TEST_LOCK.lock().unwrap();
        let joiner = device(43);
        let offer = open_pairing_offer(LEGACY, &account().public_key().0, 0).unwrap();
        let request = joiner_start(&offer, &[3u8; 16], &joiner.public_key().0, 0).unwrap();
        // Attacker swaps the primary's ephemeral key in the answer. The answer MAC is keyed by the
        // offer secret, so the joiner rejects it before any code is shown.
        let answer = primary_handle_request(&request, 0).unwrap();
        let mut attacker_answer = answer.answer.clone();
        let attacker_eph = X25519KeyPair::generate().public_key().0;
        attacker_answer[2 + 32..2 + 32 + 32].copy_from_slice(&attacker_eph);
        assert_eq!(
            joiner_handle_answer(&attacker_answer, 0),
            Err(PairingError::AuthFailed)
        );
        joiner_cancel();
        close_pairing_offer();
    }

    #[test]
    fn expired_offer_is_rejected() {
        let _guard = TEST_LOCK.lock().unwrap();
        let offer = open_pairing_offer(LEGACY, &account().public_key().0, 0).unwrap();
        let later = PAIRING_OFFER_TTL_MS + 1;
        assert_eq!(
            joiner_start(&offer, &[4u8; 16], &device(44).public_key().0, later).err(),
            Some(PairingError::Expired)
        );
        close_pairing_offer();
    }

    #[test]
    fn tampered_grant_is_rejected() {
        let _guard = TEST_LOCK.lock().unwrap();
        let joiner = device(45);
        let offer = open_pairing_offer(LEGACY, &account().public_key().0, 0).unwrap();
        let request = joiner_start(&offer, &[5u8; 16], &joiner.public_key().0, 0).unwrap();
        let answer = primary_handle_request(&request, 0).unwrap();
        joiner_handle_answer(&answer.answer, 0).unwrap();
        let mut grant = primary_confirm(&answer.device_id, &account(), 0).unwrap().grant;
        let last = grant.len() - 1;
        grant[last] ^= 1;
        assert_eq!(joiner_accept_grant(&grant, 0), Err(PairingError::AuthFailed));
        joiner_cancel();
        close_pairing_offer();
    }

    #[test]
    fn grant_for_unknown_device_is_refused() {
        let _guard = TEST_LOCK.lock().unwrap();
        let joiner = device(46);
        let offer = open_pairing_offer(LEGACY, &account().public_key().0, 0).unwrap();
        let request = joiner_start(&offer, &[6u8; 16], &joiner.public_key().0, 0).unwrap();
        let answer = primary_handle_request(&request, 0).unwrap();
        joiner_handle_answer(&answer.answer, 0).unwrap();
        // Primary issues a certificate for a different device id than the one that asked.
        assert_eq!(
            primary_confirm(&[9u8; 16], &account(), 0).err(),
            Some(PairingError::UnknownSession)
        );
        joiner_cancel();
        close_pairing_offer();
    }

    #[test]
    fn offer_bytes_round_trip_through_joiner() {
        let _guard = TEST_LOCK.lock().unwrap();
        let (primary_sas, joiner_sas, accepted) = run_pairing(&device(47), [8u8; 16], 5);
        assert_eq!(primary_sas, joiner_sas);
        assert!(accepted.is_ok());
        close_pairing_offer();
    }
}
