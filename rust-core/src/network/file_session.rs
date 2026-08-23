//! Authenticated, persistent single-peer QUIC foundation for the F4 file data plane.
//!
//! The legacy QUIC client accepts any self-signed TLS certificate. This module therefore does not
//! treat a successful TLS handshake as peer authentication. Instead, both peers exchange B2 signed
//! capability controls whose session scope is derived from per-connection TLS exporter material and
//! a fresh initiator record ID. An active TLS terminator gets different exporter bytes on each leg,
//! so it cannot relay that signed scope onto its second channel. Peer Ed25519 keys remain pinned.
//!
//! C1 deliberately owns exactly one ordered bidirectional QUIC stream. Binary B1 chunk frames and
//! signed B2 controls are length-delimited on that stream without Base64 or whole-file buffering.
//! A chunk ACK is emitted only after an injected durable sink returns success. Production sinks must
//! not return success until ciphertext bytes and their resume metadata survive process/device loss.
//! Parallel streams, Android/FFI wiring and the legacy F3 path are outside this host-only slice.

use std::future::Future;
use std::pin::Pin;
use std::time::Duration;

use quinn::{ RecvStream, SendStream };
use rand::{ rngs::OsRng, RngCore };
use sha2::{ Digest, Sha256 };

use crate::crypto::keys::{ Ed25519KeyPair, ED25519_PUBLIC_KEY_SIZE };
use crate::network::file_control::{
    sign_file_control_with_signer_v1,
    FileControlBodyV1,
    FileControlClaimsV1,
    FileControlError,
    FileControlSigner,
    SignedFileControlV1,
    FILE_CONTROL_ID_BYTES,
    MAX_FILE_CAPABILITY_LIFETIME_MS,
    MAX_FILE_CONTROL_BYTES,
};
use crate::network::file_wire::{
    negotiate_file_capabilities,
    FileCapabilitiesV1,
    FileChunkDataV1,
    FileFrameV1,
    FileWireError,
    NegotiatedFileCapabilitiesV1,
    FILE_FRAME_HEADER_BYTES,
    MAX_FILE_FRAME_BYTES,
};
use crate::network::quic_client::{ QuicClientError, QuicConnection };

pub const FILE_SESSION_MAGIC: [u8; 4] = *b"APUS";
pub const FILE_SESSION_VERSION_V1: u8 = 1;
pub const FILE_SESSION_HEADER_BYTES: usize = 12;
pub const FILE_SESSION_ACK_BYTES: usize = 16 + 8 + 4 + 4;
pub const MAX_FILE_SESSION_RECORD_BYTES: usize = FILE_SESSION_HEADER_BYTES + MAX_FILE_FRAME_BYTES;

const FILE_SESSION_SCOPE_DOMAIN_V1: &[u8] = b"apu-file-session-scope-v1\0";
const FILE_SESSION_FLAGS_V1: u16 = 0;
const SESSION_RECORD_AUTH: u8 = 1;
const SESSION_RECORD_AUTH_OK: u8 = 2;
const SESSION_RECORD_CHUNK: u8 = 3;
const SESSION_RECORD_CHUNK_ACK: u8 = 4;
const SESSION_RECORD_CONTROL: u8 = 5;
const SESSION_RECORD_CLOSE: u8 = 6;
const SESSION_ABORT_CODE: u32 = 0x4150;
const DEFAULT_AUTH_LIFETIME_MS: i64 = 60_000;

/// A peer identity already pinned by contact/identity state outside this transport.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileSessionPeer {
    pub node_id: String,
    pub ed25519_public_key: [u8; ED25519_PUBLIC_KEY_SIZE],
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FileSessionLimits {
    pub operation_timeout: Duration,
    pub durable_write_timeout: Duration,
}

impl Default for FileSessionLimits {
    fn default() -> Self {
        Self {
            operation_timeout: Duration::from_secs(5),
            durable_write_timeout: Duration::from_secs(30),
        }
    }
}

/// Durable anti-replay admission for an authenticated session scope.
///
/// Production implementations must atomically persist `(peer key, scope, expiry)` before returning
/// `Ok(true)`. `Ok(false)` means that the same scope was already admitted. A failed response after a
/// successful admission intentionally requires the initiator to create a fresh session record.
pub trait FileSessionAdmission {
    fn admit_session(
        &mut self,
        peer_ed25519_public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
        scope_id: &[u8; FILE_CONTROL_ID_BYTES],
        expires_at_ms: i64
    ) -> Result<bool, String>;
}

/// Sink boundary behind the receiver's durable-before-ACK rule.
///
/// Returning `Ok(())` is a durability assertion, not merely a buffered write. The implementation
/// must persist both this ciphertext range and enough resume metadata to request it correctly after
/// restart. Each call is bounded to one decoded B1 frame (at most 256 KiB payload).
pub trait DurableFileRangeSink {
    fn persist_range<'a>(
        &'a mut self,
        range: &'a FileChunkDataV1
    ) -> Pin<Box<dyn Future<Output = Result<(), String>> + Send + 'a>>;
}

#[derive(Debug, thiserror::Error)]
pub enum FileSessionError {
    #[error("QUIC file session error: {0}")] Quic(#[from] QuicClientError),
    #[error("file control rejected: {0}")] Control(#[from] FileControlError),
    #[error("file frame rejected: {0}")] Wire(#[from] FileWireError),
    #[error("file session operation timed out: {operation}")] Timeout {
        operation: &'static str,
    },
    #[error("file session I/O failed during {operation}: {reason}")] Io {
        operation: &'static str,
        reason: String,
    },
    #[error("invalid file session record magic")]
    InvalidMagic,
    #[error("unsupported file session version: {got}")] UnsupportedVersion {
        got: u8,
    },
    #[error("unsupported file session flags: 0x{got:04x}")] UnsupportedFlags {
        got: u16,
    },
    #[error("unsupported file session record type: {got}")] UnsupportedRecordType {
        got: u8,
    },
    #[error("file session record is too large: {size} bytes (max {max})")] RecordTooLarge {
        size: usize,
        max: usize,
    },
    #[error("invalid file session record length for type {record_type}: {actual} bytes")] InvalidRecordLength {
        record_type: u8,
        actual: usize,
    },
    #[error("unexpected file session record: expected {expected}, got {got}")] UnexpectedRecord {
        expected: &'static str,
        got: u8,
    },
    #[error("file session authentication record is not a capabilities control")]
    ExpectedCapabilities,
    #[error("file frame payload exceeds the negotiated session limit: {size} bytes (max {max})")] NegotiatedFrameTooLarge {
        size: usize,
        max: usize,
    },
    #[error("file session replay admission rejected the scope")]
    ReplayedSession,
    #[error("file session admission store failed: {0}")] Admission(String),
    #[error("durable ciphertext sink failed: {0}")] DurableSink(String),
    #[error("chunk ACK does not match the sent ciphertext range")]
    AckMismatch,
    #[error("file session was closed by the peer")]
    Closed,
    #[error("file session local time is invalid")]
    InvalidTime,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ChunkAckV1 {
    transfer_id: [u8; 16],
    chunk_index: u64,
    chunk_offset: u32,
    range_len: u32,
}

struct SessionRecord {
    record_type: u8,
    payload: Vec<u8>,
}

/// Initiator side of one authenticated, ordered QUIC file session.
pub struct FileSendSession {
    send: SendStream,
    recv: RecvStream,
    local_node_id: String,
    peer: FileSessionPeer,
    scope_id: [u8; FILE_CONTROL_ID_BYTES],
    negotiated: NegotiatedFileCapabilitiesV1,
    limits: FileSessionLimits,
}

/// Receiver side of one authenticated, ordered QUIC file session.
pub struct FileReceiveSession {
    send: SendStream,
    recv: RecvStream,
    scope_id: [u8; FILE_CONTROL_ID_BYTES],
    negotiated: NegotiatedFileCapabilitiesV1,
    limits: FileSessionLimits,
}

impl FileSendSession {
    #[allow(clippy::too_many_arguments)]
    pub async fn connect(
        connection: &QuicConnection,
        local_node_id: &str,
        local_identity: &Ed25519KeyPair,
        peer: FileSessionPeer,
        local_capabilities: FileCapabilitiesV1,
        now_ms: i64,
        limits: FileSessionLimits
    ) -> Result<Self, FileSessionError> {
        Self::connect_with_signer(
            connection,
            local_node_id,
            local_identity,
            peer,
            local_capabilities,
            now_ms,
            limits
        ).await
    }

    #[allow(clippy::too_many_arguments)]
    pub(crate) async fn connect_with_signer<S: FileControlSigner + ?Sized>(
        connection: &QuicConnection,
        local_node_id: &str,
        local_identity: &S,
        peer: FileSessionPeer,
        local_capabilities: FileCapabilitiesV1,
        now_ms: i64,
        limits: FileSessionLimits
    ) -> Result<Self, FileSessionError> {
        validate_limits(limits)?;
        let (mut send, mut recv) = timeout_result(
            limits.operation_timeout,
            "open ordered stream",
            connection.open_file_session_stream()
        ).await??;

        let result = (async {
            let record_id = random_nonzero_id();
            let channel_binding = connection.file_session_channel_binding(&record_id)?;
            let scope_id = derive_file_session_scope(&channel_binding, &record_id);
            let auth = make_capability_control(
                local_node_id,
                local_identity,
                &peer.node_id,
                record_id,
                scope_id,
                local_capabilities.clone(),
                now_ms
            )?;
            let auth_bytes = auth.encode()?;
            write_record_timeout(
                &mut send,
                SESSION_RECORD_AUTH,
                &auth_bytes,
                limits.operation_timeout
            ).await?;

            let response = read_record_timeout(&mut recv, limits.operation_timeout).await?;
            if response.record_type != SESSION_RECORD_AUTH_OK {
                return Err(FileSessionError::UnexpectedRecord {
                    expected: "authenticated capability response",
                    got: response.record_type,
                });
            }
            let peer_auth = SignedFileControlV1::decode(&response.payload)?;
            let peer_capabilities = verify_capability_control(
                &peer_auth,
                &peer,
                local_node_id,
                &scope_id,
                now_ms
            )?;
            let negotiated = negotiate_file_capabilities(&local_capabilities, &peer_capabilities)?;
            Ok((scope_id, negotiated))
        }).await;

        match result {
            Ok((scope_id, negotiated)) =>
                Ok(Self {
                    send,
                    recv,
                    local_node_id: local_node_id.to_owned(),
                    peer,
                    scope_id,
                    negotiated,
                    limits,
                }),
            Err(error) => {
                abort_streams(&mut send, &mut recv);
                Err(error)
            }
        }
    }

    pub fn scope_id(&self) -> [u8; FILE_CONTROL_ID_BYTES] {
        self.scope_id
    }

    pub fn negotiated_capabilities(&self) -> &NegotiatedFileCapabilitiesV1 {
        &self.negotiated
    }

    /// Send one exact B1 chunk frame and wait for its durable range ACK.
    pub async fn send_chunk(&mut self, frame: &FileFrameV1) -> Result<(), FileSessionError> {
        let result = self.send_chunk_inner(frame).await;
        if result.is_err() && !matches!(&result, Err(FileSessionError::Closed)) {
            abort_streams(&mut self.send, &mut self.recv);
        }
        result
    }

    async fn send_chunk_inner(&mut self, frame: &FileFrameV1) -> Result<(), FileSessionError> {
        let chunk = match frame {
            FileFrameV1::ChunkData(chunk) => chunk,
            FileFrameV1::Capabilities(_) => {
                return Err(FileSessionError::ExpectedCapabilities);
            }
        };
        let expected_ack = ChunkAckV1::from_chunk(chunk)?;
        let bytes = frame.encode()?;
        validate_negotiated_frame_size(&bytes, &self.negotiated)?;
        write_record_timeout(
            &mut self.send,
            SESSION_RECORD_CHUNK,
            &bytes,
            self.limits.operation_timeout
        ).await?;
        let response = read_record_timeout(&mut self.recv, self.limits.operation_timeout).await?;
        if response.record_type == SESSION_RECORD_CLOSE {
            return Err(FileSessionError::Closed);
        }
        if response.record_type != SESSION_RECORD_CHUNK_ACK {
            return Err(FileSessionError::UnexpectedRecord {
                expected: "durable chunk ACK",
                got: response.record_type,
            });
        }
        let ack = ChunkAckV1::decode(&response.payload)?;
        if ack != expected_ack {
            return Err(FileSessionError::AckMismatch);
        }
        Ok(())
    }

    /// Receive and authorize one independently signed B2 control (for example MissingRanges after
    /// reconnect). Replay state is supplied by the caller and must be advanced only after the
    /// control's requested operation has itself been accepted durably.
    pub async fn receive_control(
        &mut self,
        expected_scope_id: &[u8; FILE_CONTROL_ID_BYTES],
        now_ms: i64,
        last_seen_sequence: Option<u64>
    ) -> Result<SignedFileControlV1, FileSessionError> {
        let record = read_record_timeout(&mut self.recv, self.limits.operation_timeout).await?;
        if record.record_type == SESSION_RECORD_CLOSE {
            return Err(FileSessionError::Closed);
        }
        if record.record_type != SESSION_RECORD_CONTROL {
            return Err(FileSessionError::UnexpectedRecord {
                expected: "signed file control",
                got: record.record_type,
            });
        }
        let control = SignedFileControlV1::decode(&record.payload)?;
        control.verify_at(
            &self.peer.node_id,
            &self.local_node_id,
            &self.peer.ed25519_public_key,
            expected_scope_id,
            now_ms,
            last_seen_sequence
        )?;
        Ok(control)
    }

    pub async fn close(mut self) -> Result<(), FileSessionError> {
        write_record_timeout(
            &mut self.send,
            SESSION_RECORD_CLOSE,
            &[],
            self.limits.operation_timeout
        ).await?;
        self.send.finish().map_err(|error| FileSessionError::Io {
            operation: "finish ordered stream",
            reason: error.to_string(),
        })?;
        Ok(())
    }
}

impl FileReceiveSession {
    #[allow(clippy::too_many_arguments)]
    pub async fn accept<A: FileSessionAdmission>(
        connection: &QuicConnection,
        local_node_id: &str,
        local_identity: &Ed25519KeyPair,
        peer: FileSessionPeer,
        local_capabilities: FileCapabilitiesV1,
        now_ms: i64,
        limits: FileSessionLimits,
        admission: &mut A
    ) -> Result<Self, FileSessionError> {
        Self::accept_with_signer(
            connection,
            local_node_id,
            local_identity,
            peer,
            local_capabilities,
            now_ms,
            limits,
            admission
        ).await
    }

    #[allow(clippy::too_many_arguments)]
    pub(crate) async fn accept_with_signer<A, S>(
        connection: &QuicConnection,
        local_node_id: &str,
        local_identity: &S,
        peer: FileSessionPeer,
        local_capabilities: FileCapabilitiesV1,
        now_ms: i64,
        limits: FileSessionLimits,
        admission: &mut A
    ) -> Result<Self, FileSessionError>
    where
        A: FileSessionAdmission,
        S: FileControlSigner + ?Sized,
    {
        validate_limits(limits)?;
        let (mut send, mut recv) = timeout_result(
            limits.operation_timeout,
            "accept ordered stream",
            connection.accept_file_session_stream()
        ).await??;

        let result = (async {
            let request = read_record_timeout(&mut recv, limits.operation_timeout).await?;
            if request.record_type != SESSION_RECORD_AUTH {
                return Err(FileSessionError::UnexpectedRecord {
                    expected: "authenticated capability request",
                    got: request.record_type,
                });
            }
            let peer_auth = SignedFileControlV1::decode(&request.payload)?;
            let channel_binding = connection.file_session_channel_binding(
                &peer_auth.claims.record_id
            )?;
            let expected_scope = derive_file_session_scope(
                &channel_binding,
                &peer_auth.claims.record_id
            );
            let peer_capabilities = verify_capability_control(
                &peer_auth,
                &peer,
                local_node_id,
                &expected_scope,
                now_ms
            )?;
            let admitted = admission
                .admit_session(
                    &peer.ed25519_public_key,
                    &expected_scope,
                    peer_auth.claims.expires_at_ms
                )
                .map_err(FileSessionError::Admission)?;
            if !admitted {
                return Err(FileSessionError::ReplayedSession);
            }
            let negotiated = negotiate_file_capabilities(&local_capabilities, &peer_capabilities)?;
            let response = make_capability_control(
                local_node_id,
                local_identity,
                &peer.node_id,
                random_nonzero_id(),
                expected_scope,
                local_capabilities,
                now_ms
            )?;
            let response_bytes = response.encode()?;
            write_record_timeout(
                &mut send,
                SESSION_RECORD_AUTH_OK,
                &response_bytes,
                limits.operation_timeout
            ).await?;
            Ok((expected_scope, negotiated))
        }).await;

        match result {
            Ok((scope_id, negotiated)) =>
                Ok(Self {
                    send,
                    recv,
                    scope_id,
                    negotiated,
                    limits,
                }),
            Err(error) => {
                abort_streams(&mut send, &mut recv);
                Err(error)
            }
        }
    }

    pub fn scope_id(&self) -> [u8; FILE_CONTROL_ID_BYTES] {
        self.scope_id
    }

    pub fn negotiated_capabilities(&self) -> &NegotiatedFileCapabilitiesV1 {
        &self.negotiated
    }

    /// Decode one exact B1 range, await durable persistence, and only then emit its ACK.
    pub async fn receive_chunk<S: DurableFileRangeSink>(
        &mut self,
        sink: &mut S
    ) -> Result<FileChunkDataV1, FileSessionError> {
        let result = self.receive_chunk_inner(sink).await;
        if result.is_err() && !matches!(&result, Err(FileSessionError::Closed)) {
            abort_streams(&mut self.send, &mut self.recv);
        }
        result
    }

    async fn receive_chunk_inner<S: DurableFileRangeSink>(
        &mut self,
        sink: &mut S
    ) -> Result<FileChunkDataV1, FileSessionError> {
        let record = read_record_timeout(&mut self.recv, self.limits.operation_timeout).await?;
        if record.record_type == SESSION_RECORD_CLOSE {
            return Err(FileSessionError::Closed);
        }
        if record.record_type != SESSION_RECORD_CHUNK {
            return Err(FileSessionError::UnexpectedRecord {
                expected: "binary chunk frame",
                got: record.record_type,
            });
        }
        validate_negotiated_frame_size(&record.payload, &self.negotiated)?;
        let frame = FileFrameV1::decode(&record.payload)?;
        let chunk = match frame {
            FileFrameV1::ChunkData(chunk) => chunk,
            FileFrameV1::Capabilities(_) => {
                return Err(FileSessionError::ExpectedCapabilities);
            }
        };
        let ack = ChunkAckV1::from_chunk(&chunk)?;
        let persisted = tokio::time
            ::timeout(self.limits.durable_write_timeout, sink.persist_range(&chunk)).await
            .map_err(|_| FileSessionError::Timeout {
                operation: "durable ciphertext write",
            })?;
        if let Err(reason) = persisted {
            return Err(FileSessionError::DurableSink(reason));
        }
        let ack_bytes = ack.encode();
        write_record_timeout(
            &mut self.send,
            SESSION_RECORD_CHUNK_ACK,
            &ack_bytes,
            self.limits.operation_timeout
        ).await?;
        Ok(chunk)
    }

    /// Send one signed B2 resume/control record on the authenticated ordered stream.
    pub async fn send_control(
        &mut self,
        control: &SignedFileControlV1
    ) -> Result<(), FileSessionError> {
        let bytes = control.encode()?;
        write_record_timeout(
            &mut self.send,
            SESSION_RECORD_CONTROL,
            &bytes,
            self.limits.operation_timeout
        ).await
    }

    pub async fn close(mut self) -> Result<(), FileSessionError> {
        write_record_timeout(
            &mut self.send,
            SESSION_RECORD_CLOSE,
            &[],
            self.limits.operation_timeout
        ).await?;
        self.send.finish().map_err(|error| FileSessionError::Io {
            operation: "finish ordered stream",
            reason: error.to_string(),
        })?;
        Ok(())
    }
}

impl ChunkAckV1 {
    fn from_chunk(chunk: &FileChunkDataV1) -> Result<Self, FileSessionError> {
        Ok(Self {
            transfer_id: chunk.transfer_id,
            chunk_index: chunk.chunk_index,
            chunk_offset: chunk.chunk_offset,
            range_len: u32::try_from(chunk.ciphertext.len()).map_err(|_| {
                FileSessionError::InvalidRecordLength {
                    record_type: SESSION_RECORD_CHUNK_ACK,
                    actual: chunk.ciphertext.len(),
                }
            })?,
        })
    }

    fn encode(&self) -> [u8; FILE_SESSION_ACK_BYTES] {
        let mut bytes = [0u8; FILE_SESSION_ACK_BYTES];
        bytes[..16].copy_from_slice(&self.transfer_id);
        bytes[16..24].copy_from_slice(&self.chunk_index.to_be_bytes());
        bytes[24..28].copy_from_slice(&self.chunk_offset.to_be_bytes());
        bytes[28..32].copy_from_slice(&self.range_len.to_be_bytes());
        bytes
    }

    fn decode(bytes: &[u8]) -> Result<Self, FileSessionError> {
        if bytes.len() != FILE_SESSION_ACK_BYTES {
            return Err(FileSessionError::InvalidRecordLength {
                record_type: SESSION_RECORD_CHUNK_ACK,
                actual: bytes.len(),
            });
        }
        Ok(Self {
            transfer_id: bytes[..16].try_into().map_err(|_| FileSessionError::InvalidRecordLength {
                record_type: SESSION_RECORD_CHUNK_ACK,
                actual: bytes.len(),
            })?,
            chunk_index: u64::from_be_bytes(
                bytes[16..24].try_into().map_err(|_| {
                    FileSessionError::InvalidRecordLength {
                        record_type: SESSION_RECORD_CHUNK_ACK,
                        actual: bytes.len(),
                    }
                })?
            ),
            chunk_offset: u32::from_be_bytes(
                bytes[24..28].try_into().map_err(|_| {
                    FileSessionError::InvalidRecordLength {
                        record_type: SESSION_RECORD_CHUNK_ACK,
                        actual: bytes.len(),
                    }
                })?
            ),
            range_len: u32::from_be_bytes(
                bytes[28..32].try_into().map_err(|_| {
                    FileSessionError::InvalidRecordLength {
                        record_type: SESSION_RECORD_CHUNK_ACK,
                        actual: bytes.len(),
                    }
                })?
            ),
        })
    }
}

fn validate_negotiated_frame_size(
    encoded_frame: &[u8],
    negotiated: &NegotiatedFileCapabilitiesV1
) -> Result<(), FileSessionError> {
    let payload_size = encoded_frame
        .len()
        .checked_sub(FILE_FRAME_HEADER_BYTES)
        .ok_or(FileSessionError::InvalidRecordLength {
            record_type: SESSION_RECORD_CHUNK,
            actual: encoded_frame.len(),
        })?;
    let max = negotiated.max_frame_payload_bytes as usize;
    if payload_size > max {
        return Err(FileSessionError::NegotiatedFrameTooLarge {
            size: payload_size,
            max,
        });
    }
    Ok(())
}

fn validate_limits(limits: FileSessionLimits) -> Result<(), FileSessionError> {
    if limits.operation_timeout.is_zero() || limits.durable_write_timeout.is_zero() {
        return Err(FileSessionError::Timeout {
            operation: "zero session timeout",
        });
    }
    Ok(())
}

fn random_nonzero_id() -> [u8; FILE_CONTROL_ID_BYTES] {
    let mut id = [0u8; FILE_CONTROL_ID_BYTES];
    while id.iter().all(|byte| *byte == 0) {
        OsRng.fill_bytes(&mut id);
    }
    id
}

pub(crate) fn derive_file_session_scope(
    tls_exporter_binding: &[u8; 32],
    initiator_record_id: &[u8; FILE_CONTROL_ID_BYTES]
) -> [u8; FILE_CONTROL_ID_BYTES] {
    let mut hasher = Sha256::new();
    hasher.update(FILE_SESSION_SCOPE_DOMAIN_V1);
    hasher.update(tls_exporter_binding);
    hasher.update(initiator_record_id);
    let digest = hasher.finalize();
    digest[..FILE_CONTROL_ID_BYTES].try_into().expect("fixed SHA-256 prefix")
}

fn make_capability_control<S: FileControlSigner + ?Sized>(
    local_node_id: &str,
    local_identity: &S,
    recipient_node_id: &str,
    record_id: [u8; FILE_CONTROL_ID_BYTES],
    scope_id: [u8; FILE_CONTROL_ID_BYTES],
    capabilities: FileCapabilitiesV1,
    now_ms: i64
) -> Result<SignedFileControlV1, FileSessionError> {
    if now_ms < 0 {
        return Err(FileSessionError::InvalidTime);
    }
    let lifetime = DEFAULT_AUTH_LIFETIME_MS.min(MAX_FILE_CAPABILITY_LIFETIME_MS);
    let expires_at_ms = now_ms.checked_add(lifetime).ok_or(FileSessionError::InvalidTime)?;
    Ok(
        sign_file_control_with_signer_v1(
            FileControlClaimsV1 {
                record_id,
                scope_id,
                sequence: 1,
                created_at_ms: now_ms,
                expires_at_ms,
                signer_node_id: local_node_id.to_owned(),
                recipient_node_id: recipient_node_id.to_owned(),
                body: FileControlBodyV1::Capabilities(capabilities),
            },
            local_identity
        )?
    )
}

fn verify_capability_control(
    control: &SignedFileControlV1,
    peer: &FileSessionPeer,
    local_node_id: &str,
    expected_scope: &[u8; FILE_CONTROL_ID_BYTES],
    now_ms: i64
) -> Result<FileCapabilitiesV1, FileSessionError> {
    control.verify_at(
        &peer.node_id,
        local_node_id,
        &peer.ed25519_public_key,
        expected_scope,
        now_ms,
        None
    )?;
    match &control.claims.body {
        FileControlBodyV1::Capabilities(capabilities) => Ok(capabilities.clone()),
        _ => Err(FileSessionError::ExpectedCapabilities),
    }
}

async fn timeout_result<F, T>(
    duration: Duration,
    operation: &'static str,
    future: F
) -> Result<T, FileSessionError>
    where F: Future<Output = T>
{
    tokio::time
        ::timeout(duration, future).await
        .map_err(|_| FileSessionError::Timeout { operation })
}

async fn write_record_timeout(
    send: &mut SendStream,
    record_type: u8,
    payload: &[u8],
    timeout: Duration
) -> Result<(), FileSessionError> {
    timeout_result(timeout, "write session record", write_record(send, record_type, payload)).await?
}

async fn read_record_timeout(
    recv: &mut RecvStream,
    timeout: Duration
) -> Result<SessionRecord, FileSessionError> {
    timeout_result(timeout, "read session record", read_record(recv)).await?
}

async fn write_record(
    send: &mut SendStream,
    record_type: u8,
    payload: &[u8]
) -> Result<(), FileSessionError> {
    validate_record_length(record_type, payload.len())?;
    let payload_len = u32::try_from(payload.len()).map_err(|_| {
        FileSessionError::RecordTooLarge {
            size: payload.len(),
            max: MAX_FILE_FRAME_BYTES,
        }
    })?;
    let mut header = [0u8; FILE_SESSION_HEADER_BYTES];
    header[..4].copy_from_slice(&FILE_SESSION_MAGIC);
    header[4] = FILE_SESSION_VERSION_V1;
    header[5] = record_type;
    header[6..8].copy_from_slice(&FILE_SESSION_FLAGS_V1.to_be_bytes());
    header[8..12].copy_from_slice(&payload_len.to_be_bytes());
    send.write_all(&header).await.map_err(|error| FileSessionError::Io {
        operation: "write session header",
        reason: error.to_string(),
    })?;
    send.write_all(payload).await.map_err(|error| FileSessionError::Io {
        operation: "write session payload",
        reason: error.to_string(),
    })?;
    Ok(())
}

async fn read_record(recv: &mut RecvStream) -> Result<SessionRecord, FileSessionError> {
    let mut header = [0u8; FILE_SESSION_HEADER_BYTES];
    recv.read_exact(&mut header).await.map_err(|error| FileSessionError::Io {
        operation: "read session header",
        reason: error.to_string(),
    })?;
    if header[..4] != FILE_SESSION_MAGIC {
        return Err(FileSessionError::InvalidMagic);
    }
    if header[4] != FILE_SESSION_VERSION_V1 {
        return Err(FileSessionError::UnsupportedVersion { got: header[4] });
    }
    let record_type = header[5];
    let flags = u16::from_be_bytes(
        header[6..8].try_into().map_err(|_| FileSessionError::InvalidRecordLength {
            record_type,
            actual: header.len(),
        })?
    );
    if flags != FILE_SESSION_FLAGS_V1 {
        return Err(FileSessionError::UnsupportedFlags { got: flags });
    }
    let payload_len = usize
        ::try_from(
            u32::from_be_bytes(
                header[8..12].try_into().map_err(|_| FileSessionError::InvalidRecordLength {
                    record_type,
                    actual: header.len(),
                })?
            )
        )
        .map_err(|_| FileSessionError::RecordTooLarge {
            size: usize::MAX,
            max: MAX_FILE_FRAME_BYTES,
        })?;
    validate_record_length(record_type, payload_len)?;
    let mut payload = vec![0u8; payload_len];
    recv.read_exact(&mut payload).await.map_err(|error| FileSessionError::Io {
        operation: "read session payload",
        reason: error.to_string(),
    })?;
    Ok(SessionRecord {
        record_type,
        payload,
    })
}

fn validate_record_length(record_type: u8, payload_len: usize) -> Result<(), FileSessionError> {
    let max = match record_type {
        SESSION_RECORD_AUTH | SESSION_RECORD_AUTH_OK | SESSION_RECORD_CONTROL => {
            MAX_FILE_CONTROL_BYTES
        }
        SESSION_RECORD_CHUNK => MAX_FILE_FRAME_BYTES,
        SESSION_RECORD_CHUNK_ACK => FILE_SESSION_ACK_BYTES,
        SESSION_RECORD_CLOSE => 0,
        got => {
            return Err(FileSessionError::UnsupportedRecordType { got });
        }
    };
    if payload_len > max {
        return Err(FileSessionError::RecordTooLarge {
            size: payload_len,
            max,
        });
    }
    if
        (record_type == SESSION_RECORD_CHUNK_ACK && payload_len != FILE_SESSION_ACK_BYTES) ||
        (record_type == SESSION_RECORD_CLOSE && payload_len != 0)
    {
        return Err(FileSessionError::InvalidRecordLength {
            record_type,
            actual: payload_len,
        });
    }
    Ok(())
}

fn abort_streams(send: &mut SendStream, recv: &mut RecvStream) {
    let _ = send.reset(SESSION_ABORT_CODE.into());
    let _ = recv.stop(SESSION_ABORT_CODE.into());
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::signing_identity::InstalledSigningIdentity;
    use crate::network::file_control::{
        sign_file_control_v1,
        FileChunkRangePageV1,
        FileChunkRangeV1,
        FileControlClaimsV1,
    };
    use crate::network::file_wire::{
        FEATURE_CHUNK_RANGE_FRAMES,
        REQUIRED_FILE_FEATURES_V1,
        MAX_FILE_FRAME_PAYLOAD_BYTES,
    };
    use crate::network::QuicClient;
    use std::collections::HashSet;
    use std::net::{ IpAddr, Ipv4Addr, SocketAddr };
    use std::sync::{ Arc, Mutex };

    const NOW: i64 = 1_900_000_000_000;

    struct MemoryAdmission {
        scopes: HashSet<([u8; ED25519_PUBLIC_KEY_SIZE], [u8; FILE_CONTROL_ID_BYTES])>,
    }

    impl MemoryAdmission {
        fn new() -> Self {
            Self {
                scopes: HashSet::new(),
            }
        }
    }

    impl FileSessionAdmission for MemoryAdmission {
        fn admit_session(
            &mut self,
            peer_ed25519_public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
            scope_id: &[u8; FILE_CONTROL_ID_BYTES],
            _expires_at_ms: i64
        ) -> Result<bool, String> {
            Ok(self.scopes.insert((*peer_ed25519_public_key, *scope_id)))
        }
    }

    struct RecordingSink {
        events: Arc<Mutex<Vec<(u64, u32, usize)>>>,
        fail: bool,
    }

    impl DurableFileRangeSink for RecordingSink {
        fn persist_range<'a>(
            &'a mut self,
            range: &'a FileChunkDataV1
        ) -> Pin<Box<dyn Future<Output = Result<(), String>> + Send + 'a>> {
            Box::pin(async move {
                self.events
                    .lock()
                    .unwrap()
                    .push((range.chunk_index, range.chunk_offset, range.ciphertext.len()));
                if self.fail {
                    Err("injected durable write failure".into())
                } else {
                    Ok(())
                }
            })
        }
    }

    fn any_port() -> SocketAddr {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0)
    }

    fn identity(secret: u8) -> Ed25519KeyPair {
        Ed25519KeyPair::from_secret_bytes(&[secret; 32]).unwrap()
    }

    fn node(identity: &Ed25519KeyPair) -> String {
        format!("pk_{}", identity.node_id().to_hex())
    }

    fn peer(identity: &Ed25519KeyPair) -> FileSessionPeer {
        FileSessionPeer {
            node_id: node(identity),
            ed25519_public_key: identity.public_key().0.try_into().unwrap(),
        }
    }

    fn installed_identity(secret: u8) -> Arc<InstalledSigningIdentity> {
        Arc::new(
            InstalledSigningIdentity::from_seed(
                1,
                format!("pk_{}", format!("{secret:02x}").repeat(16)),
                &[secret; 32],
            )
            .unwrap(),
        )
    }

    fn installed_peer(identity: &InstalledSigningIdentity) -> FileSessionPeer {
        FileSessionPeer {
            node_id: identity.legacy_routing_node_id().to_owned(),
            ed25519_public_key: identity.public_key().try_into().unwrap(),
        }
    }

    fn capabilities() -> FileCapabilitiesV1 {
        FileCapabilitiesV1 {
            min_protocol_version: 1,
            max_protocol_version: 1,
            max_parallel_streams: 8,
            mandatory_features: REQUIRED_FILE_FEATURES_V1,
            optional_features: FEATURE_CHUNK_RANGE_FRAMES,
            max_frame_payload_bytes: MAX_FILE_FRAME_PAYLOAD_BYTES as u32,
        }
    }

    fn limits() -> FileSessionLimits {
        FileSessionLimits {
            operation_timeout: Duration::from_secs(2),
            durable_write_timeout: Duration::from_secs(2),
        }
    }

    fn chunk(index: u64, byte: u8) -> FileFrameV1 {
        FileFrameV1::ChunkData(FileChunkDataV1 {
            transfer_id: [0x41; 16],
            chunk_index: index,
            chunk_offset: 0,
            ciphertext_chunk_len: 64,
            ciphertext: vec![byte; 64],
        })
    }

    fn raw_header(record_type: u8, payload_len: u32) -> [u8; FILE_SESSION_HEADER_BYTES] {
        let mut header = [0u8; FILE_SESSION_HEADER_BYTES];
        header[..4].copy_from_slice(&FILE_SESSION_MAGIC);
        header[4] = FILE_SESSION_VERSION_V1;
        header[5] = record_type;
        header[8..12].copy_from_slice(&payload_len.to_be_bytes());
        header
    }

    fn signed_capabilities(
        signer: &Ed25519KeyPair,
        recipient: &Ed25519KeyPair,
        record_id: [u8; FILE_CONTROL_ID_BYTES],
        scope_id: [u8; FILE_CONTROL_ID_BYTES]
    ) -> SignedFileControlV1 {
        make_capability_control(
            &node(signer),
            signer,
            &node(recipient),
            record_id,
            scope_id,
            capabilities(),
            NOW
        ).unwrap()
    }

    #[tokio::test]
    async fn installed_sidecars_complete_mutual_c1_authentication() {
        let server_endpoint = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server_endpoint.local_address();
        let client_endpoint = QuicClient::new(any_port()).unwrap();

        let server_task = {
            let server_endpoint = Arc::clone(&server_endpoint);
            tokio::spawn(async move {
                let server_identity = installed_identity(42);
                let client_identity = installed_identity(41);
                let connection = server_endpoint.accept().await.unwrap();
                let mut admission = MemoryAdmission::new();
                let mut session = FileReceiveSession::accept_with_signer(
                    &connection,
                    server_identity.legacy_routing_node_id(),
                    &server_identity,
                    installed_peer(&client_identity),
                    capabilities(),
                    NOW,
                    limits(),
                    &mut admission,
                )
                .await
                .unwrap();
                let mut sink = RecordingSink {
                    events: Arc::new(Mutex::new(Vec::new())),
                    fail: false,
                };
                assert!(matches!(
                    session.receive_chunk(&mut sink).await,
                    Err(FileSessionError::Closed)
                ));
            })
        };

        let client_identity = installed_identity(41);
        let server_identity = installed_identity(42);
        let connection = client_endpoint.connect(server_addr, "p2p-messenger").await.unwrap();
        let session = FileSendSession::connect_with_signer(
            &connection,
            client_identity.legacy_routing_node_id(),
            &client_identity,
            installed_peer(&server_identity),
            capabilities(),
            NOW,
            limits(),
        )
        .await
        .unwrap();
        assert_eq!(session.negotiated_capabilities().max_parallel_streams, 8);
        session.close().await.unwrap();
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn many_binary_frames_share_one_authenticated_connection_and_ack_after_sink() {
        let server_endpoint = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server_endpoint.local_address();
        let client_endpoint = QuicClient::new(any_port()).unwrap();
        let persisted = Arc::new(Mutex::new(Vec::new()));
        let persisted_server = Arc::clone(&persisted);

        let server_task = {
            let server_endpoint = Arc::clone(&server_endpoint);
            tokio::spawn(async move {
                let server_identity = identity(2);
                let client_identity = identity(1);
                let connection = server_endpoint.accept().await.unwrap();
                let mut admission = MemoryAdmission::new();
                let mut session = FileReceiveSession::accept(
                    &connection,
                    &node(&server_identity),
                    &server_identity,
                    peer(&client_identity),
                    capabilities(),
                    NOW,
                    limits(),
                    &mut admission
                ).await.unwrap();
                assert_eq!(session.negotiated_capabilities().max_parallel_streams, 8);
                let mut sink = RecordingSink {
                    events: persisted_server,
                    fail: false,
                };
                for expected in 0..12u64 {
                    let received = session.receive_chunk(&mut sink).await.unwrap();
                    assert_eq!(received.chunk_index, expected);
                }
                assert!(matches!(
                    session.receive_chunk(&mut sink).await,
                    Err(FileSessionError::Closed)
                ));
            })
        };

        let client_identity = identity(1);
        let server_identity = identity(2);
        let connection = client_endpoint.connect(server_addr, "p2p-messenger").await.unwrap();
        let mut session = FileSendSession::connect(
            &connection,
            &node(&client_identity),
            &client_identity,
            peer(&server_identity),
            capabilities(),
            NOW,
            limits()
        ).await.unwrap();
        for index in 0..12u64 {
            session.send_chunk(&chunk(index, index as u8)).await.unwrap();
            assert_eq!(persisted.lock().unwrap().len(), index as usize + 1);
        }
        session.close().await.unwrap();
        server_task.await.unwrap();
        assert_eq!(persisted.lock().unwrap().len(), 12);
    }

    #[tokio::test]
    async fn durable_failure_aborts_without_chunk_ack() {
        let server_endpoint = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server_endpoint.local_address();
        let client_endpoint = QuicClient::new(any_port()).unwrap();

        let server_task = {
            let server_endpoint = Arc::clone(&server_endpoint);
            tokio::spawn(async move {
                let server_identity = identity(4);
                let client_identity = identity(3);
                let connection = server_endpoint.accept().await.unwrap();
                let mut admission = MemoryAdmission::new();
                let mut session = FileReceiveSession::accept(
                    &connection,
                    &node(&server_identity),
                    &server_identity,
                    peer(&client_identity),
                    capabilities(),
                    NOW,
                    limits(),
                    &mut admission
                ).await.unwrap();
                let mut sink = RecordingSink {
                    events: Arc::new(Mutex::new(Vec::new())),
                    fail: true,
                };
                assert!(matches!(
                    session.receive_chunk(&mut sink).await,
                    Err(FileSessionError::DurableSink(_))
                ));
            })
        };

        let client_identity = identity(3);
        let server_identity = identity(4);
        let connection = client_endpoint.connect(server_addr, "p2p-messenger").await.unwrap();
        let mut session = FileSendSession::connect(
            &connection,
            &node(&client_identity),
            &client_identity,
            peer(&server_identity),
            capabilities(),
            NOW,
            limits()
        ).await.unwrap();
        assert!(session.send_chunk(&chunk(0, 7)).await.is_err());
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn reconnect_carries_signed_missing_range_then_resumes_binary_chunk() {
        let server_endpoint = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server_endpoint.local_address();
        let client_endpoint = QuicClient::new(any_port()).unwrap();
        let transfer_id = [0x51; FILE_CONTROL_ID_BYTES];

        let server_task = {
            let server_endpoint = Arc::clone(&server_endpoint);
            tokio::spawn(async move {
                let server_identity = identity(6);
                let client_identity = identity(5);
                let mut admission = MemoryAdmission::new();
                let first_connection = server_endpoint.accept().await.unwrap();
                let mut first = FileReceiveSession::accept(
                    &first_connection,
                    &node(&server_identity),
                    &server_identity,
                    peer(&client_identity),
                    capabilities(),
                    NOW,
                    limits(),
                    &mut admission
                ).await.unwrap();
                let mut sink = RecordingSink {
                    events: Arc::new(Mutex::new(Vec::new())),
                    fail: false,
                };
                assert_eq!(first.receive_chunk(&mut sink).await.unwrap().chunk_index, 0);
                assert!(matches!(
                    first.receive_chunk(&mut sink).await,
                    Err(FileSessionError::Closed)
                ));

                let second_connection = server_endpoint.accept().await.unwrap();
                let mut second = FileReceiveSession::accept(
                    &second_connection,
                    &node(&server_identity),
                    &server_identity,
                    peer(&client_identity),
                    capabilities(),
                    NOW + 1,
                    limits(),
                    &mut admission
                ).await.unwrap();
                let missing = sign_file_control_v1(
                    FileControlClaimsV1 {
                        record_id: [0x61; FILE_CONTROL_ID_BYTES],
                        scope_id: transfer_id,
                        sequence: 1,
                        created_at_ms: NOW,
                        expires_at_ms: NOW + 60_000,
                        signer_node_id: node(&server_identity),
                        recipient_node_id: node(&client_identity),
                        body: FileControlBodyV1::MissingRanges(FileChunkRangePageV1 {
                            batch_id: [0x71; FILE_CONTROL_ID_BYTES],
                            page_index: 0,
                            is_last_page: true,
                            total_chunk_count: 2,
                            ranges: vec![FileChunkRangeV1 {
                                start_chunk: 1,
                                end_chunk_exclusive: 2,
                            }],
                        }),
                    },
                    &server_identity
                ).unwrap();
                second.send_control(&missing).await.unwrap();
                assert_eq!(second.receive_chunk(&mut sink).await.unwrap().chunk_index, 1);
                assert!(matches!(
                    second.receive_chunk(&mut sink).await,
                    Err(FileSessionError::Closed)
                ));
            })
        };

        let client_identity = identity(5);
        let server_identity = identity(6);
        let first_connection = client_endpoint.connect(server_addr, "p2p-messenger").await.unwrap();
        let mut first = FileSendSession::connect(
            &first_connection,
            &node(&client_identity),
            &client_identity,
            peer(&server_identity),
            capabilities(),
            NOW,
            limits()
        ).await.unwrap();
        first.send_chunk(&chunk(0, 1)).await.unwrap();
        first.close().await.unwrap();

        let second_connection = client_endpoint
            .connect(server_addr, "p2p-messenger").await
            .unwrap();
        let mut second = FileSendSession::connect(
            &second_connection,
            &node(&client_identity),
            &client_identity,
            peer(&server_identity),
            capabilities(),
            NOW + 1,
            limits()
        ).await.unwrap();
        let missing = second.receive_control(&transfer_id, NOW + 1, None).await.unwrap();
        assert!(matches!(
            missing.claims.body,
            FileControlBodyV1::MissingRanges(_)
        ));
        second.send_chunk(&chunk(1, 2)).await.unwrap();
        second.close().await.unwrap();
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn replayed_authenticated_scope_is_rejected_on_a_second_stream() {
        let server_endpoint = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server_endpoint.local_address();
        let client_endpoint = QuicClient::new(any_port()).unwrap();

        let server_task = {
            let server_endpoint = Arc::clone(&server_endpoint);
            tokio::spawn(async move {
                let server_identity = identity(15);
                let client_identity = identity(14);
                let mut admission = MemoryAdmission::new();
                let connection = server_endpoint.accept().await.unwrap();

                FileReceiveSession::accept(
                    &connection,
                    &node(&server_identity),
                    &server_identity,
                    peer(&client_identity),
                    capabilities(),
                    NOW,
                    limits(),
                    &mut admission
                ).await.unwrap();

                assert!(matches!(
                    FileReceiveSession::accept(
                        &connection,
                        &node(&server_identity),
                        &server_identity,
                        peer(&client_identity),
                        capabilities(),
                        NOW,
                        limits(),
                        &mut admission,
                    )
                    .await,
                    Err(FileSessionError::ReplayedSession)
                ));
                assert_eq!(admission.scopes.len(), 1);
            })
        };

        let client_identity = identity(14);
        let server_identity = identity(15);
        let connection = client_endpoint.connect(server_addr, "p2p-messenger").await.unwrap();
        let record_id = [0x81; FILE_CONTROL_ID_BYTES];
        let channel_binding = connection.file_session_channel_binding(&record_id).unwrap();
        let scope_id = derive_file_session_scope(&channel_binding, &record_id);
        let auth = signed_capabilities(&client_identity, &server_identity, record_id, scope_id)
            .encode()
            .unwrap();

        let (mut first_send, mut first_recv) = connection.open_file_session_stream().await.unwrap();
        write_record(&mut first_send, SESSION_RECORD_AUTH, &auth).await.unwrap();
        let response = read_record(&mut first_recv).await.unwrap();
        assert_eq!(response.record_type, SESSION_RECORD_AUTH_OK);
        drop(first_send);
        drop(first_recv);

        let (mut replay_send, _replay_recv) = connection.open_file_session_stream().await.unwrap();
        write_record(&mut replay_send, SESSION_RECORD_AUTH, &auth).await.unwrap();
        replay_send.finish().unwrap();
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn wrong_pinned_peer_is_rejected_before_session_acceptance() {
        let server_endpoint = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server_endpoint.local_address();
        let client_endpoint = QuicClient::new(any_port()).unwrap();

        let server_task = {
            let server_endpoint = Arc::clone(&server_endpoint);
            tokio::spawn(async move {
                let server_identity = identity(8);
                let wrong_client = identity(9);
                let connection = server_endpoint.accept().await.unwrap();
                let mut admission = MemoryAdmission::new();
                assert!(matches!(
                    FileReceiveSession::accept(
                        &connection,
                        &node(&server_identity),
                        &server_identity,
                        peer(&wrong_client),
                        capabilities(),
                        NOW,
                        limits(),
                        &mut admission,
                    )
                    .await,
                    Err(FileSessionError::Control(FileControlError::UnexpectedSigner))
                ));
                assert!(admission.scopes.is_empty());
            })
        };

        let client_identity = identity(7);
        let server_identity = identity(8);
        let connection = client_endpoint.connect(server_addr, "p2p-messenger").await.unwrap();
        assert!(FileSendSession::connect(
            &connection,
            &node(&client_identity),
            &client_identity,
            peer(&server_identity),
            capabilities(),
            NOW,
            limits(),
        )
        .await
        .is_err());
        server_task.await.unwrap();
    }

    async fn send_raw_auth(
        server_endpoint: Arc<QuicClient>,
        client_endpoint: &QuicClient,
        payload: &[u8]
    ) -> FileSessionError {
        let server_addr = server_endpoint.local_address();
        let server_task = tokio::spawn(async move {
            let server_identity = identity(11);
            let client_identity = identity(10);
            let connection = server_endpoint.accept().await.unwrap();
            let mut admission = MemoryAdmission::new();
            match
                FileReceiveSession::accept(
                    &connection,
                    &node(&server_identity),
                    &server_identity,
                    peer(&client_identity),
                    capabilities(),
                    NOW,
                    limits(),
                    &mut admission
                ).await
            {
                Ok(_) => panic!("malformed authentication was accepted"),
                Err(error) => error,
            }
        });
        let connection = client_endpoint.connect(server_addr, "p2p-messenger").await.unwrap();
        let (mut send, _recv) = connection.open_file_session_stream().await.unwrap();
        write_record(&mut send, SESSION_RECORD_AUTH, payload).await.unwrap();
        send.finish().unwrap();
        server_task.await.unwrap()
    }

    #[tokio::test]
    async fn tampered_signature_and_wrong_channel_scope_fail_closed() {
        let server_endpoint = Arc::new(QuicClient::new(any_port()).unwrap());
        let client_endpoint = QuicClient::new(any_port()).unwrap();
        let signer = identity(10);
        let recipient = identity(11);
        let mut tampered = signed_capabilities(
            &signer,
            &recipient,
            [0x31; FILE_CONTROL_ID_BYTES],
            [0x32; FILE_CONTROL_ID_BYTES]
        )
            .encode()
            .unwrap();
        let last = tampered.len() - 1;
        tampered[last] ^= 1;
        assert!(matches!(
            send_raw_auth(Arc::clone(&server_endpoint), &client_endpoint, &tampered).await,
            FileSessionError::Control(FileControlError::InvalidSignature)
        ));

        let server_endpoint = Arc::new(QuicClient::new(any_port()).unwrap());
        let client_endpoint = QuicClient::new(any_port()).unwrap();
        let wrong_scope = signed_capabilities(
            &signer,
            &recipient,
            [0x41; FILE_CONTROL_ID_BYTES],
            [0x42; FILE_CONTROL_ID_BYTES]
        )
            .encode()
            .unwrap();
        assert!(matches!(
            send_raw_auth(server_endpoint, &client_endpoint, &wrong_scope).await,
            FileSessionError::Control(FileControlError::UnexpectedScope)
        ));
    }

    async fn malformed_record_result(
        header: [u8; FILE_SESSION_HEADER_BYTES],
        trailing: &[u8]
    ) -> FileSessionError {
        let server_endpoint = Arc::new(QuicClient::new(any_port()).unwrap());
        let server_addr = server_endpoint.local_address();
        let client_endpoint = QuicClient::new(any_port()).unwrap();
        let server_task = {
            let server_endpoint = Arc::clone(&server_endpoint);
            tokio::spawn(async move {
                let server_identity = identity(13);
                let client_identity = identity(12);
                let connection = server_endpoint.accept().await.unwrap();
                let mut admission = MemoryAdmission::new();
                match
                    FileReceiveSession::accept(
                        &connection,
                        &node(&server_identity),
                        &server_identity,
                        peer(&client_identity),
                        capabilities(),
                        NOW,
                        limits(),
                        &mut admission
                    ).await
                {
                    Ok(_) => panic!("malformed session record was accepted"),
                    Err(error) => error,
                }
            })
        };
        let connection = client_endpoint.connect(server_addr, "p2p-messenger").await.unwrap();
        let (mut send, _recv) = connection.open_file_session_stream().await.unwrap();
        send.write_all(&header).await.unwrap();
        send.write_all(trailing).await.unwrap();
        send.finish().unwrap();
        server_task.await.unwrap()
    }

    #[test]
    fn negotiated_payload_limit_is_enforced_before_network_write() {
        let encoded = chunk(0, 9).encode().unwrap();
        let negotiated = NegotiatedFileCapabilitiesV1 {
            protocol_version: 1,
            enabled_features: REQUIRED_FILE_FEATURES_V1,
            max_parallel_streams: 1,
            max_frame_payload_bytes: 40,
        };
        assert!(matches!(
            validate_negotiated_frame_size(&encoded, &negotiated),
            Err(FileSessionError::NegotiatedFrameTooLarge { size: 100, max: 40 })
        ));
    }

    #[tokio::test]
    async fn truncated_record_fails_without_accepting_a_session() {
        let error = malformed_record_result(raw_header(SESSION_RECORD_AUTH, 100), &[1, 2]).await;
        assert!(matches!(error, FileSessionError::Io { .. }));
    }

    #[tokio::test]
    async fn oversized_record_is_rejected_before_payload_allocation() {
        let oversized = u32::try_from(MAX_FILE_CONTROL_BYTES + 1).unwrap();
        let error = malformed_record_result(raw_header(SESSION_RECORD_AUTH, oversized), &[]).await;
        assert!(matches!(
            error,
            FileSessionError::RecordTooLarge { size, max }
                if size == MAX_FILE_CONTROL_BYTES + 1 && max == MAX_FILE_CONTROL_BYTES
        ));
    }
}
