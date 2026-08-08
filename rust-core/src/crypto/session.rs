//! # Session Manager — управление активными E2E-сессиями
//!
//! Каждая сессия = отдельный Double Ratchet с одним контактом.
//! SessionManager хранит все активные сессии в памяти и предоставляет
//! простой API для отправки/получения зашифрованных сообщений.
//!
//! ## Жизненный цикл сессии:
//!
//! ```text
//! 1. Первый контакт с узлом B:
//!    ├── Получаем PublicKeyBundle узла B (через QR/сеть)
//!    ├── SessionManager::establish_as_initiator(&bundle)
//!    │   └── Внутри: X3DH → session_key → RatchetState
//!    └── Отправляем HandshakeMessage узлу B
//!
//! 2. Узел B получает HandshakeMessage:
//!    └── SessionManager::establish_as_responder(&handshake_msg)
//!        └── Внутри: X3DH → тот же session_key → RatchetState
//!
//! 3. Обмен сообщениями:
//!    ├── encrypt_message(node_id, "Hello") → RatchetEncryptedMessage
//!    └── decrypt_message(node_id, encrypted) → "Hello"
//! ```

use std::collections::HashMap;

use crate::crypto::handshake::{
    initiate_handshake, respond_to_handshake, HandshakeMessage, InitiatorKeys, PublicKeyBundle,
    ResponderKeys,
};
use crate::crypto::keys::{NodeId, X25519KeyPair};
use crate::crypto::ratchet::{RatchetEncryptedMessage, RatchetState};
use crate::crypto::{CryptoError, CryptoResult};

// ═══════════════════════════════════════════════════════════════════
// SESSION MANAGER
// ═══════════════════════════════════════════════════════════════════

/// Менеджер всех активных E2E-сессий.
///
/// Хранит RatchetState для каждого контакта в HashMap.
/// Ключ — NodeID контакта.
pub struct SessionManager {
    /// Наши постоянные ключи (Identity Key + Signed PreKey)
    responder_keys: ResponderKeys,
    /// Активные сессии: NodeID контакта → его RatchetState
    sessions: HashMap<NodeId, RatchetState>,
}

impl SessionManager {
    /// Создать новый менеджер с заданными постоянными ключами.
    ///
    /// Обычно ключи загружаются из хранилища (см. `identity.rs`),
    /// а при первом запуске генерируются свежие.
    pub fn new(responder_keys: ResponderKeys) -> Self {
        SessionManager {
            responder_keys,
            sessions: HashMap::new(),
        }
    }

    /// Публичный bundle для передачи другим узлам.
    /// Отдаём через QR-код, DHT, Presence Protocol и т.д.
    pub fn my_public_bundle(&self) -> PublicKeyBundle {
        self.responder_keys.public_bundle()
    }

    /// Установить сессию как ИНИЦИАТОР.
    ///
    /// Мы хотим начать общение с контактом, у которого есть публичный bundle.
    ///
    /// # Возвращает
    /// - `HandshakeMessage` — отправить контакту чтобы он смог создать свою сторону сессии
    pub fn establish_as_initiator(
        &mut self,
        contact_id: NodeId,
        contact_bundle: &PublicKeyBundle,
    ) -> CryptoResult<HandshakeMessage> {
        // Используем наш Identity Key + генерируем свежий Ephemeral Key
        let initiator_keys = InitiatorKeys::new(clone_x25519(&self.responder_keys.identity_key)?);

        let (session_key, handshake_msg) = initiate_handshake(&initiator_keys, contact_bundle)?;

        // Создаём RatchetState для этой сессии
        let ratchet = RatchetState::new_initiator(&session_key)?;
        self.sessions.insert(contact_id, ratchet);

        Ok(handshake_msg)
    }

    /// Установить сессию как ПОЛУЧАТЕЛЬ.
    ///
    /// Кто-то отправил нам HandshakeMessage — вычисляем тот же session_key
    /// и создаём свой RatchetState.
    ///
    /// # Аргументы
    /// - `contact_id` — NodeID отправителя (SHA-256 от его Ed25519 pubkey)
    /// - `message` — полученное HandshakeMessage
    pub fn establish_as_responder(
        &mut self,
        contact_id: NodeId,
        message: &HandshakeMessage,
    ) -> CryptoResult<()> {
        let session_key = respond_to_handshake(&self.responder_keys, message)?;

        let ratchet = RatchetState::new_responder(&session_key)?;
        self.sessions.insert(contact_id, ratchet);

        Ok(())
    }

    /// Зашифровать сообщение для контакта.
    /// Требует что сессия с контактом уже установлена.
    pub fn encrypt_message(
        &mut self,
        contact_id: &NodeId,
        plaintext: &[u8],
    ) -> CryptoResult<RatchetEncryptedMessage> {
        let session = self.sessions.get_mut(contact_id).ok_or_else(|| {
            CryptoError::Encryption(format!(
                "Нет активной сессии с узлом {}",
                contact_id.short()
            ))
        })?;

        session.encrypt(plaintext)
    }

    /// Дешифровать полученное сообщение.
    /// Требует что сессия с контактом уже установлена.
    pub fn decrypt_message(
        &mut self,
        contact_id: &NodeId,
        encrypted: &RatchetEncryptedMessage,
    ) -> CryptoResult<Vec<u8>> {
        let session = self.sessions.get_mut(contact_id).ok_or_else(|| {
            CryptoError::Decryption(format!(
                "Нет активной сессии с узлом {}",
                contact_id.short()
            ))
        })?;

        session.decrypt(encrypted)
    }

    /// Проверить есть ли активная сессия с контактом.
    pub fn has_session(&self, contact_id: &NodeId) -> bool {
        self.sessions.contains_key(contact_id)
    }

    /// Количество активных сессий.
    pub fn session_count(&self) -> usize {
        self.sessions.len()
    }

    /// Удалить сессию (при удалении контакта или сбросе).
    pub fn remove_session(&mut self, contact_id: &NodeId) -> bool {
        self.sessions.remove(contact_id).is_some()
    }

    /// Список всех контактов с активными сессиями.
    pub fn active_contacts(&self) -> Vec<NodeId> {
        self.sessions.keys().cloned().collect()
    }
}

// ═══════════════════════════════════════════════════════════════════
// ВСПОМОГАТЕЛЬНАЯ ФУНКЦИЯ
// ═══════════════════════════════════════════════════════════════════

/// Клонирование X25519KeyPair (в самой библиотеке нет Clone для StaticSecret).
/// Восстанавливаем через приватные байты.
fn clone_x25519(kp: &X25519KeyPair) -> CryptoResult<X25519KeyPair> {
    let secret_bytes = kp.secret_key().0;
    X25519KeyPair::from_secret_bytes(&secret_bytes)
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::keys::Ed25519KeyPair;

    /// Создаёт пару SessionManager'ов и устанавливает между ними сессию.
    /// Возвращает: (alice, bob, alice_node_id, bob_node_id)
    fn setup_pair() -> (SessionManager, SessionManager, NodeId, NodeId) {
        // Каждый узел имеет свою идентичность
        let alice_ed = Ed25519KeyPair::generate();
        let bob_ed = Ed25519KeyPair::generate();

        let alice_id = alice_ed.node_id();
        let bob_id = bob_ed.node_id();

        // Каждый генерирует свои X25519 ключи для рукопожатия
        let mut alice = SessionManager::new(ResponderKeys::generate());
        let mut bob = SessionManager::new(ResponderKeys::generate());

        // Alice инициирует сессию с Bob
        let bob_bundle = bob.my_public_bundle();
        let handshake = alice
            .establish_as_initiator(bob_id.clone(), &bob_bundle)
            .unwrap();

        // Bob получает handshake и создаёт свою сторону сессии
        bob.establish_as_responder(alice_id.clone(), &handshake)
            .unwrap();

        (alice, bob, alice_id, bob_id)
    }

    #[test]
    fn test_session_establishment() {
        let (alice, bob, alice_id, bob_id) = setup_pair();

        assert!(alice.has_session(&bob_id));
        assert!(bob.has_session(&alice_id));
        assert_eq!(alice.session_count(), 1);
        assert_eq!(bob.session_count(), 1);
        println!("✅ Сессия между Alice и Bob установлена");
    }

    #[test]
    fn test_encrypt_decrypt_via_session_manager() {
        let (mut alice, mut bob, alice_id, bob_id) = setup_pair();

        // Alice шифрует сообщение для Bob
        let encrypted = alice.encrypt_message(&bob_id, b"Hi Bob!").unwrap();

        // Bob дешифрует
        let decrypted = bob.decrypt_message(&alice_id, &encrypted).unwrap();

        assert_eq!(decrypted, b"Hi Bob!");
        println!("✅ Сообщение прошло через SessionManager");
    }

    #[test]
    fn test_bidirectional_via_session_manager() {
        let (mut alice, mut bob, alice_id, bob_id) = setup_pair();

        // Alice → Bob
        let e1 = alice
            .encrypt_message(&bob_id, "Привет Bob".as_bytes())
            .unwrap();
        assert_eq!(
            bob.decrypt_message(&alice_id, &e1).unwrap(),
            "Привет Bob".as_bytes()
        );

        // Bob → Alice
        let e2 = bob
            .encrypt_message(&alice_id, "Привет Alice".as_bytes())
            .unwrap();
        assert_eq!(
            alice.decrypt_message(&bob_id, &e2).unwrap(),
            "Привет Alice".as_bytes()
        );

        // Ещё раунд
        let e3 = alice
            .encrypt_message(&bob_id, "Как дела?".as_bytes())
            .unwrap();
        assert_eq!(
            bob.decrypt_message(&alice_id, &e3).unwrap(),
            "Как дела?".as_bytes()
        );

        println!("✅ Двусторонний обмен через SessionManager");
    }

    #[test]
    fn test_encrypt_without_session_fails() {
        let mut alice = SessionManager::new(ResponderKeys::generate());
        let random_contact = NodeId([42u8; 32]);

        let result = alice.encrypt_message(&random_contact, b"hi");
        assert!(result.is_err());
        println!("✅ Отправка без сессии → ошибка");
    }

    #[test]
    fn test_decrypt_without_session_fails() {
        let (mut alice, _bob, _alice_id, bob_id) = setup_pair();
        let msg = alice.encrypt_message(&bob_id, b"test").unwrap();

        // Другой узел без сессии не может дешифровать
        let mut charlie = SessionManager::new(ResponderKeys::generate());
        let alice_fake_id = NodeId([1u8; 32]);
        let result = charlie.decrypt_message(&alice_fake_id, &msg);
        assert!(result.is_err());
        println!("✅ Дешифровка без сессии → ошибка");
    }

    #[test]
    fn test_multiple_contacts() {
        let mut alice = SessionManager::new(ResponderKeys::generate());

        // Alice устанавливает сессии с Bob, Charlie и Dave
        let bob = SessionManager::new(ResponderKeys::generate());
        let charlie = SessionManager::new(ResponderKeys::generate());
        let dave = SessionManager::new(ResponderKeys::generate());

        let bob_id = NodeId([1u8; 32]);
        let charlie_id = NodeId([2u8; 32]);
        let dave_id = NodeId([3u8; 32]);

        alice
            .establish_as_initiator(bob_id.clone(), &bob.my_public_bundle())
            .unwrap();
        alice
            .establish_as_initiator(charlie_id.clone(), &charlie.my_public_bundle())
            .unwrap();
        alice
            .establish_as_initiator(dave_id.clone(), &dave.my_public_bundle())
            .unwrap();

        assert_eq!(alice.session_count(), 3);
        assert!(alice.has_session(&bob_id));
        assert!(alice.has_session(&charlie_id));
        assert!(alice.has_session(&dave_id));

        let contacts = alice.active_contacts();
        assert_eq!(contacts.len(), 3);
        println!("✅ Работа с несколькими контактами (3 сессии)");
    }

    #[test]
    fn test_remove_session() {
        let (mut alice, _bob, _alice_id, bob_id) = setup_pair();

        assert!(alice.has_session(&bob_id));
        assert!(alice.remove_session(&bob_id));
        assert!(!alice.has_session(&bob_id));
        assert_eq!(alice.session_count(), 0);

        // Повторное удаление возвращает false
        assert!(!alice.remove_session(&bob_id));
        println!("✅ Удаление сессии работает");
    }

    #[test]
    fn test_wrong_contact_id_wrong_decrypt() {
        // Alice отправляет Bob-у, но Bob пытается дешифровать используя
        // NodeID Charlie (для которого сессии нет)
        let (mut alice, mut bob, _alice_id, bob_id) = setup_pair();

        let msg = alice.encrypt_message(&bob_id, b"secret").unwrap();

        let wrong_id = NodeId([99u8; 32]);
        let result = bob.decrypt_message(&wrong_id, &msg);
        assert!(result.is_err());
        println!("✅ Неверный contact_id → ошибка");
    }

    #[test]
    fn test_conversation_long() {
        // 50 сообщений туда-обратно
        let (mut alice, mut bob, alice_id, bob_id) = setup_pair();

        for i in 0..25 {
            // Alice → Bob
            let text = format!("Alice msg {}", i);
            let enc = alice.encrypt_message(&bob_id, text.as_bytes()).unwrap();
            let dec = bob.decrypt_message(&alice_id, &enc).unwrap();
            assert_eq!(dec, text.as_bytes());

            // Bob → Alice
            let text = format!("Bob reply {}", i);
            let enc = bob.encrypt_message(&alice_id, text.as_bytes()).unwrap();
            let dec = alice.decrypt_message(&bob_id, &enc).unwrap();
            assert_eq!(dec, text.as_bytes());
        }
        println!("✅ 50 сообщений (25 туда + 25 обратно) через SessionManager");
    }
}
