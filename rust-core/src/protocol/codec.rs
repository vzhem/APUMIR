//! # Кодек — сериализация/десериализация Wire Protocol
//!
//! Отвечает за:
//! - Кодирование `ProtocolMessage` в байты (для отправки в QUIC-стрим)
//! - Декодирование байт в `ProtocolMessage` (при приёме из стрима)
//! - **Версионирование**: первый байт каждого пакета — версия протокола
//! - **Защита от переполнения**: отклонение пакетов > MAX_PACKET_SIZE
//!
//! ## Формат пакета:
//!
//! ```text
//! Байт 0:  Version (u8)
//! Байты 1..N:  bincode(ProtocolMessage)
//! ```

use super::messages::ProtocolMessage;
use super::{MAX_PACKET_SIZE, MIN_SUPPORTED_VERSION, PROTOCOL_VERSION};

// ═══════════════════════════════════════════════════════════════════
// ОШИБКИ
// ═══════════════════════════════════════════════════════════════════

/// Ошибки кодека Wire Protocol.
#[derive(Debug, thiserror::Error)]
pub enum ProtocolError {
    #[error("Пакет пустой")]
    EmptyPacket,

    #[error("Пакет слишком большой: {size} байт (максимум {max})")]
    PacketTooLarge { size: usize, max: usize },

    #[error("Неподдерживаемая версия протокола: {got} (минимум {min_supported})")]
    UnsupportedVersion { got: u8, min_supported: u8 },

    #[error("Ошибка сериализации: {0}")]
    SerializationError(String),

    #[error("Ошибка десериализации (повреждённые данные?): {0}")]
    DeserializationError(String),
}

// ═══════════════════════════════════════════════════════════════════
// КОДИРОВАНИЕ
// ═══════════════════════════════════════════════════════════════════

/// Закодировать сообщение в байты для отправки по сети.
///
/// Формат: `[version:1 байт] [bincode(msg)]`
///
/// # Пример
/// ```ignore
/// let msg = ProtocolMessage::Text(TextMsg { ... });
/// let bytes: Vec<u8> = codec::encode(&msg)?;
/// quic_stream.write_all(&bytes).await?;
/// ```
pub fn encode(msg: &ProtocolMessage) -> Result<Vec<u8>, ProtocolError> {
    // Сериализуем через bincode
    let payload =
        bincode::serialize(msg).map_err(|e| ProtocolError::SerializationError(e.to_string()))?;

    // Проверка размера ДО добавления версии
    // (если payload уже слишком большой — отказываемся)
    if payload.len() + 1 > MAX_PACKET_SIZE {
        return Err(ProtocolError::PacketTooLarge {
            size: payload.len() + 1,
            max: MAX_PACKET_SIZE,
        });
    }

    // Собираем финальный пакет: [version][payload]
    let mut packet = Vec::with_capacity(1 + payload.len());
    packet.push(PROTOCOL_VERSION);
    packet.extend_from_slice(&payload);

    Ok(packet)
}

// ═══════════════════════════════════════════════════════════════════
// ДЕКОДИРОВАНИЕ
// ═══════════════════════════════════════════════════════════════════

/// Декодировать полученные байты в сообщение.
///
/// Проверяет:
/// 1. Пакет не пустой
/// 2. Пакет не превышает максимальный размер
/// 3. Версия протокола поддерживается
/// 4. bincode-часть корректно десериализуется
///
/// # Пример
/// ```ignore
/// let bytes = quic_stream.read_to_end().await?;
/// let msg = codec::decode(&bytes)?;
/// match msg {
///     ProtocolMessage::Text(t) => handle_text(t).await,
///     ProtocolMessage::Ack(a) => handle_ack(a).await,
///     ...
/// }
/// ```
pub fn decode(bytes: &[u8]) -> Result<ProtocolMessage, ProtocolError> {
    // 1. Пакет не пустой
    if bytes.is_empty() {
        return Err(ProtocolError::EmptyPacket);
    }

    // 2. Размер в пределах разумного
    if bytes.len() > MAX_PACKET_SIZE {
        return Err(ProtocolError::PacketTooLarge {
            size: bytes.len(),
            max: MAX_PACKET_SIZE,
        });
    }

    // 3. Проверка версии (первый байт)
    let version = bytes[0];
    if version < MIN_SUPPORTED_VERSION {
        return Err(ProtocolError::UnsupportedVersion {
            got: version,
            min_supported: MIN_SUPPORTED_VERSION,
        });
    }

    // 4. Десериализация bincode-части (без первого байта)
    let payload = &bytes[1..];
    bincode::deserialize::<ProtocolMessage>(payload)
        .map_err(|e| ProtocolError::DeserializationError(e.to_string()))
}

// ═══════════════════════════════════════════════════════════════════
// СЛУЖЕБНЫЕ ФУНКЦИИ
// ═══════════════════════════════════════════════════════════════════

/// Извлечь только версию протокола из пакета, не декодируя всё.
///
/// Полезно для быстрой проверки/фильтрации на входе, до полной обработки.
pub fn extract_version(bytes: &[u8]) -> Result<u8, ProtocolError> {
    if bytes.is_empty() {
        return Err(ProtocolError::EmptyPacket);
    }
    Ok(bytes[0])
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::messages::*;

    /// Вспомогательная функция: создать тестовое TextMsg.
    fn sample_text_msg() -> ProtocolMessage {
        ProtocolMessage::Text(TextMsg {
            msg_id: [1; 16],
            sender_node_id: [2; 32],
            recipient_node_id: [3; 32],
            ratchet_counter: 42,
            nonce: vec![0; 12],
            ciphertext: vec![9; 100],
            timestamp: 1_700_000_000_000,
        })
    }

    // ── Базовые тесты encode/decode ────────────────────────────────

    #[test]
    fn test_encode_produces_versioned_output() {
        let msg = sample_text_msg();
        let bytes = encode(&msg).unwrap();

        // Первый байт — версия
        assert_eq!(bytes[0], PROTOCOL_VERSION);
        // Общий размер > 1 (есть payload)
        assert!(bytes.len() > 1);
        println!("✅ encode() добавляет версию первым байтом");
        println!("   Общий размер пакета: {} байт", bytes.len());
    }

    #[test]
    fn test_encode_decode_roundtrip_text() {
        let original = sample_text_msg();
        let bytes = encode(&original).unwrap();
        let decoded = decode(&bytes).unwrap();
        assert_eq!(decoded, original);
        println!("✅ TextMsg: encode → decode → одинаковое сообщение");
    }

    #[test]
    fn test_roundtrip_ack() {
        let original = ProtocolMessage::Ack(Ack {
            msg_id: [1; 16],
            status: AckStatus::Delivered,
            timestamp: 1_700_000_000_000,
        });
        let decoded = decode(&encode(&original).unwrap()).unwrap();
        assert_eq!(decoded, original);
        println!("✅ Ack: encode → decode");
    }

    #[test]
    fn test_roundtrip_handshake_req() {
        let original = ProtocolMessage::HandshakeReq(HandshakeReq {
            sender_node_id: [1; 32],
            sender_ed25519_pubkey: vec![2; 32],
            initiator_identity_key: vec![3; 32],
            initiator_ephemeral_key: vec![4; 32],
            timestamp: 1_700_000_000_000,
            signature: vec![5; 64],
        });
        let decoded = decode(&encode(&original).unwrap()).unwrap();
        assert_eq!(decoded, original);
        println!("✅ HandshakeReq: encode → decode");
    }

    #[test]
    fn test_roundtrip_handshake_resp() {
        let original = ProtocolMessage::HandshakeResp(HandshakeResp {
            sender_node_id: [1; 32],
            status: HandshakeStatus::Ok,
            timestamp: 1_700_000_000_000,
        });
        let decoded = decode(&encode(&original).unwrap()).unwrap();
        assert_eq!(decoded, original);
        println!("✅ HandshakeResp: encode → decode");
    }

    #[test]
    fn test_roundtrip_dht_query() {
        let original = ProtocolMessage::DhtQuery(DhtQuery {
            target_node_id: [1; 32],
            requester_node_id: [2; 32],
            max_results: 16,
        });
        let decoded = decode(&encode(&original).unwrap()).unwrap();
        assert_eq!(decoded, original);
        println!("✅ DhtQuery: encode → decode");
    }

    #[test]
    fn test_roundtrip_dht_resp_with_nodes() {
        let node1 = NodeInfo {
            node_id: [1; 32],
            ed25519_pubkey: vec![1; 32],
            address: "192.168.1.100:7777".to_string(),
            tier: NodeTier::Super,
            rating: 95.5,
        };
        let node2 = NodeInfo {
            node_id: [2; 32],
            ed25519_pubkey: vec![2; 32],
            address: "".to_string(), // Узел без прямого адреса
            tier: NodeTier::Leaf,
            rating: 30.0,
        };

        let original = ProtocolMessage::DhtResp(DhtResp {
            responder_node_id: [9; 32],
            nodes: vec![node1, node2],
        });
        let decoded = decode(&encode(&original).unwrap()).unwrap();
        assert_eq!(decoded, original);
        println!("✅ DhtResp с несколькими узлами: encode → decode");
    }

    #[test]
    fn test_roundtrip_presence_announce() {
        let original = ProtocolMessage::PresenceAnnounce(PresenceAnnounce {
            node: NodeInfo {
                node_id: [1; 32],
                ed25519_pubkey: vec![2; 32],
                address: "10.0.0.5:7777".to_string(),
                tier: NodeTier::Relay,
                rating: 75.0,
            },
            timestamp: 1_700_000_000_000,
            signature: vec![3; 64],
            message_id: [4; 16],
            ttl: 3,
        });
        let decoded = decode(&encode(&original).unwrap()).unwrap();
        assert_eq!(decoded, original);
        println!("✅ PresenceAnnounce: encode → decode");
    }

    #[test]
    fn test_roundtrip_relay_req() {
        let original = ProtocolMessage::RelayReq(RelayReq {
            from_node_id: [1; 32],
            to_node_id: [2; 32],
            payload: vec![7; 1024], // 1KB encrypted payload
            ttl: 10,
            message_id: [5; 16],
        });
        let decoded = decode(&encode(&original).unwrap()).unwrap();
        assert_eq!(decoded, original);
        println!("✅ RelayReq (1KB payload): encode → decode");
    }

    // ── Тесты обработки ошибок ─────────────────────────────────────

    #[test]
    fn test_decode_empty_bytes_error() {
        let result = decode(&[]);
        assert!(matches!(result, Err(ProtocolError::EmptyPacket)));
        println!("✅ Пустой пакет → EmptyPacket");
    }

    #[test]
    fn test_decode_only_version_byte_error() {
        // Только байт версии, без payload — bincode вернёт ошибку
        let bytes = [PROTOCOL_VERSION];
        let result = decode(&bytes);
        assert!(matches!(
            result,
            Err(ProtocolError::DeserializationError(_))
        ));
        println!("✅ Пакет только с версией без payload → DeserializationError");
    }

    #[test]
    fn test_decode_unsupported_version_error() {
        // Версия 0 — меньше MIN_SUPPORTED_VERSION (1)
        let bytes = [0, 1, 2, 3, 4];
        let result = decode(&bytes);
        assert!(matches!(
            result,
            Err(ProtocolError::UnsupportedVersion { got: 0, .. })
        ));
        println!("✅ Версия 0 → UnsupportedVersion");
    }

    #[test]
    fn test_decode_corrupted_payload_error() {
        // Валидная версия + мусор
        let bytes = vec![PROTOCOL_VERSION, 255, 255, 255, 255, 255, 255];
        let result = decode(&bytes);
        assert!(matches!(
            result,
            Err(ProtocolError::DeserializationError(_))
        ));
        println!("✅ Повреждённый payload → DeserializationError");
    }

    #[test]
    fn test_decode_too_large_packet_error() {
        // Создаём "пакет" размером больше максимального
        let huge_bytes = vec![0u8; MAX_PACKET_SIZE + 1];
        let result = decode(&huge_bytes);
        assert!(matches!(result, Err(ProtocolError::PacketTooLarge { .. })));
        println!("✅ Слишком большой пакет → PacketTooLarge");
    }

    #[test]
    fn test_encode_too_large_message_error() {
        // Создаём сообщение с payload > MAX_PACKET_SIZE
        let huge_payload = vec![0u8; MAX_PACKET_SIZE + 100];
        let msg = ProtocolMessage::RelayReq(RelayReq {
            from_node_id: [0; 32],
            to_node_id: [0; 32],
            payload: huge_payload,
            ttl: 10,
            message_id: [0; 16],
        });

        let result = encode(&msg);
        assert!(matches!(result, Err(ProtocolError::PacketTooLarge { .. })));
        println!("✅ Слишком большое сообщение → PacketTooLarge");
    }

    // ── Тесты вспомогательных функций ──────────────────────────────

    #[test]
    fn test_extract_version_from_valid_packet() {
        let msg = sample_text_msg();
        let bytes = encode(&msg).unwrap();
        let version = extract_version(&bytes).unwrap();
        assert_eq!(version, PROTOCOL_VERSION);
        println!("✅ extract_version() из валидного пакета работает");
    }

    #[test]
    fn test_extract_version_from_empty_error() {
        let result = extract_version(&[]);
        assert!(matches!(result, Err(ProtocolError::EmptyPacket)));
        println!("✅ extract_version() из пустого → EmptyPacket");
    }

    // ── Стресс-тесты ───────────────────────────────────────────────

    #[test]
    fn test_encode_decode_many_messages() {
        // Много сообщений разных типов подряд
        let messages: Vec<ProtocolMessage> = (0..100)
            .map(|i| {
                if i % 2 == 0 {
                    ProtocolMessage::Text(TextMsg {
                        msg_id: [i as u8; 16],
                        sender_node_id: [1; 32],
                        recipient_node_id: [2; 32],
                        ratchet_counter: i,
                        nonce: vec![0; 12],
                        ciphertext: vec![i as u8; 50],
                        timestamp: 1_700_000_000_000 + i as u64,
                    })
                } else {
                    ProtocolMessage::Ack(Ack {
                        msg_id: [i as u8; 16],
                        status: AckStatus::Delivered,
                        timestamp: 1_700_000_000_000 + i as u64,
                    })
                }
            })
            .collect();

        for msg in &messages {
            let bytes = encode(msg).unwrap();
            let decoded = decode(&bytes).unwrap();
            assert_eq!(&decoded, msg);
        }
        println!("✅ 100 сообщений: все encode/decode прошли");
    }

    #[test]
    fn test_message_size_reasonable() {
        // TextMsg с типичной нагрузкой должен занимать разумный объём
        let msg = ProtocolMessage::Text(TextMsg {
            msg_id: [1; 16],
            sender_node_id: [2; 32],
            recipient_node_id: [3; 32],
            ratchet_counter: 42,
            nonce: vec![0; 12],
            ciphertext: vec![9; 100], // 100 байт зашифрованных данных
            timestamp: 1_700_000_000_000,
        });

        let bytes = encode(&msg).unwrap();
        // Overhead не должен превышать разумный предел (~200 байт для 100 байт данных)
        assert!(bytes.len() < 300);
        println!(
            "✅ Overhead кодирования разумный: {} байт для 100-байт payload",
            bytes.len()
        );
    }
}
