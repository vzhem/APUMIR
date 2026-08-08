//! # Double Ratchet (упрощённый symmetric-key вариант)
//!
//! После установки первого сессионного ключа через X3DH, начинается
//! Double Ratchet — механизм обновления ключей на каждое сообщение.
//!
//! ## Perfect Forward Secrecy (PFS):
//!
//! Каждое сообщение шифруется УНИКАЛЬНЫМ ключом.
//! Если злоумышленник украдёт один Message Key:
//! - Прошлые сообщения → **безопасны** (KDF односторонняя)
//! - Будущие сообщения → **безопасны** (KDF односторонняя)
//! - Скомпрометировано → только ЭТО сообщение
//!
//! ## Схема (упрощённая, без DH-ratchet):
//!
//! ```text
//! Начальный SK от X3DH
//!         │
//!         ▼
//!   ┌───────────┐
//!   │ chain_key │──── ratchet_kdf() ──┐
//!   └─────┬─────┘                     │
//!         │                           ▼
//!         ▼                    ┌──────────────┐
//!   new chain_key              │ message_key  │──► шифруем msg 1
//!         │                    └──────────────┘
//!         ▼
//!   ratchet_kdf() ──┐
//!         │         │
//!         ▼         ▼
//!   next chain   msg_key 2 ──► шифруем msg 2
//!         │
//!         ▼
//!        ...
//! ```
//!
//! ## Что НЕ реализовано в этом упрощённом варианте:
//!
//! - **DH-ratchet** — обновление корневого ключа через новый Diffie-Hellman
//!   (даст защиту от компрометации chain_key)
//! - **Out-of-order messages** — доставка сообщений не по порядку
//! - **Skipped message keys** — сохранение ключей пропущенных сообщений
//!
//! Всё это в Фазе 2.6 (полная Signal-подобная криптография).

use crate::crypto::cipher::{Cipher, EncryptedMessage, SymmetricKey};
use crate::crypto::kdf::ratchet_kdf;
use crate::crypto::{CryptoError, CryptoResult};

// ═══════════════════════════════════════════════════════════════════
// СОСТОЯНИЕ ЦЕПОЧКИ
// ═══════════════════════════════════════════════════════════════════

/// Одна цепочка ключей (для отправки ИЛИ для приёма).
///
/// Каждая сторона диалога держит две цепочки:
/// - `sending_chain` — для шифрования исходящих сообщений
/// - `receiving_chain` — для дешифрования входящих
///
/// У Alice sending_chain соответствует receiving_chain у Bob и наоборот.
pub struct RatchetChain {
    /// Текущий chain_key. Обновляется после каждого сообщения.
    chain_key: Vec<u8>,
    /// Счётчик обработанных сообщений (для отладки и защиты от повторов).
    counter: u32,
}

impl RatchetChain {
    /// Создать новую цепочку из начального ключа (обычно X3DH session key).
    pub fn new(initial_key: &[u8]) -> CryptoResult<Self> {
        if initial_key.len() != 32 {
            return Err(CryptoError::InvalidKeyLength {
                expected: 32,
                actual: initial_key.len(),
            });
        }

        Ok(RatchetChain {
            chain_key: initial_key.to_vec(),
            counter: 0,
        })
    }

    /// Прокрутить рэтчет вперёд, получить message_key для одного сообщения.
    ///
    /// После вызова:
    /// - chain_key заменён на новый (старый безвозвратно удалён)
    /// - counter увеличен на 1
    /// - message_key возвращён для одноразового использования
    pub fn next_message_key(&mut self) -> CryptoResult<(u32, SymmetricKey)> {
        let (new_chain_key, message_key_bytes) = ratchet_kdf(&self.chain_key)?;

        // Заменяем chain_key — старый теперь недоступен (PFS!)
        self.chain_key = new_chain_key;

        let current_counter = self.counter;
        self.counter += 1;

        let message_key = SymmetricKey::from_bytes(&message_key_bytes)
            .map_err(|e| CryptoError::KeyGeneration(e.to_string()))?;

        Ok((current_counter, message_key))
    }

    /// Текущий счётчик сообщений.
    pub fn counter(&self) -> u32 {
        self.counter
    }
}

// ═══════════════════════════════════════════════════════════════════
// СОСТОЯНИЕ РЭТЧЕТА (для одной стороны диалога)
// ═══════════════════════════════════════════════════════════════════

/// Состояние Double Ratchet для одного диалога с одним контактом.
///
/// Каждая сторона диалога держит:
/// - sending_chain — для своих исходящих
/// - receiving_chain — для чужих входящих
///
/// # Инициализация:
///
/// После X3DH у обеих сторон одинаковый session_key.
/// Но роли разные: одна сторона Initiator, другая Responder.
/// Чтобы sending_chain одной = receiving_chain другой,
/// используем деривацию с разным контекстом.
pub struct RatchetState {
    sending_chain: RatchetChain,
    receiving_chain: RatchetChain,
}

impl RatchetState {
    /// Инициализация для роли Initiator (Alice).
    ///
    /// Alice sending_chain = Bob receiving_chain
    /// Alice receiving_chain = Bob sending_chain
    pub fn new_initiator(session_key: &[u8]) -> CryptoResult<Self> {
        use crate::crypto::kdf::derive_key;

        // Деривируем два разных ключа из session_key
        let alice_send = derive_key(session_key, b"", b"ratchet-init-send", 32)?;
        let alice_recv = derive_key(session_key, b"", b"ratchet-resp-send", 32)?;

        Ok(RatchetState {
            sending_chain: RatchetChain::new(&alice_send)?,
            receiving_chain: RatchetChain::new(&alice_recv)?,
        })
    }

    /// Инициализация для роли Responder (Bob).
    ///
    /// Bob sending_chain = Alice receiving_chain
    /// Bob receiving_chain = Alice sending_chain
    pub fn new_responder(session_key: &[u8]) -> CryptoResult<Self> {
        use crate::crypto::kdf::derive_key;

        // Bob: контексты меняются местами относительно Alice
        let bob_send = derive_key(session_key, b"", b"ratchet-resp-send", 32)?;
        let bob_recv = derive_key(session_key, b"", b"ratchet-init-send", 32)?;

        Ok(RatchetState {
            sending_chain: RatchetChain::new(&bob_send)?,
            receiving_chain: RatchetChain::new(&bob_recv)?,
        })
    }

    /// Зашифровать сообщение (прокручивает sending_chain).
    ///
    /// Возвращает зашифрованное сообщение + номер (нужен получателю
    /// чтобы знать сколько раз прокрутить свою receiving_chain).
    pub fn encrypt(&mut self, plaintext: &[u8]) -> CryptoResult<RatchetEncryptedMessage> {
        let (counter, msg_key) = self.sending_chain.next_message_key()?;

        let cipher = Cipher::new(&msg_key);
        let encrypted = cipher
            .encrypt(plaintext)
            .map_err(|e| CryptoError::Encryption(e.to_string()))?;

        Ok(RatchetEncryptedMessage { counter, encrypted })
    }

    /// Дешифровать сообщение (прокручивает receiving_chain).
    ///
    /// В упрощённой версии сообщения должны приходить строго по порядку.
    /// В Фазе 2.6 добавим поддержку out-of-order через skipped keys.
    pub fn decrypt(&mut self, msg: &RatchetEncryptedMessage) -> CryptoResult<Vec<u8>> {
        // Проверяем что сообщение идёт по порядку
        if msg.counter != self.receiving_chain.counter() {
            return Err(CryptoError::Decryption(format!(
                "Ожидался counter {}, получен {} (out-of-order не поддерживается в MVP)",
                self.receiving_chain.counter(),
                msg.counter
            )));
        }

        let (_counter, msg_key) = self.receiving_chain.next_message_key()?;

        let cipher = Cipher::new(&msg_key);
        let plaintext = cipher
            .decrypt(&msg.encrypted)
            .map_err(|e| CryptoError::Decryption(e.to_string()))?;

        Ok(plaintext)
    }
}

// ═══════════════════════════════════════════════════════════════════
// ЗАШИФРОВАННОЕ СООБЩЕНИЕ С COUNTER
// ═══════════════════════════════════════════════════════════════════

/// Сообщение как оно передаётся по сети:
/// зашифрованные данные + counter для правильной дешифровки.
#[derive(Debug, Clone)]
pub struct RatchetEncryptedMessage {
    /// Номер сообщения в цепочке (0, 1, 2, ...)
    pub counter: u32,
    /// Зашифрованные данные
    pub encrypted: EncryptedMessage,
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::handshake::{
        initiate_handshake, respond_to_handshake, InitiatorKeys, ResponderKeys,
    };
    use crate::crypto::keys::X25519KeyPair;

    /// Вспомогательная функция: имитирует полное установление сессии
    /// через X3DH и возвращает две готовые RatchetState.
    fn establish_session() -> (RatchetState, RatchetState) {
        let alice_identity = X25519KeyPair::generate();
        let bob = ResponderKeys::generate();

        let alice_keys = InitiatorKeys::new(alice_identity);
        let (alice_sk, msg) = initiate_handshake(&alice_keys, &bob.public_bundle()).unwrap();
        let bob_sk = respond_to_handshake(&bob, &msg).unwrap();

        // Ключи должны совпасть
        assert_eq!(alice_sk, bob_sk);

        let alice_state = RatchetState::new_initiator(&alice_sk).unwrap();
        let bob_state = RatchetState::new_responder(&bob_sk).unwrap();

        (alice_state, bob_state)
    }

    #[test]
    fn test_ratchet_chain_progression() {
        let mut chain = RatchetChain::new(&[42u8; 32]).unwrap();

        let (c0, _) = chain.next_message_key().unwrap();
        let (c1, _) = chain.next_message_key().unwrap();
        let (c2, _) = chain.next_message_key().unwrap();

        assert_eq!(c0, 0);
        assert_eq!(c1, 1);
        assert_eq!(c2, 2);
        assert_eq!(chain.counter(), 3);
        println!("✅ Счётчик рэтчета инкрементируется");
    }

    #[test]
    fn test_ratchet_message_keys_unique() {
        let mut chain = RatchetChain::new(&[42u8; 32]).unwrap();

        let (_, k1) = chain.next_message_key().unwrap();
        let (_, k2) = chain.next_message_key().unwrap();
        let (_, k3) = chain.next_message_key().unwrap();

        // Все message keys должны быть разными
        assert_ne!(k1.as_bytes(), k2.as_bytes());
        assert_ne!(k2.as_bytes(), k3.as_bytes());
        assert_ne!(k1.as_bytes(), k3.as_bytes());
        println!("✅ Message keys уникальны на каждом шаге");
    }

    #[test]
    fn test_single_message_exchange() {
        let (mut alice, mut bob) = establish_session();

        // Alice отправляет
        let msg = alice.encrypt(b"Hello Bob!").unwrap();

        // Bob получает
        let decrypted = bob.decrypt(&msg).unwrap();

        assert_eq!(decrypted, b"Hello Bob!");
        println!("✅ Одиночное сообщение через Ratchet работает");
    }

    #[test]
    fn test_multiple_messages_from_alice_to_bob() {
        let (mut alice, mut bob) = establish_session();

        let messages: Vec<&[u8]> = vec![
            b"First message",
            b"Second message",
            b"Third message",
            b"Fourth message",
            b"Fifth message",
        ];

        // Alice шифрует все, Bob дешифрует все
        let mut encrypted = Vec::new();
        for msg in &messages {
            encrypted.push(alice.encrypt(msg).unwrap());
        }

        for (i, enc) in encrypted.iter().enumerate() {
            let decrypted = bob.decrypt(enc).unwrap();
            assert_eq!(decrypted, messages[i]);
            assert_eq!(enc.counter, i as u32);
        }

        println!("✅ 5 сообщений подряд от Alice к Bob прошли");
    }

    #[test]
    fn test_bidirectional_conversation() {
        let (mut alice, mut bob) = establish_session();

        // Диалог туда-сюда
        let m1 = alice.encrypt(b"Hi Bob!").unwrap();
        assert_eq!(bob.decrypt(&m1).unwrap(), b"Hi Bob!");

        let m2 = bob.encrypt(b"Hi Alice!").unwrap();
        assert_eq!(alice.decrypt(&m2).unwrap(), b"Hi Alice!");

        let m3 = alice.encrypt(b"How are you?").unwrap();
        assert_eq!(bob.decrypt(&m3).unwrap(), b"How are you?");

        let m4 = bob.encrypt(b"Great, thanks!").unwrap();
        assert_eq!(alice.decrypt(&m4).unwrap(), b"Great, thanks!");

        println!("✅ Двусторонний диалог работает");
    }

    #[test]
    fn test_ciphertexts_differ_for_same_plaintext() {
        let (mut alice, _bob) = establish_session();

        // Одинаковый plaintext дважды
        let m1 = alice.encrypt(b"same message").unwrap();
        let m2 = alice.encrypt(b"same message").unwrap();

        // Ciphertext должен отличаться (разные message keys)
        assert_ne!(m1.encrypted.ciphertext, m2.encrypted.ciphertext);
        assert_ne!(m1.counter, m2.counter);
        println!("✅ Одинаковый plaintext → разный ciphertext (PFS работает)");
    }

    #[test]
    fn test_out_of_order_rejected_in_mvp() {
        let (mut alice, mut bob) = establish_session();

        let m1 = alice.encrypt(b"msg 1").unwrap();
        let m2 = alice.encrypt(b"msg 2").unwrap();

        // Bob пытается сначала расшифровать msg 2 (пропустив msg 1) — ошибка
        let result = bob.decrypt(&m2);
        assert!(result.is_err());

        // Правильный порядок работает
        assert_eq!(bob.decrypt(&m1).unwrap(), b"msg 1");
        assert_eq!(bob.decrypt(&m2).unwrap(), b"msg 2");
        println!("✅ Out-of-order корректно отклоняется в MVP");
    }

    #[test]
    fn test_wrong_session_cannot_decrypt() {
        let (mut alice, _bob) = establish_session();
        let (_alice2, mut bob2) = establish_session(); // Другая сессия!

        let msg = alice.encrypt(b"secret").unwrap();

        // Bob из другой сессии не может дешифровать
        let result = bob2.decrypt(&msg);
        assert!(result.is_err());
        println!("✅ Сообщение чужой сессии не дешифруется");
    }

    #[test]
    fn test_long_conversation() {
        // Стресс-тест: 100 сообщений
        let (mut alice, mut bob) = establish_session();

        for i in 0..100 {
            let text = format!("Message #{}", i);
            let encrypted = alice.encrypt(text.as_bytes()).unwrap();
            let decrypted = bob.decrypt(&encrypted).unwrap();
            assert_eq!(decrypted, text.as_bytes());
        }

        assert_eq!(alice.sending_chain.counter(), 100);
        assert_eq!(bob.receiving_chain.counter(), 100);
        println!("✅ Стресс-тест: 100 сообщений через Ratchet");
    }
}
