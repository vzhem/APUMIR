//! # Node Identity — Криптографическая идентичность узла
//!
//! При первом запуске мессенджера генерируем полный набор ключей.
//! При последующих — загружаем из хранилища.
//!
//! ## Состав идентичности:
//!
//! - **Ed25519 KeyPair** — для подписи (доказательство "это я")
//!   - Из публичного ключа получается `NodeID`
//! - **X25519 Identity Key** — для X3DH рукопожатия (долгосрочный)
//! - **X25519 Signed PreKey** — для X3DH (среднесрочный, ротируется)
//!
//! ## NodeID:
//!
//! `NodeID = SHA-256(Ed25519 public key)`
//!
//! Это уникальный идентификатор узла в сети.
//! Неподдельный: злоумышленник не может выдать себя за узел
//! без знания его приватного Ed25519 ключа.

use crate::crypto::handshake::{PublicKeyBundle, ResponderKeys};
use crate::crypto::keys::{Ed25519KeyPair, NodeId, X25519KeyPair};
use crate::crypto::{CryptoError, CryptoResult};

// ═══════════════════════════════════════════════════════════════════
// СЕРИАЛИЗАЦИЯ ИДЕНТИЧНОСТИ
// ═══════════════════════════════════════════════════════════════════

/// Сериализованная идентичность для сохранения на диск.
///
/// Все три приватных ключа в одной структуре.
/// При загрузке шифруется паролем через KeyStore (следующий файл).
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct SerializedIdentity {
    /// Приватный Ed25519 (32 байта) — для подписи
    pub ed25519_secret: Vec<u8>,
    /// Приватный X25519 Identity Key (32 байта)
    pub x25519_identity_secret: Vec<u8>,
    /// Приватный X25519 Signed PreKey (32 байта)
    pub x25519_signed_prekey_secret: Vec<u8>,
    /// Версия формата (для будущих миграций)
    pub version: u8,
}

/// Публичная часть идентичности — отдаётся другим узлам.
///
/// Содержит всё что нужно другим узлам чтобы:
/// - Проверить нашу подпись (ed25519_pubkey)
/// - Инициировать X3DH с нами (x25519 bundle)
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct PublicIdentity {
    pub node_id: [u8; 32],
    pub ed25519_pubkey: Vec<u8>,
    pub x25519_bundle: PublicKeyBundleSerde,
}

/// Serde-совместимая версия PublicKeyBundle.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct PublicKeyBundleSerde {
    pub identity_key: Vec<u8>,
    pub signed_prekey: Vec<u8>,
}

impl From<PublicKeyBundle> for PublicKeyBundleSerde {
    fn from(b: PublicKeyBundle) -> Self {
        PublicKeyBundleSerde {
            identity_key: b.identity_key,
            signed_prekey: b.signed_prekey,
        }
    }
}

impl From<PublicKeyBundleSerde> for PublicKeyBundle {
    fn from(b: PublicKeyBundleSerde) -> Self {
        PublicKeyBundle {
            identity_key: b.identity_key,
            signed_prekey: b.signed_prekey,
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// NODE IDENTITY (основная структура)
// ═══════════════════════════════════════════════════════════════════

/// Полная криптографическая идентичность узла.
///
/// Содержит ВСЕ ключи (приватные и публичные).
/// В памяти живёт только пока приложение открыто.
/// На диск сохраняется через SerializedIdentity + KeyStore.
pub struct NodeIdentity {
    /// Ed25519 пара для подписи
    ed25519: Ed25519KeyPair,
    /// X25519 Identity Key
    x25519_identity: X25519KeyPair,
    /// X25519 Signed PreKey
    x25519_signed_prekey: X25519KeyPair,
}

impl NodeIdentity {
    /// Сгенерировать полностью новую идентичность (при первом запуске).
    pub fn generate() -> Self {
        NodeIdentity {
            ed25519: Ed25519KeyPair::generate(),
            x25519_identity: X25519KeyPair::generate(),
            x25519_signed_prekey: X25519KeyPair::generate(),
        }
    }

    /// Восстановить идентичность из сериализованного вида.
    pub fn from_serialized(data: &SerializedIdentity) -> CryptoResult<Self> {
        if data.version != 1 {
            return Err(CryptoError::Serialization(format!(
                "Неподдерживаемая версия идентичности: {}",
                data.version
            )));
        }

        Ok(NodeIdentity {
            ed25519: Ed25519KeyPair::from_secret_bytes(&data.ed25519_secret)?,
            x25519_identity: X25519KeyPair::from_secret_bytes(&data.x25519_identity_secret)?,
            x25519_signed_prekey: X25519KeyPair::from_secret_bytes(
                &data.x25519_signed_prekey_secret,
            )?,
        })
    }

    /// Сериализовать для сохранения на диск.
    pub fn to_serialized(&self) -> SerializedIdentity {
        SerializedIdentity {
            ed25519_secret: self.ed25519.secret_key().0,
            x25519_identity_secret: self.x25519_identity.secret_key().0,
            x25519_signed_prekey_secret: self.x25519_signed_prekey.secret_key().0,
            version: 1,
        }
    }

    /// Уникальный NodeID = SHA-256(Ed25519 public key).
    pub fn node_id(&self) -> NodeId {
        self.ed25519.node_id()
    }

    /// Публичный Ed25519 ключ (для верификации подписей).
    pub fn ed25519_public_key(&self) -> Vec<u8> {
        self.ed25519.public_key().0
    }

    /// Подписать данные приватным Ed25519 ключом.
    pub fn sign(&self, data: &[u8]) -> Vec<u8> {
        self.ed25519.sign(data)
    }

    /// Получить ResponderKeys для работы с SessionManager.
    /// Клонирует X25519 ключи (нужны для рукопожатия).
    pub fn responder_keys(&self) -> CryptoResult<ResponderKeys> {
        Ok(ResponderKeys::new(
            X25519KeyPair::from_secret_bytes(&self.x25519_identity.secret_key().0)?,
            X25519KeyPair::from_secret_bytes(&self.x25519_signed_prekey.secret_key().0)?,
        ))
    }

    /// Публичная идентичность для передачи другим узлам.
    /// Именно это кодируется в QR-код при обмене контактами.
    pub fn public_identity(&self) -> PublicIdentity {
        let bundle = PublicKeyBundle {
            identity_key: self.x25519_identity.public_key().0,
            signed_prekey: self.x25519_signed_prekey.public_key().0,
        };

        PublicIdentity {
            node_id: self.node_id().0,
            ed25519_pubkey: self.ed25519_public_key(),
            x25519_bundle: bundle.into(),
        }
    }

    /// Ротация Signed PreKey (нужна периодически для безопасности).
    /// Идентичность (Ed25519 + X25519 IK) остаётся прежней.
    pub fn rotate_signed_prekey(&mut self) {
        self.x25519_signed_prekey = X25519KeyPair::generate();
    }
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::keys::Ed25519KeyPair;

    #[test]
    fn test_generate_identity() {
        let identity = NodeIdentity::generate();

        // NodeID имеет правильную длину
        assert_eq!(identity.node_id().0.len(), 32);

        // Все ключи имеют правильные длины
        assert_eq!(identity.ed25519_public_key().len(), 32);

        println!("✅ Идентичность сгенерирована");
        println!("   NodeID: {}", identity.node_id().to_hex());
    }

    #[test]
    fn test_node_id_matches_ed25519_pubkey() {
        let identity = NodeIdentity::generate();
        let expected = NodeId::from_ed25519_pubkey(&identity.ed25519_public_key());
        assert_eq!(identity.node_id(), expected);
        println!("✅ NodeID = SHA-256(Ed25519 pubkey)");
    }

    #[test]
    fn test_serialize_and_restore_identity() {
        let original = NodeIdentity::generate();
        let original_node_id = original.node_id();
        let original_pubkey = original.ed25519_public_key();

        // Сериализация → десериализация
        let serialized = original.to_serialized();
        let restored = NodeIdentity::from_serialized(&serialized).unwrap();

        // NodeID и публичный ключ совпадают
        assert_eq!(restored.node_id(), original_node_id);
        assert_eq!(restored.ed25519_public_key(), original_pubkey);

        // Подписи с одинаковым сообщением дают одинаковый результат
        // (Ed25519 детерминирован)
        let msg = b"test message";
        let sig1 = original.sign(msg);
        let sig2 = restored.sign(msg);
        assert_eq!(sig1, sig2);

        println!("✅ Сериализация ↔ восстановление работает");
    }

    #[test]
    fn test_sign_and_verify_own_signature() {
        let identity = NodeIdentity::generate();
        let msg = b"Important announcement";

        let signature = identity.sign(msg);

        // Проверяем свою же подпись через публичный ключ
        let result = Ed25519KeyPair::verify(&identity.ed25519_public_key(), msg, &signature);
        assert!(result.is_ok());
        println!("✅ Идентичность может подписывать и проверять");
    }

    #[test]
    fn test_public_identity_export() {
        let identity = NodeIdentity::generate();
        let public = identity.public_identity();

        // NodeID в public identity соответствует
        assert_eq!(public.node_id.to_vec(), identity.node_id().0.to_vec());
        // Публичные ключи корректной длины
        assert_eq!(public.ed25519_pubkey.len(), 32);
        assert_eq!(public.x25519_bundle.identity_key.len(), 32);
        assert_eq!(public.x25519_bundle.signed_prekey.len(), 32);
        println!("✅ Публичная идентичность экспортируется корректно");
    }

    #[test]
    fn test_public_identity_serialization() {
        let identity = NodeIdentity::generate();
        let public = identity.public_identity();

        // bincode сериализация (для QR-кода / передачи по сети)
        let bytes = bincode::serialize(&public).unwrap();
        let restored: PublicIdentity = bincode::deserialize(&bytes).unwrap();

        assert_eq!(restored.node_id, public.node_id);
        assert_eq!(restored.ed25519_pubkey, public.ed25519_pubkey);
        println!(
            "✅ PublicIdentity сериализуется через bincode ({} байт)",
            bytes.len()
        );
    }

    #[test]
    fn test_responder_keys_for_session_manager() {
        let identity = NodeIdentity::generate();
        let responder_keys = identity.responder_keys().unwrap();

        // Bundle из responder_keys должен совпадать с public_identity
        let bundle = responder_keys.public_bundle();
        let public = identity.public_identity();

        assert_eq!(bundle.identity_key, public.x25519_bundle.identity_key);
        assert_eq!(bundle.signed_prekey, public.x25519_bundle.signed_prekey);
        println!("✅ ResponderKeys консистентны с PublicIdentity");
    }

    #[test]
    fn test_rotate_signed_prekey() {
        let mut identity = NodeIdentity::generate();
        let old_public = identity.public_identity();
        let old_node_id = identity.node_id();

        identity.rotate_signed_prekey();

        let new_public = identity.public_identity();

        // NodeID и Ed25519 остались прежними
        assert_eq!(identity.node_id(), old_node_id);
        assert_eq!(new_public.ed25519_pubkey, old_public.ed25519_pubkey);
        assert_eq!(
            new_public.x25519_bundle.identity_key,
            old_public.x25519_bundle.identity_key
        );

        // А Signed PreKey изменился
        assert_ne!(
            new_public.x25519_bundle.signed_prekey,
            old_public.x25519_bundle.signed_prekey
        );
        println!("✅ Ротация Signed PreKey сохраняет идентичность узла");
    }

    #[test]
    fn test_two_identities_have_different_node_ids() {
        let id1 = NodeIdentity::generate();
        let id2 = NodeIdentity::generate();
        assert_ne!(id1.node_id(), id2.node_id());
        println!("✅ Разные идентичности → разные NodeID");
    }

    #[test]
    fn test_unsupported_version_rejected() {
        let mut serialized = NodeIdentity::generate().to_serialized();
        serialized.version = 99; // Неизвестная версия

        let result = NodeIdentity::from_serialized(&serialized);
        assert!(result.is_err());
        println!("✅ Неподдерживаемая версия отклоняется");
    }

    #[test]
    fn test_full_scenario_two_nodes_communicate() {
        // Alice и Bob создают идентичности
        let alice_identity = NodeIdentity::generate();
        let bob_identity = NodeIdentity::generate();

        // Каждый создаёт SessionManager из своей идентичности
        use crate::crypto::session::SessionManager;

        let mut alice_sm = SessionManager::new(alice_identity.responder_keys().unwrap());
        let mut bob_sm = SessionManager::new(bob_identity.responder_keys().unwrap());

        // Обмениваются PublicIdentity (через QR-код в реальности)
        let alice_public = alice_identity.public_identity();
        let bob_public = bob_identity.public_identity();

        // Alice инициирует сессию с Bob
        let handshake = alice_sm
            .establish_as_initiator(NodeId(bob_public.node_id), &bob_public.x25519_bundle.into())
            .unwrap();

        // Bob получает handshake и создаёт свою сторону
        bob_sm
            .establish_as_responder(NodeId(alice_public.node_id), &handshake)
            .unwrap();

        // Обмен сообщениями
        let enc = alice_sm
            .encrypt_message(&NodeId(bob_public.node_id), b"Real-world scenario message!")
            .unwrap();

        let dec = bob_sm
            .decrypt_message(&NodeId(alice_public.node_id), &enc)
            .unwrap();

        assert_eq!(dec, b"Real-world scenario message!");
        println!("✅ ПОЛНЫЙ СЦЕНАРИЙ: две идентичности → SessionManager → E2E-сообщение");
    }
}
