# APU — migration from legacy routing identity to real Ed25519 signing identity

Status: architecture audit / implementation plan, 2026-08-19. No engine or user-data migration is enabled yet.

## 1. Why this is required

Signed invites, referral receipts, authenticated delivery receipts, groups and moderation cannot use the current FFI signature prototype. `ffi::CryptoManager` currently:

- generates unrelated random strings `pk_<hex>` and `sk_<hex>`;
- treats `public_key` as `node_id`;
- implements `sign` as `sig_ + SHA-256(data)`;
- implements `verify` without cryptographically using `public_key`;
- accepts any non-empty public/private strings in `load_keys`.

Kotlin persistence is also legacy-compatible rather than cryptographic:

- onboarding stores `existing_public_key`, but not the generated private key;
- `CoreServerService` later stores `node_id` into both `existing_public_key` and `existing_private_key`;
- therefore existing `pk_…` values are routing identifiers, not Ed25519 public keys, and the saved “private key” is not private key material.

The unused `crypto::identity::NodeIdentity`, `crypto::keys::Ed25519KeyPair` and password-based `crypto::keystore::KeyStore` contain real primitives, but are not wired into the running engine or Android Keystore lifecycle.

## 2. Non-negotiable compatibility rule

A normal update must not silently change an existing user's routing node ID. Existing contacts, chats, retained MQTT topics and relay envelopes reference the legacy `pk_…` value.

Migration therefore introduces a **signing sidecar**, not an immediate replacement:

```text
legacy_routing_node_id  = existing pk_… identifier (stable)
signing_public_key      = real Ed25519 public key (new)
signing_key_id          = SHA-256(signing_public_key)
identity_format         = legacy+ed25519-sidecar-v1
```

New identities can later use a fully cryptographic node ID in a versioned protocol, but that is a separate compatibility gate.

## 3. Device-bound key storage

For Android versions where direct non-exportable Ed25519 Keystore support is inconsistent, use the same standard envelope pattern as relay at-rest custody:

1. Android Keystore stores a non-exportable AES-256-GCM wrap key under a dedicated alias.
2. A random 32-byte Ed25519 seed is generated once.
3. The seed is wrapped with AES-GCM and persisted in app-private SharedPreferences.
4. On startup the seed is unwrapped, passed to Rust through a narrow install call, then the Kotlin byte array is zeroed.
5. Rust constructs `Ed25519KeyPair::from_secret_bytes`; private bytes are never returned by UniFFI or logged.
6. Backup/device transfer excludes the wrapped blob; `allowBackup=false` and the device identity marker remain mandatory.

Dedicated names (not shared with relay encryption):

```text
Keystore alias: apu_identity_signing_wrap_v1
Prefs:          apu_identity_signing.xml
Blob format:    [version][key-id][iv][ciphertext+tag]
```

Failure semantics:

- missing key on a legacy profile → generate sidecar, keep legacy routing ID;
- wrapped blob present but Keystore alias missing/invalid → do not trust restored sidecar; discard only sidecar bytes and generate a new signing key;
- malformed/failed unwrap → no placeholder signature and no unsigned reward; referral/signing features remain disabled with a visible diagnostic;
- never fall back from real Ed25519 to `sig_<hash>`.

## 4. Binding old routing identity to the sidecar

A legacy ID cannot cryptographically sign the transition because it never had a real private key. The first binding is therefore an explicit TOFU migration statement:

```text
apu-identity-binding-v1(
  legacy_routing_node_id,
  signing_public_key,
  created_at,
  app_installation_marker,
  signature_by_new_ed25519_key
)
```

Rules:

- the new key signs the canonical binding statement;
- the local app stores the binding before generating signed invites;
- contacts first seeing a sidecar over an already trusted legacy chat pin it (TOFU) and warn on later key changes;
- QR/in-person verification can upgrade TOFU to verified;
- registry/relay cannot silently replace a pinned signing key;
- key rotation requires a statement signed by both old and new signing keys, unless the user explicitly performs recovery/reset.

This limitation must be documented honestly: the first migration of a legacy identity is not retroactively cryptographically provable.

## 5. Versioned Rust ownership

Target Rust structures:

```text
InstalledSigningIdentity {
  format_version,
  legacy_routing_node_id,
  ed25519_keypair,
  signing_key_id,
}
```

Lifecycle:

- Android installs key material before `P2PCore.start()`.
- `P2PCore` snapshots an `Arc` signer; no global mutable replacement while running.
- sign APIs accept a fixed domain and canonical bytes, not arbitrary UI strings.
- verify APIs are pure and accept public key + canonical payload + signature.
- logs expose only format version/key ID/fingerprint, never seed/signature payload containing private data.

Proposed UniFFI seam, only after Rust tests and Android compile:

```text
install_identity_signing_key(version, seed_bytes)
identity_signing_public_key() -> bytes
identity_signing_key_id() -> string
sign_referral_claims(canonical_claims) -> bytes
verify_referral_claims(public_key, canonical_claims, signature) -> bool
```

Do not expose `sign_arbitrary_bytes` to UI. Domain-specific APIs reduce cross-protocol signing attacks.

## 6. Migration state machine

```text
LEGACY_ONLY
  -> generate/wrap/install sidecar
  -> verify derived public key and self-sign binding
  -> persist migration record atomically
  -> LEGACY_WITH_SIGNING_V1

LEGACY_WITH_SIGNING_V1
  -> unwrap/install same key on restart
  -> verify key ID/public key equals persisted binding
  -> enable signed invites/referrals

RESTORED_OR_BROKEN_SIDECAR
  -> keep legacy routing/chat identity
  -> disable signed features
  -> generate new sidecar only after explicit local recovery
  -> notify contacts of changed signing key on next contact
```

Atomicity rule: do not set “signing enabled” until wrapped blob, derived public key, binding statement and verification all succeed.

## 7. Implementation slices

### S1 — completed foundation

- Canonical direct-referral claims.
- Real Ed25519 sign/verify and node/public-key binding.
- Nonce/time/lifetime bounds and negative tests.
- No engine/UniFFI/UI wiring.

### S2 — key lifecycle model (source slice 1 implemented, compile pending)

- Pure Kotlin versioned envelope `[version][IV12][ciphertext32+tag16]` with strict exact sizes.
- Android `IdentitySigningKeyStore`: dedicated AES-256-GCM Keystore alias, domain-separated AAD,
  random 32-byte seed, synchronous persistence and bounded `withSeed` zeroization.
- Existing-but-unreadable wrapped state returns `UNAVAILABLE`/exception and is never silently
  overwritten or rotated; read-only `mode()` never creates bytes or keys.
- Five JVM framing tests PASS (state `E6AEDB6C…4BD825`): exact layout/roundtrip, invalid lengths,
  all truncations+trailing bytes, unknown version and no aliasing.
- Android Keystore instrumented acceptance PASS on Stas v11.16.24: real wrap/unwrap stability,
  borrowed-array zeroization, tamper rejection/no overwrite, missing alias/no silent rotation.
  Instrumentation log SHA `BFA8FF7E…4FFC21`; recovery state `93060511…4F4C8`.
- Existing Stas profile prefs SHA stayed `66F65581…9C4245`, identity/device marker preserved; test
  package removed, wrapped seed absent after cleanup, APU relaunched. No data clear/force-stop.
- Test cleanup now uses `deleteSharedPreferences`, avoiding an empty leftover XML file.
- No Rust/engine/UniFFI caller yet; production does not create a signing seed in this slice.
- Next: Rust install-before-engine registry + public diagnostics, still without app startup wiring.


### S3 — legacy migration (Rust registry/API seam source started)

- Added strict `InstalledSigningIdentity`: format v1, legacy routing `pk_` validation, real
  `Ed25519KeyPair`, public key/key ID and redacted Debug.
- Install validates fully before replacing the global snapshot; invalid seed/format/routing ID leaves
  previous identity intact. `Arc` snapshots survive registry clear for future engine ownership.
- Versioned UniFFI seam added: install seed, clear, mode, public key hex and key ID. Seed input is
  wiped in Rust; private bytes are never returned. Current Android startup still does not call it.
- Rust/UniFFI build PASS: native `73FC4D6D…7AE97B`, generated binding `80C5AF87…47E629`,
  debug APK `2577AD31…6FF6C7`, state `7BE02D31…37E34B`.
- Kotlin startup wiring source added: existing profiles install sidecar before service/worker engine;
  new onboarding installs immediately after legacy node generation. Honest failure remains
  `legacy-only`; routing `node_id` is never changed. Diagnostics verify mode, public key length and
  `key_id == SHA-256(public_key)`; borrowed seed is zeroed.
- Accepted pre-wiring instrumentation test is now `@Ignore`: its default alias became production
  state and must never be deleted by a routine rerun. Future tests require isolated namespace.
- Read-only startup instrumentation source added: existing identity/marker required, sidecar mode
  READY, two Rust installs yield identical public key/key ID, SHA-256 invariant holds and legacy
  `node_id` remains byte-for-byte unchanged. It never deletes/reset sidecar or app data.
- Pending: compile startup wiring/test APK, one-phone update/data-preservation/stable key restart
  gate, then self-signed TOFU binding.

### S4 — engine ownership and diagnostics

- Install-before-start registry and immutable engine snapshot.
- Public key/key ID/mode diagnostics.
- Remove/disable prototype sign/verify for security-sensitive call sites.

### S5 — domain-specific UniFFI referral signing

- Generate/verify signed direct invite token.
- Kotlin parser only accepts rewards from verified v1 tokens.
- Legacy unsigned links remain contact-only.

## 8. Acceptance gates

- Existing v11.16.23 identity keeps the exact routing node ID, chats and contacts after update.
- New signing public key is stable across process death/reboot/update.
- Private seed is absent from logs, Room, backup, invite, Kotlin long-lived objects and diagnostics.
- Wrong Keystore key/tampered blob does not silently rotate or enable signing.
- Modified claims, wrong public key, malformed signature, expiry and replay fail.
- Two identities cannot claim the same signing key ID without identical public key bytes.
- Legacy N-1 peers continue messaging; they ignore unknown signing metadata safely.
- Signed invite/referral remains disabled whenever migration mode is not `legacy+ed25519-sidecar-v1`.
- No release claim says legacy identity was always cryptographically authenticated.

## 9. Tooling status

Production Android Rust compilation of referral S1 passed. Runtime/unit test execution is temporarily blocked on the Windows host because the failed D: disk contained MSVC `link.exe`; Microsoft CDN and WSL installation were unavailable. Several bounded attempts stopped before app/phone changes. The test matrix remains pending until one supported environment is available (MSVC on C:, existing Linux runner, or a build environment with working host linker). This tooling issue must not be hidden by weakening crypto assertions.
