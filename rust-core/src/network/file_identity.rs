//! Streaming ciphertext chunk identity and Merkle boundary (F4-B3).
//!
//! This module is pure and deliberately not wired to sockets, Android, FFI, databases or the
//! legacy F3 sender. It defines an F4 geometry with `u64` file size/chunk count, canonical
//! ciphertext identities, an O(log chunks) Merkle accumulator, and bounded single-chunk proofs.
//! Whole-file size is never used for allocation; only one bounded ciphertext chunk is hashed at a
//! time. Actual acceptance remains limited by filesystem space and owner policy, not an arbitrary
//! 4-GiB product constant.

use crate::crypto::file_transfer::{
    FILE_CHUNK_TAG_BYTES, FILE_TRANSFER_ID_BYTES, MAX_FILE_CHUNK_BYTES, MIN_FILE_CHUNK_BYTES,
};
use crate::network::file_wire::MAX_FILE_CHUNK_COUNT;
use sha2::{Digest, Sha256};

pub const FILE_IDENTITY_VERSION_V1: u8 = 1;
pub const FILE_CHUNK_IDENTITY_MAGIC: [u8; 4] = *b"APUL";
pub const FILE_IDENTITY_MANIFEST_MAGIC: [u8; 4] = *b"APUI";
pub const FILE_MERKLE_PROOF_MAGIC: [u8; 4] = *b"APUP";
pub const FILE_IDENTITY_HASH_BYTES: usize = 32;
pub const FILE_CHUNK_IDENTITY_BYTES: usize =
    4 + 1 + FILE_TRANSFER_ID_BYTES + 8 + 4 + 4 + FILE_IDENTITY_HASH_BYTES;
pub const FILE_IDENTITY_MANIFEST_BYTES: usize =
    4 + 1 + FILE_TRANSFER_ID_BYTES + 8 + 4 + 8 + FILE_IDENTITY_HASH_BYTES;
pub const FILE_MERKLE_PROOF_PREFIX_BYTES: usize = 4 + 1 + FILE_TRANSFER_ID_BYTES + 8 + 1;
pub const MAX_FILE_MERKLE_PROOF_SIBLINGS: usize = 64;
pub const MAX_FILE_MERKLE_PROOF_BYTES: usize =
    FILE_MERKLE_PROOF_PREFIX_BYTES + MAX_FILE_MERKLE_PROOF_SIBLINGS * FILE_IDENTITY_HASH_BYTES;

const FILE_CHUNK_LEAF_DOMAIN_V1: &[u8] = b"apu-file-chunk-leaf-v1\0";
const FILE_MERKLE_NODE_DOMAIN_V1: &[u8] = b"apu-file-merkle-node-v1\0";
const FILE_MERKLE_EMPTY_DOMAIN_V1: &[u8] = b"apu-file-merkle-empty-v1\0";
const FILE_MANIFEST_COMMITMENT_DOMAIN_V1: &[u8] = b"apu-file-identity-manifest-v1\0";
const MERKLE_FRONTIER_LEVELS: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileGeometryV1 {
    pub transfer_id: [u8; FILE_TRANSFER_ID_BYTES],
    pub file_size: u64,
    pub chunk_size: u32,
    pub chunk_count: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CiphertextChunkIdentityV1 {
    pub transfer_id: [u8; FILE_TRANSFER_ID_BYTES],
    pub chunk_index: u64,
    pub plaintext_len: u32,
    pub ciphertext_len: u32,
    pub ciphertext_sha256: [u8; FILE_IDENTITY_HASH_BYTES],
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileIdentityManifestV1 {
    pub geometry: FileGeometryV1,
    pub merkle_root: [u8; FILE_IDENTITY_HASH_BYTES],
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileMerkleProofV1 {
    pub transfer_id: [u8; FILE_TRANSFER_ID_BYTES],
    pub chunk_index: u64,
    /// Only actual siblings are encoded. Odd unpaired nodes are promoted without a sibling hash.
    pub siblings: Vec<[u8; FILE_IDENTITY_HASH_BYTES]>,
}

#[derive(Debug, Clone)]
pub struct FileMerkleAccumulatorV1 {
    geometry: FileGeometryV1,
    frontier: [Option<[u8; FILE_IDENTITY_HASH_BYTES]>; MERKLE_FRONTIER_LEVELS],
    leaf_count: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum FileIdentityError {
    #[error("unsupported file identity version: {got}")]
    UnsupportedVersion { got: u8 },
    #[error("invalid file identity magic")]
    InvalidMagic,
    #[error("invalid F4 file geometry: {reason}")]
    InvalidGeometry { reason: &'static str },
    #[error("invalid ciphertext chunk identity: {reason}")]
    InvalidChunkIdentity { reason: &'static str },
    #[error("ciphertext chunk is out of order")]
    OutOfOrderChunk,
    #[error("ciphertext chunk sequence is incomplete")]
    IncompleteChunkSequence,
    #[error("file identity length mismatch: expected {expected}, actual {actual}")]
    LengthMismatch { expected: usize, actual: usize },
    #[error("file identity length arithmetic overflow")]
    LengthOverflow,
    #[error("invalid file identity manifest")]
    InvalidManifest,
    #[error("invalid file Merkle proof: {reason}")]
    InvalidProof { reason: &'static str },
    #[error("file Merkle root does not match")]
    RootMismatch,
}

impl FileGeometryV1 {
    pub fn new(
        transfer_id: [u8; FILE_TRANSFER_ID_BYTES],
        file_size: u64,
        chunk_size: u32,
    ) -> Result<Self, FileIdentityError> {
        let chunk_count = expected_chunk_count(file_size, chunk_size)?;
        let geometry = Self {
            transfer_id,
            file_size,
            chunk_size,
            chunk_count,
        };
        geometry.validate()?;
        Ok(geometry)
    }

    pub fn validate(&self) -> Result<(), FileIdentityError> {
        if self.transfer_id.iter().all(|byte| *byte == 0) {
            return Err(FileIdentityError::InvalidGeometry {
                reason: "transfer ID is all zero",
            });
        }
        let expected = expected_chunk_count(self.file_size, self.chunk_size)?;
        if self.chunk_count != expected || self.chunk_count > MAX_FILE_CHUNK_COUNT {
            return Err(FileIdentityError::InvalidGeometry {
                reason: "chunk count does not match the u64 file geometry",
            });
        }
        Ok(())
    }

    pub fn expected_plaintext_len(&self, chunk_index: u64) -> Result<u32, FileIdentityError> {
        self.validate()?;
        if chunk_index >= self.chunk_count {
            return Err(FileIdentityError::InvalidChunkIdentity {
                reason: "chunk index is outside the file geometry",
            });
        }
        if chunk_index + 1 < self.chunk_count {
            return Ok(self.chunk_size);
        }
        let consumed = chunk_index
            .checked_mul(u64::from(self.chunk_size))
            .ok_or(FileIdentityError::LengthOverflow)?;
        let remaining = self
            .file_size
            .checked_sub(consumed)
            .ok_or(FileIdentityError::LengthOverflow)?;
        u32::try_from(remaining).map_err(|_| FileIdentityError::LengthOverflow)
    }

    pub fn expected_ciphertext_len(&self, chunk_index: u64) -> Result<u32, FileIdentityError> {
        self.expected_plaintext_len(chunk_index)?
            .checked_add(FILE_CHUNK_TAG_BYTES as u32)
            .ok_or(FileIdentityError::LengthOverflow)
    }
}

impl CiphertextChunkIdentityV1 {
    pub fn from_ciphertext(
        geometry: &FileGeometryV1,
        chunk_index: u64,
        ciphertext: &[u8],
    ) -> Result<Self, FileIdentityError> {
        let plaintext_len = geometry.expected_plaintext_len(chunk_index)?;
        let expected_ciphertext_len = geometry.expected_ciphertext_len(chunk_index)?;
        if ciphertext.len() != expected_ciphertext_len as usize {
            return Err(FileIdentityError::InvalidChunkIdentity {
                reason: "ciphertext length does not match geometry plus AEAD tag",
            });
        }
        let identity = Self {
            transfer_id: geometry.transfer_id,
            chunk_index,
            plaintext_len,
            ciphertext_len: expected_ciphertext_len,
            ciphertext_sha256: Sha256::digest(ciphertext).into(),
        };
        identity.validate_against(geometry)?;
        Ok(identity)
    }

    pub fn validate_shape(&self) -> Result<(), FileIdentityError> {
        if self.transfer_id.iter().all(|byte| *byte == 0) {
            return Err(FileIdentityError::InvalidChunkIdentity {
                reason: "transfer ID is all zero",
            });
        }
        if self.chunk_index >= MAX_FILE_CHUNK_COUNT {
            return Err(FileIdentityError::InvalidChunkIdentity {
                reason: "chunk index exceeds u64 file geometry",
            });
        }
        if self.plaintext_len == 0
            || self.plaintext_len > MAX_FILE_CHUNK_BYTES
            || self.ciphertext_len
                != self
                    .plaintext_len
                    .checked_add(FILE_CHUNK_TAG_BYTES as u32)
                    .ok_or(FileIdentityError::LengthOverflow)?
            || self.ciphertext_sha256.iter().all(|byte| *byte == 0)
        {
            return Err(FileIdentityError::InvalidChunkIdentity {
                reason: "invalid chunk length or ciphertext digest",
            });
        }
        Ok(())
    }

    pub fn validate_against(&self, geometry: &FileGeometryV1) -> Result<(), FileIdentityError> {
        self.validate_shape()?;
        geometry.validate()?;
        if self.transfer_id != geometry.transfer_id
            || self.plaintext_len != geometry.expected_plaintext_len(self.chunk_index)?
            || self.ciphertext_len != geometry.expected_ciphertext_len(self.chunk_index)?
        {
            return Err(FileIdentityError::InvalidChunkIdentity {
                reason: "chunk identity does not match the manifest geometry",
            });
        }
        Ok(())
    }

    pub fn verify_ciphertext(
        &self,
        geometry: &FileGeometryV1,
        ciphertext: &[u8],
    ) -> Result<(), FileIdentityError> {
        self.validate_against(geometry)?;
        let digest: [u8; FILE_IDENTITY_HASH_BYTES] = Sha256::digest(ciphertext).into();
        if ciphertext.len() != self.ciphertext_len as usize || digest != self.ciphertext_sha256 {
            return Err(FileIdentityError::InvalidChunkIdentity {
                reason: "ciphertext bytes do not match the committed length and digest",
            });
        }
        Ok(())
    }

    pub fn leaf_hash(&self) -> Result<[u8; FILE_IDENTITY_HASH_BYTES], FileIdentityError> {
        self.validate_shape()?;
        let mut hasher = Sha256::new();
        hasher.update(FILE_CHUNK_LEAF_DOMAIN_V1);
        hasher.update([FILE_IDENTITY_VERSION_V1]);
        hasher.update(self.transfer_id);
        hasher.update(self.chunk_index.to_be_bytes());
        hasher.update(self.plaintext_len.to_be_bytes());
        hasher.update(self.ciphertext_len.to_be_bytes());
        hasher.update(self.ciphertext_sha256);
        Ok(hasher.finalize().into())
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, FileIdentityError> {
        self.validate_shape()?;
        let mut out = Vec::with_capacity(FILE_CHUNK_IDENTITY_BYTES);
        out.extend_from_slice(&FILE_CHUNK_IDENTITY_MAGIC);
        out.push(FILE_IDENTITY_VERSION_V1);
        out.extend_from_slice(&self.transfer_id);
        out.extend_from_slice(&self.chunk_index.to_be_bytes());
        out.extend_from_slice(&self.plaintext_len.to_be_bytes());
        out.extend_from_slice(&self.ciphertext_len.to_be_bytes());
        out.extend_from_slice(&self.ciphertext_sha256);
        Ok(out)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, FileIdentityError> {
        if bytes.len() != FILE_CHUNK_IDENTITY_BYTES {
            return Err(FileIdentityError::LengthMismatch {
                expected: FILE_CHUNK_IDENTITY_BYTES,
                actual: bytes.len(),
            });
        }
        if &bytes[..4] != FILE_CHUNK_IDENTITY_MAGIC.as_slice() {
            return Err(FileIdentityError::InvalidMagic);
        }
        if bytes[4] != FILE_IDENTITY_VERSION_V1 {
            return Err(FileIdentityError::UnsupportedVersion { got: bytes[4] });
        }
        let identity = Self {
            transfer_id: array_at(bytes, 5)?,
            chunk_index: u64_at(bytes, 21)?,
            plaintext_len: u32_at(bytes, 29)?,
            ciphertext_len: u32_at(bytes, 33)?,
            ciphertext_sha256: array_at(bytes, 37)?,
        };
        identity.validate_shape()?;
        Ok(identity)
    }
}

impl FileIdentityManifestV1 {
    pub fn validate(&self) -> Result<(), FileIdentityError> {
        self.geometry.validate()?;
        if self.merkle_root.iter().all(|byte| *byte == 0) {
            return Err(FileIdentityError::InvalidManifest);
        }
        if self.geometry.chunk_count == 0
            && self.merkle_root != empty_merkle_root(&self.geometry.transfer_id)
        {
            return Err(FileIdentityError::RootMismatch);
        }
        Ok(())
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, FileIdentityError> {
        self.validate()?;
        let mut out = Vec::with_capacity(FILE_IDENTITY_MANIFEST_BYTES);
        out.extend_from_slice(&FILE_IDENTITY_MANIFEST_MAGIC);
        out.push(FILE_IDENTITY_VERSION_V1);
        out.extend_from_slice(&self.geometry.transfer_id);
        out.extend_from_slice(&self.geometry.file_size.to_be_bytes());
        out.extend_from_slice(&self.geometry.chunk_size.to_be_bytes());
        out.extend_from_slice(&self.geometry.chunk_count.to_be_bytes());
        out.extend_from_slice(&self.merkle_root);
        Ok(out)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, FileIdentityError> {
        if bytes.len() != FILE_IDENTITY_MANIFEST_BYTES {
            return Err(FileIdentityError::LengthMismatch {
                expected: FILE_IDENTITY_MANIFEST_BYTES,
                actual: bytes.len(),
            });
        }
        if &bytes[..4] != FILE_IDENTITY_MANIFEST_MAGIC.as_slice() {
            return Err(FileIdentityError::InvalidMagic);
        }
        if bytes[4] != FILE_IDENTITY_VERSION_V1 {
            return Err(FileIdentityError::UnsupportedVersion { got: bytes[4] });
        }
        let manifest = Self {
            geometry: FileGeometryV1 {
                transfer_id: array_at(bytes, 5)?,
                file_size: u64_at(bytes, 21)?,
                chunk_size: u32_at(bytes, 29)?,
                chunk_count: u64_at(bytes, 33)?,
            },
            merkle_root: array_at(bytes, 41)?,
        };
        manifest.validate()?;
        Ok(manifest)
    }

    pub fn commitment(&self) -> Result<[u8; FILE_IDENTITY_HASH_BYTES], FileIdentityError> {
        let bytes = self.to_bytes()?;
        let mut hasher = Sha256::new();
        hasher.update(FILE_MANIFEST_COMMITMENT_DOMAIN_V1);
        hasher.update(bytes);
        Ok(hasher.finalize().into())
    }
}

impl FileMerkleAccumulatorV1 {
    pub fn new(geometry: FileGeometryV1) -> Result<Self, FileIdentityError> {
        geometry.validate()?;
        Ok(Self {
            geometry,
            frontier: [None; MERKLE_FRONTIER_LEVELS],
            leaf_count: 0,
        })
    }

    pub fn leaf_count(&self) -> u64 {
        self.leaf_count
    }

    pub fn push(
        &mut self,
        identity: &CiphertextChunkIdentityV1,
    ) -> Result<(), FileIdentityError> {
        if identity.chunk_index != self.leaf_count {
            return Err(FileIdentityError::OutOfOrderChunk);
        }
        if self.leaf_count >= self.geometry.chunk_count {
            return Err(FileIdentityError::InvalidChunkIdentity {
                reason: "more chunks than declared by geometry",
            });
        }
        identity.validate_against(&self.geometry)?;
        let mut current = identity.leaf_hash()?;
        let mut occupied = self.leaf_count;
        let mut level = 0usize;
        while occupied & 1 == 1 {
            let left = self.frontier[level]
                .take()
                .ok_or(FileIdentityError::IncompleteChunkSequence)?;
            current = merkle_parent(&left, &current);
            occupied >>= 1;
            level = level
                .checked_add(1)
                .ok_or(FileIdentityError::LengthOverflow)?;
        }
        if level >= MERKLE_FRONTIER_LEVELS {
            return Err(FileIdentityError::LengthOverflow);
        }
        self.frontier[level] = Some(current);
        self.leaf_count = self
            .leaf_count
            .checked_add(1)
            .ok_or(FileIdentityError::LengthOverflow)?;
        Ok(())
    }

    pub fn finish(self) -> Result<FileIdentityManifestV1, FileIdentityError> {
        if self.leaf_count != self.geometry.chunk_count {
            return Err(FileIdentityError::IncompleteChunkSequence);
        }
        let merkle_root = if self.leaf_count == 0 {
            empty_merkle_root(&self.geometry.transfer_id)
        } else {
            let mut right_root: Option<[u8; FILE_IDENTITY_HASH_BYTES]> = None;
            for peak in self.frontier.into_iter().flatten() {
                right_root = Some(match right_root {
                    None => peak,
                    Some(right) => merkle_parent(&peak, &right),
                });
            }
            right_root.ok_or(FileIdentityError::IncompleteChunkSequence)?
        };
        let manifest = FileIdentityManifestV1 {
            geometry: self.geometry,
            merkle_root,
        };
        manifest.validate()?;
        Ok(manifest)
    }
}

impl FileMerkleProofV1 {
    pub fn validate_shape(&self) -> Result<(), FileIdentityError> {
        if self.transfer_id.iter().all(|byte| *byte == 0)
            || self.chunk_index >= MAX_FILE_CHUNK_COUNT
            || self.siblings.len() > MAX_FILE_MERKLE_PROOF_SIBLINGS
        {
            return Err(FileIdentityError::InvalidProof {
                reason: "invalid proof transfer, index or sibling count",
            });
        }
        Ok(())
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, FileIdentityError> {
        self.validate_shape()?;
        let sibling_count = u8::try_from(self.siblings.len()).map_err(|_| {
            FileIdentityError::InvalidProof {
                reason: "too many proof siblings",
            }
        })?;
        let total = FILE_MERKLE_PROOF_PREFIX_BYTES
            .checked_add(
                self.siblings
                    .len()
                    .checked_mul(FILE_IDENTITY_HASH_BYTES)
                    .ok_or(FileIdentityError::LengthOverflow)?,
            )
            .ok_or(FileIdentityError::LengthOverflow)?;
        let mut out = Vec::with_capacity(total);
        out.extend_from_slice(&FILE_MERKLE_PROOF_MAGIC);
        out.push(FILE_IDENTITY_VERSION_V1);
        out.extend_from_slice(&self.transfer_id);
        out.extend_from_slice(&self.chunk_index.to_be_bytes());
        out.push(sibling_count);
        for sibling in &self.siblings {
            out.extend_from_slice(sibling);
        }
        Ok(out)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, FileIdentityError> {
        if bytes.len() < FILE_MERKLE_PROOF_PREFIX_BYTES
            || bytes.len() > MAX_FILE_MERKLE_PROOF_BYTES
        {
            return Err(FileIdentityError::LengthMismatch {
                expected: FILE_MERKLE_PROOF_PREFIX_BYTES,
                actual: bytes.len(),
            });
        }
        if &bytes[..4] != FILE_MERKLE_PROOF_MAGIC.as_slice() {
            return Err(FileIdentityError::InvalidMagic);
        }
        if bytes[4] != FILE_IDENTITY_VERSION_V1 {
            return Err(FileIdentityError::UnsupportedVersion { got: bytes[4] });
        }
        let sibling_count = usize::from(bytes[29]);
        if sibling_count > MAX_FILE_MERKLE_PROOF_SIBLINGS {
            return Err(FileIdentityError::InvalidProof {
                reason: "too many proof siblings",
            });
        }
        let expected = FILE_MERKLE_PROOF_PREFIX_BYTES
            .checked_add(
                sibling_count
                    .checked_mul(FILE_IDENTITY_HASH_BYTES)
                    .ok_or(FileIdentityError::LengthOverflow)?,
            )
            .ok_or(FileIdentityError::LengthOverflow)?;
        if bytes.len() != expected {
            return Err(FileIdentityError::LengthMismatch {
                expected,
                actual: bytes.len(),
            });
        }
        let mut siblings = Vec::with_capacity(sibling_count);
        let mut offset = FILE_MERKLE_PROOF_PREFIX_BYTES;
        for _ in 0..sibling_count {
            siblings.push(array_at(bytes, offset)?);
            offset += FILE_IDENTITY_HASH_BYTES;
        }
        let proof = Self {
            transfer_id: array_at(bytes, 5)?,
            chunk_index: u64_at(bytes, 21)?,
            siblings,
        };
        proof.validate_shape()?;
        Ok(proof)
    }
}

pub fn build_file_merkle_manifest_v1(
    geometry: FileGeometryV1,
    identities: &[CiphertextChunkIdentityV1],
) -> Result<FileIdentityManifestV1, FileIdentityError> {
    if u64::try_from(identities.len()).map_err(|_| FileIdentityError::LengthOverflow)?
        != geometry.chunk_count
    {
        return Err(FileIdentityError::IncompleteChunkSequence);
    }
    let mut accumulator = FileMerkleAccumulatorV1::new(geometry)?;
    for identity in identities {
        accumulator.push(identity)?;
    }
    accumulator.finish()
}

pub fn build_file_merkle_proof_v1(
    manifest: &FileIdentityManifestV1,
    identities: &[CiphertextChunkIdentityV1],
    chunk_index: u64,
) -> Result<FileMerkleProofV1, FileIdentityError> {
    manifest.validate()?;
    if chunk_index >= manifest.geometry.chunk_count
        || u64::try_from(identities.len()).map_err(|_| FileIdentityError::LengthOverflow)?
            != manifest.geometry.chunk_count
    {
        return Err(FileIdentityError::InvalidProof {
            reason: "proof target or identity count does not match manifest",
        });
    }
    let rebuilt = build_file_merkle_manifest_v1(manifest.geometry.clone(), identities)?;
    if rebuilt.merkle_root != manifest.merkle_root {
        return Err(FileIdentityError::RootMismatch);
    }
    let mut level = Vec::with_capacity(identities.len());
    for identity in identities {
        level.push(identity.leaf_hash()?);
    }
    let mut index = usize::try_from(chunk_index).map_err(|_| FileIdentityError::LengthOverflow)?;
    let mut siblings = Vec::new();
    while level.len() > 1 {
        if index & 1 == 1 {
            siblings.push(level[index - 1]);
        } else if index + 1 < level.len() {
            siblings.push(level[index + 1]);
        }
        let mut next = Vec::with_capacity(1 + (level.len() - 1) / 2);
        let mut pair = 0usize;
        while pair < level.len() {
            if pair + 1 < level.len() {
                next.push(merkle_parent(&level[pair], &level[pair + 1]));
            } else {
                next.push(level[pair]);
            }
            pair += 2;
        }
        index /= 2;
        level = next;
    }
    let proof = FileMerkleProofV1 {
        transfer_id: manifest.geometry.transfer_id,
        chunk_index,
        siblings,
    };
    proof.validate_shape()?;
    Ok(proof)
}

pub fn verify_file_merkle_proof_v1(
    manifest: &FileIdentityManifestV1,
    identity: &CiphertextChunkIdentityV1,
    proof: &FileMerkleProofV1,
) -> Result<(), FileIdentityError> {
    manifest.validate()?;
    proof.validate_shape()?;
    identity.validate_against(&manifest.geometry)?;
    if proof.transfer_id != manifest.geometry.transfer_id
        || proof.transfer_id != identity.transfer_id
        || proof.chunk_index != identity.chunk_index
    {
        return Err(FileIdentityError::InvalidProof {
            reason: "proof identity does not match manifest or chunk",
        });
    }
    let mut hash = identity.leaf_hash()?;
    let mut index = identity.chunk_index;
    let mut width = manifest.geometry.chunk_count;
    let mut sibling_index = 0usize;
    while width > 1 {
        let has_sibling = index & 1 == 1 || index + 1 < width;
        if has_sibling {
            let sibling = proof.siblings.get(sibling_index).ok_or(
                FileIdentityError::InvalidProof {
                    reason: "proof is missing a required sibling",
                },
            )?;
            hash = if index & 1 == 1 {
                merkle_parent(sibling, &hash)
            } else {
                merkle_parent(&hash, sibling)
            };
            sibling_index += 1;
        }
        index /= 2;
        width = 1 + (width - 1) / 2;
    }
    if sibling_index != proof.siblings.len() {
        return Err(FileIdentityError::InvalidProof {
            reason: "proof contains unused siblings",
        });
    }
    if hash != manifest.merkle_root {
        return Err(FileIdentityError::RootMismatch);
    }
    Ok(())
}

fn expected_chunk_count(file_size: u64, chunk_size: u32) -> Result<u64, FileIdentityError> {
    if !(MIN_FILE_CHUNK_BYTES..=MAX_FILE_CHUNK_BYTES).contains(&chunk_size)
        || !chunk_size.is_power_of_two()
    {
        return Err(FileIdentityError::InvalidGeometry {
            reason: "chunk size is outside bounds or not a power of two",
        });
    }
    Ok(if file_size == 0 {
        0
    } else {
        1 + (file_size - 1) / u64::from(chunk_size)
    })
}

fn merkle_parent(
    left: &[u8; FILE_IDENTITY_HASH_BYTES],
    right: &[u8; FILE_IDENTITY_HASH_BYTES],
) -> [u8; FILE_IDENTITY_HASH_BYTES] {
    let mut hasher = Sha256::new();
    hasher.update(FILE_MERKLE_NODE_DOMAIN_V1);
    hasher.update([FILE_IDENTITY_VERSION_V1]);
    hasher.update(left);
    hasher.update(right);
    hasher.finalize().into()
}

fn empty_merkle_root(
    transfer_id: &[u8; FILE_TRANSFER_ID_BYTES],
) -> [u8; FILE_IDENTITY_HASH_BYTES] {
    let mut hasher = Sha256::new();
    hasher.update(FILE_MERKLE_EMPTY_DOMAIN_V1);
    hasher.update([FILE_IDENTITY_VERSION_V1]);
    hasher.update(transfer_id);
    hasher.finalize().into()
}

fn array_at<const N: usize>(
    bytes: &[u8],
    offset: usize,
) -> Result<[u8; N], FileIdentityError> {
    let end = offset
        .checked_add(N)
        .ok_or(FileIdentityError::LengthOverflow)?;
    bytes
        .get(offset..end)
        .ok_or(FileIdentityError::LengthOverflow)?
        .try_into()
        .map_err(|_| FileIdentityError::LengthOverflow)
}

fn u32_at(bytes: &[u8], offset: usize) -> Result<u32, FileIdentityError> {
    Ok(u32::from_be_bytes(array_at(bytes, offset)?))
}

fn u64_at(bytes: &[u8], offset: usize) -> Result<u64, FileIdentityError> {
    Ok(u64::from_be_bytes(array_at(bytes, offset)?))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::network::file_control::{FileChunkRangeV1, FileChunkRangePageV1};
    use crate::network::file_wire::{FileChunkDataV1, FileFrameV1};

    fn geometry(chunk_count: u64) -> FileGeometryV1 {
        FileGeometryV1::new(
            [0x11; FILE_TRANSFER_ID_BYTES],
            chunk_count * u64::from(MIN_FILE_CHUNK_BYTES),
            MIN_FILE_CHUNK_BYTES,
        )
        .unwrap()
    }

    fn identity(
        geometry: &FileGeometryV1,
        chunk_index: u64,
        byte: u8,
    ) -> CiphertextChunkIdentityV1 {
        let len = geometry.expected_ciphertext_len(chunk_index).unwrap() as usize;
        CiphertextChunkIdentityV1::from_ciphertext(geometry, chunk_index, &vec![byte; len]).unwrap()
    }

    fn identities(geometry: &FileGeometryV1) -> Vec<CiphertextChunkIdentityV1> {
        (0..geometry.chunk_count)
            .map(|index| identity(geometry, index, index as u8 + 1))
            .collect()
    }

    #[test]
    fn u64_geometry_has_no_four_gib_cap_and_exact_final_chunk() {
        let above_four_gib = 5 * 1024 * 1024 * 1024u64 + 7;
        let geometry = FileGeometryV1::new(
            [1; FILE_TRANSFER_ID_BYTES],
            above_four_gib,
            MAX_FILE_CHUNK_BYTES,
        )
        .unwrap();
        assert_eq!(
            geometry.chunk_count,
            1 + (above_four_gib - 1) / u64::from(MAX_FILE_CHUNK_BYTES)
        );
        assert_eq!(geometry.expected_plaintext_len(geometry.chunk_count - 1), Ok(7));

        let maximum = FileGeometryV1::new(
            [2; FILE_TRANSFER_ID_BYTES],
            u64::MAX,
            MIN_FILE_CHUNK_BYTES,
        )
        .unwrap();
        assert_eq!(maximum.chunk_count, MAX_FILE_CHUNK_COUNT);
        assert!(maximum
            .expected_plaintext_len(maximum.chunk_count - 1)
            .unwrap()
            > 0);
    }

    #[test]
    fn empty_geometry_has_transfer_bound_root_and_canonical_manifest() {
        let first = FileGeometryV1::new([3; FILE_TRANSFER_ID_BYTES], 0, MIN_FILE_CHUNK_BYTES).unwrap();
        let second = FileGeometryV1::new([4; FILE_TRANSFER_ID_BYTES], 0, MIN_FILE_CHUNK_BYTES).unwrap();
        let first_manifest = FileMerkleAccumulatorV1::new(first).unwrap().finish().unwrap();
        let second_manifest = FileMerkleAccumulatorV1::new(second).unwrap().finish().unwrap();
        assert_ne!(first_manifest.merkle_root, second_manifest.merkle_root);
        let bytes = first_manifest.to_bytes().unwrap();
        assert_eq!(bytes.len(), FILE_IDENTITY_MANIFEST_BYTES);
        assert_eq!(FileIdentityManifestV1::from_bytes(&bytes).unwrap(), first_manifest);
        assert_eq!(bytes, first_manifest.to_bytes().unwrap());
    }

    #[test]
    fn ciphertext_identity_is_canonical_index_bound_and_round_trips() {
        let geometry = geometry(2);
        let first = identity(&geometry, 0, 0xA1);
        let second = identity(&geometry, 1, 0xA1);
        assert_ne!(first.leaf_hash().unwrap(), second.leaf_hash().unwrap());
        let bytes = first.to_bytes().unwrap();
        assert_eq!(bytes.len(), FILE_CHUNK_IDENTITY_BYTES);
        assert_eq!(CiphertextChunkIdentityV1::from_bytes(&bytes).unwrap(), first);
        assert_eq!(bytes, first.to_bytes().unwrap());

        for len in 0..bytes.len() {
            assert!(CiphertextChunkIdentityV1::from_bytes(&bytes[..len]).is_err());
        }
        let mut trailing = bytes;
        trailing.push(0);
        assert!(CiphertextChunkIdentityV1::from_bytes(&trailing).is_err());
    }

    #[test]
    fn ciphertext_length_digest_transfer_and_geometry_mismatch_are_rejected() {
        let geometry = geometry(2);
        let expected = geometry.expected_ciphertext_len(0).unwrap() as usize;
        assert!(CiphertextChunkIdentityV1::from_ciphertext(
            &geometry,
            0,
            &vec![1; expected - 1],
        )
        .is_err());
        let ciphertext = vec![1; expected];
        let committed = CiphertextChunkIdentityV1::from_ciphertext(&geometry, 0, &ciphertext).unwrap();
        assert_eq!(committed.verify_ciphertext(&geometry, &ciphertext), Ok(()));
        let mut tampered = ciphertext;
        tampered[0] ^= 1;
        assert!(committed.verify_ciphertext(&geometry, &tampered).is_err());

        let mut value = identity(&geometry, 0, 1);
        value.ciphertext_sha256 = [0; FILE_IDENTITY_HASH_BYTES];
        assert!(value.validate_against(&geometry).is_err());
        let mut value = identity(&geometry, 0, 1);
        value.transfer_id[0] ^= 1;
        assert!(value.validate_against(&geometry).is_err());
        let mut value = identity(&geometry, 0, 1);
        value.plaintext_len -= 1;
        assert!(value.validate_against(&geometry).is_err());
    }

    #[test]
    fn streaming_accumulator_matches_batch_for_odd_and_even_leaf_counts() {
        let mut roots = Vec::new();
        for count in 1..=7 {
            let geometry = geometry(count);
            let leaves = identities(&geometry);
            let batch = build_file_merkle_manifest_v1(geometry.clone(), &leaves).unwrap();
            let mut streaming = FileMerkleAccumulatorV1::new(geometry).unwrap();
            for (index, leaf) in leaves.iter().enumerate() {
                streaming.push(leaf).unwrap();
                assert_eq!(streaming.leaf_count(), index as u64 + 1);
            }
            assert_eq!(streaming.finish().unwrap(), batch);
            roots.push(batch.merkle_root);
        }
        for pair in roots.windows(2) {
            assert_ne!(pair[0], pair[1]);
        }
    }

    #[test]
    fn proof_for_every_leaf_handles_odd_promotion_and_round_trips() {
        for count in 1..=7 {
            let geometry = geometry(count);
            let leaves = identities(&geometry);
            let manifest = build_file_merkle_manifest_v1(geometry, &leaves).unwrap();
            for index in 0..count {
                let proof = build_file_merkle_proof_v1(&manifest, &leaves, index).unwrap();
                let bytes = proof.to_bytes().unwrap();
                assert_eq!(FileMerkleProofV1::from_bytes(&bytes).unwrap(), proof);
                assert_eq!(
                    verify_file_merkle_proof_v1(&manifest, &leaves[index as usize], &proof),
                    Ok(())
                );
            }
        }
    }

    #[test]
    fn proof_tamper_wrong_leaf_root_transfer_and_extra_sibling_fail_closed() {
        let geometry = geometry(5);
        let leaves = identities(&geometry);
        let manifest = build_file_merkle_manifest_v1(geometry, &leaves).unwrap();
        let proof = build_file_merkle_proof_v1(&manifest, &leaves, 3).unwrap();

        let mut wrong_sibling = proof.clone();
        wrong_sibling.siblings[0][0] ^= 1;
        assert!(verify_file_merkle_proof_v1(&manifest, &leaves[3], &wrong_sibling).is_err());
        let mut wrong_root = manifest.clone();
        wrong_root.merkle_root[0] ^= 1;
        assert!(verify_file_merkle_proof_v1(&wrong_root, &leaves[3], &proof).is_err());
        let mut wrong_transfer = proof.clone();
        wrong_transfer.transfer_id[0] ^= 1;
        assert!(verify_file_merkle_proof_v1(&manifest, &leaves[3], &wrong_transfer).is_err());
        let mut extra = proof.clone();
        extra.siblings.push([9; FILE_IDENTITY_HASH_BYTES]);
        assert!(verify_file_merkle_proof_v1(&manifest, &leaves[3], &extra).is_err());
        assert!(verify_file_merkle_proof_v1(&manifest, &leaves[2], &proof).is_err());
    }

    #[test]
    fn proof_wire_rejects_truncation_trailing_unknown_version_and_oversize() {
        let geometry = geometry(3);
        let leaves = identities(&geometry);
        let manifest = build_file_merkle_manifest_v1(geometry, &leaves).unwrap();
        let proof = build_file_merkle_proof_v1(&manifest, &leaves, 1).unwrap();
        let bytes = proof.to_bytes().unwrap();
        for len in 0..bytes.len() {
            assert!(FileMerkleProofV1::from_bytes(&bytes[..len]).is_err());
        }
        let mut trailing = bytes.clone();
        trailing.push(0);
        assert!(FileMerkleProofV1::from_bytes(&trailing).is_err());
        let mut version = bytes;
        version[4] = 2;
        assert_eq!(
            FileMerkleProofV1::from_bytes(&version),
            Err(FileIdentityError::UnsupportedVersion { got: 2 })
        );
        let oversized = FileMerkleProofV1 {
            transfer_id: [1; FILE_TRANSFER_ID_BYTES],
            chunk_index: 0,
            siblings: vec![[1; FILE_IDENTITY_HASH_BYTES]; MAX_FILE_MERKLE_PROOF_SIBLINGS + 1],
        };
        assert!(oversized.to_bytes().is_err());
    }

    #[test]
    fn accumulator_rejects_out_of_order_missing_and_extra_chunks() {
        let geometry = geometry(2);
        let leaves = identities(&geometry);
        let mut out_of_order = FileMerkleAccumulatorV1::new(geometry.clone()).unwrap();
        assert_eq!(
            out_of_order.push(&leaves[1]),
            Err(FileIdentityError::OutOfOrderChunk)
        );
        let mut missing = FileMerkleAccumulatorV1::new(geometry.clone()).unwrap();
        missing.push(&leaves[0]).unwrap();
        assert!(matches!(
            missing.finish(),
            Err(FileIdentityError::IncompleteChunkSequence)
        ));
        let mut complete = FileMerkleAccumulatorV1::new(geometry).unwrap();
        complete.push(&leaves[0]).unwrap();
        complete.push(&leaves[1]).unwrap();
        assert!(complete.push(&leaves[1]).is_err());
    }

    #[test]
    fn manifest_commitment_and_wire_tamper_are_detected() {
        let geometry = geometry(2);
        let leaves = identities(&geometry);
        let manifest = build_file_merkle_manifest_v1(geometry, &leaves).unwrap();
        assert_eq!(manifest.commitment().unwrap(), manifest.commitment().unwrap());
        let bytes = manifest.to_bytes().unwrap();
        for index in 0..bytes.len() {
            let mut tampered = bytes.clone();
            tampered[index] ^= 1;
            if let Ok(changed) = FileIdentityManifestV1::from_bytes(&tampered) {
                assert_ne!(changed.commitment().unwrap(), manifest.commitment().unwrap());
            }
        }
        let mut trailing = bytes;
        trailing.push(0);
        assert!(FileIdentityManifestV1::from_bytes(&trailing).is_err());
    }

    #[test]
    fn preproduction_wire_and_control_ranges_preserve_indices_above_u32() {
        let high = u64::from(u32::MAX) + 7;
        let wire = FileFrameV1::ChunkData(FileChunkDataV1 {
            transfer_id: [0x51; FILE_TRANSFER_ID_BYTES],
            chunk_index: high,
            chunk_offset: 0,
            ciphertext_chunk_len: (FILE_CHUNK_TAG_BYTES + 1) as u32,
            ciphertext: vec![1],
        });
        let bytes = wire.encode().unwrap();
        assert_eq!(FileFrameV1::decode(&bytes).unwrap(), wire);

        let page = FileChunkRangePageV1 {
            batch_id: [0x61; FILE_TRANSFER_ID_BYTES],
            page_index: 0,
            is_last_page: true,
            total_chunk_count: high + 2,
            ranges: vec![FileChunkRangeV1 {
                start_chunk: high,
                end_chunk_exclusive: high + 1,
            }],
        };
        assert_eq!(page.ranges[0].start_chunk, high);
    }
}
