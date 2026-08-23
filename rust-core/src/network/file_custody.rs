//! Phone-owned durable custody for opaque E2E-encrypted file ranges.
//!
//! Custody is opt-in and quota-bound. The store never receives plaintext, filenames, file keys or a
//! whole file; one admitted unit is one already encrypted B1 range. SQLite transactions enforce
//! duplicate/tombstone identity, TTL, global/per-origin quotas, active-transfer/range limits and a
//! durable per-minute flood budget before bytes become visible to delivery code.

use std::collections::HashSet;
use std::path::Path;
use std::sync::Mutex;

use rusqlite::{params, Connection, OptionalExtension, Transaction, TransactionBehavior};
use sha2::{Digest, Sha256};

use crate::crypto::keys::ED25519_PUBLIC_KEY_SIZE;
use crate::network::file_wire::{FileChunkDataV1, FileFrameV1, FileWireError, MAX_FILE_CHUNK_DATA_BYTES};

pub const FILE_CUSTODY_MAX_TTL_MS: i64 = 30 * 24 * 60 * 60 * 1_000;
pub const FILE_CUSTODY_MIN_TTL_MS: i64 = 60_000;
pub const FILE_CUSTODY_MAX_TOTAL_QUOTA_BYTES: u64 = 512 * 1024 * 1024 * 1024;
pub const FILE_CUSTODY_MAX_ORIGIN_QUOTA_BYTES: u64 = 128 * 1024 * 1024 * 1024;
pub const FILE_CUSTODY_MAX_ALLOWED_ORIGINS: usize = 256;
pub const FILE_CUSTODY_MAX_ACTIVE_TRANSFERS_PER_ORIGIN: usize = 256;
pub const FILE_CUSTODY_MAX_RANGES_PER_TRANSFER: usize = 1_000_000;
pub const FILE_CUSTODY_MAX_RANGES_PER_ORIGIN_PER_MINUTE: u32 = 600;
pub const FILE_CUSTODY_MAX_LOAD_BYTES: usize = 32 * 1024 * 1024;
pub const FILE_CUSTODY_MAX_LOAD_RANGES: usize = 4_096;
pub const FILE_CUSTODY_TOMBSTONE_TTL_MS: i64 = 30 * 24 * 60 * 60 * 1_000;

const FILE_CUSTODY_DIGEST_DOMAIN: &[u8] = b"apu-file-custody-range-v1\0";
const MIN_NODE_ID_BYTES: usize = 35;
const MAX_NODE_ID_BYTES: usize = 67;

const FILE_CUSTODY_MIGRATION_V1: &str = "
CREATE TABLE IF NOT EXISTS file_custody_ranges (
    origin_key         BLOB NOT NULL CHECK(length(origin_key) = 32),
    origin_node_id     TEXT NOT NULL,
    recipient_node_id  TEXT NOT NULL,
    transfer_id        BLOB NOT NULL CHECK(length(transfer_id) = 16),
    chunk_index_be     BLOB NOT NULL CHECK(length(chunk_index_be) = 8),
    chunk_offset       INTEGER NOT NULL,
    ciphertext_chunk_len INTEGER NOT NULL,
    ciphertext_len     INTEGER NOT NULL,
    ciphertext_digest BLOB NOT NULL CHECK(length(ciphertext_digest) = 32),
    ciphertext         BLOB NOT NULL CHECK(length(ciphertext) = ciphertext_len),
    stored_at_ms       INTEGER NOT NULL,
    expires_at_ms      INTEGER NOT NULL,
    PRIMARY KEY (
        origin_key, recipient_node_id, transfer_id, chunk_index_be, chunk_offset
    )
);
CREATE INDEX IF NOT EXISTS idx_file_custody_expiry
    ON file_custody_ranges(expires_at_ms);
CREATE INDEX IF NOT EXISTS idx_file_custody_origin
    ON file_custody_ranges(origin_key, expires_at_ms);
CREATE INDEX IF NOT EXISTS idx_file_custody_transfer
    ON file_custody_ranges(origin_key, recipient_node_id, transfer_id, chunk_index_be, chunk_offset);

CREATE TABLE IF NOT EXISTS file_custody_tombstones (
    origin_key         BLOB NOT NULL CHECK(length(origin_key) = 32),
    recipient_node_id  TEXT NOT NULL,
    transfer_id        BLOB NOT NULL CHECK(length(transfer_id) = 16),
    chunk_index_be     BLOB NOT NULL CHECK(length(chunk_index_be) = 8),
    chunk_offset       INTEGER NOT NULL,
    ciphertext_digest BLOB NOT NULL CHECK(length(ciphertext_digest) = 32),
    removed_at_ms      INTEGER NOT NULL,
    PRIMARY KEY (
        origin_key, recipient_node_id, transfer_id, chunk_index_be, chunk_offset
    )
);
CREATE INDEX IF NOT EXISTS idx_file_custody_tombstone_age
    ON file_custody_tombstones(removed_at_ms);

CREATE TABLE IF NOT EXISTS file_custody_rate (
    origin_key      BLOB NOT NULL CHECK(length(origin_key) = 32),
    minute_bucket   INTEGER NOT NULL,
    accepted_count  INTEGER NOT NULL,
    PRIMARY KEY(origin_key, minute_bucket)
);

CREATE TABLE IF NOT EXISTS file_custody_schema_version (version INTEGER PRIMARY KEY);
INSERT OR IGNORE INTO file_custody_schema_version(version) VALUES (1);
";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FileCustodyMode {
    Disabled,
    ContactsOnly,
    AllowList,
    Open,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileCustodyPolicy {
    pub mode: FileCustodyMode,
    pub total_quota_bytes: u64,
    pub per_origin_quota_bytes: u64,
    pub max_active_transfers_per_origin: usize,
    pub max_ranges_per_transfer: usize,
    pub max_ranges_per_origin_per_minute: u32,
    pub max_ttl_ms: i64,
    pub allowed_origin_keys: Vec<[u8; ED25519_PUBLIC_KEY_SIZE]>,
}

impl Default for FileCustodyPolicy {
    fn default() -> Self {
        Self {
            // No phone stores other people's files until its owner explicitly opts in.
            mode: FileCustodyMode::Disabled,
            total_quota_bytes: 2 * 1024 * 1024 * 1024,
            per_origin_quota_bytes: 512 * 1024 * 1024,
            max_active_transfers_per_origin: 32,
            max_ranges_per_transfer: 65_536,
            max_ranges_per_origin_per_minute: 120,
            max_ttl_ms: 7 * 24 * 60 * 60 * 1_000,
            allowed_origin_keys: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileCustodyPeer {
    pub node_id: String,
    pub ed25519_public_key: [u8; ED25519_PUBLIC_KEY_SIZE],
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct FileCustodyRangeId {
    pub origin_ed25519_public_key: [u8; ED25519_PUBLIC_KEY_SIZE],
    pub recipient_node_id: String,
    pub transfer_id: [u8; 16],
    pub chunk_index: u64,
    pub chunk_offset: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileCustodyReceiptClaimsV1 {
    pub range_id: FileCustodyRangeId,
    pub ciphertext_chunk_len: u32,
    pub ciphertext_len: u32,
    pub ciphertext_digest: [u8; 32],
    pub stored_at_ms: i64,
    pub expires_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredFileCustodyRange {
    pub receipt: FileCustodyReceiptClaimsV1,
    pub ciphertext_chunk_len: u32,
    pub ciphertext: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FileCustodyStoreOutcome {
    Stored(FileCustodyReceiptClaimsV1),
    AlreadyStored(FileCustodyReceiptClaimsV1),
}

impl FileCustodyStoreOutcome {
    pub fn receipt(&self) -> &FileCustodyReceiptClaimsV1 {
        match self {
            Self::Stored(receipt) | Self::AlreadyStored(receipt) => receipt,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum FileCustodyError {
    #[error("file custody policy is invalid: {0}")]
    InvalidPolicy(&'static str),
    #[error("file custody is disabled or this origin is not authorized")]
    PolicyDenied,
    #[error("file custody request is invalid: {0}")]
    InvalidRequest(&'static str),
    #[error("file custody range failed canonical wire validation: {0}")]
    Wire(String),
    #[error("file custody global quota exceeded")]
    GlobalQuotaExceeded,
    #[error("file custody per-origin quota exceeded")]
    OriginQuotaExceeded,
    #[error("file custody active-transfer limit exceeded")]
    TransferLimitExceeded,
    #[error("file custody range-count limit exceeded")]
    RangeLimitExceeded,
    #[error("file custody durable flood limit exceeded")]
    RateLimitExceeded,
    #[error("file custody range identity conflicts with different ciphertext")]
    ConflictingRange,
    #[error("file custody range was already removed and tombstoned")]
    Tombstoned,
    #[error("file custody range is not stored")]
    NotFound,
    #[error("file custody bounded load request is invalid")]
    InvalidLoadLimit,
    #[error("file custody SQLite is full")]
    DiskFull,
    #[error("file custody durable store failed: {0}")]
    Store(String),
}

impl From<FileWireError> for FileCustodyError {
    fn from(error: FileWireError) -> Self {
        Self::Wire(error.to_string())
    }
}

pub struct FileCustodyStore {
    connection: Mutex<Connection>,
    policy: FileCustodyPolicy,
}

impl FileCustodyPolicy {
    pub fn validate(&self) -> Result<(), FileCustodyError> {
        if self.total_quota_bytes == 0
            || self.total_quota_bytes > FILE_CUSTODY_MAX_TOTAL_QUOTA_BYTES
            || self.per_origin_quota_bytes == 0
            || self.per_origin_quota_bytes > self.total_quota_bytes
            || self.per_origin_quota_bytes > FILE_CUSTODY_MAX_ORIGIN_QUOTA_BYTES
        {
            return Err(FileCustodyError::InvalidPolicy("invalid byte quota"));
        }
        if self.max_active_transfers_per_origin == 0
            || self.max_active_transfers_per_origin > FILE_CUSTODY_MAX_ACTIVE_TRANSFERS_PER_ORIGIN
            || self.max_ranges_per_transfer == 0
            || self.max_ranges_per_transfer > FILE_CUSTODY_MAX_RANGES_PER_TRANSFER
            || self.max_ranges_per_origin_per_minute == 0
            || self.max_ranges_per_origin_per_minute
                > FILE_CUSTODY_MAX_RANGES_PER_ORIGIN_PER_MINUTE
        {
            return Err(FileCustodyError::InvalidPolicy("invalid count/rate limit"));
        }
        if self.max_ttl_ms < FILE_CUSTODY_MIN_TTL_MS
            || self.max_ttl_ms > FILE_CUSTODY_MAX_TTL_MS
        {
            return Err(FileCustodyError::InvalidPolicy("invalid TTL limit"));
        }
        if self.allowed_origin_keys.len() > FILE_CUSTODY_MAX_ALLOWED_ORIGINS {
            return Err(FileCustodyError::InvalidPolicy("allow-list is too large"));
        }
        let unique = self.allowed_origin_keys.iter().collect::<HashSet<_>>();
        if unique.len() != self.allowed_origin_keys.len() {
            return Err(FileCustodyError::InvalidPolicy("allow-list contains duplicates"));
        }
        Ok(())
    }

    fn permits(&self, origin: &FileCustodyPeer, origin_is_contact: bool) -> bool {
        match self.mode {
            FileCustodyMode::Disabled => false,
            FileCustodyMode::ContactsOnly => origin_is_contact,
            FileCustodyMode::AllowList => self
                .allowed_origin_keys
                .contains(&origin.ed25519_public_key),
            FileCustodyMode::Open => true,
        }
    }
}

impl FileCustodyStore {
    pub fn open<P: AsRef<Path>>(
        path: P,
        policy: FileCustodyPolicy,
    ) -> Result<Self, FileCustodyError> {
        Self::from_connection(Connection::open(path).map_err(map_store_error)?, policy)
    }

    pub fn open_in_memory(policy: FileCustodyPolicy) -> Result<Self, FileCustodyError> {
        Self::from_connection(Connection::open_in_memory().map_err(map_store_error)?, policy)
    }

    fn from_connection(
        connection: Connection,
        policy: FileCustodyPolicy,
    ) -> Result<Self, FileCustodyError> {
        policy.validate()?;
        connection
            .execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;")
            .map_err(map_store_error)?;
        connection
            .execute_batch(FILE_CUSTODY_MIGRATION_V1)
            .map_err(map_store_error)?;
        Ok(Self {
            connection: Mutex::new(connection),
            policy,
        })
    }

    pub fn policy(&self) -> &FileCustodyPolicy {
        &self.policy
    }

    pub fn schema_version(&self) -> Result<i64, FileCustodyError> {
        let connection = self.connection.lock().map_err(|_| store_poisoned())?;
        connection
            .query_row(
                "SELECT version FROM file_custody_schema_version ORDER BY version DESC LIMIT 1",
                [],
                |row| row.get(0),
            )
            .map_err(map_store_error)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn store_range(
        &self,
        origin: &FileCustodyPeer,
        origin_is_contact: bool,
        recipient_node_id: &str,
        range: &FileChunkDataV1,
        stored_at_ms: i64,
        expires_at_ms: i64,
    ) -> Result<FileCustodyStoreOutcome, FileCustodyError> {
        validate_request(
            &self.policy,
            origin,
            origin_is_contact,
            recipient_node_id,
            range,
            stored_at_ms,
            expires_at_ms,
        )?;
        // Force the same canonical B1 validation as the direct data plane before touching SQLite.
        FileFrameV1::ChunkData(range.clone()).encode()?;
        let digest = custody_digest(range);
        let range_id = FileCustodyRangeId {
            origin_ed25519_public_key: origin.ed25519_public_key,
            recipient_node_id: recipient_node_id.to_owned(),
            transfer_id: range.transfer_id,
            chunk_index: range.chunk_index,
            chunk_offset: range.chunk_offset,
        };
        let receipt = FileCustodyReceiptClaimsV1 {
            range_id: range_id.clone(),
            ciphertext_chunk_len: range.ciphertext_chunk_len,
            ciphertext_len: u32::try_from(range.ciphertext.len())
                .map_err(|_| FileCustodyError::InvalidRequest("range length overflow"))?,
            ciphertext_digest: digest,
            stored_at_ms,
            expires_at_ms,
        };
        let mut connection = self.connection.lock().map_err(|_| store_poisoned())?;
        let transaction = connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(map_store_error)?;
        // Expired bytes cannot keep consuming quota. Cleanup shares the admission transaction, so a
        // crash exposes either the old state or the complete cleanup plus newly admitted range.
        transaction
            .execute(
                "DELETE FROM file_custody_ranges WHERE expires_at_ms <= ?1",
                params![stored_at_ms],
            )
            .map_err(map_store_error)?;
        transaction
            .execute(
                "DELETE FROM file_custody_rate WHERE minute_bucket < ?1",
                params![stored_at_ms.div_euclid(60_000).saturating_sub(1)],
            )
            .map_err(map_store_error)?;

        if tombstone_digest(&transaction, &range_id)?.is_some() {
            return Err(FileCustodyError::Tombstoned);
        }
        if let Some((
            existing_digest,
            existing_chunk_len,
            existing_len,
            existing_stored,
            existing_expiry,
        )) = existing_range(&transaction, &range_id)?
        {
            if existing_digest != digest
                || existing_chunk_len != receipt.ciphertext_chunk_len
                || existing_len != receipt.ciphertext_len
            {
                return Err(FileCustodyError::ConflictingRange);
            }
            transaction.commit().map_err(map_store_error)?;
            return Ok(FileCustodyStoreOutcome::AlreadyStored(
                FileCustodyReceiptClaimsV1 {
                    stored_at_ms: existing_stored,
                    expires_at_ms: existing_expiry,
                    ..receipt
                },
            ));
        }

        enforce_rate_limit(&transaction, &self.policy, origin, stored_at_ms)?;
        enforce_quotas(&transaction, &self.policy, origin, range.ciphertext.len())?;
        enforce_transfer_limits(
            &transaction,
            &self.policy,
            origin,
            recipient_node_id,
            &range.transfer_id,
        )?;

        transaction
            .execute(
                "INSERT INTO file_custody_ranges
                 (origin_key, origin_node_id, recipient_node_id, transfer_id, chunk_index_be,
                  chunk_offset, ciphertext_chunk_len, ciphertext_len, ciphertext_digest,
                  ciphertext, stored_at_ms, expires_at_ms)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
                params![
                    origin.ed25519_public_key.as_slice(),
                    origin.node_id,
                    recipient_node_id,
                    range.transfer_id.as_slice(),
                    range.chunk_index.to_be_bytes().as_slice(),
                    i64::from(range.chunk_offset),
                    i64::from(receipt.ciphertext_chunk_len),
                    i64::from(receipt.ciphertext_len),
                    digest.as_slice(),
                    range.ciphertext,
                    stored_at_ms,
                    expires_at_ms,
                ],
            )
            .map_err(map_store_error)?;
        increment_rate(&transaction, origin, stored_at_ms)?;
        transaction.commit().map_err(map_store_error)?;
        Ok(FileCustodyStoreOutcome::Stored(receipt))
    }

    pub fn inventory(
        &self,
        origin: &FileCustodyPeer,
        recipient_node_id: &str,
        transfer_id: &[u8; 16],
        now_ms: i64,
        limit: usize,
    ) -> Result<Vec<FileCustodyReceiptClaimsV1>, FileCustodyError> {
        validate_load_limit(limit, 1)?;
        let connection = self.connection.lock().map_err(|_| store_poisoned())?;
        let mut statement = connection
            .prepare(
                "SELECT chunk_index_be, chunk_offset, ciphertext_chunk_len, ciphertext_len,
                        ciphertext_digest, stored_at_ms, expires_at_ms
                 FROM file_custody_ranges
                 WHERE origin_key = ?1 AND recipient_node_id = ?2 AND transfer_id = ?3
                   AND expires_at_ms > ?4
                 ORDER BY chunk_index_be ASC, chunk_offset ASC
                 LIMIT ?5",
            )
            .map_err(map_store_error)?;
        let rows = statement
            .query_map(
                params![
                    origin.ed25519_public_key.as_slice(),
                    recipient_node_id,
                    transfer_id.as_slice(),
                    now_ms,
                    limit as i64,
                ],
                |row| {
                    Ok((
                        row.get::<_, Vec<u8>>(0)?,
                        row.get::<_, i64>(1)?,
                        row.get::<_, i64>(2)?,
                        row.get::<_, i64>(3)?,
                        row.get::<_, Vec<u8>>(4)?,
                        row.get::<_, i64>(5)?,
                        row.get::<_, i64>(6)?,
                    ))
                },
            )
            .map_err(map_store_error)?;
        let mut inventory = Vec::new();
        for row in rows {
            let (
                chunk_index,
                chunk_offset,
                ciphertext_chunk_len,
                ciphertext_len,
                digest,
                stored_at_ms,
                expires_at_ms,
            ) = row.map_err(map_store_error)?;
            inventory.push(receipt_from_row(
                origin,
                recipient_node_id,
                transfer_id,
                &chunk_index,
                chunk_offset,
                ciphertext_chunk_len,
                ciphertext_len,
                &digest,
                stored_at_ms,
                expires_at_ms,
            )?);
        }
        Ok(inventory)
    }

    pub fn load_for_delivery(
        &self,
        origin: &FileCustodyPeer,
        recipient_node_id: &str,
        transfer_id: &[u8; 16],
        now_ms: i64,
        max_ranges: usize,
        max_bytes: usize,
    ) -> Result<Vec<StoredFileCustodyRange>, FileCustodyError> {
        validate_load_limit(max_ranges, max_bytes)?;
        let connection = self.connection.lock().map_err(|_| store_poisoned())?;
        let mut statement = connection
            .prepare(
                "SELECT chunk_index_be, chunk_offset, ciphertext_chunk_len, ciphertext_len,
                        ciphertext_digest, ciphertext, stored_at_ms, expires_at_ms
                 FROM file_custody_ranges
                 WHERE origin_key = ?1 AND recipient_node_id = ?2 AND transfer_id = ?3
                   AND expires_at_ms > ?4
                 ORDER BY chunk_index_be ASC, chunk_offset ASC
                 LIMIT ?5",
            )
            .map_err(map_store_error)?;
        let rows = statement
            .query_map(
                params![
                    origin.ed25519_public_key.as_slice(),
                    recipient_node_id,
                    transfer_id.as_slice(),
                    now_ms,
                    max_ranges as i64,
                ],
                |row| {
                    Ok((
                        row.get::<_, Vec<u8>>(0)?,
                        row.get::<_, i64>(1)?,
                        row.get::<_, i64>(2)?,
                        row.get::<_, i64>(3)?,
                        row.get::<_, Vec<u8>>(4)?,
                        row.get::<_, Vec<u8>>(5)?,
                        row.get::<_, i64>(6)?,
                        row.get::<_, i64>(7)?,
                    ))
                },
            )
            .map_err(map_store_error)?;
        let mut loaded = Vec::new();
        let mut loaded_bytes = 0usize;
        for row in rows {
            let (
                chunk_index,
                chunk_offset,
                ciphertext_chunk_len,
                ciphertext_len,
                digest,
                ciphertext,
                stored,
                expiry,
            ) = row.map_err(map_store_error)?;
            let next_bytes = loaded_bytes
                .checked_add(ciphertext.len())
                .ok_or(FileCustodyError::InvalidLoadLimit)?;
            if next_bytes > max_bytes {
                break;
            }
            let receipt = receipt_from_row(
                origin,
                recipient_node_id,
                transfer_id,
                &chunk_index,
                chunk_offset,
                ciphertext_chunk_len,
                ciphertext_len,
                &digest,
                stored,
                expiry,
            )?;
            if custody_digest_parts(
                &receipt.range_id,
                receipt.ciphertext_chunk_len,
                u32::try_from(ciphertext.len())
                    .map_err(|_| FileCustodyError::InvalidRequest("stored range too large"))?,
                &ciphertext,
            ) != receipt.ciphertext_digest
            {
                return Err(FileCustodyError::Store("stored ciphertext digest mismatch".into()));
            }
            loaded_bytes = next_bytes;
            loaded.push(StoredFileCustodyRange {
                ciphertext_chunk_len: receipt.ciphertext_chunk_len,
                receipt,
                ciphertext,
            });
        }
        Ok(loaded)
    }

    pub fn remove_with_tombstone(
        &self,
        receipt: &FileCustodyReceiptClaimsV1,
        removed_at_ms: i64,
    ) -> Result<(), FileCustodyError> {
        let mut connection = self.connection.lock().map_err(|_| store_poisoned())?;
        let transaction = connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(map_store_error)?;
        let deleted = transaction
            .execute(
                "DELETE FROM file_custody_ranges
                 WHERE origin_key = ?1 AND recipient_node_id = ?2 AND transfer_id = ?3
                   AND chunk_index_be = ?4 AND chunk_offset = ?5
                   AND ciphertext_digest = ?6",
                params![
                    receipt.range_id.origin_ed25519_public_key.as_slice(),
                    receipt.range_id.recipient_node_id,
                    receipt.range_id.transfer_id.as_slice(),
                    receipt.range_id.chunk_index.to_be_bytes().as_slice(),
                    i64::from(receipt.range_id.chunk_offset),
                    receipt.ciphertext_digest.as_slice(),
                ],
            )
            .map_err(map_store_error)?;
        if deleted == 0 {
            return Err(FileCustodyError::NotFound);
        }
        transaction
            .execute(
                "INSERT OR REPLACE INTO file_custody_tombstones
                 (origin_key, recipient_node_id, transfer_id, chunk_index_be, chunk_offset,
                  ciphertext_digest, removed_at_ms)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![
                    receipt.range_id.origin_ed25519_public_key.as_slice(),
                    receipt.range_id.recipient_node_id,
                    receipt.range_id.transfer_id.as_slice(),
                    receipt.range_id.chunk_index.to_be_bytes().as_slice(),
                    i64::from(receipt.range_id.chunk_offset),
                    receipt.ciphertext_digest.as_slice(),
                    removed_at_ms,
                ],
            )
            .map_err(map_store_error)?;
        transaction.commit().map_err(map_store_error)
    }

    pub fn purge_expired(&self, now_ms: i64) -> Result<(usize, usize), FileCustodyError> {
        let mut connection = self.connection.lock().map_err(|_| store_poisoned())?;
        let transaction = connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(map_store_error)?;
        let ranges = transaction
            .execute(
                "DELETE FROM file_custody_ranges WHERE expires_at_ms <= ?1",
                params![now_ms],
            )
            .map_err(map_store_error)?;
        let tombstones = transaction
            .execute(
                "DELETE FROM file_custody_tombstones WHERE removed_at_ms <= ?1",
                params![now_ms.saturating_sub(FILE_CUSTODY_TOMBSTONE_TTL_MS)],
            )
            .map_err(map_store_error)?;
        transaction
            .execute(
                "DELETE FROM file_custody_rate WHERE minute_bucket < ?1",
                params![now_ms.div_euclid(60_000).saturating_sub(1)],
            )
            .map_err(map_store_error)?;
        transaction.commit().map_err(map_store_error)?;
        Ok((ranges, tombstones))
    }

    pub fn usage_bytes(&self) -> Result<u64, FileCustodyError> {
        let connection = self.connection.lock().map_err(|_| store_poisoned())?;
        query_usage(&connection, None)
    }

    pub fn origin_usage_bytes(&self, origin: &FileCustodyPeer) -> Result<u64, FileCustodyError> {
        let connection = self.connection.lock().map_err(|_| store_poisoned())?;
        query_usage(&connection, Some(&origin.ed25519_public_key))
    }
}

fn validate_request(
    policy: &FileCustodyPolicy,
    origin: &FileCustodyPeer,
    origin_is_contact: bool,
    recipient_node_id: &str,
    range: &FileChunkDataV1,
    stored_at_ms: i64,
    expires_at_ms: i64,
) -> Result<(), FileCustodyError> {
    policy.validate()?;
    if !is_canonical_node_id(&origin.node_id)
        || !is_canonical_node_id(recipient_node_id)
        || origin.node_id == recipient_node_id
    {
        return Err(FileCustodyError::InvalidRequest("invalid origin/recipient identity"));
    }
    if !policy.permits(origin, origin_is_contact) {
        return Err(FileCustodyError::PolicyDenied);
    }
    let ttl = expires_at_ms.saturating_sub(stored_at_ms);
    if stored_at_ms < 0
        || ttl < FILE_CUSTODY_MIN_TTL_MS
        || ttl > policy.max_ttl_ms
        || ttl > FILE_CUSTODY_MAX_TTL_MS
    {
        return Err(FileCustodyError::InvalidRequest("invalid custody TTL"));
    }
    if range.ciphertext.is_empty() || range.ciphertext.len() > MAX_FILE_CHUNK_DATA_BYTES {
        return Err(FileCustodyError::InvalidRequest("invalid bounded ciphertext range"));
    }
    Ok(())
}

fn enforce_rate_limit(
    transaction: &Transaction<'_>,
    policy: &FileCustodyPolicy,
    origin: &FileCustodyPeer,
    now_ms: i64,
) -> Result<(), FileCustodyError> {
    let bucket = now_ms.div_euclid(60_000);
    let count: i64 = transaction
        .query_row(
            "SELECT accepted_count FROM file_custody_rate
             WHERE origin_key = ?1 AND minute_bucket = ?2",
            params![origin.ed25519_public_key.as_slice(), bucket],
            |row| row.get(0),
        )
        .optional()
        .map_err(map_store_error)?
        .unwrap_or(0);
    if count >= i64::from(policy.max_ranges_per_origin_per_minute) {
        return Err(FileCustodyError::RateLimitExceeded);
    }
    Ok(())
}

fn increment_rate(
    transaction: &Transaction<'_>,
    origin: &FileCustodyPeer,
    now_ms: i64,
) -> Result<(), FileCustodyError> {
    transaction
        .execute(
            "INSERT INTO file_custody_rate(origin_key, minute_bucket, accepted_count)
             VALUES (?1, ?2, 1)
             ON CONFLICT(origin_key, minute_bucket)
             DO UPDATE SET accepted_count = accepted_count + 1",
            params![origin.ed25519_public_key.as_slice(), now_ms.div_euclid(60_000)],
        )
        .map_err(map_store_error)?;
    Ok(())
}

fn enforce_quotas(
    transaction: &Transaction<'_>,
    policy: &FileCustodyPolicy,
    origin: &FileCustodyPeer,
    incoming_bytes: usize,
) -> Result<(), FileCustodyError> {
    let incoming = u64::try_from(incoming_bytes)
        .map_err(|_| FileCustodyError::InvalidRequest("range size overflow"))?;
    let global = query_usage(transaction, None)?;
    if global.checked_add(incoming).filter(|total| *total <= policy.total_quota_bytes).is_none() {
        return Err(FileCustodyError::GlobalQuotaExceeded);
    }
    let origin_usage = query_usage(transaction, Some(&origin.ed25519_public_key))?;
    if origin_usage
        .checked_add(incoming)
        .filter(|total| *total <= policy.per_origin_quota_bytes)
        .is_none()
    {
        return Err(FileCustodyError::OriginQuotaExceeded);
    }
    Ok(())
}

fn enforce_transfer_limits(
    transaction: &Transaction<'_>,
    policy: &FileCustodyPolicy,
    origin: &FileCustodyPeer,
    recipient_node_id: &str,
    transfer_id: &[u8; 16],
) -> Result<(), FileCustodyError> {
    let transfer_exists: i64 = transaction
        .query_row(
            "SELECT COUNT(*) FROM file_custody_ranges
             WHERE origin_key = ?1 AND recipient_node_id = ?2 AND transfer_id = ?3",
            params![origin.ed25519_public_key.as_slice(), recipient_node_id, transfer_id.as_slice()],
            |row| row.get(0),
        )
        .map_err(map_store_error)?;
    if transfer_exists == 0 {
        let transfers: i64 = transaction
            .query_row(
                "SELECT COUNT(DISTINCT hex(transfer_id) || ':' || recipient_node_id)
                 FROM file_custody_ranges WHERE origin_key = ?1",
                params![origin.ed25519_public_key.as_slice()],
                |row| row.get(0),
            )
            .map_err(map_store_error)?;
        if transfers >= policy.max_active_transfers_per_origin as i64 {
            return Err(FileCustodyError::TransferLimitExceeded);
        }
    }
    let ranges: i64 = transaction
        .query_row(
            "SELECT COUNT(*) FROM file_custody_ranges
             WHERE origin_key = ?1 AND recipient_node_id = ?2 AND transfer_id = ?3",
            params![origin.ed25519_public_key.as_slice(), recipient_node_id, transfer_id.as_slice()],
            |row| row.get(0),
        )
        .map_err(map_store_error)?;
    if ranges >= policy.max_ranges_per_transfer as i64 {
        return Err(FileCustodyError::RangeLimitExceeded);
    }
    Ok(())
}

fn existing_range(
    transaction: &Transaction<'_>,
    id: &FileCustodyRangeId,
) -> Result<Option<([u8; 32], u32, u32, i64, i64)>, FileCustodyError> {
    let row = transaction
        .query_row(
            "SELECT ciphertext_digest, ciphertext_chunk_len, ciphertext_len, stored_at_ms,
                    expires_at_ms
             FROM file_custody_ranges
             WHERE origin_key = ?1 AND recipient_node_id = ?2 AND transfer_id = ?3
               AND chunk_index_be = ?4 AND chunk_offset = ?5",
            params![
                id.origin_ed25519_public_key.as_slice(),
                id.recipient_node_id,
                id.transfer_id.as_slice(),
                id.chunk_index.to_be_bytes().as_slice(),
                i64::from(id.chunk_offset),
            ],
            |row| {
                Ok((
                    row.get::<_, Vec<u8>>(0)?,
                    row.get::<_, i64>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, i64>(3)?,
                    row.get::<_, i64>(4)?,
                ))
            },
        )
        .optional()
        .map_err(map_store_error)?;
    row.map(|(digest, chunk_len, len, stored, expiry)| {
        Ok((
            digest.try_into().map_err(|_| FileCustodyError::Store("invalid digest length".into()))?,
            u32::try_from(chunk_len)
                .map_err(|_| FileCustodyError::Store("invalid chunk length".into()))?,
            u32::try_from(len).map_err(|_| FileCustodyError::Store("invalid range length".into()))?,
            stored,
            expiry,
        ))
    })
    .transpose()
}

fn tombstone_digest(
    transaction: &Transaction<'_>,
    id: &FileCustodyRangeId,
) -> Result<Option<[u8; 32]>, FileCustodyError> {
    transaction
        .query_row(
            "SELECT ciphertext_digest FROM file_custody_tombstones
             WHERE origin_key = ?1 AND recipient_node_id = ?2 AND transfer_id = ?3
               AND chunk_index_be = ?4 AND chunk_offset = ?5",
            params![
                id.origin_ed25519_public_key.as_slice(),
                id.recipient_node_id,
                id.transfer_id.as_slice(),
                id.chunk_index.to_be_bytes().as_slice(),
                i64::from(id.chunk_offset),
            ],
            |row| row.get::<_, Vec<u8>>(0),
        )
        .optional()
        .map_err(map_store_error)?
        .map(|digest| {
            digest.try_into().map_err(|_| FileCustodyError::Store("invalid tombstone digest".into()))
        })
        .transpose()
}

fn receipt_from_row(
    origin: &FileCustodyPeer,
    recipient_node_id: &str,
    transfer_id: &[u8; 16],
    chunk_index: &[u8],
    chunk_offset: i64,
    ciphertext_chunk_len: i64,
    ciphertext_len: i64,
    digest: &[u8],
    stored_at_ms: i64,
    expires_at_ms: i64,
) -> Result<FileCustodyReceiptClaimsV1, FileCustodyError> {
    Ok(FileCustodyReceiptClaimsV1 {
        range_id: FileCustodyRangeId {
            origin_ed25519_public_key: origin.ed25519_public_key,
            recipient_node_id: recipient_node_id.to_owned(),
            transfer_id: *transfer_id,
            chunk_index: u64::from_be_bytes(
                chunk_index.try_into().map_err(|_| FileCustodyError::Store("invalid chunk index".into()))?,
            ),
            chunk_offset: u32::try_from(chunk_offset)
                .map_err(|_| FileCustodyError::Store("invalid chunk offset".into()))?,
        },
        ciphertext_chunk_len: u32::try_from(ciphertext_chunk_len)
            .map_err(|_| FileCustodyError::Store("invalid complete chunk length".into()))?,
        ciphertext_len: u32::try_from(ciphertext_len)
            .map_err(|_| FileCustodyError::Store("invalid ciphertext length".into()))?,
        ciphertext_digest: digest
            .try_into()
            .map_err(|_| FileCustodyError::Store("invalid digest".into()))?,
        stored_at_ms,
        expires_at_ms,
    })
}

fn custody_digest(range: &FileChunkDataV1) -> [u8; 32] {
    let id = FileCustodyRangeId {
        origin_ed25519_public_key: [0u8; ED25519_PUBLIC_KEY_SIZE],
        recipient_node_id: String::new(),
        transfer_id: range.transfer_id,
        chunk_index: range.chunk_index,
        chunk_offset: range.chunk_offset,
    };
    custody_digest_parts(
        &id,
        range.ciphertext_chunk_len,
        u32::try_from(range.ciphertext.len()).unwrap_or(u32::MAX),
        &range.ciphertext,
    )
}

fn custody_digest_parts(
    id: &FileCustodyRangeId,
    chunk_length: u32,
    length: u32,
    ciphertext: &[u8],
) -> [u8; 32] {
    let mut digest = Sha256::new();
    digest.update(FILE_CUSTODY_DIGEST_DOMAIN);
    digest.update(id.transfer_id);
    digest.update(id.chunk_index.to_be_bytes());
    digest.update(id.chunk_offset.to_be_bytes());
    digest.update(chunk_length.to_be_bytes());
    digest.update(length.to_be_bytes());
    digest.update(ciphertext);
    digest.finalize().into()
}

fn validate_load_limit(max_ranges: usize, max_bytes: usize) -> Result<(), FileCustodyError> {
    if max_ranges == 0
        || max_ranges > FILE_CUSTODY_MAX_LOAD_RANGES
        || max_bytes == 0
        || max_bytes > FILE_CUSTODY_MAX_LOAD_BYTES
    {
        return Err(FileCustodyError::InvalidLoadLimit);
    }
    Ok(())
}

fn query_usage(
    connection: &Connection,
    origin_key: Option<&[u8; ED25519_PUBLIC_KEY_SIZE]>,
) -> Result<u64, FileCustodyError> {
    let value: i64 = if let Some(origin_key) = origin_key {
        connection
            .query_row(
                "SELECT COALESCE(SUM(ciphertext_len), 0) FROM file_custody_ranges
                 WHERE origin_key = ?1",
                params![origin_key.as_slice()],
                |row| row.get(0),
            )
            .map_err(map_store_error)?
    } else {
        connection
            .query_row(
                "SELECT COALESCE(SUM(ciphertext_len), 0) FROM file_custody_ranges",
                [],
                |row| row.get(0),
            )
            .map_err(map_store_error)?
    };
    u64::try_from(value).map_err(|_| FileCustodyError::Store("invalid quota usage".into()))
}

fn is_canonical_node_id(node_id: &str) -> bool {
    (node_id.len() == MIN_NODE_ID_BYTES || node_id.len() == MAX_NODE_ID_BYTES)
        && node_id.starts_with("pk_")
        && node_id[3..]
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn map_store_error(error: rusqlite::Error) -> FileCustodyError {
    if matches!(
        error.sqlite_error_code(),
        Some(rusqlite::ErrorCode::DiskFull)
    ) {
        FileCustodyError::DiskFull
    } else {
        FileCustodyError::Store(error.to_string())
    }
}

fn store_poisoned() -> FileCustodyError {
    FileCustodyError::Store("SQLite mutex poisoned".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_900_000_000_000;

    fn node(byte: u8) -> String {
        format!("pk_{}", format!("{byte:02x}").repeat(32))
    }

    fn peer(byte: u8) -> FileCustodyPeer {
        FileCustodyPeer {
            node_id: node(byte),
            ed25519_public_key: [byte; ED25519_PUBLIC_KEY_SIZE],
        }
    }

    fn range(index: u64, byte: u8, size: usize) -> FileChunkDataV1 {
        FileChunkDataV1 {
            transfer_id: [0x44; 16],
            chunk_index: index,
            chunk_offset: 0,
            ciphertext_chunk_len: u32::try_from(size).unwrap(),
            ciphertext: vec![byte; size],
        }
    }

    fn policy(mode: FileCustodyMode) -> FileCustodyPolicy {
        FileCustodyPolicy {
            mode,
            total_quota_bytes: 4 * 1024,
            per_origin_quota_bytes: 2 * 1024,
            max_active_transfers_per_origin: 2,
            max_ranges_per_transfer: 4,
            max_ranges_per_origin_per_minute: 3,
            max_ttl_ms: 2 * FILE_CUSTODY_MIN_TTL_MS,
            allowed_origin_keys: vec![[1; 32]],
        }
    }

    #[test]
    fn custody_is_disabled_by_default_and_modes_are_owner_controlled() {
        let disabled = FileCustodyStore::open_in_memory(FileCustodyPolicy::default()).unwrap();
        assert_eq!(
            disabled.store_range(
                &peer(1),
                true,
                &node(2),
                &range(0, 7, 64),
                NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            ),
            Err(FileCustodyError::PolicyDenied)
        );
        let contacts = FileCustodyStore::open_in_memory(policy(FileCustodyMode::ContactsOnly)).unwrap();
        assert_eq!(
            contacts.store_range(
                &peer(1),
                false,
                &node(2),
                &range(0, 7, 64),
                NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            ),
            Err(FileCustodyError::PolicyDenied)
        );
        assert!(contacts
            .store_range(
                &peer(1),
                true,
                &node(2),
                &range(0, 7, 64),
                NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            )
            .is_ok());
        let allow = FileCustodyStore::open_in_memory(policy(FileCustodyMode::AllowList)).unwrap();
        assert!(allow
            .store_range(
                &peer(1),
                false,
                &node(2),
                &range(0, 8, 64),
                NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            )
            .is_ok());
        assert_eq!(
            allow.store_range(
                &peer(3),
                true,
                &node(2),
                &range(0, 8, 64),
                NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            ),
            Err(FileCustodyError::PolicyDenied)
        );
    }

    #[test]
    fn opaque_range_is_durable_idempotent_and_restored_after_restart() {
        let path = std::env::temp_dir().join(format!(
            "apu-file-custody-{}-{}.sqlite3",
            std::process::id(),
            rand::random::<u64>()
        ));
        let custody_policy = policy(FileCustodyMode::Open);
        let ciphertext = range(9, 0xa5, 512);
        let first_receipt = {
            let store = FileCustodyStore::open(&path, custody_policy.clone()).unwrap();
            assert_eq!(store.schema_version().unwrap(), 1);
            let first = store
                .store_range(
                    &peer(1),
                    false,
                    &node(2),
                    &ciphertext,
                    NOW,
                    NOW + FILE_CUSTODY_MIN_TTL_MS,
                )
                .unwrap();
            assert!(matches!(first, FileCustodyStoreOutcome::Stored(_)));
            first.receipt().clone()
        };
        {
            let restarted = FileCustodyStore::open(&path, custody_policy).unwrap();
            let duplicate = restarted
                .store_range(
                    &peer(1),
                    false,
                    &node(2),
                    &ciphertext,
                    NOW + 1,
                    NOW + FILE_CUSTODY_MIN_TTL_MS,
                )
                .unwrap();
            assert!(matches!(duplicate, FileCustodyStoreOutcome::AlreadyStored(_)));
            assert_eq!(restarted.usage_bytes().unwrap(), 512);
            let loaded = restarted
                .load_for_delivery(&peer(1), &node(2), &[0x44; 16], NOW, 4, 1024)
                .unwrap();
            assert_eq!(loaded.len(), 1);
            assert_eq!(loaded[0].ciphertext, vec![0xa5; 512]);
            assert_eq!(loaded[0].receipt, first_receipt);
        }
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(path.with_extension("sqlite3-wal"));
        let _ = std::fs::remove_file(path.with_extension("sqlite3-shm"));
    }

    #[test]
    fn conflicting_duplicate_tombstone_and_expiry_fail_closed() {
        let store = FileCustodyStore::open_in_memory(policy(FileCustodyMode::Open)).unwrap();
        let first = store
            .store_range(
                &peer(1),
                false,
                &node(2),
                &range(1, 1, 64),
                NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            )
            .unwrap();
        assert_eq!(
            store.store_range(
                &peer(1),
                false,
                &node(2),
                &range(1, 2, 64),
                NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            ),
            Err(FileCustodyError::ConflictingRange)
        );
        store.remove_with_tombstone(first.receipt(), NOW + 1).unwrap();
        assert_eq!(store.usage_bytes().unwrap(), 0);
        assert_eq!(
            store.store_range(
                &peer(1),
                false,
                &node(2),
                &range(1, 1, 64),
                NOW + 2,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            ),
            Err(FileCustodyError::Tombstoned)
        );
        assert_eq!(store.purge_expired(NOW + FILE_CUSTODY_TOMBSTONE_TTL_MS + 2).unwrap().1, 1);
    }

    #[test]
    fn quotas_transfer_range_and_rate_limits_are_hard() {
        let mut small = policy(FileCustodyMode::Open);
        small.total_quota_bytes = 150;
        small.per_origin_quota_bytes = 100;
        small.max_active_transfers_per_origin = 1;
        small.max_ranges_per_transfer = 2;
        small.max_ranges_per_origin_per_minute = 2;
        let store = FileCustodyStore::open_in_memory(small).unwrap();
        store
            .store_range(
                &peer(1), false, &node(2), &range(0, 1, 50), NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            )
            .unwrap();
        store
            .store_range(
                &peer(1), false, &node(2), &range(1, 1, 50), NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            )
            .unwrap();
        assert_eq!(
            store.store_range(
                &peer(1), false, &node(2), &range(2, 1, 1), NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            ),
            Err(FileCustodyError::RateLimitExceeded)
        );
        assert_eq!(store.origin_usage_bytes(&peer(1)).unwrap(), 100);
        assert_eq!(
            store.store_range(
                &peer(3), false, &node(2), &range(0, 1, 51), NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            ),
            Err(FileCustodyError::GlobalQuotaExceeded)
        );
    }

    #[test]
    fn per_origin_transfer_and_range_limits_are_independent() {
        let mut origin_limited = policy(FileCustodyMode::Open);
        origin_limited.total_quota_bytes = 300;
        origin_limited.per_origin_quota_bytes = 100;
        origin_limited.max_ranges_per_origin_per_minute = 10;
        let store = FileCustodyStore::open_in_memory(origin_limited).unwrap();
        store
            .store_range(
                &peer(1), false, &node(2), &range(0, 1, 100), NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            )
            .unwrap();
        assert_eq!(
            store.store_range(
                &peer(1), false, &node(2), &range(1, 1, 1), NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            ),
            Err(FileCustodyError::OriginQuotaExceeded)
        );

        let mut transfer_limited = policy(FileCustodyMode::Open);
        transfer_limited.max_active_transfers_per_origin = 1;
        transfer_limited.max_ranges_per_origin_per_minute = 10;
        let store = FileCustodyStore::open_in_memory(transfer_limited).unwrap();
        store
            .store_range(
                &peer(1), false, &node(2), &range(0, 1, 32), NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            )
            .unwrap();
        let mut other_transfer = range(0, 2, 32);
        other_transfer.transfer_id = [0x55; 16];
        assert_eq!(
            store.store_range(
                &peer(1), false, &node(2), &other_transfer, NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            ),
            Err(FileCustodyError::TransferLimitExceeded)
        );

        let mut range_limited = policy(FileCustodyMode::Open);
        range_limited.max_ranges_per_transfer = 1;
        range_limited.max_ranges_per_origin_per_minute = 10;
        let store = FileCustodyStore::open_in_memory(range_limited).unwrap();
        store
            .store_range(
                &peer(1), false, &node(2), &range(0, 1, 32), NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            )
            .unwrap();
        assert_eq!(
            store.store_range(
                &peer(1), false, &node(2), &range(1, 1, 32), NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            ),
            Err(FileCustodyError::RangeLimitExceeded)
        );
    }

    #[test]
    fn sqlite_full_is_reported_as_disk_full_without_publishing_bytes() {
        let mut roomy = policy(FileCustodyMode::Open);
        roomy.total_quota_bytes = 1024 * 1024;
        roomy.per_origin_quota_bytes = 1024 * 1024;
        let store = FileCustodyStore::open_in_memory(roomy).unwrap();
        {
            let connection = store.connection.lock().unwrap();
            let page_count: i64 = connection
                .query_row("PRAGMA page_count", [], |row| row.get(0))
                .unwrap();
            connection
                .pragma_update(None, "max_page_count", page_count)
                .unwrap();
        }
        assert_eq!(
            store.store_range(
                &peer(1), false, &node(2), &range(0, 1, 128 * 1024), NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            ),
            Err(FileCustodyError::DiskFull)
        );
        assert_eq!(store.usage_bytes().unwrap(), 0);
    }

    #[test]
    fn bounded_inventory_and_load_never_read_whole_store() {
        let store = FileCustodyStore::open_in_memory(policy(FileCustodyMode::Open)).unwrap();
        for index in 0..3 {
            store
                .store_range(
                    &peer(1), false, &node(2), &range(index, index as u8, 64),
                    NOW + i64::try_from(index).unwrap(),
                    NOW + FILE_CUSTODY_MIN_TTL_MS,
                )
                .unwrap();
        }
        assert_eq!(store.inventory(&peer(1), &node(2), &[0x44; 16], NOW, 2).unwrap().len(), 2);
        assert_eq!(
            store.load_for_delivery(&peer(1), &node(2), &[0x44; 16], NOW, 3, 100).unwrap().len(),
            1
        );
        assert_eq!(
            store.load_for_delivery(
                &peer(1), &node(2), &[0x44; 16], NOW,
                FILE_CUSTODY_MAX_LOAD_RANGES + 1, 100,
            ),
            Err(FileCustodyError::InvalidLoadLimit)
        );
    }

    #[test]
    fn invalid_ttl_wire_and_policy_are_rejected_before_storage() {
        let mut invalid_policy = policy(FileCustodyMode::Open);
        invalid_policy.total_quota_bytes = 0;
        assert!(matches!(
            FileCustodyStore::open_in_memory(invalid_policy),
            Err(FileCustodyError::InvalidPolicy(_))
        ));
        let store = FileCustodyStore::open_in_memory(policy(FileCustodyMode::Open)).unwrap();
        assert!(matches!(
            store.store_range(
                &peer(1), false, &node(2), &range(0, 1, 64), NOW, NOW + 1,
            ),
            Err(FileCustodyError::InvalidRequest(_))
        ));
        let mut invalid_range = range(0, 1, 64);
        invalid_range.ciphertext_chunk_len = 63;
        assert!(matches!(
            store.store_range(
                &peer(1), false, &node(2), &invalid_range, NOW,
                NOW + FILE_CUSTODY_MIN_TTL_MS,
            ),
            Err(FileCustodyError::Wire(_))
        ));
        assert_eq!(store.usage_bytes().unwrap(), 0);
    }
}
