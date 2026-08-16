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
//!
//! **M8-C slice 2 (schema v2):** ниже добавлены `MIGRATION_RELAY_V2` (таблицы
//! `relay_records_enc` + `relay_quarantine`) и encrypted API (`*_encrypted`):
//! чувствительная часть записи хранится одним AEAD-конвертом
//! [`crate::storage::relay_at_rest`], нечитаемые/подменённые записи атомарно
//! уходят в карантин. V1-путь сохранён без изменений; SQL v2 проверен
//! `.arena/m8c2/verify_v2_sql.py` (24/24). Подключение encrypted-пути в engine
//! произойдёт вместе с Android Keystore-мостом (следующий slice).

use std::path::Path;
use std::sync::Mutex;

use rusqlite::{params, Connection, Result as SqlResult, Transaction};
use serde::{Deserialize, Serialize};

use crate::network::relay_queue::{RelayMessage, RelayValidationError};
use crate::storage::relay_at_rest::{
    build_record_aad, decrypt_record, encrypt_record, AtRestError, RelayAtRestKeySource,
};

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
// M8-C slice 2: encrypted-at-rest records (schema v2) + quarantine
// ═══════════════════════════════════════════════════════════════════
//
// V2 добавляет `relay_records_enc`: чувствительная часть записи (origin,
// chat_scope, e2e_payload, created_at_ms, hop_count) сериализуется (bincode)
// и зашифровывается одним AEAD-конвертом `relay_at_rest` в колонке `envelope`.
// Открытыми остаются только `msg_id` (PK/дедуп), `recipient` и `expires_at_ms`
// (нужны для индексов/purge и входят в AAD — подмена ломает расшифровку).
// Абсолютный expiry открыт намеренно: он и так виден в wire как ttl_secs.
//
// Миграция данных V1→V2 НЕ нужна: схема V1 никогда не выходила на устройства
// (Android compile gate для M8-B/D ни разу не запускался — нет ни одного .so
// с этим кодом). Если бы V1 когда-либо была выпущена, потребовалась бы
// отдельная миграция «прочитать V1 → зашифровать → записать V2».
//
// Зашифрованный путь — отдельные *_encrypted-методы: существующее поведение
// V1 (и вся проводка engine из M8-B/D) не меняется, пока Android Keystore-мост
// не появится (отдельный slice). Tombstones — общие для V1 и V2.

/// Вторая durable-миграция relay custody (M8-C): encrypted records + quarantine.
pub const MIGRATION_RELAY_V2: &str = "
-- Зашифрованные relay-записи: envelope = AEAD(сериализованная чувствительная часть)
CREATE TABLE IF NOT EXISTS relay_records_enc (
    msg_id        TEXT PRIMARY KEY,
    recipient     TEXT NOT NULL,
    expires_at_ms INTEGER NOT NULL,
    envelope      BLOB NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_relay_records_enc_recipient
    ON relay_records_enc(recipient);

CREATE INDEX IF NOT EXISTS idx_relay_records_enc_expires
    ON relay_records_enc(expires_at_ms);

-- Карантин: записи, которые нельзя расшифровать/проверить (неизвестная версия
-- или ключ, битые байты, невалидная запись). НЕ загружаются, НЕ показываются
-- в UI, но сохраняются для честной диагностики до истечения срока.
CREATE TABLE IF NOT EXISTS relay_quarantine (
    msg_id        TEXT PRIMARY KEY,
    recipient     TEXT NOT NULL,
    expires_at_ms INTEGER NOT NULL,
    envelope      BLOB NOT NULL,
    reason        TEXT NOT NULL,
    failed_at_ms  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_relay_quarantine_expires
    ON relay_quarantine(expires_at_ms);

INSERT OR IGNORE INTO relay_schema_version (version) VALUES (2);
";

const SQL_STORE_ENC: &str = "
INSERT OR IGNORE INTO relay_records_enc
    (msg_id, recipient, expires_at_ms, envelope)
VALUES (?1, ?2, ?3, ?4)
";

const SQL_LOAD_UNEXPIRED_ENC: &str = "
SELECT msg_id, recipient, expires_at_ms, envelope
FROM relay_records_enc
WHERE expires_at_ms > ?1
ORDER BY expires_at_ms ASC, msg_id ASC
LIMIT ?2
";

const SQL_LOAD_FOR_RECIPIENT_ENC: &str = "
SELECT msg_id, recipient, expires_at_ms, envelope
FROM relay_records_enc
WHERE recipient = ?1 AND expires_at_ms > ?2
ORDER BY expires_at_ms ASC, msg_id ASC
LIMIT ?3
";

const SQL_REMOVE_ENC: &str = "DELETE FROM relay_records_enc WHERE msg_id = ?1";
const SQL_REMOVE_FOR_RECIPIENT_ENC: &str =
    "DELETE FROM relay_records_enc WHERE recipient = ?1";
const SQL_PURGE_EXPIRED_ENC: &str =
    "DELETE FROM relay_records_enc WHERE expires_at_ms <= ?1";
const SQL_COUNT_ENC: &str = "SELECT COUNT(*) FROM relay_records_enc";

/// Переместить строку в карантин (в той же транзакции выполняется DELETE из
/// основной таблицы). `reason` — короткий стабильный код, не пользовательские
/// данные. `INSERT OR REPLACE`: повторный quarantine того же msg_id просто
/// освежает запись.
const SQL_QUARANTINE_MOVE: &str = "
INSERT OR REPLACE INTO relay_quarantine
    (msg_id, recipient, expires_at_ms, envelope, reason, failed_at_ms)
SELECT msg_id, recipient, expires_at_ms, envelope, ?2, ?3
FROM relay_records_enc
WHERE msg_id = ?1
";

const SQL_QUARANTINE_COUNT: &str = "SELECT COUNT(*) FROM relay_quarantine";
const SQL_QUARANTINE_PURGE_EXPIRED: &str =
    "DELETE FROM relay_quarantine WHERE expires_at_ms <= ?1";
const SQL_QUARANTINE_LIST: &str = "
SELECT msg_id, reason, failed_at_ms
FROM relay_quarantine
ORDER BY failed_at_ms DESC
LIMIT ?1
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

    #[error("RelayStore at-rest: {0}")]
    AtRest(#[from] AtRestError),

    #[error("RelayStore record encode/decode: {0}")]
    Encode(#[from] bincode::Error),
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
        // M8-C: encrypted records + quarantine (таблицы V2; создание пустых
        // таблиц не меняет поведение V1-пути и безопасно при повторе).
        conn.execute_batch(MIGRATION_RELAY_V2)?;
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

    // ─── M8-C slice 2: encrypted-at-rest записи (schema v2) ─────────

    /// Атомарно сохранить запись в зашифрованном виде (INSERT OR IGNORE).
    ///
    /// Порядок: `validate_durable` → сериализация чувствительной части →
    /// AEAD-шифрование текущим ключом (`current_key`) → запись. Записи,
    /// не прошедшие валидацию, НЕ шифруются и НЕ пишутся (ошибка caller'у,
    /// не quarantine: хранилище не принимает мусор на входе).
    /// Возвращает `Ok(true)` если вставлено, `Ok(false)` при дедупе.
    pub fn store_encrypted(
        &self,
        key_source: &dyn RelayAtRestKeySource,
        msg: &RelayMessage,
        now_ms: i64,
    ) -> Result<bool, RelayStoreError> {
        msg.validate_durable(now_ms)?;
        let sensitive = encode_sensitive(msg)?;
        let aad = build_record_aad(&msg.msg_id, &msg.recipient, msg.expires_at_ms);
        let key = key_source.current_key()?;
        let envelope = encrypt_record(&key, &aad, &sensitive)?;
        let conn = self.conn.lock().unwrap();
        let n = conn.execute(
            SQL_STORE_ENC,
            params![msg.msg_id, msg.recipient, msg.expires_at_ms, envelope],
        )?;
        Ok(n > 0)
    }

    /// Есть ли зашифрованная запись с таким `msg_id`.
    pub fn contains_encrypted(&self, msg_id: &str) -> Result<bool, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let c: i64 = conn.query_row(
            "SELECT COUNT(*) FROM relay_records_enc WHERE msg_id = ?1",
            params![msg_id],
            |row| row.get(0),
        )?;
        Ok(c > 0)
    }

    /// Загрузить не более `limit` не-истёкших зашифрованных записей (M8-D
    /// startup restore). Каждая: decrypt → deserialize → `validate_durable`.
    /// Отказ любой стадии = quarantine (строка перемещается в
    /// `relay_quarantine` атомарно) и НЕ возвращается. TTL не продлевается.
    pub fn load_unexpired_encrypted(
        &self,
        key_source: &dyn RelayAtRestKeySource,
        now_ms: i64,
        limit: usize,
    ) -> Result<EncryptedLoadOutcome, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        Self::load_encrypted(&conn, SQL_LOAD_UNEXPIRED_ENC, params![now_ms, limit as i64], key_source, now_ms)
    }

    /// Тот же загрузчик, но только для конкретного получателя.
    pub fn load_for_recipient_encrypted(
        &self,
        key_source: &dyn RelayAtRestKeySource,
        recipient: &str,
        now_ms: i64,
        limit: usize,
    ) -> Result<EncryptedLoadOutcome, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        Self::load_encrypted(
            &conn,
            SQL_LOAD_FOR_RECIPIENT_ENC,
            params![recipient, now_ms, limit as i64],
            key_source,
            now_ms,
        )
    }

    fn load_encrypted<P: rusqlite::Params>(
        conn: &Connection,
        sql: &str,
        query_params: P,
        key_source: &dyn RelayAtRestKeySource,
        now_ms: i64,
    ) -> Result<EncryptedLoadOutcome, RelayStoreError> {
        let mut stmt = conn.prepare(sql)?;
        let rows = stmt.query_map(query_params, |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
                row.get::<_, Vec<u8>>(3)?,
            ))
        })?;

        let mut records: Vec<RelayMessage> = Vec::new();
        let mut bad: Vec<(String, &'static str)> = Vec::new();
        for r in rows {
            let (msg_id, recipient, expires_at_ms, envelope) = r?;
            match decrypt_and_assemble(key_source, &msg_id, &recipient, expires_at_ms, &envelope, now_ms) {
                Ok(record) => records.push(record),
                Err(reason) => bad.push((msg_id, reason)),
            }
        }
        drop(stmt);

        let mut quarantined = 0usize;
        for (msg_id, reason) in bad {
            Self::quarantine_enc_row_locked(conn, &msg_id, reason, now_ms)?;
            quarantined += 1;
        }
        Ok(EncryptedLoadOutcome { records, quarantined })
    }

    /// Удалить зашифрованную запись. `true`, если она существовала.
    pub fn remove_encrypted(&self, msg_id: &str) -> Result<bool, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let n = conn.execute(SQL_REMOVE_ENC, params![msg_id])?;
        Ok(n > 0)
    }

    /// Атомарно удалить зашифрованную запись И поставить общий tombstone
    /// (receipt-cleanup для encrypted-пути; tombstone-таблица общая с V1).
    pub fn remove_encrypted_and_tombstone(
        &self,
        msg_id: &str,
        now_ms: i64,
    ) -> Result<bool, RelayStoreError> {
        let mut conn = self.conn.lock().unwrap();
        let tx: Transaction<'_> = conn.transaction()?;
        let removed = tx.execute(SQL_REMOVE_ENC, params![msg_id])?;
        tx.execute(SQL_TOMBSTONE_ADD, params![msg_id, now_ms])?;
        tx.commit()?;
        Ok(removed > 0)
    }

    /// Удалить все зашифрованные записи получателя. Число удалённых.
    pub fn remove_for_recipient_encrypted(&self, recipient: &str) -> Result<usize, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let n = conn.execute(SQL_REMOVE_FOR_RECIPIENT_ENC, params![recipient])?;
        Ok(n)
    }

    /// Удалить все истёкшие зашифрованные записи; истёкшие карантинные —
    /// тоже (БЕЗ доставки в UI). Возвращает (удалено_записей, удалено_карантин).
    pub fn purge_expired_encrypted(&self, now_ms: i64) -> Result<(usize, usize), RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let records = conn.execute(SQL_PURGE_EXPIRED_ENC, params![now_ms])?;
        let quarantined = conn.execute(SQL_QUARANTINE_PURGE_EXPIRED, params![now_ms])?;
        Ok((records, quarantined))
    }

    /// Переместить конкретную зашифрованную запись в карантин атомарно
    /// (для пост-обработки вне загрузчика, если поздний шаг отклонил запись).
    pub fn quarantine_encrypted(
        &self,
        msg_id: &str,
        reason: &'static str,
        failed_at_ms: i64,
    ) -> Result<(), RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        Self::quarantine_enc_row_locked(&conn, msg_id, reason, failed_at_ms)
    }

    fn quarantine_enc_row_locked(
        conn: &Connection,
        msg_id: &str,
        reason: &'static str,
        failed_at_ms: i64,
    ) -> Result<(), RelayStoreError> {
        // Одна транзакция: INSERT в карантин + DELETE из основной таблицы —
        // строка не может потеряться или остаться в обоих местах.
        let tx = conn.unchecked_transaction()?;
        tx.execute(SQL_QUARANTINE_MOVE, params![msg_id, reason, failed_at_ms])?;
        tx.execute(SQL_REMOVE_ENC, params![msg_id])?;
        tx.commit()?;
        Ok(())
    }

    /// Число записей в карантине (диагностика; запись ≠ UI-доставка).
    pub fn quarantine_count(&self) -> Result<usize, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let c: i64 = conn.query_row(SQL_QUARANTINE_COUNT, [], |row| row.get(0))?;
        Ok(c as usize)
    }

    /// Последние карантинные записи (msg_id, reason, failed_at_ms) — для
    /// честного diagnostics/bug-report пути (без envelope/payload байтов).
    pub fn list_quarantined(
        &self,
        limit: usize,
    ) -> Result<Vec<(String, String, i64)>, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(SQL_QUARANTINE_LIST)?;
        let rows = stmt.query_map(params![limit as i64], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?, row.get::<_, i64>(2)?))
        })?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    /// Число зашифрованных relay-записей.
    pub fn count_encrypted(&self) -> Result<usize, RelayStoreError> {
        let conn = self.conn.lock().unwrap();
        let c: i64 = conn.query_row(SQL_COUNT_ENC, [], |row| row.get(0))?;
        Ok(c as usize)
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

/// Итог загрузки зашифрованных записей: валидные записи + сколько строк
/// за время загрузки ушло в карантин (без UI и без потери молча).
#[derive(Debug, Default)]
pub struct EncryptedLoadOutcome {
    pub records: Vec<RelayMessage>,
    pub quarantined: usize,
}

/// Чувствительная часть записи (всё, кроме открытых индексных колонок).
/// Это то, что попадает ВНУТРЬ AEAD-конверта. Оформление — bincode v1;
/// эволюция этой структуры требует повышения `AT_REST_ENVELOPE_VERSION`
/// (формат конверта и формат записи версионируются вместе).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
struct SensitiveRelayRecordV1 {
    origin_sender: String,
    chat_scope: String,
    e2e_payload: Vec<u8>,
    created_at_ms: i64,
    hop_count: u8,
}

fn encode_sensitive(msg: &RelayMessage) -> Result<Vec<u8>, bincode::Error> {
    bincode::serialize(&SensitiveRelayRecordV1 {
        origin_sender: msg.origin_sender.clone(),
        chat_scope: msg.chat_scope.clone(),
        e2e_payload: msg.e2e_payload.clone(),
        created_at_ms: msg.created_at_ms,
        hop_count: msg.hop_count,
    })
}

/// Стабильные короткие коды причин карантина (НЕ пользовательские данные;
/// безопасны для diagnostics). Аутентификационный отказ намеренно один для
/// битых байтов/чужого ключа/чужого AAD — они криптографически неразличимы.
fn at_rest_reason(e: &AtRestError) -> &'static str {
    match e {
        AtRestError::UnsupportedVersion { .. } => "atrest-unsupported-version",
        AtRestError::UnknownKeyId { .. } => "atrest-unknown-key-id",
        AtRestError::KeySourceUnavailable => "atrest-key-source-unavailable",
        AtRestError::MalformedEnvelope { .. } => "atrest-malformed-envelope",
        AtRestError::PlaintextTooLarge { .. } => "atrest-plaintext-too-large",
        AtRestError::DecryptionFailed => "atrest-auth-failed",
    }
}

const REASON_DECODE_FAILED: &str = "record-decode-failed";
const REASON_VALIDATION_FAILED: &str = "record-validation-failed";

/// Расшифровать envelope, собрать полную запись и проверить
/// `validate_durable(now_ms)`. Ошибка = код причины для quarantine.
/// Сюда входит и защита от подмены открытых колонок: `msg_id`/`recipient`/
/// `expires_at_ms` — часть AAD, поэтому их подмена даёт `atrest-auth-failed`.
fn decrypt_and_assemble(
    key_source: &dyn RelayAtRestKeySource,
    msg_id: &str,
    recipient: &str,
    expires_at_ms: i64,
    envelope: &[u8],
    now_ms: i64,
) -> Result<RelayMessage, &'static str> {
    let aad = build_record_aad(msg_id, recipient, expires_at_ms);
    let sensitive_bytes =
        decrypt_record(key_source, &aad, envelope).map_err(|e| at_rest_reason(&e))?;
    let s: SensitiveRelayRecordV1 =
        bincode::deserialize(&sensitive_bytes).map_err(|_| REASON_DECODE_FAILED)?;
    let record = RelayMessage {
        msg_id: msg_id.to_string(),
        recipient: recipient.to_string(),
        origin_sender: s.origin_sender,
        chat_scope: s.chat_scope,
        e2e_payload: s.e2e_payload,
        created_at_ms: s.created_at_ms,
        expires_at_ms,
        hop_count: s.hop_count,
    };
    record
        .validate_durable(now_ms)
        .map_err(|_| REASON_VALIDATION_FAILED)?;
    Ok(record)
}

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
    use crate::storage::relay_at_rest::{RelayAtRestKey, AT_REST_KEY_BYTES};
    use std::collections::HashMap;
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
    fn test_schema_version_is_2_after_m8c() {
        // M8-C: свежая БД применяет V1+V2; актуальная версия — максимальная.
        assert_eq!(store().schema_version().unwrap(), 2);
    }

    #[test]
    fn test_migration_idempotent() {
        let s = store();
        // Повторное применение миграций не ломает схему.
        {
            let conn = s.conn.lock().unwrap();
            conn.execute_batch(MIGRATION_RELAY_V1).unwrap();
            conn.execute_batch(MIGRATION_RELAY_V2).unwrap();
        }
        assert_eq!(s.schema_version().unwrap(), 2);
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

    // ── M8-C slice 2: encrypted records / quarantine ────────────────

    /// Тестовый источник ключей (боевой — Android Keystore-мост позже).
    struct StaticKeySource {
        keys: HashMap<u16, [u8; AT_REST_KEY_BYTES]>,
        current: u16,
    }

    impl StaticKeySource {
        fn single(id: u16, byte: u8) -> Self {
            let mut keys = HashMap::new();
            keys.insert(id, [byte; AT_REST_KEY_BYTES]);
            StaticKeySource { keys, current: id }
        }
    }

    impl RelayAtRestKeySource for StaticKeySource {
        fn current_key(&self) -> Result<RelayAtRestKey, AtRestError> {
            self.keys
                .get(&self.current)
                .map(|&material| RelayAtRestKey {
                    key_id: self.current,
                    material,
                })
                .ok_or(AtRestError::KeySourceUnavailable)
        }
        fn key_by_id(&self, key_id: u16) -> Result<RelayAtRestKey, AtRestError> {
            self.keys
                .get(&key_id)
                .map(|&material| RelayAtRestKey { key_id, material })
                .ok_or(AtRestError::UnknownKeyId { key_id })
        }
    }

    fn keys() -> StaticKeySource {
        StaticKeySource::single(1, 0x5A)
    }

    /// Round-trip: все поля, включая зашифрованные origin/payload/hop,
    /// восстанавливаются точно; TTL не продлевается; порядок по expiry.
    #[test]
    fn test_enc_store_load_round_trip() {
        let s = store();
        let now = 1_000_000i64;
        let mut a = relay("a", "pk_b", now);
        a.expires_at_ms = now + 3_000;
        a.hop_count = 2;
        let mut b = relay("b", "pk_c", now);
        b.expires_at_ms = now + 1_500;
        b.e2e_payload = (0..=255u8).cycle().take(512).collect();

        assert!(s.store_encrypted(&keys(), &a, now).unwrap());
        assert!(s.store_encrypted(&keys(), &b, now).unwrap());
        assert_eq!(s.count_encrypted().unwrap(), 2);

        let out = s.load_unexpired_encrypted(&keys(), now, 10).unwrap();
        assert_eq!(out.quarantined, 0);
        assert_eq!(out.records.len(), 2);
        // expiry ASC: b раньше a.
        assert_eq!(out.records[0].msg_id, "b");
        assert_eq!(out.records[1].msg_id, "a");
        let ra = &out.records[1];
        assert_eq!(ra.origin_sender, "pk_origin");
        assert_eq!(ra.chat_scope, "chat-1");
        assert_eq!(ra.created_at_ms, now);
        assert_eq!(ra.expires_at_ms, now + 3_000); // НЕ продлён
        assert_eq!(ra.hop_count, 2);
        assert_eq!(out.records[0].e2e_payload, b.e2e_payload);
    }

    /// Хранимые байты не содержат ни payload, ни origin в открытую.
    #[test]
    fn test_enc_row_contains_no_cleartext_sensitive_bytes() {
        let s = store();
        let now = 1_000_000i64;
        let mut m = relay("m1", "pk_b", now);
        m.e2e_payload = vec![0xA5; 64];
        m.origin_sender = "pk_secret_origin".into();
        s.store_encrypted(&keys(), &m, now).unwrap();

        let (recipient, envelope): (String, Vec<u8>) = {
            let conn = s.conn.lock().unwrap();
            conn.query_row(
                "SELECT recipient, envelope FROM relay_records_enc WHERE msg_id = 'm1'",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap()
        };
        // Открытые колонки — только индексные.
        assert_eq!(recipient, "pk_b");
        let needle = [0xA5u8; 64];
        assert!(!envelope.windows(64).any(|w| w == needle.as_slice()));
        assert!(!envelope
            .windows(b"pk_secret_origin".len())
            .any(|w| w == b"pk_secret_origin"));
    }

    /// Дедуп как в V1: первый выиграл.
    #[test]
    fn test_enc_dedup_first_wins() {
        let s = store();
        let now = 1_000_000i64;
        assert!(s.store_encrypted(&keys(), &relay("m1", "pk_b", now), now).unwrap());
        assert!(!s.store_encrypted(&keys(), &relay("m1", "pk_x", now), now).unwrap());
        let out = s.load_unexpired_encrypted(&keys(), now, 10).unwrap();
        assert_eq!(out.records.len(), 1);
        assert_eq!(out.records[0].recipient, "pk_b");
    }

    /// Невалидная запись отклоняется ДО шифрования: ошибка caller'у,
    /// ничего не записано, карантин пуст (входной мусор ≠ карантин).
    #[test]
    fn test_enc_store_rejects_invalid_before_encrypt() {
        let s = store();
        let now = 1_000_000i64;
        let mut bad = relay("m1", "pk_b", now);
        bad.chat_scope = String::new();
        assert!(s.store_encrypted(&keys(), &bad, now).is_err());
        assert_eq!(s.count_encrypted().unwrap(), 0);
        assert_eq!(s.quarantine_count().unwrap(), 0);
    }

    /// Истёкшие исключаются из загрузки и удаляются purge (в т.ч. из карантина).
    #[test]
    fn test_enc_expired_excluded_and_purged() {
        let s = store();
        let now = 1_000i64;
        let mut short = relay("m1", "pk_b", now);
        short.expires_at_ms = now + 5;
        s.store_encrypted(&keys(), &short, now).unwrap();
        s.store_encrypted(&keys(), &relay("m2", "pk_b", now), now).unwrap();

        let out = s.load_unexpired_encrypted(&keys(), now + 10, 10).unwrap();
        assert_eq!(out.records.len(), 1);
        assert_eq!(out.records[0].msg_id, "m2");

        let (rec, quar) = s.purge_expired_encrypted(now + 10).unwrap();
        assert_eq!(rec, 1);
        assert_eq!(quar, 0);
        assert_eq!(s.count_encrypted().unwrap(), 1);
    }

    /// Загрузка по получателю (индекс открытой колонки работает).
    #[test]
    fn test_enc_load_for_recipient() {
        let s = store();
        let now = 1_000_000i64;
        s.store_encrypted(&keys(), &relay("m1", "pk_b", now), now).unwrap();
        s.store_encrypted(&keys(), &relay("m2", "pk_b", now), now).unwrap();
        s.store_encrypted(&keys(), &relay("m3", "pk_c", now), now).unwrap();
        let out = s
            .load_for_recipient_encrypted(&keys(), "pk_b", now, 100)
            .unwrap();
        assert_eq!(out.records.len(), 2);
        assert!(out.records.iter().all(|m| m.recipient == "pk_b"));

        let bounded = s
            .load_for_recipient_encrypted(&keys(), "pk_b", now, 1)
            .unwrap();
        assert_eq!(bounded.records.len(), 1);
    }

    /// Подмена открытой колонки recipient → AAD не сходится → запись
    /// уходит в карантин (атомарно), НЕ загружается, причина auth-failed.
    #[test]
    fn test_enc_tampered_recipient_goes_to_quarantine() {
        let s = store();
        let now = 1_000_000i64;
        s.store_encrypted(&keys(), &relay("m1", "pk_b", now), now).unwrap();
        s.store_encrypted(&keys(), &relay("m2", "pk_b", now), now).unwrap();
        {
            let conn = s.conn.lock().unwrap();
            conn.execute(
                "UPDATE relay_records_enc SET recipient = 'pk_evil' WHERE msg_id = 'm1'",
                [],
            )
            .unwrap();
        }
        let out = s.load_unexpired_encrypted(&keys(), now, 10).unwrap();
        assert_eq!(out.records.len(), 1);
        assert_eq!(out.records[0].msg_id, "m2");
        assert_eq!(out.quarantined, 1);
        // Строка перемещена: в основной таблице только валидная.
        assert_eq!(s.count_encrypted().unwrap(), 1);
        assert_eq!(s.quarantine_count().unwrap(), 1);
        let listed = s.list_quarantined(10).unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].0, "m1");
        assert_eq!(listed[0].1, "atrest-auth-failed");
    }

    /// Неизвестный key_id (data clear / другой Keystore) → карантин с
    /// кодом unknown-key-id; записи НЕ удаляются молча навсегда.
    #[test]
    fn test_enc_unknown_key_goes_to_quarantine() {
        let s = store();
        let now = 1_000_000i64;
        s.store_encrypted(&keys(), &relay("m1", "pk_b", now), now).unwrap();
        let other_keys = StaticKeySource::single(9, 0x11);
        let out = s.load_unexpired_encrypted(&other_keys, now, 10).unwrap();
        assert_eq!(out.records.len(), 0);
        assert_eq!(out.quarantined, 1);
        let listed = s.list_quarantined(10).unwrap();
        assert_eq!(listed[0].1, "atrest-unknown-key-id");
    }

    /// Неподдерживаемая версия конверта → карантин unsupported-version.
    #[test]
    fn test_enc_unsupported_version_goes_to_quarantine() {
        let s = store();
        let now = 1_000_000i64;
        s.store_encrypted(&keys(), &relay("m1", "pk_b", now), now).unwrap();
        {
            let conn = s.conn.lock().unwrap();
            let mut env: Vec<u8> = conn
                .query_row(
                    "SELECT envelope FROM relay_records_enc WHERE msg_id = 'm1'",
                    [],
                    |row| row.get(0),
                )
                .unwrap();
            env[0] = 0xEE; // несуществующая версия
            conn.execute(
                "UPDATE relay_records_enc SET envelope = ?1 WHERE msg_id = 'm1'",
                params![env],
            )
            .unwrap();
        }
        let out = s.load_unexpired_encrypted(&keys(), now, 10).unwrap();
        assert_eq!(out.records.len(), 0);
        assert_eq!(out.quarantined, 1);
        assert_eq!(s.list_quarantined(10).unwrap()[0].1, "atrest-unsupported-version");
    }

    /// Receipt-cleanup для encrypted-пути: атомарно remove + общий tombstone.
    #[test]
    fn test_enc_remove_and_tombstone_atomic() {
        let s = store();
        let now = 1_000_000i64;
        s.store_encrypted(&keys(), &relay("m1", "pk_b", now), now).unwrap();
        assert!(s.remove_encrypted_and_tombstone("m1", now + 1).unwrap());
        assert!(!s.contains_encrypted("m1").unwrap());
        // Tombstone-таблица общая с V1-путём.
        assert!(s.has_tombstone("m1").unwrap());
        // Повторно: записи нет, tombstone остаётся.
        assert!(!s.remove_encrypted_and_tombstone("m1", now + 2).unwrap());
        assert!(s.has_tombstone("m1").unwrap());
    }

    /// Истёкший карантин чистится тем же абсолютным дедлайном.
    #[test]
    fn test_quarantine_purged_by_expiry() {
        let s = store();
        let now = 1_000i64;
        let mut m = relay("m1", "pk_b", now);
        m.expires_at_ms = now + 100;
        s.store_encrypted(&keys(), &m, now).unwrap();
        {
            let conn = s.conn.lock().unwrap();
            conn.execute(
                "UPDATE relay_records_enc SET recipient = 'pk_evil' WHERE msg_id = 'm1'",
                [],
            )
            .unwrap();
        }
        let out = s.load_unexpired_encrypted(&keys(), now, 10).unwrap();
        assert_eq!(out.quarantined, 1);
        assert_eq!(s.quarantine_count().unwrap(), 1);

        let (rec, quar) = s.purge_expired_encrypted(now + 200).unwrap();
        assert_eq!(rec, 0);
        assert_eq!(quar, 1);
        assert_eq!(s.quarantine_count().unwrap(), 0);
    }

    /// Загрузка ограничена limit; валидные записи не страдают от соседства
    /// с карантинной в той же выборке.
    #[test]
    fn test_enc_load_bounded_and_mixed() {
        let s = store();
        let now = 1_000_000i64;
        s.store_encrypted(&keys(), &relay("m1", "pk_b", now), now).unwrap();
        s.store_encrypted(&keys(), &relay("m2", "pk_b", now), now).unwrap();
        s.store_encrypted(&keys(), &relay("m3", "pk_b", now), now).unwrap();
        {
            let conn = s.conn.lock().unwrap();
            conn.execute(
                "UPDATE relay_records_enc SET recipient = 'pk_evil' WHERE msg_id = 'm2'",
                [],
            )
            .unwrap();
        }
        let out = s.load_unexpired_encrypted(&keys(), now, 100).unwrap();
        assert_eq!(out.records.len(), 2);
        assert_eq!(out.quarantined, 1);

        let bounded = s.load_unexpired_encrypted(&keys(), now, 1).unwrap();
        assert_eq!(bounded.records.len(), 1);
    }

    /// V1-путь продолжает работать рядом с V2-таблицами (обратная
    /// совместимость внутри одного файла БД).
    #[test]
    fn test_v1_and_encrypted_paths_coexist() {
        let s = store();
        let now = 1_000_000i64;
        s.store(&relay("v1", "pk_b", now), now).unwrap();
        s.store_encrypted(&keys(), &relay("v2", "pk_b", now), now).unwrap();
        assert_eq!(s.count().unwrap(), 1);
        assert_eq!(s.count_encrypted().unwrap(), 1);
        assert!(s.contains("v1").unwrap());
        assert!(s.contains_encrypted("v2").unwrap());
        assert_eq!(s.load_unexpired(now, 10).unwrap().len(), 1);
        assert_eq!(
            s.load_unexpired_encrypted(&keys(), now, 10)
                .unwrap()
                .records
                .len(),
            1
        );
    }
}
