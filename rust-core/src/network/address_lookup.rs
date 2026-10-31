//! # K4-2: поиск адреса по nodeId без брокера (DHT наружу)
//!
//! Проблема (этап K4 из `docs/CORE_ROADMAP.md`): адрес узла сегодня узнаётся
//! только из общего топика брокера (`p2pm2/presence/…`). В большой сети этот
//! путь специально становится редким маяком (K4-1), а если брокер недоступен -
//! адреса нет вовсе, и связаться можно только по Wi-Fi (mDNS).
//!
//! Что делает этот модуль:
//!
//! - задаёт два текстовых кадра прямого канала: вопрос
//!   `dhtq|кто_спрашивает|кого_ищем` и ответ
//!   `dhtr|кто_ответил|кого_искали|адрес|id@адрес|…`;
//! - помнит, кого мы уже спрашивали ([`AddressLookup::should_ask`] - не чаще
//!   раза в [`LOOKUP_COOLDOWN_MS`]), чтобы поиск не превратился в поток
//!   вопросов;
//! - держит очередь ответов: их отправляет поток presence (у него есть
//!   рукоятка транспорта), а не приёмник кадров - блокирующая отправка не
//!   должна занимать рабочий поток runtime;
//! - сортирует «ближайших» по XOR-расстоянию (`dht::xor_distance`) - это и
//!   есть выход DHT наружу: сначала спрашиваем тех, кто по метрике ближе к
//!   цели, они отвечают адресом или следующим кольцом соседей.
//!
//! Запасной путь остаётся: если адрес так и не нашёлся, узел, как и раньше,
//! узнаёт его из общего топика брокера (маяк K4-1) или по mDNS в своей сети.
//!
//! Формат кадров - текст, первое поле не `pk_…`, поэтому старые сборки
//! отбрасывают их тем же стражем, что и любой чужой кадр (правило N ↔ N-1).

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Mutex;

use crate::network::dht::{xor_distance, NodeIdBytes, NODE_ID_LEN};

/// Первое поле кадра-вопроса.
pub const LOOKUP_QUERY_TAG: &str = "dhtq";

/// Первое поле кадра-ответа.
pub const LOOKUP_REPLY_TAG: &str = "dhtr";

/// Начало кадра-вопроса вместе с разделителем - по нему кадр узнаётся в общем
/// потоке прямого канала без разбора строки.
pub const LOOKUP_QUERY_PREFIX: &str = "dhtq|";

/// Начало кадра-ответа вместе с разделителем.
pub const LOOKUP_REPLY_PREFIX: &str = "dhtr|";

/// Сколько «ближайших» соседей влезает в один ответ.
pub const MAX_CLOSER_NODES: usize = 3;

/// Скольким соседям задаём один и тот же вопрос.
pub const MAX_ASK_PEERS: usize = 3;

/// Не чаще одного вопроса про одну и ту же цель за это время.
pub const LOOKUP_COOLDOWN_MS: i64 = 300_000; // 5 минут

/// Сколько ответов помним до отправки (защита от роста очереди).
pub const MAX_QUEUED_REPLIES: usize = 64;

/// Сколько целей помним в расписании вопросов.
pub const MAX_TRACKED_TARGETS: usize = 256;

// ═══════════════════════════════════════════════════════════════════
// РАЗБОР КАДРОВ
// ═══════════════════════════════════════════════════════════════════

/// Вопрос «где узел `target_id`?».
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AddressQuery {
    pub from_id: String,
    pub target_id: String,
}

/// Ответ: адрес цели, если его знает ответивший, и/или ближайшие к цели узлы,
/// адреса которых он знает.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AddressReply {
    pub from_id: String,
    pub target_id: String,
    pub addr: Option<SocketAddr>,
    pub closer: Vec<(String, SocketAddr)>,
}

/// Собрать кадр-вопрос.
pub fn query_payload(from_id: &str, target_id: &str) -> String {
    format!("{}|{}|{}", LOOKUP_QUERY_TAG, from_id, target_id)
}

/// Разобрать кадр-вопрос. `None` - не наш кадр или он битый.
pub fn parse_query(payload: &str) -> Option<AddressQuery> {
    let parts: Vec<&str> = payload.splitn(3, '|').collect();
    if parts.len() != 3 || parts[0] != LOOKUP_QUERY_TAG {
        return None;
    }
    // Оба поля - узлы (`pk_…`): тот же страж, что и у сообщений и presence.
    if !parts[1].starts_with("pk_") || !parts[2].starts_with("pk_") {
        return None;
    }
    Some(AddressQuery {
        from_id: parts[1].to_string(),
        target_id: parts[2].to_string(),
    })
}

/// Собрать кадр-ответ. Пустой адрес означает «сам не знаю, вот кто ближе».
pub fn reply_payload(
    from_id: &str,
    target_id: &str,
    addr: Option<SocketAddr>,
    closer: &[(String, SocketAddr)],
) -> String {
    reply_payload_multi(from_id, target_id, addr.into_iter().collect(), closer)
}

/// То же, но адресов цели может быть два — по одному на стек.
///
/// Первый адрес уходит в своё прежнее поле, второй — добавочным элементом
/// списка «ближайших» с идентификатором самой цели. Старый разборщик
/// (`parse_reply`) видит в этом списке запись `id@addr`, проверяет
/// `id.starts_with("pk_")` и кладёт её в `closer`; движок берёт из `closer`
/// адреса только для узлов, которых ещё нет в памяти, поэтому старый телефон
/// просто не станет пробовать второй адрес. Новое поле ничего не ломает —
/// ровно та совместимость, которой не даёт новое обязательное поле в
/// `AddressClaims` (`deny_unknown_fields`).
pub fn reply_payload_multi(
    from_id: &str,
    target_id: &str,
    addrs: Vec<SocketAddr>,
    closer: &[(String, SocketAddr)],
) -> String {
    let addrs = merge_addrs(addrs);
    let mut closer_text = closer
        .iter()
        .take(MAX_CLOSER_NODES)
        .map(|(id, node_addr)| format!("{}@{}", id, node_addr))
        .collect::<Vec<String>>();
    // Второй адрес цели — в конце списка: если соседей уже три, он не влезет.
    // Второй стек тогда потеряется, но кадр останется прежнего размера, а
    // первый адрес всё равно едет в своём поле.
    if let Some(second) = addrs.get(1) {
        // Именно в начало: `parse_reply` держит только три записи кольца, и
        // второй адрес самой цели полезнее третьего соседа.
        closer_text.insert(0, format!("{}@{}", target_id, second));
    }
    format!(
        "{}|{}|{}|{}|{}",
        LOOKUP_REPLY_TAG,
        from_id,
        target_id,
        addrs.first().map(|a| a.to_string()).unwrap_or_default(),
        closer_text.join("|")
    )
}

/// Разобрать кадр-ответ. `None` - не наш кадр или он битый.
pub fn parse_reply(payload: &str) -> Option<AddressReply> {
    let parts: Vec<&str> = payload.splitn(5, '|').collect();
    if parts.len() < 4 || parts[0] != LOOKUP_REPLY_TAG {
        return None;
    }
    if !parts[1].starts_with("pk_") || !parts[2].starts_with("pk_") {
        return None;
    }
    let addr = match parts[3].trim() {
        "" => None,
        raw => Some(raw.parse::<SocketAddr>().ok()?),
    };
    let mut closer = Vec::new();
    if parts.len() == 5 {
        for item in parts[4].split('|') {
            if closer.len() >= MAX_CLOSER_NODES {
                break;
            }
            let mut fields = item.splitn(2, '@');
            let (Some(id), Some(raw_addr)) = (fields.next(), fields.next()) else {
                continue;
            };
            if !id.starts_with("pk_") {
                continue;
            }
            if let Ok(node_addr) = raw_addr.trim().parse::<SocketAddr>() {
                closer.push((id.to_string(), node_addr));
            }
        }
    }
    Some(AddressReply {
        from_id: parts[1].to_string(),
        target_id: parts[2].to_string(),
        addr,
        closer,
    })
}

// ═══════════════════════════════════════════════════════════════════
// XOR-РАССТОЯНИЕ (выход DHT наружу)
// ═══════════════════════════════════════════════════════════════════

/// 32 байта идентификатора из строки `pk_<64 hex>`.
///
/// Тот же смысл, что у `dht::NodeIdBytes`: идентификатор узла в сети.
pub fn node_id_bytes(node_id: &str) -> Option<NodeIdBytes> {
    let hex = node_id.strip_prefix("pk_")?;
    if hex.len() != NODE_ID_LEN * 2 {
        return None;
    }
    let bytes = hex.as_bytes();
    let mut out = [0u8; NODE_ID_LEN];
    for (index, slot) in out.iter_mut().enumerate() {
        let pair = std::str::from_utf8(&bytes[index * 2..index * 2 + 2]).ok()?;
        *slot = u8::from_str_radix(pair, 16).ok()?;
    }
    Some(out)
}

/// Отсортировать известные узлы по XOR-расстоянию до цели: ближние - первыми.
///
/// Если идентификатор цели не разбирается (чужой формат), порядок остаётся
/// прежним - лучше отдать как есть, чем не отдать ничего.
pub fn sort_closer(target_id: &str, candidates: Vec<(String, SocketAddr)>) -> Vec<(String, SocketAddr)> {
    let Some(target) = node_id_bytes(target_id) else {
        return candidates;
    };
    let mut with_distance: Vec<(NodeIdBytes, (String, SocketAddr))> = candidates
        .into_iter()
        .filter_map(|node| node_id_bytes(&node.0).map(|bytes| (bytes, node)))
        .collect();
    with_distance.sort_by(|left, right| {
        xor_distance(&left.0, &target).cmp(&xor_distance(&right.0, &target))
    });
    with_distance.into_iter().map(|(_, node)| node).collect()
}

// ═══════════════════════════════════════════════════════════════════
// ОБА СТЕКА В ОДНОМ ОТВЕТЕ
// ═══════════════════════════════════════════════════════════════════

/// Сколько адресов одного узла влезает в кадр.
///
/// Два — это ровно «IPv6 и IPv4». Больше не нужно: у узла не так много
/// публичных адресов, а каждый лишний — попытка подключения, которая может
/// закончиться таймаутом.
pub const MAX_ADDRS_PER_NODE: usize = 2;

/// Слить известные адреса узла в один список: лучший (IPv6) и запасной (IPv4).
///
/// Порядок задаёт [`crate::network::dual_stack::rank`] — тот же порядок, что и
/// везде в приложении. Дубликаты (в том числе v4-mapped) убираются: один и тот
/// же адрес дважды только съест бюджет попытки.
pub fn merge_addrs(mut addrs: Vec<SocketAddr>) -> Vec<SocketAddr> {
    addrs = addrs
        .into_iter()
        .map(crate::network::dual_stack::canonical)
        .filter(|addr| crate::network::dual_stack::rank(addr.ip()).is_some())
        .collect();
    addrs.sort_by_key(|addr| {
        (
            crate::network::dual_stack::rank(addr.ip()).unwrap_or(u8::MAX),
            *addr,
        )
    });
    addrs.dedup();
    addrs.truncate(MAX_ADDRS_PER_NODE);
    addrs
}

/// Какие из кандидатов пробовать **после** подтверждённого пути.
///
/// Совет «оба стека» легко превратить в лишние таймауты: если узел за NAT,
/// его IPv4 из подсказок — приватный адрес чужой сети, и каждая отправка
/// сначала ждала бы его. Правило: подтверждённый адрес идёт первым, кандидаты
/// запоминаются только как запасной путь, и пробуются, лишь когда основной не
/// ответил (это делает
/// [`crate::network::direct_transport::DirectTransport::set_alternatives`]).
pub fn fallback_addrs(confirmed: Option<SocketAddr>, candidates: Vec<SocketAddr>) -> Vec<SocketAddr> {
    let merged = merge_addrs(candidates);
    match confirmed {
        None => merged,
        Some(confirmed) => {
            let confirmed = crate::network::dual_stack::canonical(confirmed);
            let mut out = vec![confirmed];
            for addr in merged {
                if addr != confirmed && !out.contains(&addr) {
                    out.push(addr);
                }
            }
            out.truncate(MAX_ADDRS_PER_NODE);
            out
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// СОСТОЯНИЕ ПОИСКА
// ═══════════════════════════════════════════════════════════════════

/// Очередь подготовленных ответов и расписание вопросов.
///
/// Замки обычные (не async): секции короткие, внутри нет `await`. Пишет сюда
/// приёмник кадров, читает - поток presence.
#[derive(Default)]
pub struct AddressLookup {
    /// Готовые ответы «кому и что отправить».
    outbox: Mutex<Vec<(String, Vec<u8>)>>,
    /// Когда про какую цель спрашивали в последний раз (мс).
    asked: Mutex<HashMap<String, i64>>,
    /// Replacement of a failed/stale route is allowed only in an active query to these neighbours.
    refresh: Mutex<HashMap<String, (Vec<String>, i64)>>,
}

impl AddressLookup {
    pub fn new() -> Self {
        Self {
            outbox: Mutex::new(Vec::new()),
            asked: Mutex::new(HashMap::new()),
            refresh: Mutex::new(HashMap::new()),
        }
    }

    /// Положить ответ в очередь. `false` - очередь полна (ответ потерян).
    pub fn queue_reply(&self, peer_id: &str, payload: String) -> bool {
        let mut outbox = self.outbox.lock().unwrap();
        if outbox.len() >= MAX_QUEUED_REPLIES {
            return false;
        }
        outbox.push((peer_id.to_string(), payload.into_bytes()));
        true
    }

    /// Забрать не больше `limit` готовых ответов (в порядке постановки).
    pub fn take_replies(&self, limit: usize) -> Vec<(String, Vec<u8>)> {
        let mut outbox = self.outbox.lock().unwrap();
        let take = limit.min(outbox.len());
        outbox.drain(..take).collect()
    }

    /// Сколько ответов ждёт отправки.
    pub fn queued_len(&self) -> usize {
        self.outbox.lock().unwrap().len()
    }

    /// Пора спросить про эту цель? Кулдаун - [`LOOKUP_COOLDOWN_MS`]; часы
    /// перевели назад - спрашиваем (лучше лишний вопрос, чем молчание).
    pub fn should_ask(&self, target_id: &str, now_ms: i64) -> bool {
        let asked = self.asked.lock().unwrap();
        match asked.get(target_id) {
            None => true,
            Some(previous) => {
                now_ms < *previous
                    || now_ms.saturating_sub(*previous) >= LOOKUP_COOLDOWN_MS
            }
        }
    }

    /// Отметить, что про эту цель спросили.
    pub fn mark_asked(&self, target_id: &str, now_ms: i64) {
        let mut asked = self.asked.lock().unwrap();
        if asked.len() >= MAX_TRACKED_TARGETS {
            // Сначала выбрасываем тех, у кого кулдаун уже истёк.
            let stale: Vec<String> = asked
                .iter()
                .filter(|(_, previous)| now_ms.saturating_sub(**previous) >= LOOKUP_COOLDOWN_MS)
                .map(|(id, _)| id.clone())
                .collect();
            for id in stale {
                asked.remove(&id);
            }
            if asked.len() >= MAX_TRACKED_TARGETS {
                return;
            }
        }
        asked.insert(target_id.to_string(), now_ms);
    }

    pub fn request_refresh_from(&self, target: &str, peers: Vec<String>, now: i64) {
        let mut refresh = self.refresh.lock().unwrap();
        refresh.retain(|_, (_, until)| *until > now);
        if refresh.len() >= MAX_TRACKED_TARGETS && !refresh.contains_key(target) { return; }
        refresh.insert(target.to_string(), (peers.into_iter().take(MAX_ASK_PEERS).collect(),
            now.saturating_add(LOOKUP_COOLDOWN_MS)));
    }

    pub fn may_refresh_from(&self, target: &str, responder: &str, now: i64) -> bool {
        self.refresh.lock().unwrap().get(target).map(|(peers, until)|
            now < *until && peers.iter().any(|id| id == responder)
        ).unwrap_or(false)
    }

    /// Адрес нашёлся (или цель пропала) - из расписания убираем.
    pub fn forget(&self, target_id: &str) {
        self.asked.lock().unwrap().remove(target_id);
        self.refresh.lock().unwrap().remove(target_id);
    }

    /// Сколько целей в расписании.
    pub fn tracked_len(&self) -> usize {
        self.asked.lock().unwrap().len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn addr(text: &str) -> SocketAddr {
        text.parse::<SocketAddr>().unwrap()
    }

    fn node(seed: u8) -> String {
        format!("pk_{:064x}", seed)
    }

    #[test]
    fn query_round_trip() {
        let payload = query_payload(&node(1), &node(2));
        assert_eq!(payload, format!("dhtq|{}|{}", node(1), node(2)));
        let parsed = parse_query(&payload).expect("вопрос должен разбираться");
        assert_eq!(parsed.from_id, node(1));
        assert_eq!(parsed.target_id, node(2));
        // Чужая строка и строка без узлов не разбираются.
        assert!(parse_query("привет|мир").is_none());
        assert!(parse_query("dhtq|не_узел|pk_1").is_none());
    }

    #[test]
    fn reply_round_trip_keeps_address_and_closer_nodes() {
        let closer = vec![
            (node(7), addr("203.0.113.7:7777")),
            (node(8), addr("203.0.113.8:7777")),
        ];
        let payload = reply_payload(&node(1), &node(2), Some(addr("198.51.100.5:7777")), &closer);
        let parsed = parse_reply(&payload).expect("ответ должен разбираться");
        assert_eq!(parsed.target_id, node(2));
        assert_eq!(parsed.addr, Some(addr("198.51.100.5:7777")));
        assert_eq!(parsed.closer, closer);
        // Пустой адрес - законный ответ «сам не знаю».
        let without = reply_payload(&node(1), &node(2), None, &[]);
        let parsed = parse_reply(&without).expect("ответ должен разбираться");
        assert_eq!(parsed.addr, None);
        assert!(parsed.closer.is_empty());
        assert!(parse_reply("dhtr|не_узел|не_узел|x|").is_none());
    }

    #[test]
    fn reply_carries_both_stacks_and_stays_readable_by_the_old_shape() {
        // Часть 4: у цели два адреса — IPv6 и IPv4. Первый едет в своё поле,
        // второй — записью `target@адрес` в начале кольца, потому что
        // `parse_reply` держит только три записи.
        let payload = reply_payload_multi(
            &node(1),
            &node(2),
            vec![addr("[2a00:1450:4001::1]:7777"), addr("198.51.100.5:7777")],
            &[],
        );
        let parsed = parse_reply(&payload).expect("ответ должен разбираться");
        assert_eq!(parsed.addr, Some(addr("[2a00:1450:4001::1]:7777")), "IPv6 первым");
        assert_eq!(
            parsed.closer,
            vec![(node(2), addr("198.51.100.5:7777"))],
            "второй стек цели виден в кольце с её же идентификатором"
        );
        // Три настоящих соседа не вытесняют второй адрес цели.
        let closer = vec![
            (node(7), addr("203.0.113.7:7777")),
            (node(8), addr("203.0.113.8:7777")),
            (node(9), addr("203.0.113.9:7777")),
        ];
        let payload = reply_payload_multi(
            &node(1),
            &node(2),
            vec![addr("198.51.100.5:7777"), addr("[2a00:1450:4001::1]:7777")],
            &closer,
        );
        let parsed = parse_reply(&payload).expect("ответ должен разбираться");
        assert_eq!(parsed.addr, Some(addr("[2a00:1450:4001::1]:7777")), "IPv6 всё равно первым");
        assert_eq!(parsed.closer.first(), Some(&(node(2), addr("198.51.100.5:7777"))));
        // Один адрес — прежний формат, без добавочной записи.
        let single = reply_payload_multi(&node(1), &node(2), vec![addr("198.51.100.5:7777")], &[]);
        assert_eq!(parse_reply(&single).unwrap().closer, Vec::new());
    }

    #[test]
    fn merge_addrs_prefers_ipv6_drops_duplicates_and_mapped() {
        let merged = merge_addrs(vec![
            addr("198.51.100.5:7777"),
            addr("[::ffff:198.51.100.5]:7777"),
            addr("[2a00:1450:4001::1]:7777"),
            addr("127.0.0.1:7777"),
        ]);
        assert_eq!(
            merged,
            vec![addr("[2a00:1450:4001::1]:7777"), addr("198.51.100.5:7777")],
            "v6 первым, v4-mapped слит с IPv4, случайные адреса выброшены"
        );
        assert_eq!(merged.len(), MAX_ADDRS_PER_NODE);
    }

    #[test]
    fn fallback_addrs_keep_the_confirmed_path_first() {
        // Подтверждённый IPv4-путь не должен прятаться за чужим IPv6.
        let out = fallback_addrs(
            Some(addr("198.51.100.5:7777")),
            vec![addr("[2a00:1450:4001::1]:7777"), addr("198.51.100.5:7777")],
        );
        assert_eq!(out.first(), Some(&addr("198.51.100.5:7777")));
        assert_eq!(out.len(), 2);
        // Ничего не подтверждено — обычный порядок предпочтения.
        let out = fallback_addrs(None, vec![addr("198.51.100.5:7777"), addr("[2a00:1450:4001::1]:7777")]);
        assert_eq!(out.first(), Some(&addr("[2a00:1450:4001::1]:7777")));
    }

    #[test]
    fn closer_nodes_are_sorted_by_xor_distance() {
        let far = (node(0xff), addr("203.0.113.9:7777"));
        let near = (node(0x01), addr("203.0.113.1:7777"));
        let sorted = sort_closer(&node(0x01), vec![far.clone(), near.clone()]);
        assert_eq!(sorted[0], near);
        assert_eq!(sorted[1], far);
    }

    #[test]
    fn asking_respects_cooldown_and_can_be_forgotten() {
        let lookup = AddressLookup::new();
        assert!(lookup.should_ask(&node(5), 1_000));
        lookup.mark_asked(&node(5), 1_000);
        assert!(!lookup.should_ask(&node(5), 1_000 + LOOKUP_COOLDOWN_MS - 1));
        assert!(lookup.should_ask(&node(5), 1_000 + LOOKUP_COOLDOWN_MS));
        lookup.forget(&node(5));
        assert!(lookup.should_ask(&node(5), 1_000));
        assert_eq!(lookup.tracked_len(), 0);
    }

    #[test]
    fn replies_are_queued_in_order_and_bounded() {
        let lookup = AddressLookup::new();
        assert!(lookup.queue_reply(&node(1), "dhtr|a|b||".to_string()));
        let taken = lookup.take_replies(4);
        assert_eq!(taken.len(), 1);
        assert_eq!(taken[0].0, node(1));
        assert_eq!(taken[0].1, b"dhtr|a|b||".to_vec());
        assert_eq!(lookup.queued_len(), 0);
        for index in 0..(MAX_QUEUED_REPLIES + 5) {
            lookup.queue_reply(&node(1), format!("ответ-{}", index));
        }
        assert_eq!(lookup.queued_len(), MAX_QUEUED_REPLIES);
    }
    #[test]
    fn stale_route_replacement_requires_an_active_query_to_that_responder() {
        let lookup = AddressLookup::new();
        assert!(!lookup.may_refresh_from(&node(1), &node(2), 1000));
        lookup.request_refresh_from(&node(1), vec![node(2)], 1000);
        assert!(lookup.may_refresh_from(&node(1), &node(2), 1001));
        assert!(!lookup.may_refresh_from(&node(1), &node(3), 1001));
        assert!(!lookup.may_refresh_from(&node(1), &node(2), 1000 + LOOKUP_COOLDOWN_MS));
        lookup.forget(&node(1));
        assert!(!lookup.may_refresh_from(&node(1), &node(2), 1001));
    }

}
