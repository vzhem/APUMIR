//! Inbound runtime for the production F4 direct-file session.
//!
//! The legacy direct transport still accepts unauthenticated text/uni-stream frames. This listener
//! owns only C1 bidirectional streams and admits them solely when the signing key in the bounded
//! capability record matches a durable `PeerExchange` pin. Ciphertext ranges are committed by
//! `FileSessionIngressStore` before their transport ACK; no generic EventBus callback can claim
//! durability on its own.

use std::sync::Arc;

use tokio::sync::Semaphore;

use crate::crypto::keys::{NodeId, ED25519_PUBLIC_KEY_SIZE};
use crate::crypto::signing_identity::InstalledSigningIdentity;
use crate::engine::events::CoreEvent;
use crate::network::file_session::{
    FileReceiveSession, FileSessionLimits, FileSessionPeer, FileSessionPeerResolver,
};
use crate::network::file_session_ingress::FileSessionIngressStore;
use crate::network::file_wire::{
    FileCapabilitiesV1, FileChunkDataV1, FEATURE_CHUNK_RANGE_FRAMES,
    MAX_FILE_FRAME_PAYLOAD_BYTES, REQUIRED_FILE_FEATURES_V1,
};
use crate::network::peer_exchange::{valid_node_id, PeerExchange};
use crate::network::quic_client::QuicConnection;

pub const MAX_INBOUND_FILE_SESSIONS: usize = 16;

/// Receives F4 C1 streams from the existing direct QUIC endpoint. It intentionally has no
/// filesystem/network work in the `DirectTransport` read loop: every session has an independent
/// bounded permit and cannot block interactive uni-stream traffic.
#[derive(Clone)]
pub struct InboundFileSessionServer {
    local_identity: Arc<InstalledSigningIdentity>,
    directory: Arc<PeerExchange>,
    ingress: Arc<FileSessionIngressStore>,
    events: Arc<crate::engine::events::EventBus>,
    permits: Arc<Semaphore>,
}

#[derive(Clone)]
struct PinnedPeerResolver {
    directory: Arc<PeerExchange>,
}

impl InboundFileSessionServer {
    pub fn new(
        local_identity: Arc<InstalledSigningIdentity>,
        directory: Arc<PeerExchange>,
        ingress: Arc<FileSessionIngressStore>,
        events: Arc<crate::engine::events::EventBus>,
    ) -> Self {
        Self {
            local_identity,
            directory,
            ingress,
            events,
            permits: Arc::new(Semaphore::new(MAX_INBOUND_FILE_SESSIONS)),
        }
    }

    pub async fn handle_connection(self: Arc<Self>, connection: QuicConnection) {
        let Ok(_permit) = Arc::clone(&self.permits).try_acquire_owned() else {
            tracing::debug!(
                "F4: inbound file-session limit reached; refusing stream from {}",
                connection.remote_address()
            );
            connection.close(b"file session capacity");
            return;
        };

        let resolver = PinnedPeerResolver {
            directory: Arc::clone(&self.directory),
        };
        let mut admission = self.ingress.admission_handle();
        let now_ms = crate::storage::models::now_ms();
        let mut session = match FileReceiveSession::accept_with_resolver(
            &connection,
            self.local_identity.legacy_routing_node_id(),
            &*self.local_identity,
            &resolver,
            local_capabilities(),
            now_ms,
            FileSessionLimits::default(),
            &mut admission,
        )
        .await
        {
            Ok(session) => session,
            Err(error) => {
                // Do not log a claimed routing ID/key before it has passed the pin resolver.
                tracing::debug!(
                    "F4: rejected inbound authenticated file session from {}: {}",
                    connection.remote_address(),
                    error
                );
                return;
            }
        };

        loop {
            let mut sink = self.ingress.sink();
            match session.receive_chunk(&mut sink).await {
                Ok(chunk) => self.emit_durable_chunk(chunk),
                Err(crate::network::file_session::FileSessionError::Closed) => break,
                Err(error) => {
                    tracing::debug!(
                        "F4: inbound file session from {} ended: {}",
                        connection.remote_address(),
                        error
                    );
                    break;
                }
            }
        }
    }

    fn emit_durable_chunk(&self, chunk: FileChunkDataV1) {
        self.events.emit(CoreEvent::FileChunkReceived {
            transfer_id: hex(&chunk.transfer_id),
            chunk_index: chunk.chunk_index,
            chunk_offset: chunk.chunk_offset,
            ciphertext_chunk_len: chunk.ciphertext_chunk_len,
            ciphertext: chunk.ciphertext,
        });
    }
}

impl PinnedPeerResolver {
    fn is_modern_id_bound_to_key(node_id: &str, key: &[u8; ED25519_PUBLIC_KEY_SIZE]) -> bool {
        if node_id.len() != 67 {
            return true;
        }
        node_id == format!("pk_{}", NodeId::from_ed25519_pubkey(key).to_hex())
    }
}

impl FileSessionPeerResolver for PinnedPeerResolver {
    fn resolve_peer(
        &self,
        claimed_node_id: &str,
        claimed_ed25519_public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
    ) -> Result<FileSessionPeer, String> {
        if !valid_node_id(claimed_node_id)
            || claimed_ed25519_public_key.iter().all(|byte| *byte == 0)
            || !Self::is_modern_id_bound_to_key(claimed_node_id, claimed_ed25519_public_key)
        {
            return Err("claimed peer identity is not canonical".into());
        }
        let pinned = self
            .directory
            .pinned_key(claimed_node_id)
            .ok_or_else(|| "no durable pin for this contact".to_string())?;
        if pinned != *claimed_ed25519_public_key {
            return Err("claimed signing key differs from durable contact pin".into());
        }
        Ok(FileSessionPeer {
            node_id: claimed_node_id.to_owned(),
            ed25519_public_key: pinned,
        })
    }
}

fn local_capabilities() -> FileCapabilitiesV1 {
    FileCapabilitiesV1 {
        min_protocol_version: 1,
        max_protocol_version: 1,
        max_parallel_streams: 4,
        mandatory_features: REQUIRED_FILE_FEATURES_V1,
        optional_features: FEATURE_CHUNK_RANGE_FRAMES,
        max_frame_payload_bytes: MAX_FILE_FRAME_PAYLOAD_BYTES as u32,
    }
}

fn hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut value = String::with_capacity(bytes.len().saturating_mul(2));
    for byte in bytes {
        value.push(HEX[(byte >> 4) as usize] as char);
        value.push(HEX[(byte & 0x0f) as usize] as char);
    }
    value
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::signing_identity::InstalledSigningIdentity;

    #[test]
    fn modern_identity_must_match_its_ed25519_public_key() {
        let identity = InstalledSigningIdentity::from_seed(
            1,
            format!("pk_{}", "42".repeat(32)),
            &[42; 32],
        )
        .unwrap();
        let key: [u8; 32] = identity.public_key().try_into().unwrap();
        let modern = format!("pk_{}", NodeId::from_ed25519_pubkey(&key).to_hex());
        assert!(PinnedPeerResolver::is_modern_id_bound_to_key(&modern, &key));
        assert!(!PinnedPeerResolver::is_modern_id_bound_to_key(
            &format!("pk_{}", "00".repeat(32)),
            &key
        ));
    }

    #[test]
    fn hex_is_canonical_lowercase() {
        assert_eq!(hex(&[0, 0x0f, 0xa5, 0xff]), "000fa5ff");
    }
}
