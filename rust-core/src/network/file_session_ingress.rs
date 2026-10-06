//! Durable, bounded receiver ingress for the production F4 direct-file session.
//!
//! The authenticated C1 transport must not ACK a ciphertext range merely because it reached an
//! in-memory event queue. This store commits every range and its resume identity to SQLite before
//! `FileReceiveSession` emits the transport ACK. Kotlin still performs manifest/key validation and
//! writes the canonical transfer store; the ingress copy makes a process death between the QUIC ACK
//! and that app-level work recoverable by replaying the bounded ciphertext event after restart.

use std::future::Future;
use std::path::Path;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};
use sha2::{Digest, Sha256};

use crate::crypto::keys::ED25519_PUBLIC_KEY_SIZE;
use crate::network::file_control::FILE_CONTROL_ID_BYTES;
use crate::network::file_session::{DurableFileRangeSink, FileSessionAdmission};
use crate::network::file_wire::{FileChunkDataV1, MAX_FILE_FRAME_PAYLOAD_BYTES};

/// The bridge event queue is bounded to 1,000 events, so a deliberately smaller ingress bound can
/// be replayed atomically at engine start without silently evicting a durable range before Kotlin
/// sees it. At the 256-KiB B1 ceiling this is a 64-MiB ciphertext budget.
pub const MAX_FILE_SESSION_INGRESS_RANGES: usize = 256;
pub const MAX_FILE_SESSION_INGRESS_BYTES: usize = 64 * 1024 * 1024;
pub const FILE_SESSION_INGRESS_RETENTION_MS: i64 = 7 * 24 * 60 * 60 * 1_000;

const INGRESS_SCHEMA_V1: &str = "
CREATE TABLE IF NOT EXISTS f4_file_session_admissions (
    peer_key       BLOB NOT NULL CHECK(length(peer_key) = 32),
    scope_id       BLOB NOT NULL CHECK(length(scope_id) = 16),
    expires_at_ms  INTEGER NOT NULL,
    PRIMARY KEY(peer_key, scope_id)
);
CREATE INDEX IF NOT EXISTS idx_f4_file_session_admissions_expiry
    ON f4_file_session_admissions(expires_at_ms);

CREATE TABLE IF NOT EXISTS f4_file_session_ingress (
    transfer_id             BLOB NOT NULL CHECK(length(transfer_id) = 16),
    chunk_index_be          BLOB NOT NULL CHECK(length(chunk_index_be) = 8),
    chunk_offset            INTEGER NOT NULL,
    ciphertext_chunk_len    INTEGER NOT NULL,
    ciphertext              BLOB NOT NULL CHECK(length(ciphertext) > 0 AND length(ciphertext) <= 262144),
    ciphertext_digest       BLOB NOT NULL CHECK(length(ciphertext_digest) = 32),
    created_at_ms           INTEGER NOT NULL,
    expires_at_ms           INTEGER NOT NULL,
    PRIMARY KEY(transfer_id, chunk_index_be, chunk_offset)
);
CREATE INDEX IF NOT EXISTS idx_f4_file_session_ingress_expiry
    ON f4_file_session_ingress(expires_at_ms);
CREATE INDEX IF NOT EXISTS idx_f4_file_session_ingress_created
    ON f4_file_session_ingress(created_at_ms);

CREATE TABLE IF NOT EXISTS f4_file_session_schema_version (version INTEGER PRIMARY KEY);
INSERT OR IGNORE INTO f4_file_session_schema_version(version) VALUES (1);
";

#[derive(Debug, thiserror::Error)]
pub enum FileSessionIngressError {
    #[error("file-session ingress database failed: {0}")]
    Database(String),
    #[error("file-session ingress range is invalid: {0}")]
    InvalidRange(&'static str),
    #[error("file-session ingress limit reached")]
    Capacity,
    #[error("file-session ingress found conflicting ciphertext for an acknowledged range")]
    ConflictingRange,
    #[error("file-session ingress contains malformed persisted data")]
    MalformedPersistedData,
}

/// One durable ingress database. It stores ciphertext only, never file keys, manifests, names, or
/// decoded file bytes. It is intentionally distinct from third-party FileCustody: this is the
/// recipient's crash-recovery seam for an already authenticated direct session.
pub struct FileSessionIngressStore {
    connection: Mutex<Connection>,
}

#[derive(Clone)]
pub struct FileSessionIngressAdmission {
    store: Arc<FileSessionIngressStore>,
}

#[derive(Clone)]
pub struct FileSessionIngressSink {
    store: Arc<FileSessionIngressStore>,
}

impl FileSessionIngressStore {
    pub fn open(path: &Path) -> Result<Arc<Self>, FileSessionIngressError> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        }
        let connection = Connection::open(path)
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        Self::from_connection(connection)
    }

    pub fn open_in_memory() -> Result<Arc<Self>, FileSessionIngressError> {
        let connection = Connection::open_in_memory()
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        Self::from_connection(connection)
    }

    fn from_connection(connection: Connection) -> Result<Arc<Self>, FileSessionIngressError> {
        connection
            .busy_timeout(std::time::Duration::from_secs(5))
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        connection
            .execute_batch(INGRESS_SCHEMA_V1)
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        Ok(Arc::new(Self {
            connection: Mutex::new(connection),
        }))
    }

    pub fn admission_handle(self: &Arc<Self>) -> FileSessionIngressAdmission {
        FileSessionIngressAdmission {
            store: Arc::clone(self),
        }
    }

    pub fn sink(self: &Arc<Self>) -> FileSessionIngressSink {
        FileSessionIngressSink {
            store: Arc::clone(self),
        }
    }

    /// Persist exactly one B1 ciphertext range. A duplicate is accepted only when every immutable
    /// property and the SHA-256 ciphertext digest match. It is therefore safe for reconnect/resume
    /// but never lets a changed byte overwrite an already ACKed range.
    pub fn persist_range(
        &self,
        range: &FileChunkDataV1,
        now_ms: i64,
    ) -> Result<(), FileSessionIngressError> {
        validate_range(range, now_ms)?;
        let digest: [u8; 32] = Sha256::digest(&range.ciphertext).into();
        let chunk_index = range.chunk_index.to_be_bytes();
        let offset = i64::from(range.chunk_offset);
        let whole_len = i64::from(range.ciphertext_chunk_len);
        let expires_at_ms = now_ms
            .checked_add(FILE_SESSION_INGRESS_RETENTION_MS)
            .ok_or(FileSessionIngressError::InvalidRange("ingress expiry overflow"))?;
        let mut connection = self
            .connection
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let transaction = connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        purge_expired(&transaction, now_ms)?;

        let existing: Option<(i64, Vec<u8>)> = transaction
            .query_row(
                "SELECT ciphertext_chunk_len, ciphertext_digest
                   FROM f4_file_session_ingress
                  WHERE transfer_id = ?1 AND chunk_index_be = ?2 AND chunk_offset = ?3",
                params![range.transfer_id.as_slice(), chunk_index.as_slice(), offset],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        if let Some((stored_len, stored_digest)) = existing {
            if stored_len == whole_len && stored_digest.as_slice() == digest.as_slice() {
                transaction
                    .commit()
                    .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
                return Ok(());
            }
            return Err(FileSessionIngressError::ConflictingRange);
        }

        let (stored_ranges, stored_bytes): (i64, i64) = transaction
            .query_row(
                "SELECT COUNT(*), COALESCE(SUM(length(ciphertext)), 0)
                   FROM f4_file_session_ingress",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        let next_ranges = usize::try_from(stored_ranges)
            .ok()
            .and_then(|count| count.checked_add(1))
            .ok_or(FileSessionIngressError::Capacity)?;
        let next_bytes = usize::try_from(stored_bytes)
            .ok()
            .and_then(|bytes| bytes.checked_add(range.ciphertext.len()))
            .ok_or(FileSessionIngressError::Capacity)?;
        if next_ranges > MAX_FILE_SESSION_INGRESS_RANGES
            || next_bytes > MAX_FILE_SESSION_INGRESS_BYTES
        {
            return Err(FileSessionIngressError::Capacity);
        }

        transaction
            .execute(
                "INSERT INTO f4_file_session_ingress (
                    transfer_id, chunk_index_be, chunk_offset, ciphertext_chunk_len,
                    ciphertext, ciphertext_digest, created_at_ms, expires_at_ms
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
                params![
                    range.transfer_id.as_slice(),
                    chunk_index.as_slice(),
                    offset,
                    whole_len,
                    range.ciphertext.as_slice(),
                    digest.as_slice(),
                    now_ms,
                    expires_at_ms,
                ],
            )
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        transaction
            .commit()
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))
    }

    /// Reconstructs the bounded unexpired ingress set after process restart. The caller emits each
    /// entry into the normal Android receiver path; duplicate delivery is deliberately idempotent in
    /// that path, while this durable source remains available until its absolute retention deadline.
    pub fn pending_ranges(
        &self,
        now_ms: i64,
    ) -> Result<Vec<FileChunkDataV1>, FileSessionIngressError> {
        if now_ms < 0 {
            return Err(FileSessionIngressError::InvalidRange("negative current time"));
        }
        let mut connection = self
            .connection
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let transaction = connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        purge_expired(&transaction, now_ms)?;
        let mut statement = transaction
            .prepare(
                "SELECT transfer_id, chunk_index_be, chunk_offset, ciphertext_chunk_len, ciphertext
                   FROM f4_file_session_ingress
                  ORDER BY created_at_ms ASC, transfer_id ASC, chunk_index_be ASC, chunk_offset ASC",
            )
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        let rows = statement
            .query_map([], |row| {
                Ok((
                    row.get::<_, Vec<u8>>(0)?,
                    row.get::<_, Vec<u8>>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, i64>(3)?,
                    row.get::<_, Vec<u8>>(4)?,
                ))
            })
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        let mut pending = Vec::new();
        for row in rows {
            let (transfer_id, index, offset, whole_len, ciphertext) =
                row.map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
            let transfer_id: [u8; 16] = transfer_id
                .try_into()
                .map_err(|_| FileSessionIngressError::MalformedPersistedData)?;
            let index: [u8; 8] = index
                .try_into()
                .map_err(|_| FileSessionIngressError::MalformedPersistedData)?;
            let chunk_offset = u32::try_from(offset)
                .map_err(|_| FileSessionIngressError::MalformedPersistedData)?;
            let ciphertext_chunk_len = u32::try_from(whole_len)
                .map_err(|_| FileSessionIngressError::MalformedPersistedData)?;
            let range = FileChunkDataV1 {
                transfer_id,
                chunk_index: u64::from_be_bytes(index),
                chunk_offset,
                ciphertext_chunk_len,
                ciphertext,
            };
            validate_range(&range, now_ms)?;
            pending.push(range);
        }
        drop(rows);
        drop(statement);
        transaction
            .commit()
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        Ok(pending)
    }

    fn admit_session(
        &self,
        peer_ed25519_public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
        scope_id: &[u8; FILE_CONTROL_ID_BYTES],
        expires_at_ms: i64,
        now_ms: i64,
    ) -> Result<bool, FileSessionIngressError> {
        if now_ms < 0 || expires_at_ms <= now_ms {
            return Err(FileSessionIngressError::InvalidRange("invalid session admission expiry"));
        }
        let mut connection = self
            .connection
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let transaction = connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        transaction
            .execute(
                "DELETE FROM f4_file_session_admissions WHERE expires_at_ms <= ?1",
                params![now_ms],
            )
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        let changed = transaction
            .execute(
                "INSERT OR IGNORE INTO f4_file_session_admissions (peer_key, scope_id, expires_at_ms)
                 VALUES (?1, ?2, ?3)",
                params![
                    peer_ed25519_public_key.as_slice(),
                    scope_id.as_slice(),
                    expires_at_ms,
                ],
            )
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        transaction
            .commit()
            .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
        Ok(changed == 1)
    }
}

impl FileSessionAdmission for FileSessionIngressAdmission {
    fn admit_session(
        &mut self,
        peer_ed25519_public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
        scope_id: &[u8; FILE_CONTROL_ID_BYTES],
        expires_at_ms: i64,
    ) -> Result<bool, String> {
        self.store
            .admit_session(
                peer_ed25519_public_key,
                scope_id,
                expires_at_ms,
                crate::storage::models::now_ms(),
            )
            .map_err(|error| error.to_string())
    }
}

impl DurableFileRangeSink for FileSessionIngressSink {
    fn persist_range<'a>(
        &'a mut self,
        range: &'a FileChunkDataV1,
    ) -> Pin<Box<dyn Future<Output = Result<(), String>> + Send + 'a>> {
        Box::pin(async move {
            self.store
                .persist_range(range, crate::storage::models::now_ms())
                .map_err(|error| error.to_string())
        })
    }
}

fn purge_expired(
    transaction: &rusqlite::Transaction<'_>,
    now_ms: i64,
) -> Result<(), FileSessionIngressError> {
    transaction
        .execute(
            "DELETE FROM f4_file_session_ingress WHERE expires_at_ms <= ?1",
            params![now_ms],
        )
        .map_err(|error| FileSessionIngressError::Database(error.to_string()))?;
    Ok(())
}

fn validate_range(range: &FileChunkDataV1, now_ms: i64) -> Result<(), FileSessionIngressError> {
    if now_ms < 0 {
        return Err(FileSessionIngressError::InvalidRange("negative current time"));
    }
    if range.transfer_id.iter().all(|byte| *byte == 0) {
        return Err(FileSessionIngressError::InvalidRange("all-zero transfer ID"));
    }
    if range.ciphertext.is_empty() || range.ciphertext.len() > MAX_FILE_FRAME_PAYLOAD_BYTES {
        return Err(FileSessionIngressError::InvalidRange("ciphertext range exceeds B1 bound"));
    }
    let range_end = usize::try_from(range.chunk_offset)
        .ok()
        .and_then(|offset| offset.checked_add(range.ciphertext.len()))
        .ok_or(FileSessionIngressError::InvalidRange("ciphertext range arithmetic overflow"))?;
    if range.ciphertext_chunk_len == 0 || range_end > range.ciphertext_chunk_len as usize {
        return Err(FileSessionIngressError::InvalidRange("ciphertext range exceeds whole chunk"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_800_000_000_000;

    fn range(offset: u32, data: &[u8]) -> FileChunkDataV1 {
        FileChunkDataV1 {
            transfer_id: [0x41; 16],
            chunk_index: 7,
            chunk_offset: offset,
            ciphertext_chunk_len: 8,
            ciphertext: data.to_vec(),
        }
    }

    #[test]
    fn exact_duplicate_is_idempotent_but_changed_range_is_rejected() {
        let store = FileSessionIngressStore::open_in_memory().unwrap();
        store.persist_range(&range(0, b"1234"), NOW).unwrap();
        store.persist_range(&range(0, b"1234"), NOW + 1).unwrap();
        assert_eq!(store.pending_ranges(NOW + 2).unwrap().len(), 1);
        assert!(matches!(
            store.persist_range(&range(0, b"abcd"), NOW + 2),
            Err(FileSessionIngressError::ConflictingRange)
        ));
    }

    #[test]
    fn persisted_ranges_and_session_replay_survive_a_store_handle() {
        let store = FileSessionIngressStore::open_in_memory().unwrap();
        store.persist_range(&range(0, b"1234"), NOW).unwrap();
        let restored = store.pending_ranges(NOW + 1).unwrap();
        assert_eq!(restored, vec![range(0, b"1234")]);

        let mut admission = store.admission_handle();
        let peer = [0x23; ED25519_PUBLIC_KEY_SIZE];
        let scope = [0x24; FILE_CONTROL_ID_BYTES];
        assert!(admission.admit_session(&peer, &scope, NOW + 10).unwrap());
        assert!(!admission.admit_session(&peer, &scope, NOW + 10).unwrap());
    }

    #[test]
    fn oversize_or_out_of_bounds_ciphertext_never_reaches_sqlite() {
        let store = FileSessionIngressStore::open_in_memory().unwrap();
        assert!(matches!(
            store.persist_range(&range(7, b"12"), NOW),
            Err(FileSessionIngressError::InvalidRange(_))
        ));
        let mut oversized = range(0, &vec![0x55; MAX_FILE_FRAME_PAYLOAD_BYTES + 1]);
        oversized.ciphertext_chunk_len = u32::MAX;
        assert!(matches!(
            store.persist_range(&oversized, NOW),
            Err(FileSessionIngressError::InvalidRange(_))
        ));
    }
}
