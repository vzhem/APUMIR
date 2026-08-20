# Secure File Transfer MVP

## 1. Scope and ordering

This is the nearest product milestone after the current signed-referral R1 slice. The first release is
intentionally limited to one file in a direct chat, 10 MiB maximum, explicit user selection/download,
and streaming bounded-memory processing. Photo/video/voice UX later reuses the same transport.

The MVP must not place a whole file in a text MQTT envelope, Room row, Compose state, log, analytics
event, or byte array. A relay may carry only bounded encrypted manifests/chunks under quotas and TTL.

## 2. Security model

- File bytes are untrusted until final AEAD, exact size and whole-file SHA-256 checks pass.
- Android Storage Access Framework is the only picker/export boundary; no broad storage permission.
- Sender creates a random 32-byte file key and random 16-byte transfer ID.
- Manifest is bound to sender, recipient, transfer ID, metadata, size/chunk geometry, whole hash and TTL.
- Every chunk uses XChaCha20-Poly1305 and authenticated canonical manifest + chunk index as AAD.
- The file key/manifest delivery must be protected by the established E2E session. Relay/MQTT never
  sees plaintext metadata, original filename, file bytes or key.
- Deterministic nonce per `(random transfer ID, chunk index)` makes retries byte-stable; a transfer ID
  must never be reused with the same key for another file.
- A downloaded file becomes visible to the user only after atomic final verification.

This MVP does not claim antivirus scanning or safety of file contents. APK/executable content is never
auto-opened; MIME and filename are display hints, not authority.

## 3. Canonical manifest v1

Domain: `apu-file-manifest-v1\0`.

Fields in exact order:

```text
version u8
transfer_id [16]
sender_node_id length u16 + UTF-8
recipient_node_id length u16 + UTF-8
display_name length u16 + UTF-8
media_type length u16 + ASCII
file_size u64
chunk_size u32
chunk_count u32
file_sha256 [32]
created_at_ms i64
expires_at_ms i64
```

Rules:

- canonical legacy node IDs are `pk_` + 32 or 64 lowercase hex characters;
- sender and recipient differ;
- filename is 1–255 UTF-8 bytes, has no control characters, `/`, `\`, `.`/`..` path names;
- media type is bounded printable ASCII and contains `/`;
- file size is 0–10 MiB;
- chunk size is a power of two from 16–256 KiB; default 128 KiB;
- `chunk_count == ceil(file_size/chunk_size)`, with zero chunks only for an empty file;
- TTL is positive and at most 30 days;
- unknown version or trailing bytes fail closed.

## 4. Chunk v1

Nonce is exactly `transfer_id[16] || chunk_index_be_u64`, 24 bytes for XChaCha20-Poly1305.

AAD is domain `apu-file-chunk-v1\0`, SHA-256 of canonical manifest, and chunk index. Plaintext length
must exactly match manifest geometry: full chunk except the final remainder. Ciphertext is plaintext
plus the 16-byte Poly1305 tag.

## 5. Durable state

Room stores transfer state and bounded metadata only:

- transfer ID/message/chat/direction/state;
- expected size/chunk geometry/hash;
- received chunk bitmap or normalized chunk table;
- retry timestamps/errors without paths or key material.

Encrypted partial chunks live in app-private files under a per-transfer directory. Final plaintext is
streamed through SAF to a user-selected destination only after verification, or atomically retained in
an app-private received-file area. File keys use the existing device-bound/E2E key lifecycle and must
not be logged or placed in ordinary preferences.

States are monotonic and idempotent: `OFFERED → TRANSFERRING → VERIFYING → COMPLETE`, plus
`PAUSED/CANCELLED/FAILED/EXPIRED`. Duplicate manifests/chunks/receipts never create duplicate files.

## 6. Transport order

1. Direct QUIC/P2P when both peers are reachable.
2. Bounded encrypted relay chunks only after quotas/backpressure are implemented.
3. Offline custody uses TTL, per-peer/global disk quotas and cleanup receipts.

File traffic is lower priority than text/receipts and cannot starve durable messaging.

## 7. Acceptance gates

- canonical/negative crypto tests and Android production compilation;
- streaming memory ceiling test with 10 MiB input;
- 0 B, 1 B, chunk−1, chunk, chunk+1 and 10 MiB boundaries;
- wrong key/index/manifest/recipient, corrupted/truncated/reordered/duplicate chunk rejection;
- malicious filename/MIME, oversize, disk full, cancel, process death and reboot recovery;
- two-phone online SHA-256 equality and interruption/resume exactly once;
- three-phone offline custody with plaintext/log/DB/packet audit;
- text delivery latency and reliability remain within baseline while a file transfers.
