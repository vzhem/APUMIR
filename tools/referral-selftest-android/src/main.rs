mod crypto;

use crate::crypto::keys::Ed25519KeyPair;
use crate::crypto::referral::{
    inviter_node_id, sign_referral_invite_v1, verify_referral_invite_v1,
    ReferralInviteClaimsV1, ReferralInviteError, MAX_REFERRAL_CLOCK_SKEW_MS,
    MAX_REFERRAL_LIFETIME_MS, REFERRAL_NONCE_BYTES,
};

const CREATED: i64 = 1_800_000_000_000;

fn claims(identity: &Ed25519KeyPair) -> ReferralInviteClaimsV1 {
    ReferralInviteClaimsV1 {
        inviter_node_id: inviter_node_id(identity.public_key().as_bytes()),
        nonce: [0xA5; REFERRAL_NONCE_BYTES],
        created_at_ms: CREATED,
        expires_at_ms: CREATED + 7 * 24 * 60 * 60 * 1_000,
    }
}

fn main() {
    // 1. Canonical bytes are deterministic and claims are genuinely signed.
    let identity = Ed25519KeyPair::from_secret_bytes(&[11; 32]).unwrap();
    let base_claims = claims(&identity);
    assert_eq!(
        base_claims.canonical_bytes().unwrap(),
        base_claims.canonical_bytes().unwrap()
    );
    let token = sign_referral_invite_v1(base_claims, &identity).unwrap();
    assert_eq!(token.signature.len(), 64);
    verify_referral_invite_v1(&token, CREATED + 1_000).unwrap();

    // 2. A modified signed claim must fail verification.
    let mut modified = token.clone();
    modified.claims.expires_at_ms -= 1;
    assert_eq!(
        verify_referral_invite_v1(&modified, CREATED + 1_000),
        Err(ReferralInviteError::InvalidSignature)
    );

    // 3. Public key substitution must fail node binding before trust.
    let other = Ed25519KeyPair::from_secret_bytes(&[12; 32]).unwrap();
    let mut wrong_key = token.clone();
    wrong_key.inviter_ed25519_public_key = other.public_key().0;
    assert_eq!(
        verify_referral_invite_v1(&wrong_key, CREATED + 1_000),
        Err(ReferralInviteError::InviterBindingMismatch)
    );

    // 4. Malformed signature length is rejected without panic.
    let mut short_signature = token.clone();
    short_signature.signature.truncate(63);
    assert_eq!(
        verify_referral_invite_v1(&short_signature, CREATED + 1_000),
        Err(ReferralInviteError::InvalidSignature)
    );

    // 5. Expired and not-yet-active tokens fail.
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

    // 6. Zero nonce and overlong lifetime are rejected before signing.
    let mut zero_nonce = claims(&identity);
    zero_nonce.nonce = [0; REFERRAL_NONCE_BYTES];
    assert_eq!(
        sign_referral_invite_v1(zero_nonce, &identity),
        Err(ReferralInviteError::InvalidNonce)
    );
    let mut too_long = claims(&identity);
    too_long.expires_at_ms = too_long.created_at_ms + MAX_REFERRAL_LIFETIME_MS + 1;
    assert_eq!(
        sign_referral_invite_v1(too_long, &identity),
        Err(ReferralInviteError::InvalidTimeWindow)
    );

    // 7. Declared inviter cannot be signed by a different identity.
    assert_eq!(
        sign_referral_invite_v1(claims(&other), &identity),
        Err(ReferralInviteError::InviterBindingMismatch)
    );

    println!("REFERRAL_R05_SELFTEST_PASS cases=7 algorithm=Ed25519");
}
