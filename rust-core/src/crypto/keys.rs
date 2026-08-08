//! # Управление криптографическими ключами
//!
//! Генерация и работа с ключами двух типов:
//!
//! - **Ed25519** — асимметричная подпись
//!   Используется для: подписи сообщений, аутентификации узлов
//!
//! - **X25519** — Diffie-Hellman обмен ключами
//!   Используется для: установки общего секрета между узлами
//!
//! Оба алгоритма работают на эллиптической кривой Curve25519.

use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use rand::rngs::OsRng;
use rand::RngCore;
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret};

use super::{CryptoError, CryptoResult};

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// Длина Ed25519 публичного ключа в байтах.
pub const ED25519_PUBLIC_KEY_SIZE: usize = 32;

/// Длина Ed25519 приватного ключа в байтах.
pub const ED25519_SECRET_KEY_SIZE: usize = 32;

/// Длина X25519 публичного ключа в байтах.
pub const X25519_PUBLIC_KEY_SIZE: usize = 32;

/// Длина X25519 приватного ключа в байтах.
pub const X25519_SECRET_KEY_SIZE: usize = 32;

/// Длина Ed25519 подписи в байтах.
pub const SIGNATURE_SIZE: usize = 64;

/// Длина NodeID в байтах (SHA-256 хеш).
pub const NODE_ID_SIZE: usize = 32;

// ═══════════════════════════════════════════════════════════════════
// БАЗОВЫЕ ТИПЫ (обёртки над байтами для типобезопасности)
// ═══════════════════════════════════════════════════════════════════

/// Публичный ключ — можно передавать по сети.
/// Обёртка над байтовым массивом для явности типов.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct PublicKey(pub Vec<u8>);

/// Приватный ключ — НИКОГДА не передаётся по сети.
/// Хранится только на устройстве владельца.
#[derive(Debug, Clone)]
pub struct SecretKey(pub Vec<u8>);

impl PublicKey {
    /// Преобразование в hex-строку для отображения / QR-кода.
    pub fn to_hex(&self) -> String {
        self.0.iter().map(|b| format!("{:02x}", b)).collect()
    }

    /// Байтовое представление.
    pub fn as_bytes(&self) -> &[u8] {
        &self.0
    }
}

// ═══════════════════════════════════════════════════════════════════
// NODE ID — уникальный идентификатор узла в сети
// ═══════════════════════════════════════════════════════════════════

/// Уникальный идентификатор узла = SHA-256(Ed25519 public key).
///
/// - Всегда 32 байта
/// - Криптографически связан с публичным ключом
/// - Невозможно подделать без обладания приватным ключом
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct NodeId(pub [u8; NODE_ID_SIZE]);

impl NodeId {
    /// Создать NodeID из публичного ключа Ed25519.
    pub fn from_ed25519_pubkey(pubkey: &[u8]) -> Self {
        let mut hasher = Sha256::new();
        hasher.update(pubkey);
        let hash = hasher.finalize();

        let mut id = [0u8; NODE_ID_SIZE];
        id.copy_from_slice(&hash);
        NodeId(id)
    }

    /// Hex-строка для отображения (полная, 64 символа).
    pub fn to_hex(&self) -> String {
        self.0.iter().map(|b| format!("{:02x}", b)).collect()
    }

    /// Короткая версия для UI (первые 8 символов).
    /// Пример: "a3f2c891"
    pub fn short(&self) -> String {
        self.to_hex().chars().take(8).collect()
    }

    /// Байтовое представление.
    pub fn as_bytes(&self) -> &[u8; NODE_ID_SIZE] {
        &self.0
    }
}

// ═══════════════════════════════════════════════════════════════════
// ED25519 — ЦИФРОВАЯ ПОДПИСЬ
// ═══════════════════════════════════════════════════════════════════

/// Пара ключей Ed25519 для подписи и верификации.
///
/// - Приватный ключ: используется для СОЗДАНИЯ подписи
/// - Публичный ключ: используется для ПРОВЕРКИ подписи
pub struct Ed25519KeyPair {
    signing_key: SigningKey,
}

impl Ed25519KeyPair {
    /// Сгенерировать новую пару ключей.
    /// Использует OsRng — криптографически безопасный генератор.
    pub fn generate() -> Self {
        let mut csprng = OsRng;
        let mut secret_bytes = [0u8; ED25519_SECRET_KEY_SIZE];
        csprng.fill_bytes(&mut secret_bytes);

        let signing_key = SigningKey::from_bytes(&secret_bytes);
        Ed25519KeyPair { signing_key }
    }

    /// Восстановить пару ключей из приватного ключа (для загрузки из хранилища).
    pub fn from_secret_bytes(bytes: &[u8]) -> CryptoResult<Self> {
        if bytes.len() != ED25519_SECRET_KEY_SIZE {
            return Err(CryptoError::InvalidKeyLength {
                expected: ED25519_SECRET_KEY_SIZE,
                actual: bytes.len(),
            });
        }
        let mut secret_bytes = [0u8; ED25519_SECRET_KEY_SIZE];
        secret_bytes.copy_from_slice(bytes);

        let signing_key = SigningKey::from_bytes(&secret_bytes);
        Ok(Ed25519KeyPair { signing_key })
    }

    /// Публичный ключ (можно передавать по сети).
    pub fn public_key(&self) -> PublicKey {
        let verifying_key: VerifyingKey = self.signing_key.verifying_key();
        PublicKey(verifying_key.to_bytes().to_vec())
    }

    /// Приватный ключ (НИКОГДА не передавать!).
    pub fn secret_key(&self) -> SecretKey {
        SecretKey(self.signing_key.to_bytes().to_vec())
    }

    /// NodeID = SHA-256(public_key). Уникальный идентификатор узла.
    pub fn node_id(&self) -> NodeId {
        NodeId::from_ed25519_pubkey(self.public_key().as_bytes())
    }

    /// Подписать сообщение приватным ключом.
    /// Возвращает 64 байта подписи.
    pub fn sign(&self, message: &[u8]) -> Vec<u8> {
        let signature: Signature = self.signing_key.sign(message);
        signature.to_bytes().to_vec()
    }

    /// Проверить подпись публичным ключом.
    /// Используется при получении сообщения от другого узла.
    pub fn verify(public_key: &[u8], message: &[u8], signature: &[u8]) -> CryptoResult<()> {
        // Проверяем длину публичного ключа
        if public_key.len() != ED25519_PUBLIC_KEY_SIZE {
            return Err(CryptoError::InvalidKeyLength {
                expected: ED25519_PUBLIC_KEY_SIZE,
                actual: public_key.len(),
            });
        }

        // Проверяем длину подписи
        if signature.len() != SIGNATURE_SIZE {
            return Err(CryptoError::InvalidKeyLength {
                expected: SIGNATURE_SIZE,
                actual: signature.len(),
            });
        }

        // Восстанавливаем публичный ключ
        let mut pubkey_bytes = [0u8; ED25519_PUBLIC_KEY_SIZE];
        pubkey_bytes.copy_from_slice(public_key);
        let verifying_key = VerifyingKey::from_bytes(&pubkey_bytes)
            .map_err(|e| CryptoError::Serialization(e.to_string()))?;

        // Восстанавливаем подпись
        let mut sig_bytes = [0u8; SIGNATURE_SIZE];
        sig_bytes.copy_from_slice(signature);
        let sig = Signature::from_bytes(&sig_bytes);

        // Проверяем подпись
        verifying_key
            .verify(message, &sig)
            .map_err(|_| CryptoError::InvalidSignature)
    }
}

// ═══════════════════════════════════════════════════════════════════
// X25519 — DIFFIE-HELLMAN ОБМЕН КЛЮЧАМИ
// ═══════════════════════════════════════════════════════════════════

/// Пара ключей X25519 для установления общего секрета.
///
/// Алгоритм Diffie-Hellman:
/// 1. Алиса и Боб обмениваются публичными ключами
/// 2. Каждый вычисляет: shared_secret = MY_secret × THEIR_public
/// 3. Секрет получается ОДИНАКОВЫЙ у обоих (магия ECC!)
/// 4. Наблюдатель, видя только публичные ключи, НЕ может вычислить секрет
pub struct X25519KeyPair {
    secret: StaticSecret,
    public: X25519PublicKey,
}

impl X25519KeyPair {
    /// Сгенерировать новую пару ключей.
    pub fn generate() -> Self {
        let mut csprng = OsRng;
        let mut secret_bytes = [0u8; X25519_SECRET_KEY_SIZE];
        csprng.fill_bytes(&mut secret_bytes);

        let secret = StaticSecret::from(secret_bytes);
        let public = X25519PublicKey::from(&secret);

        X25519KeyPair { secret, public }
    }

    /// Восстановить пару ключей из приватного ключа.
    pub fn from_secret_bytes(bytes: &[u8]) -> CryptoResult<Self> {
        if bytes.len() != X25519_SECRET_KEY_SIZE {
            return Err(CryptoError::InvalidKeyLength {
                expected: X25519_SECRET_KEY_SIZE,
                actual: bytes.len(),
            });
        }
        let mut secret_bytes = [0u8; X25519_SECRET_KEY_SIZE];
        secret_bytes.copy_from_slice(bytes);

        let secret = StaticSecret::from(secret_bytes);
        let public = X25519PublicKey::from(&secret);

        Ok(X25519KeyPair { secret, public })
    }

    /// Публичный ключ (можно передавать по сети).
    pub fn public_key(&self) -> PublicKey {
        PublicKey(self.public.as_bytes().to_vec())
    }

    /// Приватный ключ (НИКОГДА не передавать!).
    pub fn secret_key(&self) -> SecretKey {
        SecretKey(self.secret.to_bytes().to_vec())
    }

    /// Вычислить общий секрет с публичным ключом другой стороны.
    ///
    /// Пример:
    /// ```ignore
    /// let alice = X25519KeyPair::generate();
    /// let bob = X25519KeyPair::generate();
    ///
    /// let alice_secret = alice.diffie_hellman(bob.public_key().as_bytes()).unwrap();
    /// let bob_secret = bob.diffie_hellman(alice.public_key().as_bytes()).unwrap();
    ///
    /// assert_eq!(alice_secret, bob_secret); // Общий секрет одинаковый!
    /// ```
    pub fn diffie_hellman(&self, their_public: &[u8]) -> CryptoResult<Vec<u8>> {
        if their_public.len() != X25519_PUBLIC_KEY_SIZE {
            return Err(CryptoError::InvalidKeyLength {
                expected: X25519_PUBLIC_KEY_SIZE,
                actual: their_public.len(),
            });
        }

        let mut pubkey_bytes = [0u8; X25519_PUBLIC_KEY_SIZE];
        pubkey_bytes.copy_from_slice(their_public);
        let their_pubkey = X25519PublicKey::from(pubkey_bytes);

        let shared = self.secret.diffie_hellman(&their_pubkey);
        Ok(shared.as_bytes().to_vec())
    }
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;

    // ── Ed25519 тесты ────────────────────────────────────────────────

    #[test]
    fn test_ed25519_generate_key_pair() {
        let keypair = Ed25519KeyPair::generate();
        assert_eq!(keypair.public_key().0.len(), ED25519_PUBLIC_KEY_SIZE);
        assert_eq!(keypair.secret_key().0.len(), ED25519_SECRET_KEY_SIZE);
        println!("✅ Ed25519: пара ключей сгенерирована");
        println!("   PublicKey: {}", keypair.public_key().to_hex());
    }

    #[test]
    fn test_ed25519_sign_and_verify() {
        let keypair = Ed25519KeyPair::generate();
        let message = b"Hello, P2P world!";

        let signature = keypair.sign(message);
        assert_eq!(signature.len(), SIGNATURE_SIZE);

        // Правильная подпись должна пройти проверку
        let result = Ed25519KeyPair::verify(keypair.public_key().as_bytes(), message, &signature);
        assert!(result.is_ok());
        println!("✅ Ed25519: подпись создана и проверена");
    }

    #[test]
    fn test_ed25519_verify_fails_on_wrong_message() {
        let keypair = Ed25519KeyPair::generate();
        let message = b"Original message";
        let signature = keypair.sign(message);

        // Изменённое сообщение должно НЕ пройти проверку
        let tampered_message = b"Tampered message";
        let result = Ed25519KeyPair::verify(
            keypair.public_key().as_bytes(),
            tampered_message,
            &signature,
        );
        assert!(result.is_err());
        println!("✅ Ed25519: изменённое сообщение отвергнуто");
    }

    #[test]
    fn test_ed25519_restore_from_secret() {
        let original = Ed25519KeyPair::generate();
        let secret_bytes = original.secret_key().0;

        let restored = Ed25519KeyPair::from_secret_bytes(&secret_bytes).unwrap();

        // Восстановленный ключ должен иметь тот же публичный ключ
        assert_eq!(original.public_key(), restored.public_key());
        println!("✅ Ed25519: восстановление из приватного ключа работает");
    }

    // ── NodeID тесты ─────────────────────────────────────────────────

    #[test]
    fn test_node_id_generation() {
        let keypair = Ed25519KeyPair::generate();
        let node_id = keypair.node_id();

        assert_eq!(node_id.0.len(), NODE_ID_SIZE);
        assert_eq!(node_id.to_hex().len(), 64); // 32 байта × 2 hex-символа
        assert_eq!(node_id.short().len(), 8);
        println!("✅ NodeID: {}", node_id.to_hex());
        println!("   Короткий: {}", node_id.short());
    }

    #[test]
    fn test_node_id_deterministic() {
        // Один и тот же публичный ключ → один и тот же NodeID
        let keypair = Ed25519KeyPair::generate();
        let id1 = keypair.node_id();
        let id2 = NodeId::from_ed25519_pubkey(keypair.public_key().as_bytes());
        assert_eq!(id1, id2);
        println!("✅ NodeID детерминирован");
    }

    #[test]
    fn test_node_id_unique() {
        // Разные ключи → разные NodeID
        let kp1 = Ed25519KeyPair::generate();
        let kp2 = Ed25519KeyPair::generate();
        assert_ne!(kp1.node_id(), kp2.node_id());
        println!("✅ NodeID уникален для разных ключей");
    }

    // ── X25519 тесты ─────────────────────────────────────────────────

    #[test]
    fn test_x25519_generate_key_pair() {
        let keypair = X25519KeyPair::generate();
        assert_eq!(keypair.public_key().0.len(), X25519_PUBLIC_KEY_SIZE);
        assert_eq!(keypair.secret_key().0.len(), X25519_SECRET_KEY_SIZE);
        println!("✅ X25519: пара ключей сгенерирована");
    }

    #[test]
    fn test_x25519_diffie_hellman() {
        // Алиса и Боб генерируют свои ключи
        let alice = X25519KeyPair::generate();
        let bob = X25519KeyPair::generate();

        // Каждый вычисляет общий секрет из своего приватного + чужого публичного
        let alice_shared = alice.diffie_hellman(bob.public_key().as_bytes()).unwrap();
        let bob_shared = bob.diffie_hellman(alice.public_key().as_bytes()).unwrap();

        // ГЛАВНОЕ СВОЙСТВО DH: секрет одинаковый у обоих!
        assert_eq!(alice_shared, bob_shared);
        assert_eq!(alice_shared.len(), 32);
        println!("✅ X25519 Diffie-Hellman: общий секрет установлен");
        println!(
            "   Shared secret: {}...",
            alice_shared
                .iter()
                .take(8)
                .map(|b| format!("{:02x}", b))
                .collect::<String>()
        );
    }

    #[test]
    fn test_x25519_restore_from_secret() {
        let original = X25519KeyPair::generate();
        let secret_bytes = original.secret_key().0;

        let restored = X25519KeyPair::from_secret_bytes(&secret_bytes).unwrap();

        // Публичные ключи должны совпасть
        assert_eq!(original.public_key(), restored.public_key());
        println!("✅ X25519: восстановление из приватного ключа работает");
    }

    #[test]
    fn test_invalid_key_length_returns_error() {
        // Слишком короткий ключ
        let bad_bytes = vec![0u8; 16];

        let result = Ed25519KeyPair::from_secret_bytes(&bad_bytes);
        assert!(matches!(result, Err(CryptoError::InvalidKeyLength { .. })));

        let result = X25519KeyPair::from_secret_bytes(&bad_bytes);
        assert!(matches!(result, Err(CryptoError::InvalidKeyLength { .. })));

        println!("✅ Неверная длина ключа корректно обрабатывается");
    }
}
