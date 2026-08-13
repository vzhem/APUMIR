//! # Mesh Wire Formats — build/parse конвертов mesh
//!
//! Чистая логика (без сети): собирает и разбирает конверты mesh-пересылки.
//! Используется в M3+ (gossip/доставка). См. `docs/MESH_DELIVERY.md`.
//!
//! Форматы (pipe-delimited, тип по первому полю; base64-алфавит не содержит `|`):
//!
//! - `relay|<msg_id>|<recipient>|<origin>|<chat_scope>|<ttl_secs>|<hop>|<e2e_payload_b64>`
//! - `receipt|<msg_id>|<recipient>|<ts>`
//! - `gsumm|<msg_id1>=<rec1>|<msg_id2>=<rec2>|...`  (сводка relay-очереди для gossip)

use base64::Engine;

pub const TAG_RELAY: &str = "relay";
pub const TAG_RECEIPT: &str = "receipt";
pub const TAG_GOSSIP_SUMMARY: &str = "gsumm";

/// Распарсенный mesh-конверт.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MeshEnvelope {
    /// Переносимое сообщение (relay): хранится/пересылается узлами, пока получатель не появится.
    /// `e2e_payload` — decode-нутые байты (relay их НЕ читает; только получатель).
    Relay {
        msg_id: String,
        recipient: String,
        origin: String,
        chat_scope: String,
        ttl_secs: u64,
        hop: u8,
        e2e_payload: Vec<u8>,
    },
    /// Подтверждение получения (получатель → расходится по mesh → cleanup).
    Receipt {
        msg_id: String,
        recipient: String,
        ts: u64,
    },
    /// Сводка relay-очереди узла для gossip-обмена: список `(msg_id, recipient)`.
    GossipSummary {
        items: Vec<(String, String)>,
    },
}

fn b64_encode(bytes: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

fn b64_decode(s: &str) -> Option<Vec<u8>> {
    base64::engine::general_purpose::STANDARD.decode(s).ok()
}

// ── Build ───────────────────────────────────────────────────────────

pub fn build_relay(
    msg_id: &str,
    recipient: &str,
    origin: &str,
    chat_scope: &str,
    ttl_secs: u64,
    hop: u8,
    e2e_payload: &[u8],
) -> String {
    format!(
        "{}|{}|{}|{}|{}|{}|{}|{}",
        TAG_RELAY, msg_id, recipient, origin, chat_scope, ttl_secs, hop, b64_encode(e2e_payload)
    )
}

pub fn build_receipt(msg_id: &str, recipient: &str, ts: u64) -> String {
    format!("{}|{}|{}|{}", TAG_RECEIPT, msg_id, recipient, ts)
}

pub fn build_gossip_summary(items: &[(String, String)]) -> String {
    let mut s = String::from(TAG_GOSSIP_SUMMARY);
    for (mid, rec) in items {
        s.push('|');
        s.push_str(mid);
        s.push('=');
        s.push_str(rec);
    }
    s
}

// ── Parse ───────────────────────────────────────────────────────────

/// Разобрать mesh-конверт. `None` если формат неизвестен/битый.
pub fn parse(payload: &str) -> Option<MeshEnvelope> {
    let payload = payload.trim();
    let mut split = payload.splitn(2, '|');
    let tag = split.next()?;
    let rest = split.next().unwrap_or("");
    match tag {
        TAG_RELAY => parse_relay(rest),
        TAG_RECEIPT => parse_receipt(rest),
        TAG_GOSSIP_SUMMARY => Some(parse_gossip_summary(rest)),
        _ => None,
    }
}

fn parse_relay(rest: &str) -> Option<MeshEnvelope> {
    // 7 полей: msg_id|recipient|origin|chat_scope|ttl|hop|payload_b64
    let f: Vec<&str> = rest.split('|').collect();
    if f.len() != 7 {
        return None;
    }
    let e2e_payload = b64_decode(f[6])?;
    Some(MeshEnvelope::Relay {
        msg_id: f[0].to_string(),
        recipient: f[1].to_string(),
        origin: f[2].to_string(),
        chat_scope: f[3].to_string(),
        ttl_secs: f[4].parse().ok()?,
        hop: f[5].parse().ok()?,
        e2e_payload,
    })
}

fn parse_receipt(rest: &str) -> Option<MeshEnvelope> {
    let f: Vec<&str> = rest.split('|').collect();
    if f.len() != 3 {
        return None;
    }
    Some(MeshEnvelope::Receipt {
        msg_id: f[0].to_string(),
        recipient: f[1].to_string(),
        ts: f[2].parse().ok()?,
    })
}

fn parse_gossip_summary(rest: &str) -> MeshEnvelope {
    let mut items: Vec<(String, String)> = Vec::new();
    for chunk in rest.split('|') {
        if chunk.is_empty() {
            continue;
        }
        let mut kv = chunk.splitn(2, '=');
        if let (Some(mid), Some(rec)) = (kv.next(), kv.next()) {
            items.push((mid.to_string(), rec.to_string()));
        }
    }
    MeshEnvelope::GossipSummary { items }
}

// ── Tests ───────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn relay_round_trip() {
        let payload = b"hello mesh \x00\x01 binary";
        let s = build_relay("m1", "pk_b", "pk_a", "chat-1", 604800, 2, payload);
        assert!(s.starts_with("relay|m1|pk_b|pk_a|chat-1|604800|2|"));
        match parse(&s).unwrap() {
            MeshEnvelope::Relay {
                msg_id,
                recipient,
                origin,
                chat_scope,
                ttl_secs,
                hop,
                e2e_payload,
            } => {
                assert_eq!(msg_id, "m1");
                assert_eq!(recipient, "pk_b");
                assert_eq!(origin, "pk_a");
                assert_eq!(chat_scope, "chat-1");
                assert_eq!(ttl_secs, 604800);
                assert_eq!(hop, 2);
                assert_eq!(e2e_payload, payload);
            }
            _ => panic!("expected Relay"),
        }
    }

    #[test]
    fn receipt_round_trip() {
        let s = build_receipt("m1", "pk_b", 123);
        assert_eq!(s, "receipt|m1|pk_b|123");
        match parse(&s).unwrap() {
            MeshEnvelope::Receipt { msg_id, recipient, ts } => {
                assert_eq!(msg_id, "m1");
                assert_eq!(recipient, "pk_b");
                assert_eq!(ts, 123);
            }
            _ => panic!("expected Receipt"),
        }
    }

    #[test]
    fn gossip_summary_round_trip() {
        let items = vec![("m1".into(), "pk_b".into()), ("m2".into(), "pk_c".into())];
        let s = build_gossip_summary(&items);
        assert_eq!(s, "gsumm|m1=pk_b|m2=pk_c");
        match parse(&s).unwrap() {
            MeshEnvelope::GossipSummary { items: got } => {
                assert_eq!(got, items);
            }
            _ => panic!("expected GossipSummary"),
        }
    }

    #[test]
    fn empty_gossip_summary() {
        let s = build_gossip_summary(&[]);
        assert_eq!(s, "gsumm");
        match parse(&s).unwrap() {
            MeshEnvelope::GossipSummary { items } => assert!(items.is_empty()),
            _ => panic!("expected GossipSummary"),
        }
    }

    #[test]
    fn unknown_tag_returns_none() {
        assert!(parse("weird|stuff|here").is_none());
    }

    #[test]
    fn malformed_relay_returns_none() {
        assert!(parse("relay|m1|pk_b").is_none()); // мало полей
        assert!(parse("relay|m1|pk_b|pk_a|c|x|2|!!!not-base64!!!").is_none()); // битый base64
    }

    #[test]
    fn parse_trims_whitespace() {
        let s = format!("{}\n", build_receipt("m1", "pk_b", 1));
        assert!(parse(&s).is_some());
    }
}
