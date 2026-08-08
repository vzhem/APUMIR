//! # Типы сообщений Wire Protocol
//!
//! Все сообщения, которыми обмениваются узлы, объединены в enum
//! `ProtocolMessage`. Приёмник делает `match` по типу и обрабатывает.
//!
//! ## Категории:
//!
//! 1. **Криптографическое рукопожатие** (X3DH):
//!    - `HandshakeReq` — первый шаг (от Initiator)
//!    - `HandshakeResp` — второй шаг (от Responder)
//!
//! 2. **Пользовательские сообщения**:
//!    - `Text` — зашифрованное сообщение через Double Ratchet
//!    - `Ack`  — подтверждение доставки
//!
//! 3. **DHT (Kademlia)** для поиска узлов:
//!    - `DhtQuery` — «где найти узел X?»
//!    - `DhtResp`  — «вот ближайшие узлы к X»
//!
//! 4. **Presence Protocol** (Gossip) для оповещения о присутствии:
//!    - `PresenceAnnounce` — «я онлайн»
//!    - `PresenceQuery`    — «онлайн ли узел X?»
//!    - `PresenceResp`     — «вот кто онлайн»
//!
//! 5. **Ретрансляция**:
//!    - `RelayReq` — «передай этот зашифрованный пакет узлу X»

use serde::{Deserialize, Serialize};

// ═══════════════════════════════════════════════════════════════════
// ГЛАВНЫЙ ENUM — все возможные сообщения
// ═══════════════════════════════════════════════════════════════════

/// Единый тип для всех сообщений Wire Protocol.
///
/// При приёме пакета делаем `match` и обрабатываем соответствующим модулем.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum ProtocolMessage {
    // ─── Криптографическое рукопожатие ─────────────────────────
    HandshakeReq(HandshakeReq),
    HandshakeResp(HandshakeResp),

    // ─── Пользовательские сообщения ────────────────────────────
    Text(TextMsg),
    Ack(Ack),

    // ─── DHT ────────────────────────────────────────────────────
    DhtQuery(DhtQuery),
    DhtResp(DhtResp),

    // ─── Presence Protocol ──────────────────────────────────────
    PresenceAnnounce(PresenceAnnounce),
    PresenceQuery(PresenceQuery),
    PresenceResp(PresenceResp),

    // ─── Ретрансляция ───────────────────────────────────────────
    RelayReq(RelayReq),
}

// ═══════════════════════════════════════════════════════════════════
// РУКОПОЖАТИЕ (X3DH)
// ═══════════════════════════════════════════════════════════════════

/// Первый шаг рукопожатия — от Initiator к Responder.
///
/// Соответствует `HandshakeMessage` из `crypto::handshake`, но с добавлением
/// метаданных отправителя (NodeID, публичные ключи).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct HandshakeReq {
    /// NodeID отправителя (SHA-256 от его Ed25519 pubkey).
    pub sender_node_id: [u8; 32],

    /// Публичный Ed25519 ключ отправителя (для верификации подписи).
    pub sender_ed25519_pubkey: Vec<u8>,

    /// Публичный X25519 Identity Key отправителя.
    pub initiator_identity_key: Vec<u8>,

    /// Публичный X25519 Ephemeral Key отправителя (одноразовый).
    pub initiator_ephemeral_key: Vec<u8>,

    /// Unix timestamp в миллисекундах — для защиты от replay-атак.
    pub timestamp: u64,

    /// Подпись отправителя (Ed25519) над всеми полями выше.
    /// Гарантирует что запрос действительно от заявленного узла.
    pub signature: Vec<u8>,
}

/// Ответ на рукопожатие — от Responder к Initiator.
///
/// Простое подтверждение что мы получили `HandshakeReq` и создали
/// свою сторону сессии. Сам session_key не передаётся — обе стороны
/// вычислили его независимо через X3DH.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct HandshakeResp {
    /// NodeID отвечающего узла.
    pub sender_node_id: [u8; 32],

    /// Статус: успешно ли создана сессия.
    pub status: HandshakeStatus,

    /// Unix timestamp в миллисекундах.
    pub timestamp: u64,
}

/// Результат обработки HandshakeReq.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum HandshakeStatus {
    /// Всё ОК — сессия установлена.
    Ok,
    /// Не удалось проверить подпись.
    InvalidSignature,
    /// Неподдерживаемая версия протокола.
    UnsupportedVersion,
    /// Внутренняя ошибка (без деталей — не раскрываем инфраструктуру).
    InternalError,
}

// ═══════════════════════════════════════════════════════════════════
// ПОЛЬЗОВАТЕЛЬСКИЕ СООБЩЕНИЯ
// ═══════════════════════════════════════════════════════════════════

/// Текстовое сообщение между двумя узлами.
///
/// Содержимое (`ciphertext`) уже зашифровано через Double Ratchet —
/// сеть/ретрансляторы видят только зашифрованные байты.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TextMsg {
    /// Уникальный идентификатор сообщения (UUID v4).
    pub msg_id: [u8; 16],

    /// NodeID отправителя.
    pub sender_node_id: [u8; 32],

    /// NodeID получателя.
    pub recipient_node_id: [u8; 32],

    /// Номер сообщения в цепочке Double Ratchet (counter).
    /// Нужен получателю чтобы прокрутить ratchet до нужной позиции.
    pub ratchet_counter: u32,

    /// Nonce от ChaCha20-Poly1305 (12 байт).
    pub nonce: Vec<u8>,

    /// Зашифрованные данные + Poly1305 тег.
    pub ciphertext: Vec<u8>,

    /// Unix timestamp в миллисекундах (когда отправлено).
    pub timestamp: u64,
}

/// Подтверждение получения/обработки сообщения.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Ack {
    /// ID сообщения, которое подтверждается.
    pub msg_id: [u8; 16],

    /// Статус доставки.
    pub status: AckStatus,

    /// Unix timestamp в миллисекундах.
    pub timestamp: u64,
}

/// Возможные статусы Ack.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum AckStatus {
    /// Получено и успешно расшифровано.
    Delivered,
    /// Прочитано пользователем.
    Read,
    /// Не удалось расшифровать (проблема с ratchet).
    DecryptionFailed,
    /// Нет активной сессии с отправителем.
    NoSession,
}

// ═══════════════════════════════════════════════════════════════════
// DHT (Kademlia)
// ═══════════════════════════════════════════════════════════════════

/// Запрос к DHT: «дайте мне ближайшие узлы к target_node_id».
///
/// Используется для поиска узла в сети когда мы знаем только его NodeID.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DhtQuery {
    /// NodeID узла, который мы ищем.
    pub target_node_id: [u8; 32],

    /// NodeID отправителя запроса (кому отвечать).
    pub requester_node_id: [u8; 32],

    /// Максимальное число узлов в ответе (обычно 16 = k-параметр Kademlia).
    pub max_results: u8,
}

/// Ответ на DhtQuery: список ближайших узлов из routing table.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DhtResp {
    /// NodeID отвечающего узла.
    pub responder_node_id: [u8; 32],

    /// Список найденных ближайших узлов.
    pub nodes: Vec<NodeInfo>,
}

// ═══════════════════════════════════════════════════════════════════
// PRESENCE PROTOCOL (Gossip)
// ═══════════════════════════════════════════════════════════════════

/// Анонс присутствия: «я узел X, я онлайн».
///
/// Рассылается при появлении в сети — соседи получают, обновляют
/// свою таблицу и уведомляют подписчиков.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PresenceAnnounce {
    /// Информация об узле который анонсирует себя.
    pub node: NodeInfo,

    /// Unix timestamp в миллисекундах (когда анонсировался).
    pub timestamp: u64,

    /// Подпись Ed25519 над (node + timestamp) — защита от подделки.
    pub signature: Vec<u8>,

    /// Уникальный ID сообщения (для дедупликации при gossip).
    pub message_id: [u8; 16],

    /// TTL — сколько ещё хопов пересылать (обычно 3, уменьшается на 1 на каждом узле).
    pub ttl: u8,
}

/// Запрос: «онлайн ли узел X?»
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PresenceQuery {
    /// NodeID узла, о котором спрашиваем.
    pub target_node_id: [u8; 32],

    /// NodeID спрашивающего.
    pub requester_node_id: [u8; 32],
}

/// Ответ: «вот кто онлайн из тех, кого я знаю».
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PresenceResp {
    /// NodeID отвечающего.
    pub responder_node_id: [u8; 32],

    /// Список известных онлайн-узлов (или пустой если target недоступен).
    pub online_nodes: Vec<NodeInfo>,

    /// Есть ли конкретно запрашиваемый target_node_id среди них.
    pub target_is_online: bool,
}

// ═══════════════════════════════════════════════════════════════════
// РЕТРАНСЛЯЦИЯ
// ═══════════════════════════════════════════════════════════════════

/// Запрос на ретрансляцию: «передай этот пакет узлу X».
///
/// Используется когда прямое соединение с получателем невозможно
/// (NAT / отсутствие маршрута). Ретранслирующий узел видит только
/// зашифрованный `payload` — прочитать содержимое НЕ может.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RelayReq {
    /// NodeID отправителя.
    pub from_node_id: [u8; 32],

    /// NodeID конечного получателя.
    pub to_node_id: [u8; 32],

    /// Зашифрованный payload — обычно сериализованный TextMsg.
    /// Ретранслятор не может это прочитать.
    pub payload: Vec<u8>,

    /// TTL — сколько ещё хопов разрешено (защита от зацикливания).
    pub ttl: u8,

    /// Уникальный ID сообщения для дедупликации.
    pub message_id: [u8; 16],
}

// ═══════════════════════════════════════════════════════════════════
// ИНФОРМАЦИЯ ОБ УЗЛЕ
// ═══════════════════════════════════════════════════════════════════

/// Информация об узле — используется в DHT и Presence.
///
/// Компактное представление того, что нам нужно знать об узле:
/// как с ним связаться и какой у него уровень доверия.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct NodeInfo {
    /// Уникальный идентификатор узла.
    pub node_id: [u8; 32],

    /// Публичный Ed25519 ключ (для верификации подписей узла).
    pub ed25519_pubkey: Vec<u8>,

    /// Сетевой адрес (IP:PORT) в текстовой форме.
    /// Может быть пустой строкой если узел доступен только через relay.
    pub address: String,

    /// Уровень узла в иерархии (Super/Relay/Leaf).
    pub tier: NodeTier,

    /// Рейтинг узла (0.0 – 100.0). См. `nodes::node_rating`.
    pub rating: f32,
}

/// Уровень узла в иерархии сети.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum NodeTier {
    /// Tier 1: Super Node — рейтинг > 90.
    /// Ретрансляция файлов, звонков, store-and-forward.
    Super,

    /// Tier 2: Relay Node — рейтинг 50-90.
    /// Пересылка текста, участие в DHT.
    Relay,

    /// Tier 3: Leaf Node — рейтинг < 50.
    /// Обычные мобильные устройства пользователей.
    Leaf,
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_construct_text_msg() {
        let msg = TextMsg {
            msg_id: [1; 16],
            sender_node_id: [2; 32],
            recipient_node_id: [3; 32],
            ratchet_counter: 42,
            nonce: vec![0; 12],
            ciphertext: vec![9; 100],
            timestamp: 1_700_000_000_000,
        };

        assert_eq!(msg.ratchet_counter, 42);
        assert_eq!(msg.nonce.len(), 12);
        assert_eq!(msg.ciphertext.len(), 100);
        println!("✅ TextMsg корректно конструируется");
    }

    #[test]
    fn test_construct_ack() {
        let ack = Ack {
            msg_id: [1; 16],
            status: AckStatus::Delivered,
            timestamp: 1_700_000_000_000,
        };

        assert!(matches!(ack.status, AckStatus::Delivered));
        println!("✅ Ack корректно конструируется");
    }

    #[test]
    fn test_all_ack_statuses() {
        // Все варианты статуса должны конструироваться
        let statuses = vec![
            AckStatus::Delivered,
            AckStatus::Read,
            AckStatus::DecryptionFailed,
            AckStatus::NoSession,
        ];

        for status in statuses {
            let ack = Ack {
                msg_id: [0; 16],
                status: status.clone(),
                timestamp: 0,
            };
            assert_eq!(ack.status, status);
        }
        println!("✅ Все статусы Ack корректны");
    }

    #[test]
    fn test_all_node_tiers() {
        let tiers = vec![NodeTier::Super, NodeTier::Relay, NodeTier::Leaf];
        for tier in tiers {
            let info = NodeInfo {
                node_id: [0; 32],
                ed25519_pubkey: vec![0; 32],
                address: "127.0.0.1:7777".to_string(),
                tier: tier.clone(),
                rating: 50.0,
            };
            assert_eq!(info.tier, tier);
        }
        println!("✅ Все Tier корректны");
    }

    #[test]
    fn test_construct_handshake_req() {
        let req = HandshakeReq {
            sender_node_id: [1; 32],
            sender_ed25519_pubkey: vec![0; 32],
            initiator_identity_key: vec![0; 32],
            initiator_ephemeral_key: vec![0; 32],
            timestamp: 1_700_000_000_000,
            signature: vec![0; 64],
        };

        assert_eq!(req.sender_ed25519_pubkey.len(), 32);
        assert_eq!(req.signature.len(), 64);
        println!("✅ HandshakeReq корректно конструируется");
    }

    #[test]
    fn test_construct_dht_query() {
        let query = DhtQuery {
            target_node_id: [1; 32],
            requester_node_id: [2; 32],
            max_results: 16,
        };
        assert_eq!(query.max_results, 16);
        println!("✅ DhtQuery корректно конструируется");
    }

    #[test]
    fn test_construct_presence_announce() {
        let announce = PresenceAnnounce {
            node: NodeInfo {
                node_id: [1; 32],
                ed25519_pubkey: vec![0; 32],
                address: "10.0.0.1:7777".to_string(),
                tier: NodeTier::Leaf,
                rating: 0.0,
            },
            timestamp: 1_700_000_000_000,
            signature: vec![0; 64],
            message_id: [7; 16],
            ttl: 3,
        };

        assert_eq!(announce.ttl, 3);
        assert_eq!(announce.signature.len(), 64);
        println!("✅ PresenceAnnounce корректно конструируется");
    }

    #[test]
    fn test_construct_relay_req() {
        let relay = RelayReq {
            from_node_id: [1; 32],
            to_node_id: [2; 32],
            payload: vec![9; 500],
            ttl: 10,
            message_id: [3; 16],
        };
        assert_eq!(relay.payload.len(), 500);
        assert_eq!(relay.ttl, 10);
        println!("✅ RelayReq корректно конструируется");
    }

    #[test]
    fn test_protocol_message_can_wrap_all_types() {
        // Проверяем, что enum ProtocolMessage может обернуть все типы
        let messages = vec![
            ProtocolMessage::Text(TextMsg {
                msg_id: [0; 16],
                sender_node_id: [0; 32],
                recipient_node_id: [0; 32],
                ratchet_counter: 0,
                nonce: vec![0; 12],
                ciphertext: vec![0; 10],
                timestamp: 0,
            }),
            ProtocolMessage::Ack(Ack {
                msg_id: [0; 16],
                status: AckStatus::Delivered,
                timestamp: 0,
            }),
            ProtocolMessage::HandshakeReq(HandshakeReq {
                sender_node_id: [0; 32],
                sender_ed25519_pubkey: vec![0; 32],
                initiator_identity_key: vec![0; 32],
                initiator_ephemeral_key: vec![0; 32],
                timestamp: 0,
                signature: vec![0; 64],
            }),
            ProtocolMessage::HandshakeResp(HandshakeResp {
                sender_node_id: [0; 32],
                status: HandshakeStatus::Ok,
                timestamp: 0,
            }),
            ProtocolMessage::DhtQuery(DhtQuery {
                target_node_id: [0; 32],
                requester_node_id: [0; 32],
                max_results: 16,
            }),
            ProtocolMessage::DhtResp(DhtResp {
                responder_node_id: [0; 32],
                nodes: vec![],
            }),
            ProtocolMessage::PresenceAnnounce(PresenceAnnounce {
                node: NodeInfo {
                    node_id: [0; 32],
                    ed25519_pubkey: vec![0; 32],
                    address: String::new(),
                    tier: NodeTier::Leaf,
                    rating: 0.0,
                },
                timestamp: 0,
                signature: vec![0; 64],
                message_id: [0; 16],
                ttl: 3,
            }),
            ProtocolMessage::PresenceQuery(PresenceQuery {
                target_node_id: [0; 32],
                requester_node_id: [0; 32],
            }),
            ProtocolMessage::PresenceResp(PresenceResp {
                responder_node_id: [0; 32],
                online_nodes: vec![],
                target_is_online: false,
            }),
            ProtocolMessage::RelayReq(RelayReq {
                from_node_id: [0; 32],
                to_node_id: [0; 32],
                payload: vec![],
                ttl: 10,
                message_id: [0; 16],
            }),
        ];

        assert_eq!(messages.len(), 10);
        println!("✅ Все 10 типов сообщений оборачиваются в ProtocolMessage");
    }
}
