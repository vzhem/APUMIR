//! # K4-1: presence «своим» и редкий маяк вместо рассылки всем
//!
//! Проблема (этап K4 из `docs/CORE_ROADMAP.md`): в общий топик брокера
//! `p2pm2/presence/<id>` каждый узел публикует своё объявление раз в минуту,
//! и его читают ВСЕ. При 1 000+ узлах каждый телефон получает все чужие
//! объявления (линейный рост трафика у каждого), хотя нужны ему только
//! «свои» — контакты и участники его групп.
//!
//! Что делает этот модуль:
//!
//! - помнит список «своих» (`node_id`), которых просит приложение через
//!   `set_presence_audience` и/или которых ядро запомнило само (переписка);
//! - в маленькой сети (меньше [`SCALING_MIN_PEERS`] узлов) НИЧЕГО не меняет:
//!   режим [`PresenceMode::Broadcast`] — presence идёт в общий топик раз в
//!   минуту, как раньше (телефоны владельца и старые сборки видят старое
//!   поведение);
//! - в большой сети ([`PresenceMode::Scoped`]) объявление в общий топик
//!   уходит редко — это «маяк» раз в [`BEACON_INTERVAL_MS`]. Он остаётся
//!   retained: новый узел, подписавшись, сразу получает последний адрес и
//!   может дозвониться напрямую, не дожидаясь маяка;
//! - «своим» presence уходит лично, прямо по QUIC ([`DIRECT_PRESENCE_TAG`]),
//!   раз в [`OWN_INTERVAL_MS`] — без брокера, без общего эфира.
//!
//! Формат личного кадра — текстовая строка «тег|поля», первое поле не
//! `pk_…`, поэтому старые сборки молча отбрасывают её так же, как чужие
//! кадры (проверка отправителя в `handle_direct_frame`). Ничего в
//! существующих форматах не меняется — правило N ↔ N-1 соблюдено.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicI64, AtomicUsize, Ordering};
use std::sync::Mutex;

/// Как часто объявление уходит в общий топик в режиме «своим» (маяк).
pub const BEACON_INTERVAL_MS: i64 = 600_000; // 10 минут

/// Как часто личный presence уходит «своим» по прямому каналу.
pub const OWN_INTERVAL_MS: i64 = 60_000; // 1 минута

/// Сколько «своих» помним (защита от неограниченного роста).
pub const MAX_OWN: usize = 256;

/// Сколько «своих» обслуживаем за один круг (у остальных будет свой круг).
pub const MAX_OWN_PER_ROUND: usize = 32;

/// С какого числа узлов в сети включается режим «своим».
///
/// Маленькая сеть (телефоны владельца, первые пользователи) работает ровно
/// как до K4: это важно и для старых сборок, и для проверки на телефонах —
/// там нечего ломать.
pub const SCALING_MIN_PEERS: usize = 50;

/// Первое поле личного кадра presence в прямом канале.
pub const DIRECT_PRESENCE_TAG: &str = "ppres";

/// Начало личного кадра presence вместе с разделителем — по нему кадр
/// узнаётся в общем потоке прямого канала без разбора строки.
pub const DIRECT_PRESENCE_PREFIX: &str = "ppres|";

// ═══════════════════════════════════════════════════════════════════
// РЕЖИМ ПРИСУТСТВИЯ
// ═══════════════════════════════════════════════════════════════════

/// Как рассылаем presence.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PresenceMode {
    /// Как раньше: в общий топик раз в минуту (маленькая сеть).
    Broadcast,
    /// «Своим» лично, остальным — редкий маяк (большая сеть).
    Scoped,
}

impl PresenceMode {
    /// Режим по числу узлов, которых знает узел.
    pub fn for_peers(peer_count: usize) -> Self {
        if peer_count >= SCALING_MIN_PEERS {
            PresenceMode::Scoped
        } else {
            PresenceMode::Broadcast
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ЛИЧНЫЙ PRESENCE (прямой канал)
// ═══════════════════════════════════════════════════════════════════

/// Разобранный личный presence из прямого канала.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DirectPresence {
    pub node_id: String,
    pub display_name: String,
    pub addr: Option<SocketAddr>,
    pub is_relay: bool,
    pub version: u32,
    pub sent_at_ms: i64,
}

/// Собрать личный кадр presence.
///
/// «|» в имени заменяем на пробел: имя приходит от пользователя, а формат
/// кадра разделён этим символом (та же защита, что у presence в брокере).
pub fn direct_presence_payload(
    node_id: &str,
    display_name: &str,
    addr: Option<&str>,
    is_relay: bool,
    now_ms: i64,
) -> String {
    let safe_name = display_name.replace('|', " ");
    format!(
        "{}|{}|{}|{}|{}|{}|{}",
        DIRECT_PRESENCE_TAG,
        node_id,
        safe_name,
        addr.unwrap_or(""),
        if is_relay { "relay" } else { "client" },
        crate::config::defaults::PRESENCE_VERSION,
        now_ms
    )
}

/// Разобрать личный кадр presence. `None` — не наш кадр или он битый.
pub fn parse_direct_presence(payload: &str) -> Option<DirectPresence> {
    let parts: Vec<&str> = payload.splitn(7, '|').collect();
    if parts.len() < 7 || parts[0] != DIRECT_PRESENCE_TAG {
        return None;
    }
    // Отправитель обязан быть узлом (pk_…): тот же страж, что и у сообщений,
    // иначе в списке появлялись бы «контакты-призраки» с именем тега.
    if !parts[1].starts_with("pk_") {
        return None;
    }
    let version: u32 = parts[5].trim().parse().ok()?;
    let sent_at_ms: i64 = parts[6].trim().parse().ok()?;
    let addr = match parts[3].trim() {
        "" => None,
        raw => raw.parse::<SocketAddr>().ok(),
    };
    Some(DirectPresence {
        node_id: parts[1].to_string(),
        display_name: parts[2].to_string(),
        addr,
        is_relay: parts[4].trim() == "relay",
        version,
        sent_at_ms,
    })
}

// ═══════════════════════════════════════════════════════════════════
// PRESENCE SCOPE
// ═══════════════════════════════════════════════════════════════════

/// Кто для нас «свой» и когда что рассылать.
///
/// Все замки — обычные (не async): секции короткие, внутри нет `await`,
/// поэтому вызывать можно и из цикла брокера, и из FFI, и из отдельного
/// потока личного presence.
pub struct PresenceScope {
    /// «Свои»: node_id контактов и участников моих групп.
    ///
    /// Отсортировано, без дублей: и поиск, и порядок обхода - по одному
    /// замку, гонки «двое добавили одного» нет.
    own: Mutex<Vec<String>>,
    /// Указатель круга: с кого продолжать рассылку личного presence.
    cursor: AtomicUsize,
    /// Когда последний раз уходил маяк в общий топик (мс, 0 = никогда).
    last_beacon_ms: AtomicI64,
    /// Когда последний раз уходил личный presence «своим» (мс, 0 = никогда).
    last_own_ms: AtomicI64,
}

impl PresenceScope {
    pub fn new() -> Self {
        Self {
            own: Mutex::new(Vec::new()),
            cursor: AtomicUsize::new(0),
            last_beacon_ms: AtomicI64::new(0),
            last_own_ms: AtomicI64::new(0),
        }
    }

    /// Заменить список «своих» (вызов приложения: контакты и участники групп).
    /// Возвращает, сколько принято. Себя из списка выбрасываем.
    pub fn set_own(&self, ids: Vec<String>, our_id: Option<&str>) -> usize {
        let mut unique: Vec<String> = Vec::new();
        for raw in ids {
            let id = raw.trim().to_string();
            if id.is_empty() || Some(id.as_str()) == our_id || !id.starts_with("pk_") {
                continue;
            }
            if unique.len() >= MAX_OWN {
                break;
            }
            if !unique.iter().any(|known| known == &id) {
                unique.push(id);
            }
        }
        unique.sort();
        let count = unique.len();
        *self.own.lock().unwrap() = unique;
        self.cursor.store(0, Ordering::Relaxed);
        count
    }

    /// Запомнить «своего» (узел, с которым у нас есть переписка).
    /// `true` — запись новая; `false` — уже был или список полон.
    pub fn add_own(&self, node_id: &str, our_id: Option<&str>) -> bool {
        let id = node_id.trim();
        if id.is_empty() || Some(id) == our_id || !id.starts_with("pk_") {
            return false;
        }
        let mut own = self.own.lock().unwrap();
        if own.len() >= MAX_OWN {
            return false;
        }
        match own.binary_search_by(|known| known.as_str().cmp(id)) {
            Ok(_) => false,
            Err(position) => {
                own.insert(position, id.to_string());
                true
            }
        }
    }

    /// Сколько «своих» знаем.
    pub fn own_len(&self) -> usize {
        self.own.lock().unwrap().len()
    }

    /// Весь список «своих» (копия). Для срочного рассылания: когда
    /// поменялся СОБСТВЕННЫЙ адрес, personal presence со свежим адресом
    /// должен уйти ВСЕМ «своим» сразу, без 32-пакетного круга.
    /// Курсор не трогаем — обычный цикл продолжит со своей позиции.
    pub fn all_own(&self) -> Vec<String> {
        self.own.lock().unwrap().clone()
    }

    /// «Свой» ли узел.
    pub fn is_own(&self, node_id: &str) -> bool {
        self.own
            .lock()
            .unwrap()
            .binary_search_by(|known| known.as_str().cmp(node_id))
            .is_ok()
    }

    /// Очередная порция «своих» для личного presence (с вращением круга,
    /// чтобы за несколько кругов получить успели все).
    pub fn next_batch(&self, limit: usize) -> Vec<String> {
        let own = self.own.lock().unwrap();
        if own.is_empty() || limit == 0 {
            return Vec::new();
        }
        let take = limit.min(own.len());
        let start = self.cursor.load(Ordering::Relaxed) % own.len();
        let mut out = Vec::with_capacity(take);
        for step in 0..take {
            out.push(own[(start + step) % own.len()].clone());
        }
        self.cursor
            .store((start + take) % own.len(), Ordering::Relaxed);
        out
    }

    /// Пора публиковать маяк в общий топик?
    pub fn beacon_due(&self, now_ms: i64) -> bool {
        Self::due(
            self.last_beacon_ms.load(Ordering::Relaxed),
            now_ms,
            BEACON_INTERVAL_MS,
        )
    }

    /// Отметить, что маяк ушёл.
    pub fn mark_beacon(&self, now_ms: i64) {
        self.last_beacon_ms.store(now_ms, Ordering::Relaxed);
    }

    /// Пора рассылать личный presence «своим»?
    pub fn own_due(&self, now_ms: i64) -> bool {
        Self::due(
            self.last_own_ms.load(Ordering::Relaxed),
            now_ms,
            OWN_INTERVAL_MS,
        )
    }

    /// Отметить, что личный presence разошёлся.
    pub fn mark_own(&self, now_ms: i64) {
        self.last_own_ms.store(now_ms, Ordering::Relaxed);
    }

    /// Общее правило расписания: 0 = «ещё ни разу» → пора; часы перевели
    /// назад → тоже пора (лучше лишнее объявление, чем молчание).
    fn due(last_ms: i64, now_ms: i64, interval_ms: i64) -> bool {
        if last_ms <= 0 || now_ms < last_ms {
            return true;
        }
        now_ms - last_ms >= interval_ms
    }
}

impl Default for PresenceScope {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mode_switches_only_in_a_big_network() {
        assert_eq!(PresenceMode::for_peers(0), PresenceMode::Broadcast);
        assert_eq!(
            PresenceMode::for_peers(SCALING_MIN_PEERS - 1),
            PresenceMode::Broadcast
        );
        assert_eq!(
            PresenceMode::for_peers(SCALING_MIN_PEERS),
            PresenceMode::Scoped
        );
    }

    #[test]
    fn own_list_dedupes_sorts_and_skips_self() {
        let scope = PresenceScope::new();
        let taken = scope.set_own(
            vec![
                "pk_b".to_string(),
                "pk_a".to_string(),
                "pk_a".to_string(),
                "pk_me".to_string(),
                "  ".to_string(),
            ],
            Some("pk_me"),
        );
        assert_eq!(taken, 2);
        assert_eq!(scope.own_len(), 2);
        assert!(scope.is_own("pk_a"));
        assert!(!scope.is_own("pk_me"));
        assert_eq!(scope.next_batch(1), vec!["pk_a".to_string()]);
        assert_eq!(scope.next_batch(1), vec!["pk_b".to_string()]);
        assert_eq!(scope.next_batch(1), vec!["pk_a".to_string()]);
    }

    #[test]
    fn direct_presence_round_trip() {
        let payload = direct_presence_payload(
            "pk_peer",
            "Вла|димир",
            Some("203.0.113.7:7777"),
            true,
            1_700_000_000_000,
        );
        let parsed = parse_direct_presence(&payload).expect("кадр должен разбираться");
        assert_eq!(parsed.node_id, "pk_peer");
        assert_eq!(parsed.display_name, "Вла димир");
        assert_eq!(
            parsed.addr,
            Some("203.0.113.7:7777".parse::<SocketAddr>().unwrap())
        );
        assert!(parsed.is_relay);
        assert_eq!(parsed.sent_at_ms, 1_700_000_000_000);
        // Чужая строка и строка без узла не разбираются.
        assert!(parse_direct_presence("привет|мир").is_none());
        assert!(parse_direct_presence("ppres|не_узел|имя||client|2|1").is_none());
    }

    #[test]
    fn own_list_is_bounded() {
        let scope = PresenceScope::new();
        for i in 0..(MAX_OWN + 10) {
            scope.add_own(&format!("pk_{:04}", i), None);
        }
        assert!(scope.own_len() <= MAX_OWN);
    }

    #[test]
    fn all_own_returns_copy_without_touching_cursor() {
        let scope = PresenceScope::new();
        scope.set_own(
            vec!["pk_aaa".into(), "pk_bbb".into(), "pk_ccc".into()],
            Some("pk_aaa"),
        );
        // «Я» (pk_aaa) отфильтрован set_own: в списке два других.
        assert_eq!(scope.own_len(), 2);
        let all = scope.all_own();
        assert_eq!(all, vec!["pk_bbb".to_string(), "pk_ccc".to_string()]);
        // Повторный вызов — та же копия (список не истрачивается).
        assert_eq!(scope.all_own(), all);
    }
}
