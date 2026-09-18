//! Азбука адресов: постоянный файл «node_id → последний известный адрес».
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
use std::collections::HashMap;
use std::fs;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Mutex;

use serde::{Deserialize, Serialize};

pub const BOOK_VERSION: u8 = 1;
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

#[derive(Debug, Default, Serialize, Deserialize)]
struct FileV1 {
    v: u8,
    entries: Vec<Entry>,
}

pub struct AddressBook {
    /// `None` = режим без durability (движок без relay_db): память, save
    /// молча не пишется.
    path: Option<PathBuf>,
    entries: Mutex<HashMap<String, Entry>>,
    last_save_ms: AtomicI64,
}

impl AddressBook {
    /// Открыть азбуку: прочитать файл (если путь есть), отбросить
    /// протухшее и избыток. Повреждённый файл не роняет движок —
    /// предупреждение и пустая азбука (перезаполнится за минуты).
    pub fn open(path: Option<PathBuf>) -> Self {
        let mut entries: HashMap<String, Entry> = HashMap::new();
        if let Some(ref p) = path {
            match fs::read_to_string(p) {
                Ok(text) => match serde_json::from_str::<FileV1>(&text) {
                    Ok(file) => {
                        if file.v == BOOK_VERSION {
                            let now_ms = crate::storage::models::now_ms();
                            for mut entry in file.entries {
                                if now_ms.saturating_sub(entry.seen) > ENTRY_TTL_MS {
                                    continue;
                                }
                                entries.insert(entry.id.clone(), entry);
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
            "ADDRESS BOOK: loaded {} entries (path: {})",
            entries.len(),
            path.as_ref().map(|p| p.display().to_string()).unwrap_or_else(|| "<memory only>".into())
        );
        Self {
            path,
            entries: Mutex::new(entries),
            last_save_ms: AtomicI64::new(0),
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

    /// Узнать новый адрес узла (7 точек записи: DHT-ответ, mDNS ×3,
    /// брокер-presence, invite, входящее QUIC-соединение). Возвращает
    /// `true`, если адрес для узла МЕНЯлся (полезно для логов).
    pub fn record(&self, node_id: &str, addr: SocketAddr) -> bool {
        if node_id.is_empty() {
            return false;
        }
        let now_ms = crate::storage::models::now_ms();
        let changed;
        {
            let mut entries = self.entries.lock().unwrap();
            match entries.get_mut(node_id) {
                Some(old) => {
                    changed = old.addr != addr.to_string();
                    if changed {
                        old.addr = addr.to_string();
                    }
                    old.seen = now_ms;
                }
                None => {
                    entries.insert(
                        node_id.to_string(),
                        Entry {
                            id: node_id.to_string(),
                            addr: addr.to_string(),
                            seen: now_ms,
                        },
                    );
                    changed = true;
                }
            }
        }
        // Троттлинг: не чаще раза в SAVE_DEBOUNCE_MS (flush при stop
        // всё равно добьёт хвост).
        let last = self.last_save_ms.load(Ordering::Relaxed);
        if self.path.is_some() && now_ms.saturating_sub(last) >= SAVE_DEBOUNCE_MS {
            let _ = self.save_locked_last(now_ms);
        }
        changed
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
        let mut list: Vec<Entry> = self.entries.lock().unwrap().values().cloned().collect();
        // Уборка «неактуальных»: протухшее и избыток (самые свежие).
        list.retain(|e| now_ms.saturating_sub(e.seen) <= ENTRY_TTL_MS);
        if list.len() > MAX_ENTRIES {
            list.sort_by_key(|e| e.seen);
            list.drain(0..(list.len() - MAX_ENTRIES));
        }
        let file = FileV1 {
            v: BOOK_VERSION,
            entries: list,
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
        tracing::info!("ADDRESS BOOK: saved {} entries to {}", list.len(), p.display());
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
        let book = AddressBook::open(Some(path));
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
        };
        fs::write(&path, serde_json::to_string(&file).unwrap()).unwrap();
        let book = AddressBook::open(Some(path.clone()));
        assert_eq!(book.len(), MAX_ENTRIES);
        // Старейшие отвалены, самые свежие на месте.
        assert!(!book.seed_addrs().contains_key("pk_00000"));
        assert!(book.seed_addrs().contains_key(format!("pk_{:05}", MAX_ENTRIES + 49)));
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
        };
        fs::write(&path, serde_json::to_string(&file).unwrap()).unwrap();
        let book = AddressBook::open(Some(path.clone()));
        let seed = book.seed_addrs();
        assert!(seed.contains_key("good"));
        assert!(!seed.contains_key("bad"));
        let _ = fs::remove_file(path);
    }
}
