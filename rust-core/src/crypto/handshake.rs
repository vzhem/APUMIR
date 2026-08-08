//! # X3DH — Extended Triple Diffie-Hellman (упрощённый)
//!
//! Протокол первого рукопожатия между двумя узлами.
//! Позволяет установить общий секрет даже когда получатель офлайн.
//!
//! ## Полная схема X3DH (для справки):
//!
//! У каждого узла три типа X25519 ключей:
//! - **IK (Identity Key)** — долгосрочный ключ идентичности
//! - **SPK (Signed PreKey)** — среднесрочный ключ, подписан IK
//! - **OPK (One-Time PreKey)** — одноразовые ключи (пачка на сервере)
//!
//! Инициатор (Alice) выполняет 4 DH операции:
//! ```text
//! DH1 = DH(IK_A,  SPK_B)   ← долгосрочная связь идентичностей
//! DH2 = DH(EK_A,  IK_B)    ← EK — эфемерный ключ Alice
//! DH3 = DH(EK_A,  SPK_B)   ← основное shared secret
//! DH4 = DH(EK_A,  OPK_B)   ← одноразовость (если OPK доступен)
//!
//! SK = HKDF(DH1 || DH2 || DH3 || DH4)
//! ```
//!
//! ## Наша УПРОЩЁННАЯ версия для MVP:
//!
//! Без OPK и без подписи SPK. Используем 3 DH:
//! ```text
//! DH1 = DH(IK_A,  SPK_B)
//! DH2 = DH(EK_A,  IK_B)
//! DH3 = DH(EK_A,  SPK_B)
//!
//! SK = HKDF(DH1 || DH2 || DH3, info="x3dh-v1")
//! ```
//!
//! Полный X3DH с OPK и подписями — в Фазе 2.6 (техдолг).

use crate::crypto::kdf::derive_key;
use crate::crypto::keys::X25519KeyPair;
use crate::crypto::{CryptoError, CryptoResult};

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// Информационная строка HKDF для разделения доменов.
const X3DH_INFO: &[u8] = b"p2p-messenger-x3dh-v1";

/// Длина итогового сессионного ключа.
const SESSION_KEY_SIZE: usize = 32;

// ═══════════════════════════════════════════════════════════════════
// ПУБЛИЧНЫЕ КЛЮЧИ УЗЛА (передаются по сети)
// ═══════════════════════════════════════════════════════════════════

/// Набор публичных ключей узла для установки сессии.
///
/// Инициатор получает этот набор от получателя (например через QR-код
/// или через Presence Protocol) и использует для X3DH.
#[derive(Debug, Clone)]
pub struct PublicKeyBundle {
    /// Публичный Identity Key (долгосрочный)
    pub identity_key: Vec<u8>,
    /// Публичный Signed PreKey (среднесрочный)
    pub signed_prekey: Vec<u8>,
}

impl PublicKeyBundle {
    /// Проверить корректность длин ключей.
    pub fn validate(&self) -> CryptoResult<()> {
        if self.identity_key.len() != 32 {
            return Err(CryptoError::InvalidKeyLength {
                expected: 32,
                actual: self.identity_key.len(),
            });
        }
        if self.signed_prekey.len() != 32 {
            return Err(CryptoError::InvalidKeyLength {
                expected: 32,
                actual: self.signed_prekey.len(),
            });
        }
        Ok(())
    }
}

// ═══════════════════════════════════════════════════════════════════
// ПРИВАТНЫЕ КЛЮЧИ УЗЛА (хранятся локально)
// ═══════════════════════════════════════════════════════════════════

/// Набор приватных ключей для роли ОТПРАВИТЕЛЯ (Initiator).
///
/// Отправитель использует свой IK + одноразовый EK.
pub struct InitiatorKeys {
    /// Долгосрочный Identity Key
    pub identity_key: X25519KeyPair,
    /// Эфемерный ключ (генерируется для каждой сессии)
    pub ephemeral_key: X25519KeyPair,
}

impl InitiatorKeys {
    /// Создать набор для нового рукопожатия.
    /// IK передаётся снаружи (постоянный), EK генерируется свежий.
    pub fn new(identity_key: X25519KeyPair) -> Self {
        let ephemeral_key = X25519KeyPair::generate();
        InitiatorKeys {
            identity_key,
            ephemeral_key,
        }
    }
}

/// Набор приватных ключей для роли ПОЛУЧАТЕЛЯ (Responder).
///
/// Получатель использует свои IK + SPK.
pub struct ResponderKeys {
    /// Долгосрочный Identity Key
    pub identity_key: X25519KeyPair,
    /// Signed PreKey (среднесрочный)
    pub signed_prekey: X25519KeyPair,
}

impl ResponderKeys {
    /// Создать набор для получателя.
    pub fn new(identity_key: X25519KeyPair, signed_prekey: X25519KeyPair) -> Self {
        ResponderKeys {
            identity_key,
            signed_prekey,
        }
    }

    /// Сгенерировать полный набор для нового узла (при первом запуске).
    pub fn generate() -> Self {
        ResponderKeys {
            identity_key: X25519KeyPair::generate(),
            signed_prekey: X25519KeyPair::generate(),
        }
    }

    /// Получить публичные ключи для передачи другим узлам.
    pub fn public_bundle(&self) -> PublicKeyBundle {
        PublicKeyBundle {
            identity_key: self.identity_key.public_key().0,
            signed_prekey: self.signed_prekey.public_key().0,
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// СООБЩЕНИЕ РУКОПОЖАТИЯ
// ═══════════════════════════════════════════════════════════════════

/// Первое сообщение при инициации рукопожатия.
/// Отправляется от Initiator к Responder.
#[derive(Debug, Clone)]
pub struct HandshakeMessage {
    /// Публичный Identity Key отправителя
    pub initiator_identity_key: Vec<u8>,
    /// Публичный Ephemeral Key отправителя (свежий для этой сессии)
    pub initiator_ephemeral_key: Vec<u8>,
}

impl HandshakeMessage {
    /// Проверить корректность длин ключей.
    pub fn validate(&self) -> CryptoResult<()> {
        if self.initiator_identity_key.len() != 32 {
            return Err(CryptoError::InvalidKeyLength {
                expected: 32,
                actual: self.initiator_identity_key.len(),
            });
        }
        if self.initiator_ephemeral_key.len() != 32 {
            return Err(CryptoError::InvalidKeyLength {
                expected: 32,
                actual: self.initiator_ephemeral_key.len(),
            });
        }
        Ok(())
    }
}

// ═══════════════════════════════════════════════════════════════════
// ПРОТОКОЛ X3DH
// ═══════════════════════════════════════════════════════════════════

/// Инициатор: вычислить сессионный ключ и создать сообщение рукопожатия.
///
/// # Аргументы
/// - `initiator_keys` — свои приватные ключи (IK + EK)
/// - `responder_bundle` — публичные ключи получателя (IK + SPK)
///
/// # Возвращает
/// - `session_key` — общий секрет (32 байта)
/// - `handshake_message` — что отправить получателю
pub fn initiate_handshake(
    initiator_keys: &InitiatorKeys,
    responder_bundle: &PublicKeyBundle,
) -> CryptoResult<(Vec<u8>, HandshakeMessage)> {
    responder_bundle.validate()?;

    // ═══ Три DH операции ═══
    // DH1 = DH(IK_A, SPK_B) — связь идентичности A с prekey B
    let dh1 = initiator_keys
        .identity_key
        .diffie_hellman(&responder_bundle.signed_prekey)?;

    // DH2 = DH(EK_A, IK_B) — эфемерный ключ A с идентичностью B
    let dh2 = initiator_keys
        .ephemeral_key
        .diffie_hellman(&responder_bundle.identity_key)?;

    // DH3 = DH(EK_A, SPK_B) — эфемерный с prekey (главный секрет)
    let dh3 = initiator_keys
        .ephemeral_key
        .diffie_hellman(&responder_bundle.signed_prekey)?;

    // Конкатенация всех DH результатов
    let mut concat = Vec::with_capacity(dh1.len() + dh2.len() + dh3.len());
    concat.extend_from_slice(&dh1);
    concat.extend_from_slice(&dh2);
    concat.extend_from_slice(&dh3);

    // Финальный сессионный ключ через HKDF
    let session_key = derive_key(&concat, b"", X3DH_INFO, SESSION_KEY_SIZE)?;

    // Сообщение для получателя
    let message = HandshakeMessage {
        initiator_identity_key: initiator_keys.identity_key.public_key().0,
        initiator_ephemeral_key: initiator_keys.ephemeral_key.public_key().0,
    };

    Ok((session_key, message))
}

/// Получатель: вычислить сессионный ключ из полученного сообщения.
///
/// # Аргументы
/// - `responder_keys` — свои приватные ключи (IK + SPK)
/// - `message` — сообщение полученное от Initiator
///
/// # Возвращает
/// - `session_key` — общий секрет (тот же что вычислил Initiator!)
pub fn respond_to_handshake(
    responder_keys: &ResponderKeys,
    message: &HandshakeMessage,
) -> CryptoResult<Vec<u8>> {
    message.validate()?;

    // ═══ Три DH операции (симметрично Initiator) ═══
    // DH1 = DH(SPK_B, IK_A) — с точки зрения B
    let dh1 = responder_keys
        .signed_prekey
        .diffie_hellman(&message.initiator_identity_key)?;

    // DH2 = DH(IK_B, EK_A)
    let dh2 = responder_keys
        .identity_key
        .diffie_hellman(&message.initiator_ephemeral_key)?;

    // DH3 = DH(SPK_B, EK_A) — главный секрет
    let dh3 = responder_keys
        .signed_prekey
        .diffie_hellman(&message.initiator_ephemeral_key)?;

    // Точно та же конкатенация — тот же HKDF → тот же ключ
    let mut concat = Vec::with_capacity(dh1.len() + dh2.len() + dh3.len());
    concat.extend_from_slice(&dh1);
    concat.extend_from_slice(&dh2);
    concat.extend_from_slice(&dh3);

    let session_key = derive_key(&concat, b"", X3DH_INFO, SESSION_KEY_SIZE)?;

    Ok(session_key)
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::cipher::{Cipher, SymmetricKey};

    #[test]
    fn test_responder_generates_valid_bundle() {
        let responder = ResponderKeys::generate();
        let bundle = responder.public_bundle();

        assert!(bundle.validate().is_ok());
        assert_eq!(bundle.identity_key.len(), 32);
        assert_eq!(bundle.signed_prekey.len(), 32);
        println!("✅ Responder генерирует валидный набор ключей");
    }

    #[test]
    fn test_handshake_produces_matching_keys() {
        // ═══ Настройка ═══
        // Alice (initiator) и Bob (responder) генерируют свои ключи
        let alice_identity = X25519KeyPair::generate();
        let bob = ResponderKeys::generate();

        // Alice создаёт свой набор для инициации
        let alice_keys = InitiatorKeys::new(alice_identity);

        // Bob публикует свой bundle (например через QR-код)
        let bob_bundle = bob.public_bundle();

        // ═══ Alice выполняет X3DH ═══
        let (alice_session_key, handshake_msg) =
            initiate_handshake(&alice_keys, &bob_bundle).unwrap();

        // ═══ Bob получает сообщение и вычисляет свой сессионный ключ ═══
        let bob_session_key = respond_to_handshake(&bob, &handshake_msg).unwrap();

        // ═══ КЛЮЧЕВАЯ ПРОВЕРКА: ключи совпадают! ═══
        assert_eq!(alice_session_key, bob_session_key);
        assert_eq!(alice_session_key.len(), SESSION_KEY_SIZE);

        println!("✅ X3DH: Alice и Bob получили ОДИНАКОВЫЙ сессионный ключ");
        println!(
            "   Ключ (первые 8 байт): {}",
            alice_session_key
                .iter()
                .take(8)
                .map(|b| format!("{:02x}", b))
                .collect::<String>()
        );
    }

    #[test]
    fn test_handshake_and_encrypted_message() {
        // Полный сценарий: рукопожатие → шифрование → дешифрование

        // Ключи
        let alice_identity = X25519KeyPair::generate();
        let bob = ResponderKeys::generate();
        let alice_keys = InitiatorKeys::new(alice_identity);

        // Рукопожатие
        let (alice_session_key, msg) =
            initiate_handshake(&alice_keys, &bob.public_bundle()).unwrap();
        let bob_session_key = respond_to_handshake(&bob, &msg).unwrap();

        // Создаём симметричные ключи из session key
        let alice_sk = SymmetricKey::from_bytes(&alice_session_key).unwrap();
        let bob_sk = SymmetricKey::from_bytes(&bob_session_key).unwrap();

        // Alice шифрует сообщение
        let alice_cipher = Cipher::new(&alice_sk);
        let encrypted = alice_cipher.encrypt(b"Hello Bob!").unwrap();

        // Bob дешифрует
        let bob_cipher = Cipher::new(&bob_sk);
        let decrypted = bob_cipher.decrypt(&encrypted).unwrap();

        assert_eq!(decrypted, b"Hello Bob!");
        println!("✅ Полный цикл: X3DH → шифрование → дешифрование работает!");
    }

    #[test]
    fn test_different_handshakes_produce_different_keys() {
        let bob = ResponderKeys::generate();

        // Первая сессия
        let alice_1 = InitiatorKeys::new(X25519KeyPair::generate());
        let (key_1, _) = initiate_handshake(&alice_1, &bob.public_bundle()).unwrap();

        // Вторая сессия (другой Alice)
        let alice_2 = InitiatorKeys::new(X25519KeyPair::generate());
        let (key_2, _) = initiate_handshake(&alice_2, &bob.public_bundle()).unwrap();

        // Разные Alice → разные сессионные ключи
        assert_ne!(key_1, key_2);
        println!("✅ Разные инициаторы получают разные сессионные ключи");
    }

    #[test]
    fn test_ephemeral_key_freshness() {
        // Даже если тот же Alice начинает две сессии с тем же Bob,
        // Ephemeral Key каждый раз новый → сессионные ключи разные
        let alice_identity = X25519KeyPair::generate();
        let bob = ResponderKeys::generate();
        let bob_bundle = bob.public_bundle();

        // Alice начинает первую сессию
        let alice_secret_bytes = alice_identity.secret_key().0;
        let alice_1 =
            InitiatorKeys::new(X25519KeyPair::from_secret_bytes(&alice_secret_bytes).unwrap());
        let (key_1, _) = initiate_handshake(&alice_1, &bob_bundle).unwrap();

        // Alice начинает вторую сессию (тот же identity, новый EK)
        let alice_2 =
            InitiatorKeys::new(X25519KeyPair::from_secret_bytes(&alice_secret_bytes).unwrap());
        let (key_2, _) = initiate_handshake(&alice_2, &bob_bundle).unwrap();

        // Разные EK → разные сессионные ключи
        assert_ne!(key_1, key_2);
        println!("✅ Свежий Ephemeral Key даёт свежий сессионный ключ");
    }

    #[test]
    fn test_invalid_bundle_rejected() {
        let alice = InitiatorKeys::new(X25519KeyPair::generate());

        // Bundle с неверной длиной ключа
        let bad_bundle = PublicKeyBundle {
            identity_key: vec![0u8; 16], // Должно быть 32
            signed_prekey: vec![0u8; 32],
        };

        let result = initiate_handshake(&alice, &bad_bundle);
        assert!(result.is_err());
        println!("✅ Bundle с неверной длиной ключа отклоняется");
    }

    #[test]
    fn test_invalid_handshake_message_rejected() {
        let bob = ResponderKeys::generate();

        let bad_msg = HandshakeMessage {
            initiator_identity_key: vec![0u8; 32],
            initiator_ephemeral_key: vec![0u8; 16], // Неверная длина
        };

        let result = respond_to_handshake(&bob, &bad_msg);
        assert!(result.is_err());
        println!("✅ Сообщение с неверной длиной ключа отклоняется");
    }
}
