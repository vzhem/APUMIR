pub type CryptoResult<T> = Result<T, CryptoError>;

#[derive(Debug, thiserror::Error)]
pub enum CryptoError {
    #[error("key generation: {0}")]
    KeyGeneration(String),
    #[error("invalid key length: expected {expected}, got {actual}")]
    InvalidKeyLength { expected: usize, actual: usize },
    #[error("encryption: {0}")]
    Encryption(String),
    #[error("decryption: {0}")]
    Decryption(String),
    #[error("invalid signature")]
    InvalidSignature,
    #[error("serialization: {0}")]
    Serialization(String),
}

// Compile and execute the exact production source files without p2p-core's
// UniFFI build.rs or unrelated host-native dependencies.
#[path = "../../../rust-core/src/crypto/keys.rs"]
pub mod keys;
#[path = "../../../rust-core/src/crypto/referral.rs"]
pub mod referral;
