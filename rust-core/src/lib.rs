// Р Р°Р·СЂРµС€Р°РµРј clippy РїСЂРµРґСѓРїСЂРµР¶РґРµРЅРёРµ РІ СЃРіРµРЅРµСЂРёСЂРѕРІР°РЅРЅРѕРј UniFFI РєРѕРґРµ
#![allow(clippy::empty_line_after_doc_comments)]
//! # P2P Messenger Core
//! РЇРґСЂРѕ P2P-РјРµСЃСЃРµРЅРґР¶РµСЂР° РЅР° Rust.

uniffi::include_scaffolding!("lib");

// в”Ђв”Ђ РџРѕРґРєР»СЋС‡С‘РЅРЅС‹Рµ РјРѕРґСѓР»Рё в”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђв”Ђ
pub mod config;
pub mod crypto;
pub mod engine;
pub mod ffi;
pub mod logging;
pub mod network;
pub mod protocol;
pub mod storage;
pub mod sync;
pub mod resilience;

use engine::core::{EngineConfig, P2PCore};
use engine::events::CoreEvent;
use storage::models::{Chat, Message};

pub use config::defaults::APP_VERSION;
pub use config::defaults::PROTOCOL_VERSION;

// ============================================================
// FFI Types (СЃРѕРѕС‚РІРµС‚СЃС‚РІСѓСЋС‚ UDL)
// ============================================================

/// РЎРѕРѕР±С‰РµРЅРёРµ РґР»СЏ Kotlin
pub struct MessageFfi {
    pub id: String,
    pub chat_id: String,
    pub sender_id: String,
    pub text: String,
    pub status: String,
    pub created_at: i64,
    pub is_deleted: bool,
}

/// Р§Р°С‚ РґР»СЏ Kotlin
pub struct ChatFfi {
    pub id: String,
    pub kind: String,
    pub title: Option<String>,
    pub last_message_at: i64,
    pub unread_count: i64,
}

/// РЎРѕР±С‹С‚РёРµ РґР»СЏ Kotlin
pub struct CoreEventFfi {
    pub event_type: String,
    pub node_id: Option<String>,
    pub peer_id: Option<String>,
    pub display_name: Option<String>,
    pub message_id: Option<String>,
    pub chat_id: Option<String>,
    pub sender_id: Option<String>,
    pub text: Option<String>,
    pub status: Option<String>,
    pub timestamp: Option<i64>,
    pub is_local: Option<bool>,
}

/// РЎС‚Р°С‚СѓСЃ СЃРѕРѕР±С‰РµРЅРёСЏ РґР»СЏ Kotlin
pub enum MessageStatusFfi {
    Pending,
    Sent,
    Delivered,
    Read,
    Failed,
}

// ============================================================
// P2PCoreHandle вЂ” UniFFI РѕР±СЉРµРєС‚
// ============================================================

pub struct P2PCoreHandle {
    inner: std::sync::Mutex<P2PCore>,
}

impl P2PCoreHandle {
    fn new(display_name: String) -> Self {
        let config = EngineConfig::new(display_name);
        Self {
            inner: std::sync::Mutex::new(P2PCore::new(config)),
        }
    }

    pub fn start(&self) -> bool {
        self.inner.lock().unwrap().start()
    }

    pub fn stop(&self) {
        self.inner.lock().unwrap().stop()
    }

    pub fn is_running(&self) -> bool {
        self.inner.lock().unwrap().is_running()
    }

    pub fn node_id(&self) -> Option<String> {
        self.inner.lock().unwrap().node_id()
    }

    pub fn public_key(&self) -> Option<String> {
        self.inner.lock().unwrap().public_key()
    }

    pub fn network_status(&self) -> String {
        self.inner.lock().unwrap().network_status()
    }

    pub fn connected_peers(&self) -> u64 {
        self.inner.lock().unwrap().connected_peers() as u64
    }

    pub fn trigger_gossip_discovery(&self) -> bool {
        self.inner.lock().unwrap().trigger_gossip_discovery()
    }

    pub fn on_network_available(&self) {
        self.inner.lock().unwrap().on_network_available()
    }

    pub fn on_network_lost(&self) {
        self.inner.lock().unwrap().on_network_lost()
    }

    pub fn send_message(
        &self,
        message_id: String,
        chat_id: String,
        recipient_id: String,
        text: String,
    ) -> bool {
        self.inner
            .lock()
            .unwrap()
            .send_message(message_id, chat_id, recipient_id, text)
    }

    pub fn receive_message(
        &self,
        message_id: String,
        chat_id: String,
        sender_id: String,
        encrypted_text: String,
        timestamp: i64,
    ) {
        self.inner.lock().unwrap().receive_message(
            message_id,
            chat_id,
            sender_id,
            encrypted_text,
            timestamp,
        )
    }

    pub fn mark_message_read(&self, message_id: String) -> bool {
        self.inner.lock().unwrap().mark_message_read(&message_id)
    }

    pub fn create_chat(&self, chat_id: String) -> bool {
        self.inner.lock().unwrap().create_chat(chat_id)
    }

    pub fn add_contact(&self, user_id: String, display_name: String) -> bool {
        self.inner
            .lock()
            .unwrap()
            .add_contact(user_id, display_name)
    }


    pub fn generate_invite(&self) -> String {
        self.inner.lock().unwrap().generate_invite()
    }

    pub fn connect_via_invite(&self, link: String) -> bool {
        self.inner.lock().unwrap().connect_via_invite(link)
    }
    pub fn send_message_mqtt(&self, to_node_id: String, payload: String) -> bool {
        self.inner.lock().unwrap().send_message_mqtt(&to_node_id, &payload)
    }
    pub fn get_chats(&self) -> Vec<ChatFfi> {
        self.inner
            .lock()
            .unwrap()
            .get_chats()
            .into_iter()
            .map(chat_to_ffi)
            .collect()
    }

    pub fn get_messages(&self, chat_id: String, limit: u64) -> Vec<MessageFfi> {
        self.inner
            .lock()
            .unwrap()
            .get_messages(&chat_id, limit as usize)
            .into_iter()
            .map(message_to_ffi)
            .collect()
    }

    pub fn poll_event(&self) -> Option<CoreEventFfi> {
        self.inner.lock().unwrap().poll_event().map(event_to_ffi)
    }

    pub fn drain_events(&self) -> Vec<CoreEventFfi> {
        self.inner
            .lock()
            .unwrap()
            .drain_events()
            .into_iter()
            .map(event_to_ffi)
            .collect()
    }

    pub fn pending_events(&self) -> u64 {
        self.inner.lock().unwrap().pending_events() as u64
    }

    pub fn relay_custody_mode(&self) -> String {
        self.inner.lock().unwrap().relay_custody_mode()
    }

    pub fn relay_quarantine_count(&self) -> u64 {
        self.inner.lock().unwrap().relay_quarantine_count()
    }
}

// ============================================================
// Namespace functions
// ============================================================

pub fn initialize_core() -> Result<String, CoreError> {
    logging::init_logger();
    tracing::info!(
        version = APP_VERSION,
        protocol = PROTOCOL_VERSION,
        "P2P Core РёРЅРёС†РёР°Р»РёР·РёСЂРѕРІР°РЅ"
    );
    Ok(format!(
        "P2P Core v{} Р·Р°РїСѓС‰РµРЅ. РџСЂРѕС‚РѕРєРѕР» v{}",
        APP_VERSION, PROTOCOL_VERSION
    ))
}

pub fn get_version() -> String {
    format!("P2P Core v{}", APP_VERSION)
}

pub fn get_protocol_version() -> u8 {
    PROTOCOL_VERSION
}

pub fn create_engine(display_name: String) -> std::sync::Arc<P2PCoreHandle> {
    std::sync::Arc::new(P2PCoreHandle::new(display_name))
}

pub fn create_engine_with_keys(
    display_name: String,
    public_key: String,
    private_key: String,
) -> std::sync::Arc<P2PCoreHandle> {
    let config = EngineConfig::new(display_name)
        .with_keys(public_key, private_key);
    std::sync::Arc::new(P2PCoreHandle {
        inner: std::sync::Mutex::new(P2PCore::new(config)),
    })
}

/// M8-C slice 3: engine с durable encrypted relay custody. Пустые строки ключей
/// означают «сгенерировать новые»; пустой путь запрещён (иначе durable-режим
/// был бы тихо не тем, что заявлено).
pub fn create_engine_durable(
    display_name: String,
    public_key: String,
    private_key: String,
    relay_db_path: String,
) -> std::sync::Arc<P2PCoreHandle> {
    let mut config = EngineConfig::new(display_name).with_relay_db(relay_db_path);
    if !public_key.is_empty() && !private_key.is_empty() {
        config = config.with_keys(public_key, private_key);
    }
    std::sync::Arc::new(P2PCoreHandle {
        inner: std::sync::Mutex::new(P2PCore::new(config)),
    })
}

/// M8-C slice 3: установить at-rest ключ из Android Keystore моста.
/// Вызывается ДО start(); 32 байта unwrap-нутого master secret, иначе
/// CryptoError и прежняя установка сохраняется. Материал на диск не пишется.
pub fn install_relay_at_rest_key(
    key_id: u16,
    key_material: Vec<u8>,
) -> Result<(), CoreError> {
    storage::relay_at_rest::install_device_key_source(key_id, &key_material).map_err(|e| {
        CoreError::CryptoError {
            detail: e.to_string(),
        }
    })
}

/// R0.5/S3: install a real Ed25519 signing sidecar before engine start.
/// Invalid material never replaces a previously installed identity.
pub fn install_identity_signing_seed(
    format_version: u8,
    legacy_routing_node_id: String,
    mut seed: Vec<u8>,
) -> Result<(), CoreError> {
    let result = crypto::signing_identity::install_signing_identity(
        format_version,
        legacy_routing_node_id,
        &seed,
    )
    .map(|_| ())
    .map_err(|error| CoreError::CryptoError {
        detail: error.to_string(),
    });
    seed.fill(0);
    result
}

pub fn clear_identity_signing_seed() {
    crypto::signing_identity::clear_signing_identity();
}

pub fn identity_signing_mode() -> String {
    crypto::signing_identity::signing_identity_mode().to_string()
}

pub fn identity_signing_public_key_hex() -> String {
    crypto::signing_identity::installed_signing_identity()
        .map(|identity| {
            identity
                .public_key()
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect()
        })
        .unwrap_or_default()
}

pub fn identity_signing_key_id() -> String {
    crypto::signing_identity::installed_signing_identity()
        .map(|identity| identity.key_id().to_string())
        .unwrap_or_default()
}

pub fn create_identity_signing_binding(created_at_ms: i64) -> Result<Vec<u8>, CoreError> {
    crypto::signing_identity::create_installed_identity_binding(created_at_ms).map_err(|error| {
        CoreError::CryptoError {
            detail: error.to_string(),
        }
    })
}

pub fn verify_identity_signing_binding(binding: Vec<u8>) -> bool {
    crypto::signing_identity::verify_identity_binding(&binding)
}

pub fn identity_signing_binding_matches_installed(binding: Vec<u8>) -> bool {
    crypto::signing_identity::identity_binding_matches_installed(&binding)
}

pub fn create_referral_invite_token(
    identity_binding: Vec<u8>,
    created_at_ms: i64,
    expires_at_ms: i64,
) -> Result<Vec<u8>, CoreError> {
    crypto::signing_identity::create_installed_referral_token(
        &identity_binding,
        created_at_ms,
        expires_at_ms,
    )
    .map_err(|error| CoreError::CryptoError {
        detail: error.to_string(),
    })
}

pub fn verify_referral_invite_token(token: Vec<u8>, now_ms: i64) -> bool {
    crypto::signing_identity::verify_identity_bound_referral_token(&token, now_ms)
}

pub fn verified_referral_inviter_node_id(
    token: Vec<u8>,
    now_ms: i64,
) -> Result<String, CoreError> {
    crypto::signing_identity::verified_referral_inviter_node_id(&token, now_ms).map_err(|error| {
        CoreError::CryptoError {
            detail: error.to_string(),
        }
    })
}

/// M8-C slice 3: убрать at-rest ключ (будущий logout/wipe; действует на
/// следующий запуск движка — работающий движок держит свой снимок).
pub fn clear_relay_at_rest_key() {
    storage::relay_at_rest::clear_device_key_source();
}

/// M8-C slice 3: key_id установленного ключа или -1 (диагностика/acceptance;
/// материал не раскрывается).
pub fn relay_at_rest_key_id() -> i64 {
    storage::relay_at_rest::installed_key_id()
        .map(|id| id as i64)
        .unwrap_or(-1)
}
// ============================================================
// Conversion helpers
// ============================================================

fn message_to_ffi(m: Message) -> MessageFfi {
    MessageFfi {
        id: m.id,
        chat_id: m.chat_id,
        sender_id: m.sender_id,
        text: m.content.as_text().unwrap_or("").to_string(),
        status: m.status.as_str().to_string(),
        created_at: m.created_at,
        is_deleted: m.is_deleted,
    }
}

fn chat_to_ffi(c: Chat) -> ChatFfi {
    ChatFfi {
        id: c.id,
        kind: c.kind.as_str().to_string(),
        title: c.title,
        last_message_at: c.last_message_at,
        unread_count: c.unread_count,
    }
}

fn event_to_ffi(e: CoreEvent) -> CoreEventFfi {
    let mut ffi = CoreEventFfi {
        event_type: e.event_type().to_string(),
        node_id: None,
        peer_id: None,
        display_name: None,
        message_id: None,
        chat_id: None,
        sender_id: None,
        text: None,
        status: None,
        timestamp: None,
        is_local: None,
    };

    match e {
        CoreEvent::EngineStarted { node_id } => {
            ffi.node_id = Some(node_id);
        }
        CoreEvent::EngineStopped => {}
        CoreEvent::NetworkStatusChanged { status } => {
            ffi.status = Some(status);
        }
        CoreEvent::PeerDiscovered {
            peer_id,
            display_name,
            is_local,
        } => {
            ffi.peer_id = Some(peer_id);
            ffi.display_name = Some(display_name);
            ffi.is_local = Some(is_local);
        }
        CoreEvent::PeerLost { peer_id } => {
            ffi.peer_id = Some(peer_id);
        }
        CoreEvent::MessageReceived {
            message_id,
            chat_id,
            sender_id,
            text,
            timestamp,
        } => {
            ffi.message_id = Some(message_id);
            ffi.chat_id = Some(chat_id);
            ffi.sender_id = Some(sender_id);
            ffi.text = Some(text);
            ffi.timestamp = Some(timestamp);
        }
        CoreEvent::MessageStatusChanged { message_id, status } => {
            ffi.message_id = Some(message_id);
            ffi.status = Some(status);
        }
        CoreEvent::MessageDelivered { message_id } => {
            ffi.message_id = Some(message_id);
        }
        CoreEvent::Error { code, message } => {
            ffi.status = Some(code);
            ffi.text = Some(message);
        }
        CoreEvent::KeysGenerated { public_key } => {
            ffi.node_id = Some(public_key);
        }
    }

    ffi
}

// ============================================================
// Error type
// ============================================================

#[derive(Debug, thiserror::Error)]
pub enum CoreError {
    #[error("РћС€РёР±РєР° РёРЅРёС†РёР°Р»РёР·Р°С†РёРё: {detail}")]
    InitError { detail: String },
    #[error("РћС€РёР±РєР° СЃРµС‚Рё: {detail}")]
    NetworkError { detail: String },
    #[error("РћС€РёР±РєР° РєСЂРёРїС‚РѕРіСЂР°С„РёРё: {detail}")]
    CryptoError { detail: String },
    #[error("РћС€РёР±РєР° Р±Р°Р·С‹ РґР°РЅРЅС‹С…: {detail}")]
    DatabaseError { detail: String },
    #[error("РќРµРёР·РІРµСЃС‚РЅР°СЏ РѕС€РёР±РєР°: {detail}")]
    Unknown { detail: String },
}

// ============================================================
// Tests
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_get_version() {
        assert!(get_version().contains("0.1.0"));
    }

    #[test]
    fn test_get_protocol_version() {
        assert_eq!(get_protocol_version(), 1);
    }

    #[test]
    fn test_initialize_core() {
        assert!(initialize_core().is_ok());
    }

    #[test]
    fn test_create_engine() {
        let handle = create_engine("TestUser".into());
        assert!(!handle.is_running());
    }

    #[test]
    fn test_engine_start_stop() {
        let handle = create_engine("TestUser".into());
        assert!(handle.start());
        assert!(handle.is_running());
        handle.stop();
        assert!(!handle.is_running());
    }

    #[test]
    fn test_engine_node_id() {
        let handle = create_engine("Alice".into());
        handle.start();
        assert!(handle.node_id().is_some());
    }

    #[test]
    fn test_engine_create_chat_and_queue_offline() {
        let handle = create_engine("Bob".into());
        handle.start();
        handle.create_chat("c1".into());
        let sent_directly =
            handle.send_message("m1".into(), "c1".into(), "peer1".into(), "Hello".into());
        assert!(!sent_directly);
        let events = handle.drain_events();
        assert!(events.iter().any(|event| {
            event.event_type == "message_status_changed"
                && event.message_id.as_deref() == Some("m1")
                && event.status.as_deref() == Some("queued_offline")
        }));
    }

    #[test]
    fn test_engine_drain_events() {
        let handle = create_engine("Eve".into());
        handle.start();
        let events = handle.drain_events();
        assert!(!events.is_empty());
        let types: Vec<&str> = events.iter().map(|e| e.event_type.as_str()).collect();
        assert!(types.contains(&"engine_started"));
    }
}
