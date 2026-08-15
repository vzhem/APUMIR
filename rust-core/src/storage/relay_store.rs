//! # Relay Store — durable-хранилище relay custody (M8-B)
//!
//! Долговременное (SQLite) хранилище для **чужих** relay-сообщений и tombstone-ов.
//! Это та граница, которая делает mesh store-and-forward переживающим process
//! death / reboot / app update: RAM-очередь [`crate::network::relay_queue::RelayQueue`]
//! остаётся быстрой рабочей копией, а [`RelayStore`] — источником истины, который
//! восстанавливается при старте (M8-D).
//!
//! ## Архитектурные принципы (обязательные)
//!
//! - **Отдельная миграция** (`MIGRATION_RELAY_V1`): своя версия схемы, не смешивается
//!   с основной `Database` (пользовательские чаты/контакты). Relay custody — отдельная
//!   ответственность и может эволюционировать независимо.
//! - **Только sync-API.** `rusqlite::Connection` обёрнут в `Mutex` и НИКОГДА не
//!   удерживается через `.await`: все методы блокирующие, вызываются либо из
//!   sync-контекста, либо через `spawn_blocking` (см. integration в engine).
//! - **Атомарность.** Запись custody, удаление+ tombstone — в транзакциях.
//! - **Bounded load + expiry.** Загрузка при старте ограничена `limit` и возвращает
//!   только не-истёкшие записи; истёкшие/битые удаляются БЕЗ доставки в UI.
//! - **Только recipient-ciphertext.** `e2e_payload` хранится как непрозрачный BLOB.
//!   Relay-узел его не читает. M7-acceptance ещё не завершён, поэтому никакой
//!   plaintext сюда попадать не должен (см. security-оговорку в `relay_queue.rs`).
//!
//! SQL-семантика проверена прототипом `.arena/m8b/test_relay_store_sql.py`
//! (sqlite3 == движок `rusqlite bundled`). Compile/runtime — отдельный Windows gate.

use std::path::Path;
use std::sync::Mutex;

use rusqlite::{params, Connection, Result as SqlResult, Transaction};

use crate::network::relay_queue::{RelayMessage, RelayValidationError};

// ═══════════════════════════════════════════════════════════════════
// MIGRATION
// ═══════════════════════════════════════════════════════════════════

/// Отдельная durable-миграция relay custody (M8-B). Независима от основной
/// `Database`-миграции и имеет собственную таблицу версии схемы.
pub const MIGRATION_RELAY_V1: &str = "
-- Релейные сообщения (чужие), единица durable custody
CREATE TABLE IF NOT EXISTS relay_messages (
    msg_id        TEXT PRIMARY KEY,
    recipient     TEXT NOT NULL,
    origin_sender TEXT NOT NULL,
    chat_scope    TEXT NOT NULL,
    e2e_payload   BLOB NOT NULL,
    created_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER NOT NULL,
    hop_count     INTEGER NOT NULL
);

-- Индекс получателя: быстрая выдача «всё для B» и per-recipient подсчёт
CREATE INDEX IF NOT EXISTS idx_relay_messages_recipient
    ON relay_messages(recipient);

-- Индекс абсолютного истечения: bounded startup load и purge по expiry
CREATE INDEX IF NOT EXISTS idx_relay_messages_expires
    ON relay_messages(expires_at_ms);

-- Tombstones: доставленные/удалённые msg_id, чтобы не принять их повторно
-- после restart (durable-аналог RAM seen-tombstones)
CREATE TABLE IF NOT EXISTS relay_tombstones (
    msg_id        TEXT PRIMARY KEY,
    removed_at_ms INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_relay_tombstones_removed
    ON relay_tombstones(removed_at_ms);

-- Версия relay-схемы
CREATE TABLE IF NOT EXISTS relay_schema_version (
    version INTEGER PRIMARY KEY
);

INSERT OR IGNORE INTO relay_schema_version (version) VALUES (1);
";

// ═══════════════════════════════════════════════════════════════════
// SQL (точная семантика проверена sqlite3-прототипом)
// ═══════════════════════════════════════════════════════════════════

const SQL_STORE: &str = "
INSERT OR IGNORE INTO relay_messages
    (msg_id, recipient, origin_sender, chat_scope, e2e_payload,
     created_at_ms, expires_at_ms, hop_count)
VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
";

const SQL_LOAD_UNEXPIRED: &str = "
SELECT msg_id, recipient, origin_sender, chat_scope, e2e_payload,
       created_at_ms, expires_at_ms, hop_count
FROM relay_messages
WHERE expires_at_ms > ?1
ORDER BY expires_at_ms ASC, msg_id ASC
LIMIT ?2
";

const SQL_LOAD_FOR_RECIPIENT: &str = "
SELECT msg_id, recipient, origin_sender, chat_scope, e2e_payload,
       created_at_ms, expires_at_ms, hop_count
FROM relay_messages
WHERE recipient = ?1 AND expires_at_ms > ?2
ORDER BY expires_at_ms ASC, msg_id ASC
LIMIT ?3
";

const SQL_REMOVE: &str = "DELETE FROM relay_messages WHERE msg_id = ?1";
const SQL_REMOVE_FOR_RECIPIENT: &str = "DELETE FROM relay_messages WHERE recipient = ?1";
const SQL_PURGE_EXPIRED: &str = "DELETE FROM relay_messages WHERE expires_at_ms <= ?1";
const SQL_COUNT: &str = "SELECT COUNT(*) FROM relay_messages";
const SQL_COUNT_FOR: &str = "SELECT COUNT(*) FROM relay_messages WHERE recipient = ?1";
const SQL_CONTAINS: &str = "SELECT COUNT(*) FROM relay_messages WHERE msg_id = ?1";

const SQL_TOMBSTONE_ADD: &str = "
INSERT OR REPLACE INTO relay_tombstones (msg_id, removed_at_ms)
VALUES (?1, ?2)
";
const SQL_TOMBSTONE_HAS: &str = "SELECT COUNT(*) FROM relay_tombstones WHERE msg_id = ?1";
const SQL_TOMBSTONE_COUNT: &str = "SELECT COUNT(*) FROM relay_tombstones";
const SQL_TOMBSTONE_IDS: &str = "
SELECT msg_id FROM relay_tombstones
ORDER BY removed_at_ms DESC
LIMIT ?1
";
const SQL_TOMBSTONE_PRUNE: &str = "
DELETE FROM relay_tombstones
WHERE removed_at_ms <= ?1
   OR msg_id NOT IN (
       SELECT msg_id FROM relay_tombstones
       ORDER BY removed_at_ms DESC
       LIMIT ?2
   )
";

// ═══════════════════════════════════════════════════════════════════
// ОШИБКИ
// ═══════════════════════════════════════════════════════════════════

#[derive(Debug, thiserror::Error)]
pub enum RelayStoreError {
    #[error("RelayStore SQLite: {0}")]
    Sql(#[from] rusqlite::Error),

    #[error("RelayStore запись отклонена валидацией: {0}")]
    Validation(#[from] RelayValidationError),
}

// ═══════════════════════════════════════════════════════════════════
// RELAY STORE
// ═══════════════════════════════════════════════════════════════════

/// Durable-хранилище relay custody. Sync-only, потокобезопасно (`Mutex`).
///
/// НЕ держать этот объект через `.await`; в async-контексте вызывать через
/// `spawn_blocking` или из выделенного sync-потока.
pub struct RelayStore {
    conn: Mutex<Connection>,
}

impl RelayStore {
    /// Открыть хранилище по пути (создаёт файл и применяет миграцию).
    pub fn open<P: AsRef<Path>>(path: P) -> Result<Self, RelayStoreError> {
        let conn = Connection::open(path)?;
        let store = Self {
            conn: Mutex::new(conn),
        };
        store.migrate()?;
        Ok(store)
    }

    /// Открыть в памяти (для тестов).
    pub fn open_in_memory() -> Result<Self, RelayStoreError> {
        let conn = Connection::open_in_memory()?;
        let store = Self {
            conn: Mutex::new(conn),
        };
        store.migrate()?;
        Ok(store)
    }

    fn migrate(&self) -> SqlResult<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute_batch("PRAGMA journal_mode=WAL;")?;
        conn.execute_batch("PRAGMA foreign_keys=ON;")?;
        conn.execute_batch(MIGRATION_RELAY_V1)?;
        Ok(())
    }

    /// Текущая версия relay-схемы.
    pub fn schema_version(&self) -> Result<i64, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let v = conn.query_row(
            "SELECT version FROM relay_schema_version ORDER BY version DESC LIMIT 1",
            [],
            |row| row.get(0),
        )?;
        Ok(v)
    }

    // ─── Запись custody ─────────────────────────────────────────────

    /// Атомарно сохранить relay-запись (INSERT OR IGNORE по `msg_id`).
    ///
    /// Перед записью запись валидируется (`validate_durable`): в durable-слой
    /// не попадает пустой/oversized/битый/истёкший payload. Возвращает
    /// `Ok(true)` если вставлено, `Ok(false)` если `msg_id` уже был (дедуп).
    pub fn store(&self, msg: &RelayMessage, now_ms: i64) -> Result<bool, RelayStoreError> {
        msg.validate_durable(now_ms)?;
        let conn = self.conn.lock().unwrap();
        let n = conn.execute(
            SQL_STORE,
            params![
                msg.msg_id,
                msg.recipient,
                msg.origin_sender,
                msg.chat_scope,
                msg.e2e_payload,
                msg.created_at_ms,
                msg.expires_at_ms,
                msg.hop_count,
            ],
        )?;
        Ok(n > 0)
    }

    /// Есть ли запись с таким `msg_id`.
    pub fn contains(&self, msg_id: &str) -> Result<bool, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let c: i64 = conn.query_row(SQL_CONTAINS, params![msg_id], |row| row.get(0))?;
        Ok(c > 0)
    }

    // ─── Загрузка (bounded + expiry) ────────────────────────────────

    /// Загрузить не более `limit` не-истёкших записей (по возрастанию expiry).
    ///
    /// Каждая запись дополнительно проверяется `validate_durable(now_ms)`;
    /// битые/невалидные строки удаляются из БД и НЕ возвращаются (никакой
    /// доставки в UI). Это bounded startup load для M8-D.
    pub fn load_unexpired(
        &self,
        now_ms: i64,
        limit: usize,
    ) -> Result<Vec<RelayMessage>, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        Self::load_validated(&conn, SQL_LOAD_UNEXPIRED, params![now_ms, limit as i64], now_ms)
    }

    /// Загрузить не более `limit` не-истёкших записей для конкретного получателя.
    pub fn load_for_recipient(
        &self,
        recipient: &str,
        now_ms: i64,
        limit: usize,
    ) -> Result<Vec<RelayMessage>, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        Self::load_validated(
            &conn,
            SQL_LOAD_FOR_RECIPIENT,
            params![recipient, now_ms, limit as i64],
            now_ms,
        )
    }

    fn load_validated<P: rusqlite::Params>(
        conn: &Connection,
        sql: &str,
        query_params: P,
        now_ms: i64,
    ) -> Result<Vec<RelayMessage>, RelayStoreError> {
        let mut stmt = conn.prepare(sql)?;
        let rows = stmt.query_map(query_params, row_to_relay_message)?;

        let mut out: Vec<RelayMessage> = Vec::new();
        let mut invalid_ids: Vec<String> = Vec::new();
        for r in rows {
            let record = r?;
            match record.validate_durable(now_ms) {
                Ok(()) => out.push(record),
                Err(_) => invalid_ids.push(record.msg_id.clone()),
            }
        }
        drop(stmt);

        // Битые/невалидные строки удаляем без доставки в UI.
        for id in &invalid_ids {
            conn.execute(SQL_REMOVE, params![id])?;
        }
        Ok(out)
    }

    // ─── Удаление / cleanup ─────────────────────────────────────────

    /// Удалить запись по `msg_id`. Возвращает `true`, если она существовала.
    pub fn remove(&self, msg_id: &str) -> Result<bool, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let n = conn.execute(SQL_REMOVE, params![msg_id])?;
        Ok(n > 0)
    }

    /// Атомарно удалить запись И поставить tombstone (receipt-cleanup путь).
    /// Возвращает `true`, если relay-запись существовала.
    pub fn remove_and_tombstone(
        &self,
        msg_id: &str,
        now_ms: i64,
    ) -> Result<bool, RelayStoreError> {
        let mut conn = self.conn.lock().unwrap();
        let tx: Transaction<'_> = conn.transaction()?;
        let removed = tx.execute(SQL_REMOVE, params![msg_id])?;
        tx.execute(SQL_TOMBSTONE_ADD, params![msg_id, now_ms])?;
        tx.commit()?;
        Ok(removed > 0)
    }

    /// Удалить все записи получателя. Возвращает число удалённых.
    pub fn remove_for_recipient(&self, recipient: &str) -> Result<usize, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let n = conn.execute(SQL_REMOVE_FOR_RECIPIENT, params![recipient])?;
        Ok(n)
    }

    /// Удалить все истёкшие записи (БЕЗ доставки в UI). Возвращает число удалённых.
    pub fn purge_expired(&self, now_ms: i64) -> Result<usize, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let n = conn.execute(SQL_PURGE_EXPIRED, params![now_ms])?;
        Ok(n)
    }

    // ─── Tombstones ─────────────────────────────────────────────────

    /// Записать tombstone для `msg_id` (доставлено/удалено — не принимать снова).
    pub fn record_tombstone(&self, msg_id: &str, now_ms: i64) -> Result<(), RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        conn.execute(SQL_TOMBSTONE_ADD, params![msg_id, now_ms])?;
        Ok(())
    }

    /// Есть ли tombstone для `msg_id`.
    pub fn has_tombstone(&self, msg_id: &str) -> Result<bool, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let c: i64 = conn.query_row(SQL_TOMBSTONE_HAS, params![msg_id], |row| row.get(0))?;
        Ok(c > 0)
    }

    /// Ограничить число tombstone-ов: удалить старше `cutoff_ms` и оставить не
    /// более `max_keep` самых свежих. Возвращает число удалённых.
    pub fn prune_tombstones(&self, cutoff_ms: i64, max_keep: usize) -> Result<usize, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let n = conn.execute(SQL_TOMBSTONE_PRUNE, params![cutoff_ms, max_keep as i64])?;
        Ok(n)
    }

    /// Загрузить не более `limit` самых свежих tombstone `msg_id` (по убыванию
    /// `removed_at_ms`). Используется при старте (M8-D), чтобы восстановить
    /// RAM seen-set и не принять повторно уже доставленные relay.
    pub fn load_tombstone_ids(&self, limit: usize) -> Result<Vec<String>, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(SQL_TOMBSTONE_IDS)?;
        let rows = stmt.query_map(params![limit as i64], |row| row.get::<_, String>(0))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    // ─── Статистика ─────────────────────────────────────────────────

    /// Общее число relay-записей.
    pub fn count(&self) -> Result<usize, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let c: i64 = conn.query_row(SQL_COUNT, [], |row| row.get(0))?;
        Ok(c as usize)
    }

    /// Число relay-записей получателя.
    pub fn count_for_recipient(&self, recipient: &str) -> Result<usize, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let c: i64 = conn.query_row(SQL_COUNT_FOR, params![recipient], |row| row.get(0))?;
        Ok(c as usize)
    }

    /// Общее число tombstone-ов.
    pub fn tombstone_count(&self) -> Result<usize, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let c: i64 = conn.query_row(SQL_TOMBSTONE_COUNT, [], |row| row.get(0))?;
        Ok(c as usize)
    }
}

// ═══════════════════════════════════════════════════════════════════
// HELPERS
// ═══════════════════════════════════════════════════════════════════

fn row_to_relay_message(row: &rusqlite::Row) -> SqlResult<RelayMessage> {
    // hop_count храним как INTEGER; защищённо приводим к u8. Вне диапазона —
    // u8::MAX, такая запись будет отклонена validate_durable (HopsExceeded).
    let hop_i: i64 = row.get(7)?;
    let hop_count = if (0..=u8::MAX as i64).contains(&hop_i) {
        hop_i as u8
    } else {
        u8::MAX
    };

    Ok(RelayMessage {
        msg_id: row.get(0)?,
        recipient: row.get(1)?,
        origin_sender: row.get(2)?,
        chat_scope: row.get(3)?,
        e2e_payload: row.get(4)?,
        created_at_ms: row.get(5)?,
        expires_at_ms: row.get(6)?,
        hop_count,
    })
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;
    use crate::network::relay_queue::MAX_HOPS;
    use std::time::Duration;

    fn store() -> RelayStore {
        RelayStore::open_in_memory().unwrap()
    }

    fn relay(msg_id: &str, recipient: &str, now_ms: i64) -> RelayMessage {
        RelayMessage::with_ttl_at_ms(
            now_ms,
            msg_id.into(),
            recipient.into(),
            "pk_origin".into(),
            "chat-1".into(),
            vec![0xAA; 32],
            Duration::from_secs(3600),
        )
    }

    // ── Схема ───────────────────────────────────────────────────────

    #[test]
    fn test_schema_version_is_1() {
        assert_eq!(store().schema_version().unwrap(), 1);
    }

    #[test]
    fn test_migration_idempotent() {
        let s = store();
        // Повторное применение миграции не ломает схему.
        {
            let conn = s.conn.lock().unwrap();
            conn.execute_batch(MIGRATION_RELAY_V1).unwrap();
        }
        assert_eq!(s.schema_version().unwrap(), 1);
    }

    // ── Запись / дедуп ──────────────────────────────────────────────

    #[test]
    fn test_store_and_contains() {
        let s = store();
        let now = 1_000_000i64;
        assert!(s.store(&relay("m1", "pk_b", now), now).unwrap());
        assert!(s.contains("m1").unwrap());
        assert_eq!(s.count().unwrap(), 1);
    }

    #[test]
    fn test_store_dedup_first_wins() {
        let s = store();
        let now = 1_000_000i64;
        assert!(s.store(&relay("m1", "pk_b", now), now).unwrap());
        // Тот же msg_id, другой recipient — дубликат, игнорируется.
        assert!(!s.store(&relay("m1", "pk_x", now), now).unwrap());
        let loaded = s.load_unexpired(now, 10).unwrap();
        assert_eq!(loaded.len(), 1);
        assert_eq!(loaded[0].recipient, "pk_b");
    }

    #[test]
    fn test_store_rejects_expired() {
        let s = store();
        let created = 1_000_000i64;
        let expired = RelayMessage::with_ttl_at_ms(
            created,
            "m1".into(),
            "pk_b".into(),
            "pk_o".into(),
            "c".into(),
            vec![1],
            Duration::from_millis(10),
        );
        let later = created + 100;
        assert!(s.store(&expired, later).is_err());
        assert_eq!(s.count().unwrap(), 0);
    }

    #[test]
    fn test_store_rejects_invalid_metadata() {
        let s = store();
        let now = 1_000_000i64;
        let mut bad = relay("m1", "pk_b", now);
        bad.chat_scope = String::new();
        assert!(s.store(&bad, now).is_err());
        assert_eq!(s.count().unwrap(), 0);
    }

    #[test]
    fn test_payload_round_trip() {
        let s = store();
        let now = 1_000_000i64;
        let mut m = relay("m1", "pk_b", now);
        let binary: Vec<u8> = (0..=255u8).cycle().take(1024).collect();
        m.e2e_payload = binary.clone();
        s.store(&m, now).unwrap();
        let loaded = s.load_unexpired(now, 10).unwrap();
        assert_eq!(loaded[0].e2e_payload, binary);
    }

    // ── Загрузка (bounded + expiry) ─────────────────────────────────

    #[test]
    fn test_load_unexpired_drops_expired_and_bounds() {
        let s = store();
        let now = 1_000i64;
        // Разные expiry.
        let mut a = relay("a", "pk_b", now);
        a.expires_at_ms = 3000;
        let mut b = relay("b", "pk_b", now);
        b.expires_at_ms = 1500;
        let mut c = relay("c", "pk_c", now);
        c.expires_at_ms = 2000;
        let mut d = relay("d", "pk_c", now);
        d.expires_at_ms = 500; // уже истёк при now=1000

        for m in [&a, &b, &c, &d] {
            s.store(m, now).unwrap();
        }

        let loaded = s.load_unexpired(1000, 100).unwrap();
        let ids: Vec<&str> = loaded.iter().map(|m| m.msg_id.as_str()).collect();
        // d истёк; порядок по expiry: b(1500), c(2000), a(3000)
        assert_eq!(ids, vec!["b", "c", "a"]);

        let bounded = s.load_unexpired(1000, 2).unwrap();
        assert_eq!(bounded.len(), 2);
    }

    #[test]
    fn test_load_unexpired_at_zero_clock() {
        let s = store();
        let m = RelayMessage::with_ttl_at_ms(
            0,
            "m1".into(),
            "pk_b".into(),
            "pk_o".into(),
            "c".into(),
            vec![1],
            Duration::from_secs(100),
        );
        s.store(&m, 0).unwrap();
        let loaded = s.load_unexpired(0, 10).unwrap();
        assert_eq!(loaded.len(), 1);
    }

    #[test]
    fn test_load_for_recipient() {
        let s = store();
        let now = 1_000i64;
        s.store(&relay("m1", "pk_b", now), now).unwrap();
        s.store(&relay("m2", "pk_b", now), now).unwrap();
        s.store(&relay("m3", "pk_c", now), now).unwrap();
        let for_b = s.load_for_recipient("pk_b", now, 100).unwrap();
        assert_eq!(for_b.len(), 2);
        assert!(for_b.iter().all(|m| m.recipient == "pk_b"));
    }

    #[test]
    fn test_load_removes_corrupt_rows() {
        let s = store();
        let now = 1_000i64;
        s.store(&relay("m1", "pk_b", now), now).unwrap();
        // Имитируем «битую» строку: hop за пределами u8.
        {
            let conn = s.conn.lock().unwrap();
            conn.execute(
                "INSERT OR REPLACE INTO relay_messages
                 (msg_id, recipient, origin_sender, chat_scope, e2e_payload,
                  created_at_ms, expires_at_ms, hop_count)
                 VALUES ('corrupt','pk_b','o','c', X'00', ?1, ?2, 99999)",
                params![now, now + 1_000_000],
            )
            .unwrap();
        }
        let loaded = s.load_unexpired(now, 100).unwrap();
        assert_eq!(loaded.len(), 1);
        assert_eq!(loaded[0].msg_id, "m1");
        // Битая строка удалена из БД.
        assert_eq!(s.count().unwrap(), 1);
    }

    // ── Удаление / cleanup ──────────────────────────────────────────

    #[test]
    fn test_remove_and_tombstone() {
        let s = store();
        let now = 1_000i64;
        s.store(&relay("m1", "pk_b", now), now).unwrap();
        assert!(s.remove_and_tombstone("m1", now + 1).unwrap());
        assert!(!s.contains("m1").unwrap());
        assert!(s.has_tombstone("m1").unwrap());
        // Повторно — записи уже нет, но tombstone остаётся.
        assert!(!s.remove_and_tombstone("m1", now + 2).unwrap());
        assert!(s.has_tombstone("m1").unwrap());
    }

    #[test]
    fn test_remove_for_recipient_and_purge() {
        let s = store();
        let now = 1_000i64;
        s.store(&relay("m1", "pk_b", now), now).unwrap();
        s.store(&relay("m2", "pk_b", now), now).unwrap();
        let mut short = relay("m3", "pk_c", now);
        short.expires_at_ms = now + 5;
        s.store(&short, now).unwrap();

        assert_eq!(s.remove_for_recipient("pk_b").unwrap(), 2);
        assert_eq!(s.count().unwrap(), 1);

        let purged = s.purge_expired(now + 10).unwrap();
        assert_eq!(purged, 1);
        assert_eq!(s.count().unwrap(), 0);
    }

    // ── Tombstones ──────────────────────────────────────────────────

    #[test]
    fn test_tombstone_prune_by_age_and_cap() {
        let s = store();
        for (i, ts) in [100i64, 200, 300, 400, 500].iter().enumerate() {
            s.record_tombstone(&format!("t{}", i), *ts).unwrap();
        }
        assert_eq!(s.tombstone_count().unwrap(), 5);

        // По возрасту: удалить <= 250.
        s.prune_tombstones(250, 100).unwrap();
        assert_eq!(s.tombstone_count().unwrap(), 3);

        // По cap: оставить 2 самых свежих.
        s.prune_tombstones(0, 2).unwrap();
        assert_eq!(s.tombstone_count().unwrap(), 2);
        assert!(s.has_tombstone("t3").unwrap());
        assert!(s.has_tombstone("t4").unwrap());
        assert!(!s.has_tombstone("t2").unwrap());
    }

    #[test]
    fn test_load_tombstone_ids_newest_first_bounded() {
        let s = store();
        for (i, ts) in [100i64, 200, 300].iter().enumerate() {
            s.record_tombstone(&format!("t{}", i), *ts).unwrap();
        }
        let ids = s.load_tombstone_ids(2).unwrap();
        // Самые свежие (по removed_at_ms DESC): t2(300), t1(200).
        assert_eq!(ids, vec!["t2".to_string(), "t1".to_string()]);
        let all = s.load_tombstone_ids(100).unwrap();
        assert_eq!(all.len(), 3);
    }

    // ── Hop ─────────────────────────────────────────────────────────

    #[test]
    fn test_store_and_load_preserve_hop() {
        let s = store();
        let now = 1_000i64;
        let mut m = relay("m1", "pk_b", now);
        m.hop_count = MAX_HOPS - 1;
        s.store(&m, now).unwrap();
        let loaded = s.load_unexpired(now, 10).unwrap();
        assert_eq!(loaded[0].hop_count, MAX_HOPS - 1);
    }

    #[test]
    fn test_store_rejects_exhausted_hop() {
        let s = store();
        let now = 1_000i64;
        let mut m = relay("m1", "pk_b", now);
        m.hop_count = MAX_HOPS;
        assert!(s.store(&m, now).is_err());
        assert_eq!(s.count().unwrap(), 0);
    }
}
