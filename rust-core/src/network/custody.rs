//! # Custody — зашифрованное постоянное хранилище relay-очереди (этап M8)
//!
//! В `v11.16.16` relay-очередь («custody» — сообщения, которые наш узел хранит
//! для офлайн-получателей) жила только в памяти. Если Android убивал процесс
//! или телефон перезагружался, посредник терял недоставленные сообщения.
//!
//! Этот модуль добавляет постоянное хранилище custody:
//!
//! - **Сохранение перед «сном»** — `save_custody` шифрует очередь и атомарно
//!   записывает её в файл.
//! - **Восстановление после process death/reboot** — `load_custody` читает,
//!   расшифровывает и возвращает снапшоты очереди.
//! - **TTL не сбрасывается** — снапшоты хранят абсолютный wall-clock deadline
//!   (Unix ms), поэтому после перезапуска оставшееся время жизни сохраняется.
//!
//! ## Формат файла:
//!
//! ```text
//! ┌────────────┬─────────────┬───────────────────────────────┐
//! │ Magic (4)  │ Version (1) │ EncryptedMessage (nonce + ct) │
//! └────────────┴─────────────┴───────────────────────────────┘
//! ```
//!
//! - **Magic** — сигнатура `"P2RC"` для распознавания формата;
//! - **Version** — версия формата (сейчас `1`);
//! - **EncryptedMessage** — `bincode(Vec<QueuedMessageSnapshot>)`, зашифрованный
//!   ChaCha20-Poly1305; MAGIC + Version добавляются как AAD.
//!
//! ## Ключ шифрования
//!
//! Ключ выводится детерминированно (HKDF) из секретного ключа узла, поэтому
//! он одинаков между перезапусками одного устройства, но разный у разных узлов.
//! Секретный ключ никогда не записывается в файл custody.

use std::path::Path;

use crate::crypto::cipher::{Cipher, EncryptedMessage, SymmetricKey};
use crate::crypto::kdf::derive_key;
use crate::network::message_queue::QueuedMessageSnapshot;

// ═══════════════════════════════════════════════════════════════════
// КОНСТАНТЫ
// ═══════════════════════════════════════════════════════════════════

/// Магический префикс файла custody.
const MAGIC: &[u8; 4] = b"P2RC";

/// Версия формата файла custody.
const FORMAT_VERSION: u8 = 1;

/// Info-контекст HKDF для вывода ключа custody из секрета узла.
pub const CUSTODY_KEY_INFO: &[u8] = b"p2p-messenger-relay-custody-v1";

// ═══════════════════════════════════════════════════════════════════
// ОШИБКИ
// ═══════════════════════════════════════════════════════════════════

#[derive(Debug, thiserror::Error)]
pub enum CustodyError {
    #[error("Ошибка ввода-вывода: {0}")]
    Io(#[from] std::io::Error),

    #[error("Ошибка сериализации: {0}")]
    Serialization(String),

    #[error("Ошибка вывода ключа: {0}")]
    KeyDerivation(String),

    #[error("Неверный формат файла custody")]
    InvalidFormat,

    #[error("Не удалось расшифровать custody (неверный ключ или файл повреждён)")]
    DecryptionFailed,
}

pub type CustodyResult<T> = Result<T, CustodyError>;

// ═══════════════════════════════════════════════════════════════════
// КЛЮЧ
// ═══════════════════════════════════════════════════════════════════

/// Вывести детерминированный ключ custody из секрета узла.
///
/// Один и тот же секрет → один и тот же ключ (переживает перезапуск).
/// Разные узлы → разные ключи (custody одного узла нельзя прочитать другим).
pub fn derive_custody_key(secret: &[u8], public: &[u8]) -> CustodyResult<[u8; 32]> {
    let bytes = derive_key(secret, public, CUSTODY_KEY_INFO, 32)
        .map_err(|e| CustodyError::KeyDerivation(e.to_string()))?;
    let mut key = [0u8; 32];
    key.copy_from_slice(&bytes);
    Ok(key)
}

// ═══════════════════════════════════════════════════════════════════
// СОХРАНЕНИЕ / ЗАГРУЗКА
// ═══════════════════════════════════════════════════════════════════

/// AAD для AEAD: MAGIC + версия, чтобы подделка заголовка обнаруживалась.
fn aad() -> [u8; 5] {
    [MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3], FORMAT_VERSION]
}

/// Зашифровать и сохранить снапшоты custody в файл (атомарная запись).
pub fn save_custody(
    path: &Path,
    key: &[u8; 32],
    snapshots: &[QueuedMessageSnapshot],
) -> CustodyResult<()> {
    let plaintext = bincode::serialize(snapshots)
        .map_err(|e| CustodyError::Serialization(e.to_string()))?;

    let symmetric = SymmetricKey::from_bytes(key)
        .map_err(|e| CustodyError::Serialization(e.to_string()))?;
    let cipher = Cipher::new(&symmetric);
    let encrypted = cipher
        .encrypt_with_aad(&plaintext, &aad())
        .map_err(|_| CustodyError::DecryptionFailed)?;

    let mut output = Vec::with_capacity(4 + 1 + encrypted.to_bytes().len());
    output.extend_from_slice(MAGIC);
    output.push(FORMAT_VERSION);
    output.extend_from_slice(&encrypted.to_bytes());

    // Атомарная запись: сначала во временный файл, затем rename.
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, &output)?;
    std::fs::rename(&tmp, path)?;
    Ok(())
}

/// Загрузить и расшифровать custody из файла.
///
/// Если файл не существует — возвращается `Ok(Vec::new())` (чистый запуск).
pub fn load_custody(path: &Path, key: &[u8; 32]) -> CustodyResult<Vec<QueuedMessageSnapshot>> {
    let data = match std::fs::read(path) {
        Ok(d) => d,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => return Err(CustodyError::Io(e)),
    };

    // MAGIC(4) + VERSION(1) + NONCE(12) + TAG(16) = 33 байта минимум.
    if data.len() < 4 + 1 + 12 + 16 {
        return Err(CustodyError::InvalidFormat);
    }
    if &data[..4] != MAGIC {
        return Err(CustodyError::InvalidFormat);
    }
    if data[4] != FORMAT_VERSION {
        return Err(CustodyError::InvalidFormat);
    }

    let symmetric = SymmetricKey::from_bytes(key)
        .map_err(|e| CustodyError::Serialization(e.to_string()))?;
    let cipher = Cipher::new(&symmetric);

    let encrypted = EncryptedMessage::from_bytes(&data[5..])
        .map_err(|_| CustodyError::DecryptionFailed)?;
    let plaintext = cipher
        .decrypt_with_aad(&encrypted, &aad())
        .map_err(|_| CustodyError::DecryptionFailed)?;

    bincode::deserialize(&plaintext).map_err(|e| CustodyError::Serialization(e.to_string()))
}

// ═══════════════════════════════════════════════════════════════════
// ТЕСТЫ
// ═══════════════════════════════════════════════════════════════════

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn make_snapshot(byte: u8, expires_at_ms: i64) -> QueuedMessageSnapshot {
        QueuedMessageSnapshot {
            msg_id: [byte; 16],
            recipient: [0x42; 32],
            payload: vec![0xAA; 16],
            queued_at_ms: 1_000_000,
            expires_at_ms,
            retry_count: 2,
        }
    }

    fn temp_path(name: &str) -> PathBuf {
        let mut p = std::env::temp_dir();
        p.push(format!(
            "apumir_custody_test_{}_{}",
            std::process::id(),
            name
        ));
        p
    }

    fn key(byte: u8) -> [u8; 32] {
        [byte; 32]
    }

    #[test]
    fn test_derive_custody_key_deterministic() {
        let k1 = derive_custody_key(b"secret", b"public").unwrap();
        let k2 = derive_custody_key(b"secret", b"public").unwrap();
        assert_eq!(k1, k2);
    }

    #[test]
    fn test_derive_custody_key_different_secret() {
        let k1 = derive_custody_key(b"secret-a", b"public").unwrap();
        let k2 = derive_custody_key(b"secret-b", b"public").unwrap();
        assert_ne!(k1, k2);
    }

    #[test]
    fn test_derive_custody_key_different_public() {
        let k1 = derive_custody_key(b"secret", b"public-a").unwrap();
        let k2 = derive_custody_key(b"secret", b"public-b").unwrap();
        assert_ne!(k1, k2);
    }

    #[test]
    fn test_save_and_load_roundtrip() {
        let path = temp_path("roundtrip.bin");
        let snapshots = vec![
            make_snapshot(0x01, 2_000_000),
            make_snapshot(0x02, 3_000_000),
        ];

        save_custody(&path, &key(0xAB), &snapshots).unwrap();
        let loaded = load_custody(&path, &key(0xAB)).unwrap();

        assert_eq!(loaded, snapshots);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn test_load_empty_snapshot_list() {
        let path = temp_path("empty.bin");
        save_custody(&path, &key(0x01), &[]).unwrap();
        let loaded = load_custody(&path, &key(0x01)).unwrap();
        assert!(loaded.is_empty());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn test_load_missing_file_returns_empty() {
        let path = temp_path("does_not_exist.bin");
        let loaded = load_custody(&path, &key(0x01)).unwrap();
        assert!(loaded.is_empty());
    }

    #[test]
    fn test_load_wrong_key_fails() {
        let path = temp_path("wrong_key.bin");
        save_custody(&path, &key(0x11), &[make_snapshot(0x01, 2_000_000)]).unwrap();

        let result = load_custody(&path, &key(0x22));
        assert!(matches!(result, Err(CustodyError::DecryptionFailed)));
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn test_tampered_file_fails() {
        let path = temp_path("tampered.bin");
        save_custody(&path, &key(0x11), &[make_snapshot(0x01, 2_000_000)]).unwrap();

        let mut data = std::fs::read(&path).unwrap();
        let idx = data.len() - 1;
        data[idx] ^= 0xFF;
        std::fs::write(&path, &data).unwrap();

        let result = load_custody(&path, &key(0x11));
        assert!(matches!(result, Err(CustodyError::DecryptionFailed)));
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn test_invalid_magic_rejected() {
        let path = temp_path("bad_magic.bin");
        std::fs::write(&path, vec![0u8; 64]).unwrap();

        let result = load_custody(&path, &key(0x11));
        assert!(matches!(result, Err(CustodyError::InvalidFormat)));
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn test_truncated_file_rejected() {
        let path = temp_path("truncated.bin");
        std::fs::write(&path, vec![0u8; 10]).unwrap();

        let result = load_custody(&path, &key(0x11));
        assert!(matches!(result, Err(CustodyError::InvalidFormat)));
        let _ = std::fs::remove_file(&path);
    }
}
