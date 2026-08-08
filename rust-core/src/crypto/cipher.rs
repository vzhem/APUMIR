//! # Симметричное AEAD-шифрование ChaCha20-Poly1305
//!
//! ChaCha20-Poly1305 — современный алгоритм аутентифицированного шифрования:
//!
//! - **ChaCha20** — быстрый потоковый шифр (быстрее AES на мобильных)
//! - **Poly1305** — MAC (Message Authentication Code) — проверка целостности
//! - **AEAD** — Authenticated Encryption with Associated Data
//!
//! ## Как работает:
//!
//! 1. Отправитель шифрует данные ключом + случайным nonce
//! 2. Получатель дешифрует тем же ключом + тем же nonce
//! 3. Poly1305 автоматически проверяет что данные не изменены
//! 4. Если данные подделаны — дешифрование ВЕРНЁТ ОШИБКУ
//!
//! ## Важно про nonce:
//!
//! - Nonce ВСЕГДА должен быть уникальным для одного ключа!
//! - Повторное использование nonce с тем же ключом = катастрофа безопасности
//! - Мы генерируем nonce случайно (12 байт → 2^96 вариантов = безопасно)

use chacha20poly1305::Nonce as ChachaNonce;
use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    ChaCha20Poly1305, Key,
};
use rand::rngs::OsRng;
use rand::RngCore;

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// Длина симметричного ключа в байтах (256 бит).
pub const SYMMETRIC_KEY_SIZE: usize = 32;

/// Длина nonce в байтах (96 бит).
pub const NONCE_SIZE: usize = 12;

/// Длина тега аутентификации Poly1305 в байтах.
pub const TAG_SIZE: usize = 16;

// ═══════════════════════════════════════════════════════════════════
// ТИПЫ ОШИБОК
// ═══════════════════════════════════════════════════════════════════

/// Ошибки шифрования / дешифрования.
#[derive(Debug, thiserror::Error)]
pub enum CipherError {
    #[error("Неверная длина ключа: ожидалось {expected}, получено {actual}")]
    InvalidKeyLength { expected: usize, actual: usize },

    #[error("Неверная длина nonce: ожидалось {expected}, получено {actual}")]
    InvalidNonceLength { expected: usize, actual: usize },

    #[error("Ошибка шифрования")]
    EncryptionFailed,

    #[error("Ошибка дешифрования (возможно данные подделаны или ключ неверный)")]
    DecryptionFailed,
}

pub type CipherResult<T> = Result<T, CipherError>;

// ═══════════════════════════════════════════════════════════════════
// БАЗОВЫЕ ТИПЫ
// ═══════════════════════════════════════════════════════════════════

/// Симметричный ключ шифрования (32 байта).
/// Используется для шифрования И дешифрования.
#[derive(Clone)]
pub struct SymmetricKey(pub [u8; SYMMETRIC_KEY_SIZE]);

impl SymmetricKey {
    /// Сгенерировать случайный ключ (криптографически безопасно).
    pub fn generate() -> Self {
        let mut key = [0u8; SYMMETRIC_KEY_SIZE];
        OsRng.fill_bytes(&mut key);
        SymmetricKey(key)
    }

    /// Создать ключ из байтов (для загрузки из хранилища).
    pub fn from_bytes(bytes: &[u8]) -> CipherResult<Self> {
        if bytes.len() != SYMMETRIC_KEY_SIZE {
            return Err(CipherError::InvalidKeyLength {
                expected: SYMMETRIC_KEY_SIZE,
                actual: bytes.len(),
            });
        }
        let mut key = [0u8; SYMMETRIC_KEY_SIZE];
        key.copy_from_slice(bytes);
        Ok(SymmetricKey(key))
    }

    pub fn as_bytes(&self) -> &[u8; SYMMETRIC_KEY_SIZE] {
        &self.0
    }
}

// Автоматически стирает ключ из памяти при уничтожении объекта.
// Защита от атак типа "cold boot" — извлечение ключа из RAM.
impl Drop for SymmetricKey {
    fn drop(&mut self) {
        // Перезаписываем нулями (компилятор не оптимизирует volatile write)
        for byte in self.0.iter_mut() {
            unsafe { std::ptr::write_volatile(byte, 0) };
        }
    }
}

/// Одноразовое число (Number used ONCE).
/// КРИТИЧНО: никогда не использовать одинаковый nonce с одним ключом!
#[derive(Clone, Debug)]
pub struct Nonce(pub [u8; NONCE_SIZE]);

impl Nonce {
    /// Сгенерировать случайный nonce.
    /// 96 бит = 2^96 вариантов — вероятность коллизии пренебрежимо мала.
    pub fn generate() -> Self {
        let mut nonce = [0u8; NONCE_SIZE];
        OsRng.fill_bytes(&mut nonce);
        Nonce(nonce)
    }

    /// Создать nonce из байтов (для дешифрования полученного сообщения).
    pub fn from_bytes(bytes: &[u8]) -> CipherResult<Self> {
        if bytes.len() != NONCE_SIZE {
            return Err(CipherError::InvalidNonceLength {
                expected: NONCE_SIZE,
                actual: bytes.len(),
            });
        }
        let mut nonce = [0u8; NONCE_SIZE];
        nonce.copy_from_slice(bytes);
        Ok(Nonce(nonce))
    }

    pub fn as_bytes(&self) -> &[u8; NONCE_SIZE] {
        &self.0
    }
}

/// Зашифрованное сообщение — то что передаётся по сети.
#[derive(Debug, Clone)]
pub struct EncryptedMessage {
    /// Nonce используется при дешифровании (передаётся вместе с ciphertext).
    pub nonce: Nonce,
    /// Зашифрованные данные + Poly1305 тег в конце.
    pub ciphertext: Vec<u8>,
}

impl EncryptedMessage {
    /// Сериализация в один байтовый массив: [nonce (12 байт)][ciphertext].
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut result = Vec::with_capacity(NONCE_SIZE + self.ciphertext.len());
        result.extend_from_slice(self.nonce.as_bytes());
        result.extend_from_slice(&self.ciphertext);
        result
    }

    /// Разобрать байты обратно в EncryptedMessage.
    pub fn from_bytes(bytes: &[u8]) -> CipherResult<Self> {
        if bytes.len() < NONCE_SIZE + TAG_SIZE {
            return Err(CipherError::DecryptionFailed);
        }
        let nonce = Nonce::from_bytes(&bytes[..NONCE_SIZE])?;
        let ciphertext = bytes[NONCE_SIZE..].to_vec();
        Ok(EncryptedMessage { nonce, ciphertext })
    }
}

// ═══════════════════════════════════════════════════════════════════
// ШИФР
// ═══════════════════════════════════════════════════════════════════

/// Основная структура для шифрования/дешифрования.
pub struct Cipher {
    inner: ChaCha20Poly1305,
}

impl Cipher {
    /// Создать шифр с заданным ключом.
    pub fn new(key: &SymmetricKey) -> Self {
        let cipher_key = Key::from_slice(key.as_bytes());
        let inner = ChaCha20Poly1305::new(cipher_key);
        Cipher { inner }
    }

    /// Зашифровать сообщение.
    ///
    /// - Генерирует случайный nonce
    /// - Шифрует plaintext
    /// - Добавляет Poly1305 тег для проверки целостности
    ///
    /// # Пример
    /// ```ignore
    /// let key = SymmetricKey::generate();
    /// let cipher = Cipher::new(&key);
    /// let encrypted = cipher.encrypt(b"Секретное сообщение").unwrap();
    /// ```
    pub fn encrypt(&self, plaintext: &[u8]) -> CipherResult<EncryptedMessage> {
        let nonce = Nonce::generate();
        let chacha_nonce = ChachaNonce::from_slice(nonce.as_bytes());

        let ciphertext = self
            .inner
            .encrypt(chacha_nonce, plaintext)
            .map_err(|_| CipherError::EncryptionFailed)?;

        Ok(EncryptedMessage { nonce, ciphertext })
    }

    /// Зашифровать с дополнительными аутентифицируемыми данными (AAD).
    ///
    /// AAD не шифруется, но защищается от изменений.
    /// Используется для метаданных: sender_id, timestamp и т.д.
    pub fn encrypt_with_aad(&self, plaintext: &[u8], aad: &[u8]) -> CipherResult<EncryptedMessage> {
        let nonce = Nonce::generate();
        let chacha_nonce = ChachaNonce::from_slice(nonce.as_bytes());

        let payload = Payload {
            msg: plaintext,
            aad,
        };

        let ciphertext = self
            .inner
            .encrypt(chacha_nonce, payload)
            .map_err(|_| CipherError::EncryptionFailed)?;

        Ok(EncryptedMessage { nonce, ciphertext })
    }

    /// Дешифровать сообщение.
    ///
    /// - Проверяет целостность через Poly1305 тег
    /// - Если данные подделаны или ключ неверный → возвращает ошибку
    pub fn decrypt(&self, encrypted: &EncryptedMessage) -> CipherResult<Vec<u8>> {
        let chacha_nonce = ChachaNonce::from_slice(encrypted.nonce.as_bytes());

        self.inner
            .decrypt(chacha_nonce, encrypted.ciphertext.as_ref())
            .map_err(|_| CipherError::DecryptionFailed)
    }

    /// Дешифровать с проверкой AAD.
    pub fn decrypt_with_aad(
        &self,
        encrypted: &EncryptedMessage,
        aad: &[u8],
    ) -> CipherResult<Vec<u8>> {
        let chacha_nonce = ChachaNonce::from_slice(encrypted.nonce.as_bytes());

        let payload = Payload {
            msg: encrypted.ciphertext.as_ref(),
            aad,
        };

        self.inner
            .decrypt(chacha_nonce, payload)
            .map_err(|_| CipherError::DecryptionFailed)
    }
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_key_generation() {
        let key = SymmetricKey::generate();
        assert_eq!(key.as_bytes().len(), SYMMETRIC_KEY_SIZE);
        println!(
            "✅ Симметричный ключ сгенерирован ({} байт)",
            SYMMETRIC_KEY_SIZE
        );
    }

    #[test]
    fn test_nonce_generation() {
        let n1 = Nonce::generate();
        let n2 = Nonce::generate();
        assert_eq!(n1.as_bytes().len(), NONCE_SIZE);
        // Два случайных nonce почти наверняка разные
        assert_ne!(n1.as_bytes(), n2.as_bytes());
        println!("✅ Nonce уникален при каждой генерации");
    }

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let key = SymmetricKey::generate();
        let cipher = Cipher::new(&key);
        let plaintext = b"Secret P2P message";

        let encrypted = cipher.encrypt(plaintext).unwrap();
        let decrypted = cipher.decrypt(&encrypted).unwrap();

        assert_eq!(decrypted, plaintext);
        println!("✅ Шифрование/дешифрование работают корректно");
        println!("   Plaintext:  {} байт", plaintext.len());
        println!(
            "   Ciphertext: {} байт (+{} байт overhead)",
            encrypted.ciphertext.len(),
            encrypted.ciphertext.len() - plaintext.len()
        );
    }

    #[test]
    fn test_decrypt_with_wrong_key_fails() {
        let key1 = SymmetricKey::generate();
        let key2 = SymmetricKey::generate();
        let cipher1 = Cipher::new(&key1);
        let cipher2 = Cipher::new(&key2);

        let encrypted = cipher1.encrypt(b"secret").unwrap();
        let result = cipher2.decrypt(&encrypted);

        assert!(matches!(result, Err(CipherError::DecryptionFailed)));
        println!("✅ Дешифрование неверным ключом отклоняется");
    }

    #[test]
    fn test_decrypt_tampered_data_fails() {
        let key = SymmetricKey::generate();
        let cipher = Cipher::new(&key);

        let mut encrypted = cipher.encrypt(b"original").unwrap();
        // Портим один байт ciphertext
        encrypted.ciphertext[0] ^= 0xFF;

        let result = cipher.decrypt(&encrypted);
        assert!(matches!(result, Err(CipherError::DecryptionFailed)));
        println!("✅ Подделанные данные обнаружены Poly1305");
    }

    #[test]
    fn test_encrypt_with_aad() {
        let key = SymmetricKey::generate();
        let cipher = Cipher::new(&key);
        let plaintext = b"Message body";
        let aad = b"sender_id=alice&timestamp=12345";

        let encrypted = cipher.encrypt_with_aad(plaintext, aad).unwrap();
        let decrypted = cipher.decrypt_with_aad(&encrypted, aad).unwrap();

        assert_eq!(decrypted, plaintext);
        println!("✅ AEAD с AAD работает корректно");
    }

    #[test]
    fn test_aad_tampering_detected() {
        let key = SymmetricKey::generate();
        let cipher = Cipher::new(&key);
        let plaintext = b"Message";
        let aad = b"original_aad";
        let tampered_aad = b"tampered_aad";

        let encrypted = cipher.encrypt_with_aad(plaintext, aad).unwrap();
        // Пытаемся дешифровать с другим AAD
        let result = cipher.decrypt_with_aad(&encrypted, tampered_aad);

        assert!(matches!(result, Err(CipherError::DecryptionFailed)));
        println!("✅ Подделка AAD обнаружена");
    }

    #[test]
    fn test_encrypted_message_serialization() {
        let key = SymmetricKey::generate();
        let cipher = Cipher::new(&key);
        let plaintext = b"P2P message";

        let encrypted = cipher.encrypt(plaintext).unwrap();

        // Сериализация → байты → десериализация
        let bytes = encrypted.to_bytes();
        let restored = EncryptedMessage::from_bytes(&bytes).unwrap();

        // Дешифруем восстановленное сообщение
        let decrypted = cipher.decrypt(&restored).unwrap();
        assert_eq!(decrypted, plaintext);
        println!("✅ Сериализация EncryptedMessage работает");
        println!("   Total bytes: {}", bytes.len());
    }

    #[test]
    fn test_different_plaintexts_give_different_ciphertexts() {
        let key = SymmetricKey::generate();
        let cipher = Cipher::new(&key);

        let e1 = cipher.encrypt(b"message A").unwrap();
        let e2 = cipher.encrypt(b"message B").unwrap();

        assert_ne!(e1.ciphertext, e2.ciphertext);
        println!("✅ Разные сообщения → разные ciphertext");
    }

    #[test]
    fn test_same_plaintext_different_ciphertexts_due_to_nonce() {
        // Одинаковый plaintext + разный nonce = разный ciphertext
        // Это КРИТИЧНО для безопасности!
        let key = SymmetricKey::generate();
        let cipher = Cipher::new(&key);
        let plaintext = b"identical message";

        let e1 = cipher.encrypt(plaintext).unwrap();
        let e2 = cipher.encrypt(plaintext).unwrap();

        // Nonce разные
        assert_ne!(e1.nonce.as_bytes(), e2.nonce.as_bytes());
        // Ciphertext разные
        assert_ne!(e1.ciphertext, e2.ciphertext);

        // Но дешифруются в один и тот же plaintext
        assert_eq!(cipher.decrypt(&e1).unwrap(), plaintext);
        assert_eq!(cipher.decrypt(&e2).unwrap(), plaintext);

        println!("✅ Nonce обеспечивает вариативность ciphertext");
    }

    #[test]
    fn test_key_from_bytes_roundtrip() {
        let original = SymmetricKey::generate();
        let bytes = *original.as_bytes();
        let restored = SymmetricKey::from_bytes(&bytes).unwrap();
        assert_eq!(original.as_bytes(), restored.as_bytes());
        println!("✅ Восстановление ключа из байтов работает");
    }

    #[test]
    fn test_invalid_key_length_error() {
        let bad_bytes = vec![0u8; 16];
        let result = SymmetricKey::from_bytes(&bad_bytes);
        assert!(matches!(result, Err(CipherError::InvalidKeyLength { .. })));
        println!("✅ Неверная длина ключа обрабатывается корректно");
    }
}
