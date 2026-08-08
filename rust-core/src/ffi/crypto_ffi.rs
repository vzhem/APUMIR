//! Crypto FFI — публичный API криптографии для Kotlin
//! Генерация ключей, шифрование, подпись

use std::sync::Mutex;

// ============================================================
// Key Pair
// ============================================================

/// Пара ключей Ed25519 (публичный + приватный)
#[derive(Debug, Clone)]
pub struct KeyPair {
    /// Публичный ключ (hex)
    pub public_key: String,
    /// Приватный ключ (hex) — только для хранения
    pub private_key: String,
}

impl KeyPair {
    pub fn new(public_key: String, private_key: String) -> Self {
        Self {
            public_key,
            private_key,
        }
    }
}

// ============================================================
// Crypto Manager
// ============================================================

/// Менеджер криптографии — единственная точка входа из Kotlin
pub struct CryptoManager {
    key_pair: Mutex<Option<KeyPair>>,
    node_id: Mutex<Option<String>>,
}

impl CryptoManager {
    pub fn new() -> Self {
        Self {
            key_pair: Mutex::new(None),
            node_id: Mutex::new(None),
        }
    }

    /// Генерировать новую пару ключей
    /// Возвращает публичный ключ (hex)
    pub fn generate_keys(&self) -> String {
        // В реальности вызываем ed25519_dalek
        // Здесь симулируем генерацию для FFI слоя
        let public_key = format!("pk_{}", Self::random_hex(32));
        let private_key = format!("sk_{}", Self::random_hex(32));

        let node_id = public_key.clone();
        *self.key_pair.lock().unwrap() = Some(KeyPair::new(public_key.clone(), private_key));
        *self.node_id.lock().unwrap() = Some(node_id);

        public_key
    }

    /// Загрузить существующую пару ключей
    pub fn load_keys(&self, public_key: String, private_key: String) -> bool {
        if public_key.is_empty() || private_key.is_empty() {
            return false;
        }
        let node_id = public_key.clone();
        *self.key_pair.lock().unwrap() = Some(KeyPair::new(public_key, private_key));
        *self.node_id.lock().unwrap() = Some(node_id);
        true
    }

    /// Получить публичный ключ
    pub fn public_key(&self) -> Option<String> {
        self.key_pair
            .lock()
            .unwrap()
            .as_ref()
            .map(|kp| kp.public_key.clone())
    }

    /// Получить node_id (= публичный ключ)
    pub fn node_id(&self) -> Option<String> {
        self.node_id.lock().unwrap().clone()
    }

    /// Есть ли ключи?
    pub fn has_keys(&self) -> bool {
        self.key_pair.lock().unwrap().is_some()
    }

    /// Подписать данные
    /// Возвращает подпись (hex)
    pub fn sign(&self, data: &[u8]) -> Option<String> {
        if !self.has_keys() {
            return None;
        }
        // Симуляция подписи для FFI слоя
        let signature = format!("sig_{}", Self::hash_hex(data));
        Some(signature)
    }

    /// Проверить подпись
    pub fn verify(&self, public_key: &str, data: &[u8], signature: &str) -> bool {
        if public_key.is_empty() || signature.is_empty() {
            return false;
        }
        // Симуляция верификации
        let expected = format!("sig_{}", Self::hash_hex(data));
        signature == expected
    }

    /// Зашифровать сообщение для получателя
    /// Возвращает зашифрованные данные (hex)
    pub fn encrypt(&self, plaintext: &[u8], recipient_public_key: &str) -> Option<String> {
        if !self.has_keys() || recipient_public_key.is_empty() {
            return None;
        }
        // Симуляция шифрования
        let encrypted = format!(
            "enc_{}_{}",
            recipient_public_key.chars().take(8).collect::<String>(),
            Self::hash_hex(plaintext)
        );
        Some(encrypted)
    }

    /// Расшифровать сообщение
    pub fn decrypt(&self, ciphertext: &str, _sender_public_key: &str) -> Option<Vec<u8>> {
        if !self.has_keys() || ciphertext.is_empty() {
            return None;
        }
        // Симуляция расшифровки
        if ciphertext.starts_with("enc_") {
            Some(b"decrypted_data".to_vec())
        } else {
            None
        }
    }

    // --- Helpers ---

    fn random_hex(len: usize) -> String {
        use rand::RngCore;
        let mut rng = rand::rngs::OsRng;
        let byte_count = (len + 1) / 2;
        let mut bytes = vec![0u8; byte_count];
        rng.fill_bytes(&mut bytes);
        let hex: String = bytes.iter().map(|b| format!("{:02x}", b)).collect();
        hex[..len].to_string()
    }

    fn hash_hex(data: &[u8]) -> String {
        let mut h: u64 = 0xcbf29ce484222325;
        for b in data {
            h ^= *b as u64;
            h = h.wrapping_mul(0x100000001b3);
        }
        format!("{:016x}", h)
    }
}

impl Default for CryptoManager {
    fn default() -> Self {
        Self::new()
    }
}

// ============================================================
// TESTS
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn make_manager() -> CryptoManager {
        CryptoManager::new()
    }

    // --- KeyPair ---

    #[test]
    fn test_keypair_new() {
        let kp = KeyPair::new("pub".into(), "priv".into());
        assert_eq!(kp.public_key, "pub");
        assert_eq!(kp.private_key, "priv");
    }

    // --- CryptoManager ---

    #[test]
    fn test_initial_has_no_keys() {
        let m = make_manager();
        assert!(!m.has_keys());
        assert!(m.public_key().is_none());
        assert!(m.node_id().is_none());
    }

    #[test]
    fn test_generate_keys() {
        let m = make_manager();
        let pk = m.generate_keys();
        assert!(!pk.is_empty());
        assert!(m.has_keys());
    }

    #[test]
    fn test_generate_keys_returns_public_key() {
        let m = make_manager();
        let pk = m.generate_keys();
        assert_eq!(m.public_key(), Some(pk));
    }

    #[test]
    fn test_node_id_equals_public_key() {
        let m = make_manager();
        m.generate_keys();
        assert_eq!(m.node_id(), m.public_key());
    }

    #[test]
    fn test_load_keys_success() {
        let m = make_manager();
        let ok = m.load_keys("pk_abc".into(), "sk_xyz".into());
        assert!(ok);
        assert!(m.has_keys());
        assert_eq!(m.public_key(), Some("pk_abc".into()));
    }

    #[test]
    fn test_load_keys_empty_fails() {
        let m = make_manager();
        assert!(!m.load_keys("".into(), "sk".into()));
        assert!(!m.load_keys("pk".into(), "".into()));
        assert!(!m.has_keys());
    }

    #[test]
    fn test_sign_without_keys_returns_none() {
        let m = make_manager();
        assert!(m.sign(b"data").is_none());
    }

    #[test]
    fn test_sign_with_keys() {
        let m = make_manager();
        m.generate_keys();
        let sig = m.sign(b"hello");
        assert!(sig.is_some());
        assert!(sig.unwrap().starts_with("sig_"));
    }

    #[test]
    fn test_verify_valid_signature() {
        let m = make_manager();
        m.generate_keys();
        let pk = m.public_key().unwrap();
        let sig = m.sign(b"test data").unwrap();
        assert!(m.verify(&pk, b"test data", &sig));
    }

    #[test]
    fn test_verify_wrong_data() {
        let m = make_manager();
        m.generate_keys();
        let pk = m.public_key().unwrap();
        let sig = m.sign(b"original").unwrap();
        assert!(!m.verify(&pk, b"tampered", &sig));
    }

    #[test]
    fn test_verify_empty_key_fails() {
        let m = make_manager();
        assert!(!m.verify("", b"data", "sig"));
    }

    #[test]
    fn test_encrypt_without_keys() {
        let m = make_manager();
        assert!(m.encrypt(b"data", "recipient_pk").is_none());
    }

    #[test]
    fn test_encrypt_with_keys() {
        let m = make_manager();
        m.generate_keys();
        let ct = m.encrypt(b"secret message", "recipient_pk_123");
        assert!(ct.is_some());
        assert!(ct.unwrap().starts_with("enc_"));
    }

    #[test]
    fn test_decrypt_valid() {
        let m = make_manager();
        m.generate_keys();
        let ct = m.encrypt(b"hello", "pk_recipient").unwrap();
        let pt = m.decrypt(&ct, "pk_sender");
        assert!(pt.is_some());
    }

    #[test]
    fn test_decrypt_invalid_format() {
        let m = make_manager();
        m.generate_keys();
        let pt = m.decrypt("invalid_ciphertext", "pk");
        assert!(pt.is_none());
    }
}
