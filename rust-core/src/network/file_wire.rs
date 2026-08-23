//! Canonical binary boundary for the dedicated file data plane.
//!
//! This module is intentionally pure: it does not open sockets, mutate transfer state, touch the
//! message relay queue, or expose an FFI API. F4-B1 only defines bounded bytes that later slices can
//! authenticate and carry over a persistent peer connection.
//!
//! All integers are big-endian. Every frame is exactly:
//!
//! `magic[4] | version:u8 | type:u8 | flags:u16 | payload_len:u32 | payload`.
//!
//! A capability payload is fixed at 16 bytes. A chunk payload is:
//!
//! `transfer_id[16] | chunk_index:u64 | offset:u32 | encrypted_chunk_len:u32 |
//! range_len:u32 | ciphertext_range`.
//!
//! The 256-KiB frame ceiling is a memory-safety bound, not a cryptographic chunk size or a promised
//! transport window. A later scheduler may use smaller ranges and multiple frames per encrypted chunk.
//! Decoded capabilities are untrusted until F4-B2 binds their canonical bytes to an authenticated,
//! replay-protected control transcript.

use crate::crypto::file_transfer::{
    FILE_CHUNK_TAG_BYTES, FILE_TRANSFER_ID_BYTES, MAX_FILE_CHUNK_BYTES, MIN_FILE_CHUNK_BYTES,
};

pub const FILE_WIRE_MAGIC: [u8; 4] = *b"APUF";
pub const FILE_WIRE_VERSION_V1: u8 = 1;
pub const FILE_FRAME_HEADER_BYTES: usize = 12;
pub const MAX_FILE_FRAME_PAYLOAD_BYTES: usize = 256 * 1024;
pub const MAX_FILE_FRAME_BYTES: usize = FILE_FRAME_HEADER_BYTES + MAX_FILE_FRAME_PAYLOAD_BYTES;
pub const MAX_FILE_PARALLEL_STREAMS: u16 = 64;

pub const FEATURE_BINARY_CHUNK_FRAMES: u32 = 1 << 0;
pub const FEATURE_CHUNK_RANGE_FRAMES: u32 = 1 << 1;
pub const REQUIRED_FILE_FEATURES_V1: u32 = FEATURE_BINARY_CHUNK_FRAMES;
pub const KNOWN_FILE_FEATURES_V1: u32 =
    FEATURE_BINARY_CHUNK_FRAMES | FEATURE_CHUNK_RANGE_FRAMES;

const FRAME_TYPE_CAPABILITIES: u8 = 1;
const FRAME_TYPE_CHUNK_DATA: u8 = 2;
const CAPABILITIES_PAYLOAD_BYTES: usize = 16;
const CHUNK_INDEX_OFFSET: usize = FILE_TRANSFER_ID_BYTES;
const CHUNK_OFFSET_OFFSET: usize = CHUNK_INDEX_OFFSET + 8;
const CHUNK_LENGTH_OFFSET: usize = CHUNK_OFFSET_OFFSET + 4;
const CHUNK_DATA_LENGTH_OFFSET: usize = CHUNK_LENGTH_OFFSET + 4;
pub const FILE_CHUNK_DATA_PREFIX_BYTES: usize = CHUNK_DATA_LENGTH_OFFSET + 4;
const MIN_CHUNK_DATA_BYTES: usize = 1;
pub const MAX_FILE_CHUNK_DATA_BYTES: usize =
    MAX_FILE_FRAME_PAYLOAD_BYTES - FILE_CHUNK_DATA_PREFIX_BYTES;
const MIN_CIPHERTEXT_CHUNK_BYTES: usize = FILE_CHUNK_TAG_BYTES + 1;
const MAX_CIPHERTEXT_CHUNK_BYTES: usize = MAX_FILE_CHUNK_BYTES as usize + FILE_CHUNK_TAG_BYTES;
/// Maximum chunks required by any `u64` byte length at the minimum chunk size.
pub const MAX_FILE_CHUNK_COUNT: u64 =
    1 + ((u64::MAX - 1) / MIN_FILE_CHUNK_BYTES as u64);

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FileFrameV1 {
    Capabilities(FileCapabilitiesV1),
    ChunkData(FileChunkDataV1),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileCapabilitiesV1 {
    pub min_protocol_version: u8,
    pub max_protocol_version: u8,
    pub max_parallel_streams: u16,
    pub mandatory_features: u32,
    pub optional_features: u32,
    pub max_frame_payload_bytes: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NegotiatedFileCapabilitiesV1 {
    pub protocol_version: u8,
    pub enabled_features: u32,
    pub max_parallel_streams: u16,
    pub max_frame_payload_bytes: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileChunkDataV1 {
    pub transfer_id: [u8; FILE_TRANSFER_ID_BYTES],
    pub chunk_index: u64,
    /// Byte offset inside the complete encrypted chunk (ciphertext plus AEAD tag).
    pub chunk_offset: u32,
    /// Exact complete encrypted chunk length, repeated on every range frame.
    pub ciphertext_chunk_len: u32,
    /// A bounded binary range. It is never Base64-encoded by this codec.
    pub ciphertext: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum FileWireError {
    #[error("file frame header is truncated: {actual} bytes")]
    TruncatedHeader { actual: usize },
    #[error("invalid file frame magic")]
    InvalidMagic,
    #[error("unsupported file wire version: {got}")]
    UnsupportedVersion { got: u8 },
    #[error("unsupported file frame type: {got}")]
    UnsupportedFrameType { got: u8 },
    #[error("unsupported mandatory file frame flags: 0x{got:04x}")]
    UnsupportedFlags { got: u16 },
    #[error("file frame payload is too large: {size} bytes (max {max})")]
    PayloadTooLarge { size: usize, max: usize },
    #[error("file frame length arithmetic overflow")]
    LengthOverflow,
    #[error("file frame length mismatch: declared {declared} bytes, actual {actual} bytes")]
    LengthMismatch { declared: usize, actual: usize },
    #[error("invalid payload length for file frame type {frame_type}: {actual} bytes")]
    InvalidPayloadLength { frame_type: u8, actual: usize },
    #[error("invalid file capability: {reason}")]
    InvalidCapabilities { reason: &'static str },
    #[error("no common file wire version in peer range {min}..={max}")]
    NoCommonVersion { min: u8, max: u8 },
    #[error("unsupported mandatory file features: 0x{bits:08x}")]
    UnsupportedMandatoryFeatures { bits: u32 },
    #[error("required file features are unavailable: 0x{bits:08x}")]
    RequiredFeaturesUnavailable { bits: u32 },
    #[error("invalid file chunk data: {reason}")]
    InvalidChunkData { reason: &'static str },
}

impl FileCapabilitiesV1 {
    pub fn validate(&self) -> Result<(), FileWireError> {
        if self.min_protocol_version == 0
            || self.max_protocol_version < self.min_protocol_version
        {
            return Err(FileWireError::InvalidCapabilities {
                reason: "invalid protocol version range",
            });
        }
        if self.min_protocol_version > FILE_WIRE_VERSION_V1
            || self.max_protocol_version < FILE_WIRE_VERSION_V1
        {
            return Err(FileWireError::NoCommonVersion {
                min: self.min_protocol_version,
                max: self.max_protocol_version,
            });
        }
        if self.max_parallel_streams == 0
            || self.max_parallel_streams > MAX_FILE_PARALLEL_STREAMS
        {
            return Err(FileWireError::InvalidCapabilities {
                reason: "parallel stream limit is outside the protocol bounds",
            });
        }
        if self.mandatory_features & REQUIRED_FILE_FEATURES_V1
            != REQUIRED_FILE_FEATURES_V1
        {
            return Err(FileWireError::InvalidCapabilities {
                reason: "binary chunk frames are not mandatory",
            });
        }
        let unknown_mandatory = self.mandatory_features & !KNOWN_FILE_FEATURES_V1;
        if unknown_mandatory != 0 {
            return Err(FileWireError::UnsupportedMandatoryFeatures {
                bits: unknown_mandatory,
            });
        }
        if self.mandatory_features & self.optional_features != 0 {
            return Err(FileWireError::InvalidCapabilities {
                reason: "mandatory and optional feature sets overlap",
            });
        }
        let max_payload = self.max_frame_payload_bytes as usize;
        if max_payload < FILE_CHUNK_DATA_PREFIX_BYTES + MIN_CHUNK_DATA_BYTES
            || max_payload > MAX_FILE_FRAME_PAYLOAD_BYTES
        {
            return Err(FileWireError::InvalidCapabilities {
                reason: "frame payload limit is outside the protocol bounds",
            });
        }
        Ok(())
    }

    pub fn supported_features(&self) -> u32 {
        self.mandatory_features | self.optional_features
    }
}

impl FileChunkDataV1 {
    pub fn validate(&self) -> Result<(), FileWireError> {
        validate_chunk_parts(
            &self.transfer_id,
            self.chunk_index,
            self.chunk_offset,
            self.ciphertext_chunk_len,
            self.ciphertext.len(),
        )
    }
}

impl FileFrameV1 {
    pub fn encode(&self) -> Result<Vec<u8>, FileWireError> {
        let (frame_type, payload) = match self {
            Self::Capabilities(capabilities) => {
                capabilities.validate()?;
                let mut payload = Vec::with_capacity(CAPABILITIES_PAYLOAD_BYTES);
                payload.push(capabilities.min_protocol_version);
                payload.push(capabilities.max_protocol_version);
                payload.extend_from_slice(&capabilities.max_parallel_streams.to_be_bytes());
                payload.extend_from_slice(&capabilities.mandatory_features.to_be_bytes());
                payload.extend_from_slice(&capabilities.optional_features.to_be_bytes());
                payload.extend_from_slice(&capabilities.max_frame_payload_bytes.to_be_bytes());
                (FRAME_TYPE_CAPABILITIES, payload)
            }
            Self::ChunkData(chunk) => {
                chunk.validate()?;
                let data_len = u32::try_from(chunk.ciphertext.len())
                    .map_err(|_| FileWireError::PayloadTooLarge {
                        size: chunk.ciphertext.len(),
                        max: MAX_FILE_CHUNK_DATA_BYTES,
                    })?;
                let mut payload =
                    Vec::with_capacity(FILE_CHUNK_DATA_PREFIX_BYTES + chunk.ciphertext.len());
                payload.extend_from_slice(&chunk.transfer_id);
                payload.extend_from_slice(&chunk.chunk_index.to_be_bytes());
                payload.extend_from_slice(&chunk.chunk_offset.to_be_bytes());
                payload.extend_from_slice(&chunk.ciphertext_chunk_len.to_be_bytes());
                payload.extend_from_slice(&data_len.to_be_bytes());
                payload.extend_from_slice(&chunk.ciphertext);
                (FRAME_TYPE_CHUNK_DATA, payload)
            }
        };
        encode_frame(frame_type, &payload)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, FileWireError> {
        if bytes.len() < FILE_FRAME_HEADER_BYTES {
            return Err(FileWireError::TruncatedHeader {
                actual: bytes.len(),
            });
        }
        if &bytes[..FILE_WIRE_MAGIC.len()] != FILE_WIRE_MAGIC.as_slice() {
            return Err(FileWireError::InvalidMagic);
        }
        let version = bytes[4];
        if version != FILE_WIRE_VERSION_V1 {
            return Err(FileWireError::UnsupportedVersion { got: version });
        }
        let frame_type = bytes[5];
        let flags = u16::from_be_bytes([bytes[6], bytes[7]]);
        if flags != 0 {
            return Err(FileWireError::UnsupportedFlags { got: flags });
        }
        let declared_payload_len = usize::try_from(u32::from_be_bytes(
            bytes[8..12]
                .try_into()
                .map_err(|_| FileWireError::TruncatedHeader { actual: bytes.len() })?,
        ))
        .map_err(|_| FileWireError::LengthOverflow)?;
        let expected_len = FILE_FRAME_HEADER_BYTES
            .checked_add(declared_payload_len)
            .ok_or(FileWireError::LengthOverflow)?;
        if declared_payload_len > MAX_FILE_FRAME_PAYLOAD_BYTES {
            return Err(FileWireError::PayloadTooLarge {
                size: declared_payload_len,
                max: MAX_FILE_FRAME_PAYLOAD_BYTES,
            });
        }
        if bytes.len() != expected_len {
            return Err(FileWireError::LengthMismatch {
                declared: expected_len,
                actual: bytes.len(),
            });
        }
        let payload = &bytes[FILE_FRAME_HEADER_BYTES..];
        match frame_type {
            FRAME_TYPE_CAPABILITIES => decode_capabilities(payload),
            FRAME_TYPE_CHUNK_DATA => decode_chunk_data(payload),
            got => Err(FileWireError::UnsupportedFrameType { got }),
        }
    }
}

pub fn negotiate_file_capabilities(
    local: &FileCapabilitiesV1,
    peer: &FileCapabilitiesV1,
) -> Result<NegotiatedFileCapabilitiesV1, FileWireError> {
    local.validate()?;
    peer.validate()?;

    let common_min = local.min_protocol_version.max(peer.min_protocol_version);
    let common_max = local.max_protocol_version.min(peer.max_protocol_version);
    if common_min > common_max
        || common_min > FILE_WIRE_VERSION_V1
        || common_max < FILE_WIRE_VERSION_V1
    {
        return Err(FileWireError::NoCommonVersion {
            min: common_min,
            max: common_max,
        });
    }

    let local_supported = local.supported_features();
    let peer_supported = peer.supported_features();
    let unavailable = (local.mandatory_features & !peer_supported)
        | (peer.mandatory_features & !local_supported);
    if unavailable != 0 {
        return Err(FileWireError::RequiredFeaturesUnavailable { bits: unavailable });
    }

    Ok(NegotiatedFileCapabilitiesV1 {
        protocol_version: common_max.min(FILE_WIRE_VERSION_V1),
        enabled_features: local_supported & peer_supported & KNOWN_FILE_FEATURES_V1,
        max_parallel_streams: local.max_parallel_streams.min(peer.max_parallel_streams),
        max_frame_payload_bytes: local
            .max_frame_payload_bytes
            .min(peer.max_frame_payload_bytes),
    })
}

fn encode_frame(frame_type: u8, payload: &[u8]) -> Result<Vec<u8>, FileWireError> {
    if payload.len() > MAX_FILE_FRAME_PAYLOAD_BYTES {
        return Err(FileWireError::PayloadTooLarge {
            size: payload.len(),
            max: MAX_FILE_FRAME_PAYLOAD_BYTES,
        });
    }
    let payload_len = u32::try_from(payload.len()).map_err(|_| FileWireError::LengthOverflow)?;
    let frame_len = FILE_FRAME_HEADER_BYTES
        .checked_add(payload.len())
        .ok_or(FileWireError::LengthOverflow)?;
    let mut frame = Vec::with_capacity(frame_len);
    frame.extend_from_slice(&FILE_WIRE_MAGIC);
    frame.push(FILE_WIRE_VERSION_V1);
    frame.push(frame_type);
    frame.extend_from_slice(&0u16.to_be_bytes());
    frame.extend_from_slice(&payload_len.to_be_bytes());
    frame.extend_from_slice(payload);
    Ok(frame)
}

fn decode_capabilities(payload: &[u8]) -> Result<FileFrameV1, FileWireError> {
    if payload.len() != CAPABILITIES_PAYLOAD_BYTES {
        return Err(FileWireError::InvalidPayloadLength {
            frame_type: FRAME_TYPE_CAPABILITIES,
            actual: payload.len(),
        });
    }
    let capabilities = FileCapabilitiesV1 {
        min_protocol_version: payload[0],
        max_protocol_version: payload[1],
        max_parallel_streams: read_u16(payload, 2)?,
        mandatory_features: read_u32(payload, 4)?,
        optional_features: read_u32(payload, 8)?,
        max_frame_payload_bytes: read_u32(payload, 12)?,
    };
    capabilities.validate()?;
    Ok(FileFrameV1::Capabilities(capabilities))
}

fn decode_chunk_data(payload: &[u8]) -> Result<FileFrameV1, FileWireError> {
    if payload.len() < FILE_CHUNK_DATA_PREFIX_BYTES + MIN_CHUNK_DATA_BYTES {
        return Err(FileWireError::InvalidPayloadLength {
            frame_type: FRAME_TYPE_CHUNK_DATA,
            actual: payload.len(),
        });
    }
    let transfer_id = payload[..FILE_TRANSFER_ID_BYTES]
        .try_into()
        .map_err(|_| FileWireError::InvalidPayloadLength {
            frame_type: FRAME_TYPE_CHUNK_DATA,
            actual: payload.len(),
        })?;
    let chunk_index = read_u64(payload, CHUNK_INDEX_OFFSET)?;
    let chunk_offset = read_u32(payload, CHUNK_OFFSET_OFFSET)?;
    let ciphertext_chunk_len = read_u32(payload, CHUNK_LENGTH_OFFSET)?;
    let declared_data_len = usize::try_from(read_u32(payload, CHUNK_DATA_LENGTH_OFFSET)?)
        .map_err(|_| FileWireError::LengthOverflow)?;
    let actual_data_len = payload.len() - FILE_CHUNK_DATA_PREFIX_BYTES;
    if declared_data_len != actual_data_len {
        return Err(FileWireError::InvalidChunkData {
            reason: "declared range length does not match the frame payload",
        });
    }
    validate_chunk_parts(
        &transfer_id,
        chunk_index,
        chunk_offset,
        ciphertext_chunk_len,
        actual_data_len,
    )?;
    Ok(FileFrameV1::ChunkData(FileChunkDataV1 {
        transfer_id,
        chunk_index,
        chunk_offset,
        ciphertext_chunk_len,
        ciphertext: payload[FILE_CHUNK_DATA_PREFIX_BYTES..].to_vec(),
    }))
}

fn validate_chunk_parts(
    transfer_id: &[u8; FILE_TRANSFER_ID_BYTES],
    chunk_index: u64,
    chunk_offset: u32,
    ciphertext_chunk_len: u32,
    data_len: usize,
) -> Result<(), FileWireError> {
    if transfer_id.iter().all(|byte| *byte == 0) {
        return Err(FileWireError::InvalidChunkData {
            reason: "transfer ID is all zero",
        });
    }
    if chunk_index >= MAX_FILE_CHUNK_COUNT {
        return Err(FileWireError::InvalidChunkData {
            reason: "chunk index exceeds the protocol file geometry",
        });
    }
    let chunk_len =
        usize::try_from(ciphertext_chunk_len).map_err(|_| FileWireError::LengthOverflow)?;
    if !(MIN_CIPHERTEXT_CHUNK_BYTES..=MAX_CIPHERTEXT_CHUNK_BYTES).contains(&chunk_len) {
        return Err(FileWireError::InvalidChunkData {
            reason: "encrypted chunk length is outside the protocol bounds",
        });
    }
    if !(MIN_CHUNK_DATA_BYTES..=MAX_FILE_CHUNK_DATA_BYTES).contains(&data_len) {
        return Err(FileWireError::InvalidChunkData {
            reason: "frame range length is outside the protocol bounds",
        });
    }
    let range_end = usize::try_from(chunk_offset)
        .ok()
        .and_then(|offset| offset.checked_add(data_len))
        .ok_or(FileWireError::LengthOverflow)?;
    if range_end > chunk_len {
        return Err(FileWireError::InvalidChunkData {
            reason: "frame range exceeds the encrypted chunk length",
        });
    }
    Ok(())
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, FileWireError> {
    let end = offset.checked_add(2).ok_or(FileWireError::LengthOverflow)?;
    Ok(u16::from_be_bytes(
        bytes
            .get(offset..end)
            .ok_or(FileWireError::LengthOverflow)?
            .try_into()
            .map_err(|_| FileWireError::LengthOverflow)?,
    ))
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, FileWireError> {
    let end = offset.checked_add(4).ok_or(FileWireError::LengthOverflow)?;
    Ok(u32::from_be_bytes(
        bytes
            .get(offset..end)
            .ok_or(FileWireError::LengthOverflow)?
            .try_into()
            .map_err(|_| FileWireError::LengthOverflow)?,
    ))
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, FileWireError> {
    let end = offset.checked_add(8).ok_or(FileWireError::LengthOverflow)?;
    Ok(u64::from_be_bytes(
        bytes
            .get(offset..end)
            .ok_or(FileWireError::LengthOverflow)?
            .try_into()
            .map_err(|_| FileWireError::LengthOverflow)?,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn capabilities() -> FileCapabilitiesV1 {
        FileCapabilitiesV1 {
            min_protocol_version: FILE_WIRE_VERSION_V1,
            max_protocol_version: FILE_WIRE_VERSION_V1,
            max_parallel_streams: 4,
            mandatory_features: REQUIRED_FILE_FEATURES_V1,
            optional_features: FEATURE_CHUNK_RANGE_FRAMES,
            max_frame_payload_bytes: MAX_FILE_FRAME_PAYLOAD_BYTES as u32,
        }
    }

    fn chunk(data: Vec<u8>) -> FileChunkDataV1 {
        FileChunkDataV1 {
            transfer_id: [0x41; FILE_TRANSFER_ID_BYTES],
            chunk_index: 7,
            chunk_offset: 3,
            ciphertext_chunk_len: (data.len() + 3).max(MIN_CIPHERTEXT_CHUNK_BYTES) as u32,
            ciphertext: data,
        }
    }

    #[test]
    fn capabilities_are_canonical_and_negotiate_to_bounded_minima() {
        let frame = FileFrameV1::Capabilities(capabilities());
        let first = frame.encode().unwrap();
        let expected = vec![
            b'A', b'P', b'U', b'F', 1, 1, 0, 0, 0, 0, 0, 16, 1, 1, 0, 4, 0, 0, 0, 1, 0,
            0, 0, 2, 0, 4, 0, 0,
        ];
        assert_eq!(first, expected);
        assert_eq!(first, frame.encode().unwrap());
        assert_eq!(&first[..4], FILE_WIRE_MAGIC.as_slice());
        assert_eq!(first[4], FILE_WIRE_VERSION_V1);
        assert_eq!(first[5], FRAME_TYPE_CAPABILITIES);
        assert_eq!(&first[6..8], &[0, 0]);
        assert_eq!(u32::from_be_bytes(first[8..12].try_into().unwrap()), 16);
        assert_eq!(FileFrameV1::decode(&first).unwrap(), frame);
        assert_eq!(FileFrameV1::decode(&first).unwrap().encode().unwrap(), first);

        let local = capabilities();
        let mut peer = capabilities();
        peer.max_protocol_version = 2;
        peer.max_parallel_streams = 2;
        peer.max_frame_payload_bytes = 64 * 1024;
        let negotiated = negotiate_file_capabilities(&local, &peer).unwrap();
        assert_eq!(negotiated.protocol_version, FILE_WIRE_VERSION_V1);
        assert_eq!(negotiated.max_parallel_streams, 2);
        assert_eq!(negotiated.max_frame_payload_bytes, 64 * 1024);
        assert_eq!(
            negotiated.enabled_features,
            FEATURE_BINARY_CHUNK_FRAMES | FEATURE_CHUNK_RANGE_FRAMES
        );
    }

    #[test]
    fn binary_chunk_round_trip_is_canonical_without_text_encoding() {
        let value = FileFrameV1::ChunkData(chunk(vec![0, b'|', b'\n', 0xff, 0x80]));
        let bytes = value.encode().unwrap();
        assert_eq!(bytes[5], FRAME_TYPE_CHUNK_DATA);
        assert_eq!(FileFrameV1::decode(&bytes).unwrap(), value);
        assert_eq!(FileFrameV1::decode(&bytes).unwrap().encode().unwrap(), bytes);
        assert!(!bytes.windows(b"apu-file1".len()).any(|part| part == b"apu-file1"));
    }

    #[test]
    fn every_truncation_and_trailing_byte_is_rejected() {
        for frame in [
            FileFrameV1::Capabilities(capabilities()),
            FileFrameV1::ChunkData(chunk(vec![1, 2, 3, 4])),
        ] {
            let bytes = frame.encode().unwrap();
            for length in 0..bytes.len() {
                assert!(FileFrameV1::decode(&bytes[..length]).is_err(), "length={length}");
            }
            let mut trailing = bytes;
            trailing.push(0);
            assert!(matches!(
                FileFrameV1::decode(&trailing),
                Err(FileWireError::LengthMismatch { .. })
            ));
        }
    }

    #[test]
    fn magic_version_type_and_flags_fail_closed() {
        let original = FileFrameV1::Capabilities(capabilities()).encode().unwrap();

        let mut bytes = original.clone();
        bytes[0] ^= 1;
        assert_eq!(FileFrameV1::decode(&bytes), Err(FileWireError::InvalidMagic));

        for version in [0, FILE_WIRE_VERSION_V1 + 1] {
            let mut bytes = original.clone();
            bytes[4] = version;
            assert_eq!(
                FileFrameV1::decode(&bytes),
                Err(FileWireError::UnsupportedVersion { got: version })
            );
        }

        let mut bytes = original.clone();
        bytes[5] = 0xff;
        assert_eq!(
            FileFrameV1::decode(&bytes),
            Err(FileWireError::UnsupportedFrameType { got: 0xff })
        );

        let mut bytes = original;
        bytes[7] = 1;
        assert_eq!(
            FileFrameV1::decode(&bytes),
            Err(FileWireError::UnsupportedFlags { got: 1 })
        );
    }

    #[test]
    fn declared_length_overflow_oversize_and_mismatch_are_rejected_before_payload_decode() {
        let original = FileFrameV1::Capabilities(capabilities()).encode().unwrap();

        let mut bytes = original.clone();
        bytes[8..12].copy_from_slice(&u32::MAX.to_be_bytes());
        assert_eq!(
            FileFrameV1::decode(&bytes),
            Err(FileWireError::PayloadTooLarge {
                size: u32::MAX as usize,
                max: MAX_FILE_FRAME_PAYLOAD_BYTES,
            })
        );

        for declared in [15u32, 17u32] {
            let mut bytes = original.clone();
            bytes[8..12].copy_from_slice(&declared.to_be_bytes());
            assert!(matches!(
                FileFrameV1::decode(&bytes),
                Err(FileWireError::LengthMismatch { .. })
            ));
        }

        let mut exact_but_invalid = original;
        exact_but_invalid.pop();
        exact_but_invalid[8..12].copy_from_slice(&15u32.to_be_bytes());
        assert_eq!(
            FileFrameV1::decode(&exact_but_invalid),
            Err(FileWireError::InvalidPayloadLength {
                frame_type: FRAME_TYPE_CAPABILITIES,
                actual: 15,
            })
        );
    }

    #[test]
    fn exact_minimum_and_maximum_frames_round_trip_and_one_more_byte_is_rejected() {
        let minimum = FileFrameV1::ChunkData(FileChunkDataV1 {
            transfer_id: [0x51; FILE_TRANSFER_ID_BYTES],
            chunk_index: 0,
            chunk_offset: FILE_CHUNK_TAG_BYTES as u32,
            ciphertext_chunk_len: MIN_CIPHERTEXT_CHUNK_BYTES as u32,
            ciphertext: vec![0x01],
        });
        let minimum_bytes = minimum.encode().unwrap();
        assert_eq!(FileFrameV1::decode(&minimum_bytes).unwrap(), minimum);

        let total = MAX_CIPHERTEXT_CHUNK_BYTES;
        let data = vec![0x5a; MAX_FILE_CHUNK_DATA_BYTES];
        let value = FileFrameV1::ChunkData(FileChunkDataV1 {
            transfer_id: [0x52; FILE_TRANSFER_ID_BYTES],
            chunk_index: MAX_FILE_CHUNK_COUNT - 1,
            chunk_offset: (total - data.len()) as u32,
            ciphertext_chunk_len: total as u32,
            ciphertext: data,
        });
        let bytes = value.encode().unwrap();
        assert_eq!(bytes.len(), MAX_FILE_FRAME_BYTES);
        assert_eq!(FileFrameV1::decode(&bytes).unwrap(), value);

        let oversized = FileFrameV1::ChunkData(FileChunkDataV1 {
            transfer_id: [0x52; FILE_TRANSFER_ID_BYTES],
            chunk_index: 0,
            chunk_offset: 0,
            ciphertext_chunk_len: (MAX_FILE_CHUNK_DATA_BYTES + 1) as u32,
            ciphertext: vec![0; MAX_FILE_CHUNK_DATA_BYTES + 1],
        });
        assert!(matches!(
            oversized.encode(),
            Err(FileWireError::InvalidChunkData { .. })
        ));
    }

    #[test]
    fn invalid_chunk_identity_geometry_and_ranges_are_rejected() {
        let mut value = chunk(vec![1]);
        value.transfer_id = [0; FILE_TRANSFER_ID_BYTES];
        assert!(matches!(
            value.validate(),
            Err(FileWireError::InvalidChunkData { .. })
        ));

        let mut value = chunk(vec![1]);
        value.chunk_index = MAX_FILE_CHUNK_COUNT;
        assert!(matches!(
            value.validate(),
            Err(FileWireError::InvalidChunkData { .. })
        ));

        let mut value = chunk(vec![1]);
        value.ciphertext_chunk_len = FILE_CHUNK_TAG_BYTES as u32;
        assert!(matches!(
            value.validate(),
            Err(FileWireError::InvalidChunkData { .. })
        ));

        let mut value = chunk(vec![1]);
        value.ciphertext_chunk_len = MAX_CIPHERTEXT_CHUNK_BYTES as u32 + 1;
        assert!(matches!(
            value.validate(),
            Err(FileWireError::InvalidChunkData { .. })
        ));

        let mut value = chunk(Vec::new());
        value.ciphertext_chunk_len = MIN_CIPHERTEXT_CHUNK_BYTES as u32;
        assert!(matches!(
            value.validate(),
            Err(FileWireError::InvalidChunkData { .. })
        ));

        let mut value = chunk(vec![1, 2]);
        value.chunk_offset = u32::MAX;
        value.ciphertext_chunk_len = MAX_CIPHERTEXT_CHUNK_BYTES as u32;
        assert!(matches!(
            value.validate(),
            Err(FileWireError::InvalidChunkData { .. }) | Err(FileWireError::LengthOverflow)
        ));
    }

    #[test]
    fn inner_chunk_range_length_must_match_outer_payload_exactly() {
        let mut bytes = FileFrameV1::ChunkData(chunk(vec![1, 2, 3])).encode().unwrap();
        let inner_length_offset = FILE_FRAME_HEADER_BYTES + CHUNK_DATA_LENGTH_OFFSET;
        bytes[inner_length_offset..inner_length_offset + 4].copy_from_slice(&4u32.to_be_bytes());
        assert!(matches!(
            FileFrameV1::decode(&bytes),
            Err(FileWireError::InvalidChunkData { .. })
        ));
    }

    #[test]
    fn capability_downgrade_unknown_mandatory_and_invalid_limits_are_rejected() {
        let original = FileFrameV1::Capabilities(capabilities()).encode().unwrap();
        let mut downgraded = original.clone();
        downgraded[FILE_FRAME_HEADER_BYTES] = 2;
        downgraded[FILE_FRAME_HEADER_BYTES + 1] = 2;
        assert_eq!(
            FileFrameV1::decode(&downgraded),
            Err(FileWireError::NoCommonVersion { min: 2, max: 2 })
        );

        let mut unknown_mandatory = original;
        let feature_offset = FILE_FRAME_HEADER_BYTES + 4;
        unknown_mandatory[feature_offset..feature_offset + 4]
            .copy_from_slice(&(REQUIRED_FILE_FEATURES_V1 | (1 << 31)).to_be_bytes());
        assert_eq!(
            FileFrameV1::decode(&unknown_mandatory),
            Err(FileWireError::UnsupportedMandatoryFeatures { bits: 1 << 31 })
        );

        let mut value = capabilities();
        value.min_protocol_version = 0;
        assert!(matches!(
            value.validate(),
            Err(FileWireError::InvalidCapabilities { .. })
        ));

        let mut value = capabilities();
        value.min_protocol_version = 2;
        value.max_protocol_version = 2;
        assert_eq!(
            value.validate(),
            Err(FileWireError::NoCommonVersion { min: 2, max: 2 })
        );

        let mut value = capabilities();
        value.mandatory_features = 0;
        assert!(matches!(
            value.validate(),
            Err(FileWireError::InvalidCapabilities { .. })
        ));

        let mut value = capabilities();
        value.mandatory_features |= 1 << 31;
        assert_eq!(
            value.validate(),
            Err(FileWireError::UnsupportedMandatoryFeatures { bits: 1 << 31 })
        );

        for streams in [0, MAX_FILE_PARALLEL_STREAMS + 1] {
            let mut value = capabilities();
            value.max_parallel_streams = streams;
            assert!(matches!(
                value.validate(),
                Err(FileWireError::InvalidCapabilities { .. })
            ));
        }

        for payload_limit in [
            FILE_CHUNK_DATA_PREFIX_BYTES as u32,
            MAX_FILE_FRAME_PAYLOAD_BYTES as u32 + 1,
        ] {
            let mut value = capabilities();
            value.max_frame_payload_bytes = payload_limit;
            assert!(matches!(
                value.validate(),
                Err(FileWireError::InvalidCapabilities { .. })
            ));
        }
    }

    #[test]
    fn unknown_optional_features_survive_but_required_features_must_be_shared() {
        let mut optional_future = capabilities();
        optional_future.optional_features |= 1 << 31;
        let bytes = FileFrameV1::Capabilities(optional_future.clone())
            .encode()
            .unwrap();
        assert_eq!(
            FileFrameV1::decode(&bytes).unwrap(),
            FileFrameV1::Capabilities(optional_future.clone())
        );
        let negotiated =
            negotiate_file_capabilities(&optional_future, &optional_future).unwrap();
        assert_eq!(negotiated.enabled_features & (1 << 31), 0);

        let mut overlap = capabilities();
        overlap.optional_features |= FEATURE_BINARY_CHUNK_FRAMES;
        assert!(matches!(
            overlap.validate(),
            Err(FileWireError::InvalidCapabilities { .. })
        ));

        let mut local = capabilities();
        local.mandatory_features |= FEATURE_CHUNK_RANGE_FRAMES;
        local.optional_features = 0;
        let mut peer = capabilities();
        peer.optional_features = 0;
        assert_eq!(
            negotiate_file_capabilities(&local, &peer),
            Err(FileWireError::RequiredFeaturesUnavailable {
                bits: FEATURE_CHUNK_RANGE_FRAMES,
            })
        );
    }

    #[test]
    fn legacy_text_packet_is_not_accepted_as_binary_file_frame() {
        assert_eq!(
            FileFrameV1::decode(b"apu-file1|QUJDREVGRw=="),
            Err(FileWireError::InvalidMagic)
        );
    }
}
