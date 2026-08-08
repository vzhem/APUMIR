//! # Модуль криптографии
//!
//! Полный криптографический стек P2P-мессенджера.
//!
//! ## Компоненты (Фаза 1.2 — ЗАВЕРШЕНО):
//!
//! - `keys`      — Генерация Ed25519/X25519 ключей, NodeID
//! - `cipher`    — Симметричное шифрование ChaCha20-Poly1305
//! - `kdf`       — Key Derivation (HKDF)
//! - `handshake` — Протокол X3DH рукопожатия (упрощённый)
//! - `ratchet`   — Double Ratchet для Perfect Forward Secrecy
//! - `session`   — Менеджер активных сессий с контактами
//! - `identity`  — Идентичность узла (все ключи вместе)
//! - `keystore`  — Шифрование идентичности паролем
//!
//! ## Двухуровневая модель шифрования:
//!
//! ```text
//! ┌─────────────────────────────────────────────────────────┐
//! │              СООБЩЕНИЕ ПОЛЬЗОВАТЕЛЯ                     │
//! ├─────────────────────────────────────────────────────────┤
//! │ Уровень 2 (E2E): Double Ratchet + ChaCha20-Poly1305    │  ← этот модуль
//! ├─────────────────────────────────────────────────────────┤
//! │ Уровень 1 (Transport): QUIC / TLS 1.3 (quinn+rustls)   │
//! └─────────────────────────────────────────────────────────┘
//! ```
//!
//! ## Типичный жизненный цикл:
//!
//! ```text
//! 1. Первый запуск:
//!    identity = NodeIdentity::generate()
//!    KeyStore::encrypt_identity(&identity, "user_password") → в файл
//!
//! 2. Последующие запуски:
//!    identity = KeyStore::decrypt_identity(file_bytes, "user_password")
//!    session_manager = SessionManager::new(identity.responder_keys())
//!
//! 3. Новый контакт:
//!    Получаем PublicIdentity контакта (через QR-код)
//!    session_manager.establish_as_initiator(contact_id, &bundle)
//!    → отправляем HandshakeMessage
//!
//! 4. Обмен сообщениями:
//!    encrypted = session_manager.encrypt_message(&contact_id, "text")
//!    text = session_manager.decrypt_message(&contact_id, &encrypted)
//! ```

pub mod cipher;
pub mod handshake;
pub mod identity;
pub mod kdf;
pub mod keys;
pub mod keystore;
pub mod ratchet;
pub mod session;

// ═══════════════════════════════════════════════════════════════════
// РЕЭКСПОРТЫ (единая точка доступа ко всей криптографии)
// ═══════════════════════════════════════════════════════════════════

// Ключи и подпись
pub use keys::{Ed25519KeyPair, NodeId, PublicKey, SecretKey, X25519KeyPair};

// Симметричное шифрование
pub use cipher::{Cipher, CipherError, EncryptedMessage, Nonce, SymmetricKey};

// Деривация ключей
pub use kdf::{derive_key, ratchet_kdf};

// Рукопожатие X3DH
pub use handshake::{
    initiate_handshake, respond_to_handshake, HandshakeMessage, InitiatorKeys, PublicKeyBundle,
    ResponderKeys,
};

// Double Ratchet
pub use ratchet::{RatchetEncryptedMessage, RatchetState};

// Сессии
pub use session::SessionManager;

// Идентичность
pub use identity::{NodeIdentity, PublicIdentity, PublicKeyBundleSerde, SerializedIdentity};

// Хранилище ключей
pub use keystore::{KeyStore, KeyStoreError, KeyStoreResult};

// ═══════════════════════════════════════════════════════════════════
// ОБЩИЕ ТИПЫ
// ═══════════════════════════════════════════════════════════════════

/// Общий результат криптографических операций.
pub type CryptoResult<T> = Result<T, CryptoError>;

/// Единый тип ошибки для всего модуля криптографии.
#[derive(Debug, thiserror::Error)]
pub enum CryptoError {
    #[error("Ошибка генерации ключей: {0}")]
    KeyGeneration(String),

    #[error("Неверная длина ключа: ожидалось {expected}, получено {actual}")]
    InvalidKeyLength { expected: usize, actual: usize },

    #[error("Ошибка шифрования: {0}")]
    Encryption(String),

    #[error("Ошибка дешифрования: {0}")]
    Decryption(String),

    #[error("Неверная подпись")]
    InvalidSignature,

    #[error("Ошибка сериализации ключа: {0}")]
    Serialization(String),
}
