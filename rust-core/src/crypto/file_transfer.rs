//! Bounded cryptographic foundation for Secure File Transfer MVP.
//! Transport, persistence and UI are deliberately not wired in this slice.

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    XChaCha20Poly1305, XNonce,
};
use sha2::{Digest, Sha256};

pub const FILE_TRANSFER_VERSION_V1: u8 = 1;
pub const FILE_TRANSFER_ID_BYTES: usize = 16;
pub const FILE_KEY_BYTES: usize = 32;
pub const FILE_HASH_BYTES: usize = 32;
pub const FILE_CHUNK_TAG_BYTES: usize = 16;
pub const DEFAULT_FILE_CHUNK_BYTES: u32 = 128 * 1024;
pub const MIN_FILE_CHUNK_BYTES: u32 = 16 * 1024;
pub const MAX_FILE_CHUNK_BYTES: u32 = 256 * 1024;
pub const MAX_FILE_BYTES: u64 = 10 * 1024 * 1024;
pub const MAX_FILE_NAME_BYTES: usize = 255;
pub const MAX_MEDIA_TYPE_BYTES: usize = 127;
pub const MAX_FILE_TRANSFER_LIFETIME_MS: i64 = 30 * 24 * 60 * 60 * 1_000;
const MANIFEST_DOMAIN_V1: &[u8] = b"apu-file-manifest-v1\0";
const CHUNK_DOMAIN_V1: &[u8] = b"apu-file-chunk-v1\0";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileTransferManifestV1 {
    pub transfer_id: [u8; FILE_TRANSFER_ID_BYTES],
    pub sender_node_id: String,
    pub recipient_node_id: String,
    pub display_name: String,
    pub media_type: String,
    pub file_size: u64,
    pub chunk_size: u32,
    pub chunk_count: u32,
    pub file_sha256: [u8; FILE_HASH_BYTES],
    pub created_at_ms: i64,
    pub expires_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum FileTransferError {
    #[error("invalid transfer id")]
    InvalidTransferId,
    #[error("invalid sender or recipient")]
    InvalidPeer,
    #[error("invalid file display name")]
    InvalidDisplayName,
    #[error("invalid media type")]
    InvalidMediaType,
    #[error("invalid file size or chunk geometry")]
    InvalidGeometry,
    #[error("invalid transfer time window")]
    InvalidTimeWindow,
    #[error("malformed canonical file manifest")]
    MalformedManifest,
    #[error("file key must be exactly 32 bytes")]
    InvalidKey,
    #[error("chunk index is outside the manifest")]
    InvalidChunkIndex,
    #[error("chunk length does not match the manifest")]
    InvalidChunkLength,
    #[error("file chunk authentication failed")]
    AuthenticationFailed,
}

impl FileTransferManifestV1 {
    pub fn validate(&self) -> Result<(), FileTransferError> {
        if self.transfer_id.iter().all(|byte| *byte == 0) {
            return Err(FileTransferError::InvalidTransferId);
        }
        if !is_legacy_node_id(&self.sender_node_id)
            || !is_legacy_node_id(&self.recipient_node_id)
            || self.sender_node_id == self.recipient_node_id
        {
            return Err(FileTransferError::InvalidPeer);
        }
        if !is_safe_display_name(&self.display_name) {
            return Err(FileTransferError::InvalidDisplayName);
        }
        if !is_media_type(&self.media_type) {
            return Err(FileTransferError::InvalidMediaType);
        }
        if self.file_size > MAX_FILE_BYTES
            || self.chunk_size < MIN_FILE_CHUNK_BYTES
            || self.chunk_size > MAX_FILE_CHUNK_BYTES
            || !self.chunk_size.is_power_of_two()
            || self.chunk_count != expected_chunk_count(self.file_size, self.chunk_size)
        {
            return Err(FileTransferError::InvalidGeometry);
        }
        let lifetime = self.expires_at_ms.saturating_sub(self.created_at_ms);
        if self.created_at_ms < 0
            || lifetime <= 0
            || lifetime > MAX_FILE_TRANSFER_LIFETIME_MS
        {
            return Err(FileTransferError::InvalidTimeWindow);
        }
        Ok(())
    }

    pub fn canonical_bytes(&self) -> Result<Vec<u8>, FileTransferError> {
        self.validate()?;
        let sender = self.sender_node_id.as_bytes();
        let recipient = self.recipient_node_id.as_bytes();
        let name = self.display_name.as_bytes();
        let media_type = self.media_type.as_bytes();
        let mut output = Vec::with_capacity(
            MANIFEST_DOMAIN_V1.len()
                + 1
                + 16
                + 2 * 4
                + sender.len()
                + recipient.len()
                + name.len()
                + media_type.len()
                + 8
                + 4
                + 4
                + 32
                + 8
                + 8,
        );
        output.extend_from_slice(MANIFEST_DOMAIN_V1);
        output.push(FILE_TRANSFER_VERSION_V1);
        output.extend_from_slice(&self.transfer_id);
        append_bounded(&mut output, sender)?;
        append_bounded(&mut output, recipient)?;
        append_bounded(&mut output, name)?;
        append_bounded(&mut output, media_type)?;
        output.extend_from_slice(&self.file_size.to_be_bytes());
        output.extend_from_slice(&self.chunk_size.to_be_bytes());
        output.extend_from_slice(&self.chunk_count.to_be_bytes());
        output.extend_from_slice(&self.file_sha256);
        output.extend_from_slice(&self.created_at_ms.to_be_bytes());
        output.extend_from_slice(&self.expires_at_ms.to_be_bytes());
        Ok(output)
    }

    pub fn from_canonical_bytes(bytes: &[u8]) -> Result<Self, FileTransferError> {
        let mut cursor = ManifestCursor::new(bytes);
        if cursor.take(MANIFEST_DOMAIN_V1.len())? != MANIFEST_DOMAIN_V1
            || cursor.u8()? != FILE_TRANSFER_VERSION_V1
        {
            return Err(FileTransferError::MalformedManifest);
        }
        let transfer_id = cursor
            .take(FILE_TRANSFER_ID_BYTES)?
            .try_into()
            .map_err(|_| FileTransferError::MalformedManifest)?;
        let sender_node_id = cursor.string()?;
        let recipient_node_id = cursor.string()?;
        let display_name = cursor.string()?;
        let media_type = cursor.string()?;
        let file_size = cursor.u64()?;
        let chunk_size = cursor.u32()?;
        let chunk_count = cursor.u32()?;
        let file_sha256 = cursor
            .take(FILE_HASH_BYTES)?
            .try_into()
            .map_err(|_| FileTransferError::MalformedManifest)?;
        let created_at_ms = cursor.i64()?;
        let expires_at_ms = cursor.i64()?;
        if !cursor.is_finished() {
            return Err(FileTransferError::MalformedManifest);
        }
        let manifest = Self {
            transfer_id,
            sender_node_id,
            recipient_node_id,
            display_name,
            media_type,
            file_size,
            chunk_size,
            chunk_count,
            file_sha256,
            created_at_ms,
            expires_at_ms,
        };
        manifest.validate()?;
        Ok(manifest)
    }

    pub fn manifest_sha256(&self) -> Result<[u8; 32], FileTransferError> {
        Ok(Sha256::digest(self.canonical_bytes()?).into())
    }

    pub fn plaintext_len(&self, chunk_index: u32) -> Result<usize, FileTransferError> {
        self.validate()?;
        if chunk_index >= self.chunk_count {
            return Err(FileTransferError::InvalidChunkIndex);
        }
        let offset = u64::from(chunk_index) * u64::from(self.chunk_size);
        Ok((self.file_size - offset).min(u64::from(self.chunk_size)) as usize)
    }
}

pub fn expected_chunk_count(file_size: u64, chunk_size: u32) -> u32 {
    if file_size == 0 || chunk_size == 0 {
        return 0;
    }
    (((file_size - 1) / u64::from(chunk_size)) + 1) as u32
}

pub fn encrypt_file_chunk_v1(
    manifest: &FileTransferManifestV1,
    file_key: &[u8],
    chunk_index: u32,
    plaintext: &[u8],
) -> Result<Vec<u8>, FileTransferError> {
    let expected = manifest.plaintext_len(chunk_index)?;
    if plaintext.len() != expected {
        return Err(FileTransferError::InvalidChunkLength);
    }
    let cipher = cipher(file_key)?;
    cipher
        .encrypt(
            XNonce::from_slice(&chunk_nonce(manifest, chunk_index)),
            Payload {
                msg: plaintext,
                aad: &chunk_aad(manifest, chunk_index)?,
            },
        )
        .map_err(|_| FileTransferError::AuthenticationFailed)
}

pub fn decrypt_file_chunk_v1(
    manifest: &FileTransferManifestV1,
    file_key: &[u8],
    chunk_index: u32,
    ciphertext: &[u8],
) -> Result<Vec<u8>, FileTransferError> {
    let expected = manifest.plaintext_len(chunk_index)?;
    if ciphertext.len() != expected + FILE_CHUNK_TAG_BYTES {
        return Err(FileTransferError::InvalidChunkLength);
    }
    let cipher = cipher(file_key)?;
    let plaintext = cipher
        .decrypt(
            XNonce::from_slice(&chunk_nonce(manifest, chunk_index)),
            Payload {
                msg: ciphertext,
                aad: &chunk_aad(manifest, chunk_index)?,
            },
        )
        .map_err(|_| FileTransferError::AuthenticationFailed)?;
    if plaintext.len() != expected {
        return Err(FileTransferError::InvalidChunkLength);
    }
    Ok(plaintext)
}

fn cipher(file_key: &[u8]) -> Result<XChaCha20Poly1305, FileTransferError> {
    XChaCha20Poly1305::new_from_slice(file_key).map_err(|_| FileTransferError::InvalidKey)
}

fn chunk_nonce(manifest: &FileTransferManifestV1, chunk_index: u32) -> [u8; 24] {
    let mut nonce = [0u8; 24];
    nonce[..16].copy_from_slice(&manifest.transfer_id);
    nonce[16..].copy_from_slice(&u64::from(chunk_index).to_be_bytes());
    nonce
}

fn chunk_aad(
    manifest: &FileTransferManifestV1,
    chunk_index: u32,
) -> Result<Vec<u8>, FileTransferError> {
    let mut aad = Vec::with_capacity(CHUNK_DOMAIN_V1.len() + 32 + 4);
    aad.extend_from_slice(CHUNK_DOMAIN_V1);
    aad.extend_from_slice(&manifest.manifest_sha256()?);
    aad.extend_from_slice(&chunk_index.to_be_bytes());
    Ok(aad)
}

fn append_bounded(output: &mut Vec<u8>, value: &[u8]) -> Result<(), FileTransferError> {
    let length = u16::try_from(value.len()).map_err(|_| FileTransferError::InvalidGeometry)?;
    output.extend_from_slice(&length.to_be_bytes());
    output.extend_from_slice(value);
    Ok(())
}

struct ManifestCursor<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> ManifestCursor<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }

    fn take(&mut self, count: usize) -> Result<&'a [u8], FileTransferError> {
        let end = self
            .offset
            .checked_add(count)
            .ok_or(FileTransferError::MalformedManifest)?;
        let value = self
            .bytes
            .get(self.offset..end)
            .ok_or(FileTransferError::MalformedManifest)?;
        self.offset = end;
        Ok(value)
    }

    fn u8(&mut self) -> Result<u8, FileTransferError> {
        self.take(1)?
            .first()
            .copied()
            .ok_or(FileTransferError::MalformedManifest)
    }

    fn u16(&mut self) -> Result<u16, FileTransferError> {
        Ok(u16::from_be_bytes(
            self.take(2)?
                .try_into()
                .map_err(|_| FileTransferError::MalformedManifest)?,
        ))
    }

    fn u32(&mut self) -> Result<u32, FileTransferError> {
        Ok(u32::from_be_bytes(
            self.take(4)?
                .try_into()
                .map_err(|_| FileTransferError::MalformedManifest)?,
        ))
    }

    fn u64(&mut self) -> Result<u64, FileTransferError> {
        Ok(u64::from_be_bytes(
            self.take(8)?
                .try_into()
                .map_err(|_| FileTransferError::MalformedManifest)?,
        ))
    }

    fn i64(&mut self) -> Result<i64, FileTransferError> {
        Ok(i64::from_be_bytes(
            self.take(8)?
                .try_into()
                .map_err(|_| FileTransferError::MalformedManifest)?,
        ))
    }

    fn string(&mut self) -> Result<String, FileTransferError> {
        let length = usize::from(self.u16()?);
        std::str::from_utf8(self.take(length)?)
            .map(str::to_owned)
            .map_err(|_| FileTransferError::MalformedManifest)
    }

    fn is_finished(&self) -> bool {
        self.offset == self.bytes.len()
    }
}

fn is_legacy_node_id(value: &str) -> bool {
    let Some(suffix) = value.strip_prefix("pk_") else {
        return false;
    };
    matches!(suffix.len(), 32 | 64)
        && suffix
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

fn is_safe_display_name(value: &str) -> bool {
    let length = value.len();
    length > 0
        && length <= MAX_FILE_NAME_BYTES
        && value != "."
        && value != ".."
        && value.trim() == value
        && !value
            .chars()
            .any(|character| character.is_control() || matches!(character, '/' | '\\'))
}

fn is_media_type(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= MAX_MEDIA_TYPE_BYTES
        && value.is_ascii()
        && value.contains('/')
        && value
            .bytes()
            .all(|byte| byte.is_ascii_graphic() && byte != b'\\')
}

#[cfg(test)]
mod tests {
    use super::*;

    const CREATED: i64 = 1_800_000_000_000;

    fn manifest(file_size: u64) -> FileTransferManifestV1 {
        FileTransferManifestV1 {
            transfer_id: [0x41; 16],
            sender_node_id: "pk_0123456789abcdef0123456789abcdef".to_string(),
            recipient_node_id: "pk_fedcba9876543210fedcba9876543210".to_string(),
            display_name: "report.pdf".to_string(),
            media_type: "application/pdf".to_string(),
            file_size,
            chunk_size: DEFAULT_FILE_CHUNK_BYTES,
            chunk_count: expected_chunk_count(file_size, DEFAULT_FILE_CHUNK_BYTES),
            file_sha256: [0x52; 32],
            created_at_ms: CREATED,
            expires_at_ms: CREATED + 7 * 24 * 60 * 60 * 1_000,
        }
    }

    #[test]
    fn canonical_manifest_is_stable_and_domain_separated() {
        let value = manifest(5);
        let first = value.canonical_bytes().unwrap();
        assert_eq!(first, value.canonical_bytes().unwrap());
        assert!(first.starts_with(MANIFEST_DOMAIN_V1));
        assert_eq!(first[MANIFEST_DOMAIN_V1.len()], FILE_TRANSFER_VERSION_V1);
    }

    #[test]
    fn canonical_manifest_wire_round_trip_rejects_truncation_and_trailing_bytes() {
        let value = manifest(DEFAULT_FILE_CHUNK_BYTES as u64 + 1);
        let bytes = value.canonical_bytes().unwrap();
        assert_eq!(
            FileTransferManifestV1::from_canonical_bytes(&bytes).unwrap(),
            value
        );
        for length in 0..bytes.len() {
            assert!(FileTransferManifestV1::from_canonical_bytes(&bytes[..length]).is_err());
        }
        assert!(FileTransferManifestV1::from_canonical_bytes(&[bytes, vec![0]].concat()).is_err());
    }

    #[test]
    fn chunk_round_trip_and_retry_are_deterministic() {
        let value = manifest(5);
        let key = [0x33; 32];
        let plaintext = b"hello";
        let first = encrypt_file_chunk_v1(&value, &key, 0, plaintext).unwrap();
        let retry = encrypt_file_chunk_v1(&value, &key, 0, plaintext).unwrap();
        assert_eq!(first, retry);
        assert_eq!(decrypt_file_chunk_v1(&value, &key, 0, &first).unwrap(), plaintext);
    }

    #[test]
    fn tamper_wrong_key_index_and_manifest_are_rejected() {
        let value = manifest(u64::from(DEFAULT_FILE_CHUNK_BYTES) * 2);
        let key = [0x34; 32];
        let plaintext = vec![7; DEFAULT_FILE_CHUNK_BYTES as usize];
        let encrypted = encrypt_file_chunk_v1(&value, &key, 0, &plaintext).unwrap();

        let mut tampered = encrypted.clone();
        tampered[0] ^= 1;
        assert_eq!(
            decrypt_file_chunk_v1(&value, &key, 0, &tampered),
            Err(FileTransferError::AuthenticationFailed)
        );
        assert_eq!(
            decrypt_file_chunk_v1(&value, &[0x35; 32], 0, &encrypted),
            Err(FileTransferError::AuthenticationFailed)
        );
        assert_eq!(
            decrypt_file_chunk_v1(&value, &key, 1, &encrypted),
            Err(FileTransferError::AuthenticationFailed)
        );
        let mut changed = value;
        changed.display_name = "changed.pdf".to_string();
        assert_eq!(
            decrypt_file_chunk_v1(&changed, &key, 0, &encrypted),
            Err(FileTransferError::AuthenticationFailed)
        );
    }

    #[test]
    fn geometry_boundaries_and_empty_file_are_exact() {
        for size in [
            0,
            1,
            u64::from(DEFAULT_FILE_CHUNK_BYTES - 1),
            u64::from(DEFAULT_FILE_CHUNK_BYTES),
            u64::from(DEFAULT_FILE_CHUNK_BYTES + 1),
            MAX_FILE_BYTES,
        ] {
            assert!(manifest(size).validate().is_ok());
        }
        assert_eq!(manifest(0).chunk_count, 0);
        assert_eq!(manifest(1).plaintext_len(0).unwrap(), 1);
        assert_eq!(
            manifest(u64::from(DEFAULT_FILE_CHUNK_BYTES) + 1)
                .plaintext_len(1)
                .unwrap(),
            1
        );
        assert_eq!(
            manifest(0).plaintext_len(0),
            Err(FileTransferError::InvalidChunkIndex)
        );
        assert_eq!(
            manifest(MAX_FILE_BYTES + 1).validate(),
            Err(FileTransferError::InvalidGeometry)
        );
    }

    #[test]
    fn malicious_metadata_and_invalid_time_are_rejected() {
        for name in ["", ".", "..", "../secret", "a/b", "a\\b", " leading", "trailing "] {
            let mut value = manifest(1);
            value.display_name = name.to_string();
            assert_eq!(value.validate(), Err(FileTransferError::InvalidDisplayName));
        }
        let mut value = manifest(1);
        value.media_type = "not-a-type".to_string();
        assert_eq!(value.validate(), Err(FileTransferError::InvalidMediaType));
        let mut value = manifest(1);
        value.expires_at_ms = value.created_at_ms;
        assert_eq!(value.validate(), Err(FileTransferError::InvalidTimeWindow));
    }
}
