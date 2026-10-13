//! Азбука адресов: последний адрес + приватная история проверенных адресов.
//!
//! История НЕ рассылается по рою: для фона годятся только адреса, на которых
//! лично состоялся подписанный APUX1-обмен по настоящему QUIC-соединению.
//! Объявления MQTT/DHT и сообщения чужих узлов остаются подсказками, не
//! доказательством живого маршрута. Сохранённый адрес не означает «в сети».
//!
//! Карта — `docs/ADDRESS_BOOK.md` (задача владельца 2026-09-18):
//! все адреса сейчас живут только в памяти (`peer_addrs`), после
//! перезагрузки телефона список пуст — абоненты «теряют друг друга»,
//! пока не пересечутся на общем брокере. Файл рядом с
//! `apu_relay.sqlite` закрывает эту дыру: ядро грузит его при старте
//! (seed в `peer_addrs`), пишет при каждом узнавании адреса (7 точек)
//! и при записи убирает неактуальное (TTL/потолок).
//!
//! Правила жизни:
//! - запись атомарная (tmp + rename) — телефон не застрелится на
//!   полупустом файле;
//! - TTL [`ENTRY_TTL_MS`] — не видели узла месяц, адрес мёртв; вернётся
//!   сам по presence/DHT, когда понадобится;
//! - потолок [`MAX_ENTRIES`] — самые свежие;
//! - save не чаще раза в [`SAVE_DEBOUNCE_MS`] + flush при stop.
//!
//! Философия надёжности: азбука НИКОГДА не становится причиной
//! падения/задержки движка — любая ошибка IO = warning и работа в память.
use std::collections::{HashMap, HashSet};
use std::fs;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use std::sync::Mutex;

use serde::{Deserialize, Serialize};

use crate::network::peer_exchange::{is_public_endpoint, valid_node_id};

pub const BOOK_VERSION: u8 = 1; // расширение файла обратно совместимо: старые APK игнорируют history
/// Только доказанные прямой подписью публичные адреса. После 30 дней не пробуем.
pub const HISTORY_TTL_MS: i64 = 30 * 24 * 60 * 60 * 1000;
pub const HISTORY_PER_PEER: usize = 4;
pub const HISTORY_MAX_ENTRIES: usize = 2048;
/// Повторная проверка одного и того же адреса за час не делает его «любимым».
pub const HISTORY_HIT_INTERVAL_MS: i64 = 60 * 60 * 1000;
/// Пробованный адрес отступает на 6 ч, даже если QUIC принял кадр, но
/// подписанный ответ так и не пришёл. Иначе одна «любимая» мёртвая точка
/// вытеснила бы все альтернативы при глобальном бюджете 1 проба/час.
pub const HISTORY_ENDPOINT_COOLDOWN_MS: i64 = 6 * 60 * 60 * 1000;
pub const HISTORY_PROBE_INTERVAL_MS: i64 = 60 * 60 * 1000;
/// Узел, которого не видели 30 дней, убираем при следующей записи.
pub const ENTRY_TTL_MS: i64 = 30 * 24 * 60 * 60 * 1000;
/// Больше «своих» у абонента физически не будет; файл ~100 КБ.
pub const MAX_ENTRIES: usize = 1000;
/// Presence-обмен частый, диск — нет: не чаще раза в 15 секунд.
pub const SAVE_DEBOUNCE_MS: i64 = 15 * 1000;

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Entry {
    id: String,
    addr: String,
    seen: i64,
}

/// Only a directly authenticated, session-bound peer exchange can add/increase this.
/// Never exported to PEX gossip, HTTP registry, or the public contact list.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct HistoryEntry {
    id: String,
    addr: String,
    signing_key: [u8; 32],
    verified_at_ms: i64,
    hits: u16,
    #[serde(default)] last_hit_at_ms: i64,
    #[serde(default)] last_probe_at_ms: i64,
    #[serde(default)] failed_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerifiedAddress {
    pub id: String,
    pub addr: SocketAddr,
    pub verified_at_ms: i64,
    pub hits: u16,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct FileV1 {
    v: u8,
    entries: Vec<Entry>,
    // Additive v1 field: older APKs ignore it and continue reading the
    // current-address list. Rollback may forget history but never addresses.
    #[serde(default)]
    history: Vec<HistoryEntry>,
    #[serde(default)]
    last_history_probe_ms: i64,
}

const DAY_MS: i64 = 24 * 60 * 60 * 1000;

fn valid_history_address(record: &HistoryEntry, now: i64) -> Option<SocketAddr> {
    if !valid_node_id(&record.id) || record.hits == 0 || record.verified_at_ms <= 0
        || record.verified_at_ms > now || now.saturating_sub(record.verified_at_ms) > HISTORY_TTL_MS
        || record.signing_key == [0; 32]
    { return None; }
    record.addr.parse::<SocketAddr>().ok().filter(|addr| is_public_endpoint(*addr))
}

/// A recently verified address wins; within a recency bucket the address used
/// successfully more often wins. These are priorities, NOT availability odds.
fn history_rank(record: &HistoryEntry, now: i64) -> (u8, u16, i64) {
    let age = now.saturating_sub(record.verified_at_ms);
    let freshness = if age <= 2 * 60 * 60 * 1000 { 4 }
        else if age <= DAY_MS { 3 } else if age <= 7 * DAY_MS { 2 } else { 1 };
    (freshness, record.hits, record.verified_at_ms)
}

fn trim_history(history: &mut HashMap<(String, SocketAddr), HistoryEntry>, now: i64) {
    history.retain(|_, record| valid_history_address(record, now).is_some());
    // The newest verified route must survive even if four old, frequently
    // used addresses have more hits: a newly roaming phone needs a way back.
    let mut newest: HashMap<String, (SocketAddr, i64)> = HashMap::new();
    for ((id, addr), record) in history.iter() {
        if newest.get(id).map(|(old_addr, at)|
            record.verified_at_ms > *at || (record.verified_at_ms == *at && addr > old_addr)
        ).unwrap_or(true) {
            newest.insert(id.clone(), (*addr, record.verified_at_ms));
        }
    }
    let reserved: HashSet<_> = newest.into_iter().map(|(id, (addr, _))| (id, addr)).collect();
    let mut ranked: Vec<_> = history.iter().map(|(key, record)|
        (key.clone(), (reserved.contains(key), history_rank(record, now)))).collect();
    ranked.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    let mut per_peer: HashMap<String, usize> = HashMap::new();
    let mut kept = 0;
    for (key, _) in ranked {
        let count = per_peer.entry(key.0.clone()).or_default();
        if *count >= HISTORY_PER_PEER || kept >= HISTORY_MAX_ENTRIES { history.remove(&key); }
        else { *count += 1; kept += 1; }
    }
}

pub struct AddressBook {
    /// `None` = режим без durability (движок без relay_db): память, save
    /// молча не пишется.
    path: Option<PathBuf>,
    entries: Mutex<HashMap<String, Entry>>,
    history: Mutex<HashMap<(String, SocketAddr), HistoryEntry>>,
    write_lock: Mutex<()>,
    last_history_probe_ms: AtomicI64,
    last_save_ms: AtomicI64,
    generation: AtomicU64,
}

impl AddressBook {
    /// Открыть азбуку: прочитать файл (если путь есть), отбросить
    /// протухшее и избыток. Повреждённый файл не роняет движок —
    /// предупреждение и пустая азбука (перезаполнится за минуты).
    pub fn open(path: Option<PathBuf>) -> Self {
        let mut entries: HashMap<String, Entry> = HashMap::new();
        let mut history: HashMap<(String, SocketAddr), HistoryEntry> = HashMap::new();
        let mut last_history_probe_ms = 0;
        if let Some(ref p) = path {
            match fs::read_to_string(p) {
                Ok(text) => match serde_json::from_str::<FileV1>(&text) {
                    Ok(file) => {
                        if file.v == BOOK_VERSION {
                            let now_ms = crate::storage::models::now_ms();
                            for entry in file.entries {
                                if now_ms.saturating_sub(entry.seen) > ENTRY_TTL_MS {
                                    continue;
                                }
                                entries.insert(entry.id.clone(), entry);
                            }
                            // Histories from disk are hints only: even a recent timestamp is
                            // never considered online. Verify ID, endpoint and owner key again.
                            for record in file.history.into_iter().take(HISTORY_MAX_ENTRIES * 4) {
                                if let Some(addr) = valid_history_address(&record, now_ms) {
                                    let key = (record.id.clone(), addr);
                                    let replace = history.get(&key).map(|old|
                                        old.verified_at_ms < record.verified_at_ms).unwrap_or(true);
                                    if replace { history.insert(key, record); }
                                }
                            }
                            trim_history(&mut history, now_ms);
                            // A clock moving backwards must not reset the budget.
                            if file.last_history_probe_ms > 0 {
                                last_history_probe_ms = file.last_history_probe_ms;
                            }
                        } else {
                            tracing::warn!(
                                "ADDRESS BOOK: version {} != {}, starting empty",
                                file.v,
                                BOOK_VERSION
                            );
                        }
                    }
                    Err(e) => tracing::warn!(
                        "ADDRESS BOOK: corrupt file at {}: {} (starting empty)",
                        p.display(),
                        e
                    ),
                },
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                    tracing::info!("ADDRESS BOOK: no file yet at {}", p.display());
                }
                Err(e) => tracing::warn!(
                    "ADDRESS BOOK: cannot read {}: {}",
                    p.display(),
                    e
                ),
            }
        }
        // Потолок: если файл всё-таки больше лимита — держим самые свежие.
        if entries.len() > MAX_ENTRIES {
            let mut list: Vec<Entry> = entries.values().cloned().collect();
            list.sort_by_key(|e| e.seen);
            let keep = list.len() - MAX_ENTRIES;
            for stale in list.into_iter().take(keep) {
                entries.remove(&stale.id);
            }
        }
        tracing::info!(
            "ADDRESS BOOK: loaded {} entries, {} private verified endpoints (path: {})",
            entries.len(), history.len(),
            path.as_ref().map(|p| p.display().to_string()).unwrap_or_else(|| "<memory only>".into())
        );
        Self {
            path,
            entries: Mutex::new(entries),
            history: Mutex::new(history),
            write_lock: Mutex::new(()),
            last_history_probe_ms: AtomicI64::new(last_history_probe_ms),
            last_save_ms: AtomicI64::new(0),
            generation: AtomicU64::new(1),
        }
    }

    /// Путь на диске (для логов/отладки). `None` = память.
    pub fn path(&self) -> Option<&Path> {
        self.path.as_deref()
    }

    /// Есть ли на диске (дальше save пишется).
    pub fn is_persistent(&self) -> bool {
        self.path.is_some()
    }

    /// Сколько записей живёт.
    pub fn len(&self) -> usize {
        self.entries.lock().unwrap().len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Адреса для seed в `peer_addrs` при старте движка.
    /// Непаршащиеся строки пропускаются (запись отвалится сама).
    pub fn seed_addrs(&self) -> HashMap<String, SocketAddr> {
        let entries = self.entries.lock().unwrap();
        let mut out = HashMap::new();
        for (id, entry) in entries.iter() {
            if let Ok(addr) = entry.addr.parse::<SocketAddr>() {
                out.insert(id.clone(), addr);
            }
        }
        out
    }

    /// Timestamp snapshot used for discovery ranking. It is not proof that a peer is online.
    pub fn observations(&self) -> HashMap<String, (SocketAddr, i64)> {
        self.entries.lock().unwrap().iter().filter_map(|(id, entry)| {
            entry.addr.parse::<SocketAddr>().ok().map(|addr| (id.clone(), (addr, entry.seen)))
        }).collect()
    }

    /// Успешный обмен APUX1 с ПОДПИСЬЮ владельца и TLS exporter привязкой.
    /// Никакое MQTT-presence или третьестороннее сообщение не зовёт этот метод.
    /// Считаем не чаще раза в час, чтобы частые пульсы не изображали уверенность.
    pub fn note_verified(&self, id: &str, addr: SocketAddr, signing_key: [u8; 32], now: i64) -> bool {
        if !valid_node_id(id) || !is_public_endpoint(addr) || signing_key == [0; 32] || now <= 0 {
            return false;
        }
        let mut history = self.history.lock().unwrap();
        let key = (id.to_string(), addr);
        let first_proof = !history.contains_key(&key);
        let record = history.entry(key).or_insert_with(|| HistoryEntry {
            id: id.to_string(), addr: addr.to_string(), signing_key,
            verified_at_ms: now, hits: 0, last_hit_at_ms: 0,
            last_probe_at_ms: 0, failed_at_ms: 0,
        });
        let owner_changed = record.signing_key != signing_key;
        if owner_changed {
            // New owner of the routing ID: never inherit the old key's reputation.
            record.signing_key = signing_key;
            record.hits = 0;
            record.last_hit_at_ms = 0;
            record.verified_at_ms = now;
        }
        let earned_hit = record.hits == 0
            || now.saturating_sub(record.last_hit_at_ms) >= HISTORY_HIT_INTERVAL_MS;
        if earned_hit {
            record.hits = record.hits.saturating_add(1);
            record.last_hit_at_ms = now;
        }
        record.verified_at_ms = now;
        record.last_probe_at_ms = 0;
        record.failed_at_ms = 0;
        trim_history(&mut history, now);
        drop(history);
        // A preceding record() may have just written the old address snapshot:
        // a new proof must survive an abrupt stop, even inside the debounce.
        if first_proof || owner_changed || earned_hit { self.flush(); }
        else { self.save_if_due(now); }
        true
    }

    /// Failed *dial* of an address already proven in the past: one-hour cooldown.
    /// An unverified WebSocket/gossip hint cannot add an address to history.
    pub fn note_failed(&self, id: &str, addr: SocketAddr, now: i64) {
        let mut history = self.history.lock().unwrap();
        let mut changed = false;
        if let Some(record) = history.get_mut(&(id.to_string(), addr)) {
            if now > record.verified_at_ms && now > record.failed_at_ms {
                record.failed_at_ms = now;
                changed = true;
            }
        }
        drop(history);
        if changed { self.save_if_due(now); }
    }

    /// Private, bounded candidates for a rare direct background probe. The
    /// caller supplies the locally pinned signing keys of its own contacts;
    /// deleted/changed pins cannot inherit old addresses. No gossip export.
    pub fn verified_candidates(&self, pins: &HashMap<String, [u8; 32]>, now: i64) -> Vec<VerifiedAddress> {
        let history = self.history.lock().unwrap();
        let mut ranked: Vec<_> = history.values().filter_map(|record| {
            let addr = valid_history_address(record, now)?;
            let cooling = [record.last_probe_at_ms, record.failed_at_ms].iter().any(|at|
                *at > 0 && *at >= record.verified_at_ms
                    && now.saturating_sub(*at) < HISTORY_ENDPOINT_COOLDOWN_MS);
            if pins.get(&record.id) != Some(&record.signing_key) || cooling {
                return None;
            }
            Some((history_rank(record, now), VerifiedAddress {
                id: record.id.clone(), addr, verified_at_ms: record.verified_at_ms, hits: record.hits,
            }))
        }).collect();
        ranked.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.id.cmp(&b.1.id))
            .then_with(|| a.1.addr.cmp(&b.1.addr)));
        ranked.into_iter().map(|(_, candidate)| candidate).collect()
    }

    /// Persist the global hourly probe budget across process restarts. Claim
    /// only when an eligible candidate exists; the claim is conservative even
    /// if a subsequent dial cannot start (rate limit/backoff).
    pub fn claim_history_probe(&self, id: &str, addr: SocketAddr, now: i64) -> bool {
        let mut history = self.history.lock().unwrap();
        let Some(record) = history.get_mut(&(id.to_string(), addr)) else { return false; };
        if valid_history_address(record, now).is_none()
            || (record.last_probe_at_ms > 0 &&
                now.saturating_sub(record.last_probe_at_ms) < HISTORY_ENDPOINT_COOLDOWN_MS)
        { return false; }
        let last = self.last_history_probe_ms.load(Ordering::Relaxed);
        if now <= 0 || (last > 0 && now.saturating_sub(last) < HISTORY_PROBE_INTERVAL_MS) {
            return false;
        }
        if self.last_history_probe_ms.compare_exchange(last, now, Ordering::AcqRel, Ordering::Relaxed).is_err() {
            return false;
        }
        record.last_probe_at_ms = now;
        drop(history);
        self.flush(); // one small disk write per hour; survives process restarts
        true
    }

    /// Узнать новый адрес узла (7 точек записи: DHT-ответ, mDNS ×3,
    /// брокер-presence, invite, входящее QUIC-соединение). Возвращает
    /// `true`, если адрес для узла МЕНЯлся (полезно для логов).
    pub fn generation(&self) -> u64 { self.generation.load(Ordering::Relaxed) }

    pub fn record(&self, node_id: &str, addr: SocketAddr) -> bool {
        self.record_observed_at(node_id, addr, crate::storage::models::now_ms())
    }

    /// Preserve the observation time when learning somebody else's signed address. Repeated
    /// forwarding must not turn a yesterday-address into a freshly seen/online subscriber.
    pub fn record_observed_at(&self, node_id: &str, addr: SocketAddr, observed_at_ms: i64) -> bool {
        if node_id.is_empty() {
            return false;
        }
        let now_ms = crate::storage::models::now_ms();
        let observed_at_ms = observed_at_ms.max(0).min(now_ms);
        if now_ms.saturating_sub(observed_at_ms) > ENTRY_TTL_MS { return false; }
        let changed;
        {
            let mut entries = self.entries.lock().unwrap();
            match entries.get_mut(node_id) {
                Some(old) => {
                    changed = old.addr != addr.to_string();
                    if changed {
                        old.addr = addr.to_string();
                    }
                    old.seen = if changed { observed_at_ms } else { old.seen.max(observed_at_ms) };
                }
                None => {
                    entries.insert(
                        node_id.to_string(),
                        Entry {
                            id: node_id.to_string(),
                            addr: addr.to_string(),
                            seen: observed_at_ms,
                        },
                    );
                    changed = true;
                }
            }
        }
        if changed { self.generation.fetch_add(1, Ordering::Relaxed); }
        // Троттлинг: не чаще раза в SAVE_DEBOUNCE_MS (flush при stop
        // всё равно добьёт хвост).
        self.save_if_due(now_ms);
        changed
    }

    fn save_if_due(&self, now_ms: i64) {
        let last = self.last_save_ms.load(Ordering::Relaxed);
        if self.path.is_some() && now_ms.saturating_sub(last) >= SAVE_DEBOUNCE_MS {
            let _ = self.save_locked_last(now_ms);
        }
    }

    /// Немедленная запись (при stop движка).
    pub fn flush(&self) {
        if self.path.is_none() {
            return;
        }
        let now_ms = crate::storage::models::now_ms();
        let _ = self.save_locked_last(now_ms);
    }

    /// Запись файла под актуальной меткой времени. Ошибка — warning.
    fn save_locked_last(&self, now_ms: i64) -> Result<(), String> {
        let Some(ref p) = self.path else {
            return Ok(());
        };
        // Multiple discovery/background threads can save the same .tmp concurrently.
        let _write_guard = self.write_lock.lock().unwrap();
        let mut list: Vec<Entry> = self.entries.lock().unwrap().values().cloned().collect();
        // Уборка «неактуальных»: протухшее и избыток (самые свежие).
        list.retain(|e| now_ms.saturating_sub(e.seen) <= ENTRY_TTL_MS);
        if list.len() > MAX_ENTRIES {
            list.sort_by_key(|e| e.seen);
            list.drain(0..(list.len() - MAX_ENTRIES));
        }
        let count = list.len();
        let mut history: Vec<_> = self.history.lock().unwrap().values()
            .filter(|r| valid_history_address(r, now_ms).is_some())
            .cloned().collect();
        history.sort_by(|a, b| a.id.cmp(&b.id).then_with(|| a.addr.cmp(&b.addr)));
        let file = FileV1 {
            v: BOOK_VERSION,
            entries: list,
            history,
            last_history_probe_ms: self.last_history_probe_ms.load(Ordering::Relaxed),
        };
        let text = serde_json::to_string(&file)
            .map_err(|e| format!("serialize: {e}"))?;
        if let Some(parent) = p.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let tmp = p.with_extension("json.tmp");
        fs::write(&tmp, text).map_err(|e| format!("write tmp: {e}"))?;
        fs::rename(&tmp, p).map_err(|e| format!("rename: {e}"))?;
        self.last_save_ms.store(now_ms, Ordering::Relaxed);
        tracing::info!("ADDRESS BOOK: saved {} entries to {}", count, p.display());
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp_path(tag: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "apu_addrbook_test_{}_{}.json",
            std::process::id(),
            tag
        ))
    }

    #[test]
    fn memory_only_when_no_path() {
        let book = AddressBook::open(None);
        assert!(!book.is_persistent());
        book.record("pk_a", "10.0.0.1:443".parse().unwrap());
        assert_eq!(book.len(), 1);
        book.flush(); // не падает
    }

    #[test]
    fn round_trip_and_seed() {
        let path = tmp_path("roundtrip");
        let _ = fs::remove_file(&path);
        {
            let book = AddressBook::open(Some(path.clone()));
            book.record("pk_a", "10.0.0.1:443".parse().unwrap());
            book.record("pk_b_public", "203.0.113.5:443".parse().unwrap());
            book.flush();
        }
        let book = AddressBook::open(Some(path.clone()));
        assert_eq!(book.len(), 2);
        let seed = book.seed_addrs();
        assert_eq!(seed.get("pk_a"), Some(&"10.0.0.1:443".parse::<SocketAddr>().unwrap()));
        assert_eq!(
            seed.get("pk_b_public"),
            Some(&"203.0.113.5:443".parse::<SocketAddr>().unwrap())
        );
        let _ = fs::remove_file(path);
    }

    #[test]
    fn record_updates_seen_and_reports_change() {
        let path = tmp_path("changed");
        let _ = fs::remove_file(&path);
        let book = AddressBook::open(Some(path.clone()));
        assert!(book.record("pk_a", "10.0.0.1:443".parse().unwrap()));
        // Тот же адрес — «не изменился».
        assert!(!book.record("pk_a", "10.0.0.1:443".parse().unwrap()));
        // Новый адрес — «изменился».
        assert!(book.record("pk_a", "10.0.0.2:443".parse().unwrap()));
        assert_eq!(book.len(), 1);
        book.flush();
        let _ = fs::remove_file(path);
    }

    #[test]
    fn corrupt_file_starts_empty_not_crash() {
        let path = tmp_path("corrupt");
        fs::write(&path, "это не json {").unwrap();
        let book = AddressBook::open(Some(path.clone()));
        assert_eq!(book.len(), 0);
        book.record("pk_a", "10.0.0.1:443".parse().unwrap());
        book.flush();
        // После flush файл снова валидный.
        let book2 = AddressBook::open(Some(path.clone()));
        assert_eq!(book2.len(), 1);
        let _ = fs::remove_file(path);
    }

    #[test]
    fn stale_entries_pruned_at_load() {
        let path = tmp_path("stale");
        let _ = fs::remove_file(&path);
        let now = crate::storage::models::now_ms();
        let fresh = Entry {
            id: "fresh".into(),
            addr: "10.0.0.1:443".into(),
            seen: now,
        };
        let stale = Entry {
            id: "stale".into(),
            addr: "10.0.0.2:443".into(),
            seen: now - ENTRY_TTL_MS - 1,
        };
        let file = FileV1 {
            v: BOOK_VERSION,
            entries: vec![stale, fresh],
            history: Vec::new(),
            last_history_probe_ms: 0,
        };
        fs::write(&path, serde_json::to_string(&file).unwrap()).unwrap();
        let book = AddressBook::open(Some(path.clone()));
        assert_eq!(book.len(), 1);
        assert!(book.seed_addrs().contains_key("fresh"));
        let _ = fs::remove_file(path);
    }

    #[test]
    fn cap_keeps_newest() {
        let path = tmp_path("cap");
        let _ = fs::remove_file(&path);
        let now = crate::storage::models::now_ms();
        let mut entries = Vec::new();
        for i in 0..(MAX_ENTRIES + 50) {
            entries.push(Entry {
                id: format!("pk_{i:05}"),
                addr: "10.0.0.1:443".into(),
                seen: now - (MAX_ENTRIES + 50 - i) as i64, // i=0 — самый старый
            });
        }
        let file = FileV1 {
            v: BOOK_VERSION,
            entries,
            history: Vec::new(),
            last_history_probe_ms: 0,
        };
        fs::write(&path, serde_json::to_string(&file).unwrap()).unwrap();
        let book = AddressBook::open(Some(path.clone()));
        assert_eq!(book.len(), MAX_ENTRIES);
        // Старейшие отвалены, самые свежие на месте.
        assert!(!book.seed_addrs().contains_key("pk_00000"));
        assert!(book.seed_addrs().contains_key(&format!("pk_{:05}", MAX_ENTRIES + 49)));
        let _ = fs::remove_file(path);
    }

    #[test]
    fn bad_addr_string_never_poisons_seed() {
        let path = tmp_path("badaddr");
        let _ = fs::remove_file(&path);
        let now = crate::storage::models::now_ms();
        let file = FileV1 {
            v: BOOK_VERSION,
            entries: vec![
                Entry {
                    id: "good".into(),
                    addr: "10.0.0.1:443".into(),
                    seen: now,
                },
                Entry {
                    id: "bad".into(),
                    addr: "не-адрес".into(),
                    seen: now,
                },
            ],
            history: Vec::new(),
            last_history_probe_ms: 0,
        };
        fs::write(&path, serde_json::to_string(&file).unwrap()).unwrap();
        let book = AddressBook::open(Some(path.clone()));
        let seed = book.seed_addrs();
        assert!(seed.contains_key("good"));
        assert!(!seed.contains_key("bad"));
        let _ = fs::remove_file(path);
    }
    fn node(n: u8) -> String { format!("pk_{}", format!("{n:02x}").repeat(32)) }
    fn pins(id: &str, key: [u8; 32]) -> HashMap<String, [u8; 32]> {
        HashMap::from([(id.to_string(), key)])
    }

    #[test]
    fn verified_history_remembers_four_public_addresses_without_changing_current_route() {
        let book = AddressBook::open(None);
        let id = node(1); let key = [1; 32]; let now = 1_800_000_000_000_i64;
        for n in 1..=6 {
            let addr: SocketAddr = format!("8.8.4.{n}:7777").parse().unwrap();
            book.record_observed_at(&id, addr, now);
            assert!(book.note_verified(&id, addr, key, now + n as i64));
        }
        assert_eq!(book.seed_addrs().get(&id), Some(&"8.8.4.6:7777".parse().unwrap()));
        let result = book.verified_candidates(&pins(&id, key), now + 7);
        assert_eq!(result.len(), HISTORY_PER_PEER);
        assert!(result.iter().any(|r| r.addr == "8.8.4.6:7777".parse::<SocketAddr>().unwrap()));
        assert!(!result.iter().any(|r| r.addr == "8.8.4.1:7777".parse::<SocketAddr>().unwrap()));
    }

    #[test]
    fn newest_verified_route_survives_when_four_old_routes_have_more_hits() {
        let book = AddressBook::open(None);
        let id = node(7); let key = [7; 32]; let now = 1_800_000_000_000_i64;
        for n in 1..=4 {
            let addr: SocketAddr = format!("8.8.4.{n}:7777").parse().unwrap();
            book.note_verified(&id, addr, key, now);
            book.note_verified(&id, addr, key, now + HISTORY_HIT_INTERVAL_MS);
        }
        let latest: SocketAddr = "8.8.4.5:7777".parse().unwrap();
        book.note_verified(&id, latest, key, now + HISTORY_HIT_INTERVAL_MS + 1);
        let candidates = book.verified_candidates(&pins(&id, key), now + HISTORY_HIT_INTERVAL_MS + 2);
        assert_eq!(candidates.len(), HISTORY_PER_PEER);
        assert!(candidates.iter().any(|r| r.addr == latest));
    }

    #[test]
    fn direct_proof_is_required_and_frequency_does_not_count_heartbeats() {
        let book = AddressBook::open(None);
        let id = node(2); let now = 1_800_000_000_000_i64; let key = [2; 32];
        let home: SocketAddr = "8.8.4.2:7777".parse().unwrap();
        let mobile: SocketAddr = "1.1.1.2:7777".parse().unwrap();
        // MQTT/third-party DHT observation must never become a verified history entry.
        book.record_observed_at(&id, mobile, now);
        assert!(book.verified_candidates(&pins(&id, key), now).is_empty());
        book.note_verified(&id, home, key, now);
        for n in 1..6 { book.note_verified(&id, home, key, now + n * 10_000); }
        assert_eq!(book.verified_candidates(&pins(&id, key), now + 60_000)[0].hits, 1);
        book.note_verified(&id, home, key, now + HISTORY_HIT_INTERVAL_MS);
        assert_eq!(book.verified_candidates(&pins(&id, key), now + HISTORY_HIT_INTERVAL_MS)[0].hits, 2);
        book.note_verified(&id, mobile, key, now + HISTORY_HIT_INTERVAL_MS);
        let candidates = book.verified_candidates(&pins(&id, key), now + HISTORY_HIT_INTERVAL_MS);
        assert_eq!(candidates[0].addr, home, "у адресов одинаковая свежесть, частый первым");
        assert!(book.verified_candidates(&pins(&id, [3; 32]), now + HISTORY_HIT_INTERVAL_MS).is_empty(),
            "после смены локального pin старое доверие не наследуется");
    }

    #[test]
    fn attempted_favourite_rotates_to_next_address_even_without_signed_reply() {
        let book = AddressBook::open(None);
        let id = node(8); let key = [8; 32]; let now = 1_800_000_000_000_i64;
        let favourite: SocketAddr = "8.8.4.8:7777".parse().unwrap();
        let alternate: SocketAddr = "1.1.1.8:7777".parse().unwrap();
        book.note_verified(&id, favourite, key, now);
        book.note_verified(&id, favourite, key, now + HISTORY_HIT_INTERVAL_MS);
        book.note_verified(&id, alternate, key, now + HISTORY_HIT_INTERVAL_MS);
        let at = now + HISTORY_HIT_INTERVAL_MS;
        assert_eq!(book.verified_candidates(&pins(&id, key), at)[0].addr, favourite);
        assert!(book.claim_history_probe(&id, favourite, at));
        assert_eq!(book.verified_candidates(&pins(&id, key), at + 1)[0].addr, alternate);
        assert!(!book.claim_history_probe(&id, alternate, at + 1), "global hourly budget");
        assert!(book.claim_history_probe(&id, alternate, at + HISTORY_PROBE_INTERVAL_MS));
        assert!(book.verified_candidates(&pins(&id, key), at + HISTORY_PROBE_INTERVAL_MS + 1).is_empty());
    }

    #[test]
    fn failures_cool_down_a_historical_route_but_never_mark_peer_offline() {
        let book = AddressBook::open(None);
        let id = node(3); let key = [3; 32]; let now = 1_800_000_000_000_i64;
        let addr: SocketAddr = "8.8.4.3:7777".parse().unwrap();
        book.note_verified(&id, addr, key, now);
        book.note_failed(&id, addr, now + 1);
        assert!(book.verified_candidates(&pins(&id, key), now + 2).is_empty());
        assert_eq!(book.verified_candidates(&pins(&id, key), now + HISTORY_ENDPOINT_COOLDOWN_MS + 1).len(), 1);
        // Genuine bound reply overrides a delayed dial failure.
        book.note_verified(&id, addr, key, now + 30);
        book.note_failed(&id, addr, now + 20);
        assert_eq!(book.verified_candidates(&pins(&id, key), now + 31).len(), 1);
        assert!(book.verified_candidates(&pins(&id, key), now + HISTORY_TTL_MS + 31).is_empty());
    }

    #[test]
    fn additive_v1_file_and_private_verified_history_survive_restart() {
        let path = tmp_path("history"); let _ = fs::remove_file(&path);
        let id = node(4); let now = crate::storage::models::now_ms(); let key = [4; 32];
        // Existing APK's file contains no history at all: forward migration is safe.
        let old = format!(r#"{{"v":1,"entries":[{{"id":"{id}","addr":"8.8.4.4:7777","seen":{now}}}]}}"#);
        fs::write(&path, old).unwrap();
        let book = AddressBook::open(Some(path.clone()));
        assert_eq!(book.len(), 1);
        let addr: SocketAddr = "8.8.4.4:7777".parse().unwrap();
        book.note_verified(&id, addr, key, now);
        assert!(book.claim_history_probe(&id, addr, now));
        assert!(!book.claim_history_probe(&id, addr, now + HISTORY_PROBE_INTERVAL_MS - 1));
        book.flush();
        let text = fs::read_to_string(&path).unwrap();
        assert!(text.contains("\"history\""));
        // Old serde FileV1 ignores unknown field; current address remains intact.
        let reopened = AddressBook::open(Some(path.clone()));
        assert_eq!(reopened.seed_addrs().get(&id), Some(&"8.8.4.4:7777".parse().unwrap()));
        assert!(reopened.verified_candidates(&pins(&id, key), now).is_empty(),
            "attempted address must rotate out across a restart");
        assert!(!reopened.claim_history_probe(&id, addr, now + HISTORY_PROBE_INTERVAL_MS - 1),
            "global budget survives restart");
        assert!(reopened.verified_candidates(&pins(&id, key), now + HISTORY_ENDPOINT_COOLDOWN_MS).len() == 1);
        assert!(reopened.claim_history_probe(&id, addr, now + HISTORY_ENDPOINT_COOLDOWN_MS));
        let _ = fs::remove_file(path);
    }

    #[test]
    fn private_lan_and_unsigned_hint_are_never_background_probes() {
        let book = AddressBook::open(None);
        let id = node(5); let now = 1_800_000_000_000_i64;
        let lan: SocketAddr = "192.168.0.1:7777".parse().unwrap();
        let public: SocketAddr = "8.8.4.5:7777".parse().unwrap();
        assert!(!book.note_verified(&id, lan, [5; 32], now));
        assert!(!book.note_verified("pk_fake", public, [5; 32], now));
        book.record(&id, public);
        assert!(book.verified_candidates(&pins(&id, [5; 32]), now).is_empty());
    }

    #[test]
    fn disk_history_filters_expired_lan_invalid_id_and_changed_owner() {
        let path = tmp_path("history_filter"); let _ = fs::remove_file(&path);
        let now = crate::storage::models::now_ms(); let id = node(6); let key = [6; 32];
        let entry = |id: String, addr: &str, at: i64| HistoryEntry {
            id, addr: addr.into(), signing_key: key, verified_at_ms: at,
            hits: 20, last_hit_at_ms: at, last_probe_at_ms: 0, failed_at_ms: 0,
        };
        let file = FileV1 { v: BOOK_VERSION, entries: vec![],
            history: vec![
                entry(id.clone(), "8.8.4.6:7777", now),
                entry(id.clone(), "192.168.1.6:7777", now),
                entry(id.clone(), "1.1.1.6:7777", now - HISTORY_TTL_MS - 1),
                entry("pk_fake".into(), "8.8.4.7:7777", now),
            ], last_history_probe_ms: 0 };
        fs::write(&path, serde_json::to_string(&file).unwrap()).unwrap();
        let book = AddressBook::open(Some(path.clone()));
        let candidates = book.verified_candidates(&pins(&id, key), now);
        assert_eq!(candidates.len(), 1);
        assert_eq!(candidates[0].addr, "8.8.4.6:7777".parse().unwrap());
        assert!(book.verified_candidates(&pins(&id, [7; 32]), now).is_empty());
        let _ = fs::remove_file(path);
    }

    #[test]
    fn relayed_observation_keeps_its_original_age_and_only_address_change_wakes_discovery() {
        let book = AddressBook::open(None);
        let now = crate::storage::models::now_ms();
        let endpoint: SocketAddr = "8.8.8.8:7777".parse().unwrap();
        let old = now - 60_000;
        let initial = book.generation();
        assert!(book.record_observed_at("pk_example", endpoint, old));
        assert!(book.generation() > initial);
        let generation = book.generation();
        assert!(!book.record_observed_at("pk_example", endpoint, old - 1000));
        assert_eq!(book.observations()["pk_example"].1, old);
        assert_eq!(book.generation(), generation);
        assert!(book.record("pk_example", "8.8.4.4:7777".parse().unwrap()));
        assert!(book.generation() > generation);
    }

}
