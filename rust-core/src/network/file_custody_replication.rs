//! Durable bounded planning for replicating opaque file ranges across independent phone custodians.
//!
//! This layer stores signed receipts, never ciphertext. Planning is deterministic and page-bounded;
//! repeating it after process death produces only replicas still missing from valid receipts. A
//! target replica count is availability policy, not a whole-file allocation or file-size ceiling.

use std::collections::HashSet;
use std::path::Path;
use std::sync::Mutex;

use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};

use crate::crypto::keys::{NodeId, ED25519_PUBLIC_KEY_SIZE};
use crate::network::file_custody::{FileCustodyPeer, FileCustodyRangeId};
use crate::network::file_custody_receipt::{
    FileCustodyReceiptError, SignedFileCustodyReceiptV1, MAX_FILE_CUSTODY_RECEIPT_BYTES,
};
use crate::network::file_wire::MAX_FILE_CHUNK_DATA_BYTES;

pub const FILE_CUSTODY_MAX_REPLICAS: usize = 5;
pub const FILE_CUSTODY_MAX_REPLICATION_CANDIDATES: usize = 32;
pub const FILE_CUSTODY_MAX_REPLICATION_RANGES_PER_PLAN: usize = 256;
pub const FILE_CUSTODY_MAX_REPLICATION_ASSIGNMENTS: usize = 512;

const MIN_NODE_ID_BYTES: usize = 35;
const MAX_NODE_ID_BYTES: usize = 67;

const REPLICATION_MIGRATION_V1: &str = "
CREATE TABLE IF NOT EXISTS file_custody_replication_receipts (
    origin_key          BLOB NOT NULL CHECK(length(origin_key) = 32),
    recipient_node_id   TEXT NOT NULL,
    transfer_id         BLOB NOT NULL CHECK(length(transfer_id) = 16),
    chunk_index_be      BLOB NOT NULL CHECK(length(chunk_index_be) = 8),
    chunk_offset        INTEGER NOT NULL,
    custodian_key       BLOB NOT NULL CHECK(length(custodian_key) = 32),
    custodian_node_id   TEXT NOT NULL,
    ciphertext_len      INTEGER NOT NULL,
    ciphertext_digest   BLOB NOT NULL CHECK(length(ciphertext_digest) = 32),
    expires_at_ms       INTEGER NOT NULL,
    encoded_receipt     BLOB NOT NULL CHECK(length(encoded_receipt) <= 512),
    PRIMARY KEY (
        origin_key, recipient_node_id, transfer_id, chunk_index_be, chunk_offset, custodian_key
    )
);
CREATE INDEX IF NOT EXISTS idx_file_custody_replication_expiry
    ON file_custody_replication_receipts(expires_at_ms);
CREATE TABLE IF NOT EXISTS file_custody_replication_schema_version(version INTEGER PRIMARY KEY);
INSERT OR IGNORE INTO file_custody_replication_schema_version(version) VALUES (1);
";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileCustodyReplicationPolicy {
    pub target_replicas: usize,
    pub max_candidates: usize,
    pub max_ranges_per_plan: usize,
    pub max_assignments_per_plan: usize,
}

impl Default for FileCustodyReplicationPolicy {
    fn default() -> Self {
        Self {
            target_replicas: 2,
            max_candidates: 16,
            max_ranges_per_plan: 128,
            max_assignments_per_plan: 256,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileCustodianCandidateV1 {
    pub peer: FileCustodyPeer,
    pub priority: i32,
    pub available_quota_bytes: u64,
    pub advertised_expires_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileCustodyReplicationRangeV1 {
    pub range_id: FileCustodyRangeId,
    pub ciphertext_chunk_len: u32,
    pub ciphertext_len: u32,
    pub ciphertext_digest: [u8; 32],
    pub requested_expires_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileCustodyReplicationAssignmentV1 {
    pub custodian: FileCustodyPeer,
    pub range: FileCustodyReplicationRangeV1,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum FileCustodyReplicationError {
    #[error("invalid file custody replication policy: {0}")]
    InvalidPolicy(&'static str),
    #[error("invalid file custody replication input: {0}")]
    InvalidInput(&'static str),
    #[error("file custody replication receipt failed verification: {0}")]
    InvalidReceipt(String),
    #[error("file custody replication receipt conflicts with durable state")]
    ConflictingReceipt,
    #[error("file custody replication SQLite is full")]
    DiskFull,
    #[error("file custody replication durable store failed: {0}")]
    Store(String),
}

impl From<FileCustodyReceiptError> for FileCustodyReplicationError {
    fn from(error: FileCustodyReceiptError) -> Self {
        Self::InvalidReceipt(error.to_string())
    }
}

pub struct FileCustodyReplicationStore {
    connection: Mutex<Connection>,
    policy: FileCustodyReplicationPolicy,
}

impl FileCustodyReplicationPolicy {
    pub fn validate(&self) -> Result<(), FileCustodyReplicationError> {
        if self.target_replicas == 0 || self.target_replicas > FILE_CUSTODY_MAX_REPLICAS {
            return Err(FileCustodyReplicationError::InvalidPolicy(
                "invalid target replica count",
            ));
        }
        if self.max_candidates < self.target_replicas
            || self.max_candidates > FILE_CUSTODY_MAX_REPLICATION_CANDIDATES
        {
            return Err(FileCustodyReplicationError::InvalidPolicy(
                "invalid candidate bound",
            ));
        }
        if self.max_ranges_per_plan == 0
            || self.max_ranges_per_plan > FILE_CUSTODY_MAX_REPLICATION_RANGES_PER_PLAN
            || self.max_assignments_per_plan == 0
            || self.max_assignments_per_plan > FILE_CUSTODY_MAX_REPLICATION_ASSIGNMENTS
        {
            return Err(FileCustodyReplicationError::InvalidPolicy(
                "invalid plan page bound",
            ));
        }
        Ok(())
    }
}

impl FileCustodyReplicationStore {
    pub fn open<P: AsRef<Path>>(
        path: P,
        policy: FileCustodyReplicationPolicy,
    ) -> Result<Self, FileCustodyReplicationError> {
        Self::from_connection(Connection::open(path).map_err(map_store_error)?, policy)
    }

    pub fn open_in_memory(
        policy: FileCustodyReplicationPolicy,
    ) -> Result<Self, FileCustodyReplicationError> {
        Self::from_connection(Connection::open_in_memory().map_err(map_store_error)?, policy)
    }

    fn from_connection(
        connection: Connection,
        policy: FileCustodyReplicationPolicy,
    ) -> Result<Self, FileCustodyReplicationError> {
        policy.validate()?;
        connection
            .execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;")
            .map_err(map_store_error)?;
        connection
            .execute_batch(REPLICATION_MIGRATION_V1)
            .map_err(map_store_error)?;
        Ok(Self {
            connection: Mutex::new(connection),
            policy,
        })
    }

    pub fn schema_version(&self) -> Result<i64, FileCustodyReplicationError> {
        let connection = self.connection.lock().map_err(|_| store_poisoned())?;
        connection
            .query_row(
                "SELECT version FROM file_custody_replication_schema_version
                 ORDER BY version DESC LIMIT 1",
                [],
                |row| row.get(0),
            )
            .map_err(map_store_error)
    }

    /// Persists a cryptographically valid receipt before a future plan counts the replica.
    pub fn record_receipt(
        &self,
        receipt: &SignedFileCustodyReceiptV1,
        expected_origin: &FileCustodyPeer,
        expected_recipient_node_id: &str,
        now_ms: i64,
    ) -> Result<bool, FileCustodyReplicationError> {
        receipt.verify_active_at(
            &receipt.custodian_node_id,
            &receipt.custodian_ed25519_public_key,
            &expected_origin.node_id,
            &expected_origin.ed25519_public_key,
            expected_recipient_node_id,
            now_ms,
        )?;
        let encoded = receipt.encode()?;
        if encoded.len() > MAX_FILE_CUSTODY_RECEIPT_BYTES {
            return Err(FileCustodyReplicationError::InvalidReceipt(
                "receipt exceeds hard bound".into(),
            ));
        }
        let mut connection = self.connection.lock().map_err(|_| store_poisoned())?;
        let transaction = connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(map_store_error)?;
        transaction
            .execute(
                "DELETE FROM file_custody_replication_receipts WHERE expires_at_ms <= ?1",
                params![now_ms],
            )
            .map_err(map_store_error)?;
        let id = &receipt.claims.range_id;
        let existing = transaction
            .query_row(
                "SELECT encoded_receipt FROM file_custody_replication_receipts
                 WHERE origin_key = ?1 AND recipient_node_id = ?2 AND transfer_id = ?3
                   AND chunk_index_be = ?4 AND chunk_offset = ?5 AND custodian_key = ?6",
                params![
                    id.origin_ed25519_public_key.as_slice(),
                    id.recipient_node_id,
                    id.transfer_id.as_slice(),
                    id.chunk_index.to_be_bytes().as_slice(),
                    i64::from(id.chunk_offset),
                    receipt.custodian_ed25519_public_key.as_slice(),
                ],
                |row| row.get::<_, Vec<u8>>(0),
            )
            .optional()
            .map_err(map_store_error)?;
        if let Some(existing) = existing {
            if existing != encoded {
                return Err(FileCustodyReplicationError::ConflictingReceipt);
            }
            transaction.commit().map_err(map_store_error)?;
            return Ok(false);
        }
        transaction
            .execute(
                "INSERT INTO file_custody_replication_receipts
                 (origin_key, recipient_node_id, transfer_id, chunk_index_be, chunk_offset,
                  custodian_key, custodian_node_id, ciphertext_len, ciphertext_digest,
                  expires_at_ms, encoded_receipt)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
                params![
                    id.origin_ed25519_public_key.as_slice(),
                    id.recipient_node_id,
                    id.transfer_id.as_slice(),
                    id.chunk_index.to_be_bytes().as_slice(),
                    i64::from(id.chunk_offset),
                    receipt.custodian_ed25519_public_key.as_slice(),
                    receipt.custodian_node_id,
                    i64::from(receipt.claims.ciphertext_len),
                    receipt.claims.ciphertext_digest.as_slice(),
                    receipt.claims.expires_at_ms,
                    encoded,
                ],
            )
            .map_err(map_store_error)?;
        transaction.commit().map_err(map_store_error)?;
        Ok(true)
    }

    /// Returns a bounded page of only replicas not already proven by active signed receipts.
    pub fn plan_missing_replicas(
        &self,
        origin: &FileCustodyPeer,
        recipient_node_id: &str,
        ranges: &[FileCustodyReplicationRangeV1],
        candidates: &[FileCustodianCandidateV1],
        now_ms: i64,
    ) -> Result<Vec<FileCustodyReplicationAssignmentV1>, FileCustodyReplicationError> {
        validate_plan_input(
            &self.policy,
            origin,
            recipient_node_id,
            ranges,
            candidates,
            now_ms,
        )?;
        let mut eligible = candidates
            .iter()
            .filter(|candidate| {
                candidate.advertised_expires_at_ms > now_ms
                    && candidate.available_quota_bytes > 0
                    && candidate.peer.node_id != origin.node_id
                    && candidate.peer.node_id != recipient_node_id
            })
            .cloned()
            .collect::<Vec<_>>();
        eligible.sort_by(|left, right| {
            right
                .priority
                .cmp(&left.priority)
                .then_with(|| right.available_quota_bytes.cmp(&left.available_quota_bytes))
                .then_with(|| left.peer.node_id.cmp(&right.peer.node_id))
        });
        let connection = self.connection.lock().map_err(|_| store_poisoned())?;
        let mut assignments = Vec::new();
        for range in ranges {
            if assignments.len() >= self.policy.max_assignments_per_plan {
                break;
            }
            let existing = active_custodian_keys(&connection, origin, range, now_ms)?;
            let mut replicas = existing.len();
            for candidate in &mut eligible {
                if replicas >= self.policy.target_replicas
                    || assignments.len() >= self.policy.max_assignments_per_plan
                {
                    break;
                }
                if existing.contains(&candidate.peer.ed25519_public_key)
                    || assignments.iter().any(|assignment: &FileCustodyReplicationAssignmentV1| {
                        assignment.range.range_id == range.range_id
                            && assignment.custodian.ed25519_public_key
                                == candidate.peer.ed25519_public_key
                    })
                    || candidate.available_quota_bytes < u64::from(range.ciphertext_len)
                    || candidate.advertised_expires_at_ms < range.requested_expires_at_ms
                {
                    continue;
                }
                assignments.push(FileCustodyReplicationAssignmentV1 {
                    custodian: candidate.peer.clone(),
                    range: range.clone(),
                });
                candidate.available_quota_bytes = candidate
                    .available_quota_bytes
                    .saturating_sub(u64::from(range.ciphertext_len));
                replicas += 1;
            }
        }
        Ok(assignments)
    }

    pub fn purge_expired(&self, now_ms: i64) -> Result<usize, FileCustodyReplicationError> {
        let connection = self.connection.lock().map_err(|_| store_poisoned())?;
        connection
            .execute(
                "DELETE FROM file_custody_replication_receipts WHERE expires_at_ms <= ?1",
                params![now_ms],
            )
            .map_err(map_store_error)
    }
}

fn validate_plan_input(
    policy: &FileCustodyReplicationPolicy,
    origin: &FileCustodyPeer,
    recipient_node_id: &str,
    ranges: &[FileCustodyReplicationRangeV1],
    candidates: &[FileCustodianCandidateV1],
    now_ms: i64,
) -> Result<(), FileCustodyReplicationError> {
    policy.validate()?;
    if !is_canonical_node_id(&origin.node_id)
        || !is_canonical_node_id(recipient_node_id)
        || origin.node_id == recipient_node_id
        || now_ms < 0
    {
        return Err(FileCustodyReplicationError::InvalidInput(
            "invalid origin/recipient/time scope",
        ));
    }
    if ranges.is_empty() || ranges.len() > policy.max_ranges_per_plan {
        return Err(FileCustodyReplicationError::InvalidInput(
            "range page exceeds hard bound",
        ));
    }
    if candidates.is_empty() || candidates.len() > policy.max_candidates {
        return Err(FileCustodyReplicationError::InvalidInput(
            "candidate page exceeds hard bound",
        ));
    }
    let mut previous = None;
    for range in ranges {
        if range.range_id.origin_ed25519_public_key != origin.ed25519_public_key
            || range.range_id.recipient_node_id != recipient_node_id
            || range.range_id.transfer_id.iter().all(|byte| *byte == 0)
            || range.ciphertext_len == 0
            || usize::try_from(range.ciphertext_len)
                .map(|length| length > MAX_FILE_CHUNK_DATA_BYTES)
                .unwrap_or(true)
            || range.ciphertext_chunk_len < range.ciphertext_len
            || range
                .range_id
                .chunk_offset
                .checked_add(range.ciphertext_len)
                .map(|end| end > range.ciphertext_chunk_len)
                .unwrap_or(true)
            || range.requested_expires_at_ms <= now_ms
        {
            return Err(FileCustodyReplicationError::InvalidInput(
                "invalid exact range request",
            ));
        }
        let identity = (range.range_id.chunk_index, range.range_id.chunk_offset);
        if previous.map(|value| identity <= value).unwrap_or(false) {
            return Err(FileCustodyReplicationError::InvalidInput(
                "ranges must be sorted and unique",
            ));
        }
        previous = Some(identity);
    }
    let mut keys = HashSet::new();
    for candidate in candidates {
        if !is_canonical_node_id(&candidate.peer.node_id)
            || !valid_modern_binding(
                &candidate.peer.node_id,
                &candidate.peer.ed25519_public_key,
            )
            || !keys.insert(candidate.peer.ed25519_public_key)
        {
            return Err(FileCustodyReplicationError::InvalidInput(
                "invalid or duplicate candidate",
            ));
        }
    }
    Ok(())
}

fn active_custodian_keys(
    connection: &Connection,
    origin: &FileCustodyPeer,
    range: &FileCustodyReplicationRangeV1,
    now_ms: i64,
) -> Result<HashSet<[u8; ED25519_PUBLIC_KEY_SIZE]>, FileCustodyReplicationError> {
    let id = &range.range_id;
    let mut statement = connection
        .prepare(
            "SELECT custodian_key, encoded_receipt
             FROM file_custody_replication_receipts
             WHERE origin_key = ?1 AND recipient_node_id = ?2 AND transfer_id = ?3
               AND chunk_index_be = ?4 AND chunk_offset = ?5 AND ciphertext_len = ?6
               AND ciphertext_digest = ?7 AND expires_at_ms >= ?8",
        )
        .map_err(map_store_error)?;
    let rows = statement
        .query_map(
            params![
                id.origin_ed25519_public_key.as_slice(),
                id.recipient_node_id,
                id.transfer_id.as_slice(),
                id.chunk_index.to_be_bytes().as_slice(),
                i64::from(id.chunk_offset),
                i64::from(range.ciphertext_len),
                range.ciphertext_digest.as_slice(),
                range.requested_expires_at_ms,
            ],
            |row| Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, Vec<u8>>(1)?)),
        )
        .map_err(map_store_error)?;
    let mut keys = HashSet::new();
    for row in rows {
        let (key, encoded) = row.map_err(map_store_error)?;
        let key: [u8; ED25519_PUBLIC_KEY_SIZE] = key.try_into().map_err(|_| {
            FileCustodyReplicationError::Store("invalid durable custodian key".into())
        })?;
        let receipt = SignedFileCustodyReceiptV1::decode(&encoded)?;
        receipt.verify_active_at(
            &receipt.custodian_node_id,
            &key,
            &origin.node_id,
            &origin.ed25519_public_key,
            &range.range_id.recipient_node_id,
            now_ms,
        )?;
        if receipt.custodian_ed25519_public_key != key
            || receipt.claims.range_id != range.range_id
            || receipt.claims.ciphertext_chunk_len != range.ciphertext_chunk_len
            || receipt.claims.ciphertext_len != range.ciphertext_len
            || receipt.claims.ciphertext_digest != range.ciphertext_digest
            || receipt.claims.expires_at_ms <= now_ms
        {
            return Err(FileCustodyReplicationError::InvalidReceipt(
                "durable receipt does not match planned range".into(),
            ));
        }
        keys.insert(key);
    }
    Ok(keys)
}

fn is_canonical_node_id(node_id: &str) -> bool {
    (node_id.len() == MIN_NODE_ID_BYTES || node_id.len() == MAX_NODE_ID_BYTES)
        && node_id.starts_with("pk_")
        && node_id[3..]
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn valid_modern_binding(
    node_id: &str,
    public_key: &[u8; ED25519_PUBLIC_KEY_SIZE],
) -> bool {
    node_id.len() != MAX_NODE_ID_BYTES
        || node_id == format!("pk_{}", NodeId::from_ed25519_pubkey(public_key).to_hex())
}

fn map_store_error(error: rusqlite::Error) -> FileCustodyReplicationError {
    if matches!(error.sqlite_error_code(), Some(rusqlite::ErrorCode::DiskFull)) {
        FileCustodyReplicationError::DiskFull
    } else {
        FileCustodyReplicationError::Store(error.to_string())
    }
}

fn store_poisoned() -> FileCustodyReplicationError {
    FileCustodyReplicationError::Store("SQLite mutex poisoned".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::keys::Ed25519KeyPair;
    use crate::network::file_custody::FileCustodyReceiptClaimsV1;
    use crate::network::file_custody_receipt::sign_file_custody_receipt_v1;

    const NOW: i64 = 1_900_000_000_000;

    fn identity(byte: u8) -> Ed25519KeyPair {
        Ed25519KeyPair::from_secret_bytes(&[byte; 32]).unwrap()
    }

    fn peer(identity: &Ed25519KeyPair) -> FileCustodyPeer {
        FileCustodyPeer {
            node_id: format!("pk_{}", identity.node_id().to_hex()),
            ed25519_public_key: identity.public_key().0.try_into().unwrap(),
        }
    }

    fn candidate(byte: u8, priority: i32) -> (Ed25519KeyPair, FileCustodianCandidateV1) {
        let identity = identity(byte);
        let candidate = FileCustodianCandidateV1 {
            peer: peer(&identity),
            priority,
            available_quota_bytes: 1024 * 1024,
            advertised_expires_at_ms: NOW + 120_000,
        };
        (identity, candidate)
    }

    fn range(origin: &FileCustodyPeer, recipient: &FileCustodyPeer, index: u64) -> FileCustodyReplicationRangeV1 {
        FileCustodyReplicationRangeV1 {
            range_id: FileCustodyRangeId {
                origin_ed25519_public_key: origin.ed25519_public_key,
                recipient_node_id: recipient.node_id.clone(),
                transfer_id: [0x44; 16],
                chunk_index: index,
                chunk_offset: 0,
            },
            ciphertext_chunk_len: 64,
            ciphertext_len: 64,
            ciphertext_digest: [index as u8 + 1; 32],
            requested_expires_at_ms: NOW + 60_000,
        }
    }

    fn receipt(
        origin: &FileCustodyPeer,
        recipient: &FileCustodyPeer,
        custodian: &FileCustodyPeer,
        custodian_identity: &Ed25519KeyPair,
        range: &FileCustodyReplicationRangeV1,
    ) -> SignedFileCustodyReceiptV1 {
        sign_file_custody_receipt_v1(
            origin.node_id.clone(),
            custodian.node_id.clone(),
            FileCustodyReceiptClaimsV1 {
                range_id: range.range_id.clone(),
                ciphertext_chunk_len: range.ciphertext_chunk_len,
                ciphertext_len: range.ciphertext_len,
                ciphertext_digest: range.ciphertext_digest,
                stored_at_ms: NOW,
                expires_at_ms: range.requested_expires_at_ms,
            },
            custodian_identity,
        )
        .unwrap()
    }

    #[test]
    fn deterministic_plan_fills_only_missing_replicas() {
        let origin_identity = identity(1);
        let recipient_identity = identity(2);
        let origin = peer(&origin_identity);
        let recipient = peer(&recipient_identity);
        let (first_identity, first) = candidate(3, 20);
        let (_, second) = candidate(4, 10);
        let (_, third) = candidate(5, 0);
        let store = FileCustodyReplicationStore::open_in_memory(Default::default()).unwrap();
        let wanted = range(&origin, &recipient, u64::from(u32::MAX) + 7);
        let candidates = [third.clone(), second.clone(), first.clone()];
        let plan = store
            .plan_missing_replicas(&origin, &recipient.node_id, &[wanted.clone()], &candidates, NOW)
            .unwrap();
        assert_eq!(plan.len(), 2);
        assert_eq!(plan[0].custodian, first.peer);
        assert_eq!(plan[1].custodian, second.peer);
        store
            .record_receipt(
                &receipt(&origin, &recipient, &first.peer, &first_identity, &wanted),
                &origin,
                &recipient.node_id,
                NOW,
            )
            .unwrap();
        let remaining = store
            .plan_missing_replicas(&origin, &recipient.node_id, &[wanted], &candidates, NOW)
            .unwrap();
        assert_eq!(remaining.len(), 1);
        assert_eq!(remaining[0].custodian, second.peer);
    }

    #[test]
    fn durable_receipt_survives_restart_and_is_idempotent() {
        let path = std::env::temp_dir().join(format!(
            "apu-file-replication-{}-{}.sqlite3",
            std::process::id(),
            rand::random::<u64>()
        ));
        let origin = peer(&identity(1));
        let recipient = peer(&identity(2));
        let (custodian_identity, candidate) = candidate(3, 1);
        let wanted = range(&origin, &recipient, 9);
        let signed = receipt(
            &origin,
            &recipient,
            &candidate.peer,
            &custodian_identity,
            &wanted,
        );
        {
            let store = FileCustodyReplicationStore::open(&path, Default::default()).unwrap();
            assert_eq!(store.schema_version().unwrap(), 1);
            assert!(store
                .record_receipt(&signed, &origin, &recipient.node_id, NOW)
                .unwrap());
            assert!(!store
                .record_receipt(&signed, &origin, &recipient.node_id, NOW)
                .unwrap());
        }
        {
            let restarted = FileCustodyReplicationStore::open(&path, Default::default()).unwrap();
            let plan = restarted
                .plan_missing_replicas(
                    &origin,
                    &recipient.node_id,
                    &[wanted],
                    &[candidate],
                    NOW,
                )
                .unwrap();
            assert!(plan.is_empty());
        }
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(path.with_extension("sqlite3-wal"));
        let _ = std::fs::remove_file(path.with_extension("sqlite3-shm"));
    }

    #[test]
    fn wrong_origin_recipient_expiry_and_tamper_fail_closed() {
        let origin = peer(&identity(1));
        let recipient = peer(&identity(2));
        let (custodian_identity, candidate) = candidate(3, 1);
        let wanted = range(&origin, &recipient, 1);
        let signed = receipt(
            &origin,
            &recipient,
            &candidate.peer,
            &custodian_identity,
            &wanted,
        );
        let store = FileCustodyReplicationStore::open_in_memory(Default::default()).unwrap();
        assert!(store
            .record_receipt(&signed, &peer(&identity(9)), &recipient.node_id, NOW)
            .is_err());
        assert!(store
            .record_receipt(&signed, &origin, &peer(&identity(9)).node_id, NOW)
            .is_err());
        assert!(store
            .record_receipt(
                &signed,
                &origin,
                &recipient.node_id,
                NOW + 60_001,
            )
            .is_err());
        let mut tampered = signed;
        tampered.claims.ciphertext_digest[0] ^= 1;
        assert!(store
            .record_receipt(&tampered, &origin, &recipient.node_id, NOW)
            .is_err());
        assert_eq!(store.purge_expired(NOW).unwrap(), 0);
    }

    #[test]
    fn plan_pages_are_bounded_sorted_and_do_not_cap_u64_transfer_geometry() {
        let mut policy = FileCustodyReplicationPolicy::default();
        policy.max_ranges_per_plan = 2;
        policy.max_assignments_per_plan = 2;
        let store = FileCustodyReplicationStore::open_in_memory(policy).unwrap();
        let origin = peer(&identity(1));
        let recipient = peer(&identity(2));
        let (_, candidate) = candidate(3, 1);
        let high = u64::from(u32::MAX) + 100;
        let ranges = [range(&origin, &recipient, high), range(&origin, &recipient, high + 1)];
        let plan = store
            .plan_missing_replicas(&origin, &recipient.node_id, &ranges, &[candidate.clone()], NOW)
            .unwrap();
        assert_eq!(plan.len(), 2);
        assert_eq!(plan[0].range.range_id.chunk_index, high);
        let reversed = [ranges[1].clone(), ranges[0].clone()];
        assert!(matches!(
            store.plan_missing_replicas(
                &origin,
                &recipient.node_id,
                &reversed,
                &[candidate],
                NOW,
            ),
            Err(FileCustodyReplicationError::InvalidInput(_))
        ));
    }
}
