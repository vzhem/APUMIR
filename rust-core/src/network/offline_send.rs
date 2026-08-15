//! Offline-origin relay preparation for the automatic M3(d) send path.
//!
//! This module is intentionally transport-free. It validates bounded, delimiter-safe metadata,
//! creates the origin's durable-in-memory [`RelayMessage`], and emits the existing legacy-compatible
//! `relay|...` wire envelope understood by N-1. No protocol version is introduced here because the
//! encoding is unchanged; a future incompatible format must use explicit capability negotiation.

use std::time::Duration;

use crate::network::relay_queue::{RelayMessage, DEFAULT_RELAY_TTL};
use crate::network::wire;

pub const MAX_MESH_RELAY_ENVELOPE_BYTES: usize = 64 * 1024;
const MAX_MESSAGE_ID_BYTES: usize = 128;
const MAX_NODE_ID_BYTES: usize = 128;
const MAX_CHAT_SCOPE_BYTES: usize = 256;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum OfflineRelayPrepareError {
    InvalidMessageId,
    InvalidRecipient,
    InvalidOrigin,
    InvalidChatScope,
    EnvelopeTooLarge { actual: usize, max: usize },
}

impl std::fmt::Display for OfflineRelayPrepareError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidMessageId => write!(f, "invalid message ID for mesh relay"),
            Self::InvalidRecipient => write!(f, "invalid recipient ID for mesh relay"),
            Self::InvalidOrigin => write!(f, "invalid origin ID for mesh relay"),
            Self::InvalidChatScope => write!(f, "invalid chat scope for mesh relay"),
            Self::EnvelopeTooLarge { actual, max } => {
                write!(f, "mesh relay envelope too large: {actual} > {max}")
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PreparedOfflineRelay {
    pub message: RelayMessage,
    pub envelope: String,
}

fn valid_wire_atom(value: &str, max_bytes: usize) -> bool {
    !value.is_empty() && value.len() <= max_bytes && !value.contains('|')
}

pub fn prepare_offline_relay(
    message_id: &str,
    recipient: &str,
    origin: &str,
    chat_scope: &str,
    payload: &[u8],
) -> Result<PreparedOfflineRelay, OfflineRelayPrepareError> {
    if !valid_wire_atom(message_id, MAX_MESSAGE_ID_BYTES) {
        return Err(OfflineRelayPrepareError::InvalidMessageId);
    }
    if !valid_wire_atom(recipient, MAX_NODE_ID_BYTES) {
        return Err(OfflineRelayPrepareError::InvalidRecipient);
    }
    if !valid_wire_atom(origin, MAX_NODE_ID_BYTES) {
        return Err(OfflineRelayPrepareError::InvalidOrigin);
    }
    if !valid_wire_atom(chat_scope, MAX_CHAT_SCOPE_BYTES) {
        return Err(OfflineRelayPrepareError::InvalidChatScope);
    }

    let ttl = DEFAULT_RELAY_TTL;
    let envelope = wire::build_relay(
        message_id,
        recipient,
        origin,
        chat_scope,
        ttl.as_secs(),
        0,
        payload,
    );
    if envelope.len() > MAX_MESH_RELAY_ENVELOPE_BYTES {
        return Err(OfflineRelayPrepareError::EnvelopeTooLarge {
            actual: envelope.len(),
            max: MAX_MESH_RELAY_ENVELOPE_BYTES,
        });
    }

    let message = RelayMessage::with_ttl(
        message_id.to_owned(),
        recipient.to_owned(),
        origin.to_owned(),
        chat_scope.to_owned(),
        payload.to_vec(),
        Duration::from_secs(ttl.as_secs()),
    );

    Ok(PreparedOfflineRelay { message, envelope })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::network::wire::MeshEnvelope;

    #[test]
    fn prepares_legacy_compatible_hop_zero_relay() {
        let prepared = prepare_offline_relay(
            "msg-1",
            "pk_recipient",
            "pk_origin",
            "chat-1",
            b"ciphertext-or-legacy-payload",
        )
        .unwrap();

        assert_eq!(prepared.message.hop_count, 0);
        assert_eq!(prepared.message.recipient, "pk_recipient");
        match wire::parse(&prepared.envelope).unwrap() {
            MeshEnvelope::Relay {
                msg_id,
                recipient,
                origin,
                chat_scope,
                ttl_secs,
                hop,
                e2e_payload,
            } => {
                assert_eq!(msg_id, "msg-1");
                assert_eq!(recipient, "pk_recipient");
                assert_eq!(origin, "pk_origin");
                assert_eq!(chat_scope, "chat-1");
                assert_eq!(ttl_secs, DEFAULT_RELAY_TTL.as_secs());
                assert_eq!(hop, 0);
                assert_eq!(e2e_payload, b"ciphertext-or-legacy-payload");
            }
            _ => panic!("expected relay envelope"),
        }
    }

    #[test]
    fn rejects_delimiter_in_required_metadata() {
        assert_eq!(
            prepare_offline_relay("msg|bad", "pk_b", "pk_a", "chat", b"x")
                .unwrap_err(),
            OfflineRelayPrepareError::InvalidMessageId
        );
        assert_eq!(
            prepare_offline_relay("msg", "pk_b", "pk_a", "chat|bad", b"x")
                .unwrap_err(),
            OfflineRelayPrepareError::InvalidChatScope
        );
    }

    #[test]
    fn rejects_envelope_above_existing_mesh_limit() {
        let payload = vec![7u8; MAX_MESH_RELAY_ENVELOPE_BYTES];
        assert!(matches!(
            prepare_offline_relay("msg", "pk_b", "pk_a", "chat", &payload),
            Err(OfflineRelayPrepareError::EnvelopeTooLarge { .. })
        ));
    }
}
