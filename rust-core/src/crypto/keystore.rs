//! # KeyStore — Безопасное хранение идентичности
//!
//! Сохраняет `SerializedIdentity` в зашифрованный файл на диск.
//! Ключ шифрования выводится из пароля пользователя через HKDF.
//!
//! ## Формат зашифрованного файла:
//!
//! ```text
//! ┌────────────┬────────────┬───────────────────────────────┐
//! │  Magic (4) │  Salt (16) │  EncryptedMessage (nonce+ct)  │
//! └────────────┴────────────┴───────────────────────────────┘
//! ```
//!
//! - **Magic** — сигнатура "P2PK" для распознавания формата
//! - **Salt** — случайная соль для деривации ключа из пароля
//! - **EncryptedMessage** — зашифрованный bincode(SerializedIdentity)
//!
//! ## Безопасность (MVP):
//!
//! - Пароль → HKDF-SHA256(password, salt) → SymmetricKey
//! - Шифрование: ChaCha20-Poly1305 (уже проверенное в cipher.rs)
//! - Целостность: Poly1305 tag автоматически
//!
//! ## Что улучшим в Фазе 1.7 (Android интеграция):
//!
//! - Заменим HKDF на Argon2id (защита от brute-force)
//! - Ключ шифрования будет храниться в Android Keystore (TEE/StrongBox)
//! - Разблокировка через биометрию

use rand::rngs::OsRng;
use rand::RngCore;

use crate::crypto::cipher::{Cipher, EncryptedMessage, SymmetricKey};
use crate::crypto::identity::{NodeIdentity, SerializedIdentity};
use crate::crypto::kdf::derive_key;
use crate::crypto::CryptoError;

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// Магический префикс для распознавания формата файла.
const MAGIC: &[u8; 4] = b"P2PK";

/// Длина соли (в байтах).
const SALT_SIZE: usize = 16;

/// Info-контекст для деривации ключа шифрования из пароля.
const KEY_DERIVATION_INFO: &[u8] = b"p2p-messenger-keystore-v1";

// ═══════════════════════════════════════════════════════════════════
// ТИПЫ ОШИБОК
// ═══════════════════════════════════════════════════════════════════

#[derive(Debug, thiserror::Error)]
pub enum KeyStoreError {
    #[error("Ошибка криптографии: {0}")]
    Crypto(#[from] CryptoError),

    #[error("Ошибка сериализации: {0}")]
    Serialization(String),

    #[error("Неверный формат хранилища (не файл KeyStore)")]
    InvalidFormat,

    #[error("Файл слишком короткий для валидного KeyStore")]
    TruncatedData,

    #[error("Неверный пароль или файл повреждён")]
    WrongPasswordOrCorrupted,
}

pub type KeyStoreResult<T> = Result<T, KeyStoreError>;

// ═══════════════════════════════════════════════════════════════════
// KEYSTORE (шифрование/дешифрование идентичности паролем)
// ═══════════════════════════════════════════════════════════════════

/// Основная структура — методы для сохранения/загрузки идентичности.
pub struct KeyStore;

impl KeyStore {
    /// Зашифровать идентичность паролем.
    ///
    /// Возвращает байты которые можно записать в файл.
    ///
    /// # Формат вывода:
    /// `[Magic 4 байта][Salt 16 байт][EncryptedMessage variable]`
    pub fn encrypt_identity(identity: &NodeIdentity, password: &str) -> KeyStoreResult<Vec<u8>> {
        // 1. Сериализуем идентичность
        let serialized = identity.to_serialized();
        let plaintext = bincode::serialize(&serialized)
            .map_err(|e| KeyStoreError::Serialization(e.to_string()))?;

        // 2. Генерируем случайную соль
        let mut salt = [0u8; SALT_SIZE];
        OsRng.fill_bytes(&mut salt);

        // 3. Выводим ключ шифрования из пароля
        let key_bytes = derive_key(password.as_bytes(), &salt, KEY_DERIVATION_INFO, 32)?;
        let symmetric_key = SymmetricKey::from_bytes(&key_bytes)
            .map_err(|e| KeyStoreError::Serialization(e.to_string()))?;

        // 4. Шифруем данные
        let cipher = Cipher::new(&symmetric_key);
        let encrypted = cipher
            .encrypt(&plaintext)
            .map_err(|e| KeyStoreError::Serialization(e.to_string()))?;

        // 5. Собираем итоговый буфер: MAGIC + SALT + EncryptedMessage
        let mut output = Vec::with_capacity(4 + SALT_SIZE + encrypted.to_bytes().len());
        output.extend_from_slice(MAGIC);
        output.extend_from_slice(&salt);
        output.extend_from_slice(&encrypted.to_bytes());

        Ok(output)
    }

    /// Расшифровать идентичность паролем.
    ///
    /// # Аргументы
    /// - `data` — байты из файла (в формате из `encrypt_identity`)
    /// - `password` — пароль пользователя
    ///
    /// # Возможные ошибки
    /// - `InvalidFormat` — не тот формат файла (нет магического префикса)
    /// - `TruncatedData` — файл слишком короткий
    /// - `WrongPasswordOrCorrupted` — неверный пароль или подделка
    pub fn decrypt_identity(data: &[u8], password: &str) -> KeyStoreResult<NodeIdentity> {
        // 1. Проверка минимального размера
        // MAGIC(4) + SALT(16) + NONCE(12) + TAG(16) = 48
        if data.len() < 4 + SALT_SIZE + 12 + 16 {
            return Err(KeyStoreError::TruncatedData);
        }

        // 2. Проверка магического префикса
        if &data[..4] != MAGIC {
            return Err(KeyStoreError::InvalidFormat);
        }

        // 3. Извлекаем соль
        let salt = &data[4..4 + SALT_SIZE];

        // 4. Восстанавливаем ключ из пароля + соли
        let key_bytes = derive_key(password.as_bytes(), salt, KEY_DERIVATION_INFO, 32)?;
        let symmetric_key = SymmetricKey::from_bytes(&key_bytes)
            .map_err(|e| KeyStoreError::Serialization(e.to_string()))?;

        // 5. Восстанавливаем EncryptedMessage
        let encrypted_bytes = &data[4 + SALT_SIZE..];
        let encrypted = EncryptedMessage::from_bytes(encrypted_bytes)
            .map_err(|_| KeyStoreError::WrongPasswordOrCorrupted)?;

        // 6. Дешифруем — Poly1305 проверит целостность
        let cipher = Cipher::new(&symmetric_key);
        let plaintext = cipher
            .decrypt(&encrypted)
            .map_err(|_| KeyStoreError::WrongPasswordOrCorrupted)?;

        // 7. Десериализуем SerializedIdentity
        let serialized: SerializedIdentity = bincode::deserialize(&plaintext)
            .map_err(|e| KeyStoreError::Serialization(e.to_string()))?;

        // 8. Восстанавливаем NodeIdentity
        NodeIdentity::from_serialized(&serialized).map_err(KeyStoreError::Crypto)
    }
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encrypt_and_decrypt_identity() {
        let original = NodeIdentity::generate();
        let password = "SuperSecretPassword123!";

        let encrypted = KeyStore::encrypt_identity(&original, password).unwrap();
        let restored = KeyStore::decrypt_identity(&encrypted, password).unwrap();

        assert_eq!(restored.node_id(), original.node_id());
        assert_eq!(restored.ed25519_public_key(), original.ed25519_public_key());
        println!("✅ Идентичность зашифрована и восстановлена");
        println!("   Размер файла: {} байт", encrypted.len());
    }

    #[test]
    fn test_wrong_password_rejected() {
        let identity = NodeIdentity::generate();
        let encrypted = KeyStore::encrypt_identity(&identity, "correct_password").unwrap();

        let result = KeyStore::decrypt_identity(&encrypted, "wrong_password");
        assert!(matches!(
            result,
            Err(KeyStoreError::WrongPasswordOrCorrupted)
        ));
        println!("✅ Неверный пароль → ошибка");
    }

    #[test]
    fn test_tampered_data_rejected() {
        let identity = NodeIdentity::generate();
        let password = "password";
        let mut encrypted = KeyStore::encrypt_identity(&identity, password).unwrap();

        // Портим один байт в ciphertext (после MAGIC + SALT)
        let byte_index = 4 + SALT_SIZE + 15;
        encrypted[byte_index] ^= 0xFF;

        let result = KeyStore::decrypt_identity(&encrypted, password);
        assert!(matches!(
            result,
            Err(KeyStoreError::WrongPasswordOrCorrupted)
        ));
        println!("✅ Подделка данных обнаружена Poly1305");
    }

    #[test]
    fn test_invalid_magic_rejected() {
        let bad_data = vec![0u8; 100]; // Все нули — нет магического префикса
        let result = KeyStore::decrypt_identity(&bad_data, "password");
        assert!(matches!(result, Err(KeyStoreError::InvalidFormat)));
        println!("✅ Файл без магического префикса отклоняется");
    }

    #[test]
    fn test_truncated_file_rejected() {
        let short_data = vec![0u8; 10]; // Слишком короткий
        let result = KeyStore::decrypt_identity(&short_data, "password");
        assert!(matches!(result, Err(KeyStoreError::TruncatedData)));
        println!("✅ Слишком короткий файл отклоняется");
    }

    #[test]
    fn test_same_identity_different_ciphertexts() {
        // Одна и та же идентичность → разные зашифрованные файлы
        // (потому что соль и nonce случайные)
        let identity = NodeIdentity::generate();
        let password = "pw";

        let e1 = KeyStore::encrypt_identity(&identity, password).unwrap();
        let e2 = KeyStore::encrypt_identity(&identity, password).unwrap();

        assert_ne!(e1, e2);

        // Но оба дешифруются в ту же идентичность
        let r1 = KeyStore::decrypt_identity(&e1, password).unwrap();
        let r2 = KeyStore::decrypt_identity(&e2, password).unwrap();
        assert_eq!(r1.node_id(), r2.node_id());

        println!("✅ Салт+nonce обеспечивают уникальность ciphertext");
    }

    #[test]
    fn test_empty_password() {
        // Пустой пароль — тоже валиден (для тестирования)
        let identity = NodeIdentity::generate();

        let encrypted = KeyStore::encrypt_identity(&identity, "").unwrap();
        let restored = KeyStore::decrypt_identity(&encrypted, "").unwrap();

        assert_eq!(restored.node_id(), identity.node_id());
        println!("✅ Пустой пароль работает");
    }

    #[test]
    fn test_unicode_password() {
        let identity = NodeIdentity::generate();
        let password = "Пароль123!🔒安全";

        let encrypted = KeyStore::encrypt_identity(&identity, password).unwrap();
        let restored = KeyStore::decrypt_identity(&encrypted, password).unwrap();

        assert_eq!(restored.node_id(), identity.node_id());
        println!("✅ Unicode-пароль работает");
    }

    #[test]
    fn test_long_password() {
        let identity = NodeIdentity::generate();
        let password: String = "a".repeat(1000);

        let encrypted = KeyStore::encrypt_identity(&identity, &password).unwrap();
        let restored = KeyStore::decrypt_identity(&encrypted, &password).unwrap();

        assert_eq!(restored.node_id(), identity.node_id());
        println!("✅ Очень длинный пароль (1000 символов) работает");
    }

    #[test]
    fn test_signatures_after_restore() {
        // После восстановления из зашифрованного хранилища подписи работают
        let original = NodeIdentity::generate();
        let password = "pw";

        let encrypted = KeyStore::encrypt_identity(&original, password).unwrap();
        let restored = KeyStore::decrypt_identity(&encrypted, password).unwrap();

        // Подписываем разные сообщения
        let msg1 = b"message 1";
        let msg2 = b"message 2";

        let sig1_orig = original.sign(msg1);
        let sig1_rest = restored.sign(msg1);
        let sig2_rest = restored.sign(msg2);

        // Одинаковый msg → одинаковая подпись (Ed25519 детерминирован)
        assert_eq!(sig1_orig, sig1_rest);
        // Разные msg → разные подписи
        assert_ne!(sig1_rest, sig2_rest);
        println!("✅ Подписи работают после восстановления");
    }

    #[test]
    fn test_magic_prefix_present() {
        let identity = NodeIdentity::generate();
        let encrypted = KeyStore::encrypt_identity(&identity, "pw").unwrap();

        // Первые 4 байта должны быть "P2PK"
        assert_eq!(&encrypted[..4], MAGIC);
        assert_eq!(&encrypted[..4], b"P2PK");
        println!("✅ Магический префикс присутствует");
    }
}
